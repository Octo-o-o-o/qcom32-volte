package org.octolab.qcom32volte;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, host-unit-testable core logic for the Qcom32 VoLTE Companion Manager App.
 * Separates generic 32-bit Qualcomm IMS/QMI telemetry from per-device EFS profile guards
 * (first verified profile: blackberry-passport-wolverine on MSM8974 / Android 11).
 */
public final class VolteManagerLogic {
    public static final String PROFILE_WOLVERINE_ID = "blackberry-passport-wolverine";
    public static final String EXPECTED_DEVICE = "wolverine";
    public static final String EXPECTED_MODEL = "Passport";
    public static final int EXPECTED_SDK = 30;
    public static final String IMS_PKG = "org.octolab.qcom32ims";
    public static final String LEGACY_IMS_PKG = "org.passport.volteims";
    public static final String STATUS_AUTHORITY = "org.octolab.qcom32ims.status";
    public static final String STATUS_METHOD = "get_status";

    private static final Pattern PII_DIGITS = Pattern.compile("\\b\\d{11,20}\\b");

    private VolteManagerLogic() {}

    public static final class StatusSnapshot {
        public final String matchedProfileId;
        public final boolean isVerifiedEfsProfile;
        public final boolean suAvailable;
        public final boolean telemetryAvailable;
        public final boolean imsServicePresent;
        public final String imsServiceVersion;
        public final boolean rilConnected;
        public final boolean qmiRegistered;
        public final boolean frameworkRegistered;
        public final boolean volteHealthy;
        public final String carrierSummary;
        public final String headlineZh;
        public final String headlineEn;

        public StatusSnapshot(
                String matchedProfileId,
                boolean isVerifiedEfsProfile,
                boolean suAvailable,
                boolean telemetryAvailable,
                boolean imsServicePresent,
                String imsServiceVersion,
                boolean rilConnected,
                boolean qmiRegistered,
                boolean frameworkRegistered,
                String carrierSummary) {
            this.matchedProfileId = matchedProfileId != null ? matchedProfileId : "generic-qcom32-readonly";
            this.isVerifiedEfsProfile = isVerifiedEfsProfile;
            this.suAvailable = suAvailable;
            this.telemetryAvailable = telemetryAvailable;
            this.imsServicePresent = imsServicePresent;
            this.imsServiceVersion = imsServiceVersion != null ? imsServiceVersion : "—";
            this.rilConnected = rilConnected;
            this.qmiRegistered = qmiRegistered;
            this.frameworkRegistered = frameworkRegistered;
            this.volteHealthy = imsServicePresent
                    && rilConnected
                    && qmiRegistered
                    && frameworkRegistered;
            this.carrierSummary = carrierSummary != null ? carrierSummary : "UNKNOWN";

            if (this.volteHealthy) {
                this.headlineZh = "VoLTE 通话栈已激活并注册就绪（请用系统拨号器实测通话与 4G 保持）";
                this.headlineEn = "VoLTE stack active & IMS registered (verify with native Dialer call + LTE)";
            } else if (!isVerifiedEfsProfile) {
                this.headlineZh = "未匹配已验证基带写入配置（当前仅支持只读检测；Passport wolverine 已验证写入）";
                this.headlineEn = "Unverified EFS profile (read-only telemetry active; EFS write gated to verified profiles)";
            } else if (imsServicePresent && telemetryAvailable) {
                this.headlineZh = "IMS 桥接服务已运行，当前 SIM 尚未完成 4G/IMS 注册（请确认 4G 信号与卡侧 VoLTE）";
                this.headlineEn = "IMS bridge active, awaiting 4G LTE + IMS registration for current SIM";
            } else if (imsServicePresent && !suAvailable) {
                this.headlineZh = "IMS 桥接服务已安装（当前无本地 su，请直接拨号实测或用电脑端检测）";
                this.headlineEn = "IMS bridge installed (no local su; test a native call or check via PC)";
            } else if (!suAvailable) {
                this.headlineZh = "未检测到本地 Root (su)：请连接电脑使用 Qcom32 VoLTE 桌面激活器（走 ADB Root）";
                this.headlineEn = "No local su found: connect USB and use the Qcom32 VoLTE Desktop Activator (ADB Root)";
            } else {
                this.headlineZh = "尚未完成 VoLTE 注册：可点击下方「备份并激活 VoLTE」或重启后复查";
                this.headlineEn = "VoLTE not yet registered: tap 'Backup & Activate VoLTE' or reboot to recheck";
            }
        }
    }

    public static String matchVerifiedDeviceProfile(String model, String device, int sdkInt) {
        if (sdkInt == EXPECTED_SDK) {
            String dev = device == null ? "" : device.trim();
            String mod = model == null ? "" : model.trim();
            if (EXPECTED_DEVICE.equalsIgnoreCase(dev) && EXPECTED_MODEL.equalsIgnoreCase(mod)) {
                return PROFILE_WOLVERINE_ID;
            }
        }
        return null;
    }

    public static boolean verifyPassportIdentity(String model, String device, int sdkInt) {
        return PROFILE_WOLVERINE_ID.equals(matchVerifiedDeviceProfile(model, device, sdkInt));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String redactSensitiveDigits(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return PII_DIGITS.matcher(raw).replaceAll("<REDACTED_DIGITS>");
    }

    public static String formatCarrierLabel(String numeric, String networkType) {
        String op = numeric == null ? "" : numeric.trim();
        if (op.contains(",")) {
            op = op.split(",", 2)[0].trim();
        }
        String net = (networkType == null || networkType.trim().isEmpty()) ? "UNKNOWN" : networkType.trim();
        if (net.contains(",")) {
            net = net.split(",", 2)[0].trim();
        }
        String name;
        switch (op) {
            case "46001":
            case "46006":
            case "46009":
                name = "China Unicom (" + op + ")";
                break;
            case "46000":
            case "46002":
            case "46004":
            case "46007":
            case "46008":
                name = "China Mobile (" + op + ")";
                break;
            case "46003":
            case "46005":
            case "46011":
            case "46012":
                name = "China Telecom (" + op + ")";
                break;
            case "46015":
                name = "China Broadnet (" + op + ")";
                break;
            default:
                name = op.isEmpty() ? "No SIM / Unknown" : ("PLMN " + op);
                break;
        }
        return name + " · " + net;
    }

    public static StatusSnapshot parseStatusSummary(
            String model,
            String device,
            int sdkInt,
            boolean suAvailable,
            String serviceDump,
            String qmiOut,
            String phoneDump,
            String simNumeric,
            String networkType) {
        String profile = matchVerifiedDeviceProfile(model, device, sdkInt);
        boolean verifiedProfile = profile != null;
        String sDump = serviceDump == null ? "" : serviceDump;
        String qDump = qmiOut == null ? "" : qmiOut;
        String pDump = phoneDump == null ? "" : phoneDump;

        boolean hasLiveProviderOrDump = sDump.contains("QCOM32_VOLTE_IMS_V1") || sDump.contains("PASSPORT_VOLTE_IMS_V1");
        boolean telemetryOk = suAvailable || hasLiveProviderOrDump || !qDump.trim().isEmpty();
        boolean present = hasLiveProviderOrDump
                || sDump.contains("package=" + IMS_PKG)
                || sDump.contains("package=" + LEGACY_IMS_PKG);
        String version = extractValue(sDump, "version=([^\\s]+)");
        boolean rilConn = sDump.contains("rilConnected=true");

        Integer pdp = extractMetric(qDump, "pdp_connected");
        Integer reg = extractMetric(qDump, "reg_status");
        Integer voip = extractMetric(qDump, "voip");
        boolean qmiOk = (pdp != null && pdp == 1) && (reg != null && reg == 2) && (voip != null && voip == 2);

        boolean fwReg = pDump.contains("mImsRegistered=true")
                || sDump.contains("publishedFrameworkReg=1")
                || sDump.contains("publishedFrameworkReg=2");

        return new StatusSnapshot(
                verifiedProfile ? profile : "generic-qcom32-readonly",
                verifiedProfile,
                suAvailable,
                telemetryOk,
                present,
                version,
                rilConn,
                qmiOk,
                fwReg,
                formatCarrierLabel(simNumeric, networkType));
    }

    private static String extractValue(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public static Integer extractMetric(String text, String key) {
        if (text == null || key == null || text.isEmpty() || key.isEmpty()) {
            return null;
        }
        String qKey = Pattern.quote(key);
        String v = extractValue(text, "\"" + qKey + "\"\\s*:\\s*(-?\\d+)");
        if (v == null) {
            v = extractValue(text, "\\b" + qKey + "=(-?\\d+)");
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String quoteSh(String path) {
        if (path == null || path.isEmpty() || path.contains("'") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalArgumentException("Unsafe shell path: " + path);
        }
        return "'" + path + "'";
    }

    private static String legacyCleanupLines() {
        return "if pm path org.passport.volteims >/dev/null 2>&1; then\n"
                + "  am broadcast -n org.passport.volteims/.VolteAdminReceiver "
                + "-a org.passport.volteims.ACTION_ADMIN --es op clear_override --es cmd clear_override --include-stopped-packages || true\n"
                + "  pm uninstall org.passport.volteims || true\n"
                + "fi\n";
    }

    public static String buildRootDeployScript(String appStagedDir, String backupDir) {
        String stageQuoted = quoteSh(appStagedDir);
        String backupQuoted = quoteSh(backupDir);
        return "set -e\n"
                + "DEV=$(getprop ro.product.device)\n"
                + "if [ \"$DEV\" != \"wolverine\" ]; then echo \"ERR_UNVERIFIED_EFS_PROFILE:$DEV\"; exit 21; fi\n"
                + "TMP_PROV='/data/local/tmp/pp_regconfig_provision_v6'\n"
                + "TMP_APK='/data/local/tmp/Qcom32VolteIms.apk'\n"
                + "trap 'rm -f /data/local/tmp/pp_regconfig_provision_v6 /data/local/tmp/Qcom32VolteIms.apk' EXIT\n"
                + legacyCleanupLines()
                + "mkdir -p " + backupQuoted + "\n"
                + "cp " + stageQuoted + "/pp_regconfig_provision_v6 \"$TMP_PROV\"\n"
                + "cp " + stageQuoted + "/Qcom32VolteIms.apk \"$TMP_APK\"\n"
                + "chmod 0700 \"$TMP_PROV\"\n"
                + "chmod 0644 \"$TMP_APK\"\n"
                + "\"$TMP_PROV\" --inspect-state > " + backupQuoted + "/efs-inspect-before.txt\n"
                + "\"$TMP_PROV\" --provision-stage6a > " + backupQuoted + "/efs-provision-stage6a.txt\n"
                + "cat " + backupQuoted + "/efs-provision-stage6a.txt\n"
                + "pm install -r \"$TMP_APK\"\n"
                + "am broadcast -n org.octolab.qcom32ims/.VolteAdminReceiver "
                + "-a org.octolab.qcom32ims.ACTION_ADMIN --es op apply_override --es cmd apply_override --include-stopped-packages\n"
                + "echo 'DEPLOY_STAGE6A_AND_APK_OK'\n";
    }

    public static String buildRootUpgradeApkScript(String appStagedDir) {
        String stageQuoted = quoteSh(appStagedDir);
        return "set -e\n"
                + "TMP_APK='/data/local/tmp/Qcom32VolteIms.apk'\n"
                + "trap 'rm -f /data/local/tmp/Qcom32VolteIms.apk' EXIT\n"
                + legacyCleanupLines()
                + "cp " + stageQuoted + "/Qcom32VolteIms.apk \"$TMP_APK\"\n"
                + "chmod 0644 \"$TMP_APK\"\n"
                + "pm install -r \"$TMP_APK\"\n"
                + "am broadcast -n org.octolab.qcom32ims/.VolteAdminReceiver "
                + "-a org.octolab.qcom32ims.ACTION_ADMIN --es op apply_override --es cmd apply_override --include-stopped-packages\n"
                + "echo 'UPGRADE_APK_OK'\n";
    }

    public static String buildRootRollbackScript(String appStagedDir) {
        String stageQuoted = quoteSh(appStagedDir);
        return "set -e\n"
                + "DEV=$(getprop ro.product.device)\n"
                + "if [ \"$DEV\" != \"wolverine\" ]; then echo \"ERR_UNVERIFIED_EFS_PROFILE:$DEV\"; exit 21; fi\n"
                + "TMP_PROV='/data/local/tmp/pp_regconfig_provision_v6'\n"
                + "trap 'rm -f /data/local/tmp/pp_regconfig_provision_v6' EXIT\n"
                + legacyCleanupLines()
                + "am broadcast -n org.octolab.qcom32ims/.VolteAdminReceiver "
                + "-a org.octolab.qcom32ims.ACTION_ADMIN --es op clear_override --es cmd clear_override --include-stopped-packages || true\n"
                + "sleep 1\n"
                + "pm uninstall org.octolab.qcom32ims || true\n"
                + "cp " + stageQuoted + "/pp_regconfig_provision_v6 \"$TMP_PROV\"\n"
                + "chmod 0700 \"$TMP_PROV\"\n"
                + "\"$TMP_PROV\" --rollback-all\n"
                + "echo 'ROLLBACK_ALL_OK'\n";
    }

    public static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
