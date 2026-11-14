package org.octolab.qcom32volte;

import java.nio.charset.StandardCharsets;

public final class VolteManagerLogicTest {
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FAILED: " + msg);
        }
        passed++;
    }

    public static void main(String[] args) {
        // 1. Verified device profile matching
        check(VolteManagerLogic.verifyPassportIdentity("Passport", "wolverine", 30), "Passport wolverine API 30 verified");
        check(VolteManagerLogic.verifyPassportIdentity("BlackBerry Passport", "wolverine", 30), "BlackBerry Passport variant verified");
        check("blackberry-passport-wolverine".equals(
                VolteManagerLogic.matchVerifiedDeviceProfile("Passport", "wolverine", 30)), "Profile ID exact");
        check(!VolteManagerLogic.verifyPassportIdentity("OnePlus One", "bacon", 30), "Unverified profile gated from EFS write");
        check(!VolteManagerLogic.verifyPassportIdentity("Passport", "wolverine", 29), "Non-Android-11 rejected");

        // 2. SHA-256 check
        String abcHash = VolteManagerLogic.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
        check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(abcHash), "SHA-256 NIST vector");

        // 3. PII redaction
        String sample = "IMSI=460011234567890 num=18612345678 short=46001";
        String redacted = VolteManagerLogic.redactSensitiveDigits(sample);
        check(!redacted.contains("460011234567890"), "IMSI redacted");
        check(!redacted.contains("18612345678"), "11-digit phone number redacted");
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
        check(deploySh.contains("if [ \"$DEV\" != \"wolverine\" ]"), "Deploy enforces wolverine EFS guard");

        String rollbackSh = VolteManagerLogic.buildRootRollbackScript("/data/user/0/org.octolab.qcom32volte/files/staged");
        check(rollbackSh.contains("clear_override"), "Rollback clears CarrierConfig override");
        check(rollbackSh.contains("setprop persist.radio.fallbackmode ''"), "Rollback clears fallbackmode property");
        check(rollbackSh.contains("--rollback-all"), "Rollback restores all Stage 6A EFS items");
        check("'/data/local/tmp/probe'".equals(VolteManagerLogic.quoteSh("/data/local/tmp/probe")), "quoteSh public helper");

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
