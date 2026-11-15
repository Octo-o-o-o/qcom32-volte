# Qcom32 VoLTE — Android 11 calling stack for 32-bit Qualcomm

**Source-available (PolyForm Noncommercial 1.0.0; commercial license available)** · [中文](README.md) · [Devices](devices/README.md) · [Install and rollback](docs/guide/INSTALL_AND_ROLLBACK.md)

Qcom32 VoLTE provides an Android 11 IMS service and device-specific modem EFS tooling. One BlackBerry Passport **SQW100-1 / `wolverine`** unit was fully live-verified on the community LineageOS 18.1 `wseries` ROM, baseband DI.4.0.1 M8974AAAAANAZM, with China Unicom 46001. Retail Passports require eMMC hardware conversion and the community ROM first.

> ⚠️ **Emergency calls are not supported or certified. Keep another phone for emergencies.** On LTE-only networks where 2G/3G have shut down, this device may be unable to reach 110, 112, 119, 120 or 911.
>
> **Modem EFS writes are risky.** Make an independent device-specific EFS backup (for example `modemst1`, `modemst2`, `fsg`) first, keep it off-device, and use at your own risk. The tool automatically saves an inspection log and hashes, **not a complete restorable backup**. Rollback writes fixed values and deletes items; it does not guarantee restoration of original bytes. Verify your own recovery plan.
>
> **Published v1.2.4-r1 artifacts do not contain the new edition gate or claims corrections.** They still allow one-click writes on `oslo`, `wichita` and `keian`; do not use those writes on unverified editions.

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

Use the Windows EXE GUI “Run Fixed EFS Cleanup & Uninstall”, or `<EXE> --cli rollback`; the Android manager also has a rollback action. The same original-backup limitation applies. Registration alone does not establish successful calls. Four delivery methods (Windows EXE, Android manager, portable CLI/GUI and ROM integration) were tested end to end only on the one Unicom unit. Published downloads are on [Releases](https://github.com/Octo-o-o-o/qcom32-volte/releases).

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

## Unreleased

Gates for unverified Passport editions and corrected verification, emergency-calling and recovery claims are **not in published v1.2.4-r1 artifacts**, which still allow one-click writes on `oslo`, `wichita` and `keian`. No new artifacts have been built or published.
