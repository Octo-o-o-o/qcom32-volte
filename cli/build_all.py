#!/usr/bin/env python3
"""Standalone source-available builder & host unit-test runner for the Qcom32 VoLTE & IMS Stack.

Builds and verifies all four production components directly from `src/`:
  1. `src/modem-efs-provisioner/` -> `build/release/pp_regconfig_provision_v6` (ARM32 PIE + 559 host C assertions)
  2. `src/qmi-imsa-probe/`        -> `build/release/libimsa_probe.so`          (ARM32 PIE + 17 host C assertions)
  3. `src/android-ims-service/`   -> `build/release/Qcom32VolteIms.apk`        (Android 11 API 30 + host Java assertions + bytecode checks)
  4. `src/android-volte-manager/` -> `build/release/Qcom32VolteManager.apk`    (Android 11 API 30 companion UI + host Java assertions)

Usage:
  python3 cli/build_all.py                  # Run host unit tests + build ARM32 binaries & signed APK
  python3 cli/build_all.py --host-tests-only # Run all C & Java host unit tests only (no Android SDK/Bionic stubs needed)
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

REPO_ROOT = Path(__file__).resolve().parents[1]
EXE_SUFFIX = ".exe" if os.name == "nt" else ""


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find_executable(explicit: str | None, env_key: str, fallbacks: list[Path], binary_name: str) -> Path | None:
    if explicit:
        p = Path(explicit).expanduser().resolve()
        if p.is_file():
            return p
        raise FileNotFoundError(f"Explicit tool path not found: {p}")
    env_val = os.environ.get(env_key)
    if env_val:
        cand = Path(env_val).expanduser()
        if cand.is_dir():
            cand = cand / f"{binary_name}{EXE_SUFFIX}"
        if cand.is_file():
            return cand.resolve()
    which_hit = shutil.which(binary_name)
    if which_hit:
        return Path(which_hit).resolve()
    for fb in fallbacks:
        if fb.is_file():
            return fb.resolve()
    return None


def run_checked(argv: list[str], label: str) -> tuple[str, str]:
    proc = subprocess.run(argv, cwd=REPO_ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    if proc.returncode != 0:
        raise RuntimeError(
            f"[{label}] exited with code {proc.returncode}\n"
            f"CMD: {' '.join(argv)}\n"
            f"STDOUT:\n{proc.stdout}\n"
            f"STDERR:\n{proc.stderr}"
        )
    return proc.stdout, proc.stderr


def build_merged_sdk_with_stubs(sdk_jar: Path, stub_classes_dir: Path, dst_jar: Path) -> list[str]:
    replaced: list[str] = []
    stub_map: dict[str, Path] = {}
    for cls_file in sorted(stub_classes_dir.rglob("*.class")):
        entry = cls_file.resolve().relative_to(stub_classes_dir.resolve()).as_posix()
        stub_map[entry] = cls_file.resolve()

    with zipfile.ZipFile(sdk_jar, "r") as zin, zipfile.ZipFile(dst_jar, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            if info.filename in stub_map:
                replaced.append(info.filename)
                continue
            zout.writestr(info, zin.read(info.filename))
        for entry, cls_path in stub_map.items():
            zout.write(cls_path, entry)
    return replaced


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host-tests-only", action="store_true", help="Run host C and Java unit test suites without cross-compiling Android artifacts")
    ap.add_argument("--out-dir", default=str(REPO_ROOT / "build/release"), help="Output directory (default: build/release)")
    ap.add_argument("--zig", default=None, help="Path to zig executable (or set ZIG_EXE)")
    ap.add_argument("--jdk-bin", default=None, help="Path to JDK bin directory containing javac/java/jar/javap (or set JAVA_HOME)")
    ap.add_argument("--android-build-tools", default=None, help="Path to Android SDK build-tools directory containing aapt2/zipalign/lib/d8.jar/lib/apksigner.jar")
    ap.add_argument("--android-sdk-jar", default=None, help="Path to Android API 30 system/android.jar (with ImsService @SystemApi)")
    ap.add_argument("--sysroot-lib", default=None, help="Path to ARM32 Android system/lib directory containing libc.so and libdl.so")
    ap.add_argument("--sign-key", default=None, help="Path to ROM platform.pk8 signing key")
    ap.add_argument("--sign-cert", default=None, help="Path to ROM platform.x509.pem certificate")
    args = ap.parse_args()

    out_dir = Path(args.out_dir).expanduser().resolve()
    work_dir = out_dir / "_work"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)

    zig = find_executable(
        args.zig,
        "ZIG_EXE",
        [
            REPO_ROOT / f"private/toolchain/zig/zig{EXE_SUFFIX}",

        ],
        "zig",
    )
    if not zig:
        raise FileNotFoundError("Could not locate `zig` compiler. Install Zig 0.13+ or pass --zig / ZIG_EXE.")

    jdk_fallbacks: list[Path] = []
    jdk17_home = os.environ.get("JDK17_HOME")
    if jdk17_home:
        jdk_fallbacks.append(Path(jdk17_home) / "bin")
    jdk_fallbacks.append(REPO_ROOT / "private/toolchain/jdk/jdk-17.0.2/bin")
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        jdk_fallbacks.append(Path(java_home) / "bin")
    jdk_bin_explicit = Path(args.jdk_bin).expanduser().resolve() if args.jdk_bin else None

    def jdk_tool(name: str) -> Path:
        if jdk_bin_explicit:
            cand = jdk_bin_explicit / f"{name}{EXE_SUFFIX}"
            if cand.is_file():
                return cand
            raise FileNotFoundError(f"JDK tool `{name}` not found under --jdk-bin {jdk_bin_explicit}")
        # Prefer JAVA_HOME and bundled JDK 17 before PATH (Android 11 build-tools 30 d8.jar requires JDK 11-17, not JDK 25+)
        for fb in jdk_fallbacks:
            cand = fb / f"{name}{EXE_SUFFIX}"
            if cand.is_file():
                return cand.resolve()
        which_hit = shutil.which(name)
        if which_hit:
            return Path(which_hit).resolve()
        raise FileNotFoundError(f"Could not locate JDK tool `{name}`. Install JDK 17 or pass --jdk-bin / JAVA_HOME.")

    javac = jdk_tool("javac")
    java = jdk_tool("java")
    jar_tool = jdk_tool("jar")
    javap = jdk_tool("javap")
    keytool = jdk_tool("keytool")

    prov_src = REPO_ROOT / "src/modem-efs-provisioner"
    probe_src = REPO_ROOT / "src/qmi-imsa-probe"
    ims_src = REPO_ROOT / "src/android-ims-service"
    mgr_src = REPO_ROOT / "src/android-volte-manager"

    summary: dict[str, object] = {
        "schema": "bb-passport-volte-release-build-v1",
        "built_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(),
        "host_tests_only": bool(args.host_tests_only),
        "host_tests": {},
        "artifacts": {},
    }

    # 1. Host Unit Tests: modem-efs-provisioner
    prov_test_bin = work_dir / f"regconfig-provision-v6-test{EXE_SUFFIX}"
    run_checked(
        [
            str(zig), "cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-I", str(prov_src),
            str(prov_src / "wide_diag_codec.c"),
            str(prov_src / "regfile_diag_codec.c"),
            str(prov_src / "regconfig_provision_v6_test.c"),
            "-o", str(prov_test_bin),
        ],
        "provisioner-host-compile",
    )
    prov_test_out, _ = run_checked([str(prov_test_bin)], "provisioner-host-test")
    prov_test_json = json.loads(prov_test_out.strip())
    if prov_test_json.get("failed", 1) != 0 or prov_test_json.get("checks", 0) < 559:
        raise RuntimeError(f"modem-efs-provisioner host tests failed: {prov_test_json}")

    # 2. Host Unit Tests: qmi-imsa-probe
    probe_test_bin = work_dir / f"imsa-probe-v3-test{EXE_SUFFIX}"
    run_checked(
        [
            str(zig), "cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-I", str(probe_src),
            str(probe_src / "imsa_probe_v3.c"),
            str(probe_src / "tests/imsa_probe_v3_test.c"),
            "-o", str(probe_test_bin),
        ],
        "qmi-probe-host-compile",
    )
    probe_test_out, _ = run_checked([str(probe_test_bin)], "qmi-probe-host-test")
    probe_json_lines = [ln.strip() for ln in probe_test_out.splitlines() if ln.strip().startswith("{")]
    probe_test_json = json.loads(probe_json_lines[-1])
    if probe_test_json.get("failures", 1) != 0 or probe_test_json.get("checks", 0) < 15:
        raise RuntimeError(f"qmi-imsa-probe host tests failed: {probe_test_json}")

    # 2b. Standard POSIX/Bionic header compile check for Method 4 ROM source-tree (Soong cc_binary) compatibility
    run_checked(
        [
            str(zig), "cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-target", "arm-linux-musleabihf",
            "-I", str(probe_src),
            "-c", str(probe_src / "imsa_probe_v3.c"),
            "-o", str(work_dir / "imsa_probe_v3_std.o"),
        ],
        "qmi-probe-posix-lib-compile",
    )
    run_checked(
        [
            str(zig), "cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-target", "arm-linux-musleabihf",
            "-I", str(probe_src),
            "-c", str(probe_src / "imsa_probe_v3_main.c"),
            "-o", str(work_dir / "imsa_probe_v3_main_std.o"),
        ],
        "qmi-probe-posix-main-compile",
    )

    # 3. Host Unit Tests: android-ims-service
    host_cls_dir = work_dir / "ims-host-classes"
    host_cls_dir.mkdir(parents=True, exist_ok=True)
    ref_java_files = sorted(str(p) for p in (ims_src / "test_ref").rglob("*.java"))
    pure_java_files = [
        str(ims_src / "src/org/octolab/qcom32ims/RilImsWire.java"),
        str(ims_src / "src/org/octolab/qcom32ims/RilImsCoreClient.java"),
        str(ims_src / "src/org/octolab/qcom32ims/CallStateEngine.java"),
        str(ims_src / "src/org/octolab/qcom32ims/QmiProbeSummaryParser.java"),
        str(ims_src / "src/org/octolab/qcom32ims/VolteStatusNotificationLogic.java"),
        str(ims_src / "test/org/octolab/qcom32ims/Qcom32VolteImsHostTest.java"),
    ]
    run_checked(
        [
            str(javac), "-J-Duser.language=en", "-source", "8", "-target", "8",
            "-encoding", "UTF-8", "-Xlint:all,-options",
            "-d", str(host_cls_dir),
            *ref_java_files,
            *pure_java_files,
        ],
        "ims-host-compile",
    )
    ims_test_out, _ = run_checked(
        [str(java), "-cp", str(host_cls_dir), "org.octolab.qcom32ims.Qcom32VolteImsHostTest"],
        "ims-host-test",
    )
    ims_test_line = ims_test_out.strip()
    if not ims_test_line.startswith("PASS;"):
        raise RuntimeError(f"Qcom32VolteImsHostTest failed: {ims_test_line}")

    # 3b. Host Unit Tests: android-volte-manager
    mgr_host_cls = work_dir / "mgr-host-classes"
    mgr_host_cls.mkdir(parents=True, exist_ok=True)
    run_checked(
        [
            str(javac), "-J-Duser.language=en", "-source", "8", "-target", "8",
            "-encoding", "UTF-8", "-Xlint:all,-options",
            "-d", str(mgr_host_cls),
            str(mgr_src / "src/org/octolab/qcom32volte/VolteManagerLogic.java"),
            str(mgr_src / "test/VolteManagerLogicTest.java"),
        ],
        "mgr-host-compile",
    )
    mgr_test_out, _ = run_checked(
        [str(java), "-cp", str(mgr_host_cls), "org.octolab.qcom32volte.VolteManagerLogicTest"],
        "mgr-host-test",
    )
    mgr_test_line = mgr_test_out.strip()
    if not mgr_test_line.startswith("PASS "):
        raise RuntimeError(f"VolteManagerLogicTest failed: {mgr_test_line}")

    summary["host_tests"] = {
        "modem_efs_provisioner": prov_test_json,
        "qmi_imsa_probe": probe_test_json,
        "android_ims_service": ims_test_line,
        "android_volte_manager": mgr_test_line,
    }

    if args.host_tests_only:
        shutil.rmtree(work_dir, ignore_errors=True)
        print(json.dumps(summary, indent=2))
        return 0

    # Resolve Android cross-build dependencies
    sysroot_lib = Path(
        args.sysroot_lib
        or os.environ.get("SYSROOT_LIB_DIR")
        or (REPO_ROOT / "private/native-readonly/system/lib")
    ).expanduser().resolve()
    libc_so = sysroot_lib / "libc.so"
    libdl_so = sysroot_lib / "libdl.so"
    if not libc_so.is_file() or not libdl_so.is_file():
        raise FileNotFoundError(
            f"Missing ARM32 Bionic link stubs (`libc.so`, `libdl.so`) under {sysroot_lib}. "
            "Pull `/system/lib/libc.so` and `/system/lib/libdl.so` from the target Android 11 Passport or pass --sysroot-lib."
        )

    bt_dir = Path(
        args.android_build_tools
        or os.environ.get("ANDROID_BUILD_TOOLS_DIR")
        or (REPO_ROOT / "private/toolchain/build-tools/android-11")
    ).expanduser().resolve()
    aapt2 = bt_dir / f"aapt2{EXE_SUFFIX}"
    zipalign = bt_dir / f"zipalign{EXE_SUFFIX}"
    d8_jar = bt_dir / "lib/d8.jar"
    apksigner_jar = bt_dir / "lib/apksigner.jar"
    lambda_stubs = bt_dir / "core-lambda-stubs.jar"
    for req in (aapt2, zipalign, d8_jar, apksigner_jar, lambda_stubs):
        if not req.is_file():
            raise FileNotFoundError(f"Missing Android build-tools component: {req} (pass --android-build-tools)")

    sdk_jar = Path(
        args.android_sdk_jar
        or os.environ.get("ANDROID_SYSTEM_SDK_JAR")
        or (REPO_ROOT / "private/toolchain/android-system-30.jar")
    ).expanduser().resolve()
    if not sdk_jar.is_file():
        raise FileNotFoundError(f"Missing Android API 30 system SDK jar: {sdk_jar} (pass --android-sdk-jar)")

    sign_key = Path(
        args.sign_key
        or os.environ.get("PLATFORM_KEY_PK8")
        or (REPO_ROOT / "private/toolchain/aosp-public-platform-lab/platform.pk8")
    ).expanduser().resolve()
    sign_cert = Path(
        args.sign_cert
        or os.environ.get("PLATFORM_CERT_PEM")
        or (REPO_ROOT / "private/toolchain/aosp-public-platform-lab/platform.x509.pem")
    ).expanduser().resolve()
    if not sign_key.is_file() or not sign_cert.is_file():
        raise FileNotFoundError(
            f"Missing ROM platform signing key/cert ({sign_key}, {sign_cert}). "
            "LineageOS 18.1-wseries uses the standard AOSP platform testkey (`platform.pk8` / `platform.x509.pem`)."
        )

    # 4. Cross-compile ARM32 `pp_regconfig_provision_v6`
    prov_arm = out_dir / "pp_regconfig_provision_v6"
    run_checked(
        [
            str(zig), "cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-DQCOM32_FREESTANDING=1",
            "-target", "arm-linux-android", "-mcpu=cortex_a9", "-mfloat-abi=softfp",
            "-fPIE", "-fno-stack-protector", "-nostdlib", "-pie",
            "-I", str(prov_src),
            "-Wl,-e,_start",
            "-Wl,--dynamic-linker=/system/bin/linker",
            "-Wl,-u,passport_interpreter",
            "-Wl,--hash-style=both",
            "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
            str(prov_src / "crt_entry.c"),
            str(prov_src / "wide_diag_codec.c"),
            str(prov_src / "regfile_diag_codec.c"),
            str(prov_src / "regconfig_provision_v6.c"),
            str(libc_so),
            "-o", str(prov_arm),
        ],
        "provisioner-arm32-build",
    )

    # 5. Cross-compile ARM32 `libimsa_probe.so`
    probe_arm = out_dir / "libimsa_probe.so"
    run_checked(
        [
            str(zig), "cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-DQCOM32_FREESTANDING=1",
            "-target", "arm-linux-android", "-mcpu=cortex_a9", "-mfloat-abi=softfp",
            "-fPIE", "-fno-stack-protector", "-nostdlib", "-pie",
            "-I", str(probe_src),
            "-Wl,-e,_start",
            "-Wl,--dynamic-linker=/system/bin/linker",
            "-Wl,-u,passport_interpreter",
            "-Wl,--hash-style=both",
            "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
            str(probe_src / "crt_entry.c"),
            str(probe_src / "imsa_probe_v3.c"),
            str(probe_src / "imsa_probe_v3_main.c"),
            str(libc_so),
            str(libdl_so),
            "-o", str(probe_arm),
        ],
        "qmi-probe-arm32-build",
    )

    # 6. Build & Sign `Qcom32VolteIms.apk`
    stub_cls_dir = work_dir / "stub-classes"
    stub_cls_dir.mkdir(parents=True, exist_ok=True)
    stub_sources = sorted(str(p) for p in (ims_src / "compile_stubs").rglob("*.java"))
    boot_cp_initial = os.pathsep.join([str(sdk_jar), str(lambda_stubs)])
    run_checked(
        [
            str(javac), "-J-Duser.language=en", "-source", "8", "-target", "8",
            "-bootclasspath", boot_cp_initial, "-encoding", "UTF-8",
            "-d", str(stub_cls_dir),
            *stub_sources,
        ],
        "ims-stub-compile",
    )

    merged_sdk_jar = work_dir / "sdk-with-stubs.jar"
    build_merged_sdk_with_stubs(sdk_jar, stub_cls_dir, merged_sdk_jar)

    ims_res_zip = work_dir / "ims-res.zip"
    run_checked(
        [str(aapt2), "compile", "--dir", str(ims_src / "res"), "-o", str(ims_res_zip)],
        "ims-aapt2-compile",
    )
    ims_r_dir = work_dir / "ims-r-src"
    ims_r_dir.mkdir(parents=True, exist_ok=True)
    unsigned_apk = work_dir / "unsigned.apk"
    run_checked(
        [
            str(aapt2), "link", "-I", str(sdk_jar),
            "--manifest", str(ims_src / "AndroidManifest.xml"),
            "--java", str(ims_r_dir),
            "-o", str(unsigned_apk),
            str(ims_res_zip),
        ],
        "ims-aapt2-link",
    )

    apk_cls_dir = work_dir / "apk-classes"
    apk_cls_dir.mkdir(parents=True, exist_ok=True)
    apk_java_sources = sorted(str(p) for p in (ims_src / "src").rglob("*.java")) + sorted(
        str(p) for p in ims_r_dir.rglob("*.java")
    )
    boot_cp_merged = os.pathsep.join([str(merged_sdk_jar), str(lambda_stubs)])
    run_checked(
        [
            str(javac), "-J-Duser.language=en", "-source", "8", "-target", "8",
            "-bootclasspath", boot_cp_merged, "-encoding", "UTF-8",
            "-Xlint:all,-options,-deprecation",
            "-d", str(apk_cls_dir),
            *apk_java_sources,
        ],
        "ims-android-compile",
    )

    # Bytecode safety invariants (persistent overrideConfig + Caller ID CLIP->OIR mapping)
    cc_javap, _ = run_checked(
        [str(javap), "-c", "-p", str(apk_cls_dir / "org/octolab/qcom32ims/CarrierConfigBootstrapReceiver.class")],
        "verify-cc-bytecode",
    )
    if cc_javap.count("overrideConfig:(ILandroid/os/PersistableBundle;Z)V") != 2 or "overrideConfig:(ILandroid/os/PersistableBundle;)V" in cc_javap:
        raise RuntimeError("Bytecode invariant failed: CarrierConfigBootstrapReceiver must invoke persistent 3-arg overrideConfig exactly twice")

    call_javap, _ = run_checked(
        [
            str(javap), "-c", "-p",
            str(apk_cls_dir / "org/octolab/qcom32ims/Qcom32CallSession.class"),
            str(apk_cls_dir / "org/octolab/qcom32ims/Qcom32CallSession$CallbackBridge.class"),
        ],
        "verify-callerid-bytecode",
    )
    if "presentationToOir:(I)I" in call_javap or "getNumberOir:()I" not in call_javap or "callSessionUpdated:" not in call_javap:
        raise RuntimeError("Bytecode invariant failed: Qcom32CallSession must use getNumberOir()/getNameOir() + callSessionUpdated()")

    classes_jar = work_dir / "classes.jar"
    run_checked([str(jar_tool), "cf", str(classes_jar), "-C", str(apk_cls_dir), "."], "ims-jar")

    dex_dir = work_dir / "dex"
    dex_dir.mkdir(parents=True, exist_ok=True)
    run_checked(
        [
            str(java), "-cp", str(d8_jar), "com.android.tools.r8.D8",
            "--min-api", "30", "--lib", str(merged_sdk_jar),
            "--output", str(dex_dir), str(classes_jar),
        ],
        "ims-d8",
    )

    with zipfile.ZipFile(unsigned_apk, "a") as zf:
        zf.write(dex_dir / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
        zi = zipfile.ZipInfo("lib/armeabi-v7a/libimsa_probe.so")
        zi.compress_type = zipfile.ZIP_STORED
        zf.writestr(zi, probe_arm.read_bytes())

    aligned_apk = work_dir / "aligned.apk"
    run_checked([str(zipalign), "-f", "-p", "4", str(unsigned_apk), str(aligned_apk)], "ims-zipalign")

    final_apk = out_dir / "Qcom32VolteIms.apk"
    run_checked(
        [
            str(java), "-jar", str(apksigner_jar), "sign",
            "--key", str(sign_key),
            "--cert", str(sign_cert),
            "--out", str(final_apk),
            str(aligned_apk),
        ],
        "ims-sign",
    )
    run_checked([str(java), "-jar", str(apksigner_jar), "verify", "--verbose", str(final_apk)], "ims-verify-sig")
    perm_out, _ = run_checked([str(aapt2), "dump", "permissions", str(final_apk)], "ims-verify-perms")
    perm_lines = [ln.strip() for ln in perm_out.splitlines() if ln.strip() and not ln.strip().startswith("package:")]
    if perm_lines:
        raise RuntimeError(f"Manifest must declare zero <uses-permission> to protect com.android.phone permissions, got: {perm_lines}")
    run_checked([str(zipalign), "-c", "4", str(final_apk)], "ims-verify-align")

    # 7. Build & Sign Companion User App `Qcom32VolteManager.apk` (for Magisk / On-Device Management)
    mgr_work = work_dir / "mgr-apk"
    mgr_work.mkdir(parents=True, exist_ok=True)
    mgr_res_zip = mgr_work / "res.zip"
    run_checked(
        [str(aapt2), "compile", "--dir", str(mgr_src / "res"), "-o", str(mgr_res_zip)],
        "mgr-aapt2-compile",
    )
    mgr_r_dir = mgr_work / "r-src"
    mgr_r_dir.mkdir(parents=True, exist_ok=True)
    mgr_unsigned = mgr_work / "mgr-unsigned.apk"
    run_checked(
        [
            str(aapt2), "link", "-I", str(sdk_jar),
            "--manifest", str(mgr_src / "AndroidManifest.xml"),
            "--java", str(mgr_r_dir),
            "-o", str(mgr_unsigned),
            str(mgr_res_zip),
        ],
        "mgr-aapt2-link",
    )
    mgr_cls_dir = mgr_work / "classes"
    mgr_cls_dir.mkdir(parents=True, exist_ok=True)
    mgr_java_sources = sorted(str(p) for p in (mgr_src / "src").rglob("*.java")) + sorted(
        str(p) for p in mgr_r_dir.rglob("*.java")
    )
    run_checked(
        [
            str(javac), "-J-Duser.language=en", "-source", "8", "-target", "8",
            "-bootclasspath", boot_cp_initial, "-encoding", "UTF-8",
            "-Xlint:all,-options,-deprecation",
            "-d", str(mgr_cls_dir),
            *mgr_java_sources,
        ],
        "mgr-android-compile",
    )
    mgr_jar = mgr_work / "mgr-classes.jar"
    run_checked([str(jar_tool), "cf", str(mgr_jar), "-C", str(mgr_cls_dir), "."], "mgr-jar")
    mgr_dex = mgr_work / "dex"
    mgr_dex.mkdir(parents=True, exist_ok=True)
    run_checked(
        [
            str(java), "-cp", str(d8_jar), "com.android.tools.r8.D8",
            "--min-api", "30", "--lib", str(sdk_jar),
            "--output", str(mgr_dex), str(mgr_jar),
        ],
        "mgr-d8",
    )

    payload_manifest_bytes = (
        json.dumps(
            {
                "pp_regconfig_provision_v6": sha256_file(prov_arm),
                "libimsa_probe.so": sha256_file(probe_arm),
                "Qcom32VolteIms.apk": sha256_file(final_apk),
            },
            indent=2,
        )
        + "\n"
    ).encode("utf-8")

    with zipfile.ZipFile(mgr_unsigned, "a") as zf:
        zf.write(mgr_dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("assets/payload-manifest.json", payload_manifest_bytes, compress_type=zipfile.ZIP_DEFLATED)
        zf.write(prov_arm, "assets/pp_regconfig_provision_v6", compress_type=zipfile.ZIP_DEFLATED)
        zf.write(probe_arm, "assets/libimsa_probe.so", compress_type=zipfile.ZIP_DEFLATED)
        zf.write(final_apk, "assets/Qcom32VolteIms.apk", compress_type=zipfile.ZIP_STORED)

    mgr_aligned = mgr_work / "mgr-aligned.apk"
    run_checked([str(zipalign), "-f", "-p", "4", str(mgr_unsigned), str(mgr_aligned)], "mgr-zipalign")

    mgr_ks = REPO_ROOT / "private/toolchain/manager-app-key/manager.jks"
    if not mgr_ks.is_file():
        mgr_ks.parent.mkdir(parents=True, exist_ok=True)
        run_checked(
            [
                str(keytool), "-genkeypair",
                "-keystore", str(mgr_ks),
                "-storetype", "PKCS12",
                "-alias", "voltemanager",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-storepass", "passportvolte", "-keypass", "passportvolte",
                "-dname", "CN=Passport VoLTE Companion, OU=OctoLab, O=OctoLab, C=CN",
            ],
            "mgr-keygen",
        )

    final_mgr_apk = out_dir / "Qcom32VolteManager.apk"
    run_checked(
        [
            str(java), "-jar", str(apksigner_jar), "sign",
            "--ks", str(mgr_ks),
            "--ks-key-alias", "voltemanager",
            "--ks-pass", "pass:passportvolte",
            "--key-pass", "pass:passportvolte",
            "--out", str(final_mgr_apk),
            str(mgr_aligned),
        ],
        "mgr-sign",
    )
    run_checked([str(java), "-jar", str(apksigner_jar), "verify", "--verbose", str(final_mgr_apk)], "mgr-verify-sig")
    run_checked([str(zipalign), "-c", "4", str(final_mgr_apk)], "mgr-verify-align")

    shutil.rmtree(work_dir, ignore_errors=True)

    summary["artifacts"] = {
        "pp_regconfig_provision_v6": {
            "path": prov_arm.relative_to(REPO_ROOT).as_posix() if prov_arm.is_relative_to(REPO_ROOT) else str(prov_arm),
            "sha256": sha256_file(prov_arm),
            "bytes": prov_arm.stat().st_size,
        },
        "libimsa_probe.so": {
            "path": probe_arm.relative_to(REPO_ROOT).as_posix() if probe_arm.is_relative_to(REPO_ROOT) else str(probe_arm),
            "sha256": sha256_file(probe_arm),
            "bytes": probe_arm.stat().st_size,
        },
        "Qcom32VolteIms.apk": {
            "path": final_apk.relative_to(REPO_ROOT).as_posix() if final_apk.is_relative_to(REPO_ROOT) else str(final_apk),
            "sha256": sha256_file(final_apk),
            "bytes": final_apk.stat().st_size,
        },
        "Qcom32VolteManager.apk": {
            "path": final_mgr_apk.relative_to(REPO_ROOT).as_posix() if final_mgr_apk.is_relative_to(REPO_ROOT) else str(final_mgr_apk),
            "sha256": sha256_file(final_mgr_apk),
            "bytes": final_mgr_apk.stat().st_size,
        },
    }
    manifest_out = out_dir / "release-manifest.json"
    manifest_out.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
