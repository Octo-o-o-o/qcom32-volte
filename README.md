# Qcom32 VoLTE — Clean-Room 4G VoLTE & Android 11 `ImsService` Stack for 32-Bit Qualcomm Platforms (`qcom32-volte`)

**简体中文** · **[English (`README.en.md`)](README.en.md)** · **[官方网站 (`qcom32-volte.octoooo.com`)](https://qcom32-volte.octoooo.com/)** · **[完整使用指南](https://qcom32-volte.octoooo.com/guide)** · **[已验证设备库 (`devices/`)](devices/README.md)** · **[Releases 下载](https://github.com/Octo-o-o-o/qcom32-volte/releases)**

[![License](https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0_%7C_Commercial-2e654d.svg)](LICENSE)
[![Commercial License](https://img.shields.io/badge/Commercial_Use-Paid_License_Required-984b3e.svg)](COMMERCIAL-LICENSE.md)
[![Platform](https://img.shields.io/badge/Platform-Qualcomm_32--bit_(MSM8974)-black.svg)](docs/guide/ARCHITECTURE.md)
[![OS](https://img.shields.io/badge/Android-11_(LineageOS_18.1)-2e654d.svg)](docs/guide/INSTALL_AND_ROLLBACK.md)
[![Verified](https://img.shields.io/badge/First_Verified_Profile-BlackBerry_Passport_(wolverine)-brightgreen.svg)](devices/blackberry-passport-wolverine/profile.json)

> **OctoLab 经典设备 VoLTE 双项目导航（请按手机当前系统选择）：**
> - **高通 32 位 Android 11（LineageOS 18.1）机型 & 已刷 Android 11 的 BlackBerry Passport**：使用**本仓库 `qcom32-volte`**（官网：[`qcom32-volte.octoooo.com`](https://qcom32-volte.octoooo.com/)）。
> - **仍运行原厂 BlackBerry 10 系统（OS `10.3.3.3216`）的 Q10 / Classic Q20 / Passport / P’9983**：请前往姊妹开源项目 **[`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)**（官网：[`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/)）。

高通对骁龙 800 / 801（`MSM8974` 等 32 位 `armeabi-v7a` 平台）的官方支持停留在 Android 6.0。随着全球 2G/3G 退网，刷入 LineageOS 18.1（Android 11）的一代经典机型能驻留 4G 数据却失去了语音通话能力。

`qcom32-volte` 采用**“通用 32 位 Android 11 通话框架 + 按机型安全加锁的基带 EFS Profile”**双层解耦架构，从零实现手机本地独立运行的原生硬件 **4G VoLTE（AMR-WB 高清语音）** 通话栈——其中 **BlackBerry Passport 全系硬件版本（普通版 `wolverine` / 银色版 Silver Edition `oslo` / AT&T 圆角版 `wichita` / 保时捷版 `keian`）** 已完整开放一键激活、升级与回滚，同时在桌面端与手机 App 中内置**其他高通 32 位机型自动识别 + 100% 只读四门禁体检面板 + AI 配合 GitHub 仓库内置 Skill 自助对应适配指南**。

---

## 核心特性

- **通用 32 位 Android 11 `ImsService` 通话栈 (`Qcom32VolteIms.apk` v2.8)**：包名 `org.octolab.qcom32ims`，原生严守 **`SELinux Enforcing`**（全程零 `setenforce 0`、零自定义 sepolicy 补丁），运行于 `UID 1001 (radio)`、零 `<uses-permission>` 声明（零系统电话权限污染）与 `<protected-broadcast>` 显式广播白名单加固，完整对接高通 `/dev/socket/qmux_radio/rild_ims0` Protobuf 状态机，支持系统原生拨号器主被叫、DTMF、通话中 4G LTE 保持与 3GPP `CLIP→OIR` + `TOA=145` 来电号码正常显示。
- **ROM 遗留守护进程 / 基带兼容性写入前门禁 + 重启无信号自动回滚保护（v1.2.4+）**：自动在任何 EFS 写入前预检拦截旧版 `Permissive` ROM 遗留的 32 位 `imsqmidaemon` 冲突与非 `AAB982`（`AAA*` / BB10 `DI.2.0.1`）基带（零 EFS 改动）；若在未知定制 ROM 上重启后发生 `gsm.sim.state == ""` 无信号，桌面工具会自动执行出厂基带回滚并重启恢复手机信号。
- **黑莓 Passport 全系版本放开（含银色版 `oslo` / `SQW100-4`，v1.2.3+）**：完整覆盖 `wolverine`（普通版 `SQW100-1/2/3`）、**`oslo`（Passport Silver Edition 银色版 `SQW100-4`）**、`wichita`（AT&T 圆角版 `SQW100-2`）、`keian`（保时捷设计版）及统一 `wseries` 代号，共享同一套 `lineage_wseries` 与 `AAB982` `NON-HLOS.bin` 基带，支持一键备份激活、出厂回滚及中国电信 `46011` `fallbackmode=9` 防掉线保护。
- **内置高通 32 位多机型识别 + 只读体检面板 + AI 自助适配引导（v1.2.3+）**：Windows 桌面工具与手机端 `Qcom32VolteManager.apk` 均内置 9+ 大高通 32 位经典机系（三星 S5 `klte*`、小米 4/Note `cancro`/`virgo`、一加 1/Find 7 `bacon`/`find7`、索尼 Xperia Z1/Z2/Z3、LG G3/G2、HTC One M8、Moto X 2014 / Nexus 6、三星 Note 3/4 骁龙版等）自动识别与硬件陷阱拦截（自动提示 3G 版 `MI 4W`、猎户座 `SM-G900H`、无 `qipcall` 基带的 `Nexus 5`），提供零基带写入的 **4-Gate 只读适配体检**，并一键生成提示词引导用户通过 AI 编程助手 + 本 GitHub 仓库内置 Skill 安全自助完成新机型对应。
- **开机 / 换卡 / 待重启 / 射频异常自动系统状态通知与电信 EPS-only 防掉网（v1.2.0+）**：每次开机、插拔更换三网 SIM 卡或刚完成配置写入/回滚时，Android 11 系统通知栏会自动弹出中英文状态提示（如 **`VoLTE 已激活就绪 · 中国联通 / 中国电信`**、**`检测到已换卡 — 请重启手机激活 VoLTE`**、**`未驻留 4G 网络 · VoLTE 待机中 (中国移动)`**、**`VoLTE 配置已就绪 — 请重启手机生效`** 或 **`基带/SIM 未就绪 — 请运行回滚恢复信号`**），点按通知可直接打开管理面板；同时针对 Passport 全系（`wolverine`/`oslo`/`wichita`/`keian`）原厂 `libril.so` `fallback_thread` 在中国电信纯 4G (`EPS-only`) 卡下每 3 秒反复切网掉线的问题，自动对齐 `LTE_GSM_WCDMA (9)` 与 `persist.radio.fallbackmode=9`（换回联通/移动卡或回滚时自动还原原值）。
- **按机型隔离的基带 `EFSmodem` 供给、多机多卡兼容与确定性回滚 (`devices/*/profile.json`)**：只对通过硬件指纹校验的已建档机型开放 `/dev/diag` 基带写入（杜绝跨机型盲写 PDP 槽位破坏移动数据/彩信风险），兼容高通 `libqdp.so` 针对不同运营商数据 APN（`3gnet`/`cmnet`/`ctnet`/海外卡）或全新 `fsg` 恢复动态维护的 `/pdp_profiles/profile3` 与结构化 `profile1/2` 校验，强制先备份原值，支持一键 `--rollback-all` 100% 还原出厂基带基线。
- **免刷 Magisk + 拔掉 USB 换三网 SIM 卡零电脑自愈**：首次通过 LineageOS 原生「开发者选项 → Root 调试」激活后即可关闭 Root 调试并拔线独立运行；`CarrierConfigBootstrapReceiver` 自动监听新卡广播并为新 `subId` 绑定持久化 VoLTE 配置。
- **免 `su` 手机屏幕实时状态与多机型体检面板 (`Qcom32VolteManager.apk` v1.2.4)**：通过零隐私泄露的只读 `VolteStatusProvider` (`content://org.octolab.qcom32ims.status`)，无需安装 Magisk、无需连电脑即可在手机屏幕上实时查看三网/广电运营商、`rild_ims0`、`QMI_IMSA` 承载、机型识别与只读适配体检报告；若装有 Magisk (`su`) 更可机内直接激活或回滚。

---

## 四种使用方式与直接下载（`v1.2.4-r1`）

默认提供 **Cloudflare R2 高速直连**（国内无需代理即可满速下载）与 **GitHub Releases** 双镜像，SHA-256 校验码完全一致（汇总见 [`SHA256SUMS.txt`](https://qcom32-downloads.octoooo.com/v1.2.4-r1/SHA256SUMS.txt)）：

| 方式 | 适用人群 | 下载入口（Cloudflare R2 直连 / GitHub） | 使用简述 |
| --- | --- | --- | --- |
| **1. Windows 桌面一键版（最推荐）** | 绝大多数用户（**无需安装 Magisk**、无需装 Python/ADB） | [R2 下载 EXE](https://qcom32-downloads.octoooo.com/v1.2.4-r1/qcom32-volte-1.2.4-r1-windows-x64.exe) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.4-r1/qcom32-volte-1.2.4-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.4-r1/qcom32-volte-1.2.4-r1-windows-x64.exe.sha256) | Passport 全系（含银色版）一键备份激活；其他高通 32 位机型可点「多机型识别与只读体检」获取 AI 自助适配报告 |
| **2. 手机端状态与管理 App** | 拔线换卡实时看状态 / 多机型只读体检（**免 Magisk**）或 **Magisk** 机内管理 | [R2 下载 APK](https://qcom32-downloads.octoooo.com/v1.2.4-r1/Qcom32VolteManager-1.2.4-r1.apk) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.4-r1/Qcom32VolteManager-1.2.4-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.4-r1/Qcom32VolteManager-1.2.4-r1.apk.sha256) | 免 `su` 查看三网/IMS 状态与多机型只读四门禁体检 + AI 适配提示词；有 Magisk 可机内激活/回滚 |
| **3. 跨平台便携包（CLI + GUI）** | macOS / Linux / 命令行用户 | [R2 下载便携 ZIP](https://qcom32-downloads.octoooo.com/v1.2.4-r1/qcom32-volte-1.2.4-r1-portable.zip) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.4-r1/qcom32-volte-1.2.4-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.4-r1/qcom32-volte-1.2.4-r1-portable.zip.sha256) | 内含签名 ARM32 工具、双 APK 与 `devices/`，支持 `deploy` / `inspect-device` / `list-models` / `gui.py` |
| **4. LineageOS 18.1 ROM 源码集成** | 自定义 ROM 维护者与同代 32 位高通移植开发者 | [`rom-integration/`](rom-integration/README.md) · [系统签名通话栈 APK](https://qcom32-downloads.octoooo.com/v1.2.4-r1/Qcom32VolteIms-2.8-r1-platform.apk) · [源码 ZIP](https://qcom32-downloads.octoooo.com/v1.2.4-r1/qcom32-volte-1.2.4-r1-source.zip) | 通过 `Android.bp` + `device-qcom32-volte.mk` 直接编译进 `/system_ext/priv-app/` |

详细图文与命令行步骤请查阅 **[官网完整指南 (`qcom32-volte.octoooo.com/guide`)](https://qcom32-volte.octoooo.com/guide)** 或 **[`docs/guide/INSTALL_AND_ROLLBACK.md`](docs/guide/INSTALL_AND_ROLLBACK.md)**。

---

## 与参考项目 `qualcomm-volte-enabler` 的核心技术区别

本项目早期探索曾参考黑莓 KEY2 项目 [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler) 的 `/dev/diag` 思路，但因 32 位芯片架构与 Android 11 框架断代，最终全部自研实现（逐文件代码审计与真机对照详见 **[`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md)**）：

1. **芯片架构与系统层断代**：参考项目面向黑莓 KEY2（64 位 `SDM660`，原厂 Android 8.1 已内置高通现代闭源 `ims.apk`）；32 位 `MSM8974` 高通官方支持止步于 Android 6.0，LineageOS 18.1（Android 11）**完全没有 `ImsService`**。
2. **`/dev/diag` 内核驱动契约不兼容**：参考项目的 64 位预编译 `efs_Write` 在 32 位系统无法运行，且其指针式 `ioctl` 在 `MSM8974` 内核触发 `EINVAL (-22)`、`MEMORY_DEVICE_MODE` 会拦截 EFS 读写。本项目用 32 位纯 C 编写了 `pp_regconfig_provision_v6`（559 项单测断言），完整实现标量 `ioctl`、`sequence16+errno32` 响应头、CCITT CRC-16 与跨运营商动态 `profile3` 兼容校验。
3. **单改 `IMS_enable=01` 实测无效**：我们在真机上实测仅写 `IMS_enable=01` 仍报 `CAUSE_CODE 54 (READ_FAILED)`、无法注册；经解压 11.7MB Q6ZIP 基带固件逆向定位，必须协同供给 **54 项 Stage 6A EFS/DS 配置**（含补建 405B `qp_ims_reg_config`、改写 789B `IPV4V6` `/pdp_profiles/profile4`，并保持 `ims_hybrid_enable` 为 `ENOENT` 以走独立 DCM）。
4. **从零实现通用 Android 11 通话栈、开机/换卡系统通知与来电显示修复**：参考项目无任何 Android 代码；本项目完整实现 `Qcom32VolteIms.apk` v2.8（`UID 1001`、严守 `SELinux Enforcing`、零权限声明、持久化 `CarrierConfig`、`rild_ims0` Protobuf 状态机、3GPP `CLIP→OIR` 与 `TOA=145` 来电显示修正、开机/换卡/待重启/射频异常自动系统通知及只读 `VolteStatusProvider`）。
5. **免装 Magisk 与确定性回滚**：参考项目依赖 Magisk 且无备份/无回滚脚本；本项目支持免 Magisk 的原生 `adb root` 调试（另附免 `su` 状态面板/Magisk 伴侣 App），强制先备份原值并提供跨重启验证的 `--rollback-all`。

---

## 已验证机型、国内三网频段与其他 32 位高通旗舰适配

| 机型 / 运营商范围 | 状态 | 说明 |
| --- | --- | --- |
| **BlackBerry Passport 全系 (`wolverine` / 银色版 `oslo` / `wichita` / `keian`)** | **已完整放开一键激活** | 内置 [`devices/blackberry-passport-wolverine/profile.json`](devices/blackberry-passport-wolverine/profile.json)，覆盖普通版 `SQW100-1/3`、银色版 Silver Edition `SQW100-4` (`oslo`)、AT&T 圆角版 `SQW100-2` (`wichita`) 与保时捷版 `keian`；射频支持 **FDD-LTE B1/B2/B3/B4/B5/B7/B8/B13/B17/B20**（`SQW100-4` 另增 B28；不支持 TDD B38-41 与 CDMA 1x） |
| └─ **中国联通 (`46001`)** | **真机完整验收** | FDD B1/B3/B8 全覆盖，呼入呼出、双向 AMR-WB 语音、来电显示、LTE 保持、重启自愈与全部 4 种交付方式已实测通过 |
| └─ **中国电信 (`46003`/`46011`)** | **真机驻网与注册已验** | FDD B1/B3/B5/B8 覆盖下直接走动态 PCO VoLTE，已对 Passport 全系自动对齐 `libril.so` `fallbackmode=9` 彻底消除 EPS-only 反复无信号震荡（注：无 4G 覆盖时无 CDMA 1x 回落） |
| └─ **中国移动 (`46000`/`46002`/`46007`)** | **FDD B3/B8 区域可测** | 覆盖移动 FDD B3/B8 区域可测 VoLTE；纯 TDD B38-41 区域自动回落 2G GSM 语音 |
| **其他高通 32 位 (`MSM8974`/`APQ8084`) 旗舰** | **内置识别 + 只读体检 + AI 自助适配** | 三星 S5 (`klte*`)、小米 4/Note (`cancro`/`virgo`)、一加 1 (`bacon`)、索尼 Xperia Z1/Z2/Z3、LG G3/G2、HTC One M8、Moto X 2014 / Nexus 6 等在桌面端与手机 App 中均内置 **100% 只读四门禁体检面板**，可一键复制报告配合 AI 助手 + 本仓库内置 Skill 自助完成适配（详见 **[`devices/README.md`](devices/README.md)** 与 **[`docs/guide/MSM8974_PORTING_GUIDE.md`](docs/guide/MSM8974_PORTING_GUIDE.md)**） |

---

## AI 助手一键识别与多机型适配（内置 Multi-Agent Skill）

仓库已内置面向 **Antigravity、Claude Code、Codex、Cursor、Gemini CLI** 等主流 AI 编程助手的专用技能包（[`.agents/skills/qcom32-volte/`](.agents/skills/qcom32-volte/SKILL.md) 与 [`.claude/skills/qcom32-volte/`](.claude/skills/qcom32-volte/SKILL.md)）。其他用户或开发者使用 AI 打开本项目时，AI 会自动加载本项目的安全红线与机型工作流：

- **插线自动只读识别与路由**：执行 `python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py inspect` 自动检测 ADB / Fastboot 状态、SoC 平台、Android 版本、ROM `test-keys`/`release-keys` 签名兼容性、`rild_ims0`、音频 `Voice-MMode1` 通路与基带符号，自动分流至一键激活、APK 升级、刷机前置准备或新机移植流程（自动脱敏序列号/IMEI，绝不跨机型盲写 EFS）。
- **9 大经典机系子型号避坑查询**：执行 `python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py catalog --query <型号或代号>` 秒查三星 S5 (`klte`)、小米 4 (`cancro`)、LG G3 (`d855`)、一加 1 (`bacon`)、索尼 Z1/Z2/Z3、HTC M8、Nexus 6 等机型的推荐 ROM、刷机入口与硬件锁死变体（自动拦截联通 3G 版 `MI 4W`、猎户座 `SM-G900H`、美版锁 BL `SM-G900A` 与无 `qipcall` 基带的 `Nexus 5`）。
- **4-Gate 新机只读 EFS 移植辅助**：内置 789 字节 `/pdp_profiles/profile*` 解码器（`decode-pdp`）与 `devices/<id>/profile.json` 脚手架/校验器（`scaffold-profile` / `validate-profile`），严防覆盖非 IMS 数据或彩信槽位。

---

## 仓库目录结构

```text
├── .agents/skills/qcom32-volte/     # 【内置 AI 技能】机型自动识别、安全部署与 4-Gate 移植助手（兼容 Claude/.claude）
├── devices/                         # 【设备 Profile 注册表】按机型隔离基带 EFS 槽位与安全门禁
│   └── blackberry-passport-wolverine/profile.json
├── src/                             # 【正式生产源码】100% 从源码编译验证，零预编译黑盒
│   ├── android-ims-service/         # Qcom32VolteIms.apk (通用 32 位 Android 11 ImsService + 状态通知, v2.8)
│   ├── android-volte-manager/       # Qcom32VolteManager.apk (免 su 实时状态/多机型只读体检 & Magisk 管理 App, v1.2.4)
│   ├── modem-efs-provisioner/       # pp_regconfig_provision_v6 (ARM32 DIAG/EFS2 供给与回滚器)
│   └── qmi-imsa-probe/              # libimsa_probe.so (ARM32 QMI_IMSA/IMSS 只读状态自检探针)
├── cli/                             # 【桌面端、跨平台 CLI 与打包工具】
│   ├── device_readiness.py          # 高通 32 位多机型目录、100% 只读四门禁体检与 AI+GitHub 适配提示词引擎
│   ├── volte_ctl.py                 # 零依赖 CLI：status / inspect-device / list-models / deploy / upgrade-apk / rollback
│   ├── gui.py                       # OctoLab 双语桌面图形界面（含多机型只读体检面板、--cli 与 Windows EXE）
│   ├── build_all.py                 # 宿主机全套 C/Java 单元测试与交叉编译签名
│   ├── build_windows_exe.py         # PyInstaller 单文件 Windows x64 EXE 打包与离线自检
│   └── export_opensource_bundle.py  # 导出纯净公开仓库与全套 Release 发行包 + SHA256SUMS
├── rom-integration/                 # 【通用 ROM 源码集成套件】Android.bp 与 device-qcom32-volte.mk
├── website/                         # 【双语官方网站源码】qcom32-volte.octoooo.com (Cloudflare Workers)
├── docs/guide/                      # 【正式技术文档】架构、安装回滚、参考项目对比、换卡与 32 位移植指南
├── archive/                         # 【研发归档】八大核心踩坑复盘 (LESSONS_LEARNED.md) 与历史目录索引
├── COMMERCIAL-LICENSE.md            # 【商业授权声明】个人免费边界与商业代刷/预刷售机付费授权说明
└── LICENSE                          # PolyForm Noncommercial License 1.0.0 + OctoLab 商业双重许可
```

---

## 从源码编译与自检

```bash
# 1. 运行宿主机全套 C + Java + CLI + AI Skill 单元测试（无需连接手机或交叉工具链）
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py self-test

# 2. 交叉编译全部 ARM32 工具与签名双 APK 至 build/release/
python3 cli/build_all.py

# 3. 构建并校验官方网站静态产物
cd website && npm test
```

---

## 许可协议与商业授权声明（个人非商用免费 · 商业用途需付费授权）

本项目由 **OctoLab (`Octo-o-o-o`)** 采用**双重许可（Dual-Licensing）**发布（详见 [`LICENSE`](LICENSE) 与 [`COMMERCIAL-LICENSE.md`](COMMERCIAL-LICENSE.md)）：

1. **个人玩家与纯公益社区免费**：个人机友在自有设备上使用、研究学习、提交新机型适配，以及**100% 免费公开、无任何收费/进群/打赏门槛的纯公益第三方社区 ROM 集成**（保留署名与相同非商用协议），均免费适用 **[PolyForm Noncommercial License 1.0.0](LICENSE)**。
2. **商业用途必须事先获得付费授权**：未经 **OctoLab** 书面付费授权，**严禁将本项目的代码、EXE/APK 发行包、基带配置或教程用于任何商业营利行为**，包括但不限于：
   - 在闲鱼、淘宝、转转、拼多多、维修档口等渠道开展**有偿代刷 VoLTE、远程激活、收费技术服务或倒卖安装包/教程**；
   - 二手机商或翻新商**售卖预刷本方案或以 4G VoLTE 通话为卖点的整机/主板**（含“买二手机免费送刷 VoLTE”等捆绑销售）；
   - 合入收费 ROM、付费群解锁包或商业刷机工具箱。
3. **商业合作与授权联系邮箱**：[`octoooo@octoooo.com`](mailto:octoooo@octoooo.com)

---

## 致谢与合规声明

- 早期 `/dev/diag` 探索受 [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler) 启发，谨致谢意。
- 姊妹项目：原厂 BlackBerry 10 系统 VoLTE 方案 [`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)（[`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/)）。
- 本项目不包含任何闭源基带固件镜像、私钥或任何设备的 IMEI / IMSI / ICCID / 手机号个人身份数据；不承诺紧急呼叫（110/119/120/911）认证。
