package android.telephony;

import android.os.PersistableBundle;

/**
 * Compile-only header stub matching LineageOS 18.1 framework.jar
 * android.telephony.CarrierConfigManager (verified in EP40F, SHA-256
 * 91dfaa0cd4a5e9a99df954e1cedc77f0c6f1e9e1bea42f9c619e16bc3dd49816).
 *
 * Never packaged into classes.jar / classes.dex / APK.
 */
public class CarrierConfigManager {
    public static final String ACTION_CARRIER_CONFIG_CHANGED =
            "android.telephony.action.CARRIER_CONFIG_CHANGED";

    public static final String KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING =
            "config_ims_package_override_string";
    public static final String KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING =
            "config_ims_mmtel_package_override_string";
    public static final String KEY_CARRIER_VOLTE_AVAILABLE_BOOL =
            "carrier_volte_available_bool";
    public static final String KEY_CARRIER_VOLTE_PROVISIONED_BOOL =
            "carrier_volte_provisioned_bool";
    public static final String KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL =
            "carrier_volte_provisioning_required_bool";
    public static final String KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL =
            "enhanced_4g_lte_on_by_default_bool";
    public static final String KEY_EDITABLE_ENHANCED_4G_LTE_BOOL =
            "editable_enhanced_4g_lte_bool";
    public static final String KEY_HIDE_ENHANCED_4G_LTE_BOOL =
            "hide_enhanced_4g_lte_bool";
    public static final String KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL =
            "carrier_allow_turnoff_ims_bool";
    public static final String KEY_DISPLAY_HD_AUDIO_PROPERTY_BOOL =
            "display_hd_audio_property_bool";
    public static final String KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL =
            "show_ims_registration_status_bool";
    public static final String KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL =
            "carrier_use_ims_first_for_emergency_bool";
    public static final String KEY_CARRIER_VT_AVAILABLE_BOOL =
            "carrier_vt_available_bool";
    public static final String KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL =
            "carrier_wfc_ims_available_bool";

    public PersistableBundle getConfigForSubId(int subId) {
        throw new RuntimeException("stub");
    }

    public void overrideConfig(int subscriptionId, PersistableBundle overrides) {
        throw new RuntimeException("stub");
    }

    public void overrideConfig(int subscriptionId, PersistableBundle overrides, boolean persistent) {
        throw new RuntimeException("stub");
    }
}
