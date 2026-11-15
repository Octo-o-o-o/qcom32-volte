#!/usr/bin/env python3
"""32-Bit Qualcomm Device Catalog, 100% Read-Only 4-Gate Readiness Inspector,
and AI + GitHub Self-Adaptation Guide for the Qcom32 VoLTE & IMS Stack.

Zero third-party dependencies. Runs identically in source checkouts, portable ZIPs,
and frozen PyInstaller Windows EXEs. Never performs any EFS, property, or filesystem writes.
"""
from __future__ import annotations

import datetime as dt
import re
from typing import Any

GITHUB_REPO_URL = "https://github.com/Octo-o-o-o/qcom32-volte"
WEBSITE_URL = "https://qcom32-volte.octoooo.com/"
BB10_SISTER_URL = "https://bb10-volte.octoooo.com/"
SKILL_PATH_AGENTS = ".agents/skills/qcom32-volte/SKILL.md"
SKILL_PATH_CLAUDE = ".claude/skills/qcom32-volte/SKILL.md"

PASSPORT_WSERIES_DEVICES: tuple[str, ...] = (
    "wolverine",
    "oslo",
    "wichita",
    "keian",
    "wseries",
    "windermere",
)
PASSPORT_MODEL_TOKENS: tuple[str, ...] = (
    "passport",
    "sqw100",
    "oslo",
    "wichita",
    "keian",
    "windermere",
)

PII_REDACT_RE = re.compile(r"\b\d{11,20}\b")


def sanitize_text(text: str) -> str:
    return PII_REDACT_RE.sub("<REDACTED_DIGITS>", text or "")


CATALOG_FAMILIES: list[dict[str, Any]] = [
    {
        "family_id": "blackberry-passport-wolverine",
        "tier": "mixed_verified_and_experimental",
        "soc": "MSM8974AA (Snapdragon 801)",
        "codenames": list(PASSPORT_WSERIES_DEVICES),
        "models": [
            "Passport",
            "Passport Silver Edition",
            "SQW100-1",
            "SQW100-2",
            "SQW100-3",
            "SQW100-4",
            "Oslo",
            "Wichita",
            "Keian",
        ],
        "name_zh": "黑莓 Passport 家族（一台 wolverine 已验证；银色版 oslo 等未验证）",
        "name_en": "BlackBerry Passport family (one wolverine verified; Silver Edition oslo and others unverified)",
        "rom_zh": "LineageOS 18.1 (Android 11 / API 30, lineage_wseries)",
        "rom_en": "LineageOS 18.1 (Android 11 / API 30, lineage_wseries)",
        "status_zh": "仅一台 wolverine 真机验证；其他代号未验证，默认只读；回滚可用",
        "status_en": "One wolverine unit verified; other codenames unverified and read-only by default; rollback available",
    },
    {
        "family_id": "samsung-galaxy-s5-klte",
        "tier": "candidate",
        "soc": "MSM8974AC (Snapdragon 801)",
        "codenames": [
            "klte",
            "klteduos",
            "kltechn",
            "kltechnduo",
            "kltedv",
            "kltekdi",
            "kltekor",
            "klteactivexx",
        ],
        "models": [
            "SM-G900F",
            "SM-G900FD",
            "SM-G900I",
            "SM-G900K",
            "SM-G900L",
            "SM-G900M",
            "SM-G900P",
            "SM-G900S",
            "SM-G900T",
            "SM-G900W8",
            "SM-G9006V",
            "SM-G9008V",
            "SM-G9009D",
            "SCL23",
            "SC-04F",
        ],
        "name_zh": "三星 Galaxy S5 骁龙全系 (klte / klteduos / kltechn)",
        "name_en": "Samsung Galaxy S5 Snapdragon Family (klte / klteduos / kltechn)",
        "rom_zh": "官方/非官方 LineageOS 18.1（注意：官方签名为 release-keys，需平台密钥重签或放入 priv-app）",
        "rom_en": "Official/Unofficial LineageOS 18.1 (Note: official builds use release-keys; sign APK with ROM key or use priv-app)",
        "status_zh": "内置只读体检 + 可通过 AI + GitHub 仓库 Skill 安全解码 /pdp_profiles/ 自助建档（避开 SM-G900H 猎户座与 G900A/V 锁 BL）",
        "status_en": "Read-only check built in + self-adapt via AI + GitHub repo Skill (excludes Exynos SM-G900H & locked G900A/V)",
    },
    {
        "family_id": "xiaomi-mi4-cancro",
        "tier": "candidate",
        "soc": "MSM8974AC (Snapdragon 801)",
        "codenames": ["cancro", "virgo"],
        "models": ["MI 4LTE", "MI 4LTE-CT", "MI 4LTE-CU", "MI 4LTE-CMCC", "MI NOTE LTE"],
        "name_zh": "小米 4 LTE / 小米 Note 4G 版 (cancro / virgo)",
        "name_en": "Xiaomi Mi 4 LTE / Mi Note LTE (cancro / virgo)",
        "rom_zh": "社区非官方 LineageOS 18.1 (test-keys，先将底包合并分区刷至 MIUI MM 基带)",
        "rom_en": "Unofficial LineageOS 18.1 (test-keys; ensure Marshmallow partition layout + LTE modem firmware)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档（注意：联通/电信 3G 版 MI 4W / MI 3W 无 LTE 硬件不支持）",
        "status_en": "Read-only check + AI + GitHub self-porting (Note: 3G-only MI 4W / MI 3W lacks LTE baseband)",
    },
    {
        "family_id": "oneplus-one-bacon",
        "tier": "candidate",
        "soc": "MSM8974AC (Snapdragon 801)",
        "codenames": ["bacon", "find7", "find7s"],
        "models": ["A0001", "ONEPLUS A0001", "X9006", "X9007", "X9076", "X9077"],
        "name_zh": "一加手机 1 / OPPO Find 7 系列 (bacon / find7)",
        "name_en": "OnePlus One / OPPO Find 7 & 7a (bacon / find7)",
        "rom_zh": "LineageOS 18.1 (Android 11)",
        "rom_en": "LineageOS 18.1 (Android 11)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档（建议刷入含 qipcall 符号的 OxygenOS/氢OS/Find7 底包基带）",
        "status_en": "Read-only check + AI + GitHub self-porting (verify Gate 3 qipcall symbols in modem firmware first)",
    },
    {
        "family_id": "sony-xperia-z1-z2-z3",
        "tier": "candidate",
        "soc": "MSM8974AB / MSM8974AC (Shinano / Rhine)",
        "codenames": ["sirius", "leo", "z3", "aries", "z3c", "castor", "honami", "amami", "togari"],
        "models": ["D6503", "D6603", "D6633", "D6653", "D5803", "D5833", "C6903", "D5503", "SO-01G", "SO-02G"],
        "name_zh": "索尼 Xperia Z1 / Z2 / Z3 / Z3 Compact 系列 (sirius / leo / aries / honami)",
        "name_en": "Sony Xperia Z1 / Z2 / Z3 / Z3 Compact Family (sirius / leo / aries / honami)",
        "rom_zh": "LineageOS 18.1（注意解锁 BL 前先备份 TA 分区 DRM 密钥）",
        "rom_en": "LineageOS 18.1 (Back up TA partition DRM keys before bootloader unlock)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档（日版/欧版原厂已带 VoLTE 基带，适配成功率高）",
        "status_en": "Read-only check + AI + GitHub self-porting (stock Xperia Z2/Z3 modems commonly include full IMS engines)",
    },
    {
        "family_id": "lg-g3-g2-msm8974",
        "tier": "candidate",
        "soc": "MSM8974AC / MSM8974AA",
        "codenames": ["d855", "d850", "d851", "d852", "f400", "ls990", "vs985", "d802", "d800", "d801", "ls980", "vs980"],
        "models": ["LG-D855", "LG-D850", "LG-D851", "LG-F400", "LG-VS985", "LG-D802"],
        "name_zh": "LG G3 / LG G2 系列 (d855 / f400 / vs985 / d802)",
        "name_en": "LG G3 / LG G2 Family (d855 / f400 / vs985 / d802)",
        "rom_zh": "LineageOS 18.1 (Android 11)",
        "rom_en": "LineageOS 18.1 (Android 11)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档（韩版 F400 与美版 VS985 原厂即带完整 IMS 配置）",
        "status_en": "Read-only check + AI + GitHub self-porting (Korean F400 & Verizon VS985 ship with rich IMS modem tables)",
    },
    {
        "family_id": "htc-one-m8",
        "tier": "candidate",
        "soc": "MSM8974AB / MSM8974AC",
        "codenames": ["m8", "m8d", "b2ul"],
        "models": ["HTC One_M8", "HTC M8w", "HTC M8t", "0P6B"],
        "name_zh": "HTC One M8 / Butterfly 2 系列 (m8 / m8d / b2ul)",
        "name_en": "HTC One M8 / Butterfly 2 Family (m8 / m8d / b2ul)",
        "rom_zh": "LineageOS 18.1 (Android 11)",
        "rom_en": "LineageOS 18.1 (Android 11)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档",
        "status_en": "Read-only check + AI + GitHub self-porting",
    },
    {
        "family_id": "motorola-victara-shamu",
        "tier": "candidate",
        "soc": "MSM8974AC / APQ8084 + MDM9625M",
        "codenames": ["victara", "quark", "shamu"],
        "models": ["XT1092", "XT1095", "XT1096", "XT1097", "XT1225", "XT1254", "Nexus 6"],
        "name_zh": "Moto X 2014 / Droid Turbo / Google Nexus 6 (victara / quark / shamu)",
        "name_en": "Moto X 2014 / Droid Turbo / Google Nexus 6 (victara / quark / shamu)",
        "rom_zh": "LineageOS 18.1（注：Nexus 6 为外挂 MDM9625M 基带，需由 AI 先做 Gate 2/3 只读探测）",
        "rom_en": "LineageOS 18.1 (Note: Nexus 6 uses discrete MDM9625M modem; run Gate 2/3 read-only check first)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档",
        "status_en": "Read-only check + AI + GitHub self-porting",
    },
    {
        "family_id": "samsung-note3-note4-s4ltea",
        "tier": "candidate",
        "soc": "MSM8974 / APQ8084",
        "codenames": ["hlte", "hltechn", "hltekor", "trlte", "trlteduos", "ks01lte"],
        "models": ["SM-N9005", "SM-N9008V", "SM-N910F", "SM-N910G", "SM-N9100", "GT-I9506"],
        "name_zh": "三星 Note 3 / Note 4 / S4 LTE-A 骁龙版 (hlte / trlte / ks01lte)",
        "name_en": "Samsung Galaxy Note 3 / Note 4 / S4 LTE-A Snapdragon (hlte / trlte / ks01lte)",
        "rom_zh": "LineageOS 18.1（仅限骁龙版，不含 N9000 / N910C 猎户座版）",
        "rom_en": "LineageOS 18.1 (Snapdragon variants only; excludes Exynos N9000 / N910C)",
        "status_zh": "内置只读体检 + AI + GitHub 自助建档",
        "status_en": "Read-only check + AI + GitHub self-porting",
    },
    {
        "family_id": "qualcomm-msm8926-sd400",
        "tier": "candidate",
        "soc": "MSM8926 / MSM8928 (Snapdragon 400 LTE)",
        "codenames": ["peregrine", "thea", "titan", "eagle", "dior", "kminilte"],
        "models": ["XT1039", "XT1040", "XT1045", "XT1072", "D2303", "HM NOTE 1LTE", "SM-G800F"],
        "name_zh": "高通骁龙 400 32 位 4G 机型 (Moto G LTE / 红米 Note 4G / Xperia M2 / S5 Mini)",
        "name_en": "Snapdragon 400 32-Bit LTE Devices (Moto G LTE / Redmi Note 4G / Xperia M2 / S5 Mini)",
        "rom_zh": "LineageOS 18.1（须先在 Gate 3 确认基带含 qipcall 符号）",
        "rom_en": "LineageOS 18.1 (Verify Gate 3 qipcall modem symbols first)",
        "status_zh": "内置只读体检 + AI + GitHub 自助探索",
        "status_en": "Read-only check + AI + GitHub self-exploration",
    },
]


def is_passport_wseries(device: str, model: str) -> bool:
    dev_l = (device or "").strip().lower()
    mod_l = (model or "").strip().lower()
    dev_hit = dev_l in PASSPORT_WSERIES_DEVICES
    mod_hit = any(tok in mod_l for tok in PASSPORT_MODEL_TOKENS)
    return dev_hit and mod_hit


def list_catalog_entries(query: str | None = None) -> list[dict[str, Any]]:
    if not query or not query.strip():
        return list(CATALOG_FAMILIES)
    q = query.strip().lower()
    matched: list[dict[str, Any]] = []
    for fam in CATALOG_FAMILIES:
        hay = " ".join(
            [
                str(fam["family_id"]),
                str(fam["soc"]),
                str(fam["name_zh"]),
                str(fam["name_en"]),
                *fam["codenames"],
                *fam["models"],
            ]
        ).lower()
        if q in hay:
            matched.append(fam)
    return matched


def identify_device_props(props: dict[str, str]) -> dict[str, Any]:
    brand = (props.get("ro.product.brand") or props.get("ro.product.manufacturer") or "").strip()
    model = (props.get("ro.product.model") or "").strip()
    device = (props.get("ro.product.device") or "").strip()
    platform = (props.get("ro.board.platform") or "").strip()
    abi = (props.get("ro.product.cpu.abi") or "").strip()
    sdk_raw = (props.get("ro.build.version.sdk") or "").strip()
    sdk_int = int(sdk_raw) if sdk_raw.isdigit() else 0
    tags = (props.get("ro.build.tags") or "").strip()

    dev_l = device.lower()
    mod_u = model.upper()
    brand_l = brand.lower()
    plat_l = platform.lower()
    abi_l = abi.lower()
    test_keys = "test-keys" in tags.lower()

    if abi_l.startswith("arm64"):
        return {
            "route": "UNSUPPORTED_64BIT_PLATFORM",
            "family_id": "unsupported-64bit",
            "tier": "unsupported",
            "name_zh": "64 位平台（非本项目目标架构）",
            "name_en": "64-Bit Platform (Out of Scope)",
            "summary_zh": f"检测到 64 位 ABI ({abi})；Qcom32 VoLTE 专为缺少现代 64 位 ImsService 的高通 32 位老旗舰设计。",
            "summary_en": f"Detected 64-bit ABI ({abi}); Qcom32 VoLTE targets legacy 32-bit Qualcomm platforms.",
            "prebuilt_apk_compatible": test_keys,
        }

    if (
        dev_l in ("k3gxx", "k3g", "ha3g", "treltexx", "tre3g")
        or "G900H" in mod_u
        or any(tok in plat_l for tok in ("exynos", "universal54", "mt6", "mt8", "kirin", "hi36", "hi62"))
    ):
        return {
            "route": "UNSUPPORTED_NON_QUALCOMM",
            "family_id": "unsupported-non-qualcomm",
            "tier": "unsupported",
            "name_zh": "非高通骁龙平台（硬件不支持）",
            "name_en": "Non-Qualcomm Platform (Hardware Unsupported)",
            "summary_zh": f"当前设备 ({model} / {platform}) 采用非高通基带（如三星 Exynos / 联发科 / 麒麟），无法使用高通 QMI/EFS2 通话栈。",
            "summary_en": f"Device ({model} / {platform}) does not use a Qualcomm Snapdragon baseband.",
            "prebuilt_apk_compatible": test_keys,
        }

    if dev_l == "pisces" or mod_u in ("MI 4W", "MI 3W", "MI 4C"):
        return {
            "route": "UNSUPPORTED_HARDWARE_VARIANT",
            "family_id": "xiaomi-3g-only-variant",
            "tier": "unsupported",
            "name_zh": "小米 3G 硬件版本（无 4G LTE 基带）",
            "name_en": "Xiaomi 3G-Only Hardware Variant (No LTE Modem)",
            "summary_zh": f"{model} ({device}) 为联通/电信 3G 硬件版，缺少 4G LTE 射频与基带，硬件无法支持 VoLTE。",
            "summary_en": f"{model} ({device}) is a 3G-only hardware edition without a 4G LTE modem.",
            "prebuilt_apk_compatible": test_keys,
        }

    if "G900A" in mod_u or "G900V" in mod_u:
        return {
            "route": "UNSUPPORTED_HARDWARE_VARIANT",
            "family_id": "samsung-s5-locked-bootloader",
            "tier": "unsupported",
            "name_zh": "美版锁 Bootloader 定制机型 (SM-G900A / SM-G900V)",
            "name_en": "Locked-Bootloader Carrier S5 Variant (SM-G900A / SM-G900V)",
            "summary_zh": f"{model} 存在运营商 Bootloader 锁限制（除非早期 eMMC CID 解锁版本），刷机前请谨慎核实。",
            "summary_en": f"{model} typically enforces carrier bootloader lock unless unlocked via early eMMC CID methods.",
            "prebuilt_apk_compatible": test_keys,
        }

    if dev_l == "hammerhead" or mod_u == "NEXUS 5" or "LG-D820" in mod_u or "LG-D821" in mod_u:
        return {
            "route": "MODEM_LACKS_IMS_ENGINE",
            "family_id": "google-nexus-5-hammerhead",
            "tier": "unsupported",
            "name_zh": "Google Nexus 5 (hammerhead — 原厂基带缺失 qipcall IMS 引擎)",
            "name_en": "Google Nexus 5 (hammerhead — Stock Modem Lacks qipcall IMS Engine)",
            "summary_zh": "Nexus 5 原厂高通基带固件裁剪了 Hexagon qipcall VoLTE 引擎，无法通过纯 AP/EFS 配置开启原生 VoLTE。",
            "summary_en": "Stock Nexus 5 baseband omits the Hexagon qipcall IMS engine required for native VoLTE.",
            "prebuilt_apk_compatible": test_keys,
        }

    if is_passport_wseries(device, model):
        if sdk_int == 30:
            if dev_l != "wolverine":
                return {
                    "route": "EXPERIMENTAL_UNVERIFIED_EDITION",
                    "family_id": "blackberry-passport-wolverine",
                    "tier": "experimental",
                    "name_zh": "Passport 未经真机验证版本（只读）",
                    "name_en": "Passport unverified edition (read-only)",
                    "summary_zh": "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证；默认只读，回滚可用。",
                    "summary_en": "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证; read-only by default; rollback remains available.",
                    "prebuilt_apk_compatible": test_keys,
                }
            return {
                "route": "VERIFIED_PASSPORT_READY",
                "family_id": "blackberry-passport-wolverine",
                "tier": "verified",
                "name_zh": "黑莓 Passport wolverine（仅一台 SQW100-1 已验证）",
                "name_en": "BlackBerry Passport wolverine (one SQW100-1 verified)",
                "summary_zh": "仅一台联通 46001 真机完成通话验证；其他运营商和机型请查看验证范围。",
                "summary_en": "Calls verified on one 46001 unit; see verification limits for other carriers and editions.",
                "prebuilt_apk_compatible": test_keys,
            }
        return {
            "route": "NEEDS_ANDROID11_ROM_FIRST",
            "family_id": "blackberry-passport-wolverine",
            "tier": "unverified",
            "name_zh": "黑莓 Passport 系列（当前非 Android 11 系统）",
            "name_en": "BlackBerry Passport Family (Requires Android 11 / API 30)",
            "summary_zh": f"检测到 Passport ({device}) 但当前 SDK 为 {sdk_int}；原厂 BB10 系统请看 {BB10_SISTER_URL}，Android 系统请刷入 LineageOS 18.1。",
            "summary_en": f"Passport ({device}) is on SDK {sdk_int}; visit {BB10_SISTER_URL} for BB10 OS or flash LineageOS 18.1 for Android 11.",
            "prebuilt_apk_compatible": test_keys,
        }

    matched_fam: dict[str, Any] | None = None
    for fam in CATALOG_FAMILIES[1:]:
        c_hit = any(dev_l == c.lower() or (len(c) >= 4 and dev_l.startswith(c.lower())) for c in fam["codenames"])
        m_hit = any(m.upper() in mod_u for m in fam["models"])
        if c_hit or m_hit:
            matched_fam = fam
            break
    if matched_fam is None and brand_l == "sony" and ("xperia" in mod_u.lower() or mod_u.startswith(("D65", "D66", "D58"))):
        matched_fam = CATALOG_FAMILIES[4]

    fam_id = matched_fam["family_id"] if matched_fam else "generic-qcom32-candidate"
    name_zh = matched_fam["name_zh"] if matched_fam else f"高通 32 位候选机型 ({model or device or 'Unknown'})"
    name_en = matched_fam["name_en"] if matched_fam else f"32-Bit Qualcomm Candidate ({model or device or 'Unknown'})"

    if sdk_int != 30:
        return {
            "route": "NEEDS_ANDROID11_ROM_FIRST",
            "family_id": fam_id,
            "tier": "candidate",
            "name_zh": name_zh,
            "name_en": name_en,
            "summary_zh": f"当前系统为 Android SDK {sdk_int}；请先刷入 LineageOS 18.1 (Android 11 / API 30)，再使用 AI + GitHub 仓库内置 Skill 完成 4-Gate 适配。",
            "summary_en": f"Currently running Android SDK {sdk_int}; flash LineageOS 18.1 (Android 11 / API 30) first, then adapt via AI + GitHub repo Skill.",
            "prebuilt_apk_compatible": test_keys,
        }

    return {
        "route": "CANDIDATE_MSM8974_PORTING",
        "family_id": fam_id,
        "tier": "candidate",
        "name_zh": name_zh,
        "name_en": name_en,
        "summary_zh": "通用 Android 11 通话栈 Qcom32VolteIms 可直接复用；为防止误覆写移动数据/彩信槽位，基带写入已保护锁定，请用 AI + GitHub 仓库内置 Skill 自助适配。",
        "summary_en": "Generic Qcom32VolteIms stack is reusable; direct EFS write is locked to protect data/MMS PDP slots — self-adapt safely with AI + GitHub repo Skill.",
        "prebuilt_apk_compatible": test_keys,
    }


def build_ai_prompt(props: dict[str, str], match: dict[str, Any], gates: dict[str, Any], lang: str = "zh") -> str:
    model = sanitize_text(props.get("ro.product.model") or "Unknown")
    device = sanitize_text(props.get("ro.product.device") or "unknown")
    platform = sanitize_text(props.get("ro.board.platform") or "unknown")
    sdk = sanitize_text(props.get("ro.build.version.sdk") or "0")
    tags = sanitize_text(props.get("ro.build.tags") or "unknown")
    g1 = gates.get("gate1_rom", {}).get("status", "UNKNOWN")
    g2 = gates.get("gate2_ril_diag", {}).get("status", "UNKNOWN")
    g3 = gates.get("gate3_modem_ims", {}).get("status", "UNKNOWN")
    g4 = gates.get("gate4_audio_hal", {}).get("status", "UNKNOWN")
    if lang == "en":
        return (
            f"I cloned {GITHUB_REPO_URL} and connected my {model} (codename `{device}`, SoC `{platform}`, "
            f"SDK {sdk}, build tags `{tags}`, family `{match['family_id']}`, route `{match['route']}`). "
            f"Read-only 4-Gate summary: Gate1={g1}, Gate2={g2}, Gate3={g3}, Gate4={g4}. "
            f"Please load `{SKILL_PATH_AGENTS}` and guide me step-by-step: run `ai_device_helper.py inspect-adb`, "
            f"decode existing `/pdp_profiles/*` in read-only mode so we never overwrite mobile data or MMS APN slots, "
            f"back up original EFS items, and create a dedicated `devices/<id>/profile.json` for my phone."
        )
    return (
        f"我已经克隆了 {GITHUB_REPO_URL} 并连接了手机 {model}（代号 `{device}`，平台 `{platform}`，"
        f"SDK {sdk}，签名 `{tags}`，识别机系 `{match['family_id']}`，路由 `{match['route']}`）。"
        f"只读四门禁结果：Gate1={g1}, Gate2={g2}, Gate3={g3}, Gate4={g4}。"
        f"请读取仓库内置的 `{SKILL_PATH_AGENTS}` 技能，带我按步骤执行：运行 `ai_device_helper.py inspect-adb`，"
        f"100% 只读解码现有 `/pdp_profiles/*` 以避开移动上网与彩信槽位，先做完整原始 EFS 备份，再为这台机型生成专属 `devices/<id>/profile.json` 完成适配。"
    )


def run_readonly_readiness_audit(adb: Any) -> dict[str, Any]:
    """Run a 100% read-only 4-Gate hardware/ROM/RIL/Modem/Audio inspection over ADB.
    Never calls `adb root` and never writes any file, property, or EFS item.
    """
    prop_keys = (
        "ro.product.brand",
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.device",
        "ro.board.platform",
        "ro.product.cpu.abi",
        "ro.build.version.sdk",
        "ro.build.version.release",
        "ro.build.display.id",
        "ro.build.tags",
        "gsm.version.baseband",
        "gsm.sim.operator.numeric",
        "gsm.network.type",
    )
    props: dict[str, str] = {}
    for k in prop_keys:
        props[k] = sanitize_text(adb.shell(f"getprop {k}", timeout=15, check=False).strip())

    uid_out = adb.shell("id -u", timeout=15, check=False).strip()
    is_root_shell = uid_out == "0"
    selinux = sanitize_text(adb.shell("getenforce", timeout=15, check=False).strip() or "unknown")

    rild_ls = sanitize_text(
        adb.shell("ls -l /dev/socket/qmux_radio/rild_ims0 2>/dev/null || true", timeout=15, check=False).strip()
    )
    diag_ls = sanitize_text(adb.shell("ls -l /dev/diag 2>/dev/null || true", timeout=15, check=False).strip())
    qmuxd_pid = sanitize_text(adb.shell("pidof qmuxd rild 2>/dev/null || true", timeout=15, check=False).strip())

    audio_hits = sanitize_text(
        adb.shell(
            "grep -E -i -l 'Voice-MMode1|voice-ims|compress-voip|VoLTE' "
            "/vendor/etc/mixer_paths*.xml /system/etc/mixer_paths*.xml 2>/dev/null | head -n 4 || true",
            timeout=20,
            check=False,
        ).strip()
    )
    modem_hits = sanitize_text(
        adb.shell(
            "grep -a -l 'qp_ims_reg_config' "
            "/firmware/image/modem* /vendor/firmware_mnt/image/modem* 2>/dev/null | head -n 4 || true",
            timeout=25,
            check=False,
        ).strip()
    )

    match = identify_device_props(props)
    sdk_str = props.get("ro.build.version.sdk") or "0"
    sdk_int = int(sdk_str) if sdk_str.isdigit() else 0
    test_keys = "test-keys" in (props.get("ro.build.tags") or "").lower()

    # Gate 1: Android 11 (SDK 30) & Platform Signature
    if sdk_int == 30 and test_keys:
        g1_status = "PASS"
        g1_zh = "通过（Android 11 SDK 30 + test-keys，可直接安装预编译 Qcom32VolteIms.apk）"
        g1_en = "PASS (Android 11 SDK 30 + test-keys; prebuilt Qcom32VolteIms.apk installs directly)"
    elif sdk_int == 30:
        g1_status = "WARN_RELEASE_KEYS"
        g1_zh = f"注意（Android 11 SDK 30，但签名为 `{props.get('ro.build.tags')}`；需用 ROM 平台密钥重签 APK 或通过 rom-integration/ 集成）"
        g1_en = f"WARN (Android 11 SDK 30 with `{props.get('ro.build.tags')}`; re-sign APK with ROM platform key or use rom-integration/)"
    else:
        g1_status = "FAIL_NEEDS_ANDROID11"
        g1_zh = f"未满足（当前 SDK {sdk_int} / Android {props.get('ro.build.version.release') or '?'}，需先刷入 LineageOS 18.1 Android 11）"
        g1_en = f"FAIL (Running SDK {sdk_int} / Android {props.get('ro.build.version.release') or '?'}; flash LineageOS 18.1 Android 11 first)"

    # Gate 2: QCRIL IMS Socket & /dev/diag
    rild_ok = "rild_ims0" in rild_ls and "No such file" not in rild_ls
    diag_ok = "/dev/diag" in diag_ls and "No such file" not in diag_ls
    if rild_ok and diag_ok:
        g2_status = "PASS"
        g2_zh = "通过（已检出 /dev/socket/qmux_radio/rild_ims0 与 /dev/diag）"
        g2_en = "PASS (/dev/socket/qmux_radio/rild_ims0 and /dev/diag both present)"
    elif not is_root_shell and (not rild_ok or not diag_ok):
        g2_status = "NEEDS_ROOT_VISIBILITY"
        g2_zh = (
            f"部分受 SELinux 隐藏（当前 shell UID={uid_out}；rild_ims0={'可见' if rild_ok else '需 Root 查看'}, "
            f"/dev/diag={'可见' if diag_ok else '需 Root 查看'}；开启开发者选项「Root 授权的调试」后可由 AI 完整核验）"
        )
        g2_en = (
            f"PARTIAL_VISIBILITY (shell UID={uid_out}; rild_ims0={'visible' if rild_ok else 'needs root'}, "
            f"/dev/diag={'visible' if diag_ok else 'needs root'}; enable Rooted debugging for full AI check)"
        )
    else:
        g2_status = "WARN_MISSING_NODE"
        g2_zh = f"待核查（rild_ims0={'存在' if rild_ok else '未检出'}, /dev/diag={'存在' if diag_ok else '未检出'}）"
        g2_en = f"WARN (rild_ims0={'present' if rild_ok else 'missing'}, /dev/diag={'present' if diag_ok else 'missing'})"

    # Gate 3: Modem Hexagon IMS Engine
    if modem_hits:
        g3_status = "PASS"
        first_modem = modem_hits.splitlines()[0]
        g3_zh = f"通过（基带镜像检出 `qp_ims_reg_config` IMS 引擎符号：{first_modem}）"
        g3_en = f"PASS (`qp_ims_reg_config` IMS symbol confirmed in {first_modem})"
    elif not is_root_shell:
        g3_status = "NEEDS_ROOT_VISIBILITY"
        g3_zh = "待开启 Root 调试后只读扫描 `/firmware/image/modem*` 中的 `qp_ims_reg_config` 符号"
        g3_en = "UNCHECKED (Enable Rooted debugging so AI can read-only scan `/firmware/image/modem*` for `qp_ims_reg_config`)"
    else:
        g3_status = "WARN_NOT_DETECTED"
        g3_zh = "未直接扫到（可能基带分区为 Q6ZIP 压缩段或挂载路径不同，可由 AI 用 `qcom32-volte` 技能深入核验）"
        g3_en = "WARN (Not matched in plain strings; may use Q6ZIP compressed segments or alternate mount path — check with AI Skill)"

    # Gate 4: Audio HAL VoLTE Voice Path
    if audio_hits:
        g4_status = "PASS"
        first_audio = audio_hits.splitlines()[0]
        g4_zh = f"通过（检出语音路由配置：{first_audio}）"
        g4_en = f"PASS (Found VoLTE/Voice-MMode1 mixer route in {first_audio})"
    else:
        g4_status = "WARN_CHECK_MIXER"
        g4_zh = "待核查（未在默认 mixer_paths*.xml 匹配到 Voice-MMode1，请让 AI 检查 vendor 音频配置）"
        g4_en = "WARN (Voice-MMode1 not matched in default mixer_paths*.xml; let AI inspect vendor audio configs)"

    gates = {
        "gate1_rom": {"status": g1_status, "detail_zh": g1_zh, "detail_en": g1_en},
        "gate2_ril_diag": {
            "status": g2_status,
            "rild_ims0_ls": rild_ls,
            "diag_ls": diag_ls,
            "daemons": qmuxd_pid,
            "detail_zh": g2_zh,
            "detail_en": g2_en,
        },
        "gate3_modem_ims": {"status": g3_status, "modem_hits": modem_hits, "detail_zh": g3_zh, "detail_en": g3_en},
        "gate4_audio_hal": {"status": g4_status, "audio_hits": audio_hits, "detail_zh": g4_zh, "detail_en": g4_en},
    }

    audit: dict[str, Any] = {
        "checked_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(),
        "readonly_zero_writes": True,
        "shell_uid": uid_out,
        "selinux": selinux,
        "props": props,
        "catalog_match": match,
        "gates": gates,
        "github_repo": GITHUB_REPO_URL,
        "builtin_ai_skills": [SKILL_PATH_AGENTS, SKILL_PATH_CLAUDE],
        "ai_prompt_zh": build_ai_prompt(props, match, gates, lang="zh"),
        "ai_prompt_en": build_ai_prompt(props, match, gates, lang="en"),
    }
    audit["formatted_zh"] = format_readiness_audit(audit, lang="zh")
    audit["formatted_en"] = format_readiness_audit(audit, lang="en")
    return audit


def format_readiness_audit(audit: dict[str, Any], lang: str = "zh") -> str:
    props = audit.get("props", {})
    match = audit.get("catalog_match", {})
    gates = audit.get("gates", {})
    brand = props.get("ro.product.brand") or "Unknown"
    model = props.get("ro.product.model") or "Unknown"
    device = props.get("ro.product.device") or "unknown"
    plat = props.get("ro.board.platform") or "unknown"
    abi = props.get("ro.product.cpu.abi") or "unknown"
    sdk = props.get("ro.build.version.sdk") or "?"
    rel = props.get("ro.build.version.release") or "?"
    tags = props.get("ro.build.tags") or "?"
    bb = props.get("gsm.version.baseband") or "?"

    if lang == "en":
        return (
            "=== Qcom32 VoLTE · 32-Bit Device Identification & Read-Only 4-Gate Report ===\n"
            f"Device        : {brand} {model} (codename: {device})\n"
            f"Platform / ABI: {plat} / {abi} | Android {rel} (SDK {sdk}, {tags})\n"
            f"Baseband      : {bb} | SELinux: {audit.get('selinux')} | Shell UID: {audit.get('shell_uid')}\n"
            f"Catalog Match : {match.get('name_en')} [{match.get('family_id')}]\n"
            f"Route         : {match.get('route')}\n"
            f"Assessment    : {match.get('summary_en')}\n\n"
            "[4-Gate 100% Read-Only Readiness Check (Zero EFS / Flash Writes)]\n"
            f"  Gate 1 (ROM & Keys)   : {gates['gate1_rom']['detail_en']}\n"
            f"  Gate 2 (RIL & /dev/diag): {gates['gate2_ril_diag']['detail_en']}\n"
            f"  Gate 3 (Modem Engine) : {gates['gate3_modem_ims']['detail_en']}\n"
            f"  Gate 4 (Audio HAL)    : {gates['gate4_audio_hal']['detail_en']}\n\n"
            "[Self-Adapt Your Phone Safely with AI + GitHub Source-Available Project]\n"
            "  Why EFS writes are guarded: only one wolverine unit was verified; other Passport editions and\n"
            "  other phones are unverified. Samsung S5, Xiaomi Mi 4, OnePlus One,\n"
            "  Sony Z2/Z3, and LG G3 may store MMS or mobile-data APNs in `profile4`. Blind writes would break data.\n\n"
            f"  Step 1: Clone the source-available repository (PolyForm Noncommercial 1.0.0; commercial license available) on your computer:\n"
            f"          git clone {GITHUB_REPO_URL}\n"
            "  Step 2: Open the cloned folder in Claude Code, Cursor, Codex, Gemini CLI, or Antigravity.\n"
            f"          It automatically loads `{SKILL_PATH_AGENTS}` & `{SKILL_PATH_CLAUDE}`!\n"
            "  Step 3: Paste the ready-to-use AI prompt below to your AI coding assistant:\n"
            "  ------------------------------------------------------------------------\n"
            f"  {audit.get('ai_prompt_en')}\n"
            "  ------------------------------------------------------------------------\n"
        )

    return (
        "=== Qcom32 VoLTE · 多机型识别与 100% 只读四门禁适配体检报告 ===\n"
        f"当前设备   : {brand} {model} (代号: {device})\n"
        f"平台与系统 : {plat} / {abi} | Android {rel} (SDK {sdk}, {tags})\n"
        f"基带与安全 : {bb} | SELinux: {audit.get('selinux')} | Shell UID: {audit.get('shell_uid')}\n"
        f"识别机系   : {match.get('name_zh')} [{match.get('family_id')}]\n"
        f"适配路由   : {match.get('route')}\n"
        f"结论说明   : {match.get('summary_zh')}\n\n"
        "【四门禁 100% 只读体检结果（零基带写入 / 不改动手机任何设置）】\n"
        f"  Gate 1 (ROM 与签名) : {gates['gate1_rom']['detail_zh']}\n"
        f"  Gate 2 (RIL 与诊断) : {gates['gate2_ril_diag']['detail_zh']}\n"
        f"  Gate 3 (基带引擎)   : {gates['gate3_modem_ims']['detail_zh']}\n"
        f"  Gate 4 (音频通路)   : {gates['gate4_audio_hal']['detail_zh']}\n\n"
        "【如何使用 AI + GitHub 源码可见项目（PolyForm Noncommercial 1.0.0 + 商业授权）自行对应适配本机型？】\n"
        "  为什么基带写入默认受限：仅一台 wolverine 已验证；其他 Passport 版本和其他机型均未验证，\n"
        "  不能假设它们共用同一 EFS 布局；\n"
        "  而三星 S5、小米 4、一加 1、索尼 Z2/Z3、LG G3 等机型的 `profile4` 往往存着上网或彩信 APN，盲写会破坏移动数据。\n\n"
        f"  第 1 步：在电脑上克隆源码可见项目仓库：\n"
        f"          git clone {GITHUB_REPO_URL}\n"
        "  第 2 步：用 Claude Code、Cursor、Codex、Gemini CLI 或 Antigravity 打开克隆下来的项目文件夹\n"
        f"          （项目已内置 `{SKILL_PATH_AGENTS}` 与 `{SKILL_PATH_CLAUDE}` 智能体适配技能，AI 会自动识别！）。\n"
        "  第 3 步：手机保持 USB 调试连接电脑，直接复制下方【AI 一键适配提示词】发给 AI 助手即可安全对应：\n"
        "  ------------------------------------------------------------------------\n"
        f"  {audit.get('ai_prompt_zh')}\n"
        "  ------------------------------------------------------------------------\n"
    )


def format_catalog_overview(lang: str = "zh") -> str:
    if lang == "en":
        lines = [
            "=== Qcom32 VoLTE · Supported Editions & 32-Bit Qualcomm Porting Matrix ===",
            "",
            "[1] One wolverine unit verified; other Passport editions experimental:",
            f"  * {CATALOG_FAMILIES[0]['name_en']}",
            f"    Codenames: {', '.join(CATALOG_FAMILIES[0]['codenames'])} | Models: SQW100-1/2/3/4 (incl. Silver Edition)",
            "",
            "[2] Built-In Identification + Read-Only 4-Gate Check + AI & GitHub Self-Adaptation:",
        ]
        for fam in CATALOG_FAMILIES[1:]:
            lines.append(f"  * {fam['name_en']} [{fam['family_id']}]")
            lines.append(f"    Codenames: {', '.join(fam['codenames'][:6])} | {fam['status_en']}")
        lines.extend(
            [
                "",
                "[3] Known Hardware Blockers Automatically Flagged:",
                "  * Xiaomi Mi 4W / Mi 3W (`pisces` — 3G-only WCDMA hardware, no LTE modem)",
                "  * Samsung SM-G900H (`k3gxx` Exynos) & locked AT&T/Verizon SM-G900A / SM-G900V",
                "  * Google Nexus 5 (`hammerhead` — stock modem lacks Hexagon `qipcall` IMS engine)",
                "",
                "[How to Self-Adapt Any Candidate 32-Bit Qualcomm Phone with AI + GitHub]",
                "  1. Connect your phone via USB and click [Run Read-Only 4-Gate Check on Connected Phone].",
                f"  2. Clone `{GITHUB_REPO_URL}` and open it in Claude Code / Cursor / Codex / Gemini CLI / Antigravity.",
                f"  3. The repo ships `{SKILL_PATH_AGENTS}` — paste the generated report & prompt to let AI",
                "     inspect `/pdp_profiles/*` read-only and create your model's safe `devices/<id>/profile.json`!",
            ]
        )
        return "\n".join(lines) + "\n"

    lines = [
        "=== Qcom32 VoLTE · 已放开版本与高通 32 位机型只读体检 / AI 自助适配矩阵 ===",
        "",
        "【一、仅一台 wolverine 真机已验证；其他 Passport 版本实验性只读】",
        f"  * {CATALOG_FAMILIES[0]['name_zh']}",
        f"    覆盖代号：{', '.join(CATALOG_FAMILIES[0]['codenames'])} | 型号：SQW100-1 / 2 / 3 / 4（含银色版 Silver Edition）",
        "",
        "【二、内置识别 + 100% 只读四门禁体检 + AI 配合 GitHub 自行对应适配的机型】",
    ]
    for fam in CATALOG_FAMILIES[1:]:
        lines.append(f"  * {fam['name_zh']} [{fam['family_id']}]")
        lines.append(f"    代号：{', '.join(fam['codenames'][:6])} | {fam['status_zh']}")
    lines.extend(
        [
            "",
            "【三、自动拦截提示的硬件不兼容变体（避免白刷机）】",
            "  * 小米 4 联通/电信 3G 版 `MI 4W` / 小米 3 联通版 `pisces`（无 4G LTE 基带硬件）",
            "  * 三星 Galaxy S5 双四核猎户座版 `SM-G900H` (`k3gxx`) 与美版锁 BL 定制款 `SM-G900A` / `SM-G900V`",
            "  * Google Nexus 5 (`hammerhead` — 原厂基带裁剪了 Hexagon `qipcall` IMS 语音引擎)",
            "",
            "【四、其他高通 32 位机型如何用 AI + GitHub 项目自行对应？】",
            "  1. 将手机开启 USB 调试连上电脑，点击下方【对当前连接手机执行 100% 只读四门禁体检】；",
            f"  2. 在电脑克隆源码可见仓库：`git clone {GITHUB_REPO_URL}`；",
            "  3. 用 Claude Code / Cursor / Codex / Gemini CLI / Antigravity 打开该仓库目录",
            f"     （仓库内置 `{SKILL_PATH_AGENTS}` 与 `{SKILL_PATH_CLAUDE}` 智能体适配技能）；",
            "  4. 点击【复制体检报告与 AI 提示词】粘贴给 AI，AI 会按四门禁安全只读解码本机 `/pdp_profiles/*`，",
            "     避开上网与彩信槽位、先备份原值，再为您生成该机型的专属 `devices/<id>/profile.json`！",
        ]
    )
    return "\n".join(lines) + "\n"


def run_unit_tests() -> int:
    assertions = 0

    def check(cond: bool, msg: str) -> None:
        nonlocal assertions
        if not cond:
            raise AssertionError(f"device_readiness test failed: {msg}")
        assertions += 1

    # 1. Only the wolverine codename is verified; others remain read-only.
    for dev, mod in (
        ("wolverine", "Passport"),
        ("oslo", "Passport Silver Edition"),
        ("oslo", "SQW100-4"),
        ("wichita", "Passport"),
        ("keian", "Passport Porsche Design"),
        ("wseries", "BlackBerry Passport"),
        ("windermere", "Passport"),
    ):
        m = identify_device_props(
            {
                "ro.product.brand": "blackberry",
                "ro.product.model": mod,
                "ro.product.device": dev,
                "ro.board.platform": "msm8974",
                "ro.product.cpu.abi": "armeabi-v7a",
                "ro.build.version.sdk": "30",
                "ro.build.tags": "test-keys",
            }
        )
        expected = "VERIFIED_PASSPORT_READY" if dev == "wolverine" else "EXPERIMENTAL_UNVERIFIED_EDITION"
        check(m["route"] == expected, f"{dev}/{mod} expected {expected}")
        check(m["prebuilt_apk_compatible"] is True, f"{dev}/{mod} test-keys compatible")

    # 2. Candidate 32-bit Qualcomm models
    s5 = identify_device_props(
        {
            "ro.product.brand": "samsung",
            "ro.product.model": "SM-G900F",
            "ro.product.device": "klte",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "release-keys",
        }
    )
    check(s5["route"] == "CANDIDATE_MSM8974_PORTING" and s5["family_id"] == "samsung-galaxy-s5-klte", "Galaxy S5 klte")
    check(s5["prebuilt_apk_compatible"] is False, "release-keys flagged")

    mi4 = identify_device_props(
        {
            "ro.product.brand": "Xiaomi",
            "ro.product.model": "MI 4LTE",
            "ro.product.device": "cancro",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
        }
    )
    check(mi4["route"] == "CANDIDATE_MSM8974_PORTING" and mi4["family_id"] == "xiaomi-mi4-cancro", "Mi 4LTE cancro")

    opo = identify_device_props(
        {
            "ro.product.brand": "oneplus",
            "ro.product.model": "A0001",
            "ro.product.device": "bacon",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "29",
            "ro.build.tags": "test-keys",
        }
    )
    check(opo["route"] == "NEEDS_ANDROID11_ROM_FIRST" and opo["family_id"] == "oneplus-one-bacon", "OnePlus One SDK 29")

    # 3. Hardware blockers
    mi4w = identify_device_props(
        {
            "ro.product.brand": "Xiaomi",
            "ro.product.model": "MI 4W",
            "ro.product.device": "cancro",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
        }
    )
    check(mi4w["route"] == "UNSUPPORTED_HARDWARE_VARIANT", "MI 4W 3G blocker")

    g900h = identify_device_props(
        {
            "ro.product.brand": "samsung",
            "ro.product.model": "SM-G900H",
            "ro.product.device": "k3gxx",
            "ro.board.platform": "universal5422",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
        }
    )
    check(g900h["route"] == "UNSUPPORTED_NON_QUALCOMM", "SM-G900H Exynos blocker")

    n5 = identify_device_props(
        {
            "ro.product.brand": "google",
            "ro.product.model": "Nexus 5",
            "ro.product.device": "hammerhead",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
        }
    )
    check(n5["route"] == "MODEM_LACKS_IMS_ENGINE", "Nexus 5 hammerhead blocker")

    arm64 = identify_device_props(
        {
            "ro.product.brand": "oneplus",
            "ro.product.model": "ONEPLUS A3000",
            "ro.product.device": "oneplus3",
            "ro.board.platform": "msm8996",
            "ro.product.cpu.abi": "arm64-v8a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
        }
    )
    check(arm64["route"] == "UNSUPPORTED_64BIT_PLATFORM", "64-bit blocker")

    # 4. Overview & search & mock ADB read-only audit
    zh_ov = format_catalog_overview("zh")
    en_ov = format_catalog_overview("en")
    check(GITHUB_REPO_URL in zh_ov and SKILL_PATH_AGENTS in zh_ov, "ZH overview links GitHub & Skill")
    check(GITHUB_REPO_URL in en_ov and "Silver Edition" in en_ov, "EN overview mentions Silver Edition & GitHub")
    check(len(list_catalog_entries("oslo")) == 1, "Search oslo finds Passport family")
    check(len(list_catalog_entries("D6603")) == 1, "Search D6603 finds Xperia Z3")

    class _MockAdb:
        def shell(self, cmd: str, timeout: int = 15, check: bool = False) -> str:
            table = {
                "getprop ro.product.brand": "samsung",
                "getprop ro.product.manufacturer": "samsung",
                "getprop ro.product.model": "SM-G900F",
                "getprop ro.product.device": "klte",
                "getprop ro.board.platform": "msm8974",
                "getprop ro.product.cpu.abi": "armeabi-v7a",
                "getprop ro.build.version.sdk": "30",
                "getprop ro.build.version.release": "11",
                "getprop ro.build.display.id": "lineage_klte-userdebug 11 RQ3A test-keys",
                "getprop ro.build.tags": "test-keys",
                "getprop gsm.version.baseband": "G900FXXU1CQK3",
                "getprop gsm.sim.operator.numeric": "46001",
                "getprop gsm.network.type": "LTE",
                "id -u": "0",
                "getenforce": "Enforcing",
            }
            if cmd in table:
                return table[cmd]
            if "rild_ims0" in cmd:
                return "srw-rw---- 1 radio radio 0 /dev/socket/qmux_radio/rild_ims0"
            if "/dev/diag" in cmd:
                return "crw-rw---- 1 system qcom_diag 249, 0 /dev/diag"
            if "mixer_paths" in cmd:
                return "/vendor/etc/mixer_paths.xml"
            if "qp_ims_reg_config" in cmd:
                return "/firmware/image/modem.b18"
            return ""

    mock_audit = run_readonly_readiness_audit(_MockAdb())
    check(mock_audit["readonly_zero_writes"] is True, "Read-only flag true")
    check(mock_audit["gates"]["gate1_rom"]["status"] == "PASS", "Mock Gate 1 PASS")
    check(mock_audit["gates"]["gate2_ril_diag"]["status"] == "PASS", "Mock Gate 2 PASS")
    check(mock_audit["gates"]["gate3_modem_ims"]["status"] == "PASS", "Mock Gate 3 PASS")
    check(mock_audit["gates"]["gate4_audio_hal"]["status"] == "PASS", "Mock Gate 4 PASS")
    check(GITHUB_REPO_URL in mock_audit["formatted_zh"], "Formatted ZH report has GitHub URL")
    check(SKILL_PATH_AGENTS in mock_audit["ai_prompt_en"], "AI prompt EN cites skill path")

    return assertions
