"""Prepare the tested Silver Edition ROM for the standard VoLTE deployment."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
import time

import device_readiness


ROM = "18.1-20251205-UNOFFICIAL-wseries"
CONFIGS = {
    "init.qcom.rc": (
        "1875c65df26e0fa4e7102183d680b23cfd1142c38c51e1cb0b4157865618311e",
        "6dfb629d0d116ed90306bb5da6255ae633014bd111cf91bc3314d99d8ea266f8",
        ("service imsqmidaemon", "service imsdatadaemon", "on property:sys.ims.QMI_DAEMON_STATUS=1"),
    ),
    "init.target.rc": (
        "c26c786878727daeb8245ddd2ad804676bcfa3badb597287497f8ac201107176",
        "6a0fc3854d79ad9ad97258433d5455ba4e0194d4f17c75797c257b505cbda706",
        (
            "service ims_rtp_daemon", "service imscmservice",
            "on property:sys.ims.DATA_DAEMON_STATUS=1", "on property:sys.ims.DATA_DAEMON_STATUS=0",
        ),
    ),
}
VENDOR_CONFIG = "/vendor/etc/init/hw"
SYSTEM_BLOCK = "/dev/block/by-name/system"
SYSTEM_MOUNT = "/tmp/qcom32-oslo-system"
RECOVERY_CONFIG = f"{SYSTEM_MOUNT}/system{VENDOR_CONFIG}"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def prepare_config(name: str, original: bytes) -> bytes:
    original_hash, patched_hash, sections = CONFIGS[name]
    if digest(original) != original_hash:
        raise RuntimeError(f"{name} differs from the tested original")

    lines = original.decode("utf-8").splitlines(keepends=True)
    kept = []
    removing = False
    for line in lines:
        if line.strip() and not line[0].isspace():
            removing = any(line.strip() == section or line.startswith(section + " ") for section in sections)
        if not removing or not line.strip():
            kept.append(line)
    patched = "".join(kept).encode("utf-8")
    if digest(patched) != patched_hash:
        raise RuntimeError(f"{name} generated an unexpected patched hash")
    return patched


def shell(adb, command: str) -> str:
    return adb.shell(command, timeout=20).strip()


def config_state(hashes: dict[str, str]) -> str:
    if all(hashes.get(name) == spec[0] for name, spec in CONFIGS.items()):
        return "ORIGINAL"
    if all(hashes.get(name) == spec[1] for name, spec in CONFIGS.items()):
        return "PREPARED"
    raise RuntimeError("The ROM's IMS init files do not match the original or prepared tested build")


def running_config_state(adb) -> str:
    hashes = {}
    for name in CONFIGS:
        result = shell(adb, f"sha256sum {VENDOR_CONFIG}/{name}")
        hashes[name] = result.split()[0]
    return config_state(hashes)


def wait_ready(adb, state: str, seconds: int) -> None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        actual = adb.run(["get-state"], timeout=10, check=False).stdout.strip()
        if actual == state and (state != "device" or
                                adb.shell("getprop sys.boot_completed", timeout=10, check=False).strip() == "1"):
            return
        time.sleep(2)
    raise RuntimeError(f"Timed out waiting for {state} on the selected device")


def verify_oslo(ident: dict[str, object]) -> None:
    if (ident["device"], ident["rom_display"], ident["sdk"], ident["board_platform"], ident["cpu_abi"]) != (
        "oslo", ROM, 30, "msm8974", "armeabi-v7a",
    ):
        raise RuntimeError(f"ROM preparation requires the tested oslo {ROM} build")


def check_radio(ident: dict[str, object], state: str) -> None:
    if (ident["sim_state"], ident["network_type"]) != ("LOADED", "LTE"):
        raise RuntimeError("Expected a loaded SIM and LTE")
    if state == "PREPARED" and ident["legacy_ims_conflict"]:
        raise RuntimeError("A legacy IMS conflict remains after ROM preparation")


def write_recovery_config(adb, name: str, source: Path, scratch: Path) -> None:
    remote = f"/tmp/qcom32-oslo-{name}"
    readback = scratch / "readback"
    adb.run(["push", str(source), remote], timeout=20)
    try:
        adb.run(["pull", remote, str(readback)], timeout=20)
        if readback.read_bytes() != source.read_bytes():
            raise RuntimeError(f"Recovery transfer differs for {name}")
        shell(adb, f"cat {remote} > {RECOVERY_CONFIG}/{name} && sync")
        adb.run(["pull", f"{RECOVERY_CONFIG}/{name}", str(readback)], timeout=20)
        if readback.read_bytes() != source.read_bytes():
            raise RuntimeError(f"Recovery system readback differs for {name}")
    finally:
        shell(adb, f"rm -f {remote}")


def edit_recovery(adb, backup: Path, restore: bool) -> None:
    if shell(adb, "id -u") != "0":
        raise RuntimeError("The existing recovery must provide root ADB")
    if not shell(adb, f"ls -l {SYSTEM_BLOCK}").endswith(" -> /dev/block/mmcblk0p38"):
        raise RuntimeError("Unexpected system partition on the selected Silver Edition")

    with tempfile.TemporaryDirectory(prefix="qcom32-oslo-") as temp:
        scratch = Path(temp)
        shell(adb, f"mkdir {SYSTEM_MOUNT}")
        mounted = False
        try:
            shell(adb, f"mount -t ext4 -o ro,noload {SYSTEM_BLOCK} {SYSTEM_MOUNT}")
            mounted = True
            current = scratch / "current"
            current.mkdir()
            for name in CONFIGS:
                adb.run(["pull", f"{RECOVERY_CONFIG}/{name}", str(current / name)], timeout=20)
            hashes = {name: digest((current / name).read_bytes()) for name in CONFIGS}
            if restore:
                if any(hashes[name] not in CONFIGS[name][:2] for name in CONFIGS):
                    raise RuntimeError("Recovery contains an unknown init configuration")
            else:
                if config_state(hashes) != "ORIGINAL":
                    raise RuntimeError("Expected original IMS init files in recovery")
                for name in CONFIGS:
                    original = (current / name).read_bytes()
                    (backup / name).write_bytes(original)
                    (scratch / name).write_bytes(prepare_config(name, original))
                manifest = {"serial": adb.serial, "rom": ROM, "original_sha256": hashes}
                (backup / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            shell(adb, f"umount {SYSTEM_MOUNT}")
            mounted = False
            shell(adb, f"mount -t ext4 -o rw {SYSTEM_BLOCK} {SYSTEM_MOUNT}")
            mounted = True
            try:
                for name in CONFIGS:
                    write_recovery_config(adb, name, (backup if restore else scratch) / name, scratch)
            except Exception:
                if not restore:
                    for name in CONFIGS:
                        write_recovery_config(adb, name, backup / name, scratch)
                raise
        finally:
            if mounted:
                shell(adb, f"umount {SYSTEM_MOUNT}")
            shell(adb, f"rmdir {SYSTEM_MOUNT}")


def run(adb, identify, backup_root, *, apply=False, restore=None):
    if (apply or restore) and not adb.serial:
        raise RuntimeError("Use --serial to bind ROM preparation or restoration to one phone")
    if restore:
        backup = Path(restore).resolve()
        manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
        if (manifest["serial"], manifest["rom"]) != (adb.serial, ROM):
            raise RuntimeError("The ROM backup belongs to a different phone or ROM")
        for name in CONFIGS:
            if digest((backup / name).read_bytes()) != CONFIGS[name][0]:
                raise RuntimeError(f"Original backup hash mismatch: {name}")

    in_recovery = adb.run(["get-state"], timeout=10, check=False).stdout.strip() == "recovery"
    if not (restore and in_recovery):
        ident = identify(adb, require_verified_efs_profile=False)
        verify_oslo(ident)
        state = running_config_state(adb) if not restore else "RESTORE_REQUESTED"
        if not restore:
            check_radio(ident, state)
        if not (apply or restore) or (apply and state == "PREPARED"):
            return {"device": "oslo", "rom": ROM, "rom_state": state, "system_files_modified": False}
        if apply:
            contents = shell(adb, "cat /firmware/IMAGE/CONTENTS.TXT /firmware/image/contents.txt 2>/dev/null || true")
            build_id = device_readiness.extract_firmware_build_id(contents)
            baseband = device_readiness.assess_passport_baseband(str(ident["baseband"]), build_id)
            if not baseband["compatible"]:
                raise RuntimeError("The AAB982 modem prerequisite was not met")
            backup = Path(tempfile.mkdtemp(prefix="oslo-rom-backup-", dir=backup_root()))
            print(f"Original ROM backup directory: {backup}", file=sys.stderr, flush=True)
        if restore and adb.shell("pm path org.octolab.qcom32ims", timeout=10, check=False).strip().startswith("package:"):
            raise RuntimeError("Restore the original EFS and remove the project IMS package before restoring the old IMS init files")
        calls = re.findall(r"mCallState=(\d+)", shell(adb, "dumpsys telephony.registry"))
        if not calls or any(call != "0" for call in calls):
            raise RuntimeError("The phone must be idle before entering recovery")
        adb.run(["reboot", "recovery"], timeout=15)
        wait_ready(adb, "recovery", 50)

    try:
        edit_recovery(adb, backup, bool(restore))
    finally:
        adb.run(["reboot"], timeout=15)
    wait_ready(adb, "device", 135)
    ident = identify(adb, require_verified_efs_profile=False)
    verify_oslo(ident)
    state = running_config_state(adb)
    check_radio(ident, state)
    expected = "ORIGINAL" if restore else "PREPARED"
    if state != expected:
        raise RuntimeError(f"Expected {expected} after reboot, got {state}")
    if state == "PREPARED" and "/dev/socket/qmux_radio/rild_ims0" not in shell(adb, "cat /proc/net/unix"):
        raise RuntimeError("The expected RIL IMS socket is unavailable after reboot")
    return {"device": "oslo", "rom": ROM, "rom_state": state, "system_files_modified": True,
            "rom_backup_directory": str(backup)}
