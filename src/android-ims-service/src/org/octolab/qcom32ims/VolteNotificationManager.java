package org.octolab.qcom32ims;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Direct-Boot-safe Android 11 system notification publisher for {@code org.octolab.qcom32ims}.
 *
 * Requires ZERO {@code <uses-permission>} declarations on Android 11 (API 30), preserving
 * {@code com.android.phone}'s exact granted permissions under {@code sharedUserId="android.uid.phone"}.
 */
public final class VolteNotificationManager {
    private static final String TAG = "Qcom32VolteNotify";
    public static final String CHANNEL_ID = "qcom32_volte_status";
    public static final int NOTIFICATION_ID = 32011;
    private static final String PREFS_NAME = "volte_notification_state";
    private static final String COMPANION_PKG = "org.octolab.qcom32volte";

    private static final Object STATE_LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qcom32-volte-notify");
        t.setDaemon(true);
        return t;
    });

    private static volatile String lastSummary = "idle";
    private static volatile String lastKind = VolteStatusNotificationLogic.Kind.NONE.name();
    private static volatile String lastTitle = "";

    private VolteNotificationManager() {}

    public static String getLastSummary() {
        return lastSummary;
    }

    public static String getLastKind() {
        return lastKind;
    }

    public static String getLastTitle() {
        return lastTitle;
    }

    public static void onSimAbsentAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WORKER.execute(() -> {
            synchronized (STATE_LOCK) {
                try {
                    VolteStatusNotificationLogic.SessionState cur = loadState(appCtx);
                    VolteStatusNotificationLogic.Decision dec =
                            VolteStatusNotificationLogic.onSimAbsent(
                                    cur, readBootToken(), SystemClock.elapsedRealtime());
                    saveState(appCtx, dec.nextState);
                    applyDecision(appCtx, dec, "sim_absent");
                } catch (Exception ex) {
                    Log.w(TAG, "onSimAbsent err=" + ex.getClass().getSimpleName());
                }
            }
        });
    }

    public static String markRebootRequiredAndNotify(Context context) {
        if (context == null) return "null_context";
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (STATE_LOCK) {
            try {
                boolean en = isEnglishLocale();
                VolteStatusNotificationLogic.SessionState cur = loadState(appCtx);
                VolteStatusNotificationLogic.Decision dec =
                        VolteStatusNotificationLogic.markRebootRequired(cur, readBootToken(), en);
                saveState(appCtx, dec.nextState);
                applyDecision(appCtx, dec, "mark_reboot_required");
                return lastSummary;
            } catch (Exception ex) {
                lastSummary = "mark_reboot_err:" + ex.getClass().getSimpleName();
                Log.w(TAG, lastSummary);
                return lastSummary;
            }
        }
    }

    public static void cancelAllAndReset(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (STATE_LOCK) {
            try {
                Context deCtx = appCtx.createDeviceProtectedStorageContext();
                deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
            } catch (Exception ignored) { }
            try {
                NotificationManager nm = appCtx.getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.cancel(NOTIFICATION_ID);
                }
            } catch (Exception ignored) { }
            lastKind = VolteStatusNotificationLogic.Kind.NONE.name();
            lastTitle = "";
            lastSummary = "reset_and_cancelled";
        }
    }

    public static void onPackageReplacedAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WORKER.execute(() -> {
            synchronized (STATE_LOCK) {
                try {
                    VolteStatusNotificationLogic.SessionState cur = loadState(appCtx);
                    VolteStatusNotificationLogic.SessionState next =
                            VolteStatusNotificationLogic.onPackageReplaced(cur, readBootToken());
                    saveState(appCtx, next);
                } catch (Exception ex) {
                    Log.w(TAG, "onPackageReplaced err=" + ex.getClass().getSimpleName());
                }
            }
            evaluateSynchronously(appCtx, "pkg_replaced", false);
        });
    }

    public static void evaluateNowAsync(Context context, String trigger, boolean settleWindowElapsed) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WORKER.execute(() -> evaluateSynchronously(appCtx, trigger, settleWindowElapsed));
    }

    public static String evaluateSynchronously(Context context, String trigger, boolean settleWindowElapsed) {
        if (context == null) return "null_context";
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (STATE_LOCK) {
            try {
                String bootToken = readBootToken();
                boolean bootstrapDisabled = CarrierConfigBootstrapReceiver.isBootstrapDisabled(appCtx);

                int simState = TelephonyManager.SIM_STATE_UNKNOWN;
                try {
                    TelephonyManager tm = appCtx.getSystemService(TelephonyManager.class);
                    if (tm != null) {
                        simState = tm.getSimState();
                    }
                } catch (Exception ignored) { }
                boolean simAbsent = (simState == TelephonyManager.SIM_STATE_ABSENT);
                boolean simReady = (simState == TelephonyManager.SIM_STATE_READY);

                Bundle status = Qcom32VolteImsService.buildSanitizedStatusBundle(appCtx);
                String simOp = status.getString("sim_operator", "");
                if (!simOp.isEmpty()) {
                    simReady = true;
                }
                String netType = status.getString("network_type", "");
                boolean imsHealthy = status.getBoolean("volte_healthy", false)
                        || (status.getBoolean("voice_ims_ready", false)
                                && status.getInt("published_framework_reg", -1) == RilImsWire.REG_STATE_REGISTERED);
                int qmiTestMode = status.getInt("test_mode", -1);

                VolteStatusNotificationLogic.EvalInput input = new VolteStatusNotificationLogic.EvalInput(
                        bootToken,
                        bootstrapDisabled,
                        simAbsent,
                        simReady,
                        simOp,
                        netType,
                        Build.DEVICE,
                        imsHealthy,
                        settleWindowElapsed,
                        qmiTestMode,
                        isEnglishLocale(),
                        SystemClock.elapsedRealtime());

                VolteStatusNotificationLogic.SessionState cur = loadState(appCtx);
                if (trigger != null
                        && (trigger.startsWith("admin_") || trigger.startsWith("pkg_replaced"))
                        && !isStatusNotificationActive(appCtx)) {
                    cur = VolteStatusNotificationLogic.onPackageReplaced(cur, bootToken);
                }
                VolteStatusNotificationLogic.Decision dec = VolteStatusNotificationLogic.evaluate(cur, input);
                saveState(appCtx, dec.nextState);
                applyDecision(appCtx, dec, trigger);
                return lastSummary;
            } catch (Exception ex) {
                lastSummary = trigger + ":err=" + ex.getClass().getSimpleName();
                Log.w(TAG, lastSummary);
                return lastSummary;
            }
        }
    }

    private static void applyDecision(
            Context context,
            VolteStatusNotificationLogic.Decision dec,
            String trigger) {
        lastKind = dec.nextState.lastPostedKind;
        lastTitle = dec.nextState.lastPostedTitle;

        if (dec.action == VolteStatusNotificationLogic.Action.NOOP) {
            lastSummary = trigger + ":noop:" + dec.reason + ":activeKind=" + lastKind;
            return;
        }

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            lastSummary = trigger + ":missing_nm";
            return;
        }

        if (dec.action == VolteStatusNotificationLogic.Action.CANCEL) {
            nm.cancel(NOTIFICATION_ID);
            lastSummary = trigger + ":cancelled:" + dec.reason;
            Log.i(TAG, lastSummary);
            return;
        }

        boolean en = isEnglishLocale();
        ensureChannel(nm, en);

        PendingIntent tapIntent = buildTapPendingIntent(context);
        int accentArgb = (dec.kind == VolteStatusNotificationLogic.Kind.ACTIVATED) ? 0xFF2E654D : 0xFF984B3E;

        Notification.Builder nb = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_volte)
                .setContentTitle(dec.title)
                .setContentText(dec.body)
                .setStyle(new Notification.BigTextStyle().bigText(dec.body))
                .setColor(accentArgb)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis());

        if (tapIntent != null) {
            nb.setContentIntent(tapIntent);
        }

        nm.notify(NOTIFICATION_ID, nb.build());
        lastSummary = trigger + ":posted:" + dec.kind.name();
        Log.i(TAG, lastSummary + " title=" + dec.title);
    }

    private static void ensureChannel(NotificationManager nm, boolean english) {
        String name = english ? "VoLTE Status & SIM Swap Alerts" : "VoLTE 激活与换卡状态通知";
        String desc = english
                ? "Notifies whether 4G VoLTE is active or if a reboot is needed after boot, SIM swap, or provisioning."
                : "在开机、更换 SIM 卡或写入基带配置后，提示 VoLTE 是否激活就绪或需要重启手机。";
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(desc);
        ch.enableVibration(false);
        ch.setSound(null, null);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent buildTapPendingIntent(Context context) {
        try {
            Intent target = context.getPackageManager().getLaunchIntentForPackage(COMPANION_PKG);
            if (target == null) {
                target = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            }
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return PendingIntent.getActivity(
                    context,
                    0,
                    target,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isStatusNotificationActive(Context context) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return false;
            StatusBarNotification[] active = nm.getActiveNotifications();
            if (active == null) return false;
            for (StatusBarNotification sbn : active) {
                if (sbn != null && sbn.getId() == NOTIFICATION_ID) {
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static boolean isEnglishLocale() {
        String lang = Locale.getDefault().getLanguage();
        return lang == null || !lang.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    static String readBootToken() {
        try (InputStream in = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n > 0) {
                String id = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception ignored) { }
        long bootEpochBucket = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 15000L;
        return "epoch_" + bootEpochBucket;
    }

    private static VolteStatusNotificationLogic.SessionState loadState(Context context) {
        VolteStatusNotificationLogic.SessionState st = new VolteStatusNotificationLogic.SessionState();
        try {
            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences p = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            st.bootToken = p.getString("bootToken", "");
            st.activeSimOperator = p.getString("activeSimOperator", "");
            st.swapSeq = p.getInt("swapSeq", 0);
            st.sawSimAbsentInBoot = p.getBoolean("sawSimAbsentInBoot", false);
            st.hotSwappedInBoot = p.getBoolean("hotSwappedInBoot", false);
            st.pendingRebootBootToken = p.getString("pendingRebootBootToken", "");
            st.notifiedActivatedInSession = p.getBoolean("notifiedActivatedInSession", false);
            st.lastPostedFingerprint = p.getString("lastPostedFingerprint", "");
            st.lastPostedKind = p.getString("lastPostedKind", VolteStatusNotificationLogic.Kind.NONE.name());
            st.lastPostedTitle = p.getString("lastPostedTitle", "");
        } catch (Exception ignored) { }
        return st;
    }

    private static void saveState(Context context, VolteStatusNotificationLogic.SessionState st) {
        try {
            Context deCtx = context.createDeviceProtectedStorageContext();
            SharedPreferences p = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            p.edit()
                    .putString("bootToken", st.bootToken)
                    .putString("activeSimOperator", st.activeSimOperator)
                    .putInt("swapSeq", st.swapSeq)
                    .putBoolean("sawSimAbsentInBoot", st.sawSimAbsentInBoot)
                    .putBoolean("hotSwappedInBoot", st.hotSwappedInBoot)
                    .putString("pendingRebootBootToken", st.pendingRebootBootToken)
                    .putBoolean("notifiedActivatedInSession", st.notifiedActivatedInSession)
                    .putString("lastPostedFingerprint", st.lastPostedFingerprint)
                    .putString("lastPostedKind", st.lastPostedKind)
                    .putString("lastPostedTitle", st.lastPostedTitle)
                    .commit();
        } catch (Exception ignored) { }
    }
}
