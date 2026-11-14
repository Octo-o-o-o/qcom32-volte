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
| [`blackberry-passport-wolverine`](blackberry-passport-wolverine/profile.json) | BlackBerry Passport (`SQW100-1` / `wolverine`) | Snapdragon 801 (`MSM8974AC`) | LineageOS 18.1 (Android 11) | **Verified Working** (IMS Registration, MO/MT Calls, AMR-WB HD Audio, Caller ID, LTE Retention, Reboot Persistence, Full EFS Rollback) |

## 3. Adding a New 32-Bit Qualcomm Device Profile (e.g., OnePlus One `bacon`, Xperia Z2/Z3 `sirius`/`leo`, Galaxy S5 `klte`, Mi 4 `cancro`)

1. **Read-Only Inspection First**: Never write Passport's 789-byte `/pdp_profiles/profile4` to another device blindly. First inspect the target device's existing `/pdp_profiles/*`, `/nv/item_files/ims/*`, and `QMI_WDS` profile list in read-only mode (see [`docs/guide/MSM8974_PORTING_GUIDE.md`](../docs/guide/MSM8974_PORTING_GUIDE.md)).
2. **Verify `Qcom32VolteIms.apk` + Audio HAL**: Install `Qcom32VolteIms.apk` signed with your ROM's `platform` key and verify `rild_ims0` handshake + `voice-ims` mixer path in `mixer_paths.xml`.
3. **Register `<vendor>-<codename>/profile.json`**: Define exact `ro_product_device`, `android_sdk`, and verified EFS item payloads + rollback expectations before enabling automated EFS writes.
