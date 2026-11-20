# Qcom32 VoLTE — Android 11 calling stack for 32-bit Qualcomm

**Source-available (PolyForm Noncommercial 1.0.0; commercial license available)** · [中文](README.md) · [Devices](devices/README.md) · [Install and rollback](docs/guide/INSTALL_AND_ROLLBACK.md)

Qcom32 VoLTE provides an Android 11 IMS service and device-specific modem EFS tooling. One BlackBerry Passport **SQW100-1 / `wolverine`** unit was fully live-verified on the community LineageOS 18.1 `wseries` ROM, baseband DI.4.0.1 M8974AAAAANAZM, with China Unicom 46001. Retail Passports require eMMC hardware conversion and the community ROM first.

> ⚠️ **Emergency calls are not supported or certified. Keep another phone for emergencies.** On LTE-only networks where 2G/3G have shut down, this device may be unable to reach 110, 112, 119, 120 or 911.
>
> **Modem EFS writes are risky.** Make an independent device-specific EFS backup (for example `modemst1`, `modemst2`, `fsg`) first, keep it off-device, and use at your own risk. The tool automatically saves an inspection log and hashes, **not a complete restorable backup**. Rollback writes fixed values and deletes items; it does not guarantee restoration of original bytes. Verify your own recovery plan.
>
> **v1.2.4-r1 and earlier lack the unverified-edition gate** (still permitting one-click writes on unverified editions), and **v1.2.4-r1 / v1.2.5-r1 falsely reject the real-device `.DI.4.0.1` runtime baseband string and unrooted read-only checks**. Upgrade to v1.2.8-r1; do not use older builds to write on unverified devices.

## Verification checklist

| Item | Evidence |
| --- | --- |
| China Unicom 46001, one `wolverine` | Live-verified outgoing and incoming calls, two-way AMR-WB, caller ID, LTE data during calls, USB-unplugged reboot recovery, and all four delivery methods. |
| China Unicom 46001, one Silver Edition (`oslo`) | Incoming and outgoing calls confirmed on the prepared 20251205 ROM with AAB982. CLI experimental confirmations still apply. |
| China Telecom 46003 / 46011 | LTE and IMS registration verified. A separate 46011 unit had two-way speakerphone audio with a silent earpiece still under investigation. EPS-only `fallbackmode=9` was observed for 149 seconds without drops. |
| China Mobile | **Not tested**. FDD B3/B8 is only a theoretical possibility; Passport lacks TDD B38–41. |
| SMS over IMS | **Not implemented**; the service never claims `CAPABILITY_TYPE_SMS`. |
| Emergency calling, VoWiFi, video calls, supplementary services (forwarding/USSD/UT) | Unsupported or untested; do not rely on them. |
| Non-Chinese carriers, other Passport editions and devices | Not tested; compatibility unknown. |

## Use and rollback

The source defaults to deployment only on `wolverine`. `oslo` (Silver Edition), `wichita` (AT&T), `keian` (Porsche), and generic `wseries` / `windermere` are read-only by default. Experimental CLI writes require both `--experimental-unverified-edition` and an interactive typed confirmation, or `--i-understand-unverified-baseband-write` as the second non-interactive acknowledgement. The desktop GUI and Android manager do not offer one-click activation for these editions. Rollback remains available for recognized Passport editions; non-Passport baseband writes are blocked.

Deployment also requires no conflicting legacy IMS daemon, both `DI.4.0.1` and `AAAAANAZM` baseband identifiers (matching runtime `.DI.4.0.1-00158-M8974AAAAANAZM-1`), and firmware `build_id:AAB982`; missing or conflicting data blocks writes. Desktop deployment attempts fixed rollback after qualifying post-reboot radio loss, but does not auto-clean on an IMS-only timeout with a ready SIM. Rollback bypasses both deployment gates and cannot guarantee original values or signal recovery. The v1.2.4 safety features were checked offline or with mocks, not on the affected hardware.

```bash
python3 cli/volte_ctl.py status
python3 cli/volte_ctl.py inspect-device
python3 cli/volte_ctl.py deploy       # wolverine; make independent backup first
python3 cli/volte_ctl.py rollback     # fixed cleanup, not original-byte restoration
python3 cli/gui.py
```

Use the Windows EXE GUI “Run Fixed EFS Cleanup & Uninstall”, or `<EXE> --cli rollback`; the Android manager also has a rollback action. The same original-backup limitation applies. Registration alone does not establish successful calls. Four delivery methods (Windows EXE, Android manager, portable CLI/GUI and ROM integration) were tested end to end only on the one Unicom unit.

After activation, you can uninstall the optional **Qcom32 VoLTE** companion (`org.octolab.qcom32volte`), disconnect the computer, and turn off USB and rooted debugging. Keep **Qcom32 VoLTE IMS** (`org.octolab.qcom32ims`) installed and enabled for native VoLTE. Use Android’s app settings to remove the companion. The rollback action also clears modem configuration.

## v1.2.8-r1 standby optimization preview

- IMS 2.10 waits for socket events until the next scheduled check, reducing idle read timeouts while retaining registration checks, active-call checks and reconnects.
- Already activated devices can use `upgrade-apk` without rewriting EFS. Host builds and connection regression tests passed. Screen-off power and lock-screen calls await handset testing. [v1.2.7-r1](https://github.com/Octo-o-o-o/qcom32-volte/releases/tag/v1.2.7-r1) remains the stable release.

## v1.2.8-r1 downloads

Each asset also has an individual `.sha256` file.

| Asset | R2 mirror | GitHub Release |
| --- | --- | --- |
| Windows EXE | [Download](https://qcom32-downloads.octoooo.com/v1.2.8-r1/qcom32-volte-1.2.8-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.8-r1/qcom32-volte-1.2.8-r1-windows-x64.exe.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/qcom32-volte-1.2.8-r1-windows-x64.exe) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/qcom32-volte-1.2.8-r1-windows-x64.exe.sha256) |
| Manager APK | [Download](https://qcom32-downloads.octoooo.com/v1.2.8-r1/Qcom32VolteManager-1.2.8-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.8-r1/Qcom32VolteManager-1.2.8-r1.apk.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/Qcom32VolteManager-1.2.8-r1.apk) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/Qcom32VolteManager-1.2.8-r1.apk.sha256) |
| IMS APK 2.10 | [Download](https://qcom32-downloads.octoooo.com/v1.2.8-r1/Qcom32VolteIms-2.10-r1-platform.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.8-r1/Qcom32VolteIms-2.10-r1-platform.apk.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/Qcom32VolteIms-2.10-r1-platform.apk) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/Qcom32VolteIms-2.10-r1-platform.apk.sha256) |
| Portable ZIP | [Download](https://qcom32-downloads.octoooo.com/v1.2.8-r1/qcom32-volte-1.2.8-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.8-r1/qcom32-volte-1.2.8-r1-portable.zip.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/qcom32-volte-1.2.8-r1-portable.zip) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/qcom32-volte-1.2.8-r1-portable.zip.sha256) |
| Source ZIP | [Download](https://qcom32-downloads.octoooo.com/v1.2.8-r1/qcom32-volte-1.2.8-r1-source.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.8-r1/qcom32-volte-1.2.8-r1-source.zip.sha256) | [Download](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/qcom32-volte-1.2.8-r1-source.zip) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/qcom32-volte-1.2.8-r1-source.zip.sha256) |
| Checksums | [SHA256SUMS.txt](https://qcom32-downloads.octoooo.com/v1.2.8-r1/SHA256SUMS.txt) | [SHA256SUMS.txt](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.8-r1/SHA256SUMS.txt) |

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

## v1.2.6-r1 (2026-11-15)

- Fixed a baseband preflight false-positive in `v1.2.4-r1` / `v1.2.5-r1`: Android 11 RIL on the verified `wolverine` unit reports `gsm.version.baseband=.DI.4.0.1-00158-M8974AAAAANAZM-1` (without the `MPSS.` prefix). The CLI, GUI/EXE, Manager APK, and AI helper now validate `DI.4.0.1` + `AAAAANAZM` while continuing to strictly require `build_id:AAB982` in `/firmware/image/contents.txt` during root deployment (blocking `DI.2.0.1`, `AAA*`, and missing firmware IDs).
- Fixed unrooted read-only readiness false-positives when ADB Root or local `su` is not enabled yet (requesting root visibility to verify `AAB982` once `DI.4.0.1` + `AAAAANAZM` matches), and added actionable `/dev/diag` rollback recovery guidance for unreachable `MPSS` modems.
- Retains all `v1.2.5-r1` read-only edition gates and corrected claims: `oslo`, `wichita`, `keian`, `wseries`, and `windermere` default to read-only (rollback kept); calls were verified on one SQW100-1 `wolverine` on China Unicom 46001, China Telecom has LTE attach and IMS registration only (calls untested), China Mobile is untested, SMS over IMS is not implemented, emergency calls are unsupported, and fixed rollback cannot guarantee original values. `v1.2.4-r1` and earlier still allow one-click writes on unverified editions; upgrade to `v1.2.6-r1`.
