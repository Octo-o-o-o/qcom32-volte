---
name: qcom32-volte
description: >-
  Identifies connected or user-named phones, checks compatibility, deploys or rolls back native
  4G VoLTE on one verified Passport wolverine and checks unverified Passport editions
  (oslo, wichita, keian, wseries, windermere) read-only, and guides safe
  4-gate VoLTE porting and LineageOS 18.1 preparation for candidate MSM8974/APQ8084 devices
  (Samsung Galaxy S5 `klte`, Xiaomi Mi 4 `cancro`, LG G3 `d855`, OnePlus One `bacon`, Sony Xperia
  Z1/Z2/Z3, HTC One M8, Moto X 2014, Nexus 6). Use whenever the user asks to check device support,
  flash/enable/debug/upgrade/rollback VoLTE, or adapt a new 32-bit Qualcomm phone
  （识别手机型号、刷机与激活高通32位老旗舰安卓11 VoLTE、移植新机型或回滚基带配置）.
---

# Qcom32 VoLTE — AI Device Routing, Deployment & Safe Porting Skill

This project is source-available (PolyForm Noncommercial 1.0.0; commercial license available). Only one SQW100-1 wolverine / China Unicom 46001 has complete call and four-method verification. China Telecom 46003/46011 has LTE attach and IMS registration only; calls are untested (EPS-only fix: 149 s without drops). China Mobile is untested; FDD B3/B8 is theoretical, and the device lacks TDD B38–41. SMS over IMS is not implemented. Emergency calls are unsupported and uncertified; keep another phone. VoWiFi, video, supplementary services and non-Chinese carriers are unsupported or untested.

EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证: oslo, wichita, keian, wseries and windermere default to read-only. CLI baseband writes require --experimental-unverified-edition plus typed confirmation or --i-understand-unverified-baseband-write. GUI and Android manager do not activate them; Passport rollback remains available. Non-Passport baseband writes are blocked. Published v1.2.3-r1 artifacts do not contain this gate.

Make an independent EFS backup and keep it off-device. Automatic inspection logs contain hashes, not restorable original bytes. Fixed rollback does not guarantee recovery of original values.

`qcom32-volte` splits legacy 32-bit Qualcomm (`MSM8974` / `APQ8084` / `MSM8926`) Android 11 VoLTE into two decoupled layers:
1. **Generic Android 11 IMS Stack (~85%)**: `Qcom32VolteIms.apk` (`org.octolab.qcom32ims`, `UID 1001`, `SELinux=Enforcing`, zero `<uses-permission>`) + `libimsa_probe.so` + `Qcom32VolteManager.apk` + 32-bit `/dev/diag` EFS2 codecs.
2. **Hardware-Guarded Modem EFS Profiles (~15%)**: `devices/<profile-id>/profile.json` + device-verified Stage 6A Hexagon DCM provisioner (`mode=0` USIM dynamic discovery, 789B `IPV4V6` `ims` PDP slot, deterministic `--rollback-all`).

---

## Mandatory Safety Rules (Never Violate)

- **Never blind-write Passport's EFS provisioner (`pp_regconfig_provision_v6`) onto another model**: `/pdp_profiles/profile4` is `ims` on BlackBerry Passport, but may be MMS or tethering on Samsung S5, Xiaomi Mi 4, LG G3, or OnePlus One. `cli/volte_ctl.py` enforces `devices/*/profile.json` hardware guards — **never bypass that guard without completing the 4-Gate read-only audit first**.
- **Never copy foreign carrier P-CSCF IPs, IMSIs, ICCIDs, or another unit's `modemst1`/`modemst2` partition**: Always use `qp_ims_reg_config` `mode=0` (USIM-derived) and preserve per-unit original EFS backups.
- **Never lower SELinux (`setenforce 0`) or wipe user data without explicit user consent**.
- **Never place phone calls via `adb` on the user's behalf**: Always ask the user to test outgoing/incoming calls and confirm two-way audio.
- **Redact PII**: Never paste raw 11–20 digit IMEI / IMSI / ICCID / phone numbers into public files or commits.

---

## Step 1: Identify the Device & Select the Exact Route

Always start by running the read-only helper script [`ai_device_helper.py`](./scripts/ai_device_helper.py):

```bash
# 1A. If a phone is plugged into USB (100% read-only ADB/Fastboot auto-detection):
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py inspect

# 1B. If the user is asking about a phone model before plugging it in (e.g. S5 / Mi 4 / LG G3 / OnePlus 1):
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py catalog --query "<model_or_codename>"
```

Follow the `decision.route` field returned by `inspect`:

---

## Step 2: Execute the Matched Workflow

### Route A — `ALREADY_ACTIVE_HEALTHY`
The phone matches a verified profile (e.g., `blackberry-passport-wolverine`) and VoLTE is already registered (`mImsRegistered=true`, `reg_status=2`, `voip=2`).
- **Do not re-write modem EFS**.
- For read-only diagnostics: `python3 cli/volte_ctl.py status`
- For zero-EFS Android-side APK upgrade: `python3 cli/volte_ctl.py upgrade-apk --reboot`
- To install the on-device status app (works without `su`/Magisk): `python3 cli/volte_ctl.py install-manager`

### Route B — `VERIFIED_PROFILE_READY` (or `NEEDS_SIM_OR_LTE` / `NEEDS_ROOTED_DEBUGGING`)
The phone matches the verified wolverine profile on Android 11; one SQW100-1 unit was tested:
1. Ensure an active 4G USIM is inserted (`gsm.sim.state=LOADED`, camped on `LTE`) and the phone has **Settings -> System -> Developer options -> USB debugging + Rooted debugging** enabled.
2. Independently back up EFS off-device, run offline self-tests, then execute Stage 6A EFS provisioning + APK install + reboot verification:
   ```bash
   python3 cli/volte_ctl.py self-test
   python3 cli/volte_ctl.py deploy
   python3 cli/volte_ctl.py install-manager
   ```
3. Verify JSON output shows `"volte_stack_healthy": true`, `"qmi_imsa": {"pdp_connected": 1, "reg_status": 2, "voip": 2, "voip_rat": 1}`, and `"framework_ims_registered": ["true"]`.
4. Tell the user they can now **turn off Rooted debugging**, unplug USB, and make an outgoing + incoming test call to confirm bidirectional AMR-WB HD voice, Caller ID, and LTE retention.

### Experimental Passport — EXPERIMENTAL_UNVERIFIED_EDITION
Inspect read-only by default. Do not recommend one-click deploy. Rollback remains available; CLI experimental EFS writes require both explicit opt-ins above.

### Route C — `NEEDS_ANDROID11_ROM_FIRST` / `IN_FASTBOOT_BOOTLOADER_MODE`
The phone is not yet running Android 11 (SDK 30):
1. Read [`SUPPORTED_DEVICES_MATRIX.md`](./references/SUPPORTED_DEVICES_MATRIX.md) to check sub-model blockers first:
   - **Stock BlackBerry 10 (`QNX 10.3.3`)**: Direct the user to sister project `Octo-o-o-o/bb10-volte` (`https://bb10-volte.octoooo.com/`).
   - **Hard Hardware Blockers**: Immediately warn the user if the unit is a 3G-only Xiaomi Mi 4 (`MI 4W` / `pisces`), Exynos Galaxy S5 (`SM-G900H` / `k3gxx`), permanently locked AT&T/Verizon bootloader (`SM-G900A`, `SM-G900V` CID 11), or stock Nexus 5 (`hammerhead`, modem lacks `qipcall`).
2. Guide the user through physical button entry (Fastboot / Heimdall-Odin Download Mode / Recovery), remind them to **back up `EFS` (`modemst1`/`modemst2`/`fsg`) in TWRP** before flashing LineageOS 18.1, then re-run `ai_device_helper.py inspect` once booted into Android 11.

### Route D — `CANDIDATE_MSM8974_PORTING` (Unverified 32-Bit Qualcomm Model on Android 11)
The phone is a compatible 32-bit Qualcomm device on Android 11 (such as Galaxy S5 `klte`, Xiaomi Mi 4 LTE `cancro`, LG G3 `d855`/`f400`, OnePlus One `bacon`, Xperia Z2/Z3 `sirius`/`leo`, HTC One M8 `m8`, Moto X 2014 `victara`, or Nexus 6 `shamu`) without a verified profile yet.

Read and follow **every step** in [`PORTING_AND_EFS_SAFETY.md`](./references/PORTING_AND_EFS_SAFETY.md):
1. **Gate 1 (Modem IMS Symbols)**: Confirm `gates.modem_has_ims_symbols == true` (`qp_ims_reg_config` + `qipcall` present in `/firmware/image/modem*`).
2. **Gate 2 (Read-Only PDP Profile Audit)**: Back up `EFS` in TWRP, read all `/pdp_profiles/profile*` in read-only mode, and decode each 789B blob with:
   ```bash
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py decode-pdp <profileN.bin>
   ```
   Never overwrite a `NON_IMS_DATA_OR_MMS_DO_NOT_OVERWRITE` slot!
3. **Gate 3 (`rild_ims0` & ROM `platform` Certificate)**: Confirm `gates.rild_ims0_socket_present == true`. Check `decision.rom_signature.prebuilt_apk_compatible`: if `false` (`release-keys` official LineageOS), either re-sign with `--sign-key`/`--sign-cert`, integrate via [`rom-integration/README.md`](../../../rom-integration/README.md), or re-sign the ROM ZIP consistently.
4. **Gate 4 (Provisioner + Audio HAL + Profile Validation)**: Verify `Voice-MMode1` / `voice-ims` in `mixer_paths.xml`, adapt the target PDP slot in `src/modem-efs-provisioner/`, scaffold and validate `devices/<id>/profile.json` (`status: "experimental"` first):
   ```bash
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py scaffold-profile ...
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py validate-profile
   python3 cli/build_all.py --host-tests-only
   python3 cli/volte_ctl.py self-test
   ```
   Only change `"status"` to `"verified"` after full `--rollback-all` verification AND user-confirmed live MO/MT voice calls.

### Route E — Factory Rollback
This fixed cleanup writes 00 and deletes designated EFS items; it does not restore original bytes from the inspection log. Independently back up EFS and verify recovery before modifying the phone:
```bash
python3 cli/volte_ctl.py rollback
```

---

## Quick Diagnostic Reference

| Symptom / Error | Root Cause & Fix |
| --- | --- |
| `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` | Target ROM's `com.android.phone` is signed with private `release-keys` instead of AOSP `test-keys`. Re-sign `Qcom32VolteIms.apk` with the ROM's `platform` key (`cli/build_all.py --sign-key ... --sign-cert ...`) or build in-tree via `rom-integration/`. |
| `QMI_IMSS` returns `INVALID_SERVICE_TYPE (31)` | Normal behavior while `IMS_enable == 00` before Stage 6A provisioning + reboot. |
| `CAUSE_CODE 54` (`READ_FAILED`) on `GET 0x26` | Only `IMS_enable=01` was flipped without Stage 6A's 405B `qp_ims_reg_config` and companion items. Use full Stage 6A provisioning, never single-byte `IMS_enable` scripts. |
| Registered (`voip=2`) & call rings, **no audio** | Device `mixer_paths.xml` / `audio_platform_info.xml` is missing `Voice-MMode1` (`vsid=0x10c02000`) routing. Inspect with Gate 4 in [`PORTING_AND_EFS_SAFETY.md`](./references/PORTING_AND_EFS_SAFETY.md). |
| China Telecom (`46003`/`46011`) drops LTE every 3s | Legacy `libril.so` `fallback_thread` oscillation on `EPS-only` (`voice_rat=0`) cards. `Qcom32VolteIms` v2.5+ handles `wolverine` automatically (`fallbackmode=9`); check vendor RIL logs on newly ported devices. |
| China Mobile (`46000`/`46002`/`46007`) stays on 2G | Check device RF band support (e.g., `SQW100-1` supports FDD B3/B8 only, not TDD B38–41). |
