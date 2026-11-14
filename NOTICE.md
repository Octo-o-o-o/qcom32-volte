# 第三方材料与许可声明 / Licensing & Third-Party Notices

Original C, Java, Python, HTML/CSS/JS source code, unit tests, device EFS profile schemas, and engineering documentation in this repository are Copyright (c) 2026 **OctoLab (Octo-o-o-o)** and are dual-licensed under:

1. **PolyForm Noncommercial License 1.0.0** (`LICENSE`) — free for personal own-device use, academic research, hobbyist porting, and 100% free noncommercial community ROM distributions; OR
2. **OctoLab Commercial License** (`COMMERCIAL-LICENSE.md`) — required prior to any commercial use (including paid flashing/remote activation services, selling phones or boards pre-flashed with this stack, paid ROMs, or commercial toolkits; contact: `octoooo@octoooo.com`).

Required Notice: Copyright (c) 2026 OctoLab (Octo-o-o-o) (https://qcom32-volte.octoooo.com/ | https://github.com/Octo-o-o-o/qcom32-volte). Free for personal and noncommercial use under PolyForm Noncommercial License 1.0.0; any commercial use (including paid flashing/activation services or selling devices pre-flashed with this software) requires a prior paid commercial license from OctoLab (octoooo@octoooo.com).

## 1. Upstream Reference & Research Acknowledgment / 上游参考致谢

- [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler) (inspected at commit `ec4c82ab0d4d2dd56f95009a04a2269fb80de534` and `aab0f56`): referenced for initial problem framing around Qualcomm DIAG/EFS2 `IMS_enable` access on BlackBerry Android devices. **Zero code, scripts, or prebuilt helper binaries (`qcom-efs-browser`) from that repository are copied or redistributed here.** All ARM32 DIAG/EFS2 codecs (`src/modem-efs-provisioner/`), QMI probes (`src/qmi-imsa-probe/`), and the generic 32-bit Qualcomm Android 11 `ImsService` stack (`src/android-ims-service/`) were written independently from scratch. See [`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md) for a full technical comparison.
- **LineageOS / BBAndroids (`msm8974` / `wseries`)**: kernel `diagchar` ioctl contracts and `BoardConfig.mk` ABI definitions were cross-checked against public `msm8974` / `wseries` open-source trees. No proprietary modem firmware (`modem.elf`), carrier `.mbn` files, or device-specific NV/EFS dumps are distributed in this repository.

## 2. Windows Standalone Executable Bundle / Windows 单文件桌面包第三方组件声明

The standalone Windows executable (`qcom32-volte-*-windows-x64.exe`) bundles the following runtime components for zero-dependency offline execution on Windows 10/11 x64:

1. **CPython & Tcl/Tk** — Python Software Foundation License v2 & Tcl/Tk License.
2. **PyInstaller Bootloader** — GPLv2 with the special PyInstaller Bootloader Exception permitting bundling and distribution alongside source-available noncommercial and commercial applications.
3. **Android SDK Platform-Tools (`adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`)** — Copyright The Android Open Source Project, licensed under the **Apache License, Version 2.0** (full upstream notice embedded in `THIRD-PARTY-NOTICES.txt`).
4. **First-Party Built Payloads (`pp_regconfig_provision_v6`, `libimsa_probe.so`, `Qcom32VolteIms.apk`, `Qcom32VolteManager.apk`, `devices/`)** — compiled deterministically from `src/` in this repository under `PolyForm-Noncommercial-1.0.0 OR Commercial`.

Full license texts and third-party attributions are embedded inside the Windows executable (`THIRD-PARTY-NOTICES.txt`) and viewable offline from the desktop application. No private signing keys, personal device backups, phone numbers, or IMSI/IMEI/ICCID logs are included in any public bundle.

## 3. Trademark Notice / 商标说明

Qualcomm, Snapdragon, BlackBerry, Passport, Q10, Classic, OnePlus, Sony Xperia, Samsung Galaxy, Xiaomi, LG, and HTC are trademarks of their respective owners. This is an independent source-available interoperability and hardware-preservation project and is not affiliated with or endorsed by Qualcomm Incorporated, BlackBerry Limited, or any device manufacturer.
