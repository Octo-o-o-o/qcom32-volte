package org.octolab.qcom32ims;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java call-session & MMTEL routing state machine bridging QCRIL imsIF DriverCall
 * events to Android 11 ImsCallSessionImplBase / MmTelFeature semantics.
 *
 * Verified against stock Passport org.codeaurora.ims.ImsCallSessionImpl,
 * org.codeaurora.ims.ImsSenderRxr, org.codeaurora.ims.ImsServiceClassTracker,
 * and LineageOS 18.1 com.android.ims.ImsCall / ImsPhoneConnection.
 */
public final class CallStateEngine {

    // Mirrors ImsCallSessionImplBase.State constants without importing android.*
    public static final int SESSION_STATE_INVALID = -1;
    public static final int SESSION_STATE_IDLE = 0;
    public static final int SESSION_STATE_INITIATED = 1;
    public static final int SESSION_STATE_NEGOTIATING = 2;
    public static final int SESSION_STATE_ESTABLISHING = 3;
    public static final int SESSION_STATE_ESTABLISHED = 4;
    public static final int SESSION_STATE_RENEGOTIATING = 5;
    public static final int SESSION_STATE_REESTABLISHING = 6;
    public static final int SESSION_STATE_TERMINATING = 7;
    public static final int SESSION_STATE_TERMINATED = 8;

    // Mirrors MmTelFeature.PROCESS_CALL_IMS / PROCESS_CALL_CSFB
    public static final int PROCESS_CALL_IMS = 0;
    public static final int PROCESS_CALL_CSFB = 1;

    private static final Set<String> KNOWN_EMERGENCY_NUMBERS = new HashSet<>(Arrays.asList(
            "110", "112", "119", "120", "122", "911", "999", "000", "08", "118"
    ));

    public interface SessionCallbacks {
        void onCallProfileUpdated(
                String number,
                int numberPresentation,
                String name,
                int namePresentation,
                int audioQuality,
                int audioDirection,
                boolean isMultiparty);
        void onProgressing(int audioQuality, int audioDirection);
        void onInitiated(int audioQuality); // Maps to ImsCallSessionListener.callSessionInitiated (= callSessionStarted)
        void onInitiatedFailed(int imsReasonCode, int vendorExtraCode, String message);
        void onTerminated(int imsReasonCode, int vendorExtraCode, String message);
        void onHeld();
        void onHoldFailed(int imsReasonCode, int vendorExtraCode, String message);
        void onHoldReceived();
        void onResumed();
        void onResumeFailed(int imsReasonCode, int vendorExtraCode, String message);
        void onResumeReceived();
        void onMultipartyStateChanged(boolean isMultiparty);
        void onSuppServiceReceived(RilImsWire.SuppSvcInfo info);
    }

    public interface IncomingCallSink {
        void onIncomingCallCreated(SessionHandle handle, boolean isCallWaiting);
    }

    public final class SessionHandle {
        private final String initialId;
        private volatile long startedAtMs;
        private volatile int callIndex; // 0 while pending QMI_VOICE allocation, 1..255 once bound
        private volatile int sessionState = SESSION_STATE_IDLE;
        private volatile Integer lastDriverState;
        private volatile boolean isMt;
        private volatile boolean isMpty;
        private volatile boolean driverMetadataApplied;
        private volatile int audioQuality = RilImsWire.AUDIO_QUALITY_AMR_WB;
        private volatile boolean playLocalRingback;
        private volatile boolean locallyTerminating;
        private volatile boolean locallyRejected;
        private volatile boolean cancelPendingOnBind;
        private volatile boolean terminalNotified;
        private volatile String peerNumber = "";
        private volatile int numberPresentation = RilImsWire.RIL_CLIP_ALLOWED;
        private volatile String peerName = "";
        private volatile int namePresentation = RilImsWire.RIL_CLIP_ALLOWED;
        private volatile SessionCallbacks callbacks;

        private SessionHandle(String initialId, boolean isMt) {
            this.initialId = initialId;
            this.isMt = isMt;
        }

        public void setCallbacks(SessionCallbacks callbacks) {
            this.callbacks = callbacks;
        }

        public String getCallId() {
            int idx = callIndex;
            return idx > 0 ? Integer.toString(idx) : initialId;
        }

        public int getCallIndex() {
            return callIndex;
        }

        public int getSessionState() {
            return sessionState;
        }

        public boolean isIncoming() {
            return isMt;
        }

        public boolean hasDriverMetadata() {
            return driverMetadataApplied;
        }

        public boolean isInCall() {
            Integer ds = lastDriverState;
            if (ds == null || sessionState == SESSION_STATE_TERMINATED || sessionState == SESSION_STATE_INVALID) {
                return false;
            }
            return ds != RilImsWire.CALL_STATE_END;
        }

        public boolean isMultiparty() {
            return isMpty;
        }

        public String getPeerNumber() {
            return peerNumber;
        }

        public int getNumberPresentation() {
            return numberPresentation;
        }

        public int getNumberOir() {
            return RilImsWire.clipToOir(numberPresentation, peerNumber, true);
        }

        public String getPeerName() {
            return peerName;
        }

        public int getNamePresentation() {
            return namePresentation;
        }

        public int getNameOir() {
            return RilImsWire.clipToOir(namePresentation, peerName, false);
        }

        public int getAudioQuality() {
            return audioQuality;
        }

        public void startOutgoing(String rawCallee, int clirMode, int callDomain) {
            if (sessionState != SESSION_STATE_IDLE) return;
            String normalized = RilImsWire.normalizeDialAddress(rawCallee);
            if (normalized.isEmpty()) {
                sessionState = SESSION_STATE_TERMINATED;
                terminalNotified = true;
                SessionCallbacks cb = callbacks;
                if (cb != null) {
                    cb.onInitiatedFailed(RilImsWire.IMS_REASON_LOCAL_ILLEGAL_ARGUMENT, 0, "empty_callee");
                }
                return;
            }
            this.peerNumber = normalized;
            this.startedAtMs = System.currentTimeMillis();
            this.sessionState = SESSION_STATE_INITIATED;
            synchronized (lock) {
                if (!pendingMoSessions.contains(this)) {
                    pendingMoSessions.add(this);
                }
            }
            rilClient.sendDial(normalized, clirMode, callDomain, resp -> {
                if (resp.error != 0) {
                    handleDialCommandFailure(this, resp.error);
                }
            });
        }

        public void acceptIncoming() {
            if (sessionState == SESSION_STATE_TERMINATED || sessionState == SESSION_STATE_INVALID) return;
            sessionState = SESSION_STATE_ESTABLISHING;
            rilClient.sendAnswer(resp -> {
                if (resp.error != 0) {
                    terminateSessionWithFailCauseLookup(this, resp.error, false, false);
                }
            });
        }

        public void rejectIncoming(int imsReason) {
            if (sessionState == SESSION_STATE_TERMINATED || sessionState == SESSION_STATE_INVALID) return;
            locallyRejected = true;
            locallyTerminating = true;
            sessionState = SESSION_STATE_TERMINATING;
            int idx = callIndex;
            int vendorReject = (imsReason == RilImsWire.IMS_REASON_SIP_BUSY)
                    ? RilImsWire.CALL_FAIL_USER_BUSY
                    : RilImsWire.CALL_FAIL_USER_REJECT;
            if (idx > 0) {
                synchronized (lock) {
                    rejectedCallIndices.put(idx, this);
                }
                rilClient.sendHangup(idx, true, vendorReject, resp -> {
                    if (resp.error != 0) {
                        synchronized (lock) {
                            rejectedCallIndices.remove(idx, this);
                        }
                    }
                    notifyRejectComplete(this);
                });
            } else {
                notifyRejectComplete(this);
            }
        }

        public void terminate(int imsReason) {
            if (sessionState == SESSION_STATE_TERMINATED || sessionState == SESSION_STATE_INVALID) return;
            locallyTerminating = true;
            sessionState = SESSION_STATE_TERMINATING;
            int idx = callIndex;
            if (idx > 0) {
                rilClient.sendHangup(idx, false, 0, resp -> {
                    // Actual termination callback fires when GET_CURRENT_CALLS sees END or call removal.
                });
            } else {
                cancelPendingOnBind = true;
                rilClient.sendHangupForegroundResumeBackground(resp -> {
                    synchronized (lock) {
                        pendingMoSessions.remove(this);
                    }
                    notifyTerminatedOnce(this, RilImsWire.IMS_REASON_USER_TERMINATED, 0, "cancelled_before_bind");
                });
            }
        }

        public void hold() {
            int idx = callIndex;
            if (idx <= 0 || sessionState != SESSION_STATE_ESTABLISHED) {
                SessionCallbacks cb = callbacks;
                if (cb != null) {
                    cb.onHoldFailed(RilImsWire.IMS_REASON_LOCAL_INTERNAL_ERROR, 0, "not_established");
                }
                return;
            }
            rilClient.sendHold(idx, resp -> {
                if (resp.error != 0) {
                    SessionCallbacks cb = callbacks;
                    if (cb != null) {
                        cb.onHoldFailed(RilImsWire.IMS_REASON_LOCAL_INTERNAL_ERROR, resp.error, "hold_ril_err");
                    }
                }
            });
        }

        public void resume() {
            int idx = callIndex;
            if (idx <= 0) {
                SessionCallbacks cb = callbacks;
                if (cb != null) {
                    cb.onResumeFailed(RilImsWire.IMS_REASON_LOCAL_INTERNAL_ERROR, 0, "no_call_index");
                }
                return;
            }
            rilClient.sendResume(idx, resp -> {
                if (resp.error != 0) {
                    SessionCallbacks cb = callbacks;
                    if (cb != null) {
                        cb.onResumeFailed(RilImsWire.IMS_REASON_LOCAL_INTERNAL_ERROR, resp.error, "resume_ril_err");
                    }
                }
            });
        }

        public void sendDtmf(char digit, Runnable onComplete) {
            rilClient.sendDtmf(digit, false, resp -> {
                if (onComplete != null) onComplete.run();
            });
        }

        public void startDtmf(char digit) {
            rilClient.sendDtmf(digit, true, null);
        }

        public void stopDtmf() {
            rilClient.sendStopDtmf(null);
        }

        public void close() {
            synchronized (lock) {
                pendingMoSessions.remove(this);
                if (callIndex > 0) {
                    SessionHandle cur = boundSessions.get(callIndex);
                    if (cur == this) {
                        boundSessions.remove(callIndex);
                    }
                }
            }
            sessionState = SESSION_STATE_INVALID;
            callbacks = null;
        }
    }

    private final Object lock = new Object();
    private final RilImsCoreClient rilClient;
    private final IncomingCallSink incomingSink;
    private final AtomicInteger pendingSeq = new AtomicInteger(1);
    private final List<SessionHandle> pendingMoSessions = new ArrayList<>();
    private final Map<Integer, SessionHandle> boundSessions = new HashMap<>();
    private final Map<Integer, SessionHandle> rejectedCallIndices = new HashMap<>();

    private volatile boolean frameworkEnabled = true;
    private volatile boolean voiceCapabilityConfigured = true;
    private volatile int regState = RilImsWire.REG_STATE_NOT_REGISTERED;
    private volatile boolean modemVoiceEnabledOnLte;
    private volatile boolean playLocalRingbackFlag;

    public CallStateEngine(RilImsCoreClient rilClient, IncomingCallSink incomingSink) {
        this.rilClient = rilClient;
        this.incomingSink = incomingSink;
    }

    public SessionHandle createOutgoingSession() {
        return new SessionHandle("mo-pending-" + pendingSeq.getAndIncrement(), false);
    }

    public void setFrameworkEnabled(boolean enabled) {
        this.frameworkEnabled = enabled;
    }

    public boolean isFrameworkEnabled() {
        return frameworkEnabled;
    }

    public void setVoiceCapabilityConfigured(boolean enabled) {
        this.voiceCapabilityConfigured = enabled;
    }

    public boolean isVoiceCapabilityConfigured() {
        return voiceCapabilityConfigured;
    }

    public void updateModemRegistrationAndCapability(int regState, boolean voiceEnabledOnLte) {
        this.regState = regState;
        this.modemVoiceEnabledOnLte = voiceEnabledOnLte;
    }

    public boolean isVoiceImsReady() {
        return frameworkEnabled
                && voiceCapabilityConfigured
                && regState == RilImsWire.REG_STATE_REGISTERED
                && modemVoiceEnabledOnLte;
    }

    public int shouldProcessCall(String[] numbers, boolean platformDetectedEmergency) {
        if (platformDetectedEmergency) {
            return PROCESS_CALL_CSFB;
        }
        if (numbers == null || numbers.length == 0) {
            return PROCESS_CALL_CSFB;
        }
        for (String raw : numbers) {
            if (raw == null) continue;
            String clean = RilImsWire.normalizeDialAddress(raw);
            if (clean.isEmpty()) return PROCESS_CALL_CSFB;
            if (KNOWN_EMERGENCY_NUMBERS.contains(clean)) {
                return PROCESS_CALL_CSFB;
            }
            // Route USSD / supplementary service MMI strings over CSFB since getUt() is null
            if ((clean.startsWith("*") || clean.startsWith("#")) && clean.endsWith("#")) {
                return PROCESS_CALL_CSFB;
            }
        }
        return isVoiceImsReady() ? PROCESS_CALL_IMS : PROCESS_CALL_CSFB;
    }

    public boolean hasLiveCalls() {
        synchronized (lock) {
            return !pendingMoSessions.isEmpty() || !boundSessions.isEmpty();
        }
    }

    public int getActiveSessionCount() {
        synchronized (lock) {
            return pendingMoSessions.size() + boundSessions.size();
        }
    }

    public void onBackendDisconnected() {
        List<SessionHandle> toFailStart = new ArrayList<>();
        List<SessionHandle> toTerminate = new ArrayList<>();
        synchronized (lock) {
            toFailStart.addAll(pendingMoSessions);
            pendingMoSessions.clear();
            toTerminate.addAll(boundSessions.values());
            boundSessions.clear();
            rejectedCallIndices.clear();
        }
        for (SessionHandle s : toFailStart) {
            notifyStartFailedOnce(s, RilImsWire.IMS_REASON_LOCAL_IMS_SERVICE_DOWN, 1, "backend_disconnected");
        }
        for (SessionHandle s : toTerminate) {
            notifyTerminatedOnce(s, RilImsWire.IMS_REASON_LOCAL_IMS_SERVICE_DOWN, 1, "backend_disconnected");
        }
    }

    public void onRingbackTone(boolean playLocalTone) {
        playLocalRingbackFlag = playLocalTone;
        List<SessionHandle> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(boundSessions.values());
        }
        for (SessionHandle s : snapshot) {
            s.playLocalRingback = playLocalTone;
            Integer ds = s.lastDriverState;
            if (!s.isMt && ds != null && ds == RilImsWire.CALL_STATE_ALERTING) {
                int dir = playLocalTone ? RilImsWire.DIRECTION_INACTIVE : RilImsWire.DIRECTION_SEND_RECEIVE;
                SessionCallbacks cb = s.callbacks;
                if (cb != null) {
                    cb.onProgressing(s.audioQuality, dir);
                }
            }
        }
    }

    public void onSuppSvcNotification(RilImsWire.SuppSvcInfo info) {
        if (info == null) return;
        SessionHandle target = null;
        synchronized (lock) {
            if (info.connId > 0) {
                target = boundSessions.get(info.connId);
            }
            if (target == null && boundSessions.size() == 1) {
                target = boundSessions.values().iterator().next();
            }
        }
        if (target == null) return;
        SessionCallbacks cb = target.callbacks;
        if (cb == null) return;
        cb.onSuppServiceReceived(info);
        if (info.code == 2) {
            cb.onHoldReceived();
        } else if (info.code == 3) {
            cb.onResumeReceived();
        }
    }

    public void onCallListSnapshot(List<RilImsWire.DriverCall> calls, boolean authoritativeSolicited) {
        Set<Integer> seenLiveIndices = new HashSet<>();
        List<SessionHandle> vanishedSessions = new ArrayList<>();

        for (RilImsWire.DriverCall dc : calls) {
            SessionHandle session = null;
            boolean newlyCreatedIncoming = false;
            boolean cancelledPending = false;

            synchronized (lock) {
                session = boundSessions.get(dc.index);
                if (session == null && !dc.isMt && dc.state != RilImsWire.CALL_STATE_END && !pendingMoSessions.isEmpty()) {
                    session = pendingMoSessions.remove(0);
                    session.callIndex = dc.index;
                    session.playLocalRingback = playLocalRingbackFlag;
                    boundSessions.put(dc.index, session);
                    cancelledPending = session.cancelPendingOnBind;
                } else if (session == null && dc.isMt
                        && !rejectedCallIndices.containsKey(dc.index)
                        && (dc.state == RilImsWire.CALL_STATE_INCOMING || dc.state == RilImsWire.CALL_STATE_WAITING)) {
                    session = new SessionHandle(Integer.toString(dc.index), true);
                    session.callIndex = dc.index;
                    session.sessionState = SESSION_STATE_ESTABLISHING;
                    applyDriverCallMetadata(session, dc);
                    session.lastDriverState = dc.state;
                    boundSessions.put(dc.index, session);
                    newlyCreatedIncoming = true;
                }
                if (dc.state != RilImsWire.CALL_STATE_END) {
                    seenLiveIndices.add(dc.index);
                } else {
                    boundSessions.remove(dc.index);
                    rejectedCallIndices.remove(dc.index);
                }
            }

            if (newlyCreatedIncoming && session != null) {
                boolean isWaiting = (dc.state == RilImsWire.CALL_STATE_WAITING);
                incomingSink.onIncomingCallCreated(session, isWaiting);
                continue;
            }

            if (session != null && (!session.locallyRejected || dc.state == RilImsWire.CALL_STATE_END)) {
                if (cancelledPending) {
                    rilClient.sendHangup(dc.index, false, 0, null);
                }
                applyDriverCallTransition(session, dc);
            }
        }

        if (authoritativeSolicited) {
            List<SessionHandle> timedOutPending = new ArrayList<>();
            long nowMs = System.currentTimeMillis();
            synchronized (lock) {
                // HANGUP acknowledgement can precede the modem removing the rejected call.
                rejectedCallIndices.keySet().retainAll(seenLiveIndices);
                Iterator<Map.Entry<Integer, SessionHandle>> it = boundSessions.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, SessionHandle> e = it.next();
                    if (!seenLiveIndices.contains(e.getKey())) {
                        vanishedSessions.add(e.getValue());
                        it.remove();
                    }
                }
                if (calls.isEmpty() && !pendingMoSessions.isEmpty()) {
                    Iterator<SessionHandle> pit = pendingMoSessions.iterator();
                    while (pit.hasNext()) {
                        SessionHandle p = pit.next();
                        if (p.startedAtMs > 0L && (nowMs - p.startedAtMs) >= 3000L) {
                            timedOutPending.add(p);
                            pit.remove();
                        }
                    }
                }
            }
            for (SessionHandle stale : timedOutPending) {
                terminateSessionWithFailCauseLookup(stale, 0, true, false);
            }
            for (SessionHandle vanished : vanishedSessions) {
                if (vanished.locallyRejected) {
                    notifyRejectComplete(vanished);
                } else if (vanished.locallyTerminating) {
                    notifyTerminatedOnce(vanished, RilImsWire.IMS_REASON_USER_TERMINATED, 0, "local_hangup");
                } else {
                    terminateSessionWithFailCauseLookup(vanished, 0, false, false);
                }
            }
        }
    }

    private void applyDriverCallMetadata(SessionHandle s, RilImsWire.DriverCall dc) {
        if (!dc.number.isEmpty()) {
            s.peerNumber = dc.number;
            s.numberPresentation = dc.numberPresentation;
        } else if (s.peerNumber.isEmpty() || dc.numberPresentation != RilImsWire.RIL_CLIP_ALLOWED) {
            s.numberPresentation = dc.numberPresentation;
        }
        if (!dc.name.isEmpty()) {
            s.peerName = dc.name;
            s.namePresentation = dc.namePresentation;
        } else if (s.peerName.isEmpty() || dc.namePresentation != RilImsWire.RIL_CLIP_ALLOWED) {
            s.namePresentation = dc.namePresentation;
        }
        s.audioQuality = dc.audioQualityFromExtras();
        s.driverMetadataApplied = true;
    }

    private void applyDriverCallTransition(SessionHandle s, RilImsWire.DriverCall dc) {
        Integer prev = s.lastDriverState;
        boolean prevMpty = s.isMpty;
        applyDriverCallMetadata(s, dc);
        s.isMpty = dc.isMpty;
        s.lastDriverState = dc.state;

        int audioDir = (!s.isMt && dc.state == RilImsWire.CALL_STATE_ALERTING && s.playLocalRingback)
                ? RilImsWire.DIRECTION_INACTIVE
                : RilImsWire.DIRECTION_SEND_RECEIVE;

        switch (dc.state) {
            case RilImsWire.CALL_STATE_DIALING:
                s.sessionState = SESSION_STATE_INITIATED;
                break;
            case RilImsWire.CALL_STATE_ALERTING:
                s.sessionState = SESSION_STATE_NEGOTIATING;
                break;
            case RilImsWire.CALL_STATE_ACTIVE:
            case RilImsWire.CALL_STATE_HOLDING:
                s.sessionState = SESSION_STATE_ESTABLISHED;
                break;
            case RilImsWire.CALL_STATE_INCOMING:
            case RilImsWire.CALL_STATE_WAITING:
                s.sessionState = SESSION_STATE_ESTABLISHING;
                break;
            default:
                break;
        }

        SessionCallbacks cb = s.callbacks;
        if (cb != null && dc.state != RilImsWire.CALL_STATE_END) {
            cb.onCallProfileUpdated(
                    s.peerNumber,
                    s.numberPresentation,
                    s.peerName,
                    s.namePresentation,
                    s.audioQuality,
                    audioDir,
                    s.isMpty);
            if (prev != null && prevMpty != s.isMpty) {
                cb.onMultipartyStateChanged(s.isMpty);
            }
        }

        switch (dc.state) {
            case RilImsWire.CALL_STATE_DIALING: {
                if (prev == null && cb != null) {
                    cb.onProgressing(s.audioQuality, RilImsWire.DIRECTION_SEND_RECEIVE);
                }
                break;
            }
            case RilImsWire.CALL_STATE_ALERTING: {
                if ((prev == null || prev != RilImsWire.CALL_STATE_ALERTING) && cb != null) {
                    cb.onProgressing(s.audioQuality, audioDir);
                }
                break;
            }
            case RilImsWire.CALL_STATE_ACTIVE: {
                if (cb != null) {
                    if (prev != null && prev == RilImsWire.CALL_STATE_HOLDING) {
                        cb.onResumed();
                    } else if (prev == null || prev != RilImsWire.CALL_STATE_ACTIVE) {
                        cb.onInitiated(s.audioQuality);
                    }
                }
                break;
            }
            case RilImsWire.CALL_STATE_HOLDING: {
                if ((prev == null || prev != RilImsWire.CALL_STATE_HOLDING) && cb != null) {
                    cb.onHeld();
                }
                break;
            }
            case RilImsWire.CALL_STATE_INCOMING:
            case RilImsWire.CALL_STATE_WAITING: {
                break;
            }
            case RilImsWire.CALL_STATE_END: {
                if (s.locallyRejected) {
                    notifyRejectComplete(s);
                } else if (s.locallyTerminating) {
                    notifyTerminatedOnce(s, RilImsWire.IMS_REASON_USER_TERMINATED, 0, "local_hangup");
                } else if (dc.failCause != null) {
                    int mapped = RilImsWire.mapFailCauseToImsReason(dc.failCause, 0, false);
                    notifyTerminatedOnce(s, mapped, dc.failCause, "call_end_cause_" + dc.failCause);
                } else {
                    terminateSessionWithFailCauseLookup(s, 0, false, false);
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleDialCommandFailure(SessionHandle s, int rilError) {
        synchronized (lock) {
            pendingMoSessions.remove(s);
        }
        terminateSessionWithFailCauseLookup(s, rilError, true, false);
    }

    private void terminateSessionWithFailCauseLookup(
            SessionHandle s, int rilError, boolean asStartFailure, boolean locallyTerminated) {
        rilClient.queryLastCallFailCause((vendorCause, failQueryErr) -> {
            int mapped = RilImsWire.mapFailCauseToImsReason(vendorCause, rilError, locallyTerminated);
            int extra = vendorCause != null ? vendorCause : rilError;
            String msg = String.format(Locale.ROOT, "rilErr=%d,vendorCause=%s", rilError, String.valueOf(vendorCause));
            if (asStartFailure) {
                notifyStartFailedOnce(s, mapped, extra, msg);
            } else {
                notifyTerminatedOnce(s, mapped, extra, msg);
            }
        });
    }

    private void notifyRejectComplete(SessionHandle s) {
        synchronized (s) {
            if (s.terminalNotified) return;
            s.terminalNotified = true;
            s.sessionState = SESSION_STATE_TERMINATED;
        }
        synchronized (lock) {
            if (s.callIndex > 0) boundSessions.remove(s.callIndex, s);
        }
        SessionCallbacks cb = s.callbacks;
        if (cb != null) {
            cb.onInitiatedFailed(RilImsWire.IMS_REASON_LOCAL_CALL_DECLINE, RilImsWire.CALL_FAIL_USER_REJECT, "user_rejected");
        }
    }

    private void notifyStartFailedOnce(SessionHandle s, int imsReason, int extra, String msg) {
        synchronized (s) {
            if (s.terminalNotified) return;
            s.terminalNotified = true;
            s.sessionState = SESSION_STATE_TERMINATED;
        }
        SessionCallbacks cb = s.callbacks;
        if (cb != null) {
            cb.onInitiatedFailed(imsReason, extra, msg);
        }
    }

    private void notifyTerminatedOnce(SessionHandle s, int imsReason, int extra, String msg) {
        synchronized (s) {
            if (s.terminalNotified) return;
            s.terminalNotified = true;
            s.sessionState = SESSION_STATE_TERMINATED;
        }
        SessionCallbacks cb = s.callbacks;
        if (cb != null) {
            cb.onTerminated(imsReason, extra, msg);
        }
    }
}
