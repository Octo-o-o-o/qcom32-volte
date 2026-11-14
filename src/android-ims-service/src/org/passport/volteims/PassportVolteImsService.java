package org.passport.volteims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsService;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsFeatureConfiguration;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Production phone-local Android 11 ImsService v2.3 for BlackBerry Passport (wolverine / msm8974).
 *
 * Runs in an isolated process (:volteims) under android.uid.phone (UID 1001, u:r:radio:s0)
 * with zero extra manifest permissions, bridging Android 11 Telecom / Telephony ImsPhone
 * to QCRIL /dev/socket/qmux_radio/rild_ims0 and Hexagon Stage 6A autonomous VoLTE.
 *
 * Backend connection starts lazily on createMmTelFeature(0) / enableIms(0) so transient
 * ImsResolver dynamic-feature-query service instances never touch rild_ims0.
 */
public final class PassportVolteImsService extends ImsService {
    private static final String TAG = "PassportVolteIms";
    private static final int KEY_VOLTE_PROVISIONING_STATUS = 10;
    private static final int KEY_VT_PROVISIONING_STATUS = 11;
    private static final int KEY_WFC_PROVISIONING_STATUS = 28;

    private static volatile PassportVolteImsService activeInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Integer> configInts = new ConcurrentHashMap<>();
    private final ImsRegistrationImplBase registrationImpl = new ImsRegistrationImplBase();
    private final ImsConfigImplBase configImpl = new ImsConfigImplBase() {
        @Override
        public int getConfigInt(int item) {
            Integer v = configInts.get(item);
            if (v != null) return v;
            if (item == KEY_VOLTE_PROVISIONING_STATUS) {
                return ProvisioningManager.PROVISIONING_VALUE_ENABLED;
            }
            if (item == KEY_VT_PROVISIONING_STATUS || item == KEY_WFC_PROVISIONING_STATUS) {
                return ProvisioningManager.PROVISIONING_VALUE_DISABLED;
            }
            return ImsConfigImplBase.CONFIG_RESULT_UNKNOWN;
        }

        @Override
        public int setConfig(int item, int value) {
            configInts.put(item, value);
            return ImsConfigImplBase.CONFIG_RESULT_SUCCESS;
        }

        @Override
        public int setConfig(int item, String value) {
            return ImsConfigImplBase.CONFIG_RESULT_SUCCESS;
        }
    };

    private final BroadcastReceiver carrierConfigGuardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CarrierConfigBootstrapReceiver.isBootstrapDisabled(PassportVolteImsService.this)) {
                String act = intent != null ? intent.getAction() : "null";
                CarrierConfigBootstrapReceiver.applyOverrideIfNeeded(
                        PassportVolteImsService.this, "svc_guard:" + act, false);
            }
        }
    };

    private RilImsCoreClient rilClient;
    private CallStateEngine callEngine;
    private volatile PassportMmTelFeature mmTelFeature;
    private volatile boolean backendStarted;
    private volatile boolean guardRegistered;
    private volatile String selinuxIdentity = "unknown";
    private volatile int lastPublishedFrameworkReg = -1;

    static boolean isFeatureBound() {
        PassportVolteImsService inst = activeInstance;
        return inst != null && inst.mmTelFeature != null;
    }

    static String triggerActiveInstanceRefresh() {
        PassportVolteImsService inst = activeInstance;
        if (inst == null || inst.rilClient == null || !inst.backendStarted) {
            return "no_active_service";
        }
        inst.rilClient.requestImmediateRefresh();
        inst.mainHandler.post(inst::syncFrameworkRegistrationAndCapabilities);
        return "refresh_requested:connected=" + inst.rilClient.isConnected()
                + ",reg=" + inst.rilClient.getLastRegState()
                + ",voiceLte=" + inst.rilClient.isLastVoiceEnabledOnLte();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        selinuxIdentity = readSelinuxIdentity();
        configInts.put(KEY_VOLTE_PROVISIONING_STATUS, ProvisioningManager.PROVISIONING_VALUE_ENABLED);
        configInts.put(KEY_VT_PROVISIONING_STATUS, ProvisioningManager.PROVISIONING_VALUE_DISABLED);
        configInts.put(KEY_WFC_PROVISIONING_STATUS, ProvisioningManager.PROVISIONING_VALUE_DISABLED);

        rilClient = new RilImsCoreClient(LocalSocketTransport::new, new BackendBridge());
        callEngine = new CallStateEngine(rilClient, (handle, isWaiting) -> {
            PassportMmTelFeature feat = mmTelFeature;
            if (feat != null) {
                feat.dispatchIncomingCall(handle, isWaiting);
            } else {
                handle.rejectIncoming(RilImsWire.IMS_REASON_LOCAL_IMS_SERVICE_DOWN);
            }
        });
        Log.i(TAG, "onCreate " + selinuxIdentity);
    }

    private synchronized void ensureBackendStarted() {
        activeInstance = this;
        if (!backendStarted && rilClient != null) {
            backendStarted = true;
            rilClient.start();
            Log.i(TAG, "backendStarted " + selinuxIdentity);
            mainHandler.post(() -> {
                if (!CarrierConfigBootstrapReceiver.isBootstrapDisabled(PassportVolteImsService.this)) {
                    CarrierConfigBootstrapReceiver.applyOverrideIfNeeded(
                            PassportVolteImsService.this, "ims_service_bound", false);
                }
            });
        }
        if (!guardRegistered) {
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
                f.addAction("android.intent.action.SIM_STATE_CHANGED");
                registerReceiver(carrierConfigGuardReceiver, f);
                guardRegistered = true;
            } catch (Exception ex) {
                Log.w(TAG, "guard register err=" + ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void onDestroy() {
        if (guardRegistered) {
            try {
                unregisterReceiver(carrierConfigGuardReceiver);
            } catch (Exception ignored) { }
            guardRegistered = false;
        }
        if (backendStarted && rilClient != null) {
            rilClient.stop();
            backendStarted = false;
        }
        mmTelFeature = null;
        if (activeInstance == this) {
            activeInstance = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }

    @Override
    public ImsFeatureConfiguration querySupportedImsFeatures() {
        // Advertise MMTEL on slot 0 only (emergency calls stay on CSFB).
        return new ImsFeatureConfiguration.Builder()
                .addFeature(0, ImsFeature.FEATURE_MMTEL)
                .build();
    }

    @Override
    public void readyForFeatureCreation() {
        Log.i(TAG, "readyForFeatureCreation");
    }

    @Override
    public MmTelFeature createMmTelFeature(int slotId) {
        Log.i(TAG, "createMmTelFeature slotId=" + slotId);
        if (slotId != 0) return null;
        ensureBackendStarted();
        PassportMmTelFeature feat = mmTelFeature;
        if (feat == null) {
            feat = new PassportMmTelFeature(
                    this,
                    callEngine,
                    mainHandler,
                    this::syncFrameworkRegistrationAndCapabilities);
            mmTelFeature = feat;
        } else {
            feat.markReadyPostSetup();
        }
        final PassportMmTelFeature boundFeat = feat;
        mainHandler.post(() -> {
            boundFeat.markReadyPostSetup();
            syncFrameworkRegistrationAndCapabilities();
        });
        mainHandler.postDelayed(this::syncFrameworkRegistrationAndCapabilities, 300L);
        mainHandler.postDelayed(this::syncFrameworkRegistrationAndCapabilities, 1000L);
        return feat;
    }

    @Override
    public ImsConfigImplBase getConfig(int slotId) {
        return configImpl;
    }

    @Override
    public ImsRegistrationImplBase getRegistration(int slotId) {
        mainHandler.post(this::syncFrameworkRegistrationAndCapabilities);
        mainHandler.postDelayed(this::syncFrameworkRegistrationAndCapabilities, 300L);
        mainHandler.postDelayed(this::syncFrameworkRegistrationAndCapabilities, 1000L);
        return registrationImpl;
    }

    @Override
    public void enableIms(int slotId) {
        Log.i(TAG, "enableIms slotId=" + slotId);
        if (slotId != 0) return;
        ensureBackendStarted();
        callEngine.setFrameworkEnabled(true);
        if (rilClient != null) {
            rilClient.requestImmediateRefresh();
        }
        mainHandler.post(this::syncFrameworkRegistrationAndCapabilities);
        mainHandler.postDelayed(this::syncFrameworkRegistrationAndCapabilities, 500L);
    }

    @Override
    public void disableIms(int slotId) {
        Log.i(TAG, "disableIms slotId=" + slotId);
        if (slotId != 0) return;
        callEngine.setFrameworkEnabled(false);
        mainHandler.post(this::syncFrameworkRegistrationAndCapabilities);
    }

    private void syncFrameworkRegistrationAndCapabilities() {
        if (callEngine == null || rilClient == null) return;
        int modemReg = rilClient.getLastRegState();
        boolean voiceLte = rilClient.isLastVoiceEnabledOnLte();
        boolean connected = backendStarted && rilClient.isConnected();
        callEngine.updateModemRegistrationAndCapability(
                connected ? modemReg : RilImsWire.REG_STATE_NOT_REGISTERED,
                connected && voiceLte);

        int desiredReg;
        if (!callEngine.isFrameworkEnabled() || !connected) {
            desiredReg = RilImsWire.REG_STATE_NOT_REGISTERED;
        } else {
            desiredReg = modemReg;
        }

        try {
            if (desiredReg == RilImsWire.REG_STATE_REGISTERED) {
                registrationImpl.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
            } else if (desiredReg == RilImsWire.REG_STATE_REGISTERING) {
                registrationImpl.onRegistering(ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
            } else {
                registrationImpl.onDeregistered(new ImsReasonInfo(
                        ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, 0, "ims_not_registered"));
            }
            lastPublishedFrameworkReg = desiredReg;
        } catch (Exception ex) {
            Log.w(TAG, "registration sync err=" + ex.getClass().getSimpleName());
        }

        PassportMmTelFeature feat = mmTelFeature;
        if (feat != null) {
            feat.publishCurrentCapabilities();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        out.println("PASSPORT_VOLTE_IMS_V1");
        out.println("version=2.3-passport-volte");
        out.println("identity=" + selinuxIdentity);
        out.println("bootstrap=" + CarrierConfigBootstrapReceiver.getLastBootstrapSummary());
        out.println("backendStarted=" + backendStarted);
        if (rilClient != null) {
            out.println("rilConnected=" + rilClient.isConnected()
                    + " rildPid=" + rilClient.getLastPeerPid()
                    + " modemRegState=" + rilClient.getLastRegState()
                    + " modemVoiceLte=" + rilClient.isLastVoiceEnabledOnLte()
                    + " serviceSummary=" + rilClient.getLastServiceSummary());
            out.println("counters tx=" + rilClient.getTxCount()
                    + " rxSol=" + rilClient.getRxSolicitedCount()
                    + " rxUnsol=" + rilClient.getRxUnsolCount()
                    + " reconnects=" + rilClient.getReconnectCount());
        }
        if (callEngine != null) {
            out.println("engine frameworkEnabled=" + callEngine.isFrameworkEnabled()
                    + " voiceCapConfigured=" + callEngine.isVoiceCapabilityConfigured()
                    + " voiceImsReady=" + callEngine.isVoiceImsReady()
                    + " activeSessions=" + callEngine.getActiveSessionCount()
                    + " publishedFrameworkReg=" + lastPublishedFrameworkReg);
        }
        PassportMmTelFeature feat = mmTelFeature;
        out.println("mmTelFeatureCreated=" + (feat != null)
                + " featureReadyCalled=" + (feat != null && feat.isFeatureReadyCalled()));

        boolean runQmiProbe = false;
        if (args != null) {
            for (String a : args) {
                if ("--qmi-probe".equals(a)) {
                    runQmiProbe = true;
                    break;
                }
            }
        }
        if (runQmiProbe) {
            dumpOnDemandQmiProbe(out);
        }

        if (rilClient != null) {
            List<String> evs = rilClient.getRecentEventsSnapshot();
            out.println("recentEventsCount=" + evs.size());
            int start = Math.max(0, evs.size() - 80);
            for (int i = start; i < evs.size(); i++) {
                out.println("event=" + RilImsWire.redactPii(evs.get(i)));
            }
        }
    }

    private void dumpOnDemandQmiProbe(PrintWriter out) {
        java.lang.Process p = null;
        try {
            String bin = getApplicationInfo().nativeLibraryDir + "/libimsa_probe.so";
            p = new ProcessBuilder(bin, "--probe-all").redirectErrorStream(true).start();
            final java.lang.Process proc = p;
            final StringBuilder buf = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] chunk = new byte[2048];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        synchronized (buf) {
                            if (buf.length() < 65536) {
                                buf.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }, "volte-qmi-dump");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(15L, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                p.waitFor(1L, TimeUnit.SECONDS);
            }
            reader.join(1000L);
            String raw;
            synchronized (buf) {
                raw = buf.toString();
            }
            for (String line : raw.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    out.println("qmi_probe=" + RilImsWire.redactPii(t));
                }
            }
        } catch (Exception ex) {
            out.println("qmi_probe_err=" + ex.getClass().getSimpleName());
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static String readSelinuxIdentity() {
        String ctx = "unknown";
        try (InputStream in = new FileInputStream("/proc/self/attr/current")) {
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n > 0) {
                int len = n;
                while (len > 0 && (buf[len - 1] == 0 || buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
                    len--;
                }
                ctx = new String(buf, 0, len, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { }
        return "uid=" + Process.myUid() + " pid=" + Process.myPid() + " domain=" + ctx;
    }

    private final class BackendBridge implements RilImsCoreClient.Listener {
        @Override
        public void onBackendConnectionChanged(boolean connected, int peerPid) {
            if (!connected && callEngine != null) {
                callEngine.onBackendDisconnected();
            }
            mainHandler.post(PassportVolteImsService.this::syncFrameworkRegistrationAndCapabilities);
        }

        @Override
        public void onRegistrationAndCapabilityChanged(
                int regState, boolean voiceEnabledOnLte, String serviceSummary) {
            mainHandler.post(PassportVolteImsService.this::syncFrameworkRegistrationAndCapabilities);
        }

        @Override
        public void onCallListSnapshot(List<RilImsWire.DriverCall> calls, boolean authoritativeSolicited) {
            if (callEngine != null) {
                callEngine.onCallListSnapshot(calls, authoritativeSolicited);
            }
        }

        @Override
        public void onRingbackTone(boolean playLocalTone) {
            if (callEngine != null) {
                callEngine.onRingbackTone(playLocalTone);
            }
        }

        @Override
        public void onSuppSvcNotification(RilImsWire.SuppSvcInfo info) {
            if (callEngine != null) {
                callEngine.onSuppSvcNotification(info);
            }
        }

        @Override
        public boolean hasLiveCalls() {
            return callEngine != null && callEngine.hasLiveCalls();
        }

        @Override
        public void onDiagnosticLog(String redactedEvent) {
            Log.i(TAG, redactedEvent);
        }
    }
}
