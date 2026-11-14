package org.octolab.qcom32ims;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent, auto-reconnecting client for /dev/socket/qmux_radio/rild_ims0.
 *
 * Key design invariants verified against libril-qc-qmi-1.so & RP32:
 * 1. Never sends REQ_26 (REQUEST_IMS_REG_STATE_CHANGE) or REQ_30 (REQUEST_SET_SERVICE_STATUS)
 *    during normal operation so Hexagon Stage 6A autonomous registration is never disturbed.
 * 2. Treats 0-byte UNSOL 201 (UNSOL_RESPONSE_CALL_STATE_CHANGED) and UNSOL 202 (UNSOL_CALL_RING)
 *    as pokes to immediately issue REQ_6 (REQUEST_GET_CURRENT_CALLS) with in-flight + dirty
 *    coalescing, never misinterpreting an empty UNSOL 201 as "0 current calls".
 * 3. Adapts dynamically to any valid radio UID (1001) rild PID across restarts.
 */
public final class RilImsCoreClient {

    public interface SocketTransport {
        void connect() throws IOException;
        int peerUid() throws IOException;
        int peerPid() throws IOException;
        InputStream inputStream() throws IOException;
        OutputStream outputStream() throws IOException;
        void setSoTimeout(int timeoutMs) throws IOException;
        void close();
    }

    public interface TransportFactory {
        SocketTransport create();
    }

    public interface ResponseCallback {
        void onResponse(RilImsWire.Frame responseFrame);
    }

    public interface FailCauseCallback {
        void onFailCause(Integer vendorFailCause, int rilError);
    }

    public interface Listener {
        void onBackendConnectionChanged(boolean connected, int peerPid);
        void onRegistrationAndCapabilityChanged(
                int regState,
                boolean voiceEnabledOnLte,
                String serviceSummary);
        void onCallListSnapshot(List<RilImsWire.DriverCall> calls, boolean authoritativeSolicited);
        void onRingbackTone(boolean playLocalTone);
        void onSuppSvcNotification(RilImsWire.SuppSvcInfo info);
        boolean hasLiveCalls();
        void onDiagnosticLog(String redactedEvent);
    }

    private final TransportFactory transportFactory;
    private final Listener listener;
    private final long unregisteredPollIntervalMs;
    private final long registeredKeepaliveIntervalMs;
    private final long liveCallSafetyPollIntervalMs;
    private final long reconnectInitialBackoffMs;

    private final Object writeLock = new Object();
    private final Object syncLock = new Object();
    private final AtomicInteger nextToken = new AtomicInteger(1);
    private final Map<Integer, ResponseCallback> pendingCallbacks = new ConcurrentHashMap<>();
    private final ArrayList<String> eventRing = new ArrayList<>();

    private volatile boolean running;
    private volatile SocketTransport activeTransport;
    private Thread ioThread;

    private volatile boolean connected;
    private volatile int lastPeerPid = -1;
    private volatile int lastRadioState = RilImsWire.RADIO_STATE_UNAVAILABLE;
    private volatile int lastRegState = RilImsWire.REG_STATE_NOT_REGISTERED;
    private volatile boolean rawServiceVoiceOnLte;
    private volatile boolean lastVoiceEnabledOnLte;
    private volatile String lastServiceSummary = "none";

    private boolean callSyncInFlight;
    private boolean callSyncDirty;
    private boolean regPollInFlight;
    private boolean regPollDirty;

    private volatile int txCount;
    private volatile int rxSolicitedCount;
    private volatile int rxUnsolCount;
    private volatile int reconnectCount;

    public RilImsCoreClient(TransportFactory transportFactory, Listener listener) {
        this(transportFactory, listener, 4000L, 45000L, 2000L, 1000L);
    }

    public RilImsCoreClient(
            TransportFactory transportFactory,
            Listener listener,
            long unregisteredPollIntervalMs,
            long registeredKeepaliveIntervalMs,
            long liveCallSafetyPollIntervalMs,
            long reconnectInitialBackoffMs) {
        this.transportFactory = transportFactory;
        this.listener = listener;
        this.unregisteredPollIntervalMs = Math.max(250L, unregisteredPollIntervalMs);
        this.registeredKeepaliveIntervalMs = Math.max(1000L, registeredKeepaliveIntervalMs);
        this.liveCallSafetyPollIntervalMs = Math.max(250L, liveCallSafetyPollIntervalMs);
        this.reconnectInitialBackoffMs = Math.max(50L, reconnectInitialBackoffMs);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        ioThread = new Thread(this::runSupervisorLoop, "passport-rild-ims0");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    public synchronized void stop() {
        running = false;
        SocketTransport t = activeTransport;
        if (t != null) {
            t.close();
        }
        if (ioThread != null) {
            ioThread.interrupt();
        }
        failAllPendingCallbacks("client_stopped");
    }

    public boolean isConnected() {
        return connected;
    }

    public int getLastPeerPid() {
        return lastPeerPid;
    }

    public int getLastRegState() {
        return lastRegState;
    }

    public boolean isLastVoiceEnabledOnLte() {
        return lastVoiceEnabledOnLte;
    }

    public String getLastServiceSummary() {
        return lastServiceSummary;
    }

    public int getTxCount() {
        return txCount;
    }

    public int getRxSolicitedCount() {
        return rxSolicitedCount;
    }

    public int getRxUnsolCount() {
        return rxUnsolCount;
    }

    public int getReconnectCount() {
        return reconnectCount;
    }

    public synchronized List<String> getRecentEventsSnapshot() {
        return new ArrayList<>(eventRing);
    }

    private void recordEvent(String msg) {
        String safe = RilImsWire.redactPii(msg);
        synchronized (this) {
            if (eventRing.size() >= 300) {
                eventRing.remove(0);
            }
            eventRing.add(safe);
        }
        listener.onDiagnosticLog(safe);
    }

    public void requestImmediateRefresh() {
        sendPassiveRegAndServicePoll();
        requestCallSync();
    }

    public void requestCallSync() {
        boolean shouldSend = false;
        synchronized (syncLock) {
            if (!connected) return;
            if (callSyncInFlight) {
                callSyncDirty = true;
            } else {
                callSyncInFlight = true;
                callSyncDirty = false;
                shouldSend = true;
            }
        }
        if (shouldSend) {
            sendGetCurrentCallsInternal();
        }
    }

    private void sendGetCurrentCallsInternal() {
        int tok = sendRequestInternal(
                RilImsWire.REQ_GET_CURRENT_CALLS,
                new byte[0],
                frame -> {
                    if (frame.error == 0) {
                        try {
                            List<RilImsWire.DriverCall> calls = RilImsWire.decodeCallList(frame.payload());
                            listener.onCallListSnapshot(calls, true);
                        } catch (IOException ex) {
                            recordEvent("decode_calls_err=" + ex.getClass().getSimpleName());
                        }
                    } else {
                        recordEvent("req6_err=" + frame.error);
                    }
                    boolean followUp = false;
                    synchronized (syncLock) {
                        if (callSyncDirty && connected) {
                            callSyncDirty = false;
                            callSyncInFlight = true;
                            followUp = true;
                        } else {
                            callSyncInFlight = false;
                            callSyncDirty = false;
                        }
                    }
                    if (followUp) {
                        sendGetCurrentCallsInternal();
                    }
                });
        if (tok < 0) {
            synchronized (syncLock) {
                callSyncInFlight = false;
                callSyncDirty = false;
            }
        }
    }

    public void sendPassiveRegAndServicePoll() {
        boolean shouldSend = false;
        synchronized (syncLock) {
            if (!connected) return;
            if (regPollInFlight) {
                regPollDirty = true;
            } else {
                regPollInFlight = true;
                regPollDirty = false;
                shouldSend = true;
            }
        }
        if (shouldSend) {
            sendRegThenServicePollInternal();
        }
    }

    private void sendRegThenServicePollInternal() {
        sendRequestInternal(RilImsWire.REQ_IMS_REGISTRATION_STATE, new byte[0], frame -> {
            if (frame.error == 0) {
                try {
                    RilImsWire.RegObservation ro = RilImsWire.decodeRegistration(frame.payload());
                    if (ro.state > 0) {
                        boolean effectiveVoice = (ro.state == RilImsWire.REG_STATE_REGISTERED)
                                && rawServiceVoiceOnLte;
                        updateRegAndCapability(ro.state, effectiveVoice, lastServiceSummary);
                    }
                } catch (IOException ex) {
                    recordEvent("decode_reg_err=" + ex.getClass().getSimpleName());
                }
            } else {
                recordEvent("req1_err=" + frame.error);
            }
            sendServiceStatusAfterRegInternal();
        });
    }

    private void sendServiceStatusAfterRegInternal() {
        if (!connected) {
            finishRegPollCycle();
            return;
        }
        sendRequestInternal(RilImsWire.REQ_QUERY_SERVICE_STATUS, new byte[0], frame -> {
            if (frame.error == 0) {
                try {
                    RilImsWire.ServiceStatusSnapshot snap = RilImsWire.decodeServiceStatusList(frame.payload());
                    rawServiceVoiceOnLte = snap.isVoiceEnabledOnLte();
                    int authoritativeReg = lastRegState;
                    boolean effectiveVoice = (authoritativeReg == RilImsWire.REG_STATE_REGISTERED)
                            && rawServiceVoiceOnLte;
                    updateRegAndCapability(authoritativeReg, effectiveVoice, snap.summary());
                } catch (IOException ex) {
                    recordEvent("decode_svc_err=" + ex.getClass().getSimpleName());
                }
            } else {
                recordEvent("req29_err=" + frame.error);
            }
            finishRegPollCycle();
        });
    }

    private void finishRegPollCycle() {
        boolean followUp = false;
        synchronized (syncLock) {
            if (regPollDirty && connected) {
                regPollDirty = false;
                regPollInFlight = true;
                followUp = true;
            } else {
                regPollInFlight = false;
                regPollDirty = false;
            }
        }
        if (followUp) {
            sendRegThenServicePollInternal();
        }
    }

    public int sendDial(String callee, int clirMode, int callDomain, ResponseCallback cb) {
        final byte[] wirePayload;
        try {
            int token = nextToken.getAndIncrement();
            byte[] fullWire = RilImsWire.encodeDial(token, callee, clirMode, callDomain);
            return sendPrebuiltFrame(token, RilImsWire.REQ_DIAL, fullWire, frame -> {
                if (cb != null) cb.onResponse(frame);
                if (frame.error == 0) {
                    requestCallSync();
                }
            });
        } catch (IllegalArgumentException ex) {
            recordEvent("dial_encode_rejected=" + ex.getMessage());
            if (cb != null) {
                cb.onResponse(new RilImsWire.Frame(-1, RilImsWire.MSG_TYPE_RESPONSE, RilImsWire.REQ_DIAL, 27, new byte[0]));
            }
            return -1;
        }
    }

    public int sendAnswer(ResponseCallback cb) {
        int token = nextToken.getAndIncrement();
        byte[] fullWire = RilImsWire.encodeAnswer(token);
        return sendPrebuiltFrame(token, RilImsWire.REQ_ANSWER, fullWire, frame -> {
            if (cb != null) cb.onResponse(frame);
            requestCallSync();
        });
    }

    public int sendHangup(int callIndex, boolean userReject, int rejectFailCause, ResponseCallback cb) {
        int token = nextToken.getAndIncrement();
        byte[] fullWire = RilImsWire.encodeHangup(token, callIndex, userReject, rejectFailCause);
        return sendPrebuiltFrame(token, RilImsWire.REQ_HANGUP, fullWire, frame -> {
            if (cb != null) cb.onResponse(frame);
            requestCallSync();
        });
    }

    public int sendHangupForegroundResumeBackground(ResponseCallback cb) {
        return sendRequestInternal(RilImsWire.REQ_HANGUP_FOREGROUND_RESUME_BACKGROUND, new byte[0], frame -> {
            if (cb != null) cb.onResponse(frame);
            requestCallSync();
        });
    }

    public int sendHold(int callIndex, ResponseCallback cb) {
        int token = nextToken.getAndIncrement();
        byte[] fullWire = RilImsWire.encodeHold(token, callIndex);
        return sendPrebuiltFrame(token, RilImsWire.REQ_HOLD, fullWire, frame -> {
            if (cb != null) cb.onResponse(frame);
            requestCallSync();
        });
    }

    public int sendResume(int callIndex, ResponseCallback cb) {
        int token = nextToken.getAndIncrement();
        byte[] fullWire = RilImsWire.encodeResume(token, callIndex);
        return sendPrebuiltFrame(token, RilImsWire.REQ_RESUME, fullWire, frame -> {
            if (cb != null) cb.onResponse(frame);
            requestCallSync();
        });
    }

    public int sendDtmf(char digit, boolean continuous, ResponseCallback cb) {
        try {
            int token = nextToken.getAndIncrement();
            byte[] fullWire = RilImsWire.encodeDtmf(token, digit, continuous);
            int reqId = continuous ? RilImsWire.REQ_DTMF_START : RilImsWire.REQ_DTMF;
            return sendPrebuiltFrame(token, reqId, fullWire, cb);
        } catch (IllegalArgumentException ex) {
            recordEvent("dtmf_rejected");
            if (cb != null) {
                cb.onResponse(new RilImsWire.Frame(-1, RilImsWire.MSG_TYPE_RESPONSE, RilImsWire.REQ_DTMF, 27, new byte[0]));
            }
            return -1;
        }
    }

    public int sendStopDtmf(ResponseCallback cb) {
        int token = nextToken.getAndIncrement();
        byte[] fullWire = RilImsWire.encodeStopDtmf(token);
        return sendPrebuiltFrame(token, RilImsWire.REQ_DTMF_STOP, fullWire, cb);
    }

    public void queryLastCallFailCause(FailCauseCallback cb) {
        int tok = sendRequestInternal(RilImsWire.REQ_LAST_CALL_FAIL_CAUSE, new byte[0], frame -> {
            if (frame.error == 0) {
                try {
                    Integer cause = RilImsWire.decodeCallFailCause(frame.payload());
                    cb.onFailCause(cause, 0);
                    return;
                } catch (IOException ex) {
                    recordEvent("decode_failcause_err=" + ex.getClass().getSimpleName());
                }
            }
            cb.onFailCause(null, frame.error);
        });
        if (tok < 0) {
            cb.onFailCause(null, 1);
        }
    }

    private int sendRequestInternal(int reqId, byte[] payload, ResponseCallback cb) {
        int token = nextToken.getAndIncrement();
        byte[] fullWire = RilImsWire.encodeRequest(token, reqId, payload);
        return sendPrebuiltFrame(token, reqId, fullWire, cb);
    }

    private int sendPrebuiltFrame(int token, int reqId, byte[] fullWire, ResponseCallback cb) {
        SocketTransport t = activeTransport;
        if (!connected || t == null) {
            if (cb != null) {
                cb.onResponse(new RilImsWire.Frame(token, RilImsWire.MSG_TYPE_RESPONSE, reqId, 1, new byte[0]));
            }
            return -1;
        }
        if (cb != null) {
            pendingCallbacks.put(token, cb);
        }
        try {
            synchronized (writeLock) {
                OutputStream out = t.outputStream();
                out.write(fullWire);
                out.flush();
                txCount++;
            }
            recordEvent("tx id=" + reqId + " tok=" + token + " len=" + fullWire.length);
            return token;
        } catch (IOException ex) {
            pendingCallbacks.remove(token);
            recordEvent("tx_io_err id=" + reqId + " err=" + ex.getClass().getSimpleName());
            t.close();
            if (cb != null) {
                cb.onResponse(new RilImsWire.Frame(token, RilImsWire.MSG_TYPE_RESPONSE, reqId, 1, new byte[0]));
            }
            return -1;
        }
    }

    private void runSupervisorLoop() {
        long backoffMs = reconnectInitialBackoffMs;
        while (running) {
            SocketTransport transport = null;
            try {
                transport = transportFactory.create();
                transport.connect();
                int uid = transport.peerUid();
                int pid = transport.peerPid();
                if (uid != 1001 || pid <= 0) {
                    throw new IOException("Unexpected rild_ims0 peer uid=" + uid + " pid=" + pid);
                }
                activeTransport = transport;
                lastPeerPid = pid;
                rawServiceVoiceOnLte = false;
                synchronized (syncLock) {
                    callSyncInFlight = false;
                    callSyncDirty = false;
                    regPollInFlight = false;
                    regPollDirty = false;
                }
                connected = true;
                backoffMs = reconnectInitialBackoffMs;
                recordEvent("connected_rild_ims0 uid=" + uid + " pid=" + pid);
                listener.onBackendConnectionChanged(true, pid);

                runSingleConnectionReadLoop(transport);
            } catch (Exception ex) {
                if (running) {
                    recordEvent("conn_loop_err=" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
                }
            } finally {
                boolean wasConnected = connected;
                connected = false;
                activeTransport = null;
                rawServiceVoiceOnLte = false;
                synchronized (syncLock) {
                    callSyncInFlight = false;
                    callSyncDirty = false;
                    regPollInFlight = false;
                    regPollDirty = false;
                }
                if (transport != null) {
                    transport.close();
                }
                failAllPendingCallbacks("socket_disconnected");
                if (wasConnected) {
                    updateRegAndCapability(RilImsWire.REG_STATE_NOT_REGISTERED, false, "disconnected");
                    listener.onBackendConnectionChanged(false, -1);
                }
            }

            if (!running) break;
            reconnectCount++;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                if (!running) break;
            }
            backoffMs = Math.min(5000L, backoffMs * 2L);
        }
    }

    private void runSingleConnectionReadLoop(SocketTransport transport) throws IOException {
        InputStream in = transport.inputStream();
        boolean bootstrapped = false;
        long connectTimeMs = System.currentTimeMillis();
        long lastRegPollMs = 0L;
        long lastCallPollMs = 0L;

        while (running && connected) {
            byte[] raw = RilImsWire.receiveRawFrameOrNull(in, transport::setSoTimeout, 400, 3000);
            long now = System.currentTimeMillis();
            if (raw != null) {
                RilImsWire.Frame frame = RilImsWire.decodeFrame(raw);
                if (frame.type == RilImsWire.MSG_TYPE_UNSOL_RESPONSE) {
                    rxUnsolCount++;
                    if (frame.id == RilImsWire.UNSOL_RADIO_STATE_CHANGED) {
                        bootstrapped = true;
                        lastRegPollMs = now;
                        lastCallPollMs = now;
                    }
                    handleUnsolicitedFrame(frame);
                } else if (frame.type == RilImsWire.MSG_TYPE_RESPONSE) {
                    rxSolicitedCount++;
                    recordEvent("rx_sol id=" + frame.id + " tok=" + frame.token
                            + " err=" + frame.error + " plen=" + frame.payloadLength());
                    ResponseCallback cb = pendingCallbacks.remove(frame.token);
                    if (cb != null) {
                        cb.onResponse(frame);
                    }
                }
            }

            if (!bootstrapped && (now - connectTimeMs) >= 350L) {
                bootstrapped = true;
                lastRegPollMs = now;
                lastCallPollMs = now;
                sendPassiveRegAndServicePoll();
                requestCallSync();
            }

            if (bootstrapped) {
                boolean ready = (lastRegState == RilImsWire.REG_STATE_REGISTERED) && lastVoiceEnabledOnLte;
                long regInterval = ready ? registeredKeepaliveIntervalMs : unregisteredPollIntervalMs;
                if (now - lastRegPollMs >= regInterval) {
                    lastRegPollMs = now;
                    sendPassiveRegAndServicePoll();
                }
                if (listener.hasLiveCalls() && (now - lastCallPollMs >= liveCallSafetyPollIntervalMs)) {
                    lastCallPollMs = now;
                    requestCallSync();
                }
            }
        }
    }

    private void handleUnsolicitedFrame(RilImsWire.Frame frame) {
        recordEvent("rx_unsol id=" + frame.id + " err=" + frame.error + " plen=" + frame.payloadLength());
        if (frame.error != 0) return;

        try {
            switch (frame.id) {
                case RilImsWire.UNSOL_RADIO_STATE_CHANGED: {
                    int rs = RilImsWire.decodeRadioState(frame.payload());
                    lastRadioState = rs;
                    if (rs == RilImsWire.RADIO_STATE_ON) {
                        sendPassiveRegAndServicePoll();
                        requestCallSync();
                    } else {
                        rawServiceVoiceOnLte = false;
                        updateRegAndCapability(RilImsWire.REG_STATE_NOT_REGISTERED, false, "radio_off_" + rs);
                    }
                    break;
                }
                case RilImsWire.UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED: {
                    if (frame.payloadLength() > 0) {
                        RilImsWire.RegObservation ro = RilImsWire.decodeRegistration(frame.payload());
                        if (ro.state > 0) {
                            boolean effectiveVoice = (ro.state == RilImsWire.REG_STATE_REGISTERED)
                                    && rawServiceVoiceOnLte;
                            updateRegAndCapability(ro.state, effectiveVoice, lastServiceSummary);
                        }
                    }
                    sendPassiveRegAndServicePoll();
                    break;
                }
                case RilImsWire.UNSOL_SRV_STATUS_UPDATE: {
                    if (frame.payloadLength() > 0) {
                        RilImsWire.ServiceStatusSnapshot snap = RilImsWire.decodeServiceStatusList(frame.payload());
                        rawServiceVoiceOnLte = snap.isVoiceEnabledOnLte();
                        int authoritativeReg = lastRegState;
                        boolean effectiveVoice = (authoritativeReg == RilImsWire.REG_STATE_REGISTERED)
                                && rawServiceVoiceOnLte;
                        updateRegAndCapability(authoritativeReg, effectiveVoice, snap.summary());
                    }
                    sendPassiveRegAndServicePoll();
                    break;
                }
                case RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED:
                case RilImsWire.UNSOL_CALL_RING: {
                    if (frame.id == RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED && frame.payloadLength() > 0) {
                        List<RilImsWire.DriverCall> calls = RilImsWire.decodeCallList(frame.payload());
                        listener.onCallListSnapshot(calls, false);
                    }
                    // Always issue REQ_GET_CURRENT_CALLS (6) on UNSOL 201 / 202 (libril sends 0-byte pokes).
                    requestCallSync();
                    break;
                }
                case RilImsWire.UNSOL_RINGBACK_TONE: {
                    boolean playLocal = RilImsWire.decodeRingbackTonePlayLocal(frame.payload());
                    listener.onRingbackTone(playLocal);
                    break;
                }
                case RilImsWire.UNSOL_SUPP_SVC_NOTIFICATION: {
                    RilImsWire.SuppSvcInfo ssn = RilImsWire.decodeSuppSvcNotification(frame.payload());
                    if (ssn != null) {
                        listener.onSuppSvcNotification(ssn);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (IOException ex) {
            recordEvent("unsol_decode_err id=" + frame.id + " err=" + ex.getClass().getSimpleName());
        }
    }

    private void updateRegAndCapability(int regState, boolean voiceLte, String summary) {
        boolean changed = (regState != lastRegState)
                || (voiceLte != lastVoiceEnabledOnLte)
                || !String.valueOf(summary).equals(lastServiceSummary);
        lastRegState = regState;
        lastVoiceEnabledOnLte = voiceLte;
        lastServiceSummary = summary == null ? "" : summary;
        if (changed) {
            listener.onRegistrationAndCapabilityChanged(regState, voiceLte, lastServiceSummary);
        }
    }

    private void failAllPendingCallbacks(String why) {
        if (pendingCallbacks.isEmpty()) return;
        recordEvent("failing_pending count=" + pendingCallbacks.size() + " why=" + why);
        List<Map.Entry<Integer, ResponseCallback>> entries = new ArrayList<>(pendingCallbacks.entrySet());
        pendingCallbacks.clear();
        for (Map.Entry<Integer, ResponseCallback> e : entries) {
            try {
                e.getValue().onResponse(
                        new RilImsWire.Frame(e.getKey(), RilImsWire.MSG_TYPE_RESPONSE, 0, 1, new byte[0]));
            } catch (Exception ignored) { }
        }
    }
}
