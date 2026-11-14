#!/usr/bin/env python3
"""OctoLab Desktop Activator & Health Inspector GUI for the Qcom32 VoLTE & IMS Stack.

Runs identically:
  - From source (`python cli/gui.py` or `Start-GUI.cmd`)
  - From portable ZIP (`python3 cli/gui.py`)
  - From standalone Windows x64 EXE (`qcom32-volte-1.1.0-r1-windows-x64.exe`, with bundled Python/Tk/ADB/Payloads)

Supports:
  - `--self-test <report.json>` for automated offline build & packaging verification without touching any connected phone
  - `--cli <volte_ctl args...>` to invoke bundled CLI operations (`status`, `deploy`, `upgrade-apk`, `install-manager`, `rollback`) directly from the standalone EXE
  - `--live-gui-check <report.json>` to exercise the live Tkinter GUI + ADB worker thread against a connected device
"""
from __future__ import annotations

import argparse
import ctypes
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import queue
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk
import webbrowser

# Ensure sibling imports work whether invoked as `python cli/gui.py` or frozen PyInstaller EXE
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import volte_ctl  # noqa: E402

APP_VERSION = "1.1.0-r1"
WEBSITE_URL = "https://qcom32-volte.octoooo.com/"
SISTER_URL = "https://bb10-volte.octoooo.com/"
GITHUB_URL = "https://github.com/Octo-o-o-o/qcom32-volte"

PALETTE = {
    "canvas": "#f4f5f0",
    "surface": "#fcfcf8",
    "paper": "#fffdf7",
    "text": "#202923",
    "muted": "#657068",
    "line": "#d7ddd4",
    "accent": "#2e654d",
    "accent_hover": "#204c39",
    "accent_soft": "#e4ebe1",
    "on_accent": "#ffffff",
    "danger": "#984b3e",
    "warn": "#7b5510",
    "ok": "#176a42",
}

STRINGS = {
    "zh": {
        "title": f"Qcom32 VoLTE · 高通 32 位 Android 11 原生通话栈工具 v{APP_VERSION}",
        "eyebrow": "32-BIT QUALCOMM SNAPDRAGON (MSM8974) · ANDROID 11 · CLEAN-ROOM IMS",
        "headline": "高通 32 位平台 Android 11 原生 4G VoLTE 通话栈",
        "subhead": "通用 QCRIL rild_ims0 ↔ Android 11 ImsService 开源实现（首发完整验证机型 Profile：BlackBerry Passport wolverine）。免装 Magisk，先备份基带原值再写入，支持一键完整回滚。",
        "lang_btn": "English",
        "web_btn": "官方网站",
        "bb10_btn": "BB10 原生系统项目",
        "github_btn": "GitHub",
        "device_box": "设备与连接",
        "serial_lbl": "ADB 设备：",
        "refresh_devs": "刷新列表",
        "status_box": "当前检测状态",
        "state_idle": "待检测（连接手机并开启 USB 调试后点击“只读检查状态”）",
        "state_healthy": "VoLTE 栈运行正常（IMS 已注册 + LTE 语音就绪）",
        "state_unhealthy": "已连接设备，但 VoLTE 尚未完成激活或当前 SIM 尚未注册",
        "actions_box": "操作流程（所有写操作均校验机型 Profile 并先保存本地备份）",
        "btn_status": "1. 只读检查状态",
        "btn_deploy": "2. 备份并一键激活 VoLTE（推荐）",
        "btn_upgrade": "仅升级 Qcom32VolteIms 桥接 APK（零 EFS 写入）",
        "btn_manager": "安装手机端管理 App（无 su 可查状态 / Magisk 可激活）",
        "btn_rollback": "恢复出厂基带配置并卸载",
        "btn_export": "导出脱敏诊断报告",
        "btn_notices": "离线说明与开源许可",
        "log_box": "执行进度与诊断输出（自动隐去手机号/ICCID）",
        "confirm_deploy_title": "确认备份并激活 VoLTE",
        "confirm_deploy_msg": (
            "即将对连接的已验证 32 位高通机型（如 BlackBerry Passport wolverine / LineageOS 18.1）执行：\n\n"
            "1. 通过 ADB Root 读取并保存当前基带 EFS 原始状态到本地备份目录；\n"
            "2. 写入机型专属 Stage 6A 协同基带配置并执行 EFS_SYNC；\n"
            "3. 安装 Qcom32VolteIms.apk v2.4 并写入持久化 CarrierConfig；\n"
            "4. 正常重启手机并自动核验 QMI_IMSA / ImsResolver 注册状态。\n\n"
            "请确认手机已插入开通 VoLTE 的 SIM 卡且当前无正在进行的通话。是否继续？"
        ),
        "confirm_rollback_title": "确认恢复出厂基带配置",
        "confirm_rollback_msg": (
            "即将清除持久化 CarrierConfig、卸载 Qcom32VolteIms.apk，并将全部基带 EFS 条目精确回滚至出厂基线后正常重启手机。\n\n"
            "是否确认执行回滚？"
        ),
        "busy_msg": "正在执行任务，请等待当前操作完成……",
        "saved_report": "脱敏诊断报告已保存至：\n",
    },
    "en": {
        "title": f"Qcom32 VoLTE · 32-Bit Qualcomm Android 11 IMS Stack v{APP_VERSION}",
        "eyebrow": "32-BIT QUALCOMM SNAPDRAGON (MSM8974) · ANDROID 11 · CLEAN-ROOM IMS",
        "headline": "Clean-Room 4G VoLTE & IMS Stack for 32-Bit Qualcomm Platforms",
        "subhead": "Generic QCRIL rild_ims0 <-> Android 11 ImsService stack (First verified profile: BlackBerry Passport wolverine). No Magisk needed, automatic local EFS backup, and full factory rollback.",
        "lang_btn": "中文",
        "web_btn": "Website",
        "bb10_btn": "Sister Project (BB10 OS)",
        "github_btn": "GitHub",
        "device_box": "Device & USB Connection",
        "serial_lbl": "ADB Device:",
        "refresh_devs": "Refresh",
        "status_box": "Live Telemetry Summary",
        "state_idle": "Ready (Connect device with USB Debugging enabled and click 'Check Status')",
        "state_healthy": "VoLTE Stack Healthy (IMS REGISTERED + LTE Voice Ready)",
        "state_unhealthy": "Device connected, but VoLTE is not yet provisioned or registered",
        "actions_box": "Operations (All EFS writes verify device profile & save local backups first)",
        "btn_status": "1. Check Status (Read-Only)",
        "btn_deploy": "2. Backup & Activate VoLTE (Recommended)",
        "btn_upgrade": "Upgrade Qcom32VolteIms APK Only (Zero EFS Writes)",
        "btn_manager": "Install Mobile Companion App (Read-Only / Magisk)",
        "btn_rollback": "Restore Factory Modem EFS & Uninstall",
        "btn_export": "Export Redacted Report",
        "btn_notices": "Offline Guide & Notices",
        "log_box": "Progress & Redacted Telemetry Log",
        "confirm_deploy_title": "Confirm Backup & VoLTE Activation",
        "confirm_deploy_msg": (
            "This will run the guarded activation flow on the connected verified device profile (e.g. BlackBerry Passport wolverine / LineageOS 18.1):\n\n"
            "1. Enable ADB Root (UID 0) and save a pre-deployment modem EFS snapshot locally;\n"
            "2. Provision Stage 6A modem EFS items + EFS_SYNC;\n"
            "3. Install Qcom32VolteIms.apk v2.4 & apply persistent CarrierConfig overrides;\n"
            "4. Reboot normally and verify QMI_IMSA / ImsResolver registration.\n\n"
            "Ensure a VoLTE-provisioned SIM card is inserted and no call is active. Continue?"
        ),
        "confirm_rollback_title": "Confirm Factory Modem Rollback",
        "confirm_rollback_msg": (
            "This will clear CarrierConfig overrides, uninstall Qcom32VolteIms.apk, restore all managed modem EFS items to factory baseline, and reboot normally.\n\n"
            "Proceed with factory rollback?"
        ),
        "busy_msg": "Working... please keep the USB cable connected.",
        "saved_report": "Redacted diagnostic report saved to:\n",
    },
}


def enable_windows_dpi_awareness() -> None:
    if os.name != "nt":
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def locate_asset(rel_name: str) -> Path | None:
    for root in filter(None, [volte_ctl.FROZEN_ROOT, volte_ctl.REPO_ROOT]):
        cand = root / "assets" / rel_name
        if cand.is_file():
            return cand
    return None


def verify_embedded_payloads() -> dict[str, object]:
    """Verify presence and SHA-256 integrity of bundled release payloads."""
    manifest_path = None
    for sub in ("payloads/release-manifest.json", "build/release/release-manifest.json"):
        cand = volte_ctl.REPO_ROOT / sub
        if cand.is_file():
            manifest_path = cand
            break
    if manifest_path is None:
        raise FileNotFoundError("Missing release-manifest.json in payloads/ or build/release/")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    artifacts = manifest.get("artifacts", {})
    checked: dict[str, str] = {}
    for name in ("pp_regconfig_provision_v6", "libimsa_probe.so", "Qcom32VolteIms.apk", "Qcom32VolteManager.apk"):
        art_path = volte_ctl.resolve_artifact(None, name)
        actual_sha = hashlib.sha256(art_path.read_bytes()).hexdigest()
        expected_sha = artifacts.get(name, {}).get("sha256")
        if expected_sha and actual_sha != expected_sha:
            raise RuntimeError(f"SHA-256 mismatch for {name}: expected {expected_sha}, got {actual_sha}")
        checked[name] = actual_sha
    profiles = volte_ctl.load_device_profiles()
    if not profiles:
        raise RuntimeError("Missing device profiles under devices/*/profile.json")
    return {
        "manifest": str(manifest_path),
        "adb_path": volte_ctl.resolve_adb(None),
        "backup_root": str(volte_ctl.persistent_backup_root()),
        "verified_profiles": [p.get("profile_id") for p in profiles],
        "verified_artifacts": checked,
    }


class PassportVolteApp:
    def __init__(self, root: tk.Tk, auto_refresh_devices: bool = True, headless: bool = False):
        self.root = root
        self.headless = headless
        self.lang = "zh"
        self.busy = False
        self.last_result: dict[str, object] | None = None
        self.last_error: str | None = None
        self.msg_queue: queue.Queue[tuple[str, object]] = queue.Queue()

        self.root.configure(bg=PALETTE["canvas"])
        self.root.geometry("980x740")
        self.root.minsize(860, 640)

        ico_path = locate_asset("app.ico")
        png_path = locate_asset("icon-96.png") or locate_asset("icon.png")
        if os.name == "nt" and ico_path is not None:
            try:
                self.root.iconbitmap(default=str(ico_path))
            except Exception:
                pass
        self._icon_img = None
        if png_path is not None:
            try:
                self._icon_img = tk.PhotoImage(file=str(png_path))
                self.root.iconphoto(True, self._icon_img)
            except Exception:
                pass

        self._build_ui()
        self.apply_language()
        if auto_refresh_devices:
            self.refresh_devices_silent()
        self.root.after(50, self._poll_queue)

    def _t(self, key: str) -> str:
        return STRINGS[self.lang][key]

    def _build_ui(self) -> None:
        font_family = "Microsoft YaHei UI" if os.name == "nt" else "Sans"
        mono_family = "Consolas" if os.name == "nt" else "Monospace"

        # Header
        hdr = tk.Frame(self.root, bg=PALETTE["canvas"], padx=24, pady=18)
        hdr.pack(fill=tk.X)

        hdr_top = tk.Frame(hdr, bg=PALETTE["canvas"])
        hdr_top.pack(fill=tk.X)

        self.lbl_eyebrow = tk.Label(
            hdr_top, text="", bg=PALETTE["canvas"], fg=PALETTE["accent"], font=(mono_family, 9, "bold")
        )
        self.lbl_eyebrow.pack(side=tk.LEFT)

        nav_bar = tk.Frame(hdr_top, bg=PALETTE["canvas"])
        nav_bar.pack(side=tk.RIGHT)

        self.btn_lang = tk.Button(
            nav_bar, command=self.toggle_language, relief=tk.FLAT, bg=PALETTE["accent_soft"], fg=PALETTE["accent"],
            padx=10, pady=3, cursor="hand2", font=(font_family, 9, "bold")
        )
        self.btn_lang.pack(side=tk.RIGHT, padx=(6, 0))

        self.btn_git = tk.Button(
            nav_bar, command=lambda: webbrowser.open(GITHUB_URL), relief=tk.FLAT, bg=PALETTE["surface"],
            fg=PALETTE["text"], padx=10, pady=3, cursor="hand2", font=(font_family, 9)
        )
        self.btn_git.pack(side=tk.RIGHT, padx=4)

        self.btn_bb10 = tk.Button(
            nav_bar, command=lambda: webbrowser.open(SISTER_URL), relief=tk.FLAT, bg=PALETTE["surface"],
            fg=PALETTE["text"], padx=10, pady=3, cursor="hand2", font=(font_family, 9)
        )
        self.btn_bb10.pack(side=tk.RIGHT, padx=4)

        self.btn_web = tk.Button(
            nav_bar, command=lambda: webbrowser.open(WEBSITE_URL), relief=tk.FLAT, bg=PALETTE["surface"],
            fg=PALETTE["accent"], padx=10, pady=3, cursor="hand2", font=(font_family, 9, "bold")
        )
        self.btn_web.pack(side=tk.RIGHT, padx=4)

        self.lbl_headline = tk.Label(
            hdr, text="", bg=PALETTE["canvas"], fg=PALETTE["text"], font=(font_family, 18, "bold"), anchor="w"
        )
        self.lbl_headline.pack(fill=tk.X, pady=(8, 4))

        self.lbl_subhead = tk.Label(
            hdr, text="", bg=PALETTE["canvas"], fg=PALETTE["muted"], font=(font_family, 10), anchor="w", justify=tk.LEFT
        )
        self.lbl_subhead.pack(fill=tk.X)

        sep = tk.Frame(self.root, bg=PALETTE["line"], height=1)
        sep.pack(fill=tk.X, padx=24)

        # Device & Status Row
        mid = tk.Frame(self.root, bg=PALETTE["canvas"], padx=24, pady=14)
        mid.pack(fill=tk.X)

        dev_card = tk.Frame(mid, bg=PALETTE["surface"], highlightbackground=PALETTE["line"], highlightthickness=1, padx=14, pady=12)
        dev_card.pack(fill=tk.X, pady=(0, 10))

        self.lbl_serial = tk.Label(dev_card, text="", bg=PALETTE["surface"], fg=PALETTE["text"], font=(font_family, 10, "bold"))
        self.lbl_serial.pack(side=tk.LEFT)

        self.serial_var = tk.StringVar(value="")
        self.cmb_serial = ttk.Combobox(dev_card, textvariable=self.serial_var, width=32, state="normal")
        self.cmb_serial.pack(side=tk.LEFT, padx=10)

        self.btn_refresh = tk.Button(
            dev_card, command=self.refresh_devices_silent, relief=tk.SOLID, bd=1,
            bg=PALETTE["canvas"], fg=PALETTE["text"], padx=12, pady=3, cursor="hand2", font=(font_family, 9)
        )
        self.btn_refresh.pack(side=tk.LEFT)

        self.lbl_status_banner = tk.Label(
            dev_card, text="", bg=PALETTE["accent_soft"], fg=PALETTE["accent"],
            padx=12, pady=5, font=(font_family, 9, "bold"), anchor="w"
        )
        self.lbl_status_banner.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=(16, 0))

        # Action Buttons Grid
        act_card = tk.Frame(mid, bg=PALETTE["surface"], highlightbackground=PALETTE["line"], highlightthickness=1, padx=14, pady=12)
        act_card.pack(fill=tk.X)

        self.lbl_actions = tk.Label(
            act_card, text="", bg=PALETTE["surface"], fg=PALETTE["muted"], font=(font_family, 9, "bold"), anchor="w"
        )
        self.lbl_actions.pack(fill=tk.X, pady=(0, 8))

        row1 = tk.Frame(act_card, bg=PALETTE["surface"])
        row1.pack(fill=tk.X, pady=(0, 8))

        self.btn_status = tk.Button(
            row1, command=self.on_status, relief=tk.FLAT, bg=PALETTE["accent_soft"], fg=PALETTE["accent"],
            padx=16, pady=9, cursor="hand2", font=(font_family, 10, "bold")
        )
        self.btn_status.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_deploy = tk.Button(
            row1, command=self.on_deploy, relief=tk.FLAT, bg=PALETTE["accent"], fg=PALETTE["on_accent"],
            activebackground=PALETTE["accent_hover"], activeforeground=PALETTE["on_accent"],
            padx=18, pady=9, cursor="hand2", font=(font_family, 10, "bold")
        )
        self.btn_deploy.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_upgrade = tk.Button(
            row1, command=self.on_upgrade_apk, relief=tk.SOLID, bd=1, bg=PALETTE["canvas"], fg=PALETTE["text"],
            padx=14, pady=8, cursor="hand2", font=(font_family, 9)
        )
        self.btn_upgrade.pack(side=tk.LEFT, padx=(0, 8))

        row2 = tk.Frame(act_card, bg=PALETTE["surface"])
        row2.pack(fill=tk.X)

        self.btn_manager = tk.Button(
            row2, command=self.on_install_manager, relief=tk.SOLID, bd=1, bg=PALETTE["canvas"], fg=PALETTE["text"],
            padx=14, pady=7, cursor="hand2", font=(font_family, 9)
        )
        self.btn_manager.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_rollback = tk.Button(
            row2, command=self.on_rollback, relief=tk.FLAT, bg="#fceaea", fg=PALETTE["danger"],
            padx=14, pady=7, cursor="hand2", font=(font_family, 9, "bold")
        )
        self.btn_rollback.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_notices = tk.Button(
            row2, command=self.on_show_notices, relief=tk.SOLID, bd=1, bg=PALETTE["canvas"], fg=PALETTE["muted"],
            padx=12, pady=7, cursor="hand2", font=(font_family, 9)
        )
        self.btn_notices.pack(side=tk.RIGHT)

        self.btn_export = tk.Button(
            row2, command=self.on_export_report, relief=tk.SOLID, bd=1, bg=PALETTE["canvas"], fg=PALETTE["text"],
            padx=12, pady=7, cursor="hand2", font=(font_family, 9)
        )
        self.btn_export.pack(side=tk.RIGHT, padx=(0, 8))

        # Log Panel
        log_wrap = tk.Frame(self.root, bg=PALETTE["canvas"], padx=24, pady=4)
        log_wrap.pack(fill=tk.BOTH, expand=True, pady=(0, 18))

        self.lbl_log = tk.Label(
            log_wrap, text="", bg=PALETTE["canvas"], fg=PALETTE["muted"], font=(font_family, 9, "bold"), anchor="w"
        )
        self.lbl_log.pack(fill=tk.X, pady=(0, 6))

        self.txt_log = scrolledtext.ScrolledText(
            log_wrap, bg=PALETTE["paper"], fg=PALETTE["text"], insertbackground=PALETTE["text"],
            relief=tk.SOLID, bd=1, font=(mono_family, 9), wrap=tk.WORD
        )
        self.txt_log.pack(fill=tk.BOTH, expand=True)
        self._append_log(
            f"[Passport VoLTE v{APP_VERSION}] Backup directory: {volte_ctl.persistent_backup_root()}"
        )

    def toggle_language(self) -> None:
        self.lang = "en" if self.lang == "zh" else "zh"
        self.apply_language()

    def apply_language(self) -> None:
        self.root.title(self._t("title"))
        self.lbl_eyebrow.configure(text=self._t("eyebrow"))
        self.lbl_headline.configure(text=self._t("headline"))
        self.lbl_subhead.configure(text=self._t("subhead"))
        self.btn_lang.configure(text=self._t("lang_btn"))
        self.btn_web.configure(text=self._t("web_btn"))
        self.btn_bb10.configure(text=self._t("bb10_btn"))
        self.btn_git.configure(text=self._t("github_btn"))
        self.lbl_serial.configure(text=self._t("serial_lbl"))
        self.btn_refresh.configure(text=self._t("refresh_devs"))
        self.lbl_actions.configure(text=self._t("actions_box"))
        self.btn_status.configure(text=self._t("btn_status"))
        self.btn_deploy.configure(text=self._t("btn_deploy"))
        self.btn_upgrade.configure(text=self._t("btn_upgrade"))
        self.btn_manager.configure(text=self._t("btn_manager"))
        self.btn_rollback.configure(text=self._t("btn_rollback"))
        self.btn_export.configure(text=self._t("btn_export"))
        self.btn_notices.configure(text=self._t("btn_notices"))
        self.lbl_log.configure(text=self._t("log_box"))
        if self.last_result is None and not self.busy:
            self.lbl_status_banner.configure(text=self._t("state_idle"), bg=PALETTE["accent_soft"], fg=PALETTE["accent"])

    def _selected_serial(self) -> str | None:
        raw = self.serial_var.get().strip()
        if not raw:
            return None
        return raw.split()[0]

    def refresh_devices_silent(self) -> None:
        try:
            adb = volte_ctl.AdbClient(volte_ctl.resolve_adb(None))
            devs = adb.list_devices()
            items = [f"{serial} ({state})" for serial, state in devs]
            self.cmb_serial["values"] = items
            if items and not self.serial_var.get().strip():
                self.serial_var.set(items[0])
        except Exception as exc:
            self._append_log(f"[ADB] {exc}")

    def _set_busy(self, flag: bool) -> None:
        self.busy = flag
        state = tk.DISABLED if flag else tk.NORMAL
        for btn in (self.btn_status, self.btn_deploy, self.btn_upgrade, self.btn_manager, self.btn_rollback):
            btn.configure(state=state)
        if flag:
            self.lbl_status_banner.configure(text=self._t("busy_msg"), bg="#fff3cf", fg=PALETTE["warn"])

    def _append_log(self, line: str) -> None:
        stamp = dt.datetime.now().strftime("%H:%M:%S")
        clean = volte_ctl.sanitize_text(line)
        self.txt_log.insert(tk.END, f"[{stamp}] {clean}\n")
        self.txt_log.see(tk.END)

    def _poll_queue(self) -> None:
        while True:
            try:
                kind, payload = self.msg_queue.get_nowait()
            except queue.Empty:
                break
            if kind == "progress":
                self._append_log(str(payload))
            elif kind == "done":
                self._set_busy(False)
                res = payload if isinstance(payload, dict) else {"result": payload}
                self.last_result = res
                self._append_log(json.dumps(res, indent=2, ensure_ascii=False))
                if res.get("volte_stack_healthy") is True:
                    self.lbl_status_banner.configure(
                        text=self._t("state_healthy"), bg="#e4f5eb", fg=PALETTE["ok"]
                    )
                elif "volte_stack_healthy" in res:
                    self.lbl_status_banner.configure(
                        text=self._t("state_unhealthy"), bg="#fff3cf", fg=PALETTE["warn"]
                    )
                else:
                    self.lbl_status_banner.configure(
                        text="OK", bg="#e4f5eb", fg=PALETTE["ok"]
                    )
            elif kind == "error":
                self._set_busy(False)
                err_txt = volte_ctl.sanitize_text(str(payload))
                self.last_error = err_txt
                self._append_log(f"ERROR: {err_txt}")
                self.lbl_status_banner.configure(text=f"Error: {err_txt[:72]}", bg="#fceaea", fg=PALETTE["danger"])
                if not self.headless:
                    messagebox.showerror("Qcom32 VoLTE", err_txt)
        self.root.after(50, self._poll_queue)

    def _run_bg(self, worker) -> None:
        if self.busy:
            return
        selected_serial = self._selected_serial()
        self._set_busy(True)

        def _target() -> None:
            try:
                adb = volte_ctl.AdbClient(volte_ctl.resolve_adb(None), selected_serial)
                out = worker(adb, lambda msg: self.msg_queue.put(("progress", msg)))
                self.msg_queue.put(("done", out))
            except Exception as exc:
                self.msg_queue.put(("error", exc))

        threading.Thread(target=_target, daemon=True).start()

    def on_status(self) -> None:
        self._run_bg(lambda adb, prog: (prog("Querying Qcom32 VoLTE / QMI_IMSA / ImsResolver state..."), volte_ctl.query_live_volte_status(adb))[1])

    def on_deploy(self) -> None:
        if not messagebox.askyesno(self._t("confirm_deploy_title"), self._t("confirm_deploy_msg")):
            return
        apk = volte_ctl.resolve_artifact(None, "Qcom32VolteIms.apk")
        prov = volte_ctl.resolve_artifact(None, "pp_regconfig_provision_v6", "build/regconfig-provision-v6/*/pp_regconfig_provision_v6")
        self._run_bg(lambda adb, prog: volte_ctl.run_deploy_flow(adb, apk, prov, on_progress=prog))

    def on_upgrade_apk(self) -> None:
        apk = volte_ctl.resolve_artifact(None, "Qcom32VolteIms.apk")
        self._run_bg(lambda adb, prog: volte_ctl.run_upgrade_apk_flow(adb, apk, do_reboot=False, on_progress=prog))

    def on_install_manager(self) -> None:
        mgr = volte_ctl.resolve_artifact(None, "Qcom32VolteManager.apk")
        self._run_bg(lambda adb, prog: volte_ctl.run_install_manager_flow(adb, mgr, on_progress=prog))

    def on_rollback(self) -> None:
        if not messagebox.askyesno(self._t("confirm_rollback_title"), self._t("confirm_rollback_msg")):
            return
        prov = volte_ctl.resolve_artifact(None, "pp_regconfig_provision_v6", "build/regconfig-provision-v6/*/pp_regconfig_provision_v6")
        self._run_bg(lambda adb, prog: volte_ctl.run_rollback_flow(adb, prov, on_progress=prog))

    def on_export_report(self) -> None:
        content = volte_ctl.sanitize_text(self.txt_log.get("1.0", tk.END))
        default_name = f"qcom32-volte-report-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.txt"
        target = filedialog.asksaveasfilename(
            defaultextension=".txt",
            initialfile=default_name,
            filetypes=[("Text files", "*.txt"), ("All files", "*.*")],
        )
        if target:
            Path(target).write_text(content, encoding="utf-8")
            messagebox.showinfo("Qcom32 VoLTE", self._t("saved_report") + target)

    def on_show_notices(self) -> None:
        notices_text = (
            f"Qcom32 VoLTE & IMS Stack v{APP_VERSION}\n"
            f"Website: {WEBSITE_URL}\n"
            f"GitHub:  {GITHUB_URL}\n"
            f"Sister Project (BB10 OS 10.3.3): {SISTER_URL}\n\n"
            "Quick Steps (No Magisk Needed — Verified Profile: BlackBerry Passport wolverine):\n"
            "  1. Insert a VoLTE-enabled SIM card into your LineageOS 18.1 (Android 11) device.\n"
            "  2. Open Settings -> System -> Developer options:\n"
            "       - Enable 'Android debugging' (USB debugging)\n"
            "       - Set 'Rooted debugging' ON\n"
            "  3. Connect via USB, accept the RSA prompt on the phone, and click:\n"
            "       [1. Check Status] -> [2. Backup & Activate VoLTE].\n"
            "  4. After automatic reboot, verify native incoming & outgoing 4G calls.\n\n"
            "License: Apache License 2.0.\n"
        )
        for cand_name in ("THIRD-PARTY-NOTICES.txt", "NOTICE.md"):
            cand = volte_ctl.REPO_ROOT / cand_name
            if cand.is_file():
                notices_text += "\n" + ("=" * 64) + "\n" + cand.read_text(encoding="utf-8", errors="replace")
                break
        win = tk.Toplevel(self.root)
        win.title(self._t("btn_notices"))
        win.geometry("760x540")
        txt = scrolledtext.ScrolledText(win, wrap=tk.WORD, font=("Consolas", 9), bg=PALETTE["paper"], fg=PALETTE["text"])
        txt.pack(fill=tk.BOTH, expand=True, padx=12, pady=12)
        txt.insert("1.0", notices_text)
        txt.configure(state=tk.DISABLED)


def run_self_test(out_json: str) -> int:
    """Offline self-test for CI and PyInstaller Windows EXE validation (never touches connected devices)."""
    enable_windows_dpi_awareness()
    root = tk.Tk()
    root.withdraw()
    app = PassportVolteApp(root, auto_refresh_devices=False, headless=True)
    app.toggle_language()
    app.toggle_language()
    root.update_idletasks()
    payload_info = verify_embedded_payloads()
    ctl_rc = volte_ctl.cmd_self_test()
    if ctl_rc != 0:
        raise RuntimeError(f"volte_ctl self-test returned {ctl_rc}")
    root.destroy()

    report = {
        "self_test": "PASS",
        "app_version": APP_VERSION,
        "frozen": bool(volte_ctl.FROZEN_ROOT is not None),
        **payload_info,
    }
    out_path = Path(out_json).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if sys.stdout is not None:
        print(json.dumps(report, indent=2))
    return 0


def run_live_gui_check(out_json: str, serial: str | None = None) -> int:
    """Exercise the real Tkinter GUI + live ADB status worker against a connected Passport."""
    import time

    enable_windows_dpi_awareness()
    root = tk.Tk()
    root.withdraw()
    app = PassportVolteApp(root, auto_refresh_devices=True, headless=True)
    if serial:
        app.serial_var.set(serial)
    root.update()
    app.on_status()
    deadline = time.time() + 45.0
    while time.time() < deadline:
        root.update()
        if not app.busy and (app.last_result is not None or app.last_error is not None):
            break
        time.sleep(0.05)

    banner_text = str(app.lbl_status_banner.cget("text"))
    healthy = bool(app.last_result and app.last_result.get("volte_stack_healthy") is True)
    selected_serial = app._selected_serial()
    last_err = app.last_error
    root.destroy()

    report = {
        "live_gui_check": "PASS" if healthy else "UNHEALTHY",
        "app_version": APP_VERSION,
        "website_url": WEBSITE_URL,
        "frozen": bool(volte_ctl.FROZEN_ROOT is not None),
        "selected_serial": selected_serial,
        "banner_text": banner_text,
        "volte_stack_healthy": healthy,
        "last_error": last_err,
        "status_result": app.last_result,
    }
    out_path = Path(out_json).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    rendered = volte_ctl.sanitize_text(json.dumps(report, indent=2, ensure_ascii=False))
    out_path.write_text(rendered + "\n", encoding="utf-8")
    if sys.stdout is not None:
        print(rendered)
    return 0 if healthy else 2


def main() -> int:
    if len(sys.argv) >= 2 and sys.argv[1] == "--cli":
        try:
            return volte_ctl.main(sys.argv[2:])
        except Exception as exc:
            if sys.stderr is not None:
                sys.stderr.write(f"ERROR: {exc}\n")
            return 1

    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--self-test", default=None, help="Run offline GUI + payload integrity self-test and write JSON report")
    ap.add_argument("--live-gui-check", default=None, help="Run live Tkinter GUI ADB status check and write JSON report")
    ap.add_argument("--serial", default=None, help="Optional ADB serial for --live-gui-check")
    args, _ = ap.parse_known_args()
    if args.self_test:
        try:
            return run_self_test(args.self_test)
        except Exception as exc:
            err_path = Path(args.self_test).expanduser().resolve()
            err_path.parent.mkdir(parents=True, exist_ok=True)
            err_path.write_text(json.dumps({"self_test": "FAIL", "error": str(exc)}, indent=2) + "\n", encoding="utf-8")
            if sys.stderr is not None:
                sys.stderr.write(f"ERROR: {exc}\n")
            return 1
    if args.live_gui_check:
        try:
            return run_live_gui_check(args.live_gui_check, args.serial)
        except Exception as exc:
            err_path = Path(args.live_gui_check).expanduser().resolve()
            err_path.parent.mkdir(parents=True, exist_ok=True)
            err_path.write_text(json.dumps({"live_gui_check": "FAIL", "error": str(exc)}, indent=2) + "\n", encoding="utf-8")
            if sys.stderr is not None:
                sys.stderr.write(f"ERROR: {exc}\n")
            return 1

    enable_windows_dpi_awareness()
    root = tk.Tk()
    PassportVolteApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
