#!/usr/bin/env python3
"""Standalone deployment, health-status, APK upgrade, companion-app install, and factory-rollback CLI
for the Qcom32 VoLTE & IMS Stack (32-bit Qualcomm Legacy Platforms on Android 11; first verified
device profile: BlackBerry Passport `wolverine` / `MSM8974` on LineageOS 18.1).

Works identically in the research monorepo, standalone open-source export, portable ZIP,
and PyInstaller single-file Windows EXE (`zero` third-party Python dependencies).

Commands:
  python3 cli/volte_ctl.py self-test        # Run offline parser, profile-guard, catalog & PII-redaction unit tests
  python3 cli/volte_ctl.py status           # Read-only live VoLTE / QMI_IMSA / ImsResolver health check
  python3 cli/volte_ctl.py inspect-device   # 100% read-only 4-Gate readiness check + AI & GitHub self-porting guide
  python3 cli/volte_ctl.py list-models      # List unlocked Passport editions & candidate 32-bit Qualcomm families
  python3 cli/volte_ctl.py deploy           # Guarded modem EFS provisioning + APK install + reboot verification
  python3 cli/volte_ctl.py upgrade-apk      # Zero-EFS-write in-place APK upgrade + optional reboot verification
  python3 cli/volte_ctl.py install-manager  # Install optional on-device companion app (Qcom32VolteManager.apk)
  python3 cli/volte_ctl.py rollback         # Full factory modem EFS restoration + APK uninstall + reboot verification
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from typing import Callable

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import device_readiness  # noqa: E402

FROZEN_ROOT: Path | None = (
    Path(getattr(sys, "_MEIPASS"))
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS")
    else None
)
REPO_ROOT = FROZEN_ROOT if FROZEN_ROOT is not None else Path(__file__).resolve().parents[1]

IMS_PKG = "org.octolab.qcom32ims"
LEGACY_IMS_PKG = "org.passport.volteims"
MANAGER_PKG = "org.octolab.qcom32volte"
LEGACY_MANAGER_PKG = "org.passport.voltemanager"

IMS_SERVICE = f"{IMS_PKG}/.Qcom32VolteImsService"
LEGACY_IMS_SERVICE = f"{LEGACY_IMS_PKG}/.PassportVolteImsService"
IMS_ADMIN_RECEIVER = f"{IMS_PKG}/.VolteAdminReceiver"
IMS_ADMIN_ACTION = "org.octolab.qcom32ims.ACTION_ADMIN"
LEGACY_ADMIN_RECEIVER = f"{LEGACY_IMS_PKG}/.VolteAdminReceiver"
LEGACY_ADMIN_ACTION = "org.passport.volteims.ACTION_ADMIN"

REMOTE_TMP_PROVISIONER = "/data/local/tmp/pp_regconfig_provision_v6"
EXPECTED_VERSION = "2.7-qcom32-volte"

PII_REDACT_RE = re.compile(r"\b\d{11,20}\b")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sanitize_text(text: str) -> str:
    return PII_REDACT_RE.sub("<REDACTED_DIGITS>", text)


def emit_json(payload: dict[str, object], out_json: str | None = None) -> None:
    rendered = json.dumps(payload, indent=2, ensure_ascii=False)
    if out_json:
        out_path = Path(out_json).expanduser().resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(rendered + "\n", encoding="utf-8")
    if sys.stdout is not None:
        print(rendered)


def persistent_backup_root() -> Path:
    """Store backups in %LOCALAPPDATA% when running from a frozen EXE, never in %TEMP%."""
    if FROZEN_ROOT is not None:
        if os.name == "nt":
            base = Path(os.environ.get("LOCALAPPDATA") or (Path.home() / "AppData/Local"))
            target = base / "qcom32-volte" / "backups"
        elif sys.platform == "darwin":
            target = Path.home() / "Library/Application Support/qcom32-volte/backups"
        else:
            target = Path.home() / ".local/share/qcom32-volte/backups"
    else:
        target = REPO_ROOT / "private"
    target.mkdir(parents=True, exist_ok=True)
    return target


def load_device_profiles() -> list[dict[str, object]]:
    profiles: list[dict[str, object]] = []
    dev_dir = REPO_ROOT / "devices"
    if dev_dir.is_dir():
        for p in sorted(dev_dir.glob("*/profile.json")):
            profiles.append(json.loads(p.read_text(encoding="utf-8")))
    return profiles


def match_verified_profile(device: str, model: str, sdk_int: int) -> dict[str, object] | None:
    dev_norm = device.strip().lower()
    mod_norm = model.strip().lower()
    for prof in load_device_profiles():
        if prof.get("status") != "verified":
            continue
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
        dev_ok = dev_norm in dev_aliases
        mod_ok = any(tok and tok in mod_norm for tok in mod_aliases)
        if dev_ok and mod_ok:
            return prof
    return None


def parse_service_dump(raw: str) -> dict[str, object]:
    present = ("QCOM32_VOLTE_IMS_V1" in raw) or ("PASSPORT_VOLTE_IMS_V1" in raw)
    version = None
    ril_connected = "rilConnected=true" in raw
    voice_ready = "voiceImsReady=true" in raw
    feature_ready = "featureReadyCalled=true" in raw
    pub_reg = None
    for line in raw.splitlines():
        s = line.strip()
        if s.startswith("version="):
            version = s.split("=", 1)[1].strip()
        m = re.search(r"publishedFrameworkReg=(-?\d+)", s)
        if m:
            pub_reg = int(m.group(1))
    return {
        "present": present,
        "version": version,
        "ril_connected": ril_connected,
        "voice_ims_ready": voice_ready,
        "feature_ready": feature_ready,
        "published_framework_reg": pub_reg,
    }


def parse_qmi_probe(raw: str) -> dict[str, int | None]:
    def grab(key: str) -> int | None:
        m_json = re.search(rf'"{re.escape(key)}"\s*:\s*(-?\d+)', raw)
        if m_json:
            return int(m_json.group(1))
        m_kv = re.search(rf"\b{re.escape(key)}=(-?\d+)", raw)
        return int(m_kv.group(1)) if m_kv else None

    return {
        "pdp_connected": grab("pdp_connected"),
        "reg_status": grab("reg_status"),
        "legacy_reg": grab("legacy_reg"),
        "voip": grab("voip"),
        "voip_rat": grab("voip_rat"),
    }


def parse_package_stopped(dumpsys_pkg: str) -> bool | None:
    m = re.search(r"\bstopped=(true|false)\b", dumpsys_pkg)
    if not m:
        return None
    return m.group(1) == "true"


def subprocess_no_window_kwargs() -> dict[str, object]:
    if os.name == "nt":
        return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)}
    return {}


class AdbClient:
    def __init__(self, adb_bin: str, serial: str | None = None):
        self.adb_bin = adb_bin
        self.serial = serial

    def _base_cmd(self) -> list[str]:
        cmd = [self.adb_bin]
        if self.serial:
            cmd.extend(["-s", self.serial])
        return cmd

    def run(self, args: list[str], timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        proc = subprocess.run(
            [*self._base_cmd(), *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            **subprocess_no_window_kwargs(),
        )
        if check and proc.returncode != 0:
            raise RuntimeError(
                f"adb {' '.join(args)} failed (rc={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
            )
        return proc

    def list_devices(self) -> list[tuple[str, str]]:
        out = subprocess.run(
            [self.adb_bin, "devices"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=20,
            **subprocess_no_window_kwargs(),
        ).stdout
        devices: list[tuple[str, str]] = []
        for line in out.splitlines():
            s = line.strip()
            if not s or s.startswith("List of devices") or s.startswith("*"):
                continue
            parts = s.split()
            if len(parts) >= 2:
                devices.append((parts[0], parts[1]))
        return devices

    def shell(self, cmd: str, timeout: int = 60, check: bool = True) -> str:
        return self.run(["shell", cmd], timeout=timeout, check=check).stdout

    def ensure_root(self) -> None:
        uid_out = self.shell("id -u", check=False).strip()
        if uid_out != "0":
            self.run(["root"], timeout=30, check=True)
            time.sleep(2.0)
            self.run(["wait-for-device"], timeout=30, check=True)
            uid_out = self.shell("id -u", check=True).strip()
        if uid_out != "0":
            raise RuntimeError(
                f"ADB shell is UID {uid_out!r}, expected UID 0 (root). "
                "Enable Developer Options -> Rooted debugging on LineageOS 18.1."
            )

    def wait_after_reboot(self, old_boot_id: str, timeout_sec: int = 180) -> str:
        self.run(["reboot"], timeout=30, check=True)
        time.sleep(8.0)
        deadline = time.time() + timeout_sec
        while time.time() < deadline:
            w = self.run(["wait-for-device"], timeout=30, check=False)
            if w.returncode == 0:
                boot_done = self.shell("getprop sys.boot_completed", timeout=15, check=False).strip()
                new_boot = self.shell("cat /proc/sys/kernel/random/boot_id", timeout=15, check=False).strip()
                if boot_done == "1" and new_boot and new_boot != old_boot_id:
                    return new_boot
            time.sleep(3.0)
        raise TimeoutError("Timed out waiting for device to complete normal reboot")


def resolve_adb(explicit: str | None = None) -> str:
    if explicit:
        return explicit
    exe_name = "adb.exe" if os.name == "nt" else "adb"
    candidates = [
        REPO_ROOT / "vendor/platform-tools" / exe_name,
        REPO_ROOT / "payloads/platform-tools" / exe_name,
        REPO_ROOT / "private/platform-tools" / exe_name,
        REPO_ROOT / "private/toolchain/platform-tools" / exe_name,
    ]
    for cand in candidates:
        if cand.is_file():
            return str(cand.resolve())
    hit = shutil.which("adb")
    if hit:
        return hit
    if os.name == "nt":
        sibling_adb = Path(r"C:\LifeSpace\bbq10\tools\volte_session\android-tools\platform-tools\adb.exe")
        if sibling_adb.is_file():
            return str(sibling_adb)
    raise FileNotFoundError("Could not find `adb`. Install Android platform-tools, use the Windows EXE, or pass --adb.")


def resolve_artifact(explicit: str | None, release_name: str, fallback_glob: str | None = None) -> Path:
    if explicit:
        p = Path(explicit).expanduser().resolve()
        if not p.is_file():
            raise FileNotFoundError(f"Artifact not found: {p}")
        return p
    for subdir in ("payloads", "build/release"):
        cand = REPO_ROOT / subdir / release_name
        if cand.is_file():
            return cand.resolve()
    if fallback_glob:
        matches = sorted(REPO_ROOT.glob(fallback_glob))
        if matches:
            return matches[-1].resolve()
    raise FileNotFoundError(
        f"Missing built artifact `{release_name}`. Run `python3 cli/build_all.py` first or use a prebuilt release package."
    )


def validate_selinux_mode(selinux: str) -> str | None:
    mode = selinux.strip()
    if mode == "Enforcing":
        return None
    if mode == "Permissive":
        return (
            "Device kernel is running SELinux Permissive "
            "(official LineageOS builds use Enforcing; Qcom32 VoLTE never lowers SELinux mode)."
        )
    raise RuntimeError(f"Unexpected SELinux mode {selinux!r}; expected `Enforcing` (or custom-ROM `Permissive`).")


def verify_device_identity(adb: AdbClient, require_verified_efs_profile: bool = True) -> dict[str, object]:
    brand = adb.shell("getprop ro.product.brand", check=False).strip()
    device = adb.shell("getprop ro.product.device").strip()
    model = adb.shell("getprop ro.product.model").strip()
    platform = adb.shell("getprop ro.board.platform", check=False).strip()
    cpu_abi = adb.shell("getprop ro.product.cpu.abi", check=False).strip()
    build_tags = adb.shell("getprop ro.build.tags", check=False).strip()
    sdk_str = adb.shell("getprop ro.build.version.sdk").strip()
    sdk_int = int(sdk_str) if sdk_str.isdigit() else 0
    selinux = adb.shell("getenforce").strip()
    boot_id = adb.shell("cat /proc/sys/kernel/random/boot_id").strip()
    sim_state = adb.shell("getprop gsm.sim.state").strip()
    operator = adb.shell("getprop gsm.sim.operator.numeric").strip()
    net_type = adb.shell("getprop gsm.network.type").strip()

    cat_match = device_readiness.identify_device_props(
        {
            "ro.product.brand": brand,
            "ro.product.model": model,
            "ro.product.device": device,
            "ro.board.platform": platform,
            "ro.product.cpu.abi": cpu_abi,
            "ro.build.version.sdk": str(sdk_int),
            "ro.build.tags": build_tags,
        }
    )
    matched_prof = match_verified_profile(device, model, sdk_int)
    if require_verified_efs_profile and matched_prof is None:
        raise RuntimeError(
            f"Refusing direct modem EFS write on {model!r} ({device!r}, SDK {sdk_int}, "
            f"catalog={cat_match['family_id']!r} / {cat_match['route']}). "
            "Non-Passport models lock direct EFS writes to protect your mobile data/MMS PDP slots. "
            "Click '32-Bit Device Check & AI Porting' (or run `inspect-device`), then clone "
            f"{device_readiness.GITHUB_REPO_URL} and use AI with `{device_readiness.SKILL_PATH_AGENTS}` to adapt your model safely."
        )
    selinux_warn = validate_selinux_mode(selinux)

    res: dict[str, object] = {
        "brand": brand,
        "device": device,
        "model": model,
        "board_platform": platform,
        "cpu_abi": cpu_abi,
        "build_tags": build_tags,
        "sdk": sdk_int,
        "selinux": selinux,
        "boot_id": boot_id,
        "sim_state": sim_state,
        "sim_operator_numeric": operator,
        "network_type": net_type,
        "matched_profile": matched_prof["profile_id"] if matched_prof else "generic-qcom32-readonly",
        "verified_efs_profile": matched_prof is not None,
        "catalog_match": cat_match,
    }
    if selinux_warn is not None:
        res["selinux_warning"] = selinux_warn
    return res


def cleanup_legacy_packages(adb: AdbClient, include_manager: bool = False) -> None:
    pm_out = adb.shell("pm list packages", timeout=20, check=False)
    pkgs = {ln.strip().replace("package:", "") for ln in pm_out.splitlines() if ln.strip().startswith("package:")}
    if LEGACY_IMS_PKG in pkgs:
        adb.shell(
            f"am broadcast -n {LEGACY_ADMIN_RECEIVER} -a {LEGACY_ADMIN_ACTION} "
            "--es op clear_override --es cmd clear_override --include-stopped-packages",
            timeout=20,
            check=False,
        )
        adb.run(["uninstall", LEGACY_IMS_PKG], timeout=30, check=False)
    if include_manager and LEGACY_MANAGER_PKG in pkgs:
        adb.run(["uninstall", LEGACY_MANAGER_PKG], timeout=30, check=False)


def query_live_volte_status(adb: AdbClient) -> dict[str, object]:
    ident = verify_device_identity(adb, require_verified_efs_profile=False)
    svc_raw = adb.shell(f"dumpsys activity service {IMS_SERVICE} --qmi-probe", timeout=30, check=False)
    if "QCOM32_VOLTE_IMS_V1" not in svc_raw:
        legacy_raw = adb.shell(f"dumpsys activity service {LEGACY_IMS_SERVICE} --qmi-probe", timeout=30, check=False)
        if "PASSPORT_VOLTE_IMS_V1" in legacy_raw:
            svc_raw = legacy_raw
    svc = parse_service_dump(svc_raw)
    qmi = parse_qmi_probe(svc_raw)
    tel_dbg = adb.shell("dumpsys activity service com.android.phone/.TelephonyDebugService", timeout=30, check=False)
    ims_reg_matches = re.findall(r"mImsRegistered=(true|false)", tel_dbg)
    reg_raw = adb.shell("dumpsys telephony.registry", timeout=30, check=False)
    call_states = re.findall(r"mCallState=(-?\d+)", reg_raw)
    data_states = re.findall(r"mDataConnectionState=(-?\d+)", reg_raw)

    healthy = (
        svc["present"] is True
        and svc["ril_connected"] is True
        and svc["voice_ims_ready"] is True
        and svc["published_framework_reg"] == 1
        and qmi["pdp_connected"] == 1
        and qmi["reg_status"] == 2
        and qmi["voip"] == 2
        and qmi["voip_rat"] == 1
        and "true" in ims_reg_matches
    )
    return {
        "checked_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(),
        "volte_stack_healthy": healthy,
        "identity": ident,
        "ims_service": svc,
        "qmi_imsa": qmi,
        "framework_ims_registered": ims_reg_matches,
        "call_states": call_states[:2],
        "data_states": data_states[:2],
    }


def poll_until_volte_ready(adb: AdbClient, timeout_sec: int = 90) -> dict[str, object]:
    deadline = time.time() + timeout_sec
    last_status: dict[str, object] = {}
    while time.time() < deadline:
        last_status = query_live_volte_status(adb)
        if last_status.get("volte_stack_healthy") is True:
            return last_status
        time.sleep(4.0)
    raise RuntimeError(
        f"VoLTE stack did not reach healthy REGISTERED state within {timeout_sec}s: {json.dumps(last_status)}"
    )


def cmd_self_test(out_json: str | None = None) -> int:
    sample_dump = (
        "SERVICE org.octolab.qcom32ims/.Qcom32VolteImsService\n"
        "  QCOM32_VOLTE_IMS_V1\n"
        f"  version={EXPECTED_VERSION}\n"
        "  rilConnected=true modemRegState=1\n"
        "  engine voiceImsReady=true publishedFrameworkReg=1\n"
        "  featureReadyCalled=true\n"
        "  qmi_probe={\"event\":\"imsa_pdp\",\"pdp_connected\":1}\n"
        "  qmi_probe={\"event\":\"imsa_reg\",\"legacy_reg\":1,\"reg_status\":2}\n"
        "  qmi_probe={\"event\":\"imsa_svc\",\"voip\":2,\"voip_rat\":1}\n"
    )
    svc = parse_service_dump(sample_dump)
    qmi = parse_qmi_probe(sample_dump)
    qmi_kv = parse_qmi_probe("pdp_connected=1 reg_status=2 legacy_reg=1 voip=2 voip_rat=1")
    assert svc["present"] is True and svc["version"] == EXPECTED_VERSION
    assert svc["ril_connected"] is True and svc["voice_ims_ready"] is True and svc["published_framework_reg"] == 1
    assert qmi == {"pdp_connected": 1, "reg_status": 2, "legacy_reg": 1, "voip": 2, "voip_rat": 1}
    assert qmi_kv == qmi
    prof = match_verified_profile("wolverine", "Passport", 30)
    assert prof is not None and prof["profile_id"] == "blackberry-passport-wolverine"
    prof_oslo = match_verified_profile("oslo", "Passport Silver Edition", 30)
    assert prof_oslo is not None and prof_oslo["profile_id"] == "blackberry-passport-wolverine"
    prof_sqw4 = match_verified_profile("oslo", "SQW100-4", 30)
    assert prof_sqw4 is not None and prof_sqw4["profile_id"] == "blackberry-passport-wolverine"
    prof_wichita = match_verified_profile("wichita", "Passport", 30)
    assert prof_wichita is not None and prof_wichita["profile_id"] == "blackberry-passport-wolverine"
    prof_keian = match_verified_profile("keian", "Passport Porsche Design", 30)
    assert prof_keian is not None and prof_keian["profile_id"] == "blackberry-passport-wolverine"
    prof_wseries = match_verified_profile("wseries", "BlackBerry Passport", 30)
    assert prof_wseries is not None and prof_wseries["profile_id"] == "blackberry-passport-wolverine"
    assert match_verified_profile("oslo", "Passport Silver Edition", 29) is None
    assert match_verified_profile("bacon", "A0001", 30) is None
    assert match_verified_profile("klte", "SM-G900F", 30) is None
    assert parse_package_stopped("User 0: installed=true stopped=false") is False
    assert parse_package_stopped("User 0: installed=true stopped=true") is True
    fake_msisdn = "1" * 11
    fake_iccid = "8" * 20
    redacted = sanitize_text(f"num={fake_msisdn} iccid={fake_iccid} ok")
    assert fake_msisdn not in redacted and fake_iccid not in redacted
    assert validate_selinux_mode("Enforcing") is None
    perm_warn = validate_selinux_mode("Permissive")
    assert perm_warn is not None and "Permissive" in perm_warn
    rejected_selinux = False
    try:
        validate_selinux_mode("Disabled")
    except RuntimeError:
        rejected_selinux = True
    assert rejected_selinux is True
    readiness_assertions = device_readiness.run_unit_tests()
    emit_json({"self_test": "PASS", "assertions": 22 + readiness_assertions}, out_json)
    return 0


def run_deploy_flow(
    adb: AdbClient,
    apk_path: Path,
    prov_path: Path,
    on_progress: Callable[[str], None] | None = None,
) -> dict[str, object]:
    def step(msg: str) -> None:
        if on_progress:
            on_progress(msg)

    step("Enabling ADB Root (UID 0) & verifying target device profile...")
    adb.ensure_root()
    ident = verify_device_identity(adb, require_verified_efs_profile=True)
    if "LOADED" not in str(ident["sim_state"]):
        raise RuntimeError(f"SIM must be LOADED before deployment (got {ident['sim_state']!r}).")

    step("Cleaning up any superseded legacy IMS package...")
    cleanup_legacy_packages(adb, include_manager=False)

    backup_dir = persistent_backup_root() / ("volte-ctl-deploy-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup_dir.mkdir(parents=True, exist_ok=True)

    try:
        step(f"Saving pre-deployment EFS baseline snapshot to {backup_dir}...")
        adb.run(["push", str(prov_path), REMOTE_TMP_PROVISIONER], timeout=30)
        adb.shell(f"chmod 0700 {REMOTE_TMP_PROVISIONER}")

        inspect_before = adb.shell(f"{REMOTE_TMP_PROVISIONER} --inspect-state", timeout=60)
        (backup_dir / "efs-inspect-before.txt").write_text(inspect_before, encoding="utf-8")

        step("Provisioning Stage 6A modem EFS items + IPV4V6 profile4 + EFS_SYNC...")
        prov_out = adb.shell(f"{REMOTE_TMP_PROVISIONER} --provision-stage6a", timeout=90)
        (backup_dir / "efs-provision-stage6a.txt").write_text(prov_out, encoding="utf-8")
    finally:
        adb.shell(f"rm -f {REMOTE_TMP_PROVISIONER}", check=False)

    step("Installing Qcom32VolteIms.apk v2.7 & applying persistent CarrierConfig...")
    adb.run(["install", "-r", str(apk_path)], timeout=60)
    adb.shell(
        f"am broadcast -n {IMS_ADMIN_RECEIVER} -a {IMS_ADMIN_ACTION} "
        "--es op apply_override --es cmd apply_override --ez reboot_required true --include-stopped-packages",
        timeout=30,
    )
    pkg_dump = adb.shell(f"dumpsys package {IMS_PKG}", timeout=30)
    if parse_package_stopped(pkg_dump) is not False:
        raise RuntimeError(f"{IMS_PKG} remained stopped=true after apply_override broadcast")

    step("Rebooting phone normally & waiting for hands-free IMS / VoLTE registration...")
    new_boot = adb.wait_after_reboot(str(ident["boot_id"]))
    ready_status = poll_until_volte_ready(adb, timeout_sec=90)
    ready_status["post_reboot_boot_id"] = new_boot
    ready_status["backup_directory"] = str(backup_dir)
    ready_status["apk_sha256"] = sha256_bytes(apk_path.read_bytes())
    ready_status["provisioner_sha256"] = sha256_bytes(prov_path.read_bytes())
    return ready_status


def poll_until_apk_bound(
    adb: AdbClient,
    require_registered: bool = False,
    timeout_sec: int = 35,
) -> dict[str, object]:
    if require_registered:
        status = poll_until_volte_ready(adb, timeout_sec=max(timeout_sec, 90))
        status["apk_upgrade_verified"] = True
        return status

    deadline = time.time() + timeout_sec
    last_status: dict[str, object] = {}
    bound_since: float | None = None
    while time.time() < deadline:
        last_status = query_live_volte_status(adb)
        svc = last_status.get("ims_service", {})
        is_bound = (
            isinstance(svc, dict)
            and svc.get("present") is True
            and svc.get("version") == EXPECTED_VERSION
            and svc.get("ril_connected") is True
            and svc.get("feature_ready") is True
        )
        if is_bound and last_status.get("volte_stack_healthy") is True:
            last_status["apk_upgrade_verified"] = True
            return last_status
        if is_bound:
            if bound_since is None:
                bound_since = time.time()
            elif time.time() - bound_since >= 16.0:
                last_status["apk_upgrade_verified"] = True
                return last_status
        time.sleep(3.0)

    svc = last_status.get("ims_service", {})
    if (
        isinstance(svc, dict)
        and svc.get("present") is True
        and svc.get("version") == EXPECTED_VERSION
        and svc.get("ril_connected") is True
        and svc.get("feature_ready") is True
    ):
        last_status["apk_upgrade_verified"] = True
        return last_status
    raise RuntimeError(
        f"Upgraded {IMS_PKG} ({EXPECTED_VERSION}) did not bind to ImsResolver + rild within {timeout_sec}s: "
        f"{json.dumps(last_status)}"
    )


def run_upgrade_apk_flow(
    adb: AdbClient,
    apk_path: Path,
    do_reboot: bool,
    on_progress: Callable[[str], None] | None = None,
    require_registered: bool = False,
) -> dict[str, object]:
    if on_progress:
        on_progress("Verifying device identity & upgrading Qcom32VolteIms.apk in-place (zero EFS writes)...")
    ident = verify_device_identity(adb, require_verified_efs_profile=False)
    cleanup_legacy_packages(adb, include_manager=False)
    adb.run(["install", "-r", str(apk_path)], timeout=60)
    adb.shell(
        f"am broadcast -n {IMS_ADMIN_RECEIVER} -a {IMS_ADMIN_ACTION} "
        "--es op apply_override --es cmd apply_override --include-stopped-packages",
        timeout=30,
    )
    if do_reboot:
        if on_progress:
            on_progress("Rebooting phone normally & verifying hands-free VoLTE restoration...")
        adb.wait_after_reboot(str(ident["boot_id"]))
        status = poll_until_apk_bound(adb, require_registered=require_registered, timeout_sec=90)
    else:
        status = poll_until_apk_bound(adb, require_registered=require_registered, timeout_sec=35)
    status["upgraded_apk_sha256"] = sha256_bytes(apk_path.read_bytes())
    return status


def run_install_manager_flow(
    adb: AdbClient,
    manager_apk_path: Path,
    on_progress: Callable[[str], None] | None = None,
) -> dict[str, object]:
    if on_progress:
        on_progress("Verifying device identity & installing Qcom32VolteManager.apk...")
    ident = verify_device_identity(adb, require_verified_efs_profile=False)
    cleanup_legacy_packages(adb, include_manager=True)
    adb.run(["install", "-r", str(manager_apk_path)], timeout=60)
    return {
        "installed_package": MANAGER_PKG,
        "manager_apk_sha256": sha256_bytes(manager_apk_path.read_bytes()),
        "identity": ident,
    }


def run_rollback_flow(
    adb: AdbClient,
    prov_path: Path,
    on_progress: Callable[[str], None] | None = None,
) -> dict[str, object]:
    def step(msg: str) -> None:
        if on_progress:
            on_progress(msg)

    step("Enabling ADB Root & clearing persistent CarrierConfig overrides...")
    adb.ensure_root()
    ident = verify_device_identity(adb, require_verified_efs_profile=True)

    cleanup_legacy_packages(adb, include_manager=True)
    adb.shell(
        f"am broadcast -n {IMS_ADMIN_RECEIVER} -a {IMS_ADMIN_ACTION} "
        "--es op clear_override --es cmd clear_override --include-stopped-packages",
        timeout=30,
        check=False,
    )
    adb.run(["uninstall", IMS_PKG], timeout=30, check=False)
    adb.run(["uninstall", MANAGER_PKG], timeout=30, check=False)
    adb.shell("setprop persist.radio.fallbackmode ''", timeout=15, check=False)

    backup_dir = persistent_backup_root() / ("volte-ctl-rollback-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup_dir.mkdir(parents=True, exist_ok=True)
    try:
        step("Restoring all 54 modem EFS items to factory baseline + EFS_SYNC...")
        adb.run(["push", str(prov_path), REMOTE_TMP_PROVISIONER], timeout=30)
        adb.shell(f"chmod 0700 {REMOTE_TMP_PROVISIONER}")
        rb_out = adb.shell(f"{REMOTE_TMP_PROVISIONER} --rollback-all", timeout=90)
        (backup_dir / "efs-rollback-all.txt").write_text(rb_out, encoding="utf-8")
    finally:
        adb.shell(f"rm -f {REMOTE_TMP_PROVISIONER}", check=False)

    step("Rebooting phone normally to finalize factory baseline...")
    new_boot = adb.wait_after_reboot(str(ident["boot_id"]))
    post_ident = verify_device_identity(adb, require_verified_efs_profile=True)
    return {
        "rollback": "COMPLETE",
        "post_reboot_boot_id": new_boot,
        "rollback_log_directory": str(backup_dir),
        "identity": post_ident,
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--adb", default=None, help="Path to adb binary")
    ap.add_argument("--serial", default=None, help="Optional ADB device serial")
    ap.add_argument("--out-json", default=None, help="Optional file path to write JSON output")
    sub = ap.add_subparsers(dest="subcmd", required=True)

    sub.add_parser("self-test", help="Run offline unit tests for parsers, profile guards, catalog & PII redaction")
    sub.add_parser("status", help="Read-only live VoLTE, ImsResolver & QMI_IMSA status check")
    sub.add_parser(
        "inspect-device",
        help="100% read-only 4-Gate readiness check for any connected 32-bit Qualcomm phone + AI/GitHub porting guide",
    )
    p_models = sub.add_parser(
        "list-models",
        help="List unlocked BlackBerry Passport editions & candidate 32-bit Qualcomm families",
    )
    p_models.add_argument("--query", default=None, help="Optional model/codename filter (e.g. oslo, klte, cancro)")

    p_deploy = sub.add_parser("deploy", help="Guarded Stage 6A modem EFS provisioning + APK install + reboot verification")
    p_deploy.add_argument("--apk", default=None, help="Path to signed Qcom32VolteIms.apk")
    p_deploy.add_argument("--provisioner", default=None, help="Path to ARM32 pp_regconfig_provision_v6")

    p_upg = sub.add_parser("upgrade-apk", help="In-place Qcom32VolteIms.apk upgrade (zero EFS writes)")
    p_upg.add_argument("--apk", default=None, help="Path to signed Qcom32VolteIms.apk")
    p_upg.add_argument("--reboot", action="store_true", help="Reboot and verify hands-free VoLTE restoration")
    p_upg.add_argument(
        "--require-registered",
        action="store_true",
        help="Require active SIM carrier VoLTE registration after APK upgrade",
    )

    p_mgr = sub.add_parser("install-manager", help="Install optional on-device companion app Qcom32VolteManager.apk")
    p_mgr.add_argument("--manager-apk", default=None, help="Path to signed Qcom32VolteManager.apk")

    p_rb = sub.add_parser("rollback", help="Restore factory modem EFS baseline, uninstall APK, and reboot")
    p_rb.add_argument("--provisioner", default=None, help="Path to ARM32 pp_regconfig_provision_v6")

    args = ap.parse_args(argv)
    if args.subcmd == "self-test":
        return cmd_self_test(args.out_json)
    if args.subcmd == "list-models":
        entries = device_readiness.list_catalog_entries(args.query)
        emit_json(
            {
                "github_repo": device_readiness.GITHUB_REPO_URL,
                "builtin_ai_skills": [device_readiness.SKILL_PATH_AGENTS, device_readiness.SKILL_PATH_CLAUDE],
                "count": len(entries),
                "families": entries,
            },
            args.out_json,
        )
        return 0

    adb = AdbClient(resolve_adb(args.adb), args.serial)
    if args.subcmd == "status":
        emit_json(query_live_volte_status(adb), args.out_json)
        return 0
    if args.subcmd == "inspect-device":
        emit_json(device_readiness.run_readonly_readiness_audit(adb), args.out_json)
        return 0
    if args.subcmd == "deploy":
        apk = resolve_artifact(args.apk, "Qcom32VolteIms.apk")
        prov = resolve_artifact(args.provisioner, "pp_regconfig_provision_v6", "build/regconfig-provision-v6/*/pp_regconfig_provision_v6")
        emit_json(run_deploy_flow(adb, apk, prov), args.out_json)
        return 0
    if args.subcmd == "upgrade-apk":
        apk = resolve_artifact(args.apk, "Qcom32VolteIms.apk")
        emit_json(run_upgrade_apk_flow(adb, apk, args.reboot, require_registered=args.require_registered), args.out_json)
        return 0
    if args.subcmd == "install-manager":
        mgr = resolve_artifact(args.manager_apk, "Qcom32VolteManager.apk")
        emit_json(run_install_manager_flow(adb, mgr), args.out_json)
        return 0
    if args.subcmd == "rollback":
        prov = resolve_artifact(args.provisioner, "pp_regconfig_provision_v6", "build/regconfig-provision-v6/*/pp_regconfig_provision_v6")
        emit_json(run_rollback_flow(adb, prov), args.out_json)
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
