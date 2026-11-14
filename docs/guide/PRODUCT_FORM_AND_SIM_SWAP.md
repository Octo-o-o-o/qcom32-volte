# 面向普通用户的分发形态设计与换卡（SIM Swap）行为分析

本文档深入回答本开源项目走向大众分发时的两个核心工程与产品问题：
1. **给普通用户直接使用时，最佳产品形态应该是什么？（刷机包 vs 手机 App vs 桌面一键工具）**
2. **用户日常换卡（同运营商换卡、跨运营商换卡、无 VoLTE 卡）时，底层如何工作、用户需要做什么？**

---

## 问题一：面向最终用户的最佳产品形态是什么？

### 1.1 先看硬性系统权限边界（为什么“纯普通手机 App”无法独立完成首次激活）

在本方案中，打通 Passport VoLTE 需要跨越两个完全不同的 Android 11 安全边界（且全程保持 `SELinux = Enforcing`）：

| 步骤 | 所需运行身份与权限 | 普通用户态 App 能否做到？ | 原因说明 |
| --- | --- | --- | --- |
| **① 首次写入基带 Stage 6A EFS 配置** (`pp_regconfig_provision_v6`) | **`UID 0` (`root`)** 访问 `/dev/diag` 并切换 `DIAG_IOCTL_SWITCH_LOGGING` | **取决于系统是否装有 `su` (Magisk)** | 原版 LineageOS 18.1 默认**没有内置 `/system/xbin/su`**，App 在手机本地调不到 `su`；系统只开放了 USB `adb root`（即连电脑时的调试 Root）。即便 `UID 1001` (`radio`) 直接打开 `/dev/diag` 也会被内核拒绝 (`EACCES errno=13`)。 |
| **② 常驻运行 `Qcom32VolteImsService`** (`Qcom32VolteIms.apk`) | **`UID 1001` (`android.uid.phone` / `u:r:radio:s0`)** + **ROM `platform` 签名** | **可作为签名 APK 直接安装** | 连接 `/dev/socket/qmux_radio/rild_ims0` 与调用 `CarrierConfigManager.overrideConfig(..., true)` 必须是 `radio` 身份。由于社区流通的 LineageOS 18.1 使用统一 AOSP `platform` 签名，我们的 `Qcom32VolteIms.apk` 可以直接通过 `pm install` 安装到 `/data/app/`，甚至无需挂载改写 `/system` 分区！ |

### 1.2 三种分发形态的工程对比

| 维度 | 方案 A：TWRP 刷机包 (`Flashable ZIP`) | 方案 B：手机端 Root 管理器 App (`Manager APK`) | 方案 C：桌面端一键激活器 (`Windows/macOS/Linux GUI/CLI`) |
| --- | --- | --- | --- |
| **是否需要用户刷入 Magisk (`su`)** | 不需要 Magisk，但需要已刷入 TWRP Recovery | **必须先刷 Magisk**（否则手机本地无 `su` 执行 EFS 写入） | **不需要 Magisk，也不需要 TWRP！** 直接利用 LineageOS 自带的“开发者选项 -> Root 身份调试” |
| **对 `/system` 分区的侵入性** | 高（改写 `/system/priv-app` 与开机 `init` 脚本，OTA 升级易被覆盖） | 低（安装在 `/data/app`） | **零侵入**（不改 `/system`、不改 `boot.img`，随时一键无痕卸载回滚） |
| **基带 `/dev/diag` EFS 写入可靠性** | **存在明显短板**：在 TWRP Recovery 模式下，高通基带与正常 Android 用户态 DIAG 路由环境不同，不能在 Recovery 里直接跑 EFS2 供给，只能塞开机一次性 root 脚本，排错黑盒且危险 | 高（在正常开机驻留 LTE 后通过 `su` 调用 `pp_regconfig_provision_v6`，实时看返回结果） | **最高**（正常开机驻留 LTE 后通过 USB `adb root` 执行，且自动把用户原始基带 EFS 备份保存到电脑硬盘双重保险） |
| **普通用户上手门槛** | 中（需进 Recovery 刷包） | 极低（点一下按钮即可，但前提是手机装了 Magisk） | 极低（插上 USB 点“一键开启 VoLTE”，2 分钟自动重启生效，拔线永久使用） |

### 1.3 最终推荐产品架构：**“桌面端一键工具（主力首发） + 手机端状态/Root 伴侣 App（进阶可选）”双形态组合**

1. **主力形态（强烈推荐给 95% 用户）：跨平台桌面一键图形工具 / 单文件可执行程序 (`PassportVoLTE-Setup.exe` / `.app`)**
   - **为什么它是最佳形态**：刷了 LineageOS 18.1 的 Passport 用户，手机里未必装了 Magisk，但 **100% 都有开发者选项里的“Root 身份调试 (`adb root`)”**！
   - **用户体验**：做成单个免安装桌面小工具（内置 `adb`、`pp_regconfig_provision_v6` 和 `PassportVolteIms.apk`），界面只有三个按钮：
     - **`[一键激活 VoLTE]`**：自动检测 Passport 型号与 SIM 卡状态 -> 自动把原始 EFS 备份到电脑本地文件夹 -> 写入 Stage 6A 基带配置 -> 安装 `PassportVolteIms.apk` -> 自动重启并验证 IMS 注册成功；
     - **`[检测当前 VoLTE 状态]`**：可视化展示 LTE、IMS PDN、SIP 注册、高清语音就绪状态；
     - **`[一键恢复出厂基带]`**：随时完整回滚。
   - **最大优势**：首次连电脑只需 2 分钟，激活后**永久脱离电脑本地运行**，不破环系统完整性，且电脑端留有原始 EFS 备份。

2. **辅助形态（面向已装 Magisk 的玩家）：手机端 `Passport VoLTE 助手` Root APK**
   - 在 `PassportVolteIms.apk` 之外提供一个普通的管理界面 App（或内置诊断页）：
     - **无 Magisk 时**：作为纯只读“VoLTE 状态面板”与快捷重新触发 `CarrierConfig` 绑定的开关；
     - **检测到手机已装 Magisk (`su`) 时**：解锁 App 内的“免电脑一键写入/回滚基带配置”按钮，直接通过 `su -c` 调用内置的 `pp_regconfig_provision_v6`。

> [!TIP]
> **为什么不推荐把 TWRP 刷机包作为唯一形态？**
> 因为本项目的核心壁垒不在“往 `/system/priv-app` 塞一个 APK”，而在于**正常开机状态下通过 `/dev/diag` 对 Hexagon 基带做带校验的 Stage 6A EFS2 供给与 `EFS2_DIAG_SYNC`**。桌面一键工具 + 可选 Magisk App 既避开了 TWRP 下基带未完整启动的坑，又做到了零改 `/system` 分区。

---

## 问题二：如果用户换卡（SIM Swap），底层会发生什么？应该如何操作？

我们在设计 Stage 6A 基带配置与 `PassportVolteIms.apk` 时，**专门排除了硬编码运营商参数的做法**，因此换卡体验非常顺滑。具体分以下 4 种场景说明：

### 2.1 场景一：更换同运营商或标准 VoLTE 4G/5G SIM 卡（例如换另一张联通卡，或符合频段的电信/海外 VoLTE 卡）

- **用户需要重新连电脑刷配置吗？**
  - **完全不需要！零电脑操作！**
- **底层自动发生了什么？**
  1. **基带侧 (`Stage 6A mode=0`) 完全与卡解耦**：`pp_regconfig_provision_v6` 写入基带的 `qp_ims_reg_config` 采用的是 **`mode=0`（3GPP 标准动态发现模式）**，里面**没有写死任何联通 IP、域名、IMSI 或 ICCID**，承载 `/pdp_profiles/profile4` 也是国际通用标准 `APN="ims"` (`IPV4V6`)。换新卡后，Hexagon 基带会自动读取新 USIM 卡里的 `IMSI`/`ISIM`，自动拼出新运营商归属域 `ims.mnc<新MNC>.mcc<新MCC>.3gppnetwork.org`，并通过 LTE PCO 自动获取新卡运营商的 P-CSCF 地址完成鉴权注册！
  2. **Android 侧自动识别新卡 `subId`**：`CarrierConfigBootstrapReceiver` 同时监听了 `SIM_STATE_CHANGED` 与 `ACTION_CARRIER_CONFIG_CHANGED`，并且动态遍历 `SubscriptionManager.getActiveSubscriptionIdList()`（没有写死 `subId=1`）。插入新 SIM 卡后，广播接收器会在手机本地自动为**新卡的 `subId`** 写入持久化 `CarrierConfig` VoLTE 覆盖并绑定 `PassportVolteImsService`！
- **推荐用户换卡标准动作**：
  - 直接插拔换卡即可；如果热插拔（不关机换卡）后个别基站未立即刷新 IMS PDN，**只需在手机上正常重启一次（无需连电脑）**，约 10~15 秒即可在新卡上自动注册 VoLTE。

### 2.2 场景二：国内三大运营商跨网换卡——必须注意 Passport 硬件射频频段限制！

开源文档中必须向用户讲清楚 **BlackBerry Passport (`SQW100-1` 北美/国际公开版硬件) 的物理射频频段能力**——软件可以打通全通用 VoLTE，但不能改变射频前端硬件支持的 LTE 频段（Passport 支持 **FDD-LTE Band 1 / 2 / 3 / 4 / 5 / 7 / 8 / 13 / 17 / 20**，**硬件不支持 TDD-LTE Band 38/39/40/41，也不支持电信传统 CDMA 1x**）：

| 换入的 SIM 卡 | LTE 频段匹配度 | 换卡后表现与操作建议 |
| --- | --- | --- |
| **中国联通 (`46001`)** | **完美匹配**（主力为 FDD B1 / B3 / B8） | 插卡即用，4G 数据 + 原生高清 VoLTE 通话完美工作（已实机完整验收）。 |
| **中国电信 (`46003` / `46011`)** | **FDD 频段完美匹配**（主力为 FDD B1 / B3 / B5/B8） | **极具价值**：过去 Passport 插电信卡因为硬件缺 CDMA 1x 无法打电话；开启本方案 VoLTE 后，只要电信卡已开通 VoLTE 且系统 APN 表含电信 `ims` APN，即可直接通过 LTE VoLTE 实现电信通话！（注：因无 CDMA 1x，在无 4G 信号的极偏远地下室无法回落 2G 语音）。 |
| **中国移动 (`46000`)** | **部分匹配**（移动主力为 TDD B38/39/40/41，许多城市辅建了 FDD B3 / B8） | 在覆盖了移动 **FDD-LTE B3 / B8** 的区域可驻留 4G 并使用 VoLTE；若所在区域仅有移动 TDD 4G，手机硬件会落回移动 2G (`GSM/EDGE`)，此时本方案会自动无缝走 2G CS 语音通话。 |

### 2.3 场景三：换入一张“未开通 VoLTE”的副卡 / 物联卡 / 老旧 SIM 卡

- **会不会导致打不出电话？**
  - **绝对不会！**
- **自动回退机制（CSFB 无缝兜底）**：
  1. 基带侧我们将 `voice_domain_pref` 设为 `03` (`IMS_PS_VOICE_PREFERRED_CS_SECONDARY`，即“PS 域 IMS 语音优先，CS 电路域语音作为次选备份”)，而非强制仅 IMS；
  2. Android 侧 `CallStateEngine.isVoiceImsReady()` 严格检查基带真实注册状态：当新卡没有开通 VoLTE、基带返回 `NOT_REGISTERED` 时，`PassportMmTelFeature.shouldProcessCall()` 会自动返回 `PROCESS_CALL_CSFB`，让系统电话框架 100% 自动回落到传统 3G/2G 电路交换语音拨号与接听，移动数据上网也完全不受影响。

### 2.4 场景四：少数海外特殊运营商使用非标准 IMS APN 名称

- 绝大多数全球运营商（包括中国联通、中国移动、中国电信及主流欧美运营商）均使用 3GPP 标准 `APN="ims"` 承载 VoLTE。
- 仅当极少数特殊海外运营商要求非 `"ims"` 名称的专属 IMS APN，或仅支持纯单栈且拒绝 `IPV4V6` 请求时，才需要用工具微调 `/pdp_profiles/profile4`（`pp_regconfig_provision_v6` 源码中已预留纯 IPv6 等载荷模板，方便开发者按需扩展）。
