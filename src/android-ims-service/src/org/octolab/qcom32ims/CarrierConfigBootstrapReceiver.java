package org.octolab.qcom32ims;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsMmTelManager;
import android.util.Log;
import java.util.concurrent.TimeUnit;

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
    private static final String KEY_ROLLBACK_DISABLED = "rollback_disabled";

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
        applyOverrideIfNeeded(context, "rx:" + action, false);
        if (!Qcom32VolteImsService.isFeatureBound()) {
            startBootstrapWatcherQuietly(context);
        }
    }

    static String adminEnableAndApply(Context context) {
        setBootstrapDisabled(context, false);
        String res = applyOverrideIfNeeded(context, "admin_apply", true);
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
}
