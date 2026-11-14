# Qcom32 VoLTE — Clean-Room 4G VoLTE & Android 11 `ImsService` Stack for 32-Bit Qualcomm Platforms (`qcom32-volte`)

**[简体中文 (`README.md`)](README.md)** · **English** · **[Official Website (`qcom32-volte.octoooo.com`)](https://qcom32-volte.octoooo.com/en/)** · **[Complete Setup Guide](https://qcom32-volte.octoooo.com/en/guide)** · **[Verified Devices (`devices/`)](devices/README.md)** · **[Releases](https://github.com/Octo-o-o-o/qcom32-volte/releases)**

[![License](https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0_%7C_Commercial-2e654d.svg)](LICENSE)
[![Commercial License](https://img.shields.io/badge/Commercial_Use-Paid_License_Required-984b3e.svg)](COMMERCIAL-LICENSE.md)
[![Platform](https://img.shields.io/badge/Platform-Qualcomm_32--bit_(MSM8974)-black.svg)](docs/guide/ARCHITECTURE.md)
[![OS](https://img.shields.io/badge/Android-11_(LineageOS_18.1)-2e654d.svg)](docs/guide/INSTALL_AND_ROLLBACK.md)
[![Verified](https://img.shields.io/badge/First_Verified_Profile-BlackBerry_Passport_(wolverine)-brightgreen.svg)](devices/blackberry-passport-wolverine/profile.json)

> **OctoLab Classic VoLTE Ecosystem (Choose by OS):**
> - **32-bit Qualcomm Android 11 (LineageOS 18.1) & BlackBerry Passport on Android 11**: Use **this repository (`qcom32-volte`)** ([`qcom32-volte.octoooo.com`](https://qcom32-volte.octoooo.com/en/)).
> - **BlackBerry Q10 / Classic Q20 / Passport / P’9983 running stock BlackBerry 10 (`OS 10.3.3.3216`)**: Visit our sister project **[`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)** ([`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/en/)).

Qualcomm ended official Snapdragon 800/801 (`MSM8974` and fellow 32-bit `armeabi-v7a` SoCs) support at Android 6.0, leaving Android 11 custom ROMs without any `ImsService` as 2G/3G networks shut down worldwide.

**`qcom32-volte`** provides a clean-room, on-device **native hardware 4G VoLTE (AMR-WB HD Voice)** stack built around two cleanly decoupled layers:
1. **Generic 32-Bit Android 11 Telephony, Auto System Notifications & QMI Layer (`Qcom32VolteIms.apk` v2.6, `Qcom32VolteManager.apk` v1.2.2, `libimsa_probe.so`)** — reusable across 32-bit Qualcomm ROMs exposing `/dev/socket/qmux_radio/rild_ims0` under full **`SELinux Enforcing`** compliance (zero `setenforce 0`, zero custom sepolicy patches, strict `<protected-broadcast>` action whitelist), complete with automatic Android 11 system notifications on boot, SIM swap, and pending reboot (`VoLTE Active & Ready`, `SIM Swap Detected — Reboot to Activate`, `Not on 4G LTE`, or `Reboot Required`), plus automatic `wolverine` `libril.so` `persist.radio.fallbackmode=9` alignment for China Telecom EPS-only LTE stability (restoring stock settings on SIM swap or rollback);
2. **Profile-Guarded Modem EFS Provisioning (`devices/*/profile.json` + `pp_regconfig_provision_v6`)** — strictly gating `/dev/diag` baseband writes to verified device profiles while supporting cross-unit / multi-carrier dynamic `/pdp_profiles/profile3` (`qdp_profile`) states across all SIMs, with **BlackBerry Passport (`blackberry-passport-wolverine`)** live-verified end to end across all 4 delivery workflows.

---

## Four Ways to Deploy (`v1.2.2-r1`)

All release artifacts are served from both **Cloudflare R2** (`qcom32-downloads.octoooo.com`) and **GitHub Releases** with identical SHA-256 checksums ([`SHA256SUMS.txt`](https://qcom32-downloads.octoooo.com/v1.2.2-r1/SHA256SUMS.txt)):

| Method | Best For | Downloads (Cloudflare R2 / GitHub) | Summary |
| --- | --- | --- | --- |
| **1. Windows Desktop EXE (Recommended)** | Most users (**no Magisk**, no Python/ADB setup) | [R2 EXE](https://qcom32-downloads.octoooo.com/v1.2.2-r1/qcom32-volte-1.2.2-r1-windows-x64.exe) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.2-r1/qcom32-volte-1.2.2-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.2-r1/qcom32-volte-1.2.2-r1-windows-x64.exe.sha256) | Enable USB Debugging + Rooted Debugging, launch EXE, click *Backup & Activate VoLTE* |
| **2. Android Companion App** | Zero-`su` unplugged status dashboard or **Magisk** (`su`) on-device provisioning | [R2 APK](https://qcom32-downloads.octoooo.com/v1.2.2-r1/Qcom32VolteManager-1.2.2-r1.apk) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.2-r1/Qcom32VolteManager-1.2.2-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.2-r1/Qcom32VolteManager-1.2.2-r1.apk.sha256) | View live carrier/`rild_ims0`/`QMI_IMSA` status anytime without root; activate/rollback with Magisk |
| **3. Cross-Platform Portable ZIP** | macOS / Linux / CLI & Tkinter GUI users | [R2 ZIP](https://qcom32-downloads.octoooo.com/v1.2.2-r1/qcom32-volte-1.2.2-r1-portable.zip) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.2-r1/qcom32-volte-1.2.2-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.2-r1/qcom32-volte-1.2.2-r1-portable.zip.sha256) | Includes prebuilt ARM32 tools, signed APKs, and `devices/`; run `python3 cli/volte_ctl.py deploy` |
| **4. LineageOS 18.1 ROM Kit** | Custom ROM maintainers & fellow `MSM8974` porters | [`rom-integration/`](rom-integration/README.md) · [Platform APK](https://qcom32-downloads.octoooo.com/v1.2.2-r1/Qcom32VolteIms-2.6-r1-platform.apk) · [Source ZIP](https://qcom32-downloads.octoooo.com/v1.2.2-r1/qcom32-volte-1.2.2-r1-source.zip) | Bake `Qcom32VolteIms` into `/system_ext/priv-app/` via `Android.bp` + `device-qcom32-volte.mk` |

---

## Key Differences vs `ikimi615/qualcomm-volte-enabler`

See **[`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md)** for the full file-by-file and live-hardware audit:

1. **SoC & Framework Gap**: Upstream targets BlackBerry KEY2 (`SDM660` ARM64, stock Android 8.1 with Qualcomm’s proprietary `ims.apk` preinstalled); 32-bit `MSM8974` on LineageOS 18.1 has **zero** `ImsService`.
2. **`/dev/diag` Kernel Contract**: Upstream’s prebuilt 64-bit `efs_Write` cannot execute on ARM32, its pointer `ioctl` triggers `EINVAL (-22)` on `MSM8974`, and `MEMORY_DEVICE_MODE` blocks EFS operations. Our clean-room 32-bit C `pp_regconfig_provision_v6` (559 unit assertions) implements scalar `ioctl`, `sequence16+errno32` headers, CCITT CRC-16, and cross-carrier dynamic `profile3` structural checks.
3. **54-Item Stage 6A vs Single Byte**: Writing `IMS_enable=01` alone on Passport fails live with `CAUSE_CODE 54 (READ_FAILED)`. Decompressing 11.7MB of Q6ZIP Hexagon code revealed the exact Stage 6A set (405B `qp_ims_reg_config` with dynamic PCO `mode=0` + 789B `IPV4V6` `profile4` + keeping `ims_hybrid_enable` absent for standalone DCM).
4. **Generic Android 11 Call Stack, Auto Status Notifications & Caller ID**: Upstream contains zero Android code; we built `Qcom32VolteIms.apk` v2.6 (`UID 1001`, strict `SELinux Enforcing` compliance, zero manifest permissions, persistent `CarrierConfig`, `rild_ims0` protobuf engine, 3GPP `CLIP→OIR` + `TOA=145` Caller ID fix, automatic boot/SIM-swap/reboot notifications, and zero-PII read-only `VolteStatusProvider`).
5. **No Magisk Needed + Verified Rollback**: Works directly over LineageOS Rooted Debugging, backs up original EFS state first, auto-binds newly swapped SIM cards on boot, and provides `--rollback-all`.

---

## Onboarding Fellow Snapdragon 800 / 801 (`MSM8974`) Phones & Built-in AI Skill

Using OnePlus One (`bacon`), Xiaomi Mi 4 / Note (`cancro`), Sony Xperia Z1/Z2/Z3, Samsung Galaxy S5 (`klte`), LG G2/G3, HTC One M8, Moto X 2014, or Nexus 6 on Android 10/11?
- **Human Porting Guides**: Read **[`devices/README.md`](devices/README.md)** and **[`docs/guide/MSM8974_PORTING_GUIDE.md`](docs/guide/MSM8974_PORTING_GUIDE.md)** for reusing `Qcom32VolteIms.apk` and onboarding a verified device profile.
- **Built-in Multi-Agent AI Skill ([`.agents/skills/qcom32-volte/`](.agents/skills/qcom32-volte/SKILL.md) & [`.claude/skills/qcom32-volte/`](.claude/skills/qcom32-volte/SKILL.md))**: Out-of-the-box workspace skill for **Antigravity, Claude Code, Codex, Cursor, and Gemini CLI**. AI assistants automatically inspect connected phones in read-only mode (`ai_device_helper.py inspect`), flag incompatible hardware sub-variants (`catalog`), run guarded deploy/upgrade/rollback on verified profiles, and decode 789-byte `/pdp_profiles/*` blobs (`decode-pdp`) during 4-Gate new-device porting without blind EFS writes.

---

## Build & Test from Source

```bash
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py self-test
cd website && npm test
```

## License & Commercial Authorization

Copyright (c) 2026 **OctoLab (`Octo-o-o-o`)**. Dual-licensed under:

1. **[PolyForm Noncommercial License 1.0.0](LICENSE) — FREE for Personal & Non-Commercial Use**: Individuals activating their own phones, academic researchers, hobbyist porters, and maintainers of **100% free, non-paywalled community ROMs** (retaining attribution and these non-commercial terms) may use, modify, and redistribute this project free of charge.
2. **[OctoLab Commercial License](COMMERCIAL-LICENSE.md) — Paid Authorization Required for Any Commercial Use**: Prior written paid authorization is required before any commercial use—including paid flashing/remote activation or repair-shop services, selling refurbished/secondhand phones or motherboards pre-flashed with this stack (including "free VoLTE flashing bundled with phone purchase"), paywalled ROMs, or commercial flashing toolkits. Commercial inquiries: [`octoooo@octoooo.com`](mailto:octoooo@octoooo.com).

Contains zero proprietary firmware blobs, signing keys, or device PII. Not certified for emergency calling (911/112/110).
