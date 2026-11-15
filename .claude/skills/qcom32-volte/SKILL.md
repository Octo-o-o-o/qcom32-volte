---
name: qcom32-volte
description: >-
  Identifies connected or user-named phones, checks compatibility, deploys or rolls back native
  4G VoLTE on verified 32-bit Qualcomm Android 11 devices (BlackBerry Passport all editions:
  `wolverine`, Silver Edition `oslo`, AT&T `wichita`, Porsche `keian`, `wseries`), and guides safe
  4-gate VoLTE porting and LineageOS 18.1 preparation for candidate MSM8974/APQ8084 devices
  (Samsung Galaxy S5 `klte`, Xiaomi Mi 4 `cancro`, LG G3 `d855`, OnePlus One `bacon`, Sony Xperia
  Z1/Z2/Z3, HTC One M8, Moto X 2014, Nexus 6). Use whenever the user asks to check device support,
  flash/enable/debug/upgrade/rollback VoLTE, or adapt a new 32-bit Qualcomm phone
  （识别手机型号、刷机与激活高通32位老旗舰安卓11 VoLTE、移植新机型或回滚基带配置）.
---

# Qcom32 VoLTE — Claude Code Skill Entrypoint

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
