package org.octolab.qcom32ims;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsSuppServiceNotification;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import java.util.Objects;

/**
 * Android 11 ImsCallSessionImplBase adapter wrapping a CallStateEngine.SessionHandle.
 *
 * Maps QCRIL CLIP presentation values (0..3) via CallStateEngine.SessionHandle.getNumberOir() /
 * getNameOir() -> ImsCallProfile.OIR_* (1..4) so LineageOS 18.1 ImsPhoneConnection
 * (ImsCallProfile.OIRToPresentation) resolves incoming Caller ID as PRESENTATION_ALLOWED (1).
 */
public final class Qcom32CallSession extends ImsCallSessionImplBase {
    private final CallStateEngine.SessionHandle handle;
    private final Handler mainHandler;
    private final ImsCallProfile callProfile;
    private final ImsCallProfile localCallProfile;
    private final ImsCallProfile remoteCallProfile;
    private volatile ImsCallSessionListener sessionListener;

    Qcom32CallSession(
            CallStateEngine.SessionHandle handle,
            ImsCallProfile initialProfile,
            Handler mainHandler) {
        this.handle = handle;
        this.mainHandler = mainHandler;
        this.callProfile = initialProfile != null
                ? initialProfile
                : buildDefaultVoiceProfile(ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
        this.callProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_LTE);
        this.localCallProfile = buildDefaultVoiceProfile(
                ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
        this.remoteCallProfile = buildDefaultVoiceProfile(
                ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);

        syncProfileMetadataFromHandle(
                handle.getAudioQuality(),
                ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE);
        this.handle.setCallbacks(new CallbackBridge());
    }

    static ImsCallProfile buildDefaultVoiceProfile(int serviceType, int callType) {
        ImsStreamMediaProfile media = buildAudioMediaProfile(
                ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB,
                ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE);
        ImsCallProfile p = new ImsCallProfile(serviceType, callType, new Bundle(), media);
        p.setCallExtraInt(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_LTE);
        return p;
    }

    static ImsStreamMediaProfile buildAudioMediaProfile(int audioQuality, int audioDirection) {
        return new ImsStreamMediaProfile(
                audioQuality,
                audioDirection,
                ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                ImsStreamMediaProfile.DIRECTION_INVALID,
                ImsStreamMediaProfile.RTT_MODE_DISABLED);
    }

    private void syncProfileMetadataFromHandle(int audioQuality, int audioDirection) {
        if (handle.isIncoming() || handle.hasDriverMetadata()) {
            String num = handle.getPeerNumber() != null ? handle.getPeerNumber() : "";
            int numOir = handle.getNumberOir();
            String cna = handle.getPeerName() != null ? handle.getPeerName() : "";
            int nameOir = handle.getNameOir();

            if (!num.isEmpty()) {
                callProfile.setCallExtra(ImsCallProfile.EXTRA_OI, num);
                remoteCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, num);
            }
            callProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, numOir);
            remoteCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, numOir);

            callProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, cna);
            remoteCallProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, cna);
            callProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP, nameOir);
            remoteCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP, nameOir);
        }

        callProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_LTE);
        localCallProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_LTE);
        remoteCallProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_LTE);

        ImsStreamMediaProfile media = buildAudioMediaProfile(audioQuality, audioDirection);
        if (callProfile.getMediaProfile() != null) {
            callProfile.getMediaProfile().copyFrom(media);
        }
        if (localCallProfile.getMediaProfile() != null) {
            localCallProfile.getMediaProfile().copyFrom(media);
        }
        if (remoteCallProfile.getMediaProfile() != null) {
            remoteCallProfile.getMediaProfile().copyFrom(media);
        }
    }

    @Override
    public void setListener(ImsCallSessionListener listener) {
        this.sessionListener = listener;
    }

    @Override
    public void close() {
        sessionListener = null;
        handle.close();
    }

    @Override
    public String getCallId() {
        return handle.getCallId();
    }

    @Override
    public ImsCallProfile getCallProfile() {
        return callProfile;
    }

    @Override
    public ImsCallProfile getLocalCallProfile() {
        return localCallProfile;
    }

    @Override
    public ImsCallProfile getRemoteCallProfile() {
        return remoteCallProfile;
    }

    @Override
    public String getProperty(String name) {
        if (name == null) return null;
        return callProfile.getCallExtra(name);
    }

    @Override
    public int getState() {
        return handle.getSessionState();
    }

    @Override
    public boolean isInCall() {
        return handle.isInCall();
    }

    @Override
    public boolean isMultiparty() {
        return handle.isMultiparty();
    }

    @Override
    public void start(String callee, ImsCallProfile profile) {
        if (profile != null) {
            callProfile.updateCallType(profile);
            callProfile.updateCallExtras(profile);
            if (profile.getMediaProfile() != null && callProfile.getMediaProfile() != null) {
                callProfile.getMediaProfile().copyFrom(profile.getMediaProfile());
            }
        }
        callProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_LTE);
        int oir = callProfile.getCallExtraInt(ImsCallProfile.EXTRA_OIR, ImsCallProfile.OIR_DEFAULT);
        int clir = 0;
        if (oir == ImsCallProfile.OIR_PRESENTATION_RESTRICTED) {
            clir = 1;
        } else if (oir == ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED) {
            clir = 2;
        }
        int domain = callProfile.getCallExtraInt("call_domain", RilImsWire.IMS_CALL_DOMAIN_PS);
        handle.startOutgoing(callee, clir, domain);
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) {
        if (profile != null && callProfile.getMediaProfile() != null) {
            callProfile.getMediaProfile().copyFrom(profile);
        }
        handle.acceptIncoming();
    }

    @Override
    public void reject(int reason) {
        handle.rejectIncoming(reason);
    }

    @Override
    public void terminate(int reason) {
        handle.terminate(reason);
    }

    @Override
    public void hold(ImsStreamMediaProfile profile) {
        handle.hold();
    }

    @Override
    public void resume(ImsStreamMediaProfile profile) {
        handle.resume();
    }

    @Override
    public void sendDtmf(char c, Message result) {
        handle.sendDtmf(c, () -> completeDtmfMessage(result));
    }

    @Override
    public void startDtmf(char c) {
        handle.startDtmf(c);
    }

    @Override
    public void stopDtmf() {
        handle.stopDtmf();
    }

    private static void completeDtmfMessage(Message result) {
        if (result == null) return;
        try {
            if (result.replyTo != null) {
                Message reply = Message.obtain();
                reply.what = result.what;
                reply.arg1 = result.arg1;
                reply.arg2 = result.arg2;
                result.replyTo.send(reply);
                reply.recycle();
            } else if (result.getTarget() != null) {
                result.sendToTarget();
            }
        } catch (Exception ignored) { }
    }

    private final class CallbackBridge implements CallStateEngine.SessionCallbacks {
        @Override
        public void onCallProfileUpdated(
                String number,
                int numberPresentation,
                String name,
                int namePresentation,
                int audioQuality,
                int audioDirection,
                boolean isMultiparty) {
            mainHandler.post(() -> {
                String prevOi = callProfile.getCallExtra(ImsCallProfile.EXTRA_OI);
                int prevOir = callProfile.getCallExtraInt(ImsCallProfile.EXTRA_OIR, -1);
                String prevCna = callProfile.getCallExtra(ImsCallProfile.EXTRA_CNA);
                int prevCnap = callProfile.getCallExtraInt(ImsCallProfile.EXTRA_CNAP, -1);
                int prevQuality = callProfile.getMediaProfile() != null
                        ? callProfile.getMediaProfile().getAudioQuality()
                        : -1;

                syncProfileMetadataFromHandle(audioQuality, audioDirection);

                String newOi = callProfile.getCallExtra(ImsCallProfile.EXTRA_OI);
                int newOir = callProfile.getCallExtraInt(ImsCallProfile.EXTRA_OIR, -1);
                String newCna = callProfile.getCallExtra(ImsCallProfile.EXTRA_CNA);
                int newCnap = callProfile.getCallExtraInt(ImsCallProfile.EXTRA_CNAP, -1);

                boolean callerIdChanged = !Objects.equals(prevOi, newOi)
                        || prevOir != newOir
                        || !Objects.equals(prevCna, newCna)
                        || prevCnap != newCnap;
                boolean qualityChanged = (prevQuality != audioQuality);

                int st = handle.getSessionState();
                boolean liveState = (st == CallStateEngine.SESSION_STATE_INITIATED
                        || st == CallStateEngine.SESSION_STATE_NEGOTIATING
                        || st == CallStateEngine.SESSION_STATE_ESTABLISHING
                        || st == CallStateEngine.SESSION_STATE_ESTABLISHED);

                if (liveState && (callerIdChanged || (st == CallStateEngine.SESSION_STATE_ESTABLISHED && qualityChanged))) {
                    ImsCallSessionListener l = sessionListener;
                    if (l != null) {
                        l.callSessionUpdated(callProfile);
                    }
                }
            });
        }

        @Override
        public void onProgressing(int audioQuality, int audioDirection) {
            mainHandler.post(() -> {
                syncProfileMetadataFromHandle(audioQuality, audioDirection);
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionProgressing(buildAudioMediaProfile(audioQuality, audioDirection));
                }
            });
        }

        @Override
        public void onInitiated(int audioQuality) {
            mainHandler.post(() -> {
                syncProfileMetadataFromHandle(audioQuality, ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE);
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionInitiated(callProfile);
                }
            });
        }

        @Override
        public void onInitiatedFailed(int imsReasonCode, int vendorExtraCode, String message) {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionInitiatedFailed(new ImsReasonInfo(imsReasonCode, vendorExtraCode, message));
                }
            });
        }

        @Override
        public void onTerminated(int imsReasonCode, int vendorExtraCode, String message) {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionTerminated(new ImsReasonInfo(imsReasonCode, vendorExtraCode, message));
                }
            });
        }

        @Override
        public void onHeld() {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionHeld(callProfile);
                }
            });
        }

        @Override
        public void onHoldFailed(int imsReasonCode, int vendorExtraCode, String message) {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionHoldFailed(new ImsReasonInfo(imsReasonCode, vendorExtraCode, message));
                }
            });
        }

        @Override
        public void onHoldReceived() {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionHoldReceived(callProfile);
                }
            });
        }

        @Override
        public void onResumed() {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionResumed(callProfile);
                }
            });
        }

        @Override
        public void onResumeFailed(int imsReasonCode, int vendorExtraCode, String message) {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionResumeFailed(new ImsReasonInfo(imsReasonCode, vendorExtraCode, message));
                }
            });
        }

        @Override
        public void onResumeReceived() {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionResumeReceived(callProfile);
                }
            });
        }

        @Override
        public void onMultipartyStateChanged(boolean isMultiparty) {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null) {
                    l.callSessionMultipartyStateChanged(isMultiparty);
                }
            });
        }

        @Override
        public void onSuppServiceReceived(RilImsWire.SuppSvcInfo info) {
            mainHandler.post(() -> {
                ImsCallSessionListener l = sessionListener;
                if (l != null && info != null) {
                    String[] hist = (info.historyInfo == null || info.historyInfo.isEmpty())
                            ? new String[0]
                            : new String[] { info.historyInfo };
                    l.callSessionSuppServiceReceived(new ImsSuppServiceNotification(
                            info.notificationType,
                            info.code,
                            info.index,
                            info.type,
                            info.number,
                            hist));
                }
            });
        }
    }
}
