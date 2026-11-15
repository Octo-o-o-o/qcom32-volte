# Supported & Target Device Profiles (`devices/`)

`qcom32-volte` separates the **SoC-generic 32-bit Qualcomm Android 11 IMS/VoLTE stack** (~85% of the codebase) from **device-specific modem EFS profile layouts** (~15%):

## 1. Shared Generic Stack (Reusable Across 32-Bit Qualcomm Platforms)

| Component | Path | Portability Across `MSM8974` / `APQ8084` / `MSM8926` |
| --- | --- | --- |
| **`Qcom32VolteIms.apk`** (`org.octolab.qcom32ims`) | `src/android-ims-service/` | **100% generic** across Android 11 (API 30) ROMs whose legacy 32-bit Qualcomm `rild` exposes `/dev/socket/qmux_radio/rild_ims0` (`UID 1001` protobuf wire format). Includes 3GPP `CLIP -> OIR` Caller ID normalization, `TOA=145` `+` prefixing, persistent 3-arg `CarrierConfigManager.overrideConfig`, and zero-PII `VolteStatusProvider`. |
| **`libimsa_probe.so`** | `src/qmi-imsa-probe/` | **100% generic** ARM32 PIE `QMI_IMSA` (0x21), `QMI_IMSS` (0x12), and `QMI_WDS` (0x01) live telemetry probe via `libqmi_cci.so`. |
| **32-bit Kernel `/dev/diag` & EFS2 Codec** | `src/modem-efs-provisioner/{wide_diag_codec.c,regfile_diag_codec.c}` | **Generic** across Linux 3.4 / 3.10 32-bit Qualcomm kernels using scalar-value `DIAG_IOCTL_SWITCH_LOGGING` and `sequence16 + errno32` EFS2 wire headers. |
| **Desktop & On-Device Activators** | `cli/` & `src/android-volte-manager/` | Dynamically reads `devices/*/profile.json` and enforces hardware `ro.product.device` guards before any modem EFS write. |

## 2. Device Profiles

| Profile ID | Device | SoC | OS | Status |
| --- | --- | --- | --- | --- |
| [`blackberry-passport-wolverine`](blackberry-passport-wolverine/profile.json) | BlackBerry Passport All Editions (`SQW100-1/2/3` `wolverine`, Silver Edition `SQW100-4` `oslo`, AT&T `wichita`, Porsche `keian`, `wseries`) | Snapdragon 801 (`MSM8974AA/AC`) | LineageOS 18.1 (Android 11) | **Verified & Fully Unlocked** (IMS Registration, MO/MT Calls, AMR-WB HD Audio, Caller ID, LTE Retention, CT `46011` Protection, Reboot Persistence, Full EFS Rollback) |

## 3. Adding a New 32-Bit Qualcomm Device Profile via Built-In Read-Only Check + AI Skill

Both the desktop GUI (`32-Bit Device Check & AI Porting`), CLI (`python3 cli/volte_ctl.py inspect-device`), and Android Companion App (`Qcom32VolteManager.apk`) include a **100% Read-Only 4-Gate Readiness Panel** for Samsung Galaxy S5 (`klte*`), Xiaomi Mi 4 / Note (`cancro`/`virgo`), OnePlus One (`bacon`), Sony Xperia Z1/Z2/Z3 (`honami`/`sirius`/`leo`/`aries`), LG G3/G2 (`d855`/`d802`), HTC One M8 (`m8`), Moto X 2014 / Nexus 6 (`victara`/`shamu`), and Note 3/4 Snapdragon:

1. **Read-Only Inspection First**: Never write Passport's 789-byte `/pdp_profiles/profile4` to another device blindly. Click **32-Bit Device Check & AI Porting** (or clone this repo in **Claude Code / Cursor / Codex / Gemini CLI / Antigravity**, which auto-loads [`.agents/skills/qcom32-volte/`](../.agents/skills/qcom32-volte/SKILL.md)) to decode `/pdp_profiles/*` safely in read-only mode (see [`docs/guide/MSM8974_PORTING_GUIDE.md`](../docs/guide/MSM8974_PORTING_GUIDE.md)).
2. **Verify `Qcom32VolteIms.apk` + Audio HAL**: Install `Qcom32VolteIms.apk` signed with your ROM's `platform` key and verify `rild_ims0` handshake + `voice-ims` mixer path in `mixer_paths.xml`.
3. **Register `<vendor>-<codename>/profile.json`**: Define exact `ro_product_device`, `android_sdk`, and verified EFS item payloads + rollback expectations before enabling automated EFS writes.
