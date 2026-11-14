# 安装部署、状态检查与回滚指南 (`INSTALL_AND_ROLLBACK.md`)

本指南适用于刷入 **LineageOS 18.1 (Android 11, `wolverine`)** 的 **BlackBerry Passport** 设备。

> [!IMPORTANT]
> - 整个激活过程**不刷写分区、不安装 Magisk、不关闭 SELinux (`Enforcing`)**。
> - 首次激活需要通过数据线连接电脑并使用 LineageOS 自带的 **ADB 调试 Root** 写入一次基带 EFS 配置；激活完成后拔掉数据线，手机在任意正常关机/重启后均可 **100% 本地独立运行原生 VoLTE**。

---

## 1. 前置条件检查

1. **手机系统**：BlackBerry Passport (`ro.product.device=wolverine`)，运行 LineageOS 18.1 (Android 11)。
2. **SIM 卡**：已插入开通 VoLTE 业务且处于 Passport 硬件支持频段内的 4G USIM 卡（如中国联通 4G/5G USIM 卡），手机状态栏已正常驻留 `LTE`。
3. **开启 ADB 调试与 Root 调试**：
   - 进入手机 **设置 -> 关于手机**，连续点击 **版本号** 开启开发者选项；
   - 进入 **设置 -> 系统 -> 开发者选项**，同时打开：
     - **Android 调试 (USB debugging)**
     - **Root 身份调试 (Rooted debugging)**
4. **电脑环境**：
   - Python 3.9+（仅使用标准库，无需 `pip install` 第三方包）；
   - Android `adb` 工具已加入系统 `PATH`。

---

## 2. 从源码构建（可选，面向开发者）

仓库正式源码全部位于 [`src/`](../../src/)，可通过 [`cli/build_all.py`](../../cli/build_all.py) 一键运行宿主机单元测试并交叉编译：

```bash
# 1. 仅运行宿主机单元测试（559 项 EFS 断言 + 17 项 QMI 断言 + POSIX 头文件编译门禁 + Java 协议栈与管理端断言）
python3 cli/build_all.py --host-tests-only

# 2. 完整编译 ARM32 二进制与签名 APK（输出至 build/release/）
python3 cli/build_all.py
```

构建需要的工具链可通过环境变量或命令行参数指定：
- `ZIG_EXE` / `--zig`：Zig `0.13+`（提供零配置 ARM32 Linux/Android 交叉编译器 `zig cc`）
- `JAVA_HOME` / `--jdk-bin`：JDK `17`（注：Android 11 Build-Tools 30 的 `d8.jar` 需要 JDK 11~17，不支持 JDK 25）
- `ANDROID_BUILD_TOOLS_DIR` / `--android-build-tools`：Android SDK Build-Tools 30+（含 `aapt2`, `zipalign`, `lib/d8.jar`, `lib/apksigner.jar`, `core-lambda-stubs.jar`）
- `ANDROID_SYSTEM_SDK_JAR` / `--android-sdk-jar`：包含 `@SystemApi` `ImsService` 的 Android 11 API 30 `android.jar`
- `SYSROOT_LIB_DIR` / `--sysroot-lib`：从目标 Passport `/system/lib/` 提取的 `libc.so` 与 `libdl.so` 链接桩
- `PLATFORM_KEY_PK8` & `PLATFORM_CERT_PEM`：ROM 对应的 `platform` 签名密钥（社区版 LineageOS `18.1-wseries` 使用标准 AOSP 公开 `platform` 签名）

构建完成后产物位于 `build/release/`：
- `build/release/pp_regconfig_provision_v6`
- `build/release/libimsa_probe.so`
- `build/release/Qcom32VolteIms.apk`
- `build/release/Qcom32VolteManager.apk`
- `build/release/release-manifest.json`

---

## 3. 一键激活部署 VoLTE

将手机通过 USB 连接电脑，确认手机无正在进行的通话后执行（普通 Windows 用户也可直接运行单文件 `qcom32-volte-1.2.1-r1-windows-x64.exe`）：

```bash
# 先运行 CLI 本地自检
python3 cli/volte_ctl.py self-test

# 执行受控部署（自动匹配设备 Profile -> 备份原始 EFS -> 写入 Stage 6A -> 安装 Qcom32VolteIms -> 自动重启并核验注册）
python3 cli/volte_ctl.py deploy

# （推荐）安装手机端实时状态与管理伴侣 App，拔线换卡后可在手机屏幕免 su 查看实时状态
python3 cli/volte_ctl.py install-manager
```

部署成功时终端会输出 JSON，其中关键指标如下：
- `"volte_stack_healthy": true`
- `"ims_service": { "present": true, "version": "2.6-qcom32-volte", "ril_connected": true, "voice_ims_ready": true, "published_framework_reg": 1 }`
- `"qmi_imsa": { "pdp_connected": 1, "reg_status": 2, "legacy_reg": 1, "voip": 2, "voip_rat": 1 }`
- `"framework_ims_registered": ["true"]`

此时即可拔掉 USB 数据线，直接使用系统自带电话应用拨打和接听高清 VoLTE 电话（通话时状态栏保持 `LTE` 不断网，来电号码正常显示）。

---

## 4. 日常只读状态诊断与仅升级 APK

```bash
# 只读查看当前 VoLTE 注册、ImsService 与基带 QMI_IMSA 状态（不修改任何配置）
python3 cli/volte_ctl.py status

# 仅升级 Android 侧 Qcom32VolteIms.apk（零基带 EFS 写入，自动清理旧包 org.passport.volteims）
python3 cli/volte_ctl.py upgrade-apk --reboot
```

---

## 5. 一键完整回滚（恢复出厂基带与系统状态）

如果需要彻底卸载本方案并将基带 EFS 恢复至部署前的出厂基线（`IMS_enable=00`, `voice_domain_pref=00`，清理全部新建 IMS/PDP 条目并卸载 `org.octolab.qcom32ims`）：

```bash
python3 cli/volte_ctl.py rollback
```

回滚脚本会自动清除持久化 `CarrierConfig` 覆盖、卸载 `Qcom32VolteIms.apk`、还原 `persist.radio.fallbackmode`、调用 `pp_regconfig_provision_v6 --rollback-all` 执行 EFS2 删除与 `SYNC` 落盘，随后正常重启手机恢复至纯 CSFB 出厂状态。

---

## 6. `SELinux Enforcing` 与基带 `EFSmodem` (`modemst1` / `modemst2`) 安全说明

1. **全程原生兼容 `SELinux Enforcing`（绝不降级 `Permissive`）**：
   - 本项目所有运行时组件（`Qcom32VolteIms.apk`、`libimsa_probe.so`、`VolteStatusProvider`）均在官方 LineageOS 18.1 严格 **`SELinux Enforcing`** 策略下原生运行于 `u:r:radio:s0` (`UID 1001`) 域，从不执行 `setenforce 0`，也不注入任何第三方 sepolicy 补丁；
   - 首次激活并正常重启确认 VoLTE 就绪后，您完全可以在「开发者选项」中**关闭「Root 身份调试」甚至「USB 调试」**——手机日常通话、开机与换卡自愈均无需任何 Root 权限。
2. **`EFSmodem` 分区持久性与跨机/跨运营商兼容性**：
   - 激活写入的 54 项 Stage 6A 配置经 `EFS2_DIAG_SYNC` 持久落盘在手机独立的 `modemst1` / `modemst2` 基带分区中。日常刷入同系 LineageOS 更新包或清除 `userdata` 不会擦除基带侧配置（仅需重装 `Qcom32VolteIms.apk` 并触发一次持久绑定，或直接采用方式四合入 ROM）；
   - 自 `v1.2.1` 起，`pp_regconfig_provision_v6` 对基带内置非 IMS 数据承载采用结构化校验（严格守护 `embedded_call_prof_nums` 与 `profile1`/`profile2` 非 IMS 属性，同时兼容高通 `libqdp.so` 随联通 `3gnet`、移动 `cmnet`、电信 `ctnet`、海外 SIM 卡动态更新或全新 `fsg` 恢复后的 `/pdp_profiles/profile3` 状态），不会因不同机器或换卡产生的数据 APN 差异误报错；
   - **刷机防护建议**：每台手机的 `modemst1` / `modemst2` / `fsg`（即 TWRP 中的 **EFS** 分区）包含本机独有的射频校准与串号数据。在进行跨大版本底包刷写或分区折腾前，建议在 TWRP Recovery 中顺手勾选 **EFS** 备份留存一份离线副本。
