# Qcom32 VoLTE — Clean-Room 4G VoLTE & Android 11 `ImsService` Stack for 32-Bit Qualcomm Platforms (`qcom32-volte`)

**简体中文** · **[English (`README.en.md`)](README.en.md)** · **[官方网站 (`qcom32-volte.octoooo.com`)](https://qcom32-volte.octoooo.com/)** · **[完整使用指南](https://qcom32-volte.octoooo.com/guide)** · **[已验证设备库 (`devices/`)](devices/README.md)** · **[Releases 下载](https://github.com/Octo-o-o-o/qcom32-volte/releases)**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Qualcomm_32--bit_(MSM8974)-black.svg)](docs/guide/ARCHITECTURE.md)
[![OS](https://img.shields.io/badge/Android-11_(LineageOS_18.1)-2e654d.svg)](docs/guide/INSTALL_AND_ROLLBACK.md)
[![Verified](https://img.shields.io/badge/First_Verified_Profile-BlackBerry_Passport_(wolverine)-brightgreen.svg)](devices/blackberry-passport-wolverine/profile.json)

> **OctoLab 经典设备 VoLTE 双项目导航（请按手机当前系统选择）：**
> - **高通 32 位 Android 11（LineageOS 18.1）机型 & 已刷 Android 11 的 BlackBerry Passport**：使用**本仓库 `qcom32-volte`**（官网：[`qcom32-volte.octoooo.com`](https://qcom32-volte.octoooo.com/)）。
> - **仍运行原厂 BlackBerry 10 系统（OS `10.3.3.3216`）的 Q10 / Classic Q20 / Passport / P’9983**：请前往姊妹开源项目 **[`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte)**（官网：[`bb10-volte.octoooo.com`](https://bb10-volte.octoooo.com/)）。

高通对骁龙 800 / 801（`MSM8974` 等 32 位 `armeabi-v7a` 平台）的官方支持停留在 Android 6.0。随着全球 2G/3G 退网，刷入 LineageOS 18.1（Android 11）的一代经典机型能驻留 4G 数据却失去了语音通话能力。

**`qcom32-volte`** 采用**“通用 32 位 Android 11 通话框架 + 按机型安全加锁的基带 EFS Profile”**双层解耦架构，从零实现手机本地独立运行的原生硬件 **4G VoLTE（AMR-WB 高清语音）** 通话栈——其中 **BlackBerry Passport (`blackberry-passport-wolverine`)** 已作为首个设备 Profile 完成全功能真机验收与全部 4 种交付方式实机闭环测试。

---

## 核心特性

- **通用 32 位 Android 11 `ImsService` 通话栈 (`Qcom32VolteIms.apk` v2.5)**：包名 `org.octolab.qcom32ims`，运行于 `UID 1001 (radio)`、零 `<uses-permission>` 声明（零系统电话权限污染），完整对接高通 `/dev/socket/qmux_radio/rild_ims0` Protobuf 状态机，支持系统原生拨号器主被叫、DTMF、通话中 4G LTE 保持与 3GPP `CLIP→OIR` + `TOA=145` 来电号码正常显示。
- **开机 / 换卡 / 待重启自动系统状态通知与电信 EPS-only 防掉网（v1.2.0 新增）**：每次开机、插拔更换三网 SIM 卡或刚完成配置写入/回滚时，Android 11 系统通知栏会自动弹出中英文状态提示（如 **`VoLTE 已激活就绪 · 中国联通 / 中国电信`**、**`检测到已换卡 — 请重启手机激活 VoLTE`**、**`未驻留 4G 网络 · VoLTE 待机中 (中国移动)`** 或 **`VoLTE 配置已就绪 — 请重启手机生效`**），点按通知可直接打开管理面板；同时针对 Passport (`wolverine`) 原厂 `libril.so` `fallback_thread` 在中国电信纯 4G (`EPS-only`) 卡下每 3 秒反复切网掉线的问题，自动对齐 `LTE_GSM_WCDMA (9)` 与 `persist.radio.fallbackmode=9`（换回联通/移动卡或回滚时自动还原原值）。
- **按机型隔离的基带 EFS 供给与确定性回滚 (`devices/*/profile.json`)**：只对通过硬件指纹校验的已建档机型开放 `/dev/diag` 基带写入（杜绝跨机型盲写 PDP 槽位风险），强制先备份原值，支持一键 `--rollback-all` 100% 还原出厂基带基线。
- **免刷 Magisk + 拔掉 USB 换三网 SIM 卡零电脑自愈**：首次通过 LineageOS 原生「开发者选项 → Root 调试」激活即可拔线独立运行；`CarrierConfigBootstrapReceiver` 自动监听新卡广播并为新 `subId` 绑定持久化 VoLTE 配置。
- **免 `su` 手机屏幕实时状态面板 (`Qcom32VolteManager.apk` v1.2.0)**：通过零隐私泄露的只读 `VolteStatusProvider` (`content://org.octolab.qcom32ims.status`)，无需安装 Magisk、无需连电脑即可在手机屏幕上实时查看三网运营商、`rild_ims0`、`QMI_IMSA` 承载与框架注册状态；若装有 Magisk (`su`) 更可机内直接激活或回滚。

---

## 四种使用方式与直接下载（`v1.2.0-r1`）

默认提供 **Cloudflare R2 高速直连**（国内无需代理即可满速下载）与 **GitHub Releases** 双镜像，SHA-256 校验码完全一致（汇总见 [`SHA256SUMS.txt`](https://qcom32-downloads.octoooo.com/v1.2.0-r1/SHA256SUMS.txt)）：

| 方式 | 适用人群 | 下载入口（Cloudflare R2 直连 / GitHub） | 使用简述 |
| --- | --- | --- | --- |
| **1. Windows 桌面一键版（最推荐）** | 绝大多数用户（**无需安装 Magisk**、无需装 Python/ADB） | [R2 下载 EXE](https://qcom32-downloads.octoooo.com/v1.2.0-r1/qcom32-volte-1.2.0-r1-windows-x64.exe) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.0-r1/qcom32-volte-1.2.0-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.0-r1/qcom32-volte-1.2.0-r1-windows-x64.exe.sha256) | 手机开启「USB 调试 + Root 调试」，双击 EXE 点击「备份并一键激活 VoLTE」即可 |
| **2. 手机端状态与管理 App** | 拔线换卡实时看状态（**免 Magisk**）或已刷 **Magisk** 机内管理用户 | [R2 下载 APK](https://qcom32-downloads.octoooo.com/v1.2.0-r1/Qcom32VolteManager-1.2.0-r1.apk) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.0-r1/Qcom32VolteManager-1.2.0-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.0-r1/Qcom32VolteManager-1.2.0-r1.apk.sha256) | 安装至手机桌面；免 `su` 实时查看三网/IMS/`QMI_IMSA` 状态，有 Magisk 可机内激活/回滚 |
| **3. 跨平台便携包（CLI + GUI）** | macOS / Linux / 命令行用户 | [R2 下载便携 ZIP](https://qcom32-downloads.octoooo.com/v1.2.0-r1/qcom32-volte-1.2.0-r1-portable.zip) · [GitHub](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.0-r1/qcom32-volte-1.2.0-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.0-r1/qcom32-volte-1.2.0-r1-portable.zip.sha256) | 内含签名 ARM32 工具、双 APK 与 `devices/`，运行 `python3 cli/volte_ctl.py deploy` 或 `cli/gui.py` |
| **4. LineageOS 18.1 ROM 源码集成** | 自定义 ROM 维护者与同代 32 位高通移植开发者 | [`rom-integration/`](rom-integration/README.md) · [系统签名通话栈 APK](https://qcom32-downloads.octoooo.com/v1.2.0-r1/Qcom32VolteIms-2.5-r1-platform.apk) · [源码 ZIP](https://qcom32-downloads.octoooo.com/v1.2.0-r1/qcom32-volte-1.2.0-r1-source.zip) | 通过 `Android.bp` + `device-qcom32-volte.mk` 直接编译进 `/system/priv-app/` |

详细图文与命令行步骤请查阅 **[官网完整指南 (`qcom32-volte.octoooo.com/guide`)](https://qcom32-volte.octoooo.com/guide)** 或 **[`docs/guide/INSTALL_AND_ROLLBACK.md`](docs/guide/INSTALL_AND_ROLLBACK.md)**。

---

## 与参考项目 `qualcomm-volte-enabler` 的核心技术区别

本项目早期探索曾参考黑莓 KEY2 项目 [`ikimi615/qualcomm-volte-enabler`](https://github.com/ikimi615/qualcomm-volte-enabler) 的 `/dev/diag` 思路，但因 32 位芯片架构与 Android 11 框架断代，最终全部自研实现（逐文件代码审计与真机对照详见 **[`docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md`](docs/guide/COMPARISON_QUALCOMM_VOLTE_ENABLER.md)**）：

1. **芯片架构与系统层断代**：参考项目面向黑莓 KEY2（64 位 `SDM660`，原厂 Android 8.1 已内置高通现代闭源 `ims.apk`）；32 位 `MSM8974` 高通官方支持止步于 Android 6.0，LineageOS 18.1（Android 11）**完全没有 `ImsService`**。
2. **`/dev/diag` 内核驱动契约不兼容**：参考项目的 64 位预编译 `efs_Write` 在 32 位系统无法运行，且其指针式 `ioctl` 在 `MSM8974` 内核触发 `EINVAL (-22)`、`MEMORY_DEVICE_MODE` 会拦截 EFS 读写。本项目用 32 位纯 C 编写了 `pp_regconfig_provision_v6`（549 项单测断言），完整实现标量 `ioctl`、`sequence16+errno32` 响应头与 CCITT CRC-16 校验。
3. **单改 `IMS_enable=01` 实测无效**：我们在真机上实测仅写 `IMS_enable=01` 仍报 `CAUSE_CODE 54 (READ_FAILED)`、无法注册；经解压 11.7MB Q6ZIP 基带固件逆向定位，必须协同供给 **54 项 Stage 6A EFS/DS 配置**（含补建 405B `qp_ims_reg_config`、改写 789B `IPV4V6` `/pdp_profiles/profile4`，并保持 `ims_hybrid_enable` 为 `ENOENT` 以走独立 DCM）。
4. **从零实现通用 Android 11 通话栈、开机/换卡系统通知与来电显示修复**：参考项目无任何 Android 代码；本项目完整实现 `Qcom32VolteIms.apk` v2.5（`UID 1001`、零权限声明、持久化 `CarrierConfig`、`rild_ims0` Protobuf 状态机、3GPP `CLIP→OIR` 与 `TOA=145` 来电显示修正、开机/换卡/待重启自动系统通知及只读 `VolteStatusProvider`）。
5. **免装 Magisk 与确定性回滚**：参考项目依赖 Magisk 且无备份/无回滚脚本；本项目支持免 Magisk 的原生 `adb root` 调试（另附免 `su` 状态面板/Magisk 伴侣 App），强制先备份原值并提供跨重启验证的 `--rollback-all`。

---

## 已验证机型、国内三网频段与其他 32 位高通旗舰适配

| 机型 / 运营商范围 | 状态 | 说明 |
| --- | --- | --- |
| **BlackBerry Passport (`wolverine`)** | **首个完整验证 Profile** | 内置 [`devices/blackberry-passport-wolverine/profile.json`](devices/blackberry-passport-wolverine/profile.json)；注意 `SQW100-1` 硬件射频支持 **FDD-LTE B1/B2/B3/B4/B5/B7/B8/B13/B17/B20**（不支持 TDD B38-41 与 CDMA 1x） |
| └─ **中国联通 (`46001`)** | **真机完整验收** | FDD B1/B3/B8 全覆盖，呼入呼出、双向 AMR-WB 语音、来电显示、LTE 保持、重启自愈与全部 4 种交付方式已实测通过 |
| └─ **中国电信 (`46003`/`46011`)** | **真机驻网与注册已验** | FDD B1/B3/B5/B8 覆盖下直接走动态 PCO VoLTE，v1.2.0 已自动对齐 `wolverine` `libril.so` `fallbackmode=9` 彻底消除 EPS-only 反复无信号震荡（注：无 4G 覆盖时无 CDMA 1x 回落） |
| └─ **中国移动 (`46000`/`46002`/`46007`)** | **FDD B3/B8 区域可测** | 覆盖移动 FDD B3/B8 区域可测 VoLTE；纯 TDD B38-41 区域自动回落 2G GSM 语音 |
| **同代高通 32 位 (`MSM8974`) 旗舰** | **通用栈就绪 · 支持建档** | 一加 1 (`bacon`)、小米 4/Note (`cancro`)、索尼 Xperia Z1/Z2/Z3、三星 S5 (`klte`)、LG G2/G3、HTC One M8 等可直接复用 `Qcom32VolteIms.apk`，新增机型 Profile 指南见 **[`devices/README.md`](devices/README.md)** 与 **[`docs/guide/MSM8974_PORTING_GUIDE.md`](docs/guide/MSM8974_PORTING_GUIDE.md)** |

---

## 仓库目录结构

```text
├── devices/                         # 【设备 Profile 注册表】按机型隔离基带 EFS 槽位与安全门禁
│   └── blackberry-passport-wolverine/profile.json
├── src/                             # 【正式生产源码】100% 从源码编译验证，零预编译黑盒
│   ├── android-ims-service/         # Qcom32VolteIms.apk (通用 32 位 Android 11 ImsService + 状态通知, v2.5)
│   ├── android-volte-manager/       # Qcom32VolteManager.apk (免 su 实时状态面板 & Magisk 伴侣 App, v1.2.0)
│   ├── modem-efs-provisioner/       # pp_regconfig_provision_v6 (ARM32 DIAG/EFS2 供给与回滚器)
│   └── qmi-imsa-probe/              # libimsa_probe.so (ARM32 QMI_IMSA/IMSS 只读状态自检探针)
├── cli/                             # 【桌面端、跨平台 CLI 与打包工具】
│   ├── volte_ctl.py                 # 零依赖 CLI：status / deploy / upgrade-apk / install-manager / rollback
│   ├── gui.py                       # OctoLab 双语桌面图形界面（支持源码运行、--cli 与 Windows EXE）
│   ├── build_all.py                 # 宿主机全套 C/Java 单元测试与交叉编译签名
│   ├── build_windows_exe.py         # PyInstaller 单文件 Windows x64 EXE 打包与离线自检
│   └── export_opensource_bundle.py  # 导出纯净公开仓库与全套 Release 发行包 + SHA256SUMS
├── rom-integration/                 # 【通用 ROM 源码集成套件】Android.bp 与 device-qcom32-volte.mk
├── website/                         # 【双语官方网站源码】qcom32-volte.octoooo.com (Cloudflare Workers)
├── docs/guide/                      # 【正式技术文档】架构、安装回滚、参考项目对比、换卡与 32 位移植指南
├── archive/                         # 【研发归档】八大核心踩坑复盘 (LESSONS_LEARNED.md) 与历史目录索引
└── LICENSE                          # Apache License 2.0
```

---

## 从源码编译与自检

```bash
# 1. 运行宿主机全套 C + Java + CLI 单元测试（无需连接手机或交叉工具链）
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
