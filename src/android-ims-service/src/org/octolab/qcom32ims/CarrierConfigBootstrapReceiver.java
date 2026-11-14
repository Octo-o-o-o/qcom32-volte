package org.octolab.qcom32ims;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Idempotent Direct-Boot-aware BroadcastReceiver that binds Android 11 ImsResolver
 * to org.passport.volteims (MMTEL only, leaving RCS untouched) and persists CarrierConfig
 * VoLTE overrides across reboots via CarrierConfigManager.overrideConfig(subId, bundle, true)
 * (verified in EP40D/EP40E/EP40F against LineageOS 18.1 CarrierConfigLoader.java & ImsResolver.java).
 *
 * Checks getConfigForSubId(subId) BEFORE calling overrideConfig(subId, bundle, true) so
 * ACTION_CARRIER_CONFIG_CHANGED never re-triggers an override loop, flushes ext4 buffers
 * before reboot, and deletes the persistent -override XML cleanly on clear_override.
 */
public final class CarrierConfigBootstrapReceiver extends android.content.BroadcastReceiver {
    static final String TAG = "Qcom32VolteBootstrap";
    static final String PACKAGE_NAME = "org.octolab.qcom32ims";
    private static final String PREFS_NAME = "volte_bootstrap_state";
    private static final String KEY_SEEDED_PREFIX = "seeded_advanced_calling_sub_";
    private static final String KEY_ORIG_NET_MASK_PREFIX = "orig_net_bitmask_sub_";
    private static final String KEY_ORIG_SUB_NET_MODE_PREFIX = "orig_sub_pref_net_mode_";
    private static final String KEY_ORIG_GLOBAL_NET_MODE = "orig_global_pref_net_mode";
    private static final String KEY_ORIG_FALLBACK_PROP = "orig_persist_radio_fallbackmode";
    private static final String KEY_FALLBACK_MANAGED = "wolverine_fallback_managed";
    private static final String PROP_FALLBACK_MODE = "persist.radio.fallbackmode";
    private static final String KEY_ROLLBACK_DISABLED = "rollback_disabled";
    private static final AtomicBoolean RADIO_CYCLE_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile String lastBootstrapSummary = "never";
    private static volatile boolean lastApplyComplete;

    static String getLastBootstrapSummary() {
        return lastBootstrapSummary;
    }

    static boolean isLastApplyComplete() {
        return lastApplyComplete;
    }

    static boolean isBootstrapDisabled(Context context) {
        if (context == null) return false;
        try {
            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_ROLLBACK_DISABLED, false);
        } catch (Exception ex) {
            return false;
        }
    }

    static void setBootstrapDisabled(Context context, boolean disabled) {
        if (context == null) return;
        try {
            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_ROLLBACK_DISABLED, disabled).commit();
        } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || isBootstrapDisabled(context)) {
            return;
        }
        String action = intent != null ? intent.getAction() : "null";
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            applyOverrideIfNeeded(context, "rx:MY_PACKAGE_REPLACED", false);
            VolteNotificationManager.onPackageReplacedAsync(context);
            Qcom32VolteImsService.schedulePostEventNotificationSettle("rx:MY_PACKAGE_REPLACED");
            if (!Qcom32VolteImsService.isFeatureBound()) {
                startBootstrapWatcherQuietly(context);
            }
            return;
        }
        if ("android.intent.action.SIM_STATE_CHANGED".equals(action)) {
            String ss = intent != null ? intent.getStringExtra("ss") : null;
            if ("ABSENT".equals(ss)) {
                VolteNotificationManager.onSimAbsentAsync(context);
                return;
            }
            if ("LOADED".equals(ss) || "READY".equals(ss) || "IMSI".equals(ss)) {
                applyOverrideIfNeeded(context, "rx:SIM_" + ss, false);
                startBootstrapWatcherQuietly(context);
                Qcom32VolteImsService.schedulePostEventNotificationSettle("sim_" + ss);
                return;
            }
        }
        applyOverrideIfNeeded(context, "rx:" + action, false);
        if ("android.intent.action.BOOT_COMPLETED".equals(action)
                || "android.intent.action.USER_UNLOCKED".equals(action)
                || "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED".equals(action)) {
            VolteNotificationManager.evaluateNowAsync(context, "rx:" + action, false);
            Qcom32VolteImsService.schedulePostEventNotificationSettle("rx:" + action);
        }
        if (!Qcom32VolteImsService.isFeatureBound()) {
            startBootstrapWatcherQuietly(context);
        }
    }

    static String adminEnableAndApply(Context context) {
        return adminEnableAndApply(context, false);
    }

    static String adminEnableAndApply(Context context, boolean suppressLiveRadioCycle) {
        setBootstrapDisabled(context, false);
        String res = applyOverrideIfNeeded(context, "admin_apply", true, suppressLiveRadioCycle);
        if (!Qcom32VolteImsService.isFeatureBound()) {
            startBootstrapWatcherQuietly(context);
        }
        return res;
    }

    private static void startBootstrapWatcherQuietly(Context context) {
        try {
            context.startService(new Intent(context, VolteBootstrapService.class));
        } catch (Exception ex) {
            Log.w(TAG, "startBootstrapWatcher err=" + ex.getClass().getSimpleName());
        }
    }

    private static void flushFilesystemQuietly() {
        try {
            java.lang.Process p = new ProcessBuilder("/system/bin/sync").start();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Throwable ignored) { }
    }

    private static boolean isVolteOverridePresent(PersistableBundle bundle) {
        if (bundle == null) return false;
        String mmtelPkg = bundle.getString(
                CarrierConfigManager.KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING, "");
        boolean avail = bundle.getBoolean(
                CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false);
        boolean prov = bundle.getBoolean(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONED_BOOL, false);
        boolean defOn = bundle.getBoolean(
                CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, false);
        return PACKAGE_NAME.equals(mmtelPkg) && avail && prov && defOn;
    }

    static synchronized String applyOverrideIfNeeded(Context context, String trigger, boolean force) {
        return applyOverrideIfNeeded(context, trigger, force, false);
    }

    static synchronized String applyOverrideIfNeeded(
            Context context, String trigger, boolean force, boolean suppressLiveRadioCycle) {
        if (context == null) return "null_context";
        if (!force && isBootstrapDisabled(context)) {
            lastApplyComplete = false;
            lastBootstrapSummary = trigger + ":disabled_for_rollback";
            return lastBootstrapSummary;
        }
        try {
            CarrierConfigManager ccm = (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            SubscriptionManager sm = SubscriptionManager.from(context);
            if (ccm == null || sm == null) {
                lastApplyComplete = false;
                lastBootstrapSummary = trigger + ":missing_managers";
                return lastBootstrapSummary;
            }

            int[] activeSubs = sm.getActiveSubscriptionIdList();
            if (activeSubs == null || activeSubs.length == 0) {
                int defSub = SubscriptionManager.getDefaultSubscriptionId();
                if (SubscriptionManager.isValidSubscriptionId(defSub)) {
                    activeSubs = new int[] { defSub };
                } else {
                    lastApplyComplete = false;
                    lastBootstrapSummary = trigger + ":no_active_sub";
                    return lastBootstrapSummary;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(trigger).append(":subs=").append(activeSubs.length);
            boolean appliedOrVerifiedAny = false;
            boolean wrotePersistent = false;

            for (int subId : activeSubs) {
                if (!SubscriptionManager.isValidSubscriptionId(subId)) continue;
                PersistableBundle cur = ccm.getConfigForSubId(subId);
                boolean alreadyApplied = !force && isVolteOverridePresent(cur);

                if (!alreadyApplied) {
                    PersistableBundle override = buildVolteOverrideBundle();
                    // Persist -override XML inside com.android.phone so CarrierConfigLoader
                    // restores it automatically on every boot when SIM reaches SIM_STATE_LOADED.
                    ccm.overrideConfig(subId, override, true);
                    wrotePersistent = true;
                    boolean readbackOk = isVolteOverridePresent(ccm.getConfigForSubId(subId));
                    if (!readbackOk) {
                        for (int attempt = 0; attempt < 10 && !readbackOk; attempt++) {
                            try {
                                Thread.sleep(100L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            readbackOk = isVolteOverridePresent(ccm.getConfigForSubId(subId));
                        }
                    }
                    sb.append(",sub").append(subId).append(readbackOk ? "=PERSISTED_OK" : "=PERSIST_READBACK_MISMATCH");
                    if (readbackOk) {
                        appliedOrVerifiedAny = true;
                    }
                } else {
                    sb.append(",sub").append(subId).append("=OK");
                    appliedOrVerifiedAny = true;
                }

                seedAdvancedCallingOnce(context, subId, force, sb);
                alignWolverineChinaTelecomModeIfNeeded(context, subId, suppressLiveRadioCycle, sb);
            }

            if (wrotePersistent) {
                flushFilesystemQuietly();
            }

            lastApplyComplete = appliedOrVerifiedAny;
            lastBootstrapSummary = sb.toString();
            Log.i(TAG, lastBootstrapSummary);
            return lastBootstrapSummary;
        } catch (Exception ex) {
            lastApplyComplete = false;
            lastBootstrapSummary = trigger + ":err=" + ex.getClass().getSimpleName() + ":" + ex.getMessage();
            Log.w(TAG, lastBootstrapSummary);
            return lastBootstrapSummary;
        }
    }

    static synchronized String clearOverrideAll(Context context, String trigger) {
        if (context == null) return "null_context";
        setBootstrapDisabled(context, true);
        VolteNotificationManager.cancelAllAndReset(context);
        restoreSavedNetworkBitmasksQuietly(context);
        lastApplyComplete = false;
        try {
            context.stopService(new Intent(context, VolteBootstrapService.class));
        } catch (Exception ignored) { }
        try {
            CarrierConfigManager ccm = (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            SubscriptionManager sm = SubscriptionManager.from(context);
            if (ccm == null || sm == null) {
                lastBootstrapSummary = trigger + ":missing_managers";
                return lastBootstrapSummary;
            }
            int[] subs = sm.getCompleteActiveSubscriptionIdList();
            if (subs == null || subs.length == 0) {
                int defSub = SubscriptionManager.getDefaultSubscriptionId();
                subs = SubscriptionManager.isValidSubscriptionId(defSub) ? new int[] { defSub } : new int[0];
            }
            for (int subId : subs) {
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    // Deletes persistent -override XML and clears mPersistentOverrideConfigs + mOverrideConfigs.
                    ccm.overrideConfig(subId, null, true);
                }
            }
            flushFilesystemQuietly();
            lastBootstrapSummary = trigger + ":cleared=" + subs.length;
            Log.i(TAG, lastBootstrapSummary);
            return lastBootstrapSummary;
        } catch (Exception ex) {
            lastBootstrapSummary = trigger + ":clear_err=" + ex.getClass().getSimpleName();
            Log.w(TAG, lastBootstrapSummary);
            return lastBootstrapSummary;
        }
    }

    private static PersistableBundle buildVolteOverrideBundle() {
        PersistableBundle b = new PersistableBundle();
        // Set ONLY KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING so ImsResolver leaves RCS untouched.
        b.putString(CarrierConfigManager.KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING, PACKAGE_NAME);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONED_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL, false);
        b.putBoolean(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_DISPLAY_HD_AUDIO_PROPERTY_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, true);
        // Keep emergency calls on CSFB first and disable unsupported VT/WFC truthfully
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL, false);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, false);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false);
        return b;
    }

    private static void seedAdvancedCallingOnce(
            Context context, int subId, boolean force, StringBuilder sb) {
        try {
            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String prefKey = KEY_SEEDED_PREFIX + subId;
            if (force || !prefs.getBoolean(prefKey, false)) {
                ImsMmTelManager mgr = ImsMmTelManager.createForSubscriptionId(subId);
                if (!mgr.isAdvancedCallingSettingEnabled()) {
                    mgr.setAdvancedCallingSettingEnabled(true);
                    sb.append("(adv=enabled)");
                } else {
                    sb.append("(adv=already_on)");
                }
                prefs.edit().putBoolean(prefKey, true).apply();
            }
        } catch (Exception ex) {
            sb.append("(adv_err=").append(ex.getClass().getSimpleName()).append(')');
        }
    }

    private static String getSystemProperty(String key, String defVal) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method m = cls.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, defVal);
            return v != null ? String.valueOf(v) : defVal;
        } catch (Throwable ex) {
            return defVal;
        }
    }

    private static boolean writeFallbackModeProperty(String targetVal) {
        String desired = targetVal != null ? targetVal.trim() : "";
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method m = cls.getMethod("set", String.class, String.class);
            m.invoke(null, PROP_FALLBACK_MODE, desired);
        } catch (Throwable ex) {
            Log.w(TAG, "SystemProperties.set(" + PROP_FALLBACK_MODE + ") err=" + ex.getClass().getSimpleName());
        }
        if (!desired.equals(getSystemProperty(PROP_FALLBACK_MODE, "").trim())) {
            try {
                java.lang.Process p = new ProcessBuilder("/system/bin/setprop", PROP_FALLBACK_MODE, desired).start();
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (Throwable ignored) { }
        }
        return desired.equals(getSystemProperty(PROP_FALLBACK_MODE, "").trim());
    }

    private static void scheduleOneShotRadioPowerReload(Context context, final int subId, final String reason) {
        if (context == null || !RADIO_CYCLE_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                TelephonyManager tm = appCtx.getSystemService(TelephonyManager.class);
                if (tm == null || tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                    Log.i(TAG, "skip radio reload (" + reason + "): call not idle");
                    return;
                }
                TelephonyManager tmSub = tm.createForSubscriptionId(subId);
                Method setPower = TelephonyManager.class.getMethod("setRadioPower", boolean.class);
                Object offRes = setPower.invoke(tmSub, Boolean.FALSE);
                Thread.sleep(1000L);
                Object onRes = setPower.invoke(tmSub, Boolean.TRUE);
                Log.i(TAG, "radio power reloaded fallbackmode (" + reason + ") off=" + offRes + " on=" + onRes);
                Qcom32VolteImsService.schedulePostEventNotificationSettle("radio_reload_" + reason);
            } catch (Throwable ex) {
                Log.w(TAG, "radio reload err=" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
            } finally {
                RADIO_CYCLE_IN_FLIGHT.set(false);
            }
        }, "wolverine-fb-reload");
        t.setDaemon(true);
        t.start();
    }

    private static void alignWolverineChinaTelecomModeIfNeeded(
            Context context, int subId, boolean suppressLiveRadioCycle, StringBuilder sb) {
        try {
            if (!VolteStatusNotificationLogic.isWolverineDevice(Build.DEVICE)) {
                return;
            }
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm == null) return;
            TelephonyManager tmSub = tm.createForSubscriptionId(subId);
            String simOp = QmiProbeSummaryParser.sanitizeSimOperator(tmSub.getSimOperator());
            if (simOp.isEmpty()) {
                simOp = Qcom32VolteImsService.readSafeSimOperator(context);
            }
            if (simOp.isEmpty()) {
                return;
            }

            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            ContentResolver cr = context.getContentResolver();
            String curProp = getSystemProperty(PROP_FALLBACK_MODE, "").trim();

            if (!VolteStatusNotificationLogic.isChinaTelecom(simOp)) {
                SharedPreferences.Editor ed = prefs.edit();
                boolean prefsChanged = false;
                int origMode = prefs.getInt(
                        KEY_ORIG_GLOBAL_NET_MODE,
                        VolteStatusNotificationLogic.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA);
                if (prefs.contains(KEY_ORIG_GLOBAL_NET_MODE) && cr != null) {
                    Settings.Global.putInt(cr, "preferred_network_mode", origMode);
                    ed.remove(KEY_ORIG_GLOBAL_NET_MODE);
                    prefsChanged = true;
                }
                String subModeKey = KEY_ORIG_SUB_NET_MODE_PREFIX + subId;
                if (prefs.contains(subModeKey) && cr != null) {
                    int origSubMode = prefs.getInt(subModeKey, origMode);
                    Settings.Global.putInt(
                            cr, "preferred_network_mode" + subId, origSubMode >= 0 ? origSubMode : origMode);
                    ed.remove(subModeKey);
                    prefsChanged = true;
                }
                String origMaskKey = KEY_ORIG_NET_MASK_PREFIX + subId;
                if (prefs.contains(origMaskKey)) {
                    long origMask = prefs.getLong(origMaskKey, 0L);
                    if (origMask > 0L) {
                        tmSub.setPreferredNetworkTypeBitmask(origMask);
                    }
                    ed.remove(origMaskKey);
                    prefsChanged = true;
                }
                boolean propRestored = false;
                if (prefs.getBoolean(KEY_FALLBACK_MANAGED, false)) {
                    String origProp = prefs.getString(KEY_ORIG_FALLBACK_PROP, "");
                    if (!origProp.equals(curProp)) {
                        propRestored = writeFallbackModeProperty(origProp);
                    }
                    ed.remove(KEY_FALLBACK_MANAGED);
                    ed.remove(KEY_ORIG_FALLBACK_PROP);
                    prefsChanged = true;
                }
                if (prefsChanged) {
                    ed.commit();
                    sb.append("(restored_non_ct=").append(origMode)
                            .append(",fb_restored=").append(propRestored).append(')');
                }
                if (propRestored && !suppressLiveRadioCycle) {
                    scheduleOneShotRadioPowerReload(context, subId, "restore_non_ct");
                }
                return;
            }

            int targetMode = VolteStatusNotificationLogic.NETWORK_MODE_LTE_GSM_WCDMA;
            long targetMask = VolteStatusNotificationLogic.BITMASK_LTE_GSM_WCDMA;
            String targetProp = VolteStatusNotificationLogic.targetWolverineFallbackModeProp(Build.DEVICE, simOp);

            int curGlobal = cr != null
                    ? Settings.Global.getInt(
                            cr,
                            "preferred_network_mode",
                            VolteStatusNotificationLogic.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA)
                    : targetMode;
            int curSubMode = cr != null
                    ? Settings.Global.getInt(cr, "preferred_network_mode" + subId, -1)
                    : targetMode;
            long curMask = tmSub.getPreferredNetworkTypeBitmask();

            SharedPreferences.Editor saveEd = prefs.edit();
            boolean savedAny = false;
            if (!prefs.contains(KEY_ORIG_GLOBAL_NET_MODE) && cr != null) {
                int toSave = (curGlobal == VolteStatusNotificationLogic.NETWORK_MODE_LTE_ONLY
                        || curGlobal == targetMode)
                        ? VolteStatusNotificationLogic.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA
                        : curGlobal;
                saveEd.putInt(KEY_ORIG_GLOBAL_NET_MODE, toSave);
                savedAny = true;
            }
            String subModeKey = KEY_ORIG_SUB_NET_MODE_PREFIX + subId;
            if (!prefs.contains(subModeKey) && cr != null) {
                int toSaveSub = (curSubMode == VolteStatusNotificationLogic.NETWORK_MODE_LTE_ONLY
                        || curSubMode == targetMode)
                        ? VolteStatusNotificationLogic.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA
                        : curSubMode;
                saveEd.putInt(subModeKey, toSaveSub);
                savedAny = true;
            }
            String origMaskKey = KEY_ORIG_NET_MASK_PREFIX + subId;
            if (!prefs.contains(origMaskKey)
                    && curMask > 0L
                    && curMask != VolteStatusNotificationLogic.BITMASK_LTE_ONLY
                    && curMask != targetMask) {
                saveEd.putLong(origMaskKey, curMask);
                savedAny = true;
            }
            if (!prefs.getBoolean(KEY_FALLBACK_MANAGED, false)) {
                saveEd.putString(KEY_ORIG_FALLBACK_PROP, targetProp.equals(curProp) ? "" : curProp);
                saveEd.putBoolean(KEY_FALLBACK_MANAGED, true);
                savedAny = true;
            }
            if (savedAny) {
                saveEd.commit();
            }

            if (!VolteStatusNotificationLogic.needsChinaTelecomWolverineAlignment(
                    Build.DEVICE, simOp, curGlobal, curMask, curProp)
                    && curSubMode == targetMode) {
                sb.append("(ct_wolverine_already=9/").append(curMask).append("/fb=").append(curProp).append(')');
                return;
            }

            if (cr != null) {
                if (curGlobal != targetMode) {
                    Settings.Global.putInt(cr, "preferred_network_mode", targetMode);
                }
                if (curSubMode != targetMode) {
                    Settings.Global.putInt(cr, "preferred_network_mode" + subId, targetMode);
                }
            }
            boolean maskOk = true;
            if (!VolteStatusNotificationLogic.isLteGsmWcdmaMask(curMask)) {
                maskOk = tmSub.setPreferredNetworkTypeBitmask(targetMask);
            }
            boolean propChanged = false;
            if (!targetProp.equals(curProp)) {
                propChanged = writeFallbackModeProperty(targetProp);
            }
            String verifyProp = getSystemProperty(PROP_FALLBACK_MODE, "").trim();
            sb.append("(ct_wolverine_align=9,maskOk=").append(maskOk)
                    .append(",prevMask=").append(curMask)
                    .append(",fb=").append(verifyProp).append(')');
            if (propChanged && !suppressLiveRadioCycle) {
                scheduleOneShotRadioPowerReload(context, subId, "ct_fallback_align");
            }
        } catch (Exception ex) {
            sb.append("(ct_net_err=").append(ex.getClass().getSimpleName()).append(')');
        }
    }

    private static void restoreSavedNetworkBitmasksQuietly(Context context) {
        try {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();
            SharedPreferences.Editor ed = prefs.edit();
            boolean changed = false;
            ContentResolver cr = context.getContentResolver();
            int origGlobal = prefs.getInt(
                    KEY_ORIG_GLOBAL_NET_MODE,
                    VolteStatusNotificationLogic.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA);
            if (prefs.contains(KEY_ORIG_GLOBAL_NET_MODE) && cr != null) {
                Settings.Global.putInt(cr, "preferred_network_mode", origGlobal);
                ed.remove(KEY_ORIG_GLOBAL_NET_MODE);
                changed = true;
            }
            if (all != null) {
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String k = entry.getKey();
                    if (k == null) continue;
                    if (k.startsWith(KEY_ORIG_SUB_NET_MODE_PREFIX) && entry.getValue() instanceof Integer && cr != null) {
                        int subId = Integer.parseInt(k.substring(KEY_ORIG_SUB_NET_MODE_PREFIX.length()));
                        int savedSub = (Integer) entry.getValue();
                        Settings.Global.putInt(
                                cr, "preferred_network_mode" + subId, savedSub >= 0 ? savedSub : origGlobal);
                        ed.remove(k);
                        changed = true;
                    } else if (k.startsWith(KEY_ORIG_NET_MASK_PREFIX)
                            && entry.getValue() instanceof Long
                            && tm != null) {
                        int subId = Integer.parseInt(k.substring(KEY_ORIG_NET_MASK_PREFIX.length()));
                        long origMask = (Long) entry.getValue();
                        if (SubscriptionManager.isValidSubscriptionId(subId) && origMask > 0L) {
                            tm.createForSubscriptionId(subId).setPreferredNetworkTypeBitmask(origMask);
                        }
                        ed.remove(k);
                        changed = true;
                    }
                }
            }
            if (prefs.getBoolean(KEY_FALLBACK_MANAGED, false)) {
                String origProp = prefs.getString(KEY_ORIG_FALLBACK_PROP, "");
                writeFallbackModeProperty(origProp);
                ed.remove(KEY_FALLBACK_MANAGED);
                ed.remove(KEY_ORIG_FALLBACK_PROP);
                changed = true;
            } else if ("9".equals(getSystemProperty(PROP_FALLBACK_MODE, "").trim())) {
                writeFallbackModeProperty("");
            }
            if (changed) {
                ed.commit();
            }
        } catch (Exception ignored) { }
    }
}
