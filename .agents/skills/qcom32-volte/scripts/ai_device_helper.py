#!/usr/bin/env python3
"""AI-oriented device identifier, compatibility router, read-only porting inspector,
789-byte PDP profile decoder, and `devices/*/profile.json` validator for `qcom32-volte`.

Zero third-party Python dependencies (Python 3.9+ stdlib only).
Strictly read-only when inspecting connected USB devices (ADB / Fastboot):
  - Never writes modem EFS, NV items, partitions, or persistent system properties.
  - Never lowers SELinux mode or dials phone numbers.
  - Automatically redacts long digit sequences (IMEI / IMSI / ICCID / MSISDN).

Subcommands:
  python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py self-test
  python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py catalog [--query klte]
  python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py inspect [--adb PATH] [--serial ID]
  python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py decode-pdp <789B-bin-or-hex-file>
  python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py validate-profile [path-or-all]
  python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py scaffold-profile --profile-id ID --device CODENAME --model-contains STR --display-name NAME [--ims-pdp-path /pdp_profiles/profile4] [--out PATH]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Any

PII_DIGITS_RE = re.compile(r"(?<![0-9a-fA-F])\b\d{11,20}\b(?![0-9a-fA-F])")

SUPPORTED_32BIT_PLATFORMS = {"msm8974", "apq8084", "msm8226", "msm8926", "msm8610"}

PDP_TYPE_NAMES = {
    0: "IPV4",
    1: "PPP",
    2: "IPV6",
    3: "IPV4V6",
}

DEVICE_FAMILIES: tuple[dict[str, Any], ...] = (
    {
        "family_id": "blackberry-passport",
        "brand": "BlackBerry",
        "marketing_names": [
            "BlackBerry Passport",
            "Passport Silver Edition",
            "Passport AT&T",
            "Passport Porsche Design",
            "SQW100-1",
            "SQW100-2",
            "SQW100-3",
            "SQW100-4",
            "Oslo",
            "Wichita",
            "Keian",
        ],
        "codenames": ["wolverine", "oslo", "wichita", "keian", "wseries", "windermere"],
        "soc": "Snapdragon 801 (MSM8974AA/AC, 32-bit ARMv7-A)",
        "support_tier": "verified",
        "verified_profile_id": "blackberry-passport-wolverine",
        "flash_protocol": "BlackBerry autoloader / emmc + LineageOS 18.1-wseries recovery",
        "recommended_variants": [
            "SQW100-1 (Standard Global/NA)",
            "SQW100-2 (wichita AT&T Rounded Edition)",
            "SQW100-3",
            "SQW100-4 (oslo Passport Silver Edition)",
            "Keian (Porsche Design Edition) — all running LineageOS 18.1 Android 11",
        ],
        "blocked_or_risky_variants": [
            "Phones still running stock BlackBerry 10 OS (QNX 10.3.3): do NOT use qcom32-volte; use sister repo Octo-o-o-o/bb10-volte instead."
        ],
        "notes": "All BlackBerry Passport hardware editions (wolverine / oslo Silver Edition / wichita / keian / wseries) share unified lineage_wseries + NON-HLOS.bin modem and are unlocked for guarded 1-click deploy, APK upgrade, companion app, China Telecom 46011 fallbackmode=9 stabilization, and full 54-item EFS rollback.",
    },
    {
        "family_id": "samsung-galaxy-s5-klte",
        "brand": "Samsung",
        "marketing_names": ["Galaxy S5", "SM-G900F", "SM-G900M", "SM-G900T", "SM-G900W8", "SM-G900I", "SM-G9006V", "SM-G9008V", "SM-G9009D", "SM-G9009W", "SM-G900P", "SCL23", "SC-04F"],
        "codenames": ["klte", "kltechnduo", "klteduos", "kltedv", "kltekdi", "kltekor", "klteactivexx", "kltespr"],
        "soc": "Snapdragon 801 (MSM8974AC, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "Download Mode (VolDown+Home+Power) via Heimdall CLI or Odin -> TWRP / Lineage Recovery -> LineageOS 18.1",
        "recommended_variants": ["SM-G900F (Global)", "SM-G9006V (China Unicom)", "SM-G9008V (China Mobile)", "SM-G900M", "SM-G900T", "SM-G900W8"],
        "blocked_or_risky_variants": [
            "SM-G900A (AT&T): Permanently locked bootloader — cannot flash custom Android 11.",
            "SM-G900V (Verizon eMMC CID 11): Locked bootloader (only Samsung CID 15 eMMC units can unlock bootloader).",
            "SM-G900H (k3gxx): Exynos 5422 SoC, NOT Qualcomm — incompatible with qcom32-volte.",
        ],
        "notes": "Mature official/unofficial LineageOS 18.1 support and complete Samsung IMS baseband firmware. Audit /pdp_profiles/* in read-only mode before creating devices/samsung-s5-klte/profile.json.",
    },
    {
        "family_id": "xiaomi-mi4-minote-cancro",
        "brand": "Xiaomi",
        "marketing_names": ["Xiaomi Mi 4 LTE", "MI 4LTE", "Xiaomi Mi Note", "MI NOTE LTE"],
        "codenames": ["cancro", "virgo"],
        "soc": "Snapdragon 801 (MSM8974AC, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "Fastboot Mode (VolDown+Power) -> TWRP (requires 1.2GB merged system partition layout) -> LineageOS 18.1",
        "recommended_variants": ["MI 4LTE China Unicom 4G", "MI 4LTE China Telecom 4G", "MI NOTE LTE (virgo)"],
        "blocked_or_risky_variants": [
            "MI 4W / MI 4C (3G) / MI 3W (pisces): 3G-only WCDMA/CDMA2000 hardware — physically lacks a 4G LTE baseband, VoLTE is impossible.",
            "Mi 4c (libra) / Mi 4s (aqua): 64-bit Snapdragon 808 (MSM8992), NOT 32-bit MSM8974.",
            "MI 4LTE China Mobile TDD-only edition: Limited FDD-LTE bands outside TDD B38-41 coverage.",
        ],
        "notes": "Easy fastboot workflow without legacy unlock servers on stock bootloaders, but ensure system1+system2 partitions are merged before flashing Android 11.",
    },
    {
        "family_id": "lg-g3-g2-msm8974",
        "brand": "LG",
        "marketing_names": ["LG G3", "LG-D855", "LG-D850", "LG-D851", "LG-D852", "LG-F400", "VS985", "LS990", "LG G2", "LG-D802"],
        "codenames": ["d855", "d850", "d851", "d852", "f400", "vs985", "ls990", "g3", "d802", "d800", "d801", "g2"],
        "soc": "Snapdragon 801 / 800 (MSM8974AC / MSM8974, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "Stock KitKat/Lollipop KDZ downgrade -> Root -> Bump! / TWRP injection -> LineageOS 18.1 (except D851 which supports fastboot oem unlock)",
        "recommended_variants": ["LG-D855 (Global single-SIM)", "LG-F400S/K/L (Korea)", "LG-D851 (T-Mobile unlocked BL)", "LG-D802"],
        "blocked_or_risky_variants": [
            "LG-D858 / LG-D858HK / LG-D859 (China Dual-SIM): Dual-SIM RIL diverges and stable LineageOS 18.1 trees are largely unavailable.",
            "Flashing mismatched variant aboot/boot partitions on LG G3 triggers immediate Qualcomm HS-USB QDLoader 9008 brick.",
        ],
        "notes": "Highest bootloader-prep complexity among MSM8974 flagships; verify exact model sticker under battery and current Android build before touching boot/recovery.",
    },
    {
        "family_id": "oneplus-one-bacon",
        "brand": "OnePlus",
        "marketing_names": ["OnePlus One", "A0001", "One"],
        "codenames": ["bacon", "A0001"],
        "soc": "Snapdragon 801 (MSM8974AC, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "Fastboot Mode (VolUp+Power) -> fastboot oem unlock -> TWRP / Lineage Recovery -> LineageOS 18.1",
        "recommended_variants": ["A0001 Global 64GB/16GB", "A0001 China edition (check FDD band mask)"],
        "blocked_or_risky_variants": [],
        "notes": "Developer-friendly bootloader and clean CAF MSM8974 HALs; prime candidate for porting after read-only /pdp_profiles/* inspection.",
    },
    {
        "family_id": "sony-xperia-z1-z2-z3",
        "brand": "Sony",
        "marketing_names": ["Xperia Z1", "Xperia Z1 Compact", "Xperia Z2", "Xperia Z3", "Xperia Z3 Compact", "D6503", "D6603", "D6653", "D5803", "D5833"],
        "codenames": ["honami", "amami", "sirius", "leo", "z3", "aries", "z3c", "z3dual"],
        "soc": "Snapdragon 800 / 801 (MSM8974 / MSM8974AB / MSM8974AC, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "Fastboot Mode (VolUp + USB plug, blue LED) -> Sony Open Devices unlock -> TWRP / Lineage Recovery -> LineageOS 18.1",
        "recommended_variants": ["D6603 / D6653 (Xperia Z3)", "D5803 / D5833 (Z3 Compact)", "D6503 (Xperia Z2)"],
        "blocked_or_risky_variants": [
            "Carrier-locked Japan/US variants (SO-01G, SOL26, 401SO, D6616) where Service Menu (*#*#7378423#*#*) shows 'Bootloader unlock allowed: No'.",
        ],
        "notes": "Back up Sony Trim Area (TA / DRM keys) if still on stock Android before unlocking bootloader.",
    },
    {
        "family_id": "htc-one-m8",
        "brand": "HTC",
        "marketing_names": ["HTC One M8", "One (M8)"],
        "codenames": ["m8", "m8wl", "m8d"],
        "soc": "Snapdragon 801 (MSM8974AB, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "HBOOT Fastboot -> HTCDev unlock token -> TWRP -> LineageOS 18.1",
        "recommended_variants": ["International GSM M8_UL", "Developer Edition"],
        "blocked_or_risky_variants": [
            "Verizon / AT&T M8 without S-OFF cannot unlock via standard HTCDev.",
        ],
        "notes": "Standard CAF RIL/QMI layout on LineageOS 18.1; verify Voice-MMode1 in mixer_paths.xml.",
    },
    {
        "family_id": "moto-x-2014-nexus6",
        "brand": "Motorola / Google",
        "marketing_names": ["Moto X (2nd Gen 2014)", "XT1092", "XT1095", "XT1097", "Nexus 6", "XT1100", "XT1103"],
        "codenames": ["victara", "shamu"],
        "soc": "Snapdragon 801 (MSM8974AC) / Snapdragon 805 (APQ8084 + MDM9625M, 32-bit ARMv7-A)",
        "support_tier": "candidate_port",
        "verified_profile_id": None,
        "flash_protocol": "Fastboot Mode -> fastboot oem unlock -> TWRP / Lineage Recovery -> LineageOS 18.1",
        "recommended_variants": ["Nexus 6 (shamu, factory VoLTE-capable MDM9625M modem)", "XT1092 / XT1095 Pure Edition"],
        "blocked_or_risky_variants": [
            "XT1096 (Verizon) / XT1097 (AT&T) locked bootloaders.",
        ],
        "notes": "Nexus 6 (shamu) shipped with official Android 5.1+ VoLTE modem firmware, making its baseband IMS stack well-populated.",
    },
    {
        "family_id": "google-nexus-5-hammerhead",
        "brand": "Google / LG",
        "marketing_names": ["Nexus 5", "LG-D820", "LG-D821"],
        "codenames": ["hammerhead"],
        "soc": "Snapdragon 800 (MSM8974, 32-bit ARMv7-A)",
        "support_tier": "unsupported_modem",
        "verified_profile_id": None,
        "flash_protocol": "Fastboot Mode (fastboot oem unlock)",
        "recommended_variants": [],
        "blocked_or_risky_variants": [
            "All stock Nexus 5 (D820/D821) basebands: Google shipped hammerhead radio firmware WITHOUT Hexagon IMS / qipcall VoLTE services compiled in.",
        ],
        "notes": "Do NOT promise VoLTE on Nexus 5 unless read-only modem ELF inspection explicitly proves qp_ims_reg_config and qipcall symbols exist.",
    },
)


def discover_repo_root(explicit: str | None = None) -> Path:
    if explicit:
        return Path(explicit).expanduser().resolve()
    cur = Path(__file__).resolve().parent
    for candidate in (cur, *cur.parents):
        if (candidate / "project.json").is_file() and (candidate / "devices").is_dir() and (candidate / "cli/volte_ctl.py").is_file():
            return candidate
    return Path(__file__).resolve().parents[4]


def sanitize_text(text: str) -> str:
    return PII_DIGITS_RE.sub("<REDACTED_DIGITS>", text)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def ascii_z(buf: bytes) -> str:
    nul = buf.find(0)
    raw = buf[:nul] if nul >= 0 else buf
    return raw.decode("ascii", errors="replace").strip()


def subprocess_no_window_kwargs() -> dict[str, object]:
    if os.name == "nt":
        return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)}
    return {}


def load_repo_profiles(repo_root: Path) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    dev_dir = repo_root / "devices"
    if dev_dir.is_dir():
        for p in sorted(dev_dir.glob("*/profile.json")):
            data = json.loads(p.read_text(encoding="utf-8"))
            data["_path"] = p.relative_to(repo_root).as_posix()
            out.append(data)
    return out


def match_repo_profile(profiles: list[dict[str, Any]], device: str, model: str, sdk_int: int) -> dict[str, Any] | None:
    dev_norm = device.strip().lower()
    mod_norm = model.strip().lower()
    for prof in profiles:
        m = prof.get("match", {})
        if not isinstance(m, dict):
            continue
        if int(m.get("android_sdk", 30)) != sdk_int:
            continue
        raw_devs = m.get("ro_product_device_aliases")
        dev_aliases = (
            [str(x).strip().lower() for x in raw_devs if str(x).strip()]
            if isinstance(raw_devs, list) and raw_devs
            else [str(m.get("ro_product_device", "")).strip().lower()]
        )
        raw_mods = m.get("ro_product_model_aliases")
        mod_aliases = (
            [str(x).strip().lower() for x in raw_mods if str(x).strip()]
            if isinstance(raw_mods, list) and raw_mods
            else [str(m.get("ro_product_model_contains", "")).strip().lower()]
        )
        if dev_norm in dev_aliases and any(tok and tok in mod_norm for tok in mod_aliases):
            return prof
    return None


def search_catalog(query: str | None = None) -> list[dict[str, Any]]:
    if not query or not query.strip():
        return list(DEVICE_FAMILIES)
    q = query.strip().lower()
    hits: list[dict[str, Any]] = []
    for fam in DEVICE_FAMILIES:
        haystack = " ".join(
            [
                str(fam["family_id"]),
                str(fam["brand"]),
                str(fam["soc"]),
                *fam.get("marketing_names", []),
                *fam.get("codenames", []),
                *fam.get("recommended_variants", []),
                *fam.get("blocked_or_risky_variants", []),
            ]
        ).lower()
        if q in haystack:
            hits.append(fam)
    return hits


def match_catalog_family(device: str, model: str, brand: str = "") -> dict[str, Any] | None:
    dev_l = device.strip().lower()
    mod_l = model.strip().lower()
    br_l = brand.strip().lower()
    for fam in DEVICE_FAMILIES:
        codes = [c.strip().lower() for c in fam.get("codenames", [])]
        if dev_l and dev_l in codes:
            return fam
        for code in codes:
            if code and len(code) >= 4 and dev_l.startswith(code):
                return fam
    for fam in DEVICE_FAMILIES:
        for mname in fam.get("marketing_names", []):
            mn_l = mname.strip().lower()
            if mn_l and mn_l in mod_l:
                return fam
        if br_l and br_l == str(fam["brand"]).lower() and dev_l in ("pisces", "k3gxx", "libra", "aqua"):
            return fam
    return None


def detect_specific_variant_blocker(device: str, model: str, platform: str, abi: str) -> tuple[str | None, str | None]:
    dev_l = device.strip().lower()
    mod_u = model.strip().upper()
    plat_l = platform.strip().lower()
    abi_l = abi.strip().lower()

    if abi_l.startswith("arm64"):
        return (
            "UNSUPPORTED_64BIT_PLATFORM",
            f"Device ABI is 64-bit ({abi!r}, platform={platform!r}). `qcom32-volte` targets legacy 32-bit Qualcomm ARMv7-A platforms (MSM8974 / APQ8084 / MSM8926) lacking modern vendor ImsService.",
        )
    if plat_l and any(tag in plat_l for tag in ("exynos", "universal5422", "mt6", "mt8", "hi36", "kirin", "tegra")):
        return (
            "UNSUPPORTED_NON_QUALCOMM",
            f"Board platform {platform!r} ({model!r} / {device!r}) is not a Qualcomm Snapdragon platform.",
        )
    if dev_l in ("k3gxx", "k3g") or "G900H" in mod_u:
        return (
            "UNSUPPORTED_NON_QUALCOMM",
            "Samsung Galaxy S5 SM-G900H (k3gxx) uses Samsung Exynos 5422 rather than Qualcomm MSM8974.",
        )
    if dev_l == "pisces" or mod_u in ("MI 4W", "MI 3W", "MI 4C"):
        return (
            "UNSUPPORTED_HARDWARE_VARIANT",
            f"Xiaomi model {model!r} ({device!r}) is a 3G-only (WCDMA/CDMA) hardware edition without a 4G LTE baseband; hardware VoLTE is impossible.",
        )
    if "G900A" in mod_u:
        return (
            "UNSUPPORTED_HARDWARE_VARIANT",
            "AT&T Galaxy S5 (SM-G900A) has a permanently locked bootloader and cannot run LineageOS 18.1.",
        )
    if dev_l == "hammerhead" or mod_u in ("NEXUS 5", "LG-D820", "LG-D821"):
        return (
            "MODEM_LACKS_IMS_ENGINE",
            "Google Nexus 5 (hammerhead) stock Qualcomm baseband omits the Hexagon IMS / qipcall engine. Verify modem ELF symbols before attempting any port.",
        )
    return None, None


def assess_rom_signature(build_tags: str, build_display: str) -> dict[str, Any]:
    tags_l = build_tags.strip().lower()
    disp_l = build_display.strip().lower()
    uses_test_keys = "test-keys" in tags_l
    if uses_test_keys:
        return {
            "build_tags": build_tags,
            "prebuilt_apk_compatible": True,
            "summary": "ROM uses standard AOSP `test-keys`; prebuilt `Qcom32VolteIms.apk` (`sharedUserId=android.uid.phone`) can be installed directly via `adb install -r`.",
        }
    return {
        "build_tags": build_tags or "unknown",
        "prebuilt_apk_compatible": False,
        "summary": (
            f"ROM reports `{build_tags or 'release-keys'}` ({disp_l or 'custom/official build'}). "
            "Installing an AOSP-testkey-signed APK with `sharedUserId=android.uid.phone` via `adb install` will fail with "
            "`INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`. Either: (1) sign `Qcom32VolteIms.apk` with your ROM's `platform.pk8`/`platform.x509.pem` "
            "via `python3 cli/build_all.py --sign-key ... --sign-cert ...`, (2) integrate `rom-integration/` into your LineageOS 18.1 source tree, "
            "or (3) flash an AOSP/self-signed LineageOS 18.1 build."
        ),
    }


def analyze_mixer_paths_xml(xml_text: str) -> dict[str, Any]:
    markers = ("Voice-MMode1", "voice-ims", "compress-voip", "VoLTE", "VOLTE", "QCHAT")
    found = [m for m in markers if m in xml_text]
    has_mmode1_or_ims = any(m in found for m in ("Voice-MMode1", "voice-ims", "VoLTE", "VOLTE"))
    return {
        "has_volte_audio_markers": has_mmode1_or_ims,
        "matched_markers": found,
    }


def decode_pdp_profile_bytes(buf: bytes) -> dict[str, Any]:
    if len(buf) != 789:
        return {
            "valid_789b_struct": False,
            "byte_length": len(buf),
            "sha256": sha256_bytes(buf),
            "error": f"Expected 789-byte ds_umts_pdp_profile_type regular file, got {len(buf)} bytes",
        }
    version = buf[0]
    prof_name = ascii_z(buf[2:18])
    context_valid = buf[34]
    prof_num = buf[35]
    pdp_type_raw = buf[36]
    pdp_type = PDP_TYPE_NAMES.get(pdp_type_raw, f"UNKNOWN({pdp_type_raw})")
    apn_name = ascii_z(buf[61:165])
    im_cn_flag = buf[750]
    req_pcscf_flag = buf[751]
    dhcp_pcscf_flag = buf[752]
    apn_disabled_flag = buf[753]

    is_ims_apn = apn_name.lower() == "ims" or prof_name.lower() == "ims" or im_cn_flag == 1
    if is_ims_apn and im_cn_flag == 1 and req_pcscf_flag == 1 and apn_disabled_flag == 0 and pdp_type_raw in (2, 3):
        slot_classification = "IMS_READY_SLOT"
    elif is_ims_apn:
        slot_classification = "IMS_CANDIDATE_NEEDS_STAGE6A_FLAGS"
    else:
        slot_classification = "NON_IMS_DATA_OR_MMS_DO_NOT_OVERWRITE"

    return {
        "valid_789b_struct": version == 0x15,
        "byte_length": 789,
        "sha256": sha256_bytes(buf),
        "struct_version": f"0x{version:02x}",
        "profile_number": prof_num,
        "profile_name": prof_name,
        "apn_name": apn_name,
        "context_valid_flag": context_valid,
        "pdp_type_code": pdp_type_raw,
        "pdp_type": pdp_type,
        "im_cn_flag_b750": im_cn_flag,
        "request_pcscf_flag_b751": req_pcscf_flag,
        "dhcp_pcscf_flag_b752": dhcp_pcscf_flag,
        "apn_disabled_flag_b753": apn_disabled_flag,
        "slot_classification": slot_classification,
    }


def validate_profile_dict(prof: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    required_top = ("profile_id", "display_name", "status", "soc", "match", "modem_provisioning", "ims_service")
    for k in required_top:
        if k not in prof:
            errors.append(f"Missing top-level field `{k}`")
    status = prof.get("status")
    if status not in ("verified", "experimental", "draft"):
        errors.append(f"`status` must be 'verified', 'experimental', or 'draft' (got {status!r})")

    pid = str(prof.get("profile_id", ""))
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)+", pid):
        errors.append(f"`profile_id` must be lowercase kebab-case (got {pid!r})")

    m = prof.get("match")
    if not isinstance(m, dict):
        errors.append("`match` must be an object")
    else:
        if not str(m.get("ro_product_device", "")).strip():
            errors.append("`match.ro_product_device` must be non-empty")
        if not str(m.get("ro_product_model_contains", "")).strip():
            errors.append("`match.ro_product_model_contains` must be non-empty")
        if int(m.get("android_sdk", 0)) != 30:
            errors.append(f"`match.android_sdk` must be 30 for Android 11 (got {m.get('android_sdk')!r})")

    mp = prof.get("modem_provisioning")
    if not isinstance(mp, dict):
        errors.append("`modem_provisioning` must be an object")
    else:
        pdp_path = str(mp.get("ims_pdp_profile_path", ""))
        if not re.fullmatch(r"/pdp_profiles/profile([1-9]|1[0-6])", pdp_path):
            errors.append(f"`modem_provisioning.ims_pdp_profile_path` must match `/pdp_profiles/profile<N>` (got {pdp_path!r})")
        if int(mp.get("ims_pdp_profile_bytes", 0)) != 789:
            errors.append("`modem_provisioning.ims_pdp_profile_bytes` must be 789")
        if int(mp.get("reg_config_bytes", 0)) != 405:
            errors.append("`modem_provisioning.reg_config_bytes` must be 405")
        if int(mp.get("reg_config_mode", -1)) != 0:
            errors.append("`modem_provisioning.reg_config_mode` must be 0 (USIM auto-derived, zero foreign P-CSCF)")
        prov_bin = str(mp.get("provisioner_binary", "")).strip()
        if not prov_bin or ("TODO" in prov_bin.upper() and status == "verified"):
            errors.append("`modem_provisioning.provisioner_binary` cannot be empty or TODO when status=='verified'")

    ims = prof.get("ims_service")
    if not isinstance(ims, dict):
        errors.append("`ims_service` must be an object")
    else:
        if ims.get("package") != "org.octolab.qcom32ims":
            errors.append("`ims_service.package` must be `org.octolab.qcom32ims`")
        if ims.get("ril_socket") != "/dev/socket/qmux_radio/rild_ims0":
            errors.append("`ims_service.ril_socket` must be `/dev/socket/qmux_radio/rild_ims0`")
    return errors


def classify_device_props(
    props: dict[str, str],
    profiles: list[dict[str, Any]],
    gates: dict[str, Any] | None = None,
    volte_healthy: bool = False,
) -> dict[str, Any]:
    gates = gates or {}
    device = props.get("ro.product.device", "").strip()
    model = props.get("ro.product.model", "").strip()
    brand = props.get("ro.product.brand", "").strip()
    platform = props.get("ro.board.platform", "").strip()
    abi = props.get("ro.product.cpu.abi", "").strip()
    sdk_s = props.get("ro.build.version.sdk", "").strip()
    sdk_int = int(sdk_s) if sdk_s.isdigit() else 0
    build_tags = props.get("ro.build.tags", "").strip()
    build_display = props.get("ro.build.display.id", "").strip()
    selinux = props.get("selinux", "").strip()
    sim_state = props.get("gsm.sim.state", "").strip()
    net_type = props.get("gsm.network.type", "").strip()
    adb_uid = props.get("adb_uid", "").strip()

    blocker_code, blocker_reason = detect_specific_variant_blocker(device, model, platform, abi)
    fam = match_catalog_family(device, model, brand)
    matched_prof = match_repo_profile(profiles, device, model, sdk_int)
    rom_sig = assess_rom_signature(build_tags, build_display)

    warnings: list[str] = []
    if fam:
        warnings.extend(fam.get("blocked_or_risky_variants", []))
    if not rom_sig["prebuilt_apk_compatible"] and sdk_int == 30:
        warnings.append(rom_sig["summary"])
    if selinux == "Permissive":
        warnings.append("Kernel SELinux is Permissive; Qcom32 VoLTE natively supports Enforcing and never lowers SELinux.")

    if blocker_code is not None:
        return {
            "route": blocker_code,
            "reason": blocker_reason,
            "matched_profile": None,
            "catalog_family": fam["family_id"] if fam else None,
            "rom_signature": rom_sig,
            "warnings": warnings,
            "recommended_commands": [],
        }

    if sdk_int != 30:
        flash_hint = fam["flash_protocol"] if fam else "Unlock bootloader -> Flash TWRP/Recovery -> Flash LineageOS 18.1 (Android 11, API 30)"
        return {
            "route": "NEEDS_ANDROID11_ROM_FIRST",
            "reason": f"Connected phone is running Android SDK {sdk_int} ({build_display or 'non-Android-11'}), whereas `qcom32-volte` targets Android 11 (LineageOS 18.1, API 30).",
            "matched_profile": None,
            "catalog_family": fam["family_id"] if fam else None,
            "flash_protocol_hint": flash_hint,
            "rom_signature": rom_sig,
            "warnings": warnings,
            "recommended_commands": [
                "python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py catalog"
            ],
        }

    if matched_prof is not None and matched_prof.get("status") == "verified":
        if volte_healthy:
            return {
                "route": "ALREADY_ACTIVE_HEALTHY",
                "reason": f"Verified profile `{matched_prof['profile_id']}` is matched and live VoLTE stack is already REGISTERED & healthy. Do NOT re-run EFS provisioning unnecessarily.",
                "matched_profile": matched_prof["profile_id"],
                "catalog_family": fam["family_id"] if fam else None,
                "rom_signature": rom_sig,
                "warnings": warnings,
                "recommended_commands": [
                    "python3 cli/volte_ctl.py status",
                    "python3 cli/volte_ctl.py install-manager",
                ],
            }
        if "LOADED" not in sim_state:
            return {
                "route": "NEEDS_SIM_OR_LTE",
                "reason": f"Verified profile `{matched_prof['profile_id']}` matched, but SIM state is {sim_state!r} (network={net_type!r}). Insert an active VoLTE-provisioned 4G USIM and confirm LTE data attachment before running deploy.",
                "matched_profile": matched_prof["profile_id"],
                "catalog_family": fam["family_id"] if fam else None,
                "rom_signature": rom_sig,
                "warnings": warnings,
                "recommended_commands": [
                    "python3 cli/volte_ctl.py status",
                ],
            }
        if adb_uid and adb_uid != "0" and gates.get("adb_root_available") is False:
            return {
                "route": "NEEDS_ROOTED_DEBUGGING",
                "reason": f"Verified profile `{matched_prof['profile_id']}` matched, but ADB Root (UID 0) is disabled. Enable Settings -> System -> Developer options -> Rooted debugging on the phone first.",
                "matched_profile": matched_prof["profile_id"],
                "catalog_family": fam["family_id"] if fam else None,
                "rom_signature": rom_sig,
                "warnings": warnings,
                "recommended_commands": [
                    "python3 cli/volte_ctl.py deploy",
                    "python3 cli/volte_ctl.py install-manager",
                ],
            }
        return {
            "route": "VERIFIED_PROFILE_READY",
            "reason": f"Verified profile `{matched_prof['profile_id']}` matched on Android 11 with SIM LOADED. Ready for guarded one-command backup + Stage 6A provisioning + APK deployment.",
            "matched_profile": matched_prof["profile_id"],
            "catalog_family": fam["family_id"] if fam else None,
            "rom_signature": rom_sig,
            "warnings": warnings,
            "recommended_commands": [
                "python3 cli/volte_ctl.py self-test",
                "python3 cli/volte_ctl.py deploy",
                "python3 cli/volte_ctl.py install-manager",
            ],
        }

    if gates.get("modem_ims_symbols_checked") is True and gates.get("modem_has_ims_symbols") is False:
        return {
            "route": "MODEM_LACKS_IMS_ENGINE",
            "reason": "Read-only scan of `/firmware/image/modem*` did NOT find `qp_ims_reg_config` / `qipcall` symbols; this baseband firmware build lacks Hexagon VoLTE support.",
            "matched_profile": matched_prof["profile_id"] if matched_prof else None,
            "catalog_family": fam["family_id"] if fam else None,
            "rom_signature": rom_sig,
            "warnings": warnings,
            "recommended_commands": [],
        }

    plat_ok = (not platform) or (platform.lower() in SUPPORTED_32BIT_PLATFORMS)
    if not plat_ok:
        warnings.append(f"Board platform {platform!r} is outside tested 32-bit MSM8974/APQ8084/MSM8926 set; inspect RIL and `/dev/diag` carefully.")

    return {
        "route": "CANDIDATE_MSM8974_PORTING",
        "reason": (
            f"32-bit Qualcomm Android 11 device ({model!r} / `{device}`, platform `{platform or 'unknown'}`) has no verified `devices/*/profile.json` yet. "
            "NEVER write Passport's `/pdp_profiles/profile4` blindly — follow the 4-Gate Read-Only Porting Workflow in `.agents/skills/qcom32-volte/references/PORTING_AND_EFS_SAFETY.md`."
        ),
        "matched_profile": matched_prof["profile_id"] if matched_prof else None,
        "catalog_family": fam["family_id"] if fam else None,
        "rom_signature": rom_sig,
        "gates": gates,
        "warnings": warnings,
        "recommended_commands": [
            "python3 cli/build_all.py --host-tests-only",
            f"python3 .agents/skills/qcom32-volte/scripts/ai_device_helper.py scaffold-profile --profile-id {(brand or 'qcom').lower()}-{device.lower() or 'device'} --device {device or 'codename'} --model-contains \"{model.split()[0] if model else 'Model'}\" --display-name \"{brand} {model} (LineageOS 18.1)\"",
        ],
    }


def resolve_tool_optional(explicit: str | None, tool_name: str, repo_root: Path) -> str | None:
    if explicit:
        p = Path(explicit).expanduser().resolve()
        return str(p) if p.is_file() else None
    exe = f"{tool_name}.exe" if os.name == "nt" else tool_name
    for sub in ("vendor/platform-tools", "payloads/platform-tools", "private/platform-tools", "private/toolchain/platform-tools"):
        cand = repo_root / sub / exe
        if cand.is_file():
            return str(cand.resolve())
    hit = shutil.which(tool_name)
    return hit


def run_live_inspect(repo_root: Path, adb_explicit: str | None, serial: str | None) -> dict[str, Any]:
    profiles = load_repo_profiles(repo_root)
    adb_bin = resolve_tool_optional(adb_explicit, "adb", repo_root)
    fastboot_bin = resolve_tool_optional(None, "fastboot", repo_root)

    if not adb_bin:
        return {
            "connected": False,
            "transport": None,
            "error": "Could not find `adb` in PATH or bundled platform-tools. Install Android platform-tools or pass --adb.",
        }

    dev_proc = subprocess.run(
        [adb_bin, "devices", "-l"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=15,
        **subprocess_no_window_kwargs(),
    )
    adb_lines = [
        ln.strip()
        for ln in dev_proc.stdout.splitlines()
        if ln.strip() and not ln.strip().startswith("List of devices") and not ln.strip().startswith("*")
    ]
    parsed_adb: list[dict[str, str]] = []
    for ln in adb_lines:
        parts = ln.split()
        if len(parts) >= 2:
            parsed_adb.append({"serial": sanitize_text(parts[0]), "raw_serial": parts[0], "state": parts[1], "detail": sanitize_text(" ".join(parts[2:]))})

    if not parsed_adb:
        fb_devices: list[str] = []
        if fastboot_bin:
            fb_proc = subprocess.run(
                [fastboot_bin, "devices"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=10,
                **subprocess_no_window_kwargs(),
            )
            fb_devices = [sanitize_text(ln.strip()) for ln in fb_proc.stdout.splitlines() if ln.strip()]
        if fb_devices:
            return {
                "connected": True,
                "transport": "fastboot",
                "fastboot_devices": fb_devices,
                "route": "IN_FASTBOOT_BOOTLOADER_MODE",
                "reason": "Device is currently in Fastboot/Bootloader mode rather than booted Android OS. Check unlock state (`fastboot oem device-info` or `fastboot getvar all`) before flashing TWRP / LineageOS 18.1.",
            }
        return {
            "connected": False,
            "transport": None,
            "route": "NO_USB_DEVICE_DETECTED",
            "reason": "No ADB or Fastboot device detected. Ensure USB data cable is connected and USB debugging (or Fastboot/Download mode) is enabled.",
            "verified_profiles_in_repo": [p["profile_id"] for p in profiles if p.get("status") == "verified"],
        }

    chosen = None
    if serial:
        for item in parsed_adb:
            if item["raw_serial"] == serial:
                chosen = item
                break
        if chosen is None:
            return {"connected": False, "error": f"Requested serial {sanitize_text(serial)!r} not found among connected ADB devices."}
    else:
        chosen = parsed_adb[0]

    if chosen["state"] != "device":
        return {
            "connected": True,
            "transport": "adb",
            "adb_state": chosen["state"],
            "route": "ADB_NOT_AUTHORIZED_OR_RECOVERY",
            "reason": (
                f"Device is in ADB state `{chosen['state']}` ({chosen['detail']}). "
                "If `unauthorized`, ask the user to tap 'Allow USB debugging' on the phone screen; "
                "if `recovery` or `sideload`, complete LineageOS 18.1 flashing first and boot into Android."
            ),
        }

    base_cmd = [adb_bin, "-s", chosen["raw_serial"]]

    def sh(cmd: str, timeout: int = 20) -> str:
        p = subprocess.run(
            [*base_cmd, "shell", cmd],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            **subprocess_no_window_kwargs(),
        )
        return p.stdout.strip()

    prop_keys = (
        "ro.product.brand",
        "ro.product.model",
        "ro.product.device",
        "ro.board.platform",
        "ro.product.cpu.abi",
        "ro.build.version.sdk",
        "ro.build.version.release",
        "ro.build.display.id",
        "ro.build.tags",
        "gsm.version.baseband",
        "gsm.sim.state",
        "gsm.sim.operator.numeric",
        "gsm.network.type",
    )
    props: dict[str, str] = {}
    for k in prop_keys:
        props[k] = sanitize_text(sh(f"getprop {k}"))
    props["selinux"] = sanitize_text(sh("getenforce"))
    props["adb_uid"] = sanitize_text(sh("id -u"))

    rild_sock_out = sh("ls -lZ /dev/socket/qmux_radio/rild_ims0 2>/dev/null || true")
    diag_node_out = sh("ls -lZ /dev/diag 2>/dev/null || true")
    mixer_sample = sh("cat /vendor/etc/mixer_paths*.xml /system/etc/mixer_paths*.xml 2>/dev/null || true", timeout=25)
    mixer_info = analyze_mixer_paths_xml(mixer_sample) if mixer_sample else {"has_volte_audio_markers": False, "matched_markers": []}

    modem_checked = False
    modem_has_syms = False
    if props["adb_uid"] == "0":
        sym_out = sh(
            "grep -a -l 'qp_ims_reg_config' /firmware/image/modem* /vendor/firmware_mnt/image/modem* 2>/dev/null | head -n 3 || true",
            timeout=25,
        )
        if sym_out:
            modem_checked = True
            modem_has_syms = True
        else:
            modem_files = sh("ls /firmware/image/modem* /vendor/firmware_mnt/image/modem* 2>/dev/null || true")
            if modem_files:
                modem_checked = True
                modem_has_syms = False

    svc_dump = sh("dumpsys activity service org.octolab.qcom32ims/.Qcom32VolteImsService --qmi-probe 2>/dev/null || true", timeout=25)
    tel_dbg = sh("dumpsys activity service com.android.phone/.TelephonyDebugService 2>/dev/null || true", timeout=25)
    volte_healthy = (
        "QCOM32_VOLTE_IMS_V1" in svc_dump
        and "rilConnected=true" in svc_dump
        and "voiceImsReady=true" in svc_dump
        and '"reg_status":2' in svc_dump.replace(" ", "")
        and "mImsRegistered=true" in tel_dbg
    )

    gates = {
        "rild_ims0_socket_present": "rild_ims0" in rild_sock_out and "No such file" not in rild_sock_out,
        "dev_diag_present": "/dev/diag" in diag_node_out and "No such file" not in diag_node_out,
        "mixer_paths_audio": mixer_info,
        "modem_ims_symbols_checked": modem_checked,
        "modem_has_ims_symbols": modem_has_syms if modem_checked else None,
        "adb_root_currently_uid0": props["adb_uid"] == "0",
    }

    decision = classify_device_props(props, profiles, gates=gates, volte_healthy=volte_healthy)
    return {
        "connected": True,
        "transport": "adb",
        "device_count": len(parsed_adb),
        "properties": props,
        "gates": gates,
        "volte_currently_healthy": volte_healthy,
        "decision": decision,
    }


def cmd_self_test(repo_root: Path) -> int:
    profiles = load_repo_profiles(repo_root)
    assert len(profiles) >= 1, "Expected at least blackberry-passport-wolverine profile in devices/"
    for p in profiles:
        errs = validate_profile_dict(p)
        assert not errs, f"Existing repo profile {p.get('_path')} failed validation: {errs}"

    # 1. Verified Passport ready for deployment
    d_pp_ready = classify_device_props(
        {
            "ro.product.brand": "blackberry",
            "ro.product.device": "wolverine",
            "ro.product.model": "Passport",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
            "selinux": "Enforcing",
            "gsm.sim.state": "LOADED",
            "gsm.network.type": "LTE",
            "adb_uid": "0",
        },
        profiles,
    )
    assert d_pp_ready["route"] == "VERIFIED_PROFILE_READY"
    assert d_pp_ready["matched_profile"] == "blackberry-passport-wolverine"
    assert d_pp_ready["rom_signature"]["prebuilt_apk_compatible"] is True

    # 1B. Passport Silver Edition (oslo / SQW100-4) & AT&T Wichita also unlocked via profile aliases
    d_oslo_ready = classify_device_props(
        {
            "ro.product.brand": "blackberry",
            "ro.product.device": "oslo",
            "ro.product.model": "Passport Silver Edition",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
            "selinux": "Enforcing",
            "gsm.sim.state": "LOADED",
            "gsm.network.type": "LTE",
            "adb_uid": "0",
        },
        profiles,
    )
    assert d_oslo_ready["route"] == "VERIFIED_PROFILE_READY"
    assert d_oslo_ready["matched_profile"] == "blackberry-passport-wolverine"

    d_wichita_ready = classify_device_props(
        {
            "ro.product.brand": "blackberry",
            "ro.product.device": "wichita",
            "ro.product.model": "SQW100-2",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
            "selinux": "Enforcing",
            "gsm.sim.state": "LOADED",
            "gsm.network.type": "LTE",
            "adb_uid": "0",
        },
        profiles,
    )
    assert d_wichita_ready["route"] == "VERIFIED_PROFILE_READY"

    # 2. Verified Passport already healthy
    d_pp_healthy = classify_device_props(
        {
            "ro.product.device": "wolverine",
            "ro.product.model": "Passport",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "test-keys",
            "selinux": "Enforcing",
            "gsm.sim.state": "LOADED",
        },
        profiles,
        volte_healthy=True,
    )
    assert d_pp_healthy["route"] == "ALREADY_ACTIVE_HEALTHY"

    # 3. Candidate Samsung Galaxy S5 (klte) on official LineageOS 18.1 (release-keys warning)
    d_s5 = classify_device_props(
        {
            "ro.product.brand": "samsung",
            "ro.product.device": "klte",
            "ro.product.model": "SM-G900F",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
            "ro.build.tags": "release-keys",
            "ro.build.display.id": "lineage_klte-userdebug 11 RQ3A",
            "selinux": "Enforcing",
        },
        profiles,
        gates={"rild_ims0_socket_present": True, "dev_diag_present": True},
    )
    assert d_s5["route"] == "CANDIDATE_MSM8974_PORTING"
    assert d_s5["catalog_family"] == "samsung-galaxy-s5-klte"
    assert d_s5["rom_signature"]["prebuilt_apk_compatible"] is False

    # 4. Candidate LG G3 (d855) still on stock Android 6.0 (SDK 23)
    d_g3_stock = classify_device_props(
        {
            "ro.product.brand": "lge",
            "ro.product.device": "g3",
            "ro.product.model": "LG-D855",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "23",
        },
        profiles,
    )
    assert d_g3_stock["route"] == "NEEDS_ANDROID11_ROM_FIRST"
    assert d_g3_stock["catalog_family"] == "lg-g3-g2-msm8974"

    # 5. Blocker: Xiaomi Mi 4 3G WCDMA (MI 4W)
    d_mi4_3g = classify_device_props(
        {
            "ro.product.brand": "Xiaomi",
            "ro.product.device": "cancro",
            "ro.product.model": "MI 4W",
            "ro.board.platform": "msm8974",
            "ro.product.cpu.abi": "armeabi-v7a",
            "ro.build.version.sdk": "30",
        },
        profiles,
    )
    assert d_mi4_3g["route"] == "UNSUPPORTED_HARDWARE_VARIANT"

    # 6. Blocker: Samsung S5 Exynos (SM-G900H / k3gxx) & AT&T SM-G900A & 64-bit platform & Nexus 5 hammerhead
    assert classify_device_props({"ro.product.device": "k3gxx", "ro.product.model": "SM-G900H", "ro.build.version.sdk": "30"}, profiles)["route"] == "UNSUPPORTED_NON_QUALCOMM"
    assert classify_device_props({"ro.product.device": "klteatt", "ro.product.model": "SAMSUNG-SM-G900A", "ro.build.version.sdk": "23"}, profiles)["route"] == "UNSUPPORTED_HARDWARE_VARIANT"
    assert classify_device_props({"ro.product.device": "libra", "ro.product.model": "Mi-4c", "ro.product.cpu.abi": "arm64-v8a", "ro.build.version.sdk": "30"}, profiles)["route"] == "UNSUPPORTED_64BIT_PLATFORM"
    assert classify_device_props({"ro.product.device": "hammerhead", "ro.product.model": "Nexus 5", "ro.product.cpu.abi": "armeabi-v7a", "ro.build.version.sdk": "30"}, profiles)["route"] == "MODEM_LACKS_IMS_ENGINE"

    # 7. 789B DS PDP profile binary decoder check (IMS ready vs Non-IMS data slot)
    fake_ims = bytearray(789)
    fake_ims[0] = 0x15
    fake_ims[2:5] = b"ims"
    fake_ims[34] = 1
    fake_ims[35] = 4
    fake_ims[36] = 3  # IPV4V6
    fake_ims[61:64] = b"ims"
    fake_ims[750] = 1
    fake_ims[751] = 1
    fake_ims[753] = 0
    dec_ims = decode_pdp_profile_bytes(bytes(fake_ims))
    assert dec_ims["valid_789b_struct"] is True
    assert dec_ims["pdp_type"] == "IPV4V6"
    assert dec_ims["slot_classification"] == "IMS_READY_SLOT"

    fake_mms = bytearray(789)
    fake_mms[0] = 0x15
    fake_mms[2:5] = b"mms"
    fake_mms[35] = 2
    fake_mms[61:66] = b"3gwap"
    dec_mms = decode_pdp_profile_bytes(bytes(fake_mms))
    assert dec_mms["slot_classification"] == "NON_IMS_DATA_OR_MMS_DO_NOT_OVERWRITE"

    # 8. Mixer paths & PII sanitizer checks
    mx = analyze_mixer_paths_xml('<mixer><path name="voice-ims"><ctl name="Voice-MMode1"/></path></mixer>')
    assert mx["has_volte_audio_markers"] is True
    synth_digits = "9" * 15
    assert synth_digits not in sanitize_text(f"imei={synth_digits} ok")

    # 9. Catalog query check
    assert len(search_catalog("oslo")) == 1
    assert len(search_catalog("klte")) == 1
    assert len(search_catalog("cancro")) == 1
    assert len(search_catalog("d855")) == 1

    print(json.dumps({"self_test": "PASS", "assertions": 30, "catalog_families": len(DEVICE_FAMILIES)}, indent=2))
    return 0


def build_scaffold_profile(
    profile_id: str,
    device: str,
    model_contains: str,
    display_name: str,
    ims_pdp_path: str,
) -> dict[str, Any]:
    return {
        "profile_id": profile_id,
        "display_name": display_name,
        "status": "experimental",
        "soc": "Qualcomm Snapdragon 800/801 (MSM8974, 32-bit ARMv7-A)",
        "match": {
            "ro_product_device": device,
            "ro_product_model_contains": model_contains,
            "android_sdk": 30,
            "selinux": "Enforcing",
        },
        "modem_provisioning": {
            "mode": "stage6a_standalone_dcm",
            "provisioner_binary": "TODO_BUILD_DEVICE_VERIFIED_PROVISIONER",
            "ims_pdp_profile_path": ims_pdp_path,
            "ims_pdp_profile_bytes": 789,
            "ims_pdp_type": "IPV4V6",
            "reg_config_path": "/nv/item_files/ims/qp_ims_reg_config",
            "reg_config_bytes": 405,
            "reg_config_mode": 0,
            "total_managed_efs_items": 54,
        },
        "ims_service": {
            "package": "org.octolab.qcom32ims",
            "service_component": "org.octolab.qcom32ims/.Qcom32VolteImsService",
            "ril_socket": "/dev/socket/qmux_radio/rild_ims0",
            "audio_hal_notes": "Verify Voice-MMode1 / voice-ims path in mixer_paths.xml before promoting status to verified",
        },
        "rf_capabilities": {
            "fdd_lte_bands": [],
            "tdd_lte_bands": [],
            "cdma_1x_voice": False,
            "notes": "Populate exact hardware RF bands for this variant after live verification",
        },
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo-root", default=None, help="Optional override path to qcom32-volte repository root")
    sub = ap.add_subparsers(dest="subcmd", required=True)

    sub.add_parser("self-test", help="Run offline deterministic unit tests for device routing, PDP decoding, and profile validation")

    p_cat = sub.add_parser("catalog", help="List supported/candidate 32-bit Qualcomm families, recommended variants, and hardware blockers")
    p_cat.add_argument("--query", default=None, help="Filter by model, codename, or brand (e.g. klte, G900F, cancro, d855, bacon)")

    p_ins = sub.add_parser("inspect", help="Read-only USB/ADB/Fastboot inspection and automatic workflow routing for connected phone")
    p_ins.add_argument("--adb", default=None, help="Optional explicit path to adb executable")
    p_ins.add_argument("--serial", default=None, help="Optional ADB device serial")

    p_pdp = sub.add_parser("decode-pdp", help="Decode a 789-byte /pdp_profiles/profileN binary or hex dump")
    p_pdp.add_argument("path", help="Path to raw 789-byte binary file or hex text file")

    p_val = sub.add_parser("validate-profile", help="Validate one profile.json or all devices/*/profile.json files")
    p_val.add_argument("target", nargs="?", default="all", help="Path to profile.json, or 'all' (default)")

    p_scaf = sub.add_parser("scaffold-profile", help="Generate an experimental devices/<id>/profile.json template for a new MSM8974 port")
    p_scaf.add_argument("--profile-id", required=True, help="Lowercase kebab-case profile ID (e.g. samsung-s5-klte)")
    p_scaf.add_argument("--device", required=True, help="Exact ro.product.device value (e.g. klte)")
    p_scaf.add_argument("--model-contains", required=True, help="Substring of ro.product.model (e.g. SM-G900)")
    p_scaf.add_argument("--display-name", required=True, help="Human-readable device & OS name")
    p_scaf.add_argument("--ims-pdp-path", default="/pdp_profiles/profile4", help="Verified IMS PDP slot path after read-only EFS audit")
    p_scaf.add_argument("--out", default=None, help="Optional output file path (prints JSON to stdout if omitted)")

    args = ap.parse_args(argv)
    repo_root = discover_repo_root(args.repo_root)

    if args.subcmd == "self-test":
        return cmd_self_test(repo_root)

    if args.subcmd == "catalog":
        matches = search_catalog(args.query)
        print(json.dumps({"query": args.query, "count": len(matches), "families": matches}, indent=2, ensure_ascii=False))
        return 0

    if args.subcmd == "inspect":
        report = run_live_inspect(repo_root, args.adb, args.serial)
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 0

    if args.subcmd == "decode-pdp":
        p = Path(args.path).expanduser().resolve()
        raw = p.read_bytes()
        if len(raw) != 789:
            txt = raw.decode("ascii", errors="ignore").strip()
            hex_clean = re.sub(r"[^0-9a-fA-F]", "", txt)
            if len(hex_clean) == 789 * 2:
                raw = bytes.fromhex(hex_clean)
        decoded = decode_pdp_profile_bytes(raw)
        print(json.dumps(decoded, indent=2, ensure_ascii=False))
        return 0 if decoded.get("valid_789b_struct") else 1

    if args.subcmd == "validate-profile":
        targets: list[Path] = []
        if args.target == "all":
            targets = sorted((repo_root / "devices").glob("*/profile.json"))
        else:
            targets = [Path(args.target).expanduser().resolve()]
        results: list[dict[str, Any]] = []
        any_err = False
        for t in targets:
            prof = json.loads(t.read_text(encoding="utf-8"))
            errs = validate_profile_dict(prof)
            if errs:
                any_err = True
            results.append({"file": str(t), "profile_id": prof.get("profile_id"), "status": prof.get("status"), "valid": not errs, "errors": errs})
        print(json.dumps({"all_valid": not any_err, "profiles": results}, indent=2, ensure_ascii=False))
        return 1 if any_err else 0

    if args.subcmd == "scaffold-profile":
        draft = build_scaffold_profile(
            args.profile_id,
            args.device,
            args.model_contains,
            args.display_name,
            args.ims_pdp_path,
        )
        errs = validate_profile_dict(draft)
        if errs:
            raise ValueError(f"Generated profile failed schema check: {errs}")
        rendered = json.dumps(draft, indent=2, ensure_ascii=False) + "\n"
        if args.out:
            out_p = Path(args.out).expanduser().resolve()
            out_p.parent.mkdir(parents=True, exist_ok=True)
            out_p.write_text(rendered, encoding="utf-8", newline="\n")
        print(rendered, end="")
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
