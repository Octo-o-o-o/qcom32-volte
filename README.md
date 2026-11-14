# BlackBerry Passport Android 11 Native VoLTE (`passport-android11-volte`)

**简体中文** · **[English (`README.en.md`)](README.en.md)** · **[官方网站 (`passport-volte.octoooo.com`)](https://passport-volte.octoooo.com/)** · **[完整使用指南](https://passport-volte.octoooo.com/guide)** · **[Releases 下载](https://github.com/Octo-o-o-o/passport-android11-volte/releases)**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Device](https://img.shields.io/badge/Device-BlackBerry_Passport_(wolverine)-black.svg)](docs/guide/ARCHITECTURE.md)
[![OS](https://img.shields.io/badge/Android-11_(LineageOS_18.1)-2e654d.svg)](docs/guide/INSTALL_AND_ROLLBACK.md)
[![VoLTE](https://img.shields.io/badge/VoLTE-Verified_Working_(AMR--WB_HD)-brightgreen.svg)](https://passport-volte.octoooo.com/)

> **OctoLab 黑莓 VoLTE 双项目导航（请按手机当前系统选择）：**
> - **当前手机已刷 Android 11（LineageOS 18.1）的 BlackBerry Passport**：使用**本仓库**（[`passport-volte.octoooo.com`](https://passport-volte.octoooo.com/)）。
> - **当前手机仍运行原厂 BlackBerry 10 系统（OS `10.3.3.3216`）的 Q10 / Classic Q20 / Passport / P’9983**：请前往姊妹开源项目 **[`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)**（官网：[`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/)）。

为运行 **LineageOS 18.1 (Android 11, API 30)** 的 **BlackBerry Passport (`MSM8974` / `wolverine`)** 提供**手机本地独立运行的原生硬件 4G VoLTE 高清语音方案**。

本方案同时补齐了高通骁龙 801 基带的 **Stage 6A 独立 DCM EFS 配置**（54 项 EFS/DS 条目 + 789B `IPV4V6` `profile4`）与 Android 11 缺失的系统级 **`ImsService` (`PassportVolteIms.apk` v2.3)**——**无需刷入 Magisk、不刷改内核/基带镜像、保持 `SELinux=Enforcing`、继续使用系统原生拨号器、来电显示正常、支持一键完整恢复出厂基带状态**。

---

## 核心特性（已真机完整验收）

- **真实基带 SIP 注册与双栈承载**：采用 Stage 6A 独立基带 DCM 模式（`mode=0` 动态 PCO 发现 + USIM 自动推导归属域，零硬编码运营商 P-CSCF IP），开机自动完成 IMS 注册。
- **系统原生拨号盘无缝接打 + 正常来电显示**：直接用系统自带电话应用呼出、振铃接听、挂断与 DTMF，双向 **AMR-WB 高清语音**；修正 3GPP `CLIP` 与 Android `ImsCallProfile.OIR` 极性映射及 `+86` (`TOA=145`) 号码规整，彻底解决来电显示“未知号码”问题。
- **通话期间 4G LTE 不断网**：语音走基带独立 IMS 承载 (`profile4`)，日常移动数据 (`rmnet0`) 全程保持 `LTE` 在线不回落。
- **免装 Magisk、拔掉 USB 跨重启自恢复**：首次通过 LineageOS 自带的「开发者选项 → Root 调试」部署后即可拔掉数据线，日常开关机/重启 100% 由手机本地独立恢复 VoLTE。
- **先备份再写入、一键无痕回滚**：每次写入前自动在本地保存基带原始快照；随时可一键还原全部 54 项基带条目至出厂基线并卸载 APK。

---

## 四种使用方式与直接下载（`v1.0.0-r1`）

默认提供 **Cloudflare R2 高速直连**（国内无需代理即可满速下载）与 **GitHub Releases** 双镜像，SHA-256 校验码完全一致（汇总见 [`SHA256SUMS.txt`](https://passport-downloads.octoooo.com/v1.0.0-r1/SHA256SUMS.txt)）：

| 方式 | 适用人群 | 下载入口（Cloudflare R2 直连 / GitHub） | 使用简述 |
| --- | --- | --- | --- |
| **1. Windows 桌面一键版（最推荐）** | 绝大多数用户（**无需安装 Magisk**、无需装 Python/ADB） | [R2 下载 EXE](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-windows-x64.exe) · [GitHub](https://github.com/Octo-o-o-o/passport-android11-volte/releases/download/v1.0.0-r1/passport-android11-volte-1.0.0-r1-windows-x64.exe) · [SHA-256](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-windows-x64.exe.sha256) | 手机开启「USB 调试 + Root 调试」，双击 EXE 点击「备份并一键激活 VoLTE」即可 |
| **2. 手机端独立管理 App** | 已刷入 **Magisk**（有本机 `su`）或需在手机屏幕只读查看状态的用户 | [R2 下载 APK](https://passport-downloads.octoooo.com/v1.0.0-r1/PassportVolteManager-1.0.0-r1.apk) · [GitHub](https://github.com/Octo-o-o-o/passport-android11-volte/releases/download/v1.0.0-r1/PassportVolteManager-1.0.0-r1.apk) · [SHA-256](https://passport-downloads.octoooo.com/v1.0.0-r1/PassportVolteManager-1.0.0-r1.apk.sha256) | 安装至手机桌面；无 Root 可查看 IMS/`QMI_IMSA` 状态，有 Magisk 可机内一键激活/回滚 |
| **3. 跨平台便携包（CLI + GUI）** | macOS / Linux / 命令行用户 | [R2 下载便携 ZIP](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-portable.zip) · [GitHub](https://github.com/Octo-o-o-o/passport-android11-volte/releases/download/v1.0.0-r1/passport-android11-volte-1.0.0-r1-portable.zip) · [SHA-256](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-portable.zip.sha256) | 内含已编译签名的 ARM32 工具与 APK，运行 `python3 cli/volte_ctl.py deploy` 或 `cli/gui.py` |
| **4. LineageOS 18.1 ROM 源码集成** | 自定义 ROM 维护者与同代骁龙 801 移植极客 | [`rom-integration/`](rom-integration/README.md) · [系统签名桥接 APK](https://passport-downloads.octoooo.com/v1.0.0-r1/PassportVolteIms-2.3-r1-platform.apk) · [源码 ZIP](https://passport-downloads.octoooo.com/v1.0.0-r1/passport-android11-volte-1.0.0-r1-source.zip) | 通过 `Android.bp` + `device-passport-volte.mk` 直接编译进 `/system/priv-app/` |

详细图文与命令行步骤请查阅 **[官网完整指南 (`passport-volte.octoooo.com/guide`)](https://passport-volte.octoooo.com/guide)** 或 **[`docs/guide/INSTALL_AND_ROLLBACK.md`](docs/guide/INSTALL_AND_ROLLBACK.md)**。

---

## 与参考项目 `qualcomm-volte-enabler` 的核心技术区别

本项目早期探索曾参考黑莓 KEY2 项目 [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler) 的 `/dev/diag` 思路，但因硬件代际与系统断代差异，最终全部自研实现（逐文件代码审计与真机对照详见 **[`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md)**）：

1. **芯片架构与系统层断代**：参考项目面向黑莓 KEY2（64 位 `SDM660`，原厂 Android 8.1 已内置高通现代 `ims.apk`）；Passport 为 32 位 `MSM8974`，高通官方支持止步于 Android 6.0，LineageOS 18.1（Android 11）**完全没有 `ImsService`**。
2. **`/dev/diag` 内核驱动契约不兼容**：参考项目的 64 位预编译 `efs_Write` 在 32 位系统无法运行，且其指针式 `ioctl` 在 `MSM8974` 内核触发 `EINVAL (-22)`、`MEMORY_DEVICE_MODE` 会拦截 EFS 读写。本项目用 32 位纯 C 编写了 `pp_regconfig_provision_v6`（549 项单测断言），完整实现标量 `ioctl`、`sequence16+errno32` 响应头与 CCITT CRC-16 校验。
3. **单改 `IMS_enable=01` 在 Passport 上实测无效**：我们在真机上实测仅写 `IMS_enable=01` 仍报 `CAUSE_CODE 54 (READ_FAILED)`、无法注册；经解压 11.7MB Q6ZIP 基带固件逆向定位，必须协同供给 **54 项 Stage 6A EFS/DS 配置**（含补建 405B `qp_ims_reg_config`、改写 789B `IPV4V6` `/pdp_profiles/profile4`，并保持 `ims_hybrid_enable` 为 `ENOENT` 以走独立 DCM）。
4. **从零实现 Android 11 通话栈与来电显示修复**：参考项目无任何 Android 代码；本项目完整实现 `PassportVolteIms.apk` v2.3（`UID 1001`、零权限声明、持久化 `CarrierConfig`、`rild_ims0` Protobuf 状态机、3GPP `CLIP→OIR` 与 `TOA=145` 来电显示修正）。
5. **免装 Magisk 与确定性回滚**：参考项目依赖 Magisk 且无备份/无回滚脚本；本项目支持免 Magisk 的原生 `adb root` 调试（另附可选手机管理 App），强制先备份原值并提供跨重启验证的 `--rollback-all`。

---

## 硬件频段、换卡与其他骁龙 801 老旗舰移植说明

BlackBerry Passport (`SQW100-1`) 硬件支持 **FDD-LTE Band 1/2/3/4/5/7/8/13/17/20**（物理射频不支持 TDD-LTE B38/39/40/41 及 CDMA 1x）。详见 **[产品形态与换卡分析 (`docs/guide/PRODUCT_FORM_AND_SIM_SWAP.md`)](docs/guide/PRODUCT_FORM_AND_SIM_SWAP.md)**：

| 运营商 / 平台 | 状态 | 说明 |
| --- | --- | --- |
| **中国联通 (`46001`)** | **真机完整验收** | FDD B1/B3/B8 全覆盖，呼入呼出、双向 AMR-WB 语音、来电显示、LTE 保持与重启自恢复已完整验证 |
| **中国电信 (`46003`/`46011`)** | **FDD 区域可测** | FDD B1/B3/B5/B8 覆盖下可走动态 PCO VoLTE（无 4G 时无 CDMA 1x 回落） |
| **中国移动 (`46000`)** | **FDD B3/B8 区域可测** | 覆盖移动 FDD B3/B8 区域可测 VoLTE；纯 TDD 区域自动回落 2G GSM 语音 |
| **海外主流 VoLTE 运营商** | **标准兼容可测** | Stage 6A 采用标准 `ims` APN 与 `mode=0` 动态 PCO，零硬编码运营商 IP |
| **同代骁龙 801 (`MSM8974`) 旗舰** | **提供完整移植指南** | 一加 1 (`bacon`)、索尼 Xperia Z2/Z3、三星 S5 (`klte`)、小米 4/Note (`cancro`)、LG G3、HTC One M8 等详见 **[`docs/guide/MSM8974_PORTING_GUIDE.md`](docs/guide/MSM8974_PORTING_GUIDE.md)** |

> **日常换 SIM 卡提示**：基带 Stage 6A 配置跟手机走，换卡不丢失；换入新 SIM 卡后，只需执行一次 `python3 cli/volte_ctl.py upgrade-apk`（或在桌面工具/手机管理 App 点击升级/重刷 `CarrierConfig`），即可为新卡 `subId` 绑定持久化 VoLTE 开关。

---

## 仓库目录结构

```text
├── src/                             # 【正式生产源码】100% 从源码编译验证，零预编译黑盒
│   ├── android-ims-service/         # PassportVolteIms.apk (Android 11 ImsService + MmTelFeature, v2.3)
│   ├── android-volte-manager/       # PassportVolteManager.apk (手机端状态查看与 Magisk 伴侣管理 App)
│   ├── modem-efs-provisioner/       # pp_regconfig_provision_v6 (ARM32 DIAG/EFS2 Stage 6A 供给与回滚器)
│   └── qmi-imsa-probe/              # libimsa_probe.so (ARM32 QMI_IMSA/IMSS 只读状态自检探针)
├── cli/                             # 【桌面端、跨平台 CLI 与打包工具】
│   ├── volte_ctl.py                 # 零依赖 CLI：status / deploy / upgrade-apk / install-manager / rollback
│   ├── gui.py                       # OctoLab 双语桌面图形界面（支持源码运行与 Windows EXE）
│   ├── build_all.py                 # 宿主机全套 C/Java 单元测试 (699 项断言) 与交叉编译签名
│   ├── build_windows_exe.py         # PyInstaller 单文件 Windows x64 EXE 打包与离线自检
│   └── export_opensource_bundle.py  # 导出纯净公开仓库与全套 Release 发行包 + SHA256SUMS
├── rom-integration/                 # 【ROM 源码集成套件】LineageOS 18.1 Android.bp 与 device.mk
├── website/                         # 【双语官方网站源码】passport-volte.octoooo.com (Cloudflare Pages)
├── docs/guide/                      # 【正式技术文档】
│   ├── ARCHITECTURE.md              # 端到端系统架构、协议栈与安全边界详解
│   ├── INSTALL_AND_ROLLBACK.md      # 编译、四种激活方式、状态检查与无痕回滚指南
│   ├── COMPARISON_QUALCOMM_VOLTE_ENABLER.md # 与 qualcomm-volte-enabler 的逐层代码与实测对比
│   ├── MSM8974_PORTING_GUIDE.md     # 同代骁龙 801 老旗舰 Android 11 VoLTE 移植指南
│   └── PRODUCT_FORM_AND_SIM_SWAP.md # 产品分发形态选型与换卡指南
├── archive/                         # 【研发归档】八大核心踩坑复盘 (LESSONS_LEARNED.md) 与历史目录索引
└── LICENSE                          # Apache License 2.0
```

---

## 从源码编译与自检

```bash
# 1. 仅运行宿主机全套 C + Java 单元测试（无需连接手机或交叉工具链）
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test

# 2. 交叉编译全部 ARM32 工具与签名双 APK 至 build/release/
python3 cli/build_all.py

# 3. 构建并校验官方网站静态产物
cd website && npm test
```

---

## 致谢与合规声明

- 早期 `/dev/diag` 探索受 [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler) 启发，谨致谢意。
- 姊妹项目：原厂 BlackBerry 10 系统 VoLTE 方案 [`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)（[`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/)）。
- 本项目不包含任何闭源基带固件镜像、私钥或任何设备的 IMEI / IMSI / ICCID / 手机号个人身份数据；不承诺紧急呼叫（110/119/120/911）认证。
