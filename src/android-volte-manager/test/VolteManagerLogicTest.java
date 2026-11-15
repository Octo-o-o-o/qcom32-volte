package org.octolab.qcom32volte;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class VolteManagerLogicTest {
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FAILED: " + msg);
        }
        passed++;
    }

    private static int runShellGuard(String script, String device, String baseband, String firmware, String daemon) throws Exception {
        Path dir = Files.createTempDirectory("qcom32-guard-test-");
        Path getprop = dir.resolve("getprop");
        Path cat = dir.resolve("cat");
        try {
            Files.write(getprop, ("#!/bin/sh\ncase \"$1\" in\n"
                    + "ro.product.device) printf '%s' \"$TEST_DEVICE\" ;;\n"
                    + "gsm.sim.state) printf LOADED ;;\n"
                    + "gsm.version.baseband) printf '%s' \"$TEST_BASEBAND\" ;;\n"
                    + "init.svc.imsqmidaemon) printf '%s' \"$TEST_DAEMON\" ;;\n"
                    + "esac\n").getBytes(StandardCharsets.UTF_8));
            Files.write(cat, "#!/bin/sh\nprintf '%s\\n' \"$TEST_FIRMWARE\"\n".getBytes(StandardCharsets.UTF_8));
            if (!getprop.toFile().setExecutable(true) || !cat.toFile().setExecutable(true)) {
                throw new IllegalStateException("Cannot make fake commands executable");
            }
            String preflight = script.substring(0, script.indexOf("TMP_PROV="));
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", preflight).redirectErrorStream(true);
            builder.environment().put("PATH", dir + ":" + System.getenv("PATH"));
            builder.environment().put("TEST_DEVICE", device);
            builder.environment().put("TEST_BASEBAND", baseband);
            builder.environment().put("TEST_FIRMWARE", firmware);
            builder.environment().put("TEST_DAEMON", daemon);
            Process process = builder.start();
            try (java.io.InputStream input = process.getInputStream()) {
                byte[] buf = new byte[512];
                while (input.read(buf) != -1) { /* Drain the small, simulated error output. */ }
            }
            return process.waitFor();
        } finally {
            Files.deleteIfExists(getprop);
            Files.deleteIfExists(cat);
            Files.deleteIfExists(dir);
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. Only wolverine is enabled for on-device deploy; all Passport editions retain rollback.
        check(VolteManagerLogic.verifyPassportIdentity("Passport", "wolverine", 30), "Passport wolverine API 30 verified");
        check(VolteManagerLogic.verifyPassportIdentity("BlackBerry Passport", "wolverine", 30), "BlackBerry Passport variant verified");
        check(VolteManagerLogic.isRootOperationAllowed("deploy", "Passport", "wolverine", 30), "wolverine deploy allowed");
        for (String dev : new String[]{"oslo", "wichita", "keian", "wseries", "windermere"}) {
            check(!VolteManagerLogic.verifyPassportIdentity("Passport", dev, 30), dev + " unverified");
            check(!VolteManagerLogic.isRootOperationAllowed("deploy", "Passport", dev, 30), dev + " deploy blocked");
            check(!VolteManagerLogic.isRootOperationAllowed("upgrade", "Passport", dev, 30), dev + " upgrade blocked");
            check(VolteManagerLogic.isRootOperationAllowed("rollback", "Passport", dev, 30), dev + " rollback allowed");
        }
        check(VolteManagerLogic.isRootOperationAllowed("rollback", "Passport", "wolverine", 30), "wolverine rollback allowed");
        check("blackberry-passport-wolverine".equals(
                VolteManagerLogic.matchVerifiedDeviceProfile("Passport", "wolverine", 30)), "Profile ID exact");
        check(VolteManagerLogic.matchVerifiedDeviceProfile("Passport Silver Edition", "oslo", 30) == null, "Silver Edition not verified");
        check(!VolteManagerLogic.verifyPassportIdentity("OnePlus One", "bacon", 30), "Unverified profile gated from EFS write");
        check(!VolteManagerLogic.isRootOperationAllowed("rollback", "OnePlus One", "bacon", 30), "non-Passport rollback blocked");
        check(!VolteManagerLogic.verifyPassportIdentity("Passport", "wolverine", 29), "Non-Android-11 rejected");
        check(!VolteManagerLogic.verifyPassportIdentity("Passport Silver Edition", "oslo", 29), "Silver Edition non-Android-11 rejected");

        // 2. SHA-256 check
        String abcHash = VolteManagerLogic.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
        check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(abcHash), "SHA-256 NIST vector");

        // 3. PII redaction
        String sample = "IMSI=4600112" + "34567890 num=18612" + "345678 short=46001";
        String redacted = VolteManagerLogic.redactSensitiveDigits(sample);
        check(!redacted.contains("4600112" + "34567890"), "IMSI redacted");
        check(!redacted.contains("18612" + "345678"), "11-digit phone number redacted");
        check(redacted.contains("short=46001"), "PLMN 46001 preserved");

        // 4. Healthy China Unicom status snapshot (JSON QMI + no local su via VolteStatusProvider)
        VolteManagerLogic.StatusSnapshot snapProviderNoSu = VolteManagerLogic.parseStatusSummary(
                "Passport",
                "wolverine",
                30,
                false,
                "QCOM32_VOLTE_IMS_V1\nversion=2.5-qcom32-volte\nrilConnected=true\npublishedFrameworkReg=1",
                "{\"event\":\"imsa_pdp\",\"pdp_connected\":1}\n{\"event\":\"imsa_reg\",\"reg_status\":2,\"legacy_reg\":1}\n{\"event\":\"imsa_svc\",\"voip\":2,\"voip_rat\":1}",
                "",
                "46006",
                "LTE");
        check(snapProviderNoSu.telemetryAvailable, "VolteStatusProvider telemetry works without su");
        check(snapProviderNoSu.volteHealthy, "Healthy snapshot detected without su");
        check("2.5-qcom32-volte".equals(snapProviderNoSu.imsServiceVersion), "Service version 2.5-qcom32-volte parsed");
        check("China Unicom (46006) · LTE".equals(snapProviderNoSu.carrierSummary), "China Unicom 46006 label exact");

        VolteManagerLogic.StatusSnapshot snapUnreg = VolteManagerLogic.parseStatusSummary(
                "Passport",
                "wolverine",
                30,
                false,
                "QCOM32_VOLTE_IMS_V1\nversion=2.5-qcom32-volte\nrilConnected=true\npublishedFrameworkReg=2",
                "pdp_connected=1 reg_status=1 voip=0 voip_rat=-1",
                "",
                "46011",
                "LTE");
        check(!snapUnreg.frameworkRegistered && !snapUnreg.volteHealthy,
                "publishedFrameworkReg=2 (NOT_REGISTERED) is reported as frameworkRegistered=false");

        // 5. Four-carrier label checks (Mobile, Telecom & Broadnet 46015/46020)
        check("China Mobile (46000) · LTE".equals(VolteManagerLogic.formatCarrierLabel("46000,", "LTE,")), "China Mobile 46000 label");
        check("China Telecom (46011) · LTE".equals(VolteManagerLogic.formatCarrierLabel("46011", "LTE")), "China Telecom 46011 label");
        check("China Broadnet (46020) · LTE".equals(VolteManagerLogic.formatCarrierLabel("46020", "LTE")), "China Broadnet 46020 label");

        // 6. Generic 32-bit Qualcomm read-only telemetry on non-wolverine ROM integration
        VolteManagerLogic.StatusSnapshot snapGenericRom = VolteManagerLogic.parseStatusSummary(
                "A0001",
                "bacon",
                30,
                false,
                "QCOM32_VOLTE_IMS_V1\nversion=2.4-qcom32-volte\nrilConnected=true\npublishedFrameworkReg=1",
                "pdp_connected=1 reg_status=2 voip=2 voip_rat=1",
                "",
                "46001",
                "LTE");
        check(!snapGenericRom.isVerifiedEfsProfile && "generic-qcom32-readonly".equals(snapGenericRom.matchedProfileId),
                "Generic MSM8974 device stays gated from EFS write");
        check(snapGenericRom.volteHealthy, "Generic MSM8974 ROM integration reports healthy when registered");

        // 7. Guarded shell scripts & legacy org.passport.volteims migration cleanup
        String deploySh = VolteManagerLogic.buildRootDeployScript(
                "/data/user/0/org.octolab.qcom32volte/files/staged",
                "/data/user/0/org.octolab.qcom32volte/files/bk");
        check(deploySh.contains("--inspect-state"), "Deploy captures inspect-state backup first");
        check(deploySh.contains("--provision-stage6a"), "Deploy invokes stage6a");
        check(deploySh.contains("Qcom32VolteIms.apk"), "Deploy installs Qcom32VolteIms.apk");
        check(deploySh.contains("org.octolab.qcom32ims/.VolteAdminReceiver"), "Deploy invokes new admin receiver");
        check(deploySh.contains("--ez reboot_required true"), "Deploy marks reboot_required notification");
        check(deploySh.contains("pm uninstall org.passport.volteims"), "Deploy migrates legacy package cleanly");
        check(deploySh.contains("  wolverine) ;;") && !deploySh.contains("wolverine|oslo"), "Deploy shell enforces wolverine guard");
        check(deploySh.indexOf("ERR_SIM_NOT_LOADED") < deploySh.indexOf("--provision-stage6a"),
                "Deploy checks SIM LOADED before touching EFS");
        check(deploySh.indexOf("ERR_LEGACY_IMS_DAEMON_CONFLICT") < deploySh.indexOf("--provision-stage6a"),
                "Deploy blocks conflicting imsqmidaemon before touching EFS");
        check(deploySh.indexOf("ERR_INCOMPATIBLE_PASSPORT_BASEBAND") < deploySh.indexOf("--provision-stage6a"),
                "Deploy blocks incompatible baseband before touching EFS");
        check(deploySh.indexOf("ERR_LEGACY_AAA_BASEBAND") < deploySh.indexOf("--provision-stage6a"),
                "Deploy blocks older AAA* engineering baseband before touching EFS");
        check(deploySh.contains("ERR_UNVERIFIED_FIRMWARE_BUILD_ID")
                        && deploySh.indexOf("ERR_UNVERIFIED_FIRMWARE_BUILD_ID") < deploySh.indexOf("--provision-stage6a"),
                "Deploy requires AAB982 build_id before touching EFS");

        String rollbackSh = VolteManagerLogic.buildRootRollbackScript("/data/user/0/org.octolab.qcom32volte/files/staged");
        check(rollbackSh.contains("wolverine|oslo|wichita|keian|wseries|windermere)"), "Rollback enforces Passport wseries EFS guard");
        check(!rollbackSh.contains("ERR_UNVERIFIED_FIRMWARE_BUILD_ID")
                && !rollbackSh.contains("ERR_LEGACY_IMS_DAEMON_CONFLICT"), "Rollback bypasses deploy preflight gates");
        if (Files.isExecutable(Paths.get("/bin/sh"))) {
            String goodBaseband = "MPSS.DI.4.0.1-00158-M8974AAAAANAZM-1";
            check(runShellGuard(deploySh, "wolverine", goodBaseband, "build_id:AAB982", "") == 0,
                    "Deploy shell accepts matching device and actual firmware guard");
            check(runShellGuard(deploySh, "wolverine", goodBaseband, "", "") == 25,
                    "Deploy shell rejects unknown firmware");
            check(runShellGuard(deploySh, "wolverine", goodBaseband, "build_id:AAB982", "running") == 23,
                    "Deploy shell rejects running legacy daemon");
            check(runShellGuard(deploySh, "wolverine", "MPSS.DI.2.0.1-AAAAANPZM", "build_id:AAB982", "") == 24,
                    "Deploy shell rejects bad baseband");
            check(runShellGuard(deploySh, "oslo", goodBaseband, "build_id:AAB982", "") == 21,
                    "Manager shell rejects unverified edition");
            check(runShellGuard(rollbackSh, "oslo", "bad", "build_id:AAA596", "running") == 0,
                    "Rollback shell allows experimental edition even with bad firmware and daemon");
            check(runShellGuard(rollbackSh, "bacon", goodBaseband, "build_id:AAB982", "") == 21,
                    "Rollback shell rejects non-Passport");
        }
        check(rollbackSh.indexOf("imsqmidaemon") < rollbackSh.indexOf("--rollback-all"),
                "Rollback stops conflicting legacy imsqmidaemon before EFS rollback");
        check(rollbackSh.contains("*override*.xml"), "Rollback deletes persistent CarrierConfig override XMLs directly");
        check(rollbackSh.contains("clear_override"), "Rollback clears CarrierConfig override");
        check(rollbackSh.contains("setprop persist.radio.fallbackmode ''"), "Rollback clears fallbackmode property");
        check(rollbackSh.contains("--rollback-all"), "Rollback invokes fixed EFS cleanup");
        check("'/data/local/tmp/probe'".equals(VolteManagerLogic.quoteSh("/data/local/tmp/probe")), "quoteSh public helper");

        // 7b. Baseband & legacy daemon compatibility helpers + post-reboot radio loss headline
        check(VolteManagerLogic.isPassportBasebandCompatible(
                "MPSS.DI.4.0.1-00158-M8974AAAAANAZM-1", "build_id:AAB982\nMP_QC_Release:MPSS.DI.4.0.1-00158"),
                "AAB982 DI.4.0.1-00158 baseband accepted");
        check(!VolteManagerLogic.isPassportBasebandCompatible("MPSS.DI.4.0.1-00158-M8974AAAAANAZM-1", ""),
                "Missing CONTENTS.TXT is rejected");
        check(!VolteManagerLogic.isPassportBasebandCompatible("MPSS.DI.4.0.1-AAAAANPZM", "build_id:AAB982"),
                "Partial or conflicting baseband tokens are rejected");
        check(!VolteManagerLogic.isPassportBasebandCompatible("MPSS.DI.2.0.1.c1.16-00029-M8974AAAAANPZM-1", ""),
                "BB10 DI.2.0.1 ANPZM baseband rejected");
        check(!VolteManagerLogic.isPassportBasebandCompatible("", ""), "Empty baseband rejected");
        check(!VolteManagerLogic.isPassportBasebandCompatible(
                "MPSS.DI.4.0.1-00158-M8974AAAAANAZM-1", "build_id:AAA596\nMP_QC_Release:MPSS.DI.4.0.1-00158"),
                "Older engineering AAA596 modem rejected");

        check(VolteManagerLogic.hasConflictingLegacyImsDaemon("imsqmidaemon=running, imsdatadaemon=stopped"),
                "Running imsqmidaemon flagged as conflict");
        check(VolteManagerLogic.hasConflictingLegacyImsDaemon("sys.ims.QMI_DAEMON_STATUS=1"),
                "Active QMI_DAEMON_STATUS=1 flagged as conflict");
        check(!VolteManagerLogic.hasConflictingLegacyImsDaemon("imsdatadaemon=stopped")
                && !VolteManagerLogic.hasConflictingLegacyImsDaemon(""),
                "Stopped or absent legacy services do not false-positive");

        VolteManagerLogic.StatusSnapshot snapNoSignal = VolteManagerLogic.parseStatusSummary(
                "Passport", "wolverine", 30, true,
                "package=org.octolab.qcom32ims\nversion=2.8-qcom32-volte", "", "", "", "UNKNOWN", "", false);
        check(snapNoSignal.radioUnavailable && snapNoSignal.headlineZh.contains("无信号"),
                "Post-reboot empty sim_state flags radioUnavailable rollback guidance");
        VolteManagerLogic.StatusSnapshot snapExperimentalNoSignal = VolteManagerLogic.parseStatusSummary(
                "Passport", "oslo", 30, true,
                "package=org.octolab.qcom32ims\nversion=2.8-qcom32-volte", "", "", "", "UNKNOWN", "", false);
        check(snapExperimentalNoSignal.radioUnavailable && snapExperimentalNoSignal.headlineZh.contains("无信号"),
                "Experimental edition also displays radio loss recovery guidance");

        VolteManagerLogic.StatusSnapshot snapAirplane = VolteManagerLogic.parseStatusSummary(
                "Passport", "wolverine", 30, true,
                "package=org.octolab.qcom32ims\nversion=2.8-qcom32-volte", "", "", "", "UNKNOWN", "", true);
        check(!snapAirplane.radioUnavailable, "Airplane mode does not false-positive radioUnavailable");

        String ppConflictReport = VolteManagerLogic.buildReadOnlyPortingReport(
                "blackberry", "Passport", "wolverine", "msm8974", "armeabi-v7a", 30, "test-keys",
                "Permissive", true, true, true, true, Boolean.TRUE, false,
                ".DI.4.0.1-00158-M8974AAAAANAZM-1", "build_id:AAA596", "imsqmidaemon=running");
        check(ppConflictReport.contains("冲突拦截") && ppConflictReport.contains("AAB982"),
                "Passport read-only report flags imsqmidaemon conflict and non-AAB982 modem");

        // 8. 32-Bit Qualcomm catalog identification & read-only AI/GitHub porting report
        VolteManagerLogic.DeviceCatalogMatch osloCat = VolteManagerLogic.identifyDeviceCatalog(
                "blackberry", "Passport Silver Edition", "oslo", "msm8974", "armeabi-v7a", 30, "test-keys");
        check("EXPERIMENTAL_UNVERIFIED_EDITION".equals(osloCat.route) && osloCat.prebuiltApkCompatible,
                "Silver Edition oslo catalog route experimental");

        VolteManagerLogic.DeviceCatalogMatch s5Cat = VolteManagerLogic.identifyDeviceCatalog(
                "samsung", "SM-G900F", "klte", "msm8974", "armeabi-v7a", 30, "release-keys");
        check("CANDIDATE_MSM8974_PORTING".equals(s5Cat.route)
                && "samsung-galaxy-s5-klte".equals(s5Cat.familyId)
                && !s5Cat.prebuiltApkCompatible,
                "Galaxy S5 klte release-keys identified as candidate MSM8974");

        VolteManagerLogic.DeviceCatalogMatch mi4LteCat = VolteManagerLogic.identifyDeviceCatalog(
                "Xiaomi", "MI 4LTE", "cancro", "msm8974", "armeabi-v7a", 30, "test-keys");
        check("CANDIDATE_MSM8974_PORTING".equals(mi4LteCat.route) && "xiaomi-mi4-cancro".equals(mi4LteCat.familyId),
                "Mi 4LTE cancro identified as candidate MSM8974");

        VolteManagerLogic.DeviceCatalogMatch mi4wCat = VolteManagerLogic.identifyDeviceCatalog(
                "Xiaomi", "MI 4W", "cancro", "msm8974", "armeabi-v7a", 30, "test-keys");
        check("UNSUPPORTED_HARDWARE_VARIANT".equals(mi4wCat.route), "Mi 4W 3G hardware blocker detected");

        VolteManagerLogic.DeviceCatalogMatch exynosCat = VolteManagerLogic.identifyDeviceCatalog(
                "samsung", "SM-G900H", "k3gxx", "universal5422", "armeabi-v7a", 30, "test-keys");
        check("UNSUPPORTED_NON_QUALCOMM".equals(exynosCat.route), "Exynos SM-G900H blocker detected");

        VolteManagerLogic.DeviceCatalogMatch n5Cat = VolteManagerLogic.identifyDeviceCatalog(
                "google", "Nexus 5", "hammerhead", "msm8974", "armeabi-v7a", 30, "test-keys");
        check("MODEM_LACKS_IMS_ENGINE".equals(n5Cat.route), "Nexus 5 hammerhead modem blocker detected");

        VolteManagerLogic.DeviceCatalogMatch arm64Cat = VolteManagerLogic.identifyDeviceCatalog(
                "oneplus", "ONEPLUS A3000", "oneplus3", "msm8996", "arm64-v8a", 30, "test-keys");
        check("UNSUPPORTED_64BIT_PLATFORM".equals(arm64Cat.route), "64-bit ABI blocker detected");

        String zhReport = VolteManagerLogic.buildReadOnlyPortingReport(
                "samsung", "SM-G900F", "klte", "msm8974", "armeabi-v7a", 30, "test-keys",
                "Enforcing", true, true, true, true, Boolean.TRUE, false);
        check(zhReport.contains(VolteManagerLogic.GITHUB_REPO_URL), "ZH porting report links GitHub repo");
        check(zhReport.contains(".agents/skills/qcom32-volte/"), "ZH porting report cites built-in AI Skill");
        check(zhReport.contains("samsung-galaxy-s5-klte"), "ZH porting report includes matched family");

        String enReport = VolteManagerLogic.buildReadOnlyPortingReport(
                "OnePlus", "A0001", "bacon", "msm8974", "armeabi-v7a", 30, "test-keys",
                "Enforcing", false, false, false, true, null, true);
        check(enReport.contains(VolteManagerLogic.GITHUB_REPO_URL), "EN porting report links GitHub repo");
        check(enReport.contains("oneplus-one-bacon"), "EN porting report includes matched family");

        boolean rejectedBadQuote = false;
        try {
            VolteManagerLogic.buildRootUpgradeApkScript("/tmp/bad'quote");
        } catch (IllegalArgumentException expected) {
            rejectedBadQuote = true;
        }
        check(rejectedBadQuote, "Unsafe shell quote rejected");

        System.out.println("PASS VolteManagerLogicTest assertions=" + passed);
    }
}
