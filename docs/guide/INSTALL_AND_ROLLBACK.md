# 安装、检查与回滚

仅一台 BlackBerry Passport SQW100-1 `wolverine`、社区 LineageOS 18.1 `wseries`、联通 46001 真机完成主被叫、双向 AMR-WB、来显、通话中 LTE 数据、拔线重启及四种交付方式验收。原厂设备须先进行 eMMC 硬件改造并安装该 ROM。

> ⚠️ 紧急呼叫不支持、未认证；请保留另一部手机。在 2G/3G 关闭的 LTE-only 网络上，本机可能无法拨通 110/112/119/120/911。
>
> **先独立备份本机基带 EFS 并存到手机之外。** 工具自动保存的只是检查日志和哈希，不是可完整回灌的原始备份。写入有风险，使用者自行承担；固定回滚不保证恢复任意设备的原值。
>
> `v1.2.4-r1` 及更早版本不含本次机型门禁，仍允许未经验证的版本一键写入；`v1.2.4-r1` 与 `v1.2.5-r1` 还会误判拦截 `wolverine` 真机运行时基带串 `.DI.4.0.1-00158-M8974AAAAANAZM-1`。请升级至 `v1.2.8-r1`，勿用旧版在未验证机型上写入。

电信 46003/46011 验证 LTE 与 IMS；另有一台 46011 免提双向通话、听筒无声仍在排查；EPS-only 防掉线观察 149 秒无掉线。移动未测，FDD B3/B8 仅为理论可能，机身无 TDD B38–41。IMS 短信未实现；VoWiFi、视频、紧急呼叫、补充业务及非中国运营商不支持或未测试。

## 机型门禁

`wolverine` 保持部署、升级和回滚。`oslo`、`wichita`、`keian`、`wseries`、`windermere` 默认只读，桌面 GUI 和 Android 管理端不提供激活。一台 `oslo` 在下述确切配置完成实机注册及主被叫，实验门禁仍适用。CLI 实验部署必须同时指定 `--experimental-unverified-edition` 和输入终端所要求的强确认文本；非交互模式用 `--i-understand-unverified-baseband-write` 作为第二项确认。所有已识别 Passport 版本保留回滚；非 Passport 基带写入被拒绝。

无论机型，部署前还必须通过旧 IMS 守护进程和基带检查：四个遗留服务均不得运行，基带必须包含 `DI.4.0.1` 和 `AAAAANAZM`（真机运行时属性为 `.DI.4.0.1-00158-M8974AAAAANAZM-1`），固件 `build_id` 必须为 `AAB982`。CLI 在重启前 SIM 已就绪、重启后超时且 SIM 状态为空并非飞行模式时尝试自动固定回滚；仅 IMS 超时不会触发。回滚不受上述部署门禁阻断，但不保证原值或信号恢复。此防护仅经离线/模拟验证。

## 基本操作

确认独立备份与恢复路径、SIM 和 LTE 状态、空闲通话状态及供电后，在 Android 11 开启 USB 调试和 Root 调试：

```bash
python3 cli/volte_ctl.py self-test
python3 cli/volte_ctl.py status
python3 cli/volte_ctl.py inspect-device
python3 cli/volte_ctl.py deploy
python3 cli/volte_ctl.py install-manager
```

GUI 用 `python3 cli/gui.py`；Windows 发行 EXE 双击打开 GUI。CLI 发行版的回滚形式是 `<EXE> --cli rollback`。注册成功只说明检测到 IMS 状态，须由用户自行验证普通主被叫、双向声音和 LTE 数据；不要用紧急号码测试。

## 激活成功后可以卸载什么

| 组件 | 保留 VoLTE 时的处理 |
| --- | --- |
| 手机伴侣 `Qcom32VolteManager.apk`（`org.octolab.qcom32volte`，应用名 **Qcom32 VoLTE**） | 可在 Android 设置的应用列表中卸载。需要查看状态或用手机管理时可以重装。 |
| Windows EXE、便携工具、手机上的安装包文件 | 可删除。电脑可以断开，USB 调试和 Root 调试可以关闭。 |
| IMS 服务 `Qcom32VolteIms.apk`（`org.octolab.qcom32ims`，应用名 **Qcom32 VoLTE IMS**） | 保留并保持启用。Android 拨号器通过它与基带交换 VoLTE 注册和来去电状态。ROM 集成时同样保留内置的 IMS 服务和配套探针。 |

如果只想卸载伴侣，请用系统的应用卸载入口。工具内的「回滚」会执行基带清理并移除 VoLTE 配置。

## Passport Silver Edition（`oslo`）的已实测 ROM 准备流程

一台银色版在联通 46001、AAB982、`18.1-20251205-UNOFFICIAL-wseries` 上完成了 Android IMS 和基带 LTE 语音注册，用户确认主叫、被叫正常。该结果适用于这台真机及其测试配置，实验机型门禁仍适用。

先从 Android 开机状态检查这两个 ROM 配置。命令识别 `ORIGINAL` 与 `PREPARED`，其他配置哈希会拒绝处理。独立备份本机 EFS，确认现有 Recovery 能读取四个基带分区并接收备份。

```bash
python3 cli/volte_ctl.py --serial <adb-serial> prepare-oslo-rom
python3 cli/volte_ctl.py --serial <adb-serial> prepare-oslo-rom --apply
python3 cli/volte_ctl.py --serial <adb-serial> deploy --experimental-unverified-edition
```

`--apply` 使用现有的 root ADB Recovery 自动备份两份 init 文件，移除四个旧 IMS 服务及其触发段，再检查启动后的 LTE、RIL IMS socket 和冲突状态。已经准备好的设备会直接返回。恢复原 ROM 配置前应先用本机 EFS 备份恢复激活前状态，并卸载项目 IMS 应用。随后可从 Android 或已进入的 Recovery 执行 `prepare-oslo-rom --restore <rom-backup-directory>`，并指定同一个序列号。

## 无信号与硬件故障

Windows 体检与 `inspect-device` 会把「SIM 已加载、飞行模式关闭、语音和数据均无服务、运行时基带版本为空」分流到基带启动诊断。先核对本机基带来源及运行模式。工厂测试模式是已实测的一个原因，需要单独诊断和同机可恢复备份；工具不会更改该模式。

如果普通本地音频和 VoLTE 都只有免提有声，按听筒输出链路排查。触控控制器的幽灵触点需要单独作输入诊断。标准安装程序没有触屏过滤器、听筒配置写入或工厂模式修复。

## 固定基带回滚

```bash
python3 cli/volte_ctl.py rollback
```

GUI 点击“执行固定基带清理并卸载”，Android 管理端也提供回滚。当前 `--rollback-all` 将指定项目写成 `00`，删除指定 EFS 项并同步，随后清理应用／配置和重启；它不是从检查日志恢复原始字节，也不保证任意设备的出厂状态。提前核对独立备份及实际恢复工具。

源码可见（PolyForm Noncommercial 1.0.0 + 商业授权）；详见 [许可](../../LICENSE) 和 [商业授权](../../COMMERCIAL-LICENSE.md)。
