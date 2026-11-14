# 与参考项目 `ikimi615/qualcomm-volte-enabler` 的完整技术对照

在项目启动初期，我们参考并完整拉取审阅了社区针对黑莓 KEY2 的开源项目 [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler)（固定提交 `ec4c82ab` 与后续文档提交 `aab0f56`）。它提供了“通过高通 `/dev/diag` 访问基带 EFS2 文件系统”的宝贵切入思路，但在 **Android 11 的 BlackBerry Passport（骁龙 801 `MSM8974`）** 上，直接套用或简单改写该项目**完全无法跑通 VoLTE**。

下表基于源码审计、离线内存沙箱复现与 Passport 真机实测证据，逐层对比两者的核心差异：

---

## 一、核心架构与技术栈全维度对比表

| 对比维度 | 参考项目 `qualcomm-volte-enabler`（面向 KEY2） | 本项目 `qcom32-volte`（面向高通 32 位 Android 11，首发 Passport） |
| --- | --- | --- |
| **目标机型与硬件平台** | BlackBerry KEY2（高通骁龙 660 `SDM660`，**64 位 ARM64**） | 高通 32 位平台（骁龙 800/801 `MSM8974`，**32 位 ARMv7-A**；首个完整验证 Profile：BlackBerry Passport `wolverine`） |
| **系统与自带 IMS 框架差异** | KEY2 运行原厂 Android 8.1，系统**自带完整的高通现代闭源 `ims.apk` / `qti-telephony-common` 框架**，只缺基带开关 | 刷入非官方 LineageOS 18.1（Android 11），高通在 Android 6.0 后停更 `MSM8974`，**系统完全缺失现代 `ImsService` 前端** |
| **`/dev/diag` 辅助程序与驱动契约** | 直接分发第三方 `catxeon/qcom-efs-browser v1.4.8` 的 **AARCH64 闭源编译产物**；其 `ioctl` 向 `diag_switch_logging` 传结构体指针，在 `MSM8974` 内核直接触发 `EINVAL (-22)`，且 `MEMORY_DEVICE_MODE` 的 `mask_request_validate` 会拦截 EFS `GET 39/PUT 38` | **100% 从零自研纯 C 语言 32 位 `pp_regconfig_provision_v6`**（559 项宿主机断言）：适配 `MSM8974` 标量整数 `ioctl`，严格校验 EFS2 `sequence16 + errno32`、CCITT CRC-16 与多运营商动态 `profile3` |
| **基带 EFS 修改深度** | **仅将 `/nv/item_files/ims/IMS_enable` 单字节改为 `01`** | **真机实证单改 `IMS_enable=01` 重启后仍 100% `NOT_REGISTERED`**。逆向解压 11.7MB Hexagon Q6ZIP 固件后完成 **Stage 6A 全套 54 项协同配置**：<br>① 补齐原厂缺失的 405B `qp_ims_reg_config`（`mode=0` 动态 PCO 发现，`test_mode=0`）；<br>② 解锁 `/pdp_profiles/profile4` 的 `b753=0`（原为 `1` 禁用）并置位 `b750=1` (`im_cn_flag`) / `b751=1` (`pcscf_flag`)，升级为 789B `IPV4V6`；<br>③ 保持 `ims_hybrid_enable` 为 `ENOENT`，消除 Android `rmnet1` 与 Hexagon DCM 建链死锁；<br>④ 写入 `voice_domain_pref=03`、`ims_operation_mode=02` 及 QIPCALL/SMS/DPL 配套项 |
| **Android 框架与通话控制桥接** | **无任何 Android 应用层代码**（其 README 提到的 `qcril.db`、`ICCID immutable` 与 `post-fs-data` 在仓库 4 个脚本中均无实际实现代码） | **从零手写通用 32 位高通 Android 11 通话栈应用 [`Qcom32VolteIms.apk`](../../src/android-ims-service/)**（`org.octolab.qcom32ims` v2.6）：<br>① `UID 1001` 严守 `SELinux Enforcing`、零 `<uses-permission>` 保护系统电话全部权限；<br>② 三参 `@SystemApi` `overrideConfig(subId, bundle, true)` 跨重启及换卡自动持久绑定；<br>③ 对接 `/dev/socket/qmux_radio/rild_ims0` Protobuf 状态机，打通主被叫、`AMR-WB` 高清语音、DTMF 与挂断；<br>④ 修复 3GPP `CLIP` -> Android `OIR` 语义反转与 `TOA=145` `+` 号补齐，解决来电显示未知号码（`nump=1`）；<br>⑤ 内置零隐私只读 `VolteStatusProvider` |
| **QMI 诊断与栈安全防护** | 仅通过 `dumpsys` 文本 grep 模糊查看输出，无法观测基带真实 IMS PDN 与 SIP 状态 | 自研 [`libimsa_probe.so`](../../src/qmi-imsa-probe/) 直读 `QMI_IMSA` 真实承载与注册位，并预留 64B 负向缓冲区修复黑莓旧版 `libqmi_client_qmux.so` 40B 栈帧踩踏 `SIGSEGV` |
| **原值备份、错误处理与无损回滚** | ① `auto_enable_volte.sh` 未调用备份脚本；<br>② 读取被拒（`EACCES`）变空字节后仍强行解锁写入；<br>③ `EFS_SYNC` 返回失败仍可打印 `SUCCESS` 退出 `0`；<br>④ 无一键恢复原厂工具 | ① **强制 `devices/*/profile.json` 机型锁 + 前置原值备份与哈希/CRC 双重核验**；<br>② 任何一步读写、权限、绑定或 `EFS_SYNC` 轮询状态异常立即中止并自动触发跨重启恢复；<br>③ 内置独立 **`rollback` 一键无损回滚**：自动删除全部 52 个新建 EFS 路径（核验 `ENOENT`）、恢复 `IMS_enable=00` / `voice_domain_pref=00` / 4 项 DS Profile 引用并卸载通话栈包 |
| **用户交付与使用形态** | 仅提供命令行 Shell + Python 散装脚本（需手动装 Python、ADB、端口转发 9999） | 提供 **4 种完整标准化交付形态**（均经实机端到端验证）：<br>① **Windows 单文件一键 EXE 图形界面**（内置 ADB 与全套签名载荷，免装任何环境）；<br>② **手机端状态/管理伴侣 App（`Qcom32VolteManager.apk`）**（免 `su` 实时屏幕面板 + Magisk 机内激活/回滚）；<br>③ **跨平台零依赖 CLI / Tk GUI**（支持 Windows / macOS / Linux）；<br>④ **LineageOS 18.1 ROM 源码树集成补丁包**（`rom-integration/`） |

---

## 二、为什么不能直接把上游脚本改个 32 位就用在 Passport 上？

我们在研发早期曾按证据逐步验证过“最小改动路径”，真机实验证明每一层都有硬阻塞：

1. **第一道墙（内核 DIAG 过滤）**：直接运行 64 位 helper 会因 ELF64 架构不匹配立即失败；即便重编 32 位，若沿用上游的 `MEMORY_DEVICE_MODE` + 指针 `ioctl`，`MSM8974` 内核驱动直接拒绝请求。
2. **第二道墙（单改 `IMS_enable=01` 远远不够）**：在打通 32 位 DIAG 后，我们将 `IMS_enable` 单独改为 `01` 并完成 `EFS_SYNC` 重启，基带 `IMS_enable` 读回确实是 `01`，但因 Passport 原厂 BB10 从未为开放市场预置 `qp_ims_reg_config`，且 `profile4` 处于禁用态（`b753=1`），基带 `QMI_IMSS` 直接返回 `CAUSE_CODE 54 / READ_FAILED`，IMS 全程未注册。
3. **第三道墙（基带注册了，Android 11 依然打不出 VoLTE）**：即便完成 Stage 6A 让基带在开机后成功连上联通 IMS 核心网并拿到 `200 OK`（`QMI_IMSA reg_status=2`），LineageOS 18.1 的 `ImsResolver` 因为找不到任何实现 `MmTelFeature` 的现代 `ImsService`，系统 `mImsRegistered` 依然为 `false`，拨号器仍强制走 CSFB 回落——必须配合 `Qcom32VolteIms.apk` 才能把基带已就绪的 IMS 语音能力真正桥接给 Android 11 原生电话应用。

---

## 三、总结致谢

尽管技术实现完全不同，`ikimi615/qualcomm-volte-enabler` 验证了“黑莓高通安卓设备在取得 Root 后可通过 `/dev/diag` 操作基带 EFS2”这一关键方向。本项目在此启发下完成了从 `MSM8974` Hexagon 基带逆向、EFS2 动态供给回滚器到 Android 11 原生 `ImsService` 电话栈的完整闭环开源实现。
