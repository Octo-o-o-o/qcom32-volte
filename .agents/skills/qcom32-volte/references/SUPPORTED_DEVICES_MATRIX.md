# Supported & Candidate 32-Bit Qualcomm Device Matrix (`SUPPORTED_DEVICES_MATRIX.md`)

`qcom32-volte` targets **32-bit Qualcomm Snapdragon legacy platforms** (`MSM8974` Snapdragon 800/801, `APQ8084` Snapdragon 805, `MSM8926` Snapdragon 400 LTE) running **Android 11 / LineageOS 18.1 (`API 30`, `armeabi-v7a`)** where Qualcomm ended official IMS/VoLTE HAL support at Android 6.0.

Always run `python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py inspect` (or `catalog --query <model>`) before advising a user or executing commands.

---

## 1. Tier Summary & Variant Trap Reference

| Brand & Model Family | Codename(s) | SoC | Tier | Recommended Sub-Models | Hard Blockers & Trap Variants (Must Reject / Warn!) |
| --- | --- | --- | --- | --- | --- |
| **BlackBerry Passport** | `wolverine` (`wseries`) | `MSM8974AC` | **Verified** (`blackberry-passport-wolverine`) | `SQW100-1`, `SQW100-2`, `SQW100-3`, `SQW100-4` on LineageOS 18.1 | - **Stock BB10 OS (`10.3.3`)**: Use sister repo [`Octo-o-o-o/bb10-volte`](https://github.com/Octo-o-o-o/bb10-volte) instead.<br>- **RF Limits**: Supports FDD B1/2/3/4/5/7/8/13/17/20; no TDD B38–41 and no CDMA 1x fallback. |
| **Samsung Galaxy S5** | `klte`, `kltechnduo`, `klteduos`, `kltedv`, `kltekdi`, `kltekor` | `MSM8974AC` | **Candidate Port** | `SM-G900F` (Global), `SM-G9006V` (Unicom), `SM-G9008V` (CMCC), `SM-G900M`, `SM-G900T`, `SM-G900W8` | - **`SM-G900A` (AT&T)**: Permanently locked bootloader.<br>- **`SM-G900V` (Verizon CID 11)**: Locked bootloader (only eMMC CID 15 can unlock).<br>- **`SM-G900H` (`k3gxx`)**: Exynos 5422 SoC — **NOT Qualcomm**. |
| **Xiaomi Mi 4 LTE / Mi Note** | `cancro`, `virgo` | `MSM8974AC` | **Candidate Port** | `MI 4LTE` (Unicom 4G / Telecom 4G preferred for FDD B1/B3), `MI NOTE LTE` | - **`MI 4W` / `MI 4C` (3G) / `MI 3W` (`pisces`)**: **3G-only hardware** without an LTE baseband — VoLTE is physically impossible.<br>- **`Mi 4c` (`libra`) / `Mi 4s` (`aqua`)**: 64-bit `MSM8992` (not 32-bit `qcom32-volte`).<br>- Requires **1.2 GB unified `system` partition merge** before flashing Android 11. |
| **LG G3 / LG G2** | `d855`, `d850`, `d851`, `d852`, `f400`, `vs985`, `ls990`, `d802` | `MSM8974AC` / `MSM8974` | **Candidate Port** | `LG-D855` (Global single-SIM), `LG-F400S/K/L`, `LG-D851` (unlocked BL), `LG-D802` | - **`LG-D858` / `D858HK` / `D859` (China Dual-SIM)**: Scarce/unstable Android 11 trees & divergent dual-SIM RIL.<br>- **High 9008 Brick Risk**: Except `D851`, G3 lacks standard `fastboot oem unlock` and requires Stock KitKat/Lollipop KDZ -> Root -> **Bump!** TWRP. Flashing wrong-variant `aboot` bricks into QDLoader `9008`. |
| **OnePlus One** | `bacon` (`A0001`) | `MSM8974AC` | **Candidate Port** | `A0001` Global / China | - Clean `fastboot oem unlock`; verify FDD band mask on early China units. |
| **Sony Xperia Z1 / Z2 / Z3 / Compact** | `honami`, `amami`, `sirius`, `leo` (`z3`), `aries` (`z3c`) | `MSM8974` / `8974AB` / `8974AC` | **Candidate Port** | `D6603` / `D6653` (Z3), `D5803` / `D5833` (Z3c), `D6503` (Z2) | - Carrier-locked Japan/US units (`SO-01G`, `SOL26`, `D6616`) with `Bootloader unlock allowed: No` cannot unlock.<br>- Back up `TA` (Trim Area DRM partition) before unlocking if on stock Sony firmware. |
| **HTC One M8** | `m8`, `m8wl`, `m8d` | `MSM8974AB` | **Candidate Port** | `M8_UL` International / Developer Edition | - AT&T / Verizon units without S-OFF cannot unlock via HTCDev. |
| **Moto X 2014 / Google Nexus 6** | `victara`, `shamu` | `MSM8974AC` / `APQ8084` | **Candidate Port** | `Nexus 6` (`shamu`, `XT1100`/`XT1103`), `XT1092`/`XT1095` | - `XT1096` (Verizon) / `XT1097` (AT&T) locked bootloaders. (`shamu` shipped with official Android 5.1+ VoLTE modem firmware.) |
| **Google Nexus 5** | `hammerhead` | `MSM8974` | **Modem Unsupported** | None on stock baseband | - Stock `D820`/`D821` Google baseband omits `qipcall` / Hexagon IMS services. Do not attempt unless modem ELF scan proves `qp_ims_reg_config` exists. |

---

## 2. Division of Labor Between AI Agent and Human User During ROM Prep

When a user brings an un-flashed phone (stock Android 4.4 / 5.0 / 6.0), the AI agent **can execute all host-side CLI commands** (`adb`, `fastboot`, `heimdall`, checksum verification, ROM re-signing, and `volte_ctl.py`), while prompting the human user **only** for physical hardware actions:

1. **Entering Hardware Boot Modes**:
   - **Samsung (`klte`) Download Mode**: Power off -> Hold `Volume Down + Home + Power` -> Press `Volume Up` to confirm -> AI runs `heimdall print-pit` / `heimdall flash --RECOVERY twrp.img --no-reboot` (or guides Odin GUI).
   - **Xiaomi (`cancro`) / OnePlus (`bacon`) / Moto / Nexus Fastboot**: Power off -> Hold `Volume Down + Power` (`Volume Up + Power` on `bacon`) -> AI runs `fastboot devices` and `fastboot flash recovery twrp.img`.
   - **Sony (`sirius`/`leo`) Fastboot**: Power off -> Hold `Volume Up` while plugging USB cable (LED turns blue) -> AI runs `fastboot devices`.
   - **LG (`d855`/`f400`)**: Confirm exact sticker model under battery and current OS version first; never blind-flash `boot`/`aboot` across `D850`/`D855`/`F400` variants.
2. **Pre-Flight TWRP Backup (Mandatory Before Any Porting Experiment)**:
   - Once TWRP is booted, instruct the user (or run via `adb shell twrp backup` where supported) to back up **`EFS` (`modemst1`, `modemst2`, `fsg`) + `boot` + `system`** and pull a copy to the host PC.
   - **Never** flash one unit's `modemst1`/`modemst2` onto another unit (contains per-device IMEI and RF calibration).
