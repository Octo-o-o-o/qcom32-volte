package org.octolab.qcom32ims;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java, zero-PII parser for {@code libimsa_probe.so --probe-all} output and
 * non-PII PLMN / RAT field validators used by {@link VolteStatusProvider}.
 *
 * Kept free of Android framework imports so exact production bytecode is exercised
 * by {@code Qcom32VolteImsHostTest} on the host.
 */
public final class QmiProbeSummaryParser {
    private static final Pattern PLMN_PATTERN = Pattern.compile("^\\d{5,6}$");
    private static final Pattern RAT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");

    private QmiProbeSummaryParser() {}

    public static final class Summary {
        public final int pdpConnected;
        public final int regStatus;
        public final int legacyReg;
        public final int voip;
        public final int voipRat;
        public final int sms;
        public final int testMode;

        public Summary(
                int pdpConnected,
                int regStatus,
                int legacyReg,
                int voip,
                int voipRat,
                int sms,
                int testMode) {
            this.pdpConnected = pdpConnected;
            this.regStatus = regStatus;
            this.legacyReg = legacyReg;
            this.voip = voip;
            this.voipRat = voipRat;
            this.sms = sms;
            this.testMode = testMode;
        }

        public static Summary unknown() {
            return new Summary(-1, -1, -1, -1, -1, -1, -1);
        }

        public boolean isRegisteredVoipReady() {
            return pdpConnected == 1 && regStatus == 2 && voip == 2;
        }
    }

    public static Summary parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Summary.unknown();
        }
        int pdp = extractMetric(raw, "pdp_connected");
        int reg = extractMetric(raw, "reg_status");
        int leg = extractMetric(raw, "legacy_reg");
        int voip = extractMetric(raw, "voip");
        int voipRat = extractMetric(raw, "voip_rat");
        int sms = extractMetric(raw, "sms");
        int testMode = extractMetric(raw, "test_mode");
        return new Summary(pdp, reg, leg, voip, voipRat, sms, testMode);
    }

    public static int extractMetric(String text, String key) {
        if (text == null || key == null || text.isEmpty() || key.isEmpty()) {
            return -1;
        }
        String qKey = Pattern.quote(key);
        Matcher jsonMatcher = Pattern.compile("\"" + qKey + "\"\\s*:\\s*(-?\\d+)").matcher(text);
        if (jsonMatcher.find()) {
            try {
                return Integer.parseInt(jsonMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        Matcher kvMatcher = Pattern.compile("\\b" + qKey + "=(-?\\d+)").matcher(text);
        if (kvMatcher.find()) {
            try {
                return Integer.parseInt(kvMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * Accepts strictly 5- or 6-digit MCC+MNC codes (e.g. 46001, 46000, 46011).
     * Rejects IMSI, ICCID, MSISDN, or any longer/malformed string so zero PII can pass through.
     */
    public static String sanitizeSimOperator(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if ( trimmed.contains(",")) {
            trimmed = trimmed.split(",", 2)[0].trim();
        }
        return PLMN_PATTERN.matcher(trimmed).matches() ? trimmed : "";
    }

    /**
     * Accepts short radio technology tokens (e.g. LTE, EDGE, UMTS, IWLAN, UNKNOWN) and rejects digits-only PII.
     */
    public static String sanitizeNetworkType(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.contains(",")) {
            trimmed = trimmed.split(",", 2)[0].trim();
        }
        if (!RAT_PATTERN.matcher(trimmed).matches()) {
            return "";
        }
        boolean hasAlpha = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetter(trimmed.charAt(i))) {
                hasAlpha = true;
                break;
            }
        }
        return hasAlpha ? trimmed : "";
    }

    /**
     * Selects the native QMI probe binary path across standalone APK installs
     * ({@code <nativeLibraryDir>/libimsa_probe.so}) and Method 4 ROM source-tree builds
     * ({@code /system_ext/bin/libimsa_probe.so} or {@code /system/bin/libimsa_probe.so}).
     */
    public static String selectProbeBinaryPath(String nativeLibDir, String[] existingExecutablePaths) {
        String apkCandidate = (nativeLibDir != null && !nativeLibDir.trim().isEmpty())
                ? (nativeLibDir.trim().replaceAll("/+$", "") + "/libimsa_probe.so")
                : "";
        String[] candidates = new String[] {
            apkCandidate,
            "/system_ext/bin/libimsa_probe.so",
            "/system/bin/libimsa_probe.so"
        };
        if (existingExecutablePaths != null) {
            for (String cand : candidates) {
                if (cand.isEmpty()) continue;
                for (String existing : existingExecutablePaths) {
                    if (cand.equals(existing)) {
                        return cand;
                    }
                }
            }
        }
        return !apkCandidate.isEmpty() ? apkCandidate : "/system_ext/bin/libimsa_probe.so";
    }
}
