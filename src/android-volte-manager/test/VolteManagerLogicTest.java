package org.passport.voltemanager;

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
        // 1. Hardware identity guard
        check(VolteManagerLogic.verifyPassportIdentity("Passport", "wolverine", 30), "Passport wolverine API 30 accepted");
        check(!VolteManagerLogic.verifyPassportIdentity("Nexus 5", "hammerhead", 30), "Non-Passport rejected");
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

        // 4. Healthy China Unicom status snapshot
        VolteManagerLogic.StatusSnapshot snapOk = VolteManagerLogic.parseStatusSummary(
                "Passport",
                "wolverine",
                30,
                true,
                "PASSPORT_VOLTE_IMS_V1\nversion=2.3-passport-volte\nrilConnected=true\npublishedFrameworkReg=1",
                "pdp_connected=1 reg_status=2 legacy_reg=1 voip=2 voip_rat=1",
                "mImsRegistered=true",
                "46001",
                "LTE");
        check(snapOk.volteHealthy, "Healthy snapshot detected");
        check("2.3-passport-volte".equals(snapOk.imsServiceVersion), "Service version parsed");
        check(snapOk.carrierSummary.contains("China Unicom"), "China Unicom label formatted");

        // 5. No-su snapshot
        VolteManagerLogic.StatusSnapshot snapNoSu = VolteManagerLogic.parseStatusSummary(
                "Passport", "wolverine", 30, false, "", "", "", "46011", "LTE");
        check(!snapNoSu.volteHealthy, "No-su without service is not healthy");
        check(snapNoSu.carrierSummary.contains("China Telecom"), "China Telecom label formatted");

        // 6. Guarded shell scripts
        String deploySh = VolteManagerLogic.buildRootDeployScript("/data/user/0/org.passport.voltemanager/files/staged", "/data/user/0/org.passport.voltemanager/files/bk");
        check(deploySh.contains("--inspect-state"), "Deploy captures inspect-state backup first");
        check(deploySh.contains("--provision-stage6a"), "Deploy invokes stage6a");
        check(!deploySh.contains("--backup-dir"), "Deploy uses exact v6 CLI flags");
        check(deploySh.contains("apply_override --include-stopped-packages"), "Deploy clears FLAG_STOPPED");
        check(deploySh.contains("if [ \"$DEV\" != \"wolverine\" ]"), "Deploy enforces wolverine hardware guard");

        String rollbackSh = VolteManagerLogic.buildRootRollbackScript("/data/user/0/org.passport.voltemanager/files/staged");
        check(rollbackSh.contains("clear_override"), "Rollback clears CarrierConfig override");
        check(rollbackSh.contains("--rollback-all"), "Rollback restores all Stage 6A EFS items");

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
