# LineageOS 18.1 / Android 11 ROM 源码树原生集成指南 (`rom-integration/`)

如果您是高通 32 位遗留平台（**骁龙 800 / 801 `MSM8974`**、**骁龙 805 `APQ8084`** 等，如 BlackBerry Passport `wseries`、OnePlus One `bacon`、Xiaomi Mi 4 `cancro`、Sony Xperia Z2/Z3 `sirius`/`leo`、Samsung Galaxy S5 `klte`）的 Android 11 / LineageOS 18.1 ROM 维护者，可以直接将本项目的通用通话栈 `Qcom32VolteIms`（`org.octolab.qcom32ims`）与 `libimsa_probe` 合入您的 ROM 源码树随系统编译。

## 一、合入 ROM 源码树的优势

1. **天然匹配 `platform` 签名**：无需关心用户刷入的是官方 `testkey` 还是您私有密钥签名的自编译包，`Soong` / `Make` 会自动使用当前构建树的 `certificate: "platform"` 为 `Qcom32VolteIms` 签名，安全运行于 `android.uid.phone` (`UID 1001`)。
2. **零 `<uses-permission>` 回归保障**：随系统构建放入 `/system_ext/priv-app/Qcom32VolteIms/`，不会触发 `sharedUserId` 权限重置。
3. **通用 `rild_ims0` 协议层与机型基带供给解耦**：`Qcom32VolteIms` 直接对接高通 32 位 QCRIL `/dev/socket/qmux_radio/rild_ims0` Protobuf 接口；而基带 EFS2 是持久存储在手机 `modemst1/modemst2` 分区中的非易失数据（首个完整验证机型 Profile 见 [`devices/blackberry-passport-wolverine/`](../devices/blackberry-passport-wolverine/profile.json)）。

## 二、集成步骤（3 步完成）

1. 在您的 Android 11 源码树新建目录 `packages/services/Qcom32VolteIms/`，将本仓库的以下目录拷入：
   - `src/android-ims-service/AndroidManifest.xml` -> `packages/services/Qcom32VolteIms/AndroidManifest.xml`
   - `src/android-ims-service/res/` -> `packages/services/Qcom32VolteIms/res/`
   - `src/android-ims-service/src/` -> `packages/services/Qcom32VolteIms/src/`
   - `src/qmi-imsa-probe/` -> `packages/services/Qcom32VolteIms/jni/`
   - `rom-integration/Android.bp` -> `packages/services/Qcom32VolteIms/Android.bp`
   - `rom-integration/device-qcom32-volte.mk` -> `packages/services/Qcom32VolteIms/device-qcom32-volte.mk`
2. 在您的设备仓库（如 `device/blackberry/wseries/device.mk`）末尾引入 [`device-qcom32-volte.mk`](device-qcom32-volte.mk)：
   ```makefile
   $(call inherit-product, packages/services/Qcom32VolteIms/device-qcom32-volte.mk)
   ```
3. 在设备 `overlay/frameworks/base/core/res/res/values/config.xml` 中设置默认绑定包名（可选）：
   ```xml
   <bool name="config_device_volte_available">true</bool>
   <bool name="config_dynamic_bind_ims">true</bool>
   <string name="config_ims_mmtel_package">org.octolab.qcom32ims</string>
   ```

各 `MSM8974` 机型基带 PDP Profile 与音频路由适配详见 [`docs/guide/MSM8974_PORTING_GUIDE.md`](../docs/guide/MSM8974_PORTING_GUIDE.md) 与 [`devices/README.md`](../devices/README.md)。

## 三、社区公益 ROM 与商业授权边界

- **100% 免费纯公益社区 ROM 允许免费合入**：遵循 [`LICENSE`](../LICENSE) 中的 **PolyForm Noncommercial License 1.0.0**，完全免费向公众开放、无付费群/打赏门槛的第三方非商业社区 ROM 可直接集成，分发时须在源码与发布说明中保留原作者署名、`LICENSE`、`COMMERCIAL-LICENSE.md` 及 `Required Notice:` 非商用声明。
- **收费 ROM 与预刷售机须取得商业授权**：将本组件合入收费刷机包、会员/赞助解锁包，或预装于对外销售的二手机/翻新机整机中，均属于商业用途，须事先联系 **OctoLab**（`octoooo@octoooo.com`）获取书面付费授权（详见 [`COMMERCIAL-LICENSE.md`](../COMMERCIAL-LICENSE.md)）。
