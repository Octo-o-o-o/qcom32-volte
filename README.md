# Qcom32 VoLTE：高通 32 位 Android 11 通话栈

**源码可见（PolyForm Noncommercial 1.0.0 + 商业授权）** · [English](README.en.md) · [设备范围](devices/README.md) · [安装与回滚](docs/guide/INSTALL_AND_ROLLBACK.md) · [官网源码](website/)

Qcom32 VoLTE 包含 Android 11 原生 IMS 服务和按设备隔离的基带 EFS 配置工具。唯一完整实测设备为 BlackBerry Passport **SQW100-1 / `wolverine`**，社区 LineageOS 18.1 `wseries`，基带 DI.4.0.1 M8974AAAAANAZM，中国联通 46001。原厂 Passport 需要先进行 eMMC 硬件改造并安装该社区 ROM。

> ⚠️ **紧急呼叫不受支持，也未获认证。请备另一部手机。** 在 2G/3G 已关闭的 LTE-only 网络中，本机可能无法拨通 110、112、119、120 或 911。
>
> **基带写入有风险。** 修改前独立备份本机 modem EFS（如 `modemst1`、`modemst2`、`fsg`），将备份保存在手机之外，风险自负。当前工具自动保存的是 EFS 检查日志和哈希，**不是可完整回灌的原始备份**；回滚执行固定值写入和条目删除，不保证恢复本机原值。请核实自己的恢复方案。
>
> **v1.2.4-r1 及更早版本缺少本次机型门禁**，仍允许对未经验证的版本一键写入；请升级至 v1.2.5-r1，勿用旧版在这些机型上写入。

## 验证范围

| 项目 | 结果 |
| --- | --- |
| 联通 46001，一台 `wolverine` | 已实测主叫、被叫、双向 AMR-WB、来电显示、通话中 LTE 数据保持、拔线重启恢复和四种交付方式。 |
| 电信 46003 / 46011 | 仅验证 LTE 驻留与 IMS 注册；**未测试 VoLTE 通话**。EPS-only `fallbackmode=9` 防掉线观察 149 秒无掉线。 |
| 中国移动 | **未测试**；FDD B3/B8 仅为理论可能，Passport 不支持 TDD B38–41。 |
| IMS 短信 | **未实现**；服务不声明 `CAPABILITY_TYPE_SMS`。 |
| 紧急呼叫、VoWiFi、视频通话、呼叫转移/USSD/UT 等补充业务 | 不支持或未测试，不能依赖。 |
| 非中国运营商、其他 Passport 版本及其他设备 | 未测试；兼容性未知。 |

## 使用与回滚

本仓库源码默认只允许 `wolverine` 进行部署；`oslo`（银色版）、`wichita`（AT&T）、`keian`（保时捷）及通用 `wseries` / `windermere` 默认只读。实验性 CLI 写入必须同时提供 `--experimental-unverified-edition` 与交互式强确认；非交互时还需 `--i-understand-unverified-baseband-write`。桌面 GUI 和手机管理端不提供这些机型的一键激活。所有识别出的 Passport 版本保留回滚入口，非 Passport 基带写入拒绝。

部署还必须通过旧 IMS 守护进程检查及基带预检：要求 `MPSS.DI.4.0.1`、`AAAAANAZM` 和固件 `build_id:AAB982`；缺失或冲突时拒绝写入。重启后满足无信号条件时桌面端尝试固定回滚；仅 IMS 注册超时且 SIM 仍就绪时不会自动清理。回滚不受机型实验授权或 ROM/基带部署门禁阻断，也不保证原值或信号恢复。这些 v1.2.4 防护仅经离线／模拟验证，未在故障真机上验证。

```bash
python3 cli/volte_ctl.py status
python3 cli/volte_ctl.py inspect-device
python3 cli/volte_ctl.py deploy                  # wolverine；需先准备独立备份
python3 cli/volte_ctl.py rollback                # 固定清理操作，非原值备份恢复
python3 cli/gui.py                               # 图形界面
```

Windows EXE 图形界面可点击“执行固定基带清理并卸载”，命令行形式为 `<EXE> --cli rollback`；Android 管理端可点击回滚。各入口的回滚均有上述原值限制。完整步骤见 [安装与回滚](docs/guide/INSTALL_AND_ROLLBACK.md)。请以手机实际通话验证为准，IMS 注册不等于通话成功。

四种交付形态包括 Windows 桌面 EXE、Android 管理端、跨平台 CLI/GUI 和 ROM 源码集成；四种方式仅在上述一台联通设备上完成端到端验证。

## v1.2.5-r1 下载

每个文件均提供独立的 `.sha256` 校验文件。

| 文件 | R2 镜像 | GitHub Release |
| --- | --- | --- |
| Windows EXE | [下载](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe.sha256) | [下载](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-windows-x64.exe.sha256) |
| Manager APK | [下载](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk.sha256) | [下载](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteManager-1.2.5-r1.apk.sha256) |
| IMS APK 2.8 | [下载](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk.sha256) | [下载](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/Qcom32VolteIms-2.8-r1-platform.apk.sha256) |
| 便携包 | [下载](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip.sha256) | [下载](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-portable.zip.sha256) |
| 源码包 | [下载](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip) · [SHA-256](https://qcom32-downloads.octoooo.com/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip.sha256) | [下载](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip) · [SHA-256](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/qcom32-volte-1.2.5-r1-source.zip.sha256) |
| 全部校验和 | [SHA256SUMS.txt](https://qcom32-downloads.octoooo.com/v1.2.5-r1/SHA256SUMS.txt) | [SHA256SUMS.txt](https://github.com/Octo-o-o-o/qcom32-volte/releases/download/v1.2.5-r1/SHA256SUMS.txt) |

## 本地验证

```bash
python3 cli/build_all.py --host-tests-only
python3 cli/volte_ctl.py self-test
python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py self-test
cd website && npm test
```

## 致谢

感谢 [balika011](https://balika011.hu) 提供 LineageOS 18.1 `wseries` 移植和转换工具（[设备源码](https://github.com/BBAndroids/android_device_blackberry_wseries)）；感谢 guizmox 对 Passport LineageOS 的稳定化与社区贡献；感谢 [ikimi615](https://github.com/ikimi615/qualcomm-volte-enabler) 早期 `/dev/diag` 思路。

## 许可

源码可见：适用 [PolyForm Noncommercial 1.0.0](LICENSE)，商业授权另行提供，详见 [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)。

## v1.2.5-r1 (2026-11-15)

- `oslo`、`wichita`、`keian`、`wseries`、`windermere` 未经真机验证，在 CLI、GUI、EXE 和 Manager 中默认只读；实验性 CLI 写入需显式开启并输入确认，非交互运行须提供第二项明确确认。已识别的 Passport 版本均保留回滚入口。
- 修正验证与恢复口径：仅一台联通 46001 的 SQW100-1 `wolverine` 完成通话实测；电信仅验证 LTE 驻留及 IMS 注册，通话未测；移动未测，IMS 短信未实现，紧急呼叫不支持。回滚写入固定基线并删除创建的条目，不保证恢复本机原值；`--inspect-state` 不是备份。请自行备份 `modemst1`、`modemst2`、`fsg`。
- 包含 v1.2.4 的 ROM/基带预检和重启后无信号自动回滚；这些防护仅经离线或模拟 ADB 验证，未在故障真机上验证。
- v1.2.4-r1 及更早版本仍允许未经验证的版本一键写入；用户应升级到 v1.2.5-r1。
