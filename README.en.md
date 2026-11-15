# Qcom32 VoLTE — Android 11 calling stack for 32-bit Qualcomm

**Source-available (PolyForm Noncommercial 1.0.0; commercial license available)** · [中文](README.md) · [Devices](devices/README.md) · [Install and rollback](docs/guide/INSTALL_AND_ROLLBACK.md)

Qcom32 VoLTE provides an Android 11 IMS service and device-specific modem EFS tooling. One BlackBerry Passport **SQW100-1 / `wolverine`** unit was fully live-verified on the community LineageOS 18.1 `wseries` ROM, baseband DI.4.0.1 M8974AAAAANAZM, with China Unicom 46001. Retail Passports require eMMC hardware conversion and the community ROM first.

> ⚠️ **Emergency calls are not supported or certified. Keep another phone for emergencies.** On LTE-only networks where 2G/3G have shut down, this device may be unable to reach 110, 112, 119, 120 or 911.
>
> **Modem EFS writes are risky.** Make an independent device-specific EFS backup (for example `modemst1`, `modemst2`, `fsg`) first, keep it off-device, and use at your own risk. The tool automatically saves an inspection log and hashes, **not a complete restorable backup**. Rollback writes fixed values and deletes items; it does not guarantee restoration of original bytes. Verify your own recovery plan.
>
> **v1.2.4-r1 and earlier lack the new edition gate** and still allow one-click writes on unverified editions. Upgrade to v1.2.5-r1; do not use the older builds to write on those devices.

## Verification checklist

| Item | Evidence |
| --- | --- |
| China Unicom 46001, one `wolverine` | Live-verified outgoing and incoming calls, two-way AMR-WB, caller ID, LTE data during calls, USB-unplugged reboot recovery, and all four delivery methods. |
| China Telecom 46003 / 46011 | LTE attach and IMS registration only; **VoLTE calls not tested**. EPS-only `fallbackmode=9` fix observed for 149 seconds without a drop. |
| China Mobile | **Not tested**. FDD B3/B8 is only a theoretical possibility; Passport lacks TDD B38–41. |
| SMS over IMS | **Not implemented**; the service never claims `CAPABILITY_TYPE_SMS`. |
| Emergency calling, VoWiFi, video calls, supplementary services (forwarding/USSD/UT) | Unsupported or untested; do not rely on them. |
| Non-Chinese carriers, other Passport editions and devices | Not tested; compatibility unknown. |

## Use and rollback

The source defaults to deployment only on `wolverine`. `oslo` (Silver Edition), `wichita` (AT&T), `keian` (Porsche), and generic `wseries` / `windermere` are read-only by default. Experimental CLI writes require both `--experimental-unverified-edition` and an interactive typed confirmation, or `--i-understand-unverified-baseband-write` as the second non-interactive acknowledgement. The desktop GUI and Android manager do not offer one-click activation for these editions. Rollback remains available for recognized Passport editions; non-Passport baseband writes are blocked.

Deployment also requires no conflicting legacy IMS daemon, both `MPSS.DI.4.0.1` and `AAAAANAZM` baseband identifiers, and firmware `build_id:AAB982`; missing or conflicting data blocks writes. Desktop deployment attempts fixed rollback after qualifying post-reboot radio loss, but does not auto-clean on an IMS-only timeout with a ready SIM. Rollback bypasses both deployment gates and cannot guarantee original values or signal recovery. The v1.2.4 safety features were checked offline or with mocks, not on the affected hardware.

```bash
python3 cli/volte_ctl.py status
python3 cli/volte_ctl.py inspect-device
python3 cli/volte_ctl.py deploy       # wolverine; make independent backup first
python3 cli/volte_ctl.py rollback     # fixed cleanup, not original-byte restoration
python3 cli/gui.py
```

Use the Windows EXE GUI “Run Fixed EFS Cleanup & Uninstall”, or `<EXE> --cli rollback`; the Android manager also has a rollback action. The same original-backup limitation applies. Registration alone does not establish successful calls. Four delivery methods (Windows EXE, Android manager, portable CLI/GUI and ROM integration) were tested end to end only on the one Unicom unit.

## v1.2.5-r1 downloads

Each asset also has an individual `.sha256` file.

| Asset | R2 mirror | GitHub Release |
| --- | --- | --- |
| Windows EXE | [Download](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe.sha256) |
| Manager APK | [Download](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk.sha256) |
| IMS APK 2.8 | [Download](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk.sha256) |
| Portable ZIP | [Download](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip.sha256) |
| Source ZIP | [Download](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip.sha256) |
| Checksums | [SHA256SUMS.txt](https://qcom32-downloads.octoooo.com/v1.2.5-r1/SHA256SUMS.txt) | [SHA256SUMS.txt](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/SHA256SUMS.txt) |

## Local checks

```bash
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py self-test
cd website && npm test
```

## Credits

Thanks to [balika011](https://balika011.hu) for the LineageOS 18.1 `wseries` port and conversion tools ([device tree](https://github.com/BBAndroids/android_device_blackberry_wseries)); guizmox for Passport LineageOS stabilization and community work; and [ikimi615](https://github.com/ikimi615/qualcomm-volte-enabler) for the early `/dev/diag` idea.

## License

Source-available under [PolyForm Noncommercial 1.0.0](LICENSE); a commercial license is available: [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md).

## v1.2.5-r1 (2026-11-15)

- Unverified `oslo`, `wichita`, `keian`, `wseries` and `windermere` are read-only by default in the CLI, GUI, EXE and Manager. Experimental CLI writes require explicit opt-in and typed confirmation, or a second explicit acknowledgement for non-interactive use. Rollback remains available for recognized Passport editions.
- Corrected claims: calls were verified on one SQW100-1 `wolverine` with China Unicom 46001; China Telecom has LTE attach and IMS registration evidence only, with calls untested; China Mobile is untested, SMS over IMS is not implemented, and emergency calls are unsupported. Rollback writes a fixed baseline and unlinks created items, and cannot restore each device's original values; `--inspect-state` is not a backup. Independently back up `modemst1`, `modemst2` and `fsg`.
- Includes the v1.2.4 ROM/baseband preflight guards and post-reboot radio-loss auto-rollback, verified offline or with mock ADB only, not on a failing real device.
- v1.2.4-r1 and earlier still allow one-click writes on unverified editions; upgrade to v1.2.5-r1.
