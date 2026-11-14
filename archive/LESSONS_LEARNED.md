# BlackBerry Passport Android 11 VoLTE 历史踩坑与失败路线复盘 (`LESSONS_LEARNED`)

在打通 BlackBerry Passport (`wolverine` / `msm8974` / 基带 `.DI.4.0.1-00158-M8974AAAAANAZM-1`) 原生 VoLTE 的过程中，项目经历了从无 Root 探索到 Root 后基带 Hexagon Q6ZIP 逆向、EFS2 供给、QMI/RIL 协议桥接与 Android 11 `ImsService` 适配的完整闭环。以下归档所有**已证实走不通或踩过深坑的方案**，供后续开发者与同类高通老平台（MSM8974 / MSM8992 / MSM8994 / SDM660）移植者避坑。

---

## 踩坑 1：照搬 `qualcomm-volte-enabler` 只改 `IMS_enable = 01`

- **最初假设**：参考黑莓 KEY2 (`ikimi615/qualcomm-volte-enabler`) 方案，通过 `/dev/diag` EFS2 将 `/nv/item_files/ims/IMS_enable` 从 `00` 改为 `01` 并重启，就能激活基带 IMS。
- **失败现象**：
  1. KEY2 上游 `diag_efs_helper.c` 为 ARM64 且使用指针型 `ioctl`，在 Passport 32 位 ARM 内核 (`DIAG_IOCTL_SWITCH_LOGGING` 接收标量值 `1`/`2`) 上直接调用不兼容，且内核 memory-mode 会拦截部分未带序列号头的 EFS GET 请求。
  2. 适配 ARM32 DIAG/EFS2 协议并成功写入 `IMS_enable=01` + `EFS2_DIAG_SYNC` 重启后，虽然 `QMI_IMSS` (0x12) 与 `QMI_IMSA` (0x21) 服务确实被基带发布了，但 IMS 始终处于 `NOT_REGISTERED`，VoLTE 完全不可用。
- **真正根因**：Passport 出厂面向 BB10 OS，基带 EFS 中**压根不存在** `/nv/item_files/ims/qp_ims_reg_config`、`ims_operation_mode`、`qp_ims_sms_config`、`qipcall_*` 等 50 个核心 IMS 配置文件（`STAT` 全部返回 `ENOENT`），且 `voice_domain_pref` 默认是 CS-only (`00`)。单开 `IMS_enable=01` 时，Hexagon `RegInfo` 读取组 0 配置失败，直接阻断 SIP 注册状态机。

---

## 踩坑 2：试图通过 `QMI_PDC` 刷入或激活原厂 MBN (`65535.mbn` / `GCF`)

- **最初假设**：从 BlackBerry Autoloader 固件 (`NON-HLOS.bin`) 提取中国联通对应的 MCFG MBN，通过 `QMI_PDC` 加载激活。
- **失败现象**：
  1. 提取固件内全部 32 份原厂 MBN 后发现，中国联通 (`36`) 映射到的 `65535.mbn` 是全零空壳 (`NULL`)，没有任何 IMS 配置。
  2. 裸 MCFG 载荷通过 `QMI_PDC LOAD` 发送被基带直接以错误码 `0x22` 拒绝。
  3. 含 405 字节 `qp_ims_reg_config` 的其他运营商 MBN（如 `GCF`、`ROGERS`）偏移 `38` 处硬编码了境外公网/测试网固定 P-CSCF 地址，直接用于国内联通 SIM 会导致 SIP 发往错误网关。
- **最终正解**：基于 Hexagon Q6ZIP 解压出的 24 字段精确结构体定义，构造 **`mode=0`（3GPP 标准 PCO 动态发现 P-CSCF + USIM 自动推导归属域 `ims.mncXXX.mccYYY.3gppnetwork.org`）** 的纯净 `qp_ims_reg_config`，不硬编码任何运营商私有 IP 或外机身份。

---

## 踩坑 3：基带 `/pdp_profiles/profile4` 隐藏的标志位陷阱 (`apn_disabled` 与缺失 `PCO`)

- **最初假设**：只要把 50 个 `/nv/item_files/ims/*` EFS 条目补齐，基带就能自己连上 `ims` APN。
- **失败现象**：在 Stage 5CD 试验中，Hexagon 注册门控 A/B/C 全部放行，`QMI_IMSA` 进入 `REGISTERING`，但 IMS PDN 建链始终失败。
- **真正根因**：逆向 Hexagon DS 3GPP Profile 解析器 (`789` 字节 `/pdp_profiles/profile4`) 发现：
  - `byte[753]` (`apn_disabled_flag`, 对应 `QMI_WDS 0x002B TLV 0x33`) 出厂被置为 `1`（禁用该 Profile）！
  - `byte[750]` (`im_cn_flag`, `TLV 0x21`) 与 `byte[751]` (`request_pcscf_address_flag`, `TLV 0x2e`) 均为 `0`，导致基带即使发起建链也不向基站请求 P-CSCF 地址！
- **最终正解**：在 `pp_regconfig_provision_v6` 中写入修正后的 `IPV4V6` `/pdp_profiles/profile4`（`APN="ims"`, `b750=1`, `b751=1`, `b753=0`），基带独立 DCM 在开机数秒内即拿到双栈 IMS IP 与联通 P-CSCF 并完成 SIP 200 OK 注册。

---

## 踩坑 4：让 Android `ConnectivityService` 主动拉起 `NET_CAPABILITY_IMS` (`rmnet1` 死锁)

- **最初假设**：在 Android 侧通过 `ConnectivityManager.requestNetwork(NET_CAPABILITY_IMS)` 建立 `rmnet1` 承载。
- **失败现象**：
  1. 早期触发 `SETUP_DATA_CALL` 曾出现请求 `[730]` 悬挂超过 2 小时且无请求级取消接口，只能重启恢复。
  2. 当启用 `APConnectivity=1` / `ims_hybrid_enable` 让 AP 与基带共同管理承载时，Android 的 `rmnet1` 与 Hexagon 内部 DCM 发生双端抢占冲突，SIP 注册反而卡死。
- **最终正解**：保持 `ims_hybrid_enable` 不存在（`ENOENT`），采用 **Standalone Hexagon DCM (`Stage 6A`)**——由基带自己在内部维护 IMS PDN 与 P-CSCF，Android 侧只管 `rmnet0` 普通 4G 上网数据 (`3gnet`)，两者互不干扰，通话期间 4G 数据流量完全不断网！

---

## 踩坑 5：`libqmi_client_qmux.so` 40 字节私有包头覆写栈内存导致 `SIGSEGV (139)`

- **失败现象**：首次编写 ARM32 原生 QMI 客户端调用 `qmi_qmux_if_send_qmi_msg()` 时，进程直接 `SIGSEGV`（退出码 `139`）。
- **真正根因**：反汇编高通闭源 `libqmi_client_qmux.so` 发现，该函数会在传入的 `msg_ptr` **向前负偏移 `40` 字节**处直接原地写入 QMUX 内部传输头！如果在普通栈数组头部直接传指针，会瞬间覆写栈帧与返回地址 `LR`。
- **最终正解**：在 `src/qmi-imsa-probe/` 中为发送缓冲区强制预留 `64` 字节前置 Headroom（并在宿主机单元测试中专门用 Mock 覆写前 40 字节做回归断言）。

---

## 踩坑 6：`rild_ims0` 发送 `REQUEST_IMS_REG_STATE_CHANGE (26)` 反致掉线

- **最初假设**：Android `ImsService` 启动后应按常规流程向 `rild_ims0` 发送 `msgId=26` (`turnOnIms`) 和 `msgId=30` (`SET_SERVICE_STATUS`)。
- **失败现象**：在 `Stage 6A` 基带已自动完成 VoLTE 注册的前提下，向 Passport 旧版 `libqcril_ims` 发送 `26` 或 `30` 会返回 `E_GENERIC_FAILURE (error=2)` 或触发基带侧重置 `test_mode`。
- **最终正解**：`RilImsWire` 在协议编码层**硬性禁止发送 `26` 与 `30`**（单元测试 `forbidden_26_30=0` 强制校验），仅使用只读查询 `1` (`GET_REGISTRATION_STATE`)、`29` (`QUERY_SERVICE_STATUS`) 及通话控制命令 `10..17`（拨号、接听、挂断、DTMF 等）。

---

## 踩坑 7：`sharedUserId="android.uid.phone"` 清空系统电话 120 项权限与非持久 `CarrierConfig`

- **踩坑 A（权限污染风险）**：与 `com.android.phone` 共享 `android.uid.phone` (`UID 1001`) 的 APK 若声明不匹配的 `<uses-permission>`，可能影响共享 UID 权限集。最终 `PassportVolteIms.apk` 采用**零 `<uses-permission>` 声明**，直接继承 `UID 1001` 既有权限，每次部署与回滚均核验 `120` 项电话权限一字不差。
- **踩坑 B（重启后绑定丢失）**：`PassportVolteIms` v1 调用了两参版 `CarrierConfigManager.overrideConfig(subId, bundle)`，该方法默认 `persistent=false`，手机一重启配置即丢失，`ImsResolver` 不再绑定 `PassportVolteImsService`。v2 尝试调用三参版 `overrideConfig(subId, bundle, true)` 时，发现公开 Android 11 SDK stub 剥离了该 `@SystemApi` 重载。最终在 `compile_stubs/` 补齐编译期签名并通过 `javap` 字节码校验，实现跨重启、拔掉 USB 断电重启均 100% 自动恢复绑定。

---

## 踩坑 8：来电显示“未知号码” (`CLIP` 与 `OIR` 语义反转 Bug)

- **失败现象**：`PassportVolteIms` v2r2 实机打通双向高清语音后，手机呼出正常，但外部拨入时系统来电界面显示“未知”（号码被隐藏），日志中 `ImsPhoneCallTracker` 打印 `nump = 3` (`PRESENTATION_UNKNOWN`)。
- **真正根因**：高通 `rild_ims0` `CALL_STATE_CHANGED` 报文里的 `numberPresentation` 字段传的是 **3GPP `CLIP` 原生枚举**（`0 = 允许显示`, `1 = 限制显示`, `2 = 未知`），而 `PassportCallSession` v2r2 把它当成了 `TelecomManager.PRESENTATION_*`（`1 = ALLOWED`），调用 `ImsCallProfile.presentationToOir(1)` 时默认分支返回了 `OIR_DEFAULT (0)` -> 被框架反解为 `PRESENTATION_UNKNOWN (3)`！此外国际格式前缀 `+86` (`TOA=145`) 也未补 `+`。
- **最终正解**：在 `v2r3`（即正式版 `src/android-ims-service/`）中修正 `clipToOir()` 映射（`0 -> OIR_PRESENTATION_NOT_RESTRICTED (2)`, `1 -> OIR_PRESENTATION_RESTRICTED (1)`），支持 `TOA=145` 自动补 `+`，并在每次振铃/通话快照变化时触发 `callSessionUpdated(callProfile)`。实机复测 `observed_nump_values = [1, 1]`，来电号码完美显示。
