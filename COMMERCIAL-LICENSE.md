# 商业授权与非商用使用边界说明 / Commercial Licensing & Scope Guide

**版权方 / Copyright Holder**: `OctoLab (Octo-o-o-o)`  
**商业授权联系邮箱 / Commercial Licensing Contact**: [`octoooo@octoooo.com`](mailto:octoooo@octoooo.com)  
**适用软件范围 / Covered Software**: `qcom32-volte` 仓库内全部原创源码、编译产物（`pp_regconfig_provision_v6`、`libimsa_probe.so`、`Qcom32VolteIms.apk`、`Qcom32VolteManager.apk`、`qcom32-volte-*-windows-x64.exe`）、设备基带档案（`devices/*/profile.json`）、ROM 集成套件（`rom-integration/`）与配套文档。

---

## 一、使用场景授权对照表 / Quick Eligibility Matrix

| 使用场景 / Scenario | 是否免费 / Free? | 适用协议与条件 / Applicable License & Conditions |
| --- | --- | --- |
| **个人玩家自用**（给自己的老手机刷入/激活 VoLTE、日常通话、无偿帮亲友排障）<br>*Personal own-device activation, daily use, hobbyist debugging* | **免费允许 (FREE)** | 适用 [`LICENSE`](LICENSE) 中的 **PolyForm Noncommercial License 1.0.0**，无需申请。 |
| **开源/学术研究与新机型免费适配 PR**（研究 MSM8974 基带机制、提交新机型 `profile.json`）<br>*Academic research, noncommercial reverse-engineering & free device profile PRs* | **免费允许 (FREE)** | 适用 **PolyForm Noncommercial License 1.0.0**，保留署名与相同非商用协议即可。 |
| **100% 纯公益免费的第三方社区 ROM 集成**（如免费公开发布的 LineageOS 社区刷机包合入 `rom-integration/`）<br>*100% free, non-paywalled community custom ROMs* | **免费允许 (FREE)** | 适用 **PolyForm Noncommercial License 1.0.0**；须在 ROM 发布说明与源码树中保留原作者署名、`LICENSE`、`COMMERCIAL-LICENSE.md` 及 `Required Notice:` 非商用声明，不得设置付费下载、进付费群或强制打赏门槛。 |
| **有偿代刷 / 远程激活 / 维修店技术服务 / 倒卖安装包或教程**（闲鱼、淘宝、转转、拼多多、小红书、线下档口等）<br>*Paid flashing, remote activation, repair-shop installation, or selling packages/guides* | **需付费授权 (PAID LICENSE REQUIRED)** | 属于商业营利行为，未经 **OctoLab** 书面付费授权严禁开展。 |
| **二手机 / 翻新机 / 主板预刷 VoLTE 售卖**（含将“已破解 4G 高清通话”作为卖点，或“买手机免费送代刷 VoLTE”等捆绑销售）<br>*Selling secondhand/refurbished phones or boards pre-flashed with this stack, or bundling VoLTE activation with hardware sales* | **需付费授权 (PAID LICENSE REQUIRED)** | 属于借助本软件获取商业溢价或促成硬件交易的商业用途，必须事先获得 **OctoLab** 按量或按店付费授权。 |
| **收费刷机包 / 赞助解锁 ROM / 商业刷机盒子或工具箱集成**<br>*Paid/VIP custom ROMs, commercial flashing boxes, or commercial software bundling* | **需付费授权 (PAID LICENSE REQUIRED)** | 必须事先签署 **OctoLab** 商业软件集成授权协议。 |

---

## 二、如何申请商业授权 / How to Obtain a Commercial License

如果您是数码维修工作室、经典机型二手商家、复古硬件翻新团队或刷机工具开发者，希望合规地在商业服务或预装整机中使用 **`qcom32-volte`**，请在使用前发送邮件至：

- **联系邮箱 / Email**: [`octoooo@octoooo.com`](mailto:octoooo@octoooo.com)
- **建议邮件标题 / Suggested Subject**: `[Commercial License] Qcom32 VoLTE - <您的店铺/机构名称 / Your Shop or Entity Name>`
- **请简要说明 / Please Include**:
  1. 您的主体名称、所在平台或店铺主页（如闲鱼/淘宝店铺名、维修工作室或公司名称）；
  2. 计划使用的具体场景（例如：单次代刷服务、预刷整机销售预估台数、或软件/ROM 集成）；
  3. 涉及的目标机型（如 BlackBerry Passport `wolverine` 或其他高通 32 位机型）。

获得正式商业授权后，您将享有合规商业使用权，且无需承担违反非商业协议的侵权追责风险。

---

## 三、常见合规问答 / Licensing FAQ

### 1. 我把手机自用一段时间后当普通二手闲置卖掉，算商用吗？
**不算商用。** 普通个人玩家偶尔转让自己日常使用过的单台闲置自用手机属于正常个人物权处分（您也可以在转让前使用一键回滚恢复出厂基带）。本协议限制的是**以营利为目的批量采购、翻新、预刷 VoLTE 加价售卖手机/主板，或挂牌提供有偿代刷服务**的经营行为。

### 2. 我在闲鱼卖手机“只收手机钱，免费帮买家刷好 VoLTE”，需要商业授权吗？
**需要。** 无论是单独收取手工代刷费，还是将预刷/赠送本方案 VoLTE 功能作为二手手机销售的附加服务或宣传卖点，实质上均属于商业经营中的增值商业使用，均不在 `PolyForm Noncommercial License 1.0.0` 的非商业许可范围内。

### 3. 第三方工具箱把 EXE 放在网盘里卖“整理费/辛苦费”允许吗？
**不允许。** 本项目所有官方发行包已在官网（`https://qcom32-volte.octoooo.com/`）、Cloudflare R2 国内直连镜像与 GitHub Releases 完全免费公开提供。以“打包费、网盘会员解压码、远程指导费”等任何名义向用户收费分发均构成未经授权的商业侵权。
