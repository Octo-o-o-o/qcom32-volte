package org.passport.volteims;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import java.util.List;

/**
 * Android 11 MmTelFeature implementation backed by CallStateEngine + RilImsCoreClient.
 *
 * Truthfully advertises ONLY CAPABILITY_TYPE_VOICE when LTE VoLTE is registered and enabled
 * (never claiming CAPABILITY_TYPE_SMS, CAPABILITY_TYPE_VIDEO, or CAPABILITY_TYPE_UT).
 */
public final class PassportMmTelFeature extends MmTelFeature {
    private final Context context;
    private final CallStateEngine engine;
    private final Handler mainHandler;
    private final Runnable stateSyncCallback;
    private volatile boolean featureReadyActive;

    PassportMmTelFeature(
            Context context,
            CallStateEngine engine,
            Handler mainHandler,
            Runnable stateSyncCallback) {
        this.context = context;
        this.engine = engine;
        this.mainHandler = mainHandler;
        this.stateSyncCallback = stateSyncCallback;
        setFeatureState(ImsFeature.STATE_READY);
        this.featureReadyActive = true;
    }

    void markReadyPostSetup() {
        setFeatureState(ImsFeature.STATE_READY);
        featureReadyActive = true;
    }

    boolean isFeatureReadyCalled() {
        return featureReadyActive && getFeatureState() == ImsFeature.STATE_READY;
    }

    void publishCurrentCapabilities() {
        MmTelCapabilities caps = new MmTelCapabilities();
        if (engine.isVoiceImsReady()) {
            caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);
        }
        try {
            notifyCapabilitiesStatusChanged(caps);
        } catch (Exception ignored) { }
    }

    void dispatchIncomingCall(CallStateEngine.SessionHandle handle, boolean isCallWaiting) {
        mainHandler.post(() -> {
            ImsCallProfile profile = PassportCallSession.buildDefaultVoiceProfile(
                    ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
            PassportCallSession session = new PassportCallSession(handle, profile, mainHandler);
            Bundle extras = new Bundle();
            extras.putBoolean(MmTelFeature.EXTRA_IS_USSD, false);
            extras.putBoolean(MmTelFeature.EXTRA_IS_UNKNOWN_CALL, false);
            notifyIncomingCall(session, extras);
        });
    }

    @Override
    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        if (capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                && radioTech == ImsRegistrationImplBase.REGISTRATION_TECH_LTE) {
            return engine.isVoiceCapabilityConfigured();
        }
        return false;
    }

    @Override
    public void changeEnabledCapabilities(
            CapabilityChangeRequest request, CapabilityCallbackProxy c) {
        if (request == null) return;
        List<CapabilityChangeRequest.CapabilityPair> toDisable = request.getCapabilitiesToDisable();
        if (toDisable != null) {
            for (CapabilityChangeRequest.CapabilityPair p : toDisable) {
                if (p.getCapability() == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                        && p.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_LTE) {
                    engine.setVoiceCapabilityConfigured(false);
                }
            }
        }
        List<CapabilityChangeRequest.CapabilityPair> toEnable = request.getCapabilitiesToEnable();
        if (toEnable != null) {
            for (CapabilityChangeRequest.CapabilityPair p : toEnable) {
                if (p.getCapability() == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                        && p.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_LTE) {
                    engine.setVoiceCapabilityConfigured(true);
                }
                // Unsupported non-voice capabilities remain disabled without throwing errors.
            }
        }
        mainHandler.post(this::publishCurrentCapabilities);
        if (stateSyncCallback != null) {
            mainHandler.post(stateSyncCallback);
        }
    }

    @Override
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        return PassportCallSession.buildDefaultVoiceProfile(callSessionType, callType);
    }

    @Override
    public ImsCallSessionImplBase createCallSession(ImsCallProfile profile) {
        CallStateEngine.SessionHandle handle = engine.createOutgoingSession();
        return new PassportCallSession(handle, profile, mainHandler);
    }

    @Override
    public int shouldProcessCall(String[] numbers) {
        boolean platformEmergency = false;
        if (numbers != null) {
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    for (String n : numbers) {
                        if (n != null && tm.isEmergencyNumber(n)) {
                            platformEmergency = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        int decision = engine.shouldProcessCall(numbers, platformEmergency);
        return decision == CallStateEngine.PROCESS_CALL_IMS
                ? MmTelFeature.PROCESS_CALL_IMS
                : MmTelFeature.PROCESS_CALL_CSFB;
    }

    @Override
    public void onFeatureReady() {
        featureReadyActive = true;
        setFeatureState(ImsFeature.STATE_READY);
        if (stateSyncCallback != null) {
            mainHandler.post(stateSyncCallback);
            mainHandler.postDelayed(stateSyncCallback, 250L);
        }
    }

    @Override
    public void onFeatureRemoved() {
        featureReadyActive = false;
    }
}
