package org.octolab.qcom32ims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * MODIFY_PHONE_STATE-protected administrative receiver supporting explicit
 * CarrierConfig override apply/clear and backend state refresh during deployment & rollback.
 */
public final class VolteAdminReceiver extends BroadcastReceiver {
    public static final String ACTION_ADMIN = "org.octolab.qcom32ims.ACTION_ADMIN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String op = intent.getStringExtra("op");
        if (op == null) op = intent.getStringExtra("cmd");
        if (op == null) op = "apply_override";

        String result;
        switch (op) {
            case "clear_override":
                result = CarrierConfigBootstrapReceiver.clearOverrideAll(context, "admin_clear");
                setResultCode(0);
                setResultData(result);
                break;
            case "refresh_backend":
                result = Qcom32VolteImsService.triggerActiveInstanceRefresh();
                setResultCode(0);
                setResultData(result);
                break;
            case "apply_override":
            default:
                result = CarrierConfigBootstrapReceiver.adminEnableAndApply(context);
                setResultCode(0);
                setResultData(result);
                break;
        }
    }
}
