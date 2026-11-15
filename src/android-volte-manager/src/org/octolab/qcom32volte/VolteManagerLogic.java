package org.octolab.qcom32volte;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, host-unit-testable core logic for the Qcom32 VoLTE Companion Manager App.
 * Separates generic 32-bit Qualcomm IMS/QMI telemetry & 4-Gate read-only porting checks
 * from per-device EFS profile guards. One wolverine unit was verified; other editions are experimental.
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
    public static final String GITHUB_REPO_URL = "https://github.com/Octo-o-o-o/qcom32-volte";

    private static final String[] PASSPORT_WSERIES_DEVICES = new String[]{
            "wolverine", "oslo", "wichita", "keian", "wseries", "windermere"
    };
    private static final String[] PASSPORT_MODEL_TOKENS = new String[]{
            "passport", "sqw100", "oslo", "wichita", "keian", "windermere"
    };

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
        public final boolean radioUnavailable;
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
            this(
                    matchedProfileId,
                    isVerifiedEfsProfile,
                    suAvailable,
                    telemetryAvailable,
                    imsServicePresent,
                    imsServiceVersion,
                    rilConnected,
                    qmiRegistered,
                    frameworkRegistered,
                    carrierSummary,
                    false);
        }

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
                String carrierSummary,
                boolean radioUnavailable) {
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
            this.radioUnavailable = !this.volteHealthy && radioUnavailable;
            this.carrierSummary = carrierSummary != null ? carrierSummary : "UNKNOWN";

            if (this.volteHealthy) {
                this.headlineZh = "VoLTE 通话栈已激活并注册就绪（请用系统拨号器实测通话与 4G 保持）";
                this.headlineEn = "VoLTE stack active & IMS registered (verify with native Dialer call + LTE)";
            } else if (this.radioUnavailable && imsServicePresent) {
                this.headlineZh = "检测到基带/SIM 未就绪（无信号）— 可执行固定基带清理，再核查 ROM 与 AAB982 基带；不保证原值或信号恢复";
                this.headlineEn = "Modem/SIM unavailable — Run fixed EFS cleanup and check ROM and AAB982 modem; original values and signal recovery are not guaranteed";
            } else if (!isVerifiedEfsProfile) {
                this.headlineZh = "未建档机型（EFS 写入已保护锁定；请点下方「多机型只读适配体检」并用 AI + GitHub 自助适配）";
                this.headlineEn = "Unverified EFS profile (EFS write locked; tap '32-Bit Readiness Check' & self-adapt via AI + GitHub)";
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
                this.headlineZh = "尚未完成 VoLTE 注册：可点击下方「激活 VoLTE」或重启后复查";
                this.headlineEn = "VoLTE not yet registered: tap 'Activate VoLTE' or reboot to recheck";
            }
        }
    }

    public static final class DeviceCatalogMatch {
        public final String route;
        public final String familyId;
        public final String displayNameZh;
        public final String displayNameEn;
        public final String detailZh;
        public final String detailEn;
        public final boolean prebuiltApkCompatible;

        public DeviceCatalogMatch(
                String route,
                String familyId,
                String displayNameZh,
                String displayNameEn,
                String detailZh,
                String detailEn,
                boolean prebuiltApkCompatible) {
            this.route = route;
            this.familyId = familyId;
            this.displayNameZh = displayNameZh;
            this.displayNameEn = displayNameEn;
            this.detailZh = detailZh;
            this.detailEn = detailEn;
            this.prebuiltApkCompatible = prebuiltApkCompatible;
        }
    }

    public static boolean isPassportWseriesCodename(String device) {
        String dev = device == null ? "" : device.trim().toLowerCase(Locale.ROOT);
        for (String cand : PASSPORT_WSERIES_DEVICES) {
            if (cand.equals(dev)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPassportModelToken(String model) {
        String mod = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        for (String tok : PASSPORT_MODEL_TOKENS) {
            if (mod.contains(tok)) {
                return true;
            }
        }
        return false;
    }

    public static String matchVerifiedDeviceProfile(String model, String device, int sdkInt) {
        if (sdkInt == EXPECTED_SDK && EXPECTED_DEVICE.equalsIgnoreCase(device == null ? "" : device.trim()) && matchesPassportModelToken(model)) {
            return PROFILE_WOLVERINE_ID;
        }
        return null;
    }

    public static boolean verifyPassportIdentity(String model, String device, int sdkInt) {
        return PROFILE_WOLVERINE_ID.equals(matchVerifiedDeviceProfile(model, device, sdkInt));
    }

    public static boolean isPassportFamilyIdentity(String model, String device, int sdkInt) {
        return sdkInt == EXPECTED_SDK && isPassportWseriesCodename(device) && matchesPassportModelToken(model);
    }

    public static boolean isExperimentalPassportEdition(String model, String device, int sdkInt) {
        return isPassportFamilyIdentity(model, device, sdkInt) && !verifyPassportIdentity(model, device, sdkInt);
    }

    public static boolean isRootOperationAllowed(String mode, String model, String device, int sdkInt) {
        if ("rollback".equals(mode)) return isPassportFamilyIdentity(model, device, sdkInt);
        if ("deploy".equals(mode)) return verifyPassportIdentity(model, device, sdkInt);
        return "upgrade".equals(mode) && !isExperimentalPassportEdition(model, device, sdkInt);
    }

    public static DeviceCatalogMatch identifyDeviceCatalog(
            String brand,
            String model,
            String device,
            String boardPlatform,
            String cpuAbi,
            int sdkInt,
            String buildTags) {
        String devL = device == null ? "" : device.trim().toLowerCase(Locale.ROOT);
        String modU = model == null ? "" : model.trim().toUpperCase(Locale.ROOT);
        String brandL = brand == null ? "" : brand.trim().toLowerCase(Locale.ROOT);
        String platL = boardPlatform == null ? "" : boardPlatform.trim().toLowerCase(Locale.ROOT);
        String abiL = cpuAbi == null ? "" : cpuAbi.trim().toLowerCase(Locale.ROOT);
        String tagsL = buildTags == null ? "" : buildTags.trim().toLowerCase(Locale.ROOT);
        boolean testKeys = tagsL.contains("test-keys");

        if (abiL.startsWith("arm64")) {
            return new DeviceCatalogMatch(
                    "UNSUPPORTED_64BIT_PLATFORM",
                    "unsupported-64bit",
                    "64 位平台（非本项目目标架构）",
                    "64-Bit Platform (Out of Scope)",
                    "检测到 64 位 ABI (" + cpuAbi + ")；Qcom32 VoLTE 专为缺少现代 ImsService 的高通 32 位老旗舰（MSM8974/APQ8084/MSM8926）设计。",
                    "Detected 64-bit ABI (" + cpuAbi + "); Qcom32 VoLTE targets legacy 32-bit Qualcomm platforms (MSM8974/APQ8084/MSM8926).",
                    testKeys);
        }
        if (devL.equals("k3gxx") || devL.equals("k3g") || modU.contains("G900H")
                || platL.contains("exynos") || platL.contains("universal5422")
                || platL.contains("mt6") || platL.contains("mt8") || platL.contains("kirin") || platL.contains("hi36")) {
            return new DeviceCatalogMatch(
                    "UNSUPPORTED_NON_QUALCOMM",
                    "unsupported-non-qualcomm",
                    "非高通骁龙平台（硬件不支持）",
                    "Non-Qualcomm Platform (Unsupported)",
                    "当前设备平台 (" + boardPlatform + " / " + model + ") 并非高通骁龙基带，无法使用 QMI/EFS2 通话栈。",
                    "Board platform (" + boardPlatform + " / " + model + ") is not Qualcomm Snapdragon.",
                    testKeys);
        }
        if (devL.equals("pisces") || modU.equals("MI 4W") || modU.equals("MI 3W") || modU.equals("MI 4C")) {
            return new DeviceCatalogMatch(
                    "UNSUPPORTED_HARDWARE_VARIANT",
                    "xiaomi-3g-only-variant",
                    "小米 3G 硬件版本（无 4G LTE 基带）",
                    "Xiaomi 3G-Only Variant (No LTE Baseband)",
                    model + " (" + device + ") 为联通/电信 3G 硬件版，无 4G LTE 基带，硬件上无法支持 VoLTE。",
                    model + " (" + device + ") is a 3G-only hardware edition without a 4G LTE modem.",
                    testKeys);
        }
        if (modU.contains("G900A") || modU.contains("G900V")) {
            return new DeviceCatalogMatch(
                    "UNSUPPORTED_HARDWARE_VARIANT",
                    "samsung-s5-locked-bl",
                    "美版锁 Bootloader 变体（SM-G900A/V）",
                    "Locked-Bootloader Carrier S5 Variant",
                    "美版定制款 " + model + " 存在 Bootloader 签名锁限制，请注意刷机风险。",
                    model + " has carrier bootloader restrictions; verify unlock status carefully.",
                    testKeys);
        }
        if (devL.equals("hammerhead") || modU.equals("NEXUS 5") || modU.contains("LG-D820") || modU.contains("LG-D821")) {
            return new DeviceCatalogMatch(
                    "MODEM_LACKS_IMS_ENGINE",
                    "google-nexus-5-hammerhead",
                    "Google Nexus 5（原厂基带缺失 qipcall IMS 引擎）",
                    "Google Nexus 5 (Modem Lacks qipcall IMS Engine)",
                    "Nexus 5 (hammerhead) 原厂高通基带裁剪了 Hexagon qipcall VoLTE 引擎，需先核查基带符号。",
                    "Stock Nexus 5 (hammerhead) baseband omits the Hexagon qipcall IMS engine.",
                    testKeys);
        }

        if (verifyPassportIdentity(model, device, sdkInt)) {
            return new DeviceCatalogMatch(
                    "VERIFIED_PASSPORT_READY",
                    PROFILE_WOLVERINE_ID,
                    "黑莓 Passport wolverine（仅一台 SQW100-1 已验证）",
                    "BlackBerry Passport wolverine (one SQW100-1 verified)",
                    "联通 46001 已实测通话；电信仅验证 LTE 与 IMS 注册，通话未测。",
                    "46001 calls verified on one unit; Telecom LTE and IMS registration only, calls untested.",
                    testKeys);
        }
        if (isExperimentalPassportEdition(model, device, sdkInt)) {
            return new DeviceCatalogMatch("EXPERIMENTAL_UNVERIFIED_EDITION", PROFILE_WOLVERINE_ID,
                    "Passport 未经真机验证版本（只读）", "Passport unverified edition (read-only)",
                    "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证；仅只读与回滚。",
                    "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证; read-only and rollback only.", testKeys);
        }
        if (isPassportWseriesCodename(devL) || matchesPassportModelToken(modU)) {
            return new DeviceCatalogMatch(
                    "NEEDS_ANDROID11_ROM_FIRST",
                    PROFILE_WOLVERINE_ID,
                    "黑莓 Passport 系列（需先运行 Android 11 / API 30）",
                    "BlackBerry Passport Family (Requires Android 11 / API 30)",
                    "检测到 Passport 机型但系统为 SDK " + sdkInt + "；若为原厂 BB10 系统请访问 bb10-volte.octoooo.com，Android 请刷 LineageOS 18.1。",
                    "Passport detected on SDK " + sdkInt + "; use bb10-volte.octoooo.com for BB10 OS or LineageOS 18.1 for Android 11.",
                    testKeys);
        }

        String famId = "generic-qcom32-candidate";
        String nameZh = "高通 32 位候选机型（" + (model == null || model.isEmpty() ? devL : model) + "）";
        String nameEn = "32-Bit Qualcomm Candidate (" + (model == null || model.isEmpty() ? devL : model) + ")";
        if (devL.startsWith("klte") || modU.contains("SM-G900") || modU.contains("SCL23") || modU.contains("SC-04F")) {
            famId = "samsung-galaxy-s5-klte";
            nameZh = "三星 Galaxy S5 系列 (klte / MSM8974AC)";
            nameEn = "Samsung Galaxy S5 Family (klte / MSM8974AC)";
        } else if (devL.equals("cancro") || devL.equals("virgo") || modU.contains("MI 4") || modU.contains("MI NOTE")) {
            famId = "xiaomi-mi4-cancro";
            nameZh = "小米 4 LTE / 小米 Note 系列 (cancro / virgo / MSM8974AC)";
            nameEn = "Xiaomi Mi 4 LTE / Mi Note Family (cancro / virgo / MSM8974AC)";
        } else if (devL.equals("bacon") || devL.startsWith("find7") || modU.contains("A0001") || modU.contains("ONEPLUS") || modU.contains("X900") || modU.contains("X907")) {
            famId = "oneplus-one-bacon";
            nameZh = "一加 1 / OPPO Find 7 系列 (bacon / find7 / MSM8974AC)";
            nameEn = "OnePlus One / OPPO Find 7 Family (bacon / find7 / MSM8974AC)";
        } else if (devL.equals("sirius") || devL.equals("leo") || devL.equals("z3") || devL.equals("aries")
                || devL.equals("z3c") || devL.equals("honami") || devL.equals("amami") || devL.equals("castor")
                || brandL.equals("sony") || modU.contains("XPERIA") || modU.startsWith("D65") || modU.startsWith("D66") || modU.startsWith("D58")) {
            famId = "sony-xperia-z1-z2-z3";
            nameZh = "索尼 Xperia Z1 / Z2 / Z3 系列 (Shinano / Rhine MSM8974)";
            nameEn = "Sony Xperia Z1 / Z2 / Z3 Family (Shinano / Rhine MSM8974)";
        } else if (devL.equals("d855") || devL.startsWith("d85") || devL.startsWith("f400") || devL.equals("ls990")
                || devL.equals("vs985") || devL.startsWith("d80") || devL.equals("g3") || devL.equals("g2")
                || modU.contains("LG-D85") || modU.contains("LG-D80") || modU.contains("LG-F400")) {
            famId = "lg-g3-g2-msm8974";
            nameZh = "LG G3 / G2 系列 (d855 / d802 / MSM8974)";
            nameEn = "LG G3 / G2 Family (d855 / d802 / MSM8974)";
        } else if (devL.startsWith("m8") || devL.equals("b2ul") || modU.contains("ONE M8") || modU.contains("HTC_M8")) {
            famId = "htc-one-m8";
            nameZh = "HTC One M8 系列 (m8 / MSM8974AB/AC)";
            nameEn = "HTC One M8 Family (m8 / MSM8974AB/AC)";
        } else if (devL.equals("victara") || devL.equals("shamu") || devL.equals("quark")
                || modU.contains("NEXUS 6") || modU.contains("XT109") || modU.contains("XT1225") || modU.contains("XT1254")) {
            famId = "motorola-victara-shamu";
            nameZh = "Moto X 2014 / Droid Turbo / Nexus 6 系列 (victara / shamu)";
            nameEn = "Moto X 2014 / Droid Turbo / Nexus 6 Family (victara / shamu)";
        } else if (devL.startsWith("hlte") || devL.startsWith("trlte") || devL.startsWith("ks01lte")
                || modU.contains("SM-N9005") || modU.contains("SM-N910") || modU.contains("GT-I9506")) {
            famId = "samsung-note3-note4-s4ltea";
            nameZh = "三星 Note 3 / Note 4 / S4 LTE-A 骁龙版 (hlte / trlte / ks01lte)";
            nameEn = "Samsung Note 3 / Note 4 / S4 LTE-A Snapdragon (hlte / trlte / ks01lte)";
        }

        if (sdkInt != EXPECTED_SDK) {
            return new DeviceCatalogMatch(
                    "NEEDS_ANDROID11_ROM_FIRST",
                    famId,
                    nameZh,
                    nameEn,
                    "当前系统为 Android SDK " + sdkInt + "，需先刷入 LineageOS 18.1 (Android 11 / API 30) 再进行 4-Gate 适配。",
                    "Running Android SDK " + sdkInt + "; flash LineageOS 18.1 (Android 11 / API 30) first before 4-Gate porting.",
                    testKeys);
        }

        return new DeviceCatalogMatch(
                "CANDIDATE_MSM8974_PORTING",
                famId,
                nameZh,
                nameEn,
                "通用 Android 11 通话栈 Qcom32VolteIms 可复用；基带 EFS 槽位需使用 AI + GitHub 仓库内置 Skill 按 4-Gate 只读审计后建档。",
                "Generic Qcom32VolteIms stack is reusable; use AI + the GitHub repo built-in Skill to run 4-Gate read-only PDP slot discovery.",
                testKeys);
    }

    public static boolean isPassportBasebandCompatible(String basebandProp, String firmwareContents) {
        String bb = basebandProp == null ? "" : basebandProp.trim().toUpperCase(Locale.ROOT);
        if (!bb.contains("MPSS.DI.4.0.1") || !bb.contains("AAAAANAZM")
                || bb.contains("DI.2.0.1") || bb.contains("AAAAANPZM")) {
            return false;
        }
        String fw = firmwareContents == null ? "" : firmwareContents.toUpperCase(Locale.ROOT);
        java.util.regex.Matcher buildId = java.util.regex.Pattern.compile("(?m)^\\s*BUILD_ID\\s*:\\s*([A-Z0-9_.-]+)").matcher(fw);
        return buildId.find() && "AAB982".equals(buildId.group(1));
    }

    public static boolean hasConflictingLegacyImsDaemon(String daemonSummary) {
        if (daemonSummary == null || daemonSummary.trim().isEmpty()) {
            return false;
        }
        String s = daemonSummary.toLowerCase(Locale.ROOT);
        return s.contains("=running")
                || s.contains("=restarting")
                || s.contains("qmi_daemon_status=1")
                || s.contains("data_daemon_status=1");
    }

    public static String buildReadOnlyPortingReport(
            String brand,
            String model,
            String device,
            String boardPlatform,
            String cpuAbi,
            int sdkInt,
            String buildTags,
            String selinuxMode,
            boolean suAvailable,
            boolean rildSocketPresent,
            boolean diagNodePresent,
            boolean audioMarkersFound,
            Boolean modemHasImsSymbols,
            boolean english) {
        return buildReadOnlyPortingReport(
                brand,
                model,
                device,
                boardPlatform,
                cpuAbi,
                sdkInt,
                buildTags,
                selinuxMode,
                suAvailable,
                rildSocketPresent,
                diagNodePresent,
                audioMarkersFound,
                modemHasImsSymbols,
                english,
                "",
                "",
                "");
    }

    public static String buildReadOnlyPortingReport(
            String brand,
            String model,
            String device,
            String boardPlatform,
            String cpuAbi,
            int sdkInt,
            String buildTags,
            String selinuxMode,
            boolean suAvailable,
            boolean rildSocketPresent,
            boolean diagNodePresent,
            boolean audioMarkersFound,
            Boolean modemHasImsSymbols,
            boolean english,
            String basebandProp,
            String firmwareContents,
            String legacyDaemonSummary) {
        DeviceCatalogMatch m = identifyDeviceCatalog(brand, model, device, boardPlatform, cpuAbi, sdkInt, buildTags);
        boolean legacyConflict = hasConflictingLegacyImsDaemon(legacyDaemonSummary);
        String bbClean = basebandProp == null ? "" : basebandProp.trim();
        boolean isPassport = "VERIFIED_PASSPORT_READY".equals(m.route);

        String modemGate;
        if (modemHasImsSymbols == null) {
            modemGate = english
                    ? "UNCHECKED (Requires Root / ADB Root to scan /firmware/image/modem*)"
                    : "待用电脑开启 ADB Root 或本地 su 只读扫描 /firmware/image/modem*";
        } else if (modemHasImsSymbols) {
            modemGate = english ? "PASS (qp_ims_reg_config / qipcall found)" : "通过（检出 qp_ims_reg_config / qipcall 引擎符号）";
        } else {
            modemGate = english ? "FAIL (No qipcall IMS symbol in modem firmware)" : "未检出（当前基带镜像不含 qipcall IMS 引擎）";
        }
        if (isPassport && (!bbClean.isEmpty() || (firmwareContents != null && !firmwareContents.trim().isEmpty()))) {
            if (isPassportBasebandCompatible(bbClean, firmwareContents)) {
                modemGate += english
                        ? (" | Baseband PASS (" + safeText(bbClean) + ")")
                        : (" | 基带版本通过 (" + safeText(bbClean) + ")");
            } else {
                modemGate = english
                        ? ("FAIL (Incompatible Passport modem '" + safeText(bbClean) + "' — flash BlackBerry Android AAB982 NON-HLOS.bin first)")
                        : ("不兼容（当前基带 '" + safeText(bbClean) + "' 非 AAB982 DI.4.0.1-00158 正式版，请先刷入 AAB982 NON-HLOS.bin）");
            }
        }

        String romGate = (sdkInt == EXPECTED_SDK)
                ? (m.prebuiltApkCompatible
                        ? (english ? "PASS (SDK 30 + test-keys; prebuilt APK installs directly)" : "通过（Android 11 SDK 30 + test-keys，可直接安装预编译 APK）")
                        : (english ? "WARN (SDK 30 + release-keys; sign with ROM platform key or use rom-integration/)" : "注意（Android 11 SDK 30 + release-keys 官方签名，需用 ROM 平台密钥重签或放入 priv-app）"))
                : (english ? ("FAIL (SDK " + sdkInt + ", requires Android 11 SDK 30)") : ("未满足（当前 SDK " + sdkInt + "，需 Android 11 / LineageOS 18.1）"));
        if (legacyConflict) {
            romGate = english
                    ? ("FAIL (Legacy vendor imsqmidaemon active [" + safeText(legacyDaemonSummary) + "] — upgrade to Enforcing LineageOS 18.1 20260129+)")
                    : ("冲突拦截（检出旧版 ROM 残留 imsqmidaemon 运行中 [" + safeText(legacyDaemonSummary) + "]，开启 IMS_enable=01 会导致重启无信号；请先升级 Enforcing 版 LineageOS 18.1）");
        }

        String sockGate = rildSocketPresent
                ? (english ? "PASS (/dev/socket/qmux_radio/rild_ims0 present)" : "已检出 (/dev/socket/qmux_radio/rild_ims0)")
                : (suAvailable
                        ? (english ? "WARN (rild_ims0 not found)" : "未发现 rild_ims0 套接字")
                        : (english ? "INFO (Connect PC ADB Root or su to verify /dev/socket/qmux_radio/rild_ims0)" : "待核验（普通应用权限受 SELinux 限制，连电脑点「只读体检」可精确核验）"));

        String diagGate = diagNodePresent
                ? (english ? "PASS (/dev/diag character node present)" : "已检出 (/dev/diag 诊断节点)")
                : (suAvailable
                        ? (english ? "WARN (/dev/diag not found)" : "未发现 /dev/diag 节点")
                        : (english ? "INFO (Verify via PC Read-Only Check)" : "待核验（连电脑桌面端一键只读体检可精确核验）"));

        String audioGate = audioMarkersFound
                ? (english ? "PASS (Voice-MMode1 / voice-ims found in mixer_paths)" : "通过（mixer_paths 检出 Voice-MMode1 / voice-ims 通话路由）")
                : (english ? "WARN (Check /vendor/etc/mixer_paths.xml for Voice-MMode1)" : "待核查（建议检查 /vendor/etc/mixer_paths.xml 是否含 Voice-MMode1）");

        if (english) {
            return "=== Qcom32 VoLTE Read-Only Readiness & AI Porting Report ===\n"
                    + "Device       : " + safeText(brand) + " " + safeText(model) + " (" + safeText(device) + ")\n"
                    + "SoC / ABI    : " + safeText(boardPlatform) + " / " + safeText(cpuAbi) + " | SDK " + sdkInt + " | SELinux=" + safeText(selinuxMode) + "\n"
                    + "Catalog Match: " + m.displayNameEn + " [" + m.familyId + "]\n"
                    + "Route        : " + m.route + "\n"
                    + "Summary      : " + m.detailEn + "\n\n"
                    + "[4-Gate Read-Only Check (Zero EFS Writes)]\n"
                    + "  Gate 1 (ROM & Key)   : " + romGate + "\n"
                    + "  Gate 2 (RIL & Diag)  : " + sockGate + " | " + diagGate + "\n"
                    + "  Gate 3 (Modem IMS)   : " + modemGate + "\n"
                    + "  Gate 4 (Audio HAL)   : " + audioGate + "\n\n"
                    + "[How to Adapt Your Phone Yourself with AI + GitHub]\n"
                    + "  Why EFS write is locked: Passport's IMS APN is /pdp_profiles/profile4, whereas Samsung/Xiaomi/\n"
                    + "  OnePlus/Sony may use profile4 for MMS or mobile data. Blindly writing it would break data/MMS.\n"
                    + "  1. Clone the source-available repo (PolyForm Noncommercial 1.0.0; commercial license available): " + GITHUB_REPO_URL + "\n"
                    + "  2. Open the repo folder in Claude Code, Cursor, Codex, Gemini CLI, or Antigravity\n"
                    + "     (it includes the built-in `.agents/skills/qcom32-volte/` & `.claude/skills/qcom32-volte/` AI Skill!).\n"
                    + "  3. Connect USB, paste this report, and tell your AI assistant:\n"
                    + "     \"Run the qcom32-volte 4-Gate read-only inspection on my connected " + safeText(model) + " (" + safeText(device) + "),\n"
                    + "      decode /pdp_profiles/ safely without overwriting data/MMS slots, and help me build its profile.json.\"\n";
        }
        return "=== Qcom32 VoLTE 多机型只读适配体检与 AI 自助适配报告 ===\n"
                + "当前设备   : " + safeText(brand) + " " + safeText(model) + " (" + safeText(device) + ")\n"
                + "平台与系统 : " + safeText(boardPlatform) + " / " + safeText(cpuAbi) + " | SDK " + sdkInt + " | SELinux=" + safeText(selinuxMode) + "\n"
                + "识别机系   : " + m.displayNameZh + " [" + m.familyId + "]\n"
                + "当前路由   : " + m.route + "\n"
                + "评估结论   : " + m.detailZh + "\n\n"
                + "【四门禁 100% 只读体检（零基带写入）】\n"
                + "  Gate 1 (ROM 与签名) : " + romGate + "\n"
                + "  Gate 2 (RIL 与诊断) : " + sockGate + " | " + diagGate + "\n"
                + "  Gate 3 (基带引擎)   : " + modemGate + "\n"
                + "  Gate 4 (音频通路)   : " + audioGate + "\n\n"
                + "【如何使用 AI + GitHub 项目自行适配本机型？】\n"
                + "  为什么默认锁定写基带：Passport 的 IMS 槽位是 /pdp_profiles/profile4，但三星 S5、小米 4、一加 1、\n"
                + "  索尼 Z2/Z3、LG G3 等机型的 profile4 可能是彩信或上网槽位，盲写会破坏移动数据。\n"
                + "  项目已在 GitHub 内置多智能体 AI Skill，您可以用 AI 助手安全自助对应：\n"
                + "  1. 在电脑克隆源码可见仓库（PolyForm Noncommercial 1.0.0 + 商业授权）：" + GITHUB_REPO_URL + "\n"
                + "  2. 用 Claude Code、Cursor、Codex、Gemini CLI 或 Antigravity 打开仓库目录\n"
                + "     （会自动加载仓库内置的 `.agents/skills/qcom32-volte/` 与 `.claude/skills/qcom32-volte/` 适配技能）；\n"
                + "  3. 手机连上电脑 USB，复制本报告发给 AI 并输入：\n"
                + "     「请按仓库内置 qcom32-volte 技能对这台 " + safeText(model) + " (" + safeText(device) + ") 执行只读 4-Gate 体检，\n"
                + "      只读解码 /pdp_profiles/ 找出安全的 IMS 槽位并生成专属 profile.json 完成适配。」\n";
    }

    private static String safeText(String s) {
        return (s == null || s.trim().isEmpty()) ? "unknown" : redactSensitiveDigits(s.trim());
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
            case "46020":
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
        return parseStatusSummary(
                model,
                device,
                sdkInt,
                suAvailable,
                serviceDump,
                qmiOut,
                phoneDump,
                simNumeric,
                networkType,
                null,
                false);
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
            String networkType,
            String simStateProp,
            boolean airplaneMode) {
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
                || sDump.contains("publishedFrameworkReg=1");

        String simOpClean = simNumeric == null ? "" : simNumeric.trim();
        boolean radioDown = simStateProp != null
                && simStateProp.trim().isEmpty()
                && simOpClean.isEmpty()
                && !airplaneMode;

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
                formatCarrierLabel(simNumeric, networkType),
                radioDown);
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

    public static String quoteSh(String path) {
        if (path == null || path.isEmpty() || path.contains("'") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalArgumentException("Unsafe shell path: " + path);
        }
        return "'" + path + "'";
    }

    private static String passportDeviceGuardLines() {
        return "DEV=$(getprop ro.product.device)\n"
                + "case \"$DEV\" in\n"
                + "  wolverine|oslo|wichita|keian|wseries|windermere) ;;\n"
                + "  *) echo \"ERR_UNVERIFIED_EFS_PROFILE:$DEV\"; exit 21 ;;\n"
                + "esac\n";
    }

    private static String verifiedDeployGuardLines() {
        return "DEV=$(getprop ro.product.device)\n"
                + "case \"$DEV\" in\n"
                + "  wolverine) ;;\n"
                + "  *) echo \"ERR_UNVERIFIED_EFS_PROFILE:$DEV\"; exit 21 ;;\n"
                + "esac\n";
    }

    private static String legacyCleanupLines() {
        return "if pm path org.passport.volteims >/dev/null 2>&1; then\n"
                + "  am broadcast -n org.passport.volteims/.VolteAdminReceiver "
                + "-a org.passport.volteims.ACTION_ADMIN --es op clear_override --es cmd clear_override --include-stopped-packages || true\n"
                + "  pm uninstall org.passport.volteims || true\n"
                + "fi\n";
    }

    private static String passportDeploySafetyGuardLines() {
        return "SIM_ST=$(getprop gsm.sim.state)\n"
                + "case \"$SIM_ST\" in\n"
                + "  *LOADED*) ;;\n"
                + "  *) echo \"ERR_SIM_NOT_LOADED:gsm.sim.state=$SIM_ST (Insert active 4G USIM and disable Airplane Mode first)\"; exit 22 ;;\n"
                + "esac\n"
                + "for SVC in imsqmidaemon imsdatadaemon ims_rtp_daemon imscmservice; do\n"
                + "  ST=$(getprop init.svc.$SVC)\n"
                + "  case \"$ST\" in\n"
                + "    running|restarting)\n"
                + "      echo \"ERR_LEGACY_IMS_DAEMON_CONFLICT:$SVC=$ST (Older Permissive ROM runs legacy vendor imsqmidaemon which collides with Stage 6A IMS_enable=01 after reboot; upgrade to Enforcing LineageOS 18.1 20260129+ first)\"; exit 23 ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [ \"$(getprop sys.ims.QMI_DAEMON_STATUS)\" = \"1\" ] || [ \"$(getprop sys.ims.DATA_DAEMON_STATUS)\" = \"1\" ]; then\n"
                + "  echo \"ERR_LEGACY_IMS_DAEMON_CONFLICT:sys.ims_active=1 (Upgrade to Enforcing LineageOS 18.1 20260129+ without legacy vendor imsqmidaemon)\"; exit 23\n"
                + "fi\n"
                + "BB_VER=$(getprop gsm.version.baseband)\n"
                + "case \"$BB_VER\" in\n"
                + "  *DI.2.0.1*|*AAAAANPZM*) echo \"ERR_INCOMPATIBLE_PASSPORT_BASEBAND:baseband=$BB_VER\"; exit 24 ;;\n"
                + "  *MPSS.DI.4.0.1*AAAAANAZM*) ;;\n"
                + "  *) echo \"ERR_INCOMPATIBLE_PASSPORT_BASEBAND:baseband=$BB_VER (Expected BlackBerry Android AAB982 MPSS.DI.4.0.1-00158-M8974AAAAANAZM-1)\"; exit 24 ;;\n"
                + "esac\n"
                + "FW_TXT=$(cat /firmware/IMAGE/CONTENTS.TXT /firmware/image/contents.txt /vendor/firmware_mnt/IMAGE/CONTENTS.TXT /vendor/firmware_mnt/image/contents.txt 2>/dev/null || true)\n"
                + "case \"$FW_TXT\" in\n"
                + "  *build_id:AAA*|*BUILD_ID:AAA*)\n"
                + "    echo \"ERR_LEGACY_AAA_BASEBAND:Older engineering AAA* NON-HLOS.bin detected; flash BlackBerry Android AAB982 NON-HLOS.bin first\"; exit 25 ;;\n"
                + "esac\n"
                + "if ! printf '%s\\n' \"$FW_TXT\" | grep -iqE '^[[:space:]]*build_id[[:space:]]*:[[:space:]]*AAB982([[:space:]]|$)'; then\n"
                + "  echo \"ERR_UNVERIFIED_FIRMWARE_BUILD_ID:AAB982 is required\"; exit 25\n"
                + "fi\n";
    }

    private static String stopLegacyVendorImsDaemonsLines() {
        return "setprop sys.ims.DATA_DAEMON_STATUS 0 2>/dev/null || true\n"
                + "setprop sys.ims.QMI_DAEMON_STATUS 0 2>/dev/null || true\n"
                + "for SVC in imscmservice ims_rtp_daemon imsdatadaemon imsqmidaemon; do\n"
                + "  stop \"$SVC\" 2>/dev/null || true\n"
                + "done\n";
    }

    public static String buildRootDeployScript(String appStagedDir, String backupDir) {
        String stageQuoted = quoteSh(appStagedDir);
        String backupQuoted = quoteSh(backupDir);
        return "set -e\n"
                + verifiedDeployGuardLines()
                + passportDeploySafetyGuardLines()
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
                + "-a org.octolab.qcom32ims.ACTION_ADMIN --es op apply_override --es cmd apply_override --ez reboot_required true --include-stopped-packages\n"
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
                + passportDeviceGuardLines()
                + "TMP_PROV='/data/local/tmp/pp_regconfig_provision_v6'\n"
                + "trap 'rm -f /data/local/tmp/pp_regconfig_provision_v6' EXIT\n"
                + stopLegacyVendorImsDaemonsLines()
                + legacyCleanupLines()
                + "(timeout 8 content call --uri content://org.octolab.qcom32ims.status --method clear_override || true) >/dev/null 2>&1 || true\n"
                + "am broadcast -n org.octolab.qcom32ims/.VolteAdminReceiver "
                + "-a org.octolab.qcom32ims.ACTION_ADMIN --es op clear_override --es cmd clear_override --include-stopped-packages || true\n"
                + "rm -f /data/user_de/0/com.android.phone/files/carrierconfig-org.octolab.qcom32ims-*.xml /data/user_de/0/com.android.phone/files/*override*.xml || true\n"
                + "sync || true\n"
                + "sleep 1\n"
                + "pm uninstall org.octolab.qcom32ims || true\n"
                + "setprop persist.radio.fallbackmode '' || true\n"
                + "cp " + stageQuoted + "/pp_regconfig_provision_v6 \"$TMP_PROV\"\n"
                + "chmod 0700 \"$TMP_PROV\"\n"
                + "\"$TMP_PROV\" --rollback-all\n"
                + "echo 'ROLLBACK_OK'\n";
    }

    public static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
