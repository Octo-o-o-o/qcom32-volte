#!/usr/bin/env python3
"""Standalone deployment, health-status, APK upgrade, companion-app install, and factory-rollback CLI
for BlackBerry Passport Android 11 VoLTE.

Works identically in the research monorepo, standalone open-source export, portable ZIP,
and PyInstaller single-file Windows EXE (`zero` third-party Python dependencies).

Commands:
  python3 cli/volte_ctl.py self-test        # Run offline parser, guard & PII-redaction unit tests
  python3 cli/volte_ctl.py status           # Read-only live VoLTE / QMI_IMSA / ImsResolver health check
  python3 cli/volte_ctl.py deploy           # Guarded Stage 6A modem EFS provisioning + APK install + reboot verification
  python3 cli/volte_ctl.py upgrade-apk      # Zero-EFS-write in-place APK upgrade + optional reboot verification
  python3 cli/volte_ctl.py install-manager  # Install optional on-device companion app (PassportVolteManager.apk)
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

FROZEN_ROOT: Path | None = Path(getattr(sys, "_MEIPASS")) if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS") else None
REPO_ROOT = FROZEN_ROOT if FROZEN_ROOT is not None else Path(__file__).resolve().parents[1]

IMS_PKG = "org.passport.volteims"
MANAGER_PKG = "org.passport.voltemanager"
IMS_SERVICE = f"{IMS_PKG}/.PassportVolteImsService"
IMS_ADMIN_RECEIVER = f"{IMS_PKG}/.VolteAdminReceiver"
IMS_ADMIN_ACTION = "org.passport.volteims.ACTION_ADMIN"
REMOTE_TMP_PROVISIONER = "/data/local/tmp/pp_regconfig_provision_v6"
EXPECTED_VERSION = "2.3-passport-volte"

PII_REDACT_RE = re.compile(r"\b\d{11,20}\b")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sanitize_text(text: str) -> str:
    return PII_REDACT_RE.sub("<REDACTED_DIGITS>", text)


def persistent_backup_root() -> Path:
    """Store backups in %LOCALAPPDATA% when running from a frozen EXE, never in %TEMP%."""
    if FROZEN_ROOT is not None:
        if os.name == "nt":
            base = Path(os.environ.get("LOCALAPPDATA") or (Path.home() / "AppData/Local"))
            target = base / "passport-android11-volte" / "backups"
        elif sys.platform == "darwin":
            target = Path.home() / "Library/Application Support/passport-android11-volte/backups"
        else:
            target = Path.home() / ".local/share/passport-android11-volte/backups"
    else:
        target = REPO_ROOT / "private"
    target.mkdir(parents=True, exist_ok=True)
    return target


def parse_service_dump(raw: str) -> dict[str, object]:
    present = "PASSPORT_VOLTE_IMS_V1" in raw
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
        m = re.search(rf"\b{re.escape(key)}=(-?\d+)", raw)
        return int(m.group(1)) if m else None

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
            raise RuntimeError(f"adb {' '.join(args)} failed (rc={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}")
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
        raise TimeoutError("Timed out waiting for BlackBerry Passport to complete normal reboot")


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


def verify_passport_identity(adb: AdbClient) -> dict[str, str]:
    device = adb.shell("getprop ro.product.device").strip()
    model = adb.shell("getprop ro.product.model").strip()
    selinux = adb.shell("getenforce").strip()
    boot_id = adb.shell("cat /proc/sys/kernel/random/boot_id").strip()
    sim_state = adb.shell("getprop gsm.sim.state").strip()
    operator = adb.shell("getprop gsm.sim.operator.numeric").strip()
    net_type = adb.shell("getprop gsm.network.type").strip()
    if device != "wolverine" or "Passport" not in model:
        raise RuntimeError(f"Refusing to run: target device is {model!r} ({device!r}), expected BlackBerry Passport (`wolverine`).")
    if selinux != "Enforcing":
        raise RuntimeError(f"Unexpected SELinux mode {selinux!r}; expected `Enforcing`.")
    return {
        "device": device,
        "model": model,
        "selinux": selinux,
        "boot_id": boot_id,
        "sim_state": sim_state,
        "sim_operator_numeric": operator,
        "network_type": net_type,
    }


def query_live_volte_status(adb: AdbClient) -> dict[str, object]:
    ident = verify_passport_identity(adb)
    svc_raw = adb.shell(f"dumpsys activity service {IMS_SERVICE} --qmi-probe", timeout=30, check=False)
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
    raise RuntimeError(f"VoLTE stack did not reach healthy REGISTERED state within {timeout_sec}s: {json.dumps(last_status)}")


def cmd_self_test() -> int:
    sample_dump = (
        "SERVICE org.passport.volteims/.PassportVolteImsService\n"
        "  PASSPORT_VOLTE_IMS_V1\n"
        f"  version={EXPECTED_VERSION}\n"
        "  rilConnected=true modemRegState=1\n"
        "  engine voiceImsReady=true publishedFrameworkReg=1\n"
        "  featureReadyCalled=true\n"
        "  qmiProbe pdp_connected=1 reg_status=2 legacy_reg=1 voip=2 voip_rat=1\n"
    )
    svc = parse_service_dump(sample_dump)
    qmi = parse_qmi_probe(sample_dump)
    assert svc["present"] is True and svc["version"] == EXPECTED_VERSION
    assert svc["ril_connected"] is True and svc["voice_ims_ready"] is True and svc["published_framework_reg"] == 1
    assert qmi == {"pdp_connected": 1, "reg_status": 2, "legacy_reg": 1, "voip": 2, "voip_rat": 1}
    assert parse_package_stopped("User 0: installed=true stopped=false") is False
    assert parse_package_stopped("User 0: installed=true stopped=true") is True
    fake_msisdn = "1" * 11
    fake_iccid = "8" * 20
    redacted = sanitize_text(f"num={fake_msisdn} iccid={fake_iccid} ok")
    assert fake_msisdn not in redacted and fake_iccid not in redacted
    print(json.dumps({"self_test": "PASS", "assertions": 9}, indent=2))
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

    step("Enabling ADB Root (UID 0) & verifying BlackBerry Passport identity...")
    adb.ensure_root()
    ident = verify_passport_identity(adb)
    if "LOADED" not in ident["sim_state"]:
        raise RuntimeError(f"SIM must be LOADED before deployment (got {ident['sim_state']!r}).")

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

    step("Installing PassportVolteIms.apk v2.3 & applying persistent CarrierConfig...")
    adb.run(["install", "-r", str(apk_path)], timeout=60)
    adb.shell(
        f"am broadcast -n {IMS_ADMIN_RECEIVER} -a {IMS_ADMIN_ACTION} --es cmd apply_override --include-stopped-packages",
        timeout=30,
    )
    pkg_dump = adb.shell(f"dumpsys package {IMS_PKG}", timeout=30)
    if parse_package_stopped(pkg_dump) is not False:
        raise RuntimeError(f"{IMS_PKG} remained stopped=true after apply_override broadcast")

    step("Rebooting phone normally & waiting for hands-free IMS / VoLTE registration...")
    new_boot = adb.wait_after_reboot(ident["boot_id"])
    ready_status = poll_until_volte_ready(adb, timeout_sec=90)
    ready_status["post_reboot_boot_id"] = new_boot
    ready_status["backup_directory"] = str(backup_dir)
    ready_status["apk_sha256"] = sha256_bytes(apk_path.read_bytes())
    ready_status["provisioner_sha256"] = sha256_bytes(prov_path.read_bytes())
    return ready_status


def run_upgrade_apk_flow(
    adb: AdbClient,
    apk_path: Path,
    do_reboot: bool,
    on_progress: Callable[[str], None] | None = None,
) -> dict[str, object]:
    if on_progress:
        on_progress("Verifying Passport identity & upgrading PassportVolteIms.apk in-place...")
    ident = verify_passport_identity(adb)
    adb.run(["install", "-r", str(apk_path)], timeout=60)
    adb.shell(
        f"am broadcast -n {IMS_ADMIN_RECEIVER} -a {IMS_ADMIN_ACTION} --es cmd apply_override --include-stopped-packages",
        timeout=30,
    )
    if do_reboot:
        if on_progress:
            on_progress("Rebooting phone normally & verifying hands-free VoLTE restoration...")
        adb.wait_after_reboot(ident["boot_id"])
    status = poll_until_volte_ready(adb, timeout_sec=90)
    status["upgraded_apk_sha256"] = sha256_bytes(apk_path.read_bytes())
    return status


def run_install_manager_flow(
    adb: AdbClient,
    manager_apk_path: Path,
    on_progress: Callable[[str], None] | None = None,
) -> dict[str, object]:
    if on_progress:
        on_progress("Verifying Passport identity & installing PassportVolteManager.apk...")
    ident = verify_passport_identity(adb)
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
    ident = verify_passport_identity(adb)

    adb.shell(
        f"am broadcast -n {IMS_ADMIN_RECEIVER} -a {IMS_ADMIN_ACTION} --es cmd clear_override --include-stopped-packages",
        timeout=30,
        check=False,
    )
    adb.run(["uninstall", IMS_PKG], timeout=30, check=False)

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
    new_boot = adb.wait_after_reboot(ident["boot_id"])
    post_ident = verify_passport_identity(adb)
    return {
        "rollback": "COMPLETE",
        "post_reboot_boot_id": new_boot,
        "rollback_log_directory": str(backup_dir),
        "identity": post_ident,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--adb", default=None, help="Path to adb binary")
    ap.add_argument("--serial", default=None, help="Optional ADB device serial")
    sub = ap.add_subparsers(dest="subcmd", required=True)

    sub.add_parser("self-test", help="Run offline unit tests for parsers, guards, and PII redaction")
    sub.add_parser("status", help="Read-only live VoLTE, ImsResolver & QMI_IMSA status check")

    p_deploy = sub.add_parser("deploy", help="Guarded Stage 6A modem EFS provisioning + APK install + reboot verification")
    p_deploy.add_argument("--apk", default=None, help="Path to signed PassportVolteIms.apk")
    p_deploy.add_argument("--provisioner", default=None, help="Path to ARM32 pp_regconfig_provision_v6")

    p_upg = sub.add_parser("upgrade-apk", help="In-place PassportVolteIms.apk upgrade (zero EFS writes)")
    p_upg.add_argument("--apk", default=None, help="Path to signed PassportVolteIms.apk")
    p_upg.add_argument("--reboot", action="store_true", help="Reboot and verify hands-free VoLTE restoration")

    p_mgr = sub.add_parser("install-manager", help="Install optional on-device companion app PassportVolteManager.apk")
    p_mgr.add_argument("--manager-apk", default=None, help="Path to signed PassportVolteManager.apk")

    p_rb = sub.add_parser("rollback", help="Restore factory modem EFS baseline, uninstall APK, and reboot")
    p_rb.add_argument("--provisioner", default=None, help="Path to ARM32 pp_regconfig_provision_v6")

    args = ap.parse_args()
    if args.subcmd == "self-test":
        return cmd_self_test()

    adb = AdbClient(resolve_adb(args.adb), args.serial)
    if args.subcmd == "status":
        print(json.dumps(query_live_volte_status(adb), indent=2))
        return 0
    if args.subcmd == "deploy":
        apk = resolve_artifact(args.apk, "PassportVolteIms.apk", "build/passport-volte-ims-v2r3/*/PassportVolteIms.apk")
        prov = resolve_artifact(args.provisioner, "pp_regconfig_provision_v6", "build/regconfig-provision-v6/*/pp_regconfig_provision_v6")
        print(json.dumps(run_deploy_flow(adb, apk, prov), indent=2))
        return 0
    if args.subcmd == "upgrade-apk":
        apk = resolve_artifact(args.apk, "PassportVolteIms.apk", "build/passport-volte-ims-v2r3/*/PassportVolteIms.apk")
        print(json.dumps(run_upgrade_apk_flow(adb, apk, args.reboot), indent=2))
        return 0
    if args.subcmd == "install-manager":
        mgr = resolve_artifact(args.manager_apk, "PassportVolteManager.apk")
        print(json.dumps(run_install_manager_flow(adb, mgr), indent=2))
        return 0
    if args.subcmd == "rollback":
        prov = resolve_artifact(args.provisioner, "pp_regconfig_provision_v6", "build/regconfig-provision-v6/*/pp_regconfig_provision_v6")
        print(json.dumps(run_rollback_flow(adb, prov), indent=2))
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
