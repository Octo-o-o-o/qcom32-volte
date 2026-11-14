package org.passport.voltemanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, host-unit-testable core logic for the Passport VoLTE Companion Manager App.
 * Handles strict hardware guards, SHA-256 payload verification, QMI/ImsService status parsing,
 * PII redaction, and guarded Magisk (`su`) shell script generation.
 */
public final class VolteManagerLogic {
    public static final String EXPECTED_DEVICE = "wolverine";
    public static final String EXPECTED_MODEL = "Passport";
    public static final int EXPECTED_SDK = 30;
    public static final String IMS_PKG = "org.passport.volteims";

    private static final Pattern PII_DIGITS = Pattern.compile("\\b\\d{11,20}\\b");

    private VolteManagerLogic() {}

    public static final class StatusSnapshot {
        public final boolean isPassportAndroid11;
        public final boolean suAvailable;
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
                boolean isPassportAndroid11,
                boolean suAvailable,
                boolean imsServicePresent,
                String imsServiceVersion,
                boolean rilConnected,
                boolean qmiRegistered,
                boolean frameworkRegistered,
                String carrierSummary) {
            this.isPassportAndroid11 = isPassportAndroid11;
            this.suAvailable = suAvailable;
            this.imsServicePresent = imsServicePresent;
            this.imsServiceVersion = imsServiceVersion != null ? imsServiceVersion : "—";
            this.rilConnected = rilConnected;
            this.qmiRegistered = qmiRegistered;
            this.frameworkRegistered = frameworkRegistered;
            this.volteHealthy = isPassportAndroid11
                    && imsServicePresent
                    && rilConnected
                    && qmiRegistered
                    && frameworkRegistered;
            this.carrierSummary = carrierSummary != null ? carrierSummary : "UNKNOWN";

            if (!isPassportAndroid11) {
                this.headlineZh = "非目标设备：仅支持 BlackBerry Passport (wolverine / Android 11)";
                this.headlineEn = "Unsupported device: BlackBerry Passport (wolverine / Android 11) required";
            } else if (this.volteHealthy) {
                this.headlineZh = "VoLTE 已激活并注册就绪（请用系统拨号器实测通话与 4G 保持）";
                this.headlineEn = "VoLTE active & IMS registered (verify with native Dialer call + LTE)";
            } else if (imsServicePresent && !suAvailable) {
                this.headlineZh = "桥接服务已安装（当前无本地 su，深度基带状态请连电脑查看或直接拨号实测）";
                this.headlineEn = "Bridge APK installed (no local su; test a native call or check via PC)";
            } else if (!suAvailable) {
                this.headlineZh = "未检测到本地 Root (su)：请使用 Windows/电脑端工具通过 ADB Root 激活";
                this.headlineEn = "No local su found: use the Desktop tool via ADB Root to activate";
            } else {
                this.headlineZh = "尚未完成 VoLTE 注册：可点击下方「备份并激活 VoLTE」或重启后复查";
                this.headlineEn = "VoLTE not yet registered: tap 'Backup & Activate VoLTE' or reboot to recheck";
            }
        }
    }

    public static boolean verifyPassportIdentity(String model, String device, int sdkInt) {
        if (sdkInt != EXPECTED_SDK) {
            return false;
        }
        String dev = device == null ? "" : device.trim();
        String mod = model == null ? "" : model.trim();
        return EXPECTED_DEVICE.equalsIgnoreCase(dev) && EXPECTED_MODEL.equalsIgnoreCase(mod);
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
        String net = (networkType == null || networkType.trim().isEmpty()) ? "UNKNOWN" : networkType.trim();
        String name;
        switch (op) {
            case "46001":
            case "46006":
            case "46009":
                name = "China Unicom (46001)";
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
                name = "China Telecom (" + op + ")";
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
        boolean idOk = verifyPassportIdentity(model, device, sdkInt);
        String sDump = serviceDump == null ? "" : serviceDump;
        String qDump = qmiOut == null ? "" : qmiOut;
        String pDump = phoneDump == null ? "" : phoneDump;

        boolean present = sDump.contains("PASSPORT_VOLTE_IMS_V1") || sDump.contains("package=org.passport.volteims");
        String version = extractValue(sDump, "version=([^\\s]+)");
        boolean rilConn = sDump.contains("rilConnected=true");

        Integer pdp = extractInt(qDump, "\\bpdp_connected=(-?\\d+)");
        Integer reg = extractInt(qDump, "\\breg_status=(-?\\d+)");
        Integer voip = extractInt(qDump, "\\bvoip=(-?\\d+)");
        boolean qmiOk = (pdp != null && pdp == 1) && (reg != null && reg == 2) && (voip != null && voip == 2);

        boolean fwReg = pDump.contains("mImsRegistered=true")
                || sDump.contains("publishedFrameworkReg=1")
                || sDump.contains("publishedFrameworkReg=2");

        return new StatusSnapshot(
                idOk,
                suAvailable,
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

    private static Integer extractInt(String text, String regex) {
        String v = extractValue(text, regex);
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

    public static String buildRootDeployScript(String appStagedDir, String backupDir) {
        String stageQuoted = quoteSh(appStagedDir);
        String backupQuoted = quoteSh(backupDir);
        return "set -e\n"
                + "DEV=$(getprop ro.product.device)\n"
                + "if [ \"$DEV\" != \"wolverine\" ]; then echo \"ERR_UNSUPPORTED_DEVICE:$DEV\"; exit 21; fi\n"
                + "TMP_PROV='/data/local/tmp/pp_regconfig_provision_v6'\n"
                + "TMP_APK='/data/local/tmp/PassportVolteIms.apk'\n"
                + "trap 'rm -f /data/local/tmp/pp_regconfig_provision_v6 /data/local/tmp/PassportVolteIms.apk' EXIT\n"
                + "mkdir -p " + backupQuoted + "\n"
                + "cp " + stageQuoted + "/pp_regconfig_provision_v6 \"$TMP_PROV\"\n"
                + "cp " + stageQuoted + "/PassportVolteIms.apk \"$TMP_APK\"\n"
                + "chmod 0700 \"$TMP_PROV\"\n"
                + "chmod 0644 \"$TMP_APK\"\n"
                + "\"$TMP_PROV\" --inspect-state > " + backupQuoted + "/efs-inspect-before.txt\n"
                + "\"$TMP_PROV\" --provision-stage6a > " + backupQuoted + "/efs-provision-stage6a.txt\n"
                + "cat " + backupQuoted + "/efs-provision-stage6a.txt\n"
                + "pm install -r \"$TMP_APK\"\n"
                + "am broadcast -n org.passport.volteims/.VolteAdminReceiver "
                + "-a org.passport.volteims.ACTION_ADMIN --es cmd apply_override --include-stopped-packages\n"
                + "echo 'DEPLOY_STAGE6A_AND_APK_OK'\n";
    }

    public static String buildRootUpgradeApkScript(String appStagedDir) {
        String stageQuoted = quoteSh(appStagedDir);
        return "set -e\n"
                + "DEV=$(getprop ro.product.device)\n"
                + "if [ \"$DEV\" != \"wolverine\" ]; then echo \"ERR_UNSUPPORTED_DEVICE:$DEV\"; exit 21; fi\n"
                + "TMP_APK='/data/local/tmp/PassportVolteIms.apk'\n"
                + "trap 'rm -f /data/local/tmp/PassportVolteIms.apk' EXIT\n"
                + "cp " + stageQuoted + "/PassportVolteIms.apk \"$TMP_APK\"\n"
                + "chmod 0644 \"$TMP_APK\"\n"
                + "pm install -r \"$TMP_APK\"\n"
                + "am broadcast -n org.passport.volteims/.VolteAdminReceiver "
                + "-a org.passport.volteims.ACTION_ADMIN --es cmd apply_override --include-stopped-packages\n"
                + "echo 'UPGRADE_APK_OK'\n";
    }

    public static String buildRootRollbackScript(String appStagedDir) {
        String stageQuoted = quoteSh(appStagedDir);
        return "set -e\n"
                + "DEV=$(getprop ro.product.device)\n"
                + "if [ \"$DEV\" != \"wolverine\" ]; then echo \"ERR_UNSUPPORTED_DEVICE:$DEV\"; exit 21; fi\n"
                + "TMP_PROV='/data/local/tmp/pp_regconfig_provision_v6'\n"
                + "trap 'rm -f /data/local/tmp/pp_regconfig_provision_v6' EXIT\n"
                + "am broadcast -n org.passport.volteims/.VolteAdminReceiver "
                + "-a org.passport.volteims.ACTION_ADMIN --es cmd clear_override --include-stopped-packages || true\n"
                + "sleep 1\n"
                + "pm uninstall org.passport.volteims || true\n"
                + "cp " + stageQuoted + "/pp_regconfig_provision_v6 \"$TMP_PROV\"\n"
                + "chmod 0700 \"$TMP_PROV\"\n"
                + "\"$TMP_PROV\" --rollback-all\n"
                + "echo 'ROLLBACK_ALL_OK'\n";
    }

    public static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
