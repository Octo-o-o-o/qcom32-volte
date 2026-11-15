---
name: qcom32-volte
description: >-
  Identifies connected or user-named phones, checks compatibility, deploys or rolls back native
  4G VoLTE on a verified BlackBerry Passport `wolverine` and guides read-only inspection of
  unverified Passport editions (`oslo`, `wichita`, `keian`, `wseries`, `windermere`) and safe
  4-gate VoLTE porting and LineageOS 18.1 preparation for candidate MSM8974/APQ8084 devices
  (Samsung Galaxy S5 `klte`, Xiaomi Mi 4 `cancro`, LG G3 `d855`, OnePlus One `bacon`, Sony Xperia
  Z1/Z2/Z3, HTC One M8, Moto X 2014, Nexus 6). Use whenever the user asks to check device support,
  flash/enable/debug/upgrade/rollback VoLTE, or adapt a new 32-bit Qualcomm phone
  （识别手机型号、刷机与激活高通32位老旗舰安卓11 VoLTE、移植新机型或回滚基带配置）.
---

# Qcom32 VoLTE — Claude Code Skill Entrypoint

Only one SQW100-1 `wolverine` on China Unicom 46001 has complete call verification. China Telecom 46003/46011 has LTE attach and IMS registration only; calls are untested (EPS-only fix: 149 s without a drop). China Mobile is untested; FDD B3/B8 is theoretical and Passport lacks TDD B38–41. IMS SMS is not implemented. Emergency calls are not supported or certified; keep another phone. VoWiFi, video, supplementary services and non-Chinese carriers are unsupported or untested.

EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证: `oslo`, `wichita`, `keian`, `wseries`, `windermere` default to read-only; CLI EFS writes require `--experimental-unverified-edition` plus typed confirmation or `--i-understand-unverified-baseband-write`. GUI/on-device activation is blocked; rollback remains available for Passport. Non-Passport baseband writes are blocked. v1.2.4-r1 and earlier lack this gate and still allow one-click writes on unverified editions; upgrade to v1.2.5-r1.

Back up EFS independently and keep the backup off-device before writing. The automatic inspection log is not a restorable full backup, and fixed rollback does not guarantee the original bytes. This project is source-available (PolyForm Noncommercial 1.0.0; commercial license available).

This repository ships a unified multi-agent skill under `.agents/skills/qcom32-volte/`.

1. **Read the complete workflow & safety rules first**:
   - Main runbook: [`../../../.agents/skills/qcom32-volte/SKILL.md`](../../../.agents/skills/qcom32-volte/SKILL.md)
   - Device & sub-model blocker matrix: [`../../../.agents/skills/qcom32-volte/references/SUPPORTED_DEVICES_MATRIX.md`](../../../.agents/skills/qcom32-volte/references/SUPPORTED_DEVICES_MATRIX.md)
   - 4-Gate new-device porting & EFS safety guide: [`../../../.agents/skills/qcom32-volte/references/PORTING_AND_EFS_SAFETY.md`](../../../.agents/skills/qcom32-volte/references/PORTING_AND_EFS_SAFETY.md)
2. **Always run the read-only device inspector / catalog before acting**:
   ```bash
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py inspect
   python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py catalog --query "<model_or_codename>"
   ```
3. **Core Guardrail**: Never run Passport's `pp_regconfig_provision_v6` EFS write commands on non-Passport models without completing the 4-Gate read-only `/pdp_profiles/*` audit and registering a verified `devices/<id>/profile.json`.
