# LineageOS 18.1 ROM 源码树原生集成指南 (`rom-integration/`)

如果您是 **LineageOS 18.1 (`android_device_blackberry_wseries`)** 或同代高通 **骁龙 800 / 801 (`MSM8974`)** 机型的 ROM 维护者，可以直接将本项目的 `PassportVolteIms` 与 `libimsa_probe` 合入您的 ROM 源码树随系统一起编译。

## 一、合入 ROM 源码树的优势

1. **天然匹配 `platform` 签名**：无需关心用户刷入的是官方 `testkey` 还是您私有密钥签名的自编译包，`Soong` / `Make` 会自动使用当前构建树的 `certificate: "platform"` 为 `PassportVolteIms` 签名，安全运行于 `android.uid.phone` (`UID 1001`)。
2. **零 `<uses-permission>` 回归保障**：随系统构建放入 `/system_ext/priv-app/PassportVolteIms/`（或 `/system/app/PassportVolteIms/`），不会触发 `sharedUserId` 权限重置。
3. **基带供给解耦**：基带 EFS2 是持久存储在手机 `modemst1/modemst2` 分区中的非易失数据——用户只需用桌面工具或 `pp_regconfig_provision_v6` 激活过一次基带 Stage 6A，后续刷入任何集成了 `PassportVolteIms` 的 OTA 更新包都能开箱即用 VoLTE！

## 二、集成步骤（3 步完成）

1. 在您的 Android 11 源码树新建目录 `packages/services/PassportVolteIms/`，将本仓库的以下目录拷入：
   - `src/android-ims-service/AndroidManifest.xml` -> `packages/services/PassportVolteIms/AndroidManifest.xml`
   - `src/android-ims-service/src/` -> `packages/services/PassportVolteIms/src/`
   - `src/qmi-imsa-probe/` -> `packages/services/PassportVolteIms/jni/`
   - `rom-integration/Android.bp` -> `packages/services/PassportVolteIms/Android.bp`
2. 在您的设备仓库（如 `device/blackberry/wseries/device.mk`）末尾引入 [`device-passport-volte.mk`](device-passport-volte.mk)：
   ```makefile
   $(call inherit-product, packages/services/PassportVolteIms/device-passport-volte.mk)
   ```
3. 在设备 `overlay/frameworks/base/core/res/res/values/config.xml` 中设置默认绑定包名（可选，有了它甚至连首次开机广播等待都可省去）：
   ```xml
   <bool name="config_device_volte_available">true</bool>
   <bool name="config_dynamic_bind_ims">true</bool>
   <string name="config_ims_mmtel_package">org.passport.volteims</string>
   ```

若需将本方案移植到**一加 1 (`bacon`)、小米 4 (`cancro`)、索尼 Z2/Z3 (`sirius`/`leo`)、三星 Galaxy S5 (`klte`)** 等其他骁龙 800/801 机型，请阅读 [`docs/guide/MSM8974_PORTING_GUIDE.md`](../docs/guide/MSM8974_PORTING_GUIDE.md)。
