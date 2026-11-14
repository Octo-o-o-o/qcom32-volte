# 4-Gate Porting & Modem EFS Safety Runbook (`PORTING_AND_EFS_SAFETY.md`)

Use this reference whenever `ai_device_helper.py inspect` returns `route: "CANDIDATE_MSM8974_PORTING"` (a 32-bit Qualcomm device on Android 11 / LineageOS 18.1 that does not yet have a `"status": "verified"` profile under `devices/*/profile.json`).

---

## Non-Negotiable Safety Boundaries

1. **Never blind-write Passport's `/pdp_profiles/profile4`** onto another phone model. On Samsung `klte`, Xiaomi `cancro`, OnePlus `bacon`, Sony `leo`, or LG `d855`, `profile4` may hold MMS, tethering, or carrier data!
2. **Never copy foreign carrier P-CSCF IPs, IMSIs, ICCIDs, or another phone's `modemst1`/`modemst2`**. Always use `qp_ims_reg_config` **`mode=0`** (`PP_V6_QP_IMS_REG_CONFIG_MODE0`, 405 bytes, zero hardcoded IP) so the Hexagon baseband derives the home IMS domain dynamically from the inserted USIM and requests P-CSCF via LTE PCO.
3. **Keep `/nv/item_files/ims/ims_hybrid_enable` absent (`ENOENT`)** so the `MSM8974` Hexagon modem uses **Standalone Modem DCM** (`*(uint8_t*)(dpl_ctx + 0xc19) = 0`) rather than waiting for missing Android 6.0 `imsdatadaemon` user-space sockets.
4. **Keep `SELinux=Enforcing`**: `Qcom32VolteIms.apk` runs natively in `UID 1001` (`u:r:radio:s0`) with **zero `<uses-permission>`** declarations. Never run `setenforce 0`.
5. **Every EFS change must be backed up first and verified with full `--rollback-all` restoration** before a new `devices/<id>/profile.json` can be promoted from `"experimental"` to `"verified"`.

---

## Gate 1: Verify Modem Hexagon IMS Engine Presence (Read-Only)

With `USB debugging` + `Rooted debugging` (`adb root`, `UID 0`) enabled on LineageOS 18.1:

```bash
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py inspect
```

Check `gates.modem_has_ims_symbols` (or inspect manually via read-only `adb shell`):

```sh
grep -a -l 'qp_ims_reg_config' /firmware/image/modem* /vendor/firmware_mnt/image/modem*
grep -a -l 'qipcall' /firmware/image/modem* /vendor/firmware_mnt/image/modem*
```

- **PASS**: Both `qp_ims_reg_config` and `qipcall` exist in the device's factory baseband ELF segments -> Hardware & firmware contain the native Hexagon VoLTE engine.
- **STOP**: Neither symbol exists (e.g., stock Google Nexus 5 `hammerhead`) -> Baseband firmware was compiled without IMS; Android-side changes alone cannot enable VoLTE.

> Note: When `IMS_enable == 00`, attempting to allocate `QMI_IMSS` (`0x12`) returns `INVALID_SERVICE_TYPE (31)` (`alloc -2`). That is normal `IMS_enable=00` gating, **not** proof that the modem lacks IMS.

---

## Gate 2: Read-Only Audit of `/pdp_profiles/*` & DS Mapping

In Qualcomm `MSM8974` EFS2, each 3GPP PDP profile under `/pdp_profiles/profile<N>` is a **789-byte regular file (`S_IFREG`)** (`ds_umts_pdp_profile_type`):

| Byte Offset(s) | Field | Expected Value for Active VoLTE `ims` Slot |
| --- | --- | --- |
| `buf[0]` | `struct_version` | `0x15` |
| `buf[2..17]` | `profile_name` (ASCIIZ) | `"ims"` |
| `buf[34]` | `context_valid_flag` | `1` |
| `buf[35]` | `profile_number` | `<N>` (matching `/pdp_profiles/profile<N>`) |
| `buf[36]` | `pdp_type` | `3` (`IPV4V6`, recommended) or `2` (`IPV6`) |
| `buf[61..164]` | `apn_name` (ASCIIZ) | `"ims"` |
| `buf[750]` | `im_cn_flag` (TLV `0x21`) | `1` (`0` on ordinary internet/MMS profiles) |
| `buf[751]` | `request_pcscf_address_flag` (TLV `0x2e`) | `1` (`0` on ordinary internet/MMS profiles) |
| `buf[752]` | `dhcp_pcscf_flag` (TLV `0x20`) | `0` |
| `buf[753]` | `apn_disabled_flag` (TLV `0x33`) | `0` (Factory Passport had `1` disabled!) |

### Procedure for a New Device
1. Use a read-only DIAG EFS2 `OPEN` (`0x4b 0x13 0x02`) + `READ` (`0x4b 0x13 0x04`) tool (built from `src/modem-efs-provisioner/regfile_diag_codec.c` + `wide_diag_codec.c`) to pull **all existing** `/pdp_profiles/profile1` .. `profile16` and `/pdp_profiles/embedded_call_prof_nums` into a host backup folder (`private/<device>-efs-readonly-backup/`).
2. Decode every pulled 789-byte profile using the skill helper:
   ```bash
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py decode-pdp private/<device>-efs-readonly-backup/profile4.bin
   ```
3. Identify the target IMS PDP slot safely:
   - **Case A**: An existing `/pdp_profiles/profile<K>` already has `apn_name == "ims"` (or `profile_name == "ims"`), e.g., disabled (`b753=1`) or `IPV4`-only (`b36=0`, `b750=0`, `b751=0`) -> Target `profile<K>` (and preserve exact original `profile<K>` bytes for `--rollback-all`!).
   - **Case B**: No existing profile is `ims` -> Allocate the lowest unused `/pdp_profiles/profile<K>` (`ENOENT` on baseline -> `UNLINK` on `--rollback-all`), ensuring `buf[35] = K`.

---

## Gate 3: Verify `rild_ims0` & Align Android 11 `platform` Key

1. **Check Vendor RIL Socket**:
   `inspect` checks `ls -lZ /dev/socket/qmux_radio/rild_ims0`. As long as Qualcomm's 32-bit `libril-qc-qmi-1.so` exposes `rild_ims0`, `Qcom32VolteIms.apk` (`src/android-ims-service/`) works **without Java protocol changes**.
2. **Align `sharedUserId="android.uid.phone"` Signing Certificate**:
   - **Community / Unofficial ROMs (`ro.build.tags=test-keys`)**: Prebuilt `Qcom32VolteIms.apk` is signed with the standard AOSP `platform` test key and installs directly via `adb install -r`.
   - **Official LineageOS 18.1 / Custom-Signed ROMs (`release-keys` / `dev-keys`)**: `adb install` of an AOSP-signed APK will return `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` because `com.android.phone` is signed with the ROM builder's private `platform` key. Use one of:
     - **Option 3A (User has ROM keys)**: Rebuild with matching keys:
       ```bash
       python3 cli/build_all.py --sign-key /path/to/platform.pk8 --sign-cert /path/to/platform.x509.pem
       ```
     - **Option 3B (ROM Source Integration — cleanest for maintainers)**: Copy into `packages/services/Qcom32VolteIms/` per [`rom-integration/README.md`](../../../../rom-integration/README.md).
     - **Option 3C (Re-sign ROM ZIP consistently)**: Re-sign the user's LineageOS 18.1 ZIP with a local keyset (and run LineageOS key-migration script once in Recovery) so `com.android.phone` and `Qcom32VolteIms.apk` share the exact same `platform` certificate.

---

## Gate 4: Audio Routing (`Voice-MMode1`), Provisioner Build & Acceptance

1. **Check `mixer_paths.xml` & `audio_platform_info.xml`**:
   - On `MSM8974`, Hexagon VoLTE voice uses `vsid=0x10c02000` (`Voice2` / `Voice-MMode1`).
   - If IMS registers (`mImsRegistered=true`, `reg_status=2`, `voip=2`) and calls ring/connect **with silent audio**, add/enable the `Voice-MMode1` <-> `handset`/`speaker`/`voice-handset` mixer path in `/vendor/etc/mixer_paths.xml` (comparing against CAF `msm8974` reference mixer tables).
2. **Scaffold & Validate `devices/<vendor>-<codename>/profile.json`**:
   ```bash
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py scaffold-profile \
     --profile-id samsung-s5-klte \
     --device klte \
     --model-contains SM-G900 \
     --display-name "Samsung Galaxy S5 (LineageOS 18.1 / Android 11)" \
     --ims-pdp-path /pdp_profiles/profile<K> \
     --out devices/samsung-s5-klte/profile.json
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py validate-profile
   ```
3. **Full Acceptance Before Promoting `"status": "experimental"` -> `"verified"`**:
   - Host unit tests pass (`python3 cli/build_all.py --host-tests-only` and `python3 cli/volte_ctl.py self-test`);
   - Guarded `--rehearse-stage6` & `--rollback-all` verified bit-exact restoration of baseline EFS;
   - Post-reboot `python3 cli/volte_ctl.py status` reports `"volte_stack_healthy": true` (`pdp_connected=1`, `reg_status=2`, `voip=2`, `voip_rat=1`, `mImsRegistered=true`, `SELinux=Enforcing`);
   - **Human user confirms live phone calls**: outgoing call, incoming call with normal Caller ID, bidirectional AMR-WB voice audio, LTE data retained during active call, and hands-free recovery after normal reboot with USB unplugged.
