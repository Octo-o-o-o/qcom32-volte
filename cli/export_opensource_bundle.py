#!/usr/bin/env python3
"""Export the standalone public source-available Git repository bundle AND package v1.2.7-r1 release assets
for `qcom32-volte`.

Outputs:
  1. `dist/qcom32-volte-opensource/` — Clean public Git repository tree (zero research clutter, zero keys, zero PII)
  2. `dist/release-v1.2.7-r1/` (when built artifacts exist under `build/release/` & `build/windows/`):
       - `qcom32-volte-1.2.7-r1-windows-x64.exe` (+ `.sha256`)
       - `Qcom32VolteManager-1.2.7-r1.apk` (+ `.sha256`)
       - `Qcom32VolteIms-2.9-r1-platform.apk` (+ `.sha256`)
       - `qcom32-volte-1.2.7-r1-portable.zip` (+ `.sha256`)
       - `qcom32-volte-1.2.7-r1-source.zip` (+ `.sha256`)
       - `SHA256SUMS.txt`
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

REPO_ROOT = Path(__file__).resolve().parents[1]

EXPORT_TREE_ENTRIES = (
    "README.md",
    "README.en.md",
    "LICENSE",
    "COMMERCIAL-LICENSE.md",
    "NOTICE.md",
    "project.json",
    "Start-GUI.cmd",
    ".gitignore",
    ".github/workflows/ci.yml",
    ".agents/skills/qcom32-volte",
    ".claude/skills/qcom32-volte",
    "assets",
    "devices",
    "src",
    "cli",
    "rom-integration",
    "docs/guide",
    "archive/README.md",
    "archive/LESSONS_LEARNED.md",
    "archive/catalog.json",
    "website/package.json",
    "website/wrangler.jsonc",
    "website/worker.mjs",
    "website/README.md",
    "website/scripts",
    "website/tests",
    "website/src",
)

FORBIDDEN_SUFFIXES = {".pem", ".key", ".pk8", ".keystore", ".jks", ".pyc"}
BINARY_ASSET_SUFFIXES = {".png", ".ico"}
PII_SCAN_RE = re.compile(r"(?<![0-9a-fA-F])\b\d{11,20}\b(?![0-9a-fA-F])")
SYNTHETIC_UNIT_TEST_FIXTURES = {
    "18612" "345678",
    "861861" "2345678",
    "861860" "0001234",
    "861860" "0009999",
    "18688" "887777",
    "4600112" "34567890",
    "4600198" "76543210",
}


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_sha_sidecar(asset_path: Path) -> str:
    digest = sha256_file(asset_path)
    sidecar = asset_path.with_name(asset_path.name + ".sha256")
    sidecar.write_text(f"{digest}  {asset_path.name}\n", encoding="utf-8", newline="\n")
    return digest


def zip_directory_deterministic(src_dir: Path, dst_zip: Path, prefix: str) -> None:
    with zipfile.ZipFile(dst_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for fpath in sorted(src_dir.rglob("*")):
            if not fpath.is_file():
                continue
            arcname = f"{prefix}/{fpath.relative_to(src_dir).as_posix()}"
            zi = zipfile.ZipInfo(arcname, date_time=(2026, 11, 14, 14, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o100644 << 16
            zf.writestr(zi, fpath.read_bytes())


def build_portable_zip(opensource_dir: Path, release_dir: Path, dst_zip: Path, ver_label: str) -> None:
    prefix = f"qcom32-volte-{ver_label}-portable"
    portable_entries = (
        "README.md",
        "README.en.md",
        "LICENSE",
        "COMMERCIAL-LICENSE.md",
        "NOTICE.md",
        "project.json",
        "Start-GUI.cmd",
        ".agents/skills/qcom32-volte",
        ".claude/skills/qcom32-volte",
        "assets",
        "devices",
        "cli/device_readiness.py",
        "cli/oslo_rom.py",
        "cli/volte_ctl.py",
        "cli/gui.py",
        "docs/guide",
    )
    payload_names = (
        "pp_regconfig_provision_v6",
        "libimsa_probe.so",
        "Qcom32VolteIms.apk",
        "Qcom32VolteManager.apk",
        "release-manifest.json",
    )
    with zipfile.ZipFile(dst_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for rel in portable_entries:
            src = opensource_dir / rel
            files = sorted(p for p in src.rglob("*") if p.is_file()) if src.is_dir() else [src]
            for fp in files:
                arc_rel = fp.relative_to(opensource_dir).as_posix()
                zi = zipfile.ZipInfo(f"{prefix}/{arc_rel}", date_time=(2026, 11, 14, 14, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.external_attr = (0o100755 if fp.suffix == ".py" else 0o100644) << 16
                zf.writestr(zi, fp.read_bytes())
        for pname in payload_names:
            psrc = release_dir / pname
            zi = zipfile.ZipInfo(f"{prefix}/payloads/{pname}", date_time=(2026, 11, 14, 14, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o100644 << 16
            zf.writestr(zi, psrc.read_bytes())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument(
        "--out",
        default=str(REPO_ROOT / "dist/qcom32-volte-opensource"),
        help="Destination directory for clean public Git repository bundle",
    )
    ap.add_argument(
        "--release-out",
        default=None,
        help="Destination directory for packaged release assets (default: dist/release-v<ver>-r<rev>)",
    )
    ap.add_argument(
        "--skip-release-package",
        action="store_true",
        help="Export dist/qcom32-volte-opensource only without repackaging dist/release-*",
    )
    ap.add_argument("--release-build-dir", type=Path, default=REPO_ROOT / "build/release")
    ap.add_argument("--windows-build-dir", type=Path, default=REPO_ROOT / "build/windows")
    args = ap.parse_args()

    # Never publish stale agent instructions that label unverified editions as verified.
    helper = (REPO_ROOT / ".agents/skills/qcom32-volte/scripts/ai_device_helper.py").read_text(encoding="utf-8")
    skill = (REPO_ROOT / ".agents/skills/qcom32-volte/SKILL.md").read_text(encoding="utf-8")
    if "EXPERIMENTAL_UNVERIFIED_EDITION" not in helper or "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE" not in skill:
        raise RuntimeError("Export blocked: update the read-only .agents skill and helper for unverified Passport editions first.")

    proj = json.loads((REPO_ROOT / "project.json").read_text(encoding="utf-8"))
    ver_label = f"{proj['version']}-r{proj['desktop_revision']}"
    tag = f"v{ver_label}"

    dst_root = Path(args.out).expanduser().resolve()
    if dst_root == REPO_ROOT:
        raise ValueError("Refusing to overwrite repository root")
    if dst_root.exists():
        shutil.rmtree(dst_root)
    dst_root.mkdir(parents=True, exist_ok=True)

    exported_files: list[dict[str, object]] = []

    for rel in EXPORT_TREE_ENTRIES:
        src = REPO_ROOT / rel
        if not src.exists():
            raise FileNotFoundError(f"Missing required source-available entry: {rel}")
        dst = dst_root / rel
        if src.is_dir():
            shutil.copytree(
                src,
                dst,
                ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.pyo", "node_modules", "dist", ".wrangler"),
            )
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            if rel == "README.md":
                raw_readme = src.read_text(encoding="utf-8")
                marker = "<!-- RESEARCH_MONOREPO_HISTORY_START -->"
                if marker in raw_readme:
                    raw_readme = raw_readme.split(marker, 1)[0].rstrip() + "\n"
                dst.write_text(raw_readme, encoding="utf-8")
            else:
                shutil.copy2(src, dst)

    for fpath in sorted(dst_root.rglob("*")):
        if not fpath.is_file():
            continue
        rel_out = fpath.relative_to(dst_root).as_posix()
        if fpath.suffix.lower() in FORBIDDEN_SUFFIXES:
            raise RuntimeError(f"Forbidden key/binary file detected in export bundle: {rel_out}")
        if fpath.suffix.lower() not in BINARY_ASSET_SUFFIXES:
            text = fpath.read_text(encoding="utf-8", errors="ignore")
            pii_hits = PII_SCAN_RE.findall(text)
            real_hits = [
                h for h in pii_hits
                if not h.startswith("2026") and h not in SYNTHETIC_UNIT_TEST_FIXTURES
            ]
            if real_hits:
                raise RuntimeError(f"Potential digit PII sequence in {rel_out}; raw values omitted")
        exported_files.append({
            "path": rel_out,
            "bytes": fpath.stat().st_size,
            "sha256": sha256_file(fpath),
        })

    release_summary: dict[str, object] = {"packaged": False}
    build_rel_dir = args.release_build_dir.resolve()
    win_exe = args.windows_build_dir.resolve() / f"qcom32-volte-{ver_label}-windows-x64.exe"
    if (not args.skip_release_package) and (build_rel_dir / "Qcom32VolteIms.apk").is_file() and (build_rel_dir / "Qcom32VolteManager.apk").is_file() and win_exe.is_file():
        rel_out = Path(args.release_out).expanduser().resolve() if args.release_out else (REPO_ROOT / f"dist/release-{tag}")
        if rel_out.exists():
            shutil.rmtree(rel_out)
        rel_out.mkdir(parents=True, exist_ok=True)

        ims_short = proj["ims_service_version"].split("-", 1)[0]
        dst_win = rel_out / f"qcom32-volte-{ver_label}-windows-x64.exe"
        dst_mgr = rel_out / f"Qcom32VolteManager-{ver_label}.apk"
        dst_ims = rel_out / f"Qcom32VolteIms-{ims_short}-r{proj['desktop_revision']}-platform.apk"
        dst_port = rel_out / f"qcom32-volte-{ver_label}-portable.zip"
        dst_src = rel_out / f"qcom32-volte-{ver_label}-source.zip"

        shutil.copy2(win_exe, dst_win)
        shutil.copy2(build_rel_dir / "Qcom32VolteManager.apk", dst_mgr)
        shutil.copy2(build_rel_dir / "Qcom32VolteIms.apk", dst_ims)
        build_portable_zip(dst_root, build_rel_dir, dst_port, ver_label)
        zip_directory_deterministic(dst_root, dst_src, f"qcom32-volte-{ver_label}")

        primary_assets = [dst_win, dst_mgr, dst_ims, dst_port, dst_src]
        sums_lines: list[str] = []
        assets_info: dict[str, object] = {}
        for asset in primary_assets:
            digest = write_sha_sidecar(asset)
            sums_lines.append(f"{digest}  {asset.name}")
            assets_info[asset.name] = {"bytes": asset.stat().st_size, "sha256": digest}

        (rel_out / "SHA256SUMS.txt").write_text("\n".join(sums_lines) + "\n", encoding="utf-8", newline="\n")
        release_summary = {
            "packaged": True,
            "tag": tag,
            "release_directory": rel_out.relative_to(REPO_ROOT).as_posix() if rel_out.is_relative_to(REPO_ROOT) else str(rel_out),
            "assets": assets_info,
        }

    print(json.dumps({
        "success": True,
        "bundle_directory": dst_root.relative_to(REPO_ROOT).as_posix() if dst_root.is_relative_to(REPO_ROOT) else str(dst_root),
        "exported_file_count": len(exported_files),
        "release": release_summary,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
