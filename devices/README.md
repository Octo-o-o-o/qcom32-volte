# Device profiles

The source-available stack (PolyForm Noncommercial 1.0.0; commercial license available) separates Android IMS code from modem EFS profiles. Other devices require independent porting and verification; shared hardware or ROM names do not prove compatibility.

| Identity | Evidence and default action |
| --- | --- |
| One Passport SQW100-1, `wolverine`, LineageOS 18.1 wseries, China Unicom 46001 | Outgoing/incoming calls, two-way AMR-WB, caller ID, LTE data during calls, unplugged reboot recovery and all four delivery methods verified on this unit. Source permits deploy, upgrade and rollback. |
| `oslo` (SQW100-4 Silver Edition), `wichita` (SQW100-2 AT&T), `keian` (Porsche), `wseries`, `windermere` | Experimental, not verified on real hardware. Read-only by default. CLI baseband write requires `--experimental-unverified-edition` and a separate typed confirmation, or `--i-understand-unverified-baseband-write` for non-interactive use. GUI/Android manager do not offer activation. Rollback remains available. |
| Non-Passport | No Passport EFS writes or rollback; read-only readiness inspection and independent porting only. |

China Telecom 46003/46011: LTE attach and IMS registration only; calls not tested; EPS-only fallback fix observed 149 seconds without drops. China Mobile is untested (FDD B3/B8 theoretical only, no TDD B38–41). Non-Chinese carriers, emergency calls, VoWiFi, video and supplementary services are unsupported or untested. SMS over IMS is not implemented.

**Published v1.2.3-r1 artifacts predate the new edition gate.** Do not run their write operations on unverified editions. Source changes have not been released.

Back up the device's original EFS independently and keep it off-device before writing. The tool's automatic inspection log contains hashes, not a restorable full backup. Fixed `rollback` actions do not guarantee recovery of original bytes. See [installation and rollback](../docs/guide/INSTALL_AND_ROLLBACK.md) and [porting guidance](../docs/guide/MSM8974_PORTING_GUIDE.md).
