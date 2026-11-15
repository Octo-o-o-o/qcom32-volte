# 安装、检查与回滚

仅一台 BlackBerry Passport SQW100-1 `wolverine`、社区 LineageOS 18.1 `wseries`、联通 46001 真机完成主被叫、双向 AMR-WB、来显、通话中 LTE 数据、拔线重启及四种交付方式验收。原厂设备须先进行 eMMC 硬件改造并安装该 ROM。

> ⚠️ 紧急呼叫不支持、未认证；请保留另一部手机。在 2G/3G 关闭的 LTE-only 网络上，本机可能无法拨通 110/112/119/120/911。
>
> **先独立备份本机基带 EFS 并存到手机之外。** 工具自动保存的只是检查日志和哈希，不是可完整回灌的原始备份。写入有风险，使用者自行承担；固定回滚不保证恢复任意设备的原值。
>
> 已发布的 `v1.2.4-r1` 不含本次机型门禁和口径修正，仍允许 `oslo`、`wichita`、`keian` 一键写入；未验证版本请勿使用该版写入。

电信 46003/46011 仅验证 LTE 驻留与 IMS 注册，通话未测；EPS-only 防掉线观察 149 秒无掉线。移动未测，FDD B3/B8 仅为理论可能，机身无 TDD B38–41。IMS 短信未实现；VoWiFi、视频、紧急呼叫、补充业务及非中国运营商不支持或未测试。

## 机型门禁

`wolverine` 保持部署、升级和回滚。`oslo`、`wichita`、`keian`、`wseries`、`windermere` 尚未真机验证，默认只读；桌面 GUI 和 Android 管理端不提供激活。CLI 若自行承担实验风险，必须同时指定 `--experimental-unverified-edition` 和输入终端所要求的强确认文本；非交互模式用 `--i-understand-unverified-baseband-write` 作为第二项确认。所有已识别 Passport 版本保留回滚；非 Passport 基带写入被拒绝。

无论机型，部署前还必须通过旧 IMS 守护进程和基带检查：四个遗留服务均不得运行，基带必须包含 `MPSS.DI.4.0.1` 和 `AAAAANAZM`，固件 `build_id` 必须为 `AAB982`。CLI 在重启前 SIM 已就绪、重启后超时且 SIM 状态为空并非飞行模式时尝试自动固定回滚；仅 IMS 超时不会触发。回滚不受上述部署门禁阻断，但不保证原值或信号恢复。此防护仅经离线/模拟验证。

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

## 回滚边界

```bash
python3 cli/volte_ctl.py rollback
```

GUI 点击“执行固定基带清理并卸载”，Android 管理端也提供回滚。当前 `--rollback-all` 将指定项目写成 `00`，删除指定 EFS 项并同步，随后清理应用／配置和重启；它不是从检查日志恢复原始字节，也不保证任意设备的出厂状态。提前核对独立备份及实际恢复工具。

源码可见（PolyForm Noncommercial 1.0.0 + 商业授权）；详见 [许可](../../LICENSE) 和 [商业授权](../../COMMERCIAL-LICENSE.md)。
