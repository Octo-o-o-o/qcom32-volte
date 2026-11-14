#!/usr/bin/env python3
"""Build standalone Windows x64 single-file desktop activator EXE for the Qcom32 VoLTE & IMS Stack.

Bundles:
  - Python 3 + Tcl/Tk runtime (via PyInstaller `--onefile --windowed --noupx`)
  - Official Android Platform-Tools (`adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`, `NOTICE.txt`)
  - Verified phone release payloads (`pp_regconfig_provision_v6`, `libimsa_probe.so`, `Qcom32VolteIms.apk`, `Qcom32VolteManager.apk`, `release-manifest.json`)
  - Device profile registry (`devices/*/profile.json`)
  - Brand icons (`assets/app.ico`, `assets/icon.png`, `assets/icon-96.png`) & `THIRD-PARTY-NOTICES.txt`

Performs post-build PyInstaller archive inspection (rejecting any `.pem`, `.key`, `.pk8`, `.jks`, `.keystore`, or `private/`)
and runs `qcom32-volte-<ver>-windows-x64.exe --self-test` offline to guarantee startup & SHA-256 payload integrity.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

REPO_ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_ARCHIVE_PATTERNS = (".pem", ".key", ".pk8", ".jks", ".keystore", "private/")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_platform_tools(explicit: str | None) -> Path:
    candidates = []
    if explicit:
        candidates.append(Path(explicit).expanduser().resolve())
    env_dir = os.environ.get("ANDROID_PLATFORM_TOOLS_DIR")
    if env_dir:
        candidates.append(Path(env_dir).expanduser().resolve())
    candidates.extend([
        REPO_ROOT / "private/toolchain/platform-tools",
        Path(r"C:\LifeSpace\bbq10\tools\volte_session\android-tools\platform-tools"),
    ])
    for cand in candidates:
        if (cand / "adb.exe").is_file() and (cand / "AdbWinApi.dll").is_file() and (cand / "AdbWinUsbApi.dll").is_file():
            return cand
    raise FileNotFoundError(
        "Missing official Windows platform-tools (`adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`). Pass --platform-tools."
    )


def write_version_file(dst: Path, ver_tuple: tuple[int, int, int, int], version_label: str) -> None:
    content = f"""# UTF-8
VSVersionInfo(
  ffi=FixedFileInfo(
    filevers={ver_tuple},
    prodvers={ver_tuple},
    mask=0x3f,
    flags=0x0,
    OS=0x40004,
    fileType=0x1,
    subtype=0x0,
    date=(0, 0)
  ),
  kids=[
    StringFileInfo([
      StringTable(
        '040904B0',
        [
          StringStruct('CompanyName', 'OctoLab Open Source'),
          StringStruct('FileDescription', 'Qcom32 VoLTE — 32-Bit Qualcomm Android 11 Native IMS Stack Activator'),
          StringStruct('FileVersion', '{version_label}'),
          StringStruct('InternalName', 'qcom32-volte'),
          StringStruct('LegalCopyright', 'Copyright (c) 2026 OctoLab (Apache-2.0)'),
          StringStruct('OriginalFilename', 'qcom32-volte-{version_label}-windows-x64.exe'),
          StringStruct('ProductName', 'Qcom32 VoLTE & IMS Stack'),
          StringStruct('ProductVersion', '{version_label}')
        ]
      )
    ]),
    VarFileInfo([VarStruct('Translation', [1033, 1200])])
  ]
)
"""
    dst.write_text(content, encoding="utf-8")


def inspect_pyinstaller_archive(exe_path: Path) -> int:
    from PyInstaller.archive.readers import CArchiveReader

    reader = CArchiveReader(str(exe_path))
    names = list(reader.toc.keys())
    for name in names:
        low = name.replace("\\", "/").lower()
        for bad in FORBIDDEN_ARCHIVE_PATTERNS:
            if bad in low:
                raise RuntimeError(f"Forbidden secret/key path leaked into Windows EXE archive: {name}")
    required = {
        "payloads/pp_regconfig_provision_v6",
        "payloads/libimsa_probe.so",
        "payloads/Qcom32VolteIms.apk",
        "payloads/Qcom32VolteManager.apk",
        "payloads/release-manifest.json",
        "devices/blackberry-passport-wolverine/profile.json",
        "vendor/platform-tools/adb.exe",
        "THIRD-PARTY-NOTICES.txt",
    }
    normalized = {n.replace("\\", "/") for n in names}
    missing = sorted(required - normalized)
    if missing:
        raise RuntimeError(f"Windows EXE archive missing required embedded files: {missing}")
    return len(names)


def main() -> int:
    if os.name != "nt":
        raise RuntimeError("build_windows_exe.py must be executed on Windows x64")

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--release-dir", default=str(REPO_ROOT / "build/release"), help="Directory containing built phone payloads")
    ap.add_argument("--out-dir", default=str(REPO_ROOT / "build/windows"), help="Output directory for Windows EXE")
    ap.add_argument("--platform-tools", default=None, help="Directory containing adb.exe + AdbWinApi.dll + AdbWinUsbApi.dll")
    args = ap.parse_args()

    proj = json.loads((REPO_ROOT / "project.json").read_text(encoding="utf-8"))
    ver = proj["version"]
    rev = int(proj["desktop_revision"])
    ver_parts = [int(x) for x in ver.split(".")]
    ver_tuple = (ver_parts[0], ver_parts[1], ver_parts[2], rev)
    version_label = f"{ver}-r{rev}"
    exe_name = f"qcom32-volte-{version_label}-windows-x64"

    release_dir = Path(args.release_dir).expanduser().resolve()
    out_dir = Path(args.out_dir).expanduser().resolve()
    stage_dir = out_dir / "_stage"
    pyi_work = out_dir / "_pyi_work"
    pyi_spec = out_dir / "_pyi_spec"

    for d in (stage_dir, pyi_work, pyi_spec):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    pt_dir = resolve_platform_tools(args.platform_tools)
    staged_pt = stage_dir / "platform-tools"
    staged_pt.mkdir(parents=True, exist_ok=True)
    for fname in ("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt"):
        src = pt_dir / fname
        if src.is_file():
            shutil.copy2(src, staged_pt / fname)

    staged_payloads = stage_dir / "payloads"
    staged_payloads.mkdir(parents=True, exist_ok=True)
    for fname in (
        "pp_regconfig_provision_v6",
        "libimsa_probe.so",
        "Qcom32VolteIms.apk",
        "Qcom32VolteManager.apk",
        "release-manifest.json",
    ):
        src = release_dir / fname
        if not src.is_file():
            raise FileNotFoundError(f"Missing phone payload {src} — run `cli/build_all.py` first.")
        shutil.copy2(src, staged_payloads / fname)

    notices_file = stage_dir / "THIRD-PARTY-NOTICES.txt"
    notice_parts = [
        (REPO_ROOT / "NOTICE.md").read_text(encoding="utf-8"),
        "\n" + ("=" * 72) + "\n",
        (REPO_ROOT / "LICENSE").read_text(encoding="utf-8"),
    ]
    adb_notice = staged_pt / "NOTICE.txt"
    if adb_notice.is_file():
        notice_parts.extend([
            "\n" + ("=" * 72) + "\nAndroid SDK Platform-Tools Notice:\n",
            adb_notice.read_text(encoding="utf-8", errors="replace"),
        ])
    notices_file.write_text("".join(notice_parts), encoding="utf-8")

    ver_file = stage_dir / "file_version_info.txt"
    write_version_file(ver_file, ver_tuple, version_label)

    ico_path = REPO_ROOT / "assets/app.ico"
    if not ico_path.is_file():
        raise FileNotFoundError(f"Missing {ico_path} — run `tools/generate_brand_assets.py` first.")

    pyi_cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
        "--windowed",
        "--noupx",
        "--name",
        exe_name,
        "--icon",
        str(ico_path),
        "--version-file",
        str(ver_file),
        "--paths",
        str(REPO_ROOT / "cli"),
        "--hidden-import",
        "volte_ctl",
        "--add-data",
        f"{staged_payloads}{os.pathsep}payloads",
        "--add-data",
        f"{REPO_ROOT / 'devices'}{os.pathsep}devices",
        "--add-data",
        f"{staged_pt}{os.pathsep}vendor/platform-tools",
        "--add-data",
        f"{REPO_ROOT / 'assets'}{os.pathsep}assets",
        "--add-data",
        f"{notices_file}{os.pathsep}.",
        "--distpath",
        str(out_dir),
        "--workpath",
        str(pyi_work),
        "--specpath",
        str(pyi_spec),
        str(REPO_ROOT / "cli/gui.py"),
    ]
    proc = subprocess.run(pyi_cmd, cwd=REPO_ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300)
    if proc.returncode != 0:
        raise RuntimeError(f"PyInstaller failed (rc={proc.returncode}):\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}")

    exe_path = out_dir / f"{exe_name}.exe"
    if not exe_path.is_file():
        raise FileNotFoundError(f"Expected output EXE not found: {exe_path}")

    toc_entries = inspect_pyinstaller_archive(exe_path)

    selftest_json = out_dir / "exe-self-test.json"
    if selftest_json.exists():
        selftest_json.unlink()
    st_proc = subprocess.run(
        [str(exe_path), "--self-test", str(selftest_json)],
        cwd=out_dir,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
    )
    if st_proc.returncode != 0 or not selftest_json.is_file():
        raise RuntimeError(f"Built EXE --self-test failed (rc={st_proc.returncode}): {st_proc.stderr or st_proc.stdout}")
    st_report = json.loads(selftest_json.read_text(encoding="utf-8"))
    if st_report.get("self_test") != "PASS" or st_report.get("frozen") is not True:
        raise RuntimeError(f"Unexpected EXE self-test report: {st_report}")

    for tmp_d in (stage_dir, pyi_work, pyi_spec):
        shutil.rmtree(tmp_d, ignore_errors=True)

    summary = {
        "exe_path": exe_path.relative_to(REPO_ROOT).as_posix() if exe_path.is_relative_to(REPO_ROOT) else str(exe_path),
        "bytes": exe_path.stat().st_size,
        "sha256": sha256_file(exe_path),
        "archive_toc_entries": toc_entries,
        "self_test": st_report,
    }
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
