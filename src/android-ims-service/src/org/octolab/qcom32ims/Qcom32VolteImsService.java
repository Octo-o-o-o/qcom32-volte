package org.octolab.qcom32ims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
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
 * Production phone-local Android 11 ImsService v2.6 for 32-bit Qualcomm Snapdragon platforms
 * (verified on BlackBerry Passport wolverine / msm8974).
 *
 * Runs in an isolated process (:qcom32ims) under android.uid.phone (UID 1001, u:r:radio:s0)
 * with zero extra manifest permissions, bridging Android 11 Telecom / Telephony ImsPhone
 * to QCRIL /dev/socket/qmux_radio/rild_ims0 and Hexagon Stage 6A autonomous VoLTE.
 *
 * Backend connection starts lazily on createMmTelFeature(0) / enableIms(0) so transient
 * ImsResolver dynamic-feature-query service instances never touch rild_ims0.
 */
public final class Qcom32VolteImsService extends ImsService {
    static final String SERVICE_VERSION = "2.6-qcom32-volte";
    private static final String TAG = "Qcom32VolteIms";
    private static final int KEY_VOLTE_PROVISIONING_STATUS = 10;
    private static final int KEY_VT_PROVISIONING_STATUS = 11;
    private static final int KEY_WFC_PROVISIONING_STATUS = 28;
    private static final long QMI_CACHE_TTL_MS = 2500L;
    private static final long NOTIFICATION_SETTLE_DELAY_MS = 26000L;

    private static volatile Qcom32VolteImsService activeInstance;
    private static final Object QMI_PROBE_LOCK = new Object();
    private static long lastQmiProbeElapsedMs = -100000L;
    private static QmiProbeSummaryParser.Summary lastQmiSummary = QmiProbeSummaryParser.Summary.unknown();

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
            if (!CarrierConfigBootstrapReceiver.isBootstrapDisabled(Qcom32VolteImsService.this)) {
                String act = intent != null ? intent.getAction() : "null";
                if ("android.intent.action.SIM_STATE_CHANGED".equals(act)) {
                    String ss = intent != null ? intent.getStringExtra("ss") : null;
                    if ("ABSENT".equals(ss)) {
                        VolteNotificationManager.onSimAbsentAsync(Qcom32VolteImsService.this);
                        return;
                    }
                    if ("LOADED".equals(ss) || "READY".equals(ss)) {
                        rescheduleSettleNotificationCheck("svc_sim_" + ss);
                        if (rilClient != null && backendStarted) {
                            rilClient.requestImmediateRefresh();
                        }
                    }
                }
                CarrierConfigBootstrapReceiver.applyOverrideIfNeeded(
                        Qcom32VolteImsService.this, "svc_guard:" + act, false);
            }
        }
    };

    private final Runnable registeredNotifyRunnable = new Runnable() {
        @Override
        public void run() {
            VolteNotificationManager.evaluateNowAsync(
                    Qcom32VolteImsService.this, "ims_registered", false);
        }
    };

    private final Runnable settleNotifyRunnable = new Runnable() {
        @Override
        public void run() {
            VolteNotificationManager.evaluateNowAsync(
                    Qcom32VolteImsService.this, "svc_settle_26s", true);
        }
    };

    private RilImsCoreClient rilClient;
    private CallStateEngine callEngine;
    private volatile Qcom32MmTelFeature mmTelFeature;
    private volatile boolean backendStarted;
    private volatile boolean guardRegistered;
    private volatile String selinuxIdentity = "unknown";
    private volatile int lastPublishedFrameworkReg = -1;

    static boolean isFeatureBound() {
        Qcom32VolteImsService inst = activeInstance;
        return inst != null && inst.mmTelFeature != null;
    }

    static void schedulePostEventNotificationSettle(String reason) {
        Qcom32VolteImsService inst = activeInstance;
        if (inst != null) {
            inst.rescheduleSettleNotificationCheck(reason);
        }
    }

    private void rescheduleSettleNotificationCheck(String reason) {
        mainHandler.removeCallbacks(settleNotifyRunnable);
        mainHandler.postDelayed(settleNotifyRunnable, NOTIFICATION_SETTLE_DELAY_MS);
    }

    static String triggerActiveInstanceRefresh() {
        Qcom32VolteImsService inst = activeInstance;
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
            Qcom32MmTelFeature feat = mmTelFeature;
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
                if (!CarrierConfigBootstrapReceiver.isBootstrapDisabled(Qcom32VolteImsService.this)) {
                    CarrierConfigBootstrapReceiver.applyOverrideIfNeeded(
                            Qcom32VolteImsService.this, "ims_service_bound", false);
                }
                rescheduleSettleNotificationCheck("backend_started");
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
        Qcom32MmTelFeature feat = mmTelFeature;
        if (feat == null) {
            feat = new Qcom32MmTelFeature(
                    this,
                    callEngine,
                    mainHandler,
                    this::syncFrameworkRegistrationAndCapabilities);
            mmTelFeature = feat;
        } else {
            feat.markReadyPostSetup();
        }
        final Qcom32MmTelFeature boundFeat = feat;
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

        Qcom32MmTelFeature feat = mmTelFeature;
        if (feat != null) {
            feat.publishCurrentCapabilities();
        }
        if (desiredReg == RilImsWire.REG_STATE_REGISTERED && callEngine.isVoiceImsReady()) {
            mainHandler.removeCallbacks(registeredNotifyRunnable);
            mainHandler.postDelayed(registeredNotifyRunnable, 1200L);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        out.println("QCOM32_VOLTE_IMS_V1");
        out.println("version=" + SERVICE_VERSION);
        out.println("identity=" + selinuxIdentity);
        out.println("bootstrap=" + CarrierConfigBootstrapReceiver.getLastBootstrapSummary());
        out.println("notification=" + VolteNotificationManager.getLastSummary()
                + " kind=" + VolteNotificationManager.getLastKind());
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
        Qcom32MmTelFeature feat = mmTelFeature;
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

    static Bundle buildSanitizedStatusBundle(Context context) {
        Bundle b = new Bundle();
        b.putString("version", SERVICE_VERSION);

        Qcom32VolteImsService inst = activeInstance;
        boolean serviceRunning = inst != null;
        boolean rilConnected = false;
        int modemRegState = RilImsWire.REG_STATE_NOT_REGISTERED;
        boolean modemVoiceLte = false;
        boolean voiceImsReady = false;
        boolean featureReady = false;
        int publishedReg = -1;

        if (inst != null) {
            RilImsCoreClient rc = inst.rilClient;
            if (rc != null && inst.backendStarted) {
                rilConnected = rc.isConnected();
                modemRegState = rc.getLastRegState();
                modemVoiceLte = rc.isLastVoiceEnabledOnLte();
            }
            CallStateEngine ce = inst.callEngine;
            if (ce != null) {
                voiceImsReady = ce.isVoiceImsReady();
            }
            Qcom32MmTelFeature feat = inst.mmTelFeature;
            featureReady = feat != null && feat.isFeatureReadyCalled();
            publishedReg = inst.lastPublishedFrameworkReg;
        }

        QmiProbeSummaryParser.Summary qmi = queryCachedQmiSummary(context);
        String simOp = readSafeSimOperator(context);
        String netType = readSafeNetworkType();
        boolean fwRegistered = publishedReg == RilImsWire.REG_STATE_REGISTERED;
        boolean healthy = serviceRunning
                && rilConnected
                && voiceImsReady
                && featureReady
                && fwRegistered
                && qmi.isRegisteredVoipReady();

        b.putBoolean("service_running", serviceRunning);
        b.putBoolean("ril_connected", rilConnected);
        b.putInt("modem_reg_state", modemRegState);
        b.putBoolean("modem_voice_lte", modemVoiceLte);
        b.putBoolean("voice_ims_ready", voiceImsReady);
        b.putBoolean("feature_ready", featureReady);
        b.putInt("published_framework_reg", publishedReg);
        b.putInt("pdp_connected", qmi.pdpConnected);
        b.putInt("reg_status", qmi.regStatus);
        b.putInt("legacy_reg", qmi.legacyReg);
        b.putInt("voip", qmi.voip);
        b.putInt("voip_rat", qmi.voipRat);
        b.putInt("sms", qmi.sms);
        b.putInt("test_mode", qmi.testMode);
        b.putBoolean("qmi_voip_ready", qmi.isRegisteredVoipReady());
        b.putString("sim_operator", simOp);
        b.putString("network_type", netType);
        b.putBoolean("volte_healthy", healthy);
        b.putString("notification_kind", VolteNotificationManager.getLastKind());
        b.putString("notification_title", VolteNotificationManager.getLastTitle());
        return b;
    }

    private static String resolveImsaProbeBinaryPath(Context context) {
        String nativeLibDir = (context != null && context.getApplicationInfo() != null)
                ? context.getApplicationInfo().nativeLibraryDir
                : "";
        String apkPath = (nativeLibDir != null && !nativeLibDir.trim().isEmpty())
                ? (nativeLibDir.trim().replaceAll("/+$", "") + "/libimsa_probe.so")
                : "";
        String[] candidates = new String[] {
            apkPath,
            "/system_ext/bin/libimsa_probe.so",
            "/system/bin/libimsa_probe.so"
        };
        java.util.ArrayList<String> present = new java.util.ArrayList<>(3);
        for (String p : candidates) {
            if (p != null && !p.isEmpty()) {
                java.io.File f = new java.io.File(p);
                if (f.isFile() && f.canExecute()) {
                    present.add(p);
                }
            }
        }
        return QmiProbeSummaryParser.selectProbeBinaryPath(
                nativeLibDir, present.toArray(new String[0]));
    }

    private static QmiProbeSummaryParser.Summary queryCachedQmiSummary(Context context) {
        if (context == null) {
            return QmiProbeSummaryParser.Summary.unknown();
        }
        synchronized (QMI_PROBE_LOCK) {
            long now = SystemClock.elapsedRealtime();
            if ((now - lastQmiProbeElapsedMs) < QMI_CACHE_TTL_MS) {
                return lastQmiSummary;
            }
            String raw = runNativeQmiProbeRaw(resolveImsaProbeBinaryPath(context));
            lastQmiSummary = QmiProbeSummaryParser.parse(raw);
            lastQmiProbeElapsedMs = SystemClock.elapsedRealtime();
            return lastQmiSummary;
        }
    }

    private static String runNativeQmiProbeRaw(String binPath) {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder(binPath, "--probe-all").redirectErrorStream(true).start();
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
            }, "volte-qmi-status");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(12L, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                p.waitFor(1L, TimeUnit.SECONDS);
            }
            reader.join(1000L);
            synchronized (buf) {
                return buf.toString();
            }
        } catch (Exception ex) {
            return "";
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    static String readSafeSimOperator(Context context) {
        try {
            if (context != null) {
                TelephonyManager tm = context.getSystemService(TelephonyManager.class);
                if (tm != null) {
                    String op = QmiProbeSummaryParser.sanitizeSimOperator(tm.getSimOperator());
                    if (!op.isEmpty()) {
                        return op;
                    }
                }
            }
        } catch (Exception ignored) { }
        return QmiProbeSummaryParser.sanitizeSimOperator(readQuickProp("gsm.sim.operator.numeric"));
    }

    private static String readSafeNetworkType() {
        return QmiProbeSummaryParser.sanitizeNetworkType(readQuickProp("gsm.network.type"));
    }

    private static String readQuickProp(String key) {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            byte[] buf = new byte[64];
            int n = p.getInputStream().read(buf);
            p.waitFor(2L, TimeUnit.SECONDS);
            if (n > 0) {
                return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        return "";
    }

    private void dumpOnDemandQmiProbe(PrintWriter out) {
        String raw = runNativeQmiProbeRaw(resolveImsaProbeBinaryPath(this));
        if (raw.isEmpty()) {
            out.println("qmi_probe_err=EmptyOrFailed");
            return;
        }
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.println("qmi_probe=" + RilImsWire.redactPii(t));
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
            mainHandler.post(Qcom32VolteImsService.this::syncFrameworkRegistrationAndCapabilities);
        }

        @Override
        public void onRegistrationAndCapabilityChanged(
                int regState, boolean voiceEnabledOnLte, String serviceSummary) {
            mainHandler.post(Qcom32VolteImsService.this::syncFrameworkRegistrationAndCapabilities);
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
