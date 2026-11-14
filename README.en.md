# BlackBerry Passport Android 11 Native VoLTE (`passport-android11-volte`)

**[简体中文 (`README.md`)](README.md)** · **English** · **[Official Website (`passport-volte.octoooo.com`)](https://passport-volte.octoooo.com/en/)** · **[Complete Setup Guide](https://passport-volte.octoooo.com/en/guide)** · **[Releases](https://github.com/Octo-o-o-o/passport-android11-volte/releases)**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Device](https://img.shields.io/badge/Device-BlackBerry_Passport_(wolverine)-black.svg)](docs/guide/ARCHITECTURE.md)
[![OS](https://img.shields.io/badge/Android-11_(LineageOS_18.1)-2e654d.svg)](docs/guide/INSTALL_AND_ROLLBACK.md)
[![VoLTE](https://img.shields.io/badge/VoLTE-Verified_Working_(AMR--WB_HD)-brightgreen.svg)](https://passport-volte.octoooo.com/en/)

> **OctoLab BlackBerry VoLTE Ecosystem (Choose by OS):**
> - **BlackBerry Passport flashed to Android 11 (LineageOS 18.1)**: Use **this repository** ([`passport-volte.octoooo.com`](https://passport-volte.octoooo.com/en/)).
> - **BlackBerry Q10 / Classic Q20 / Passport / P’9983 running stock BlackBerry 10 (`OS 10.3.3.3216`)**: Visit our sister open-source project **[`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)** ([`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/en/)).

Standalone, on-device **native hardware 4G VoLTE (AMR-WB HD Voice)** stack for **BlackBerry Passport (`MSM8974` / `wolverine`)** running **LineageOS 18.1 (Android 11, API 30)**.

Qualcomm ended official Snapdragon 801 (`MSM8974`) support at Android 6.0, leaving LineageOS 18.1 without any `ImsService` while Passport’s factory modem EFS lacked `qp_ims_reg_config` and disabled `IPV4V6` on `/pdp_profiles/profile4`. This project bridges both layers cleanly—**no Magisk required, no kernel/modem partition reflashing, `SELinux=Enforcing` preserved, stock Dialer with working Caller ID, hands-free boot recovery, and one-command factory modem rollback**.

---

## Four Ways to Deploy (`v1.0.0-r1`)

All release artifacts are served from both **Cloudflare R2** (`passport-downloads.octoooo.com`) and **GitHub Releases** with identical SHA-256 checksums ([`SHA256SUMS.txt`](https://passport-downloads.octoooo.com/v1.0.0-r1/SHA256SUMS.txt)):

| Method | Best For | Downloads (Cloudflare R2 / GitHub) | Summary |
| --- | --- | --- | --- |
| **1. Windows Desktop EXE (Recommended)** | Most users (**no Magisk**, no Python/ADB setup) | [R2 EXE](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-windows-x64.exe) · [GitHub](https://github.com/Octo-o-o-o/passport-android11-volte/releases/download/v1.0.0-r1/passport-android11-volte-1.0.0-r1-windows-x64.exe) · [SHA-256](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-windows-x64.exe.sha256) | Enable USB Debugging + Rooted Debugging, launch EXE, click *Backup & Activate VoLTE* |
| **2. Android Companion App** | Users with **Magisk** (`su`) or on-device read-only status inspection | [R2 APK](https://passport-downloads.octoooo.com/v1.0.0-r1/PassportVolteManager-1.0.0-r1.apk) · [GitHub](https://github.com/Octo-o-o-o/passport-android11-volte/releases/download/v1.0.0-r1/PassportVolteManager-1.0.0-r1.apk) · [SHA-256](https://passport-downloads.octoooo.com/v1.0.0-r1/PassportVolteManager-1.0.0-r1.apk.sha256) | View live IMS/`QMI_IMSA` status anytime; activate/rollback on-device with Magisk |
| **3. Cross-Platform Portable ZIP** | macOS / Linux / CLI & Tkinter GUI users | [R2 ZIP](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-portable.zip) · [GitHub](https://github.com/Octo-o-o-o/passport-android11-volte/releases/download/v1.0.0-r1/passport-android11-volte-1.0.0-r1-portable.zip) · [SHA-256](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-portable.zip.sha256) | Includes prebuilt ARM32 tools & signed APKs; run `python3 cli/volte_ctl.py deploy` |
| **4. LineageOS 18.1 ROM Kit** | Custom ROM maintainers & fellow `MSM8974` porters | [`rom-integration/`](rom-integration/README.md) · [Platform APK](https://passport-downloads.octoooo.com/v1.0.0-r1/PassportVolteIms-2.3-r1-platform.apk) · [Source ZIP](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-source.zip) | Bake `PassportVolteIms` into `/system/priv-app/` via `Android.bp` + `device.mk` |

---

## Key Differences vs `ikimi615/qualcomm-volte-enabler`

See **[`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md)** for the full file-by-file and live-hardware audit:

1. **SoC & Framework Gap**: Upstream targets BlackBerry KEY2 (`SDM660` ARM64, stock Android 8.1 with Qualcomm’s modern `ims.apk` preinstalled); Passport is 32-bit `MSM8974` on LineageOS 18.1 with **zero** `ImsService`.
2. **`/dev/diag` Kernel Contract**: Upstream’s prebuilt 64-bit `efs_Write` cannot execute on ARM32, its pointer `ioctl` triggers `EINVAL (-22)` on `MSM8974`, and `MEMORY_DEVICE_MODE` blocks EFS operations. Our open-source 32-bit C `pp_regconfig_provision_v6` (549 unit assertions) implements scalar `ioctl`, `sequence16+errno32` headers, and CCITT CRC-16.
3. **54-Item Stage 6A vs Single Byte**: Writing `IMS_enable=01` alone on Passport fails live with `CAUSE_CODE 54 (READ_FAILED)`. Decompressing 11.7MB of Q6ZIP Hexagon code revealed the exact Stage 6A set (405B `qp_ims_reg_config` with dynamic PCO `mode=0` + 789B `IPV4V6` `profile4` + keeping `ims_hybrid_enable` absent for standalone DCM).
4. **Full Android 11 Call Stack & Caller ID**: Upstream contains zero Android code; we built `PassportVolteIms.apk` v2.3 (`UID 1001`, zero manifest permissions, persistent `CarrierConfig`, `rild_ims0` protobuf engine, and 3GPP `CLIP→OIR` + `TOA=145` Caller ID fix).
5. **No Magisk Needed + Verified Rollback**: Works directly over LineageOS Rooted Debugging, backs up original EFS state first, and provides `--rollback-all`.

---

## Porting to Fellow Snapdragon 801 (`MSM8974`) Phones

Using OnePlus One (`bacon`), Sony Xperia Z2/Z3, Samsung Galaxy S5 (`klte`), Xiaomi Mi 4 / Note (`cancro`), LG G3, or HTC One M8 on Android 10/11? Read **[`docs/guide/MSM8974_PORTING_GUIDE.md`](docs/guide/MSM8974_PORTING_GUIDE.md)** for what reuses directly vs what requires device-specific RIL/profile adaptation.

---

## Build & Test from Source

```bash
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test
cd website && npm test
```

## License & Disclaimer

Published under **Apache License 2.0**. Contains zero proprietary firmware blobs, signing keys, or device PII. Not certified for emergency calling (911/112/110).
