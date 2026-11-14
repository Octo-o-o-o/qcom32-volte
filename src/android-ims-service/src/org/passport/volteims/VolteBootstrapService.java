package org.passport.volteims;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

/**
 * Short-lived Direct-Boot-aware bootstrap watcher started on LOCKED_BOOT_COMPLETED /
 * BOOT_COMPLETED so that even when the USIM finishes loading several seconds after
 * boot broadcasts fire, CarrierConfig override + ImsResolver binding complete 100%
 * hands-free without lingering once PassportVolteImsService is bound.
 */
public final class VolteBootstrapService extends Service {
    private static final String TAG = "PassportVolteBootSvc";
    private static final long[] RETRY_DELAYS_MS = {
            0L, 1500L, 4000L, 8000L, 15000L, 25000L, 40000L, 55000L
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SubscriptionManager subscriptionManager;
    private boolean receiversRegistered;
    private int stepIndex;

    private final BroadcastReceiver dynamicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent != null ? intent.getAction() : "null";
            onTrigger("dyn:" + act);
        }
    };

    private final SubscriptionManager.OnSubscriptionsChangedListener subListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    onTrigger("sub_changed");
                }
            };

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            onTrigger("timer_" + stepIndex);
            stepIndex++;
            if (PassportVolteImsService.isFeatureBound()
                    && CarrierConfigBootstrapReceiver.isLastApplyComplete()) {
                Log.i(TAG, "PassportVolteImsService bound and CarrierConfig verified; stopping watcher");
                stopSelf();
                return;
            }
            if (stepIndex < RETRY_DELAYS_MS.length) {
                long delay = RETRY_DELAYS_MS[stepIndex] - RETRY_DELAYS_MS[stepIndex - 1];
                handler.postDelayed(this, Math.max(500L, delay));
            } else {
                Log.i(TAG, "Bootstrap retry window finished; stopping watcher");
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
            f.addAction("android.intent.action.SIM_STATE_CHANGED");
            f.addAction("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED");
            registerReceiver(dynamicReceiver, f);
            receiversRegistered = true;
        } catch (Exception ex) {
            Log.w(TAG, "registerReceiver err=" + ex.getClass().getSimpleName());
        }
        try {
            subscriptionManager = SubscriptionManager.from(this);
            if (subscriptionManager != null) {
                subscriptionManager.addOnSubscriptionsChangedListener(subListener);
            }
        } catch (Exception ex) {
            Log.w(TAG, "addOnSubscriptionsChangedListener err=" + ex.getClass().getSimpleName());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (CarrierConfigBootstrapReceiver.isBootstrapDisabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(retryRunnable);
        stepIndex = 0;
        handler.post(retryRunnable);
        return START_STICKY;
    }

    private void onTrigger(String why) {
        if (CarrierConfigBootstrapReceiver.isBootstrapDisabled(this)) {
            stopSelf();
            return;
        }
        CarrierConfigBootstrapReceiver.applyOverrideIfNeeded(this, why, false);
        if (PassportVolteImsService.isFeatureBound()
                && CarrierConfigBootstrapReceiver.isLastApplyComplete()) {
            handler.removeCallbacks(retryRunnable);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiversRegistered) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (Exception ignored) { }
            receiversRegistered = false;
        }
        if (subscriptionManager != null) {
            try {
                subscriptionManager.removeOnSubscriptionsChangedListener(subListener);
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
