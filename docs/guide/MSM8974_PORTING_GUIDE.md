# 非黑莓高通骁龙 800 / 801 (`MSM8974`) 老旗舰 VoLTE 移植指南

随着全球 2G/3G 退网（尤其是中国联通 3G 退网与电信 CDMA 退网），一加 1 (`bacon`)、小米 4 LTE (`cancro`)、索尼 Xperia Z1/Z2/Z3 (`honami`/`sirius`/`leo`)、三星 Galaxy S5 (`klte`)、LG G2/G3 等搭载 **高通骁龙 800 / 801 (`MSM8974`)** 的一代经典旗舰虽然支持 4G LTE 数据上网，但在刷入 LineageOS 17.1 / 18.1（Android 10 / 11）后普遍缺失 VoLTE 语音能力。

本项目的两大核心模块可以作为上述同代高通老旗舰打通原生 VoLTE 的参考蓝图。**注意：切勿在其他机型上直接盲跑 Passport 专用的 `pp_regconfig_provision_v6` 写入命令**，移植前请严格按以下 4 步核验：

---

## 第一步：核验基带固件是否包含 `QMI_IMSA` / `QMI_IMSS` / `qipcall`

并非所有 2013–2014 年出厂的 `MSM8974` 基带都编译进了 IMS 协议栈（例如部分极早期纯数据/WCDMA CSFB 基带可能裁掉了 `qipcall`）：

1. 连接手机获取 `adb root`，推送本项目的只读探针 `libimsa_probe.so`（不写任何 EFS）。
2. 若在 `IMS_enable=00` 下分配 `QMI_IMSS` (service `0x12`) 返回 `INVALID_SERVICE_TYPE (31)` 属于正常门控；也可以提取手机 `/firmware/image/modem.*` 检索字符串 `qp_ims_reg_config`、`qipcall_process_call_list`、`imsa_v01`。
3. 只要基带 ELF 中存在上述三个符号，说明您的骁龙 800/801 基带硬件与固件**具备完整的原生 Hexagon VoLTE 引擎**！

## 第二步：核对 `/nv/item_files/pdp_profiles/` 的槽位映射（关键安全红线！）

Passport 的基带中，`profile1..3` 为普通数据业务，`profile4` 原厂即为 `ims` APN 槽位（仅被 `apn_disabled_flag=1` 禁用且为 `IPV4`）。
而在小米 4、一加 1 或三星 S5 上，`profile4` 可能是彩信（MMS）或共享热点槽位，真实的 `ims` 槽位可能位于 `profile2`、`profile5` 或需要新建槽位并更新 `/nv/item_files/modem/data/3gpp/ps/3gpp_Scell_pdp_prof_num` 等映射表！

- **正确做法**：先用只读 DIAG EFS `GET` 将目标机型 `/nv/item_files/pdp_profiles/` 下所有现存 profile 完整拉回电脑备份并解析其 ASCII APN 名称与 `b750/b751/b753` 标志位，只修改对应 `ims` 的那个 profile（或分配空闲槽位），再调整 `src/modem-efs-provisioner/` 中的目标路径常量。

## 第三步：核验 `rild` 是否暴露 `/dev/socket/qmux_radio/rild_ims0`

在目标机型的 LineageOS 18.1 上执行：
```sh
ls -lZ /dev/socket/qmux_radio/
```
如果存在 `rild_ims0`（由高通 `libril-qc-qmi-1.so` 启动创建），那么本项目的通用 **[`src/android-ims-service/`](../../src/android-ims-service/)（`Qcom32VolteIms.apk`，包名 `org.octolab.qcom32ims`）可以直接零修改复用**！

您只需：
1. 在 [`devices/`](../../devices/README.md) 下为目标机型新建 `devices/<device-id>/profile.json` 登记核验后的基带 PDP 槽位；
2. 使用目标 LineageOS 18.1 ROM 对应的 `platform` 密钥对 `Qcom32VolteIms.apk` 重新签名（或按 [`rom-integration/README.md`](../../rom-integration/README.md) 通过 `device-qcom32-volte.mk` 直接放进源码树编译）。

## 第四步：音频路由（Voice-MMode1 / CSD）核验

Passport 的 `audio.primary.msm8974.so` 在 VoLTE 通话激活时通过 `vsid=0x10c02000` (`Voice2` / `Voice-MMode1`) 建立 `AMR-WB` 上下行通路。若在其他 `MSM8974` 机型上出现“能拨通振铃但接通无声”，请检查该机型 `mixer_paths.xml` 与 `audio_platform_info.xml` 中是否包含 `Voice-MMode1`（或 `VoLTE` PCM DAI）路由定义。
