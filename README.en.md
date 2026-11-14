# Qcom32 VoLTE — Clean-Room 4G VoLTE & Android 11 `ImsService` Stack for 32-Bit Qualcomm Platforms (`qcom32-volte`)

**[简体中文 (`README.md`)](README.md)** · **English** · **[Official Website (`qcom32-volte.octoooo.com`)](https://qcom32-volte.octoooo.com/en/)** · **[Complete Setup Guide](https://qcom32-volte.octoooo.com/en/guide)** · **[Verified Devices (`devices/`)](devices/README.md)** · **[Releases](https://github.com/Octo-o-o-o/qcom32-volte/releases)**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Qualcomm_32--bit_(MSM8974)-black.svg)](docs/guide/ARCHITECTURE.md)
[![OS](https://img.shields.io/badge/Android-11_(LineageOS_18.1)-2e654d.svg)](docs/guide/INSTALL_AND_ROLLBACK.md)
[![Verified](https://img.shields.io/badge/First_Verified_Profile-BlackBerry_Passport_(wolverine)-brightgreen.svg)](devices/blackberry-passport-wolverine/profile.json)

> **OctoLab Classic VoLTE Ecosystem (Choose by OS):**
> - **32-bit Qualcomm Android 11 (LineageOS 18.1) & BlackBerry Passport on Android 11**: Use **this repository (`qcom32-volte`)** ([`qcom32-volte.octoooo.com`](https://qcom32-volte.octoooo.com/en/)).
> - **BlackBerry Q10 / Classic Q20 / Passport / P’9983 running stock BlackBerry 10 (`OS 10.3.3.3216`)**: Visit our sister open-source project **[`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)** ([`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/en/)).

Qualcomm ended official Snapdragon 800/801 (`MSM8974` and fellow 32-bit `armeabi-v7a` SoCs) support at Android 6.0, leaving Android 11 custom ROMs without any `ImsService` as 2G/3G networks shut down worldwide.

**`qcom32-volte`** provides a clean-room, on-device **native hardware 4G VoLTE (AMR-WB HD Voice)** stack built around two cleanly decoupled layers:
1. **Generic 32-Bit Android 11 Telephony & QMI Layer (`Qcom32VolteIms.apk` v2.4, `Qcom32VolteManager.apk` v1.1.0, `libimsa_probe.so`)** — reusable across 32-bit Qualcomm ROMs exposing `/dev/socket/qmux_radio/rild_ims0`;
2. **Profile-Guarded Modem EFS Provisioning (`devices/*/profile.json` + `pp_regconfig_provision_v6`)** — strictly gating `/dev/diag` baseband writes to verified device profiles, with **BlackBerry Passport (`blackberry-passport-wolverine`)** live-verified end to end across all 4 delivery workflows.

---

## Four Ways to Deploy (`v1.1.0-r1`)

All release artifacts are served from both **Cloudflare R2** (`qcom32-downloads.octoooo.com`) and **GitHub Releases** with identical SHA-256 checksums ([`SHA256SUMS.txt`](https://qcom32-downloads.octoooo.com/v1.1.0-r1/SHA256SUMS.txt)):

| Method | Best For | Downloads (Cloudflare R2 / GitHub) | Summary |
| --- | --- | --- | --- |
| **1. Windows Desktop EXE (Recommended)** | Most users (**no Magisk**, no Python/ADB setup) | [R2 EXE](https://qcom32-downloads.octoooo.com/v1.1.0-r1/qcom32-volte-1.1.0-r1-windows-x64.exe) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.1.0-r1/qcom32-volte-1.1.0-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.1.0-r1/qcom32-volte-1.1.0-r1-windows-x64.exe.sha256) | Enable USB Debugging + Rooted Debugging, launch EXE, click *Backup & Activate VoLTE* |
| **2. Android Companion App** | Zero-`su` unplugged status dashboard or **Magisk** (`su`) on-device provisioning | [R2 APK](https://qcom32-downloads.octoooo.com/v1.1.0-r1/Qcom32VolteManager-1.1.0-r1.apk) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.1.0-r1/Qcom32VolteManager-1.1.0-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.1.0-r1/Qcom32VolteManager-1.1.0-r1.apk.sha256) | View live carrier/`rild_ims0`/`QMI_IMSA` status anytime without root; activate/rollback with Magisk |
| **3. Cross-Platform Portable ZIP** | macOS / Linux / CLI & Tkinter GUI users | [R2 ZIP](https://qcom32-downloads.octoooo.com/v1.1.0-r1/qcom32-volte-1.1.0-r1-portable.zip) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.1.0-r1/qcom32-volte-1.1.0-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.1.0-r1/qcom32-volte-1.1.0-r1-portable.zip.sha256) | Includes prebuilt ARM32 tools, signed APKs, and `devices/`; run `python3 cli/volte_ctl.py deploy` |
| **4. LineageOS 18.1 ROM Kit** | Custom ROM maintainers & fellow `MSM8974` porters | [`rom-integration/`](rom-integration/README.md) · [Platform APK](https://qcom32-downloads.octoooo.com/v1.1.0-r1/Qcom32VolteIms-2.4-r1-platform.apk) · [Source ZIP](https://qcom32-downloads.octoooo.com/v1.1.0-r1/qcom32-volte-1.1.0-r1-source.zip) | Bake `Qcom32VolteIms` into `/system/priv-app/` via `Android.bp` + `device-qcom32-volte.mk` |

---

## Key Differences vs `ikimi615/qualcomm-volte-enabler`

See **[`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md)** for the full file-by-file and live-hardware audit:

1. **SoC & Framework Gap**: Upstream targets BlackBerry KEY2 (`SDM660` ARM64, stock Android 8.1 with Qualcomm’s proprietary `ims.apk` preinstalled); 32-bit `MSM8974` on LineageOS 18.1 has **zero** `ImsService`.
2. **`/dev/diag` Kernel Contract**: Upstream’s prebuilt 64-bit `efs_Write` cannot execute on ARM32, its pointer `ioctl` triggers `EINVAL (-22)` on `MSM8974`, and `MEMORY_DEVICE_MODE` blocks EFS operations. Our open-source 32-bit C `pp_regconfig_provision_v6` (549 unit assertions) implements scalar `ioctl`, `sequence16+errno32` headers, and CCITT CRC-16.
3. **54-Item Stage 6A vs Single Byte**: Writing `IMS_enable=01` alone on Passport fails live with `CAUSE_CODE 54 (READ_FAILED)`. Decompressing 11.7MB of Q6ZIP Hexagon code revealed the exact Stage 6A set (405B `qp_ims_reg_config` with dynamic PCO `mode=0` + 789B `IPV4V6` `profile4` + keeping `ims_hybrid_enable` absent for standalone DCM).
4. **Generic Android 11 Call Stack & Caller ID**: Upstream contains zero Android code; we built `Qcom32VolteIms.apk` v2.4 (`UID 1001`, zero manifest permissions, persistent `CarrierConfig`, `rild_ims0` protobuf engine, 3GPP `CLIP→OIR` + `TOA=145` Caller ID fix, and zero-PII read-only `VolteStatusProvider`).
5. **No Magisk Needed + Verified Rollback**: Works directly over LineageOS Rooted Debugging, backs up original EFS state first, auto-binds newly swapped SIM cards on boot, and provides `--rollback-all`.

---

## Onboarding Fellow Snapdragon 800 / 801 (`MSM8974`) Phones

Using OnePlus One (`bacon`), Xiaomi Mi 4 / Note (`cancro`), Sony Xperia Z1/Z2/Z3, Samsung Galaxy S5 (`klte`), LG G2/G3, or HTC One M8 on Android 10/11? Read **[`devices/README.md`](devices/README.md)** and **[`docs/guide/MSM8974_PORTING_GUIDE.md`](docs/guide/MSM8974_PORTING_GUIDE.md)** for reusing `Qcom32VolteIms.apk` and creating a verified device profile.

---

## Build & Test from Source

```bash
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test
cd website && npm test
```

## License & Disclaimer

Published under **Apache License 2.0**. Contains zero proprietary firmware blobs, signing keys, or device PII. Not certified for emergency calling (911/112/110).
