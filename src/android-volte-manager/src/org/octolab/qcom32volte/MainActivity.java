package org.octolab.qcom32volte;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Companion on-device status & Magisk Root manager UI for the Qcom32 VoLTE Android 11 IMS Stack.
 * First verified modem EFS profile: BlackBerry Passport (wolverine / MSM8974 / LineageOS 18.1).
 */
public final class MainActivity extends Activity {
    private static final int COLOR_CANVAS = Color.parseColor("#F4F5F0");
    private static final int COLOR_SURFACE = Color.parseColor("#FCFCF8");
    private static final int COLOR_TEXT = Color.parseColor("#202923");
    private static final int COLOR_MUTED = Color.parseColor("#657068");
    private static final int COLOR_LINE = Color.parseColor("#D7DDD4");
    private static final int COLOR_ACCENT = Color.parseColor("#2E654D");
    private static final int COLOR_DANGER = Color.parseColor("#984B3E");

    private static final String[] BUNDLED_ASSET_NAMES = new String[]{
            "pp_regconfig_provision_v6", "libimsa_probe.so", "Qcom32VolteIms.apk"
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean englishMode = false;
    private boolean busy = false;

    private TextView titleView;
    private TextView subtitleView;
    private Button langButton;
    private TextView statusBadgeView;
    private TextView statusDetailsView;
    private TextView logView;
    private TextView licenseNoteView;
    private Button refreshButton;
    private Button readinessButton;
    private Button deployButton;
    private Button upgradeButton;
    private Button rollbackButton;
    private Button copyButton;
    private String lastSanitizedReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        englishMode = !Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
        buildResponsiveUi();
        refreshLabels();
        runCheckStatus();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable roundedBox(int fill, int stroke, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private Button createActionButton(String label, boolean primary, boolean danger) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int padV = dp(12);
        int padH = dp(16);
        b.setPadding(padH, padV, padH, padV);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackground(roundedBox(COLOR_ACCENT, COLOR_ACCENT, 4));
        } else if (danger) {
            b.setTextColor(COLOR_DANGER);
            b.setBackground(roundedBox(COLOR_SURFACE, COLOR_DANGER, 4));
        } else {
            b.setTextColor(COLOR_TEXT);
            b.setBackground(roundedBox(COLOR_SURFACE, COLOR_LINE, 4));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);
        b.setText(label);
        return b;
    }

    private void buildResponsiveUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_CANVAS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCol.setLayoutParams(titleLp);

        titleView = new TextView(this);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(titleView);

        subtitleView = new TextView(this);
        subtitleView.setTextColor(COLOR_MUTED);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(subtitleView);

        langButton = new Button(this);
        langButton.setAllCaps(false);
        langButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        langButton.setTextColor(COLOR_ACCENT);
        langButton.setBackground(roundedBox(COLOR_SURFACE, COLOR_LINE, 4));
        langButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        langButton.setOnClickListener(v -> {
            englishMode = !englishMode;
            refreshLabels();
            runCheckStatus();
        });

        header.addView(titleCol);
        header.addView(langButton);
        root.addView(header);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBox(COLOR_SURFACE, COLOR_LINE, 6));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        cardLp.bottomMargin = dp(14);
        card.setLayoutParams(cardLp);

        statusBadgeView = new TextView(this);
        statusBadgeView.setTextColor(COLOR_ACCENT);
        statusBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusBadgeView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(statusBadgeView);

        statusDetailsView = new TextView(this);
        statusDetailsView.setTextColor(COLOR_TEXT);
        statusDetailsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusDetailsView.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detLp.topMargin = dp(8);
        statusDetailsView.setLayoutParams(detLp);
        card.addView(statusDetailsView);
        root.addView(card);

        refreshButton = createActionButton("", false, false);
        refreshButton.setOnClickListener(v -> runCheckStatus());
        root.addView(refreshButton);

        readinessButton = createActionButton("", false, false);
        readinessButton.setOnClickListener(v -> runReadOnlyPortingCheck());
        root.addView(readinessButton);

        deployButton = createActionButton("", true, false);
        deployButton.setOnClickListener(v -> confirmAndRunDeploy());
        root.addView(deployButton);

        upgradeButton = createActionButton("", false, false);
        upgradeButton.setOnClickListener(v -> confirmAndRunUpgrade());
        root.addView(upgradeButton);

        rollbackButton = createActionButton("", false, true);
        rollbackButton.setOnClickListener(v -> confirmAndRunRollback());
        root.addView(rollbackButton);

        copyButton = createActionButton("", false, false);
        copyButton.setOnClickListener(v -> copyReportToClipboard());
        root.addView(copyButton);

        logView = new TextView(this);
        logView.setTextColor(COLOR_MUTED);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(roundedBox(COLOR_SURFACE, COLOR_LINE, 6));
        logView.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(logView);

        licenseNoteView = new TextView(this);
        licenseNoteView.setTextColor(COLOR_MUTED);
        licenseNoteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams licLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        licLp.topMargin = dp(10);
        licenseNoteView.setLayoutParams(licLp);
        root.addView(licenseNoteView);

        scroll.addView(root);
        setContentView(scroll);
        setBusy(false);
    }

    private void refreshLabels() {
        if (englishMode) {
            titleView.setText("Qcom32 VoLTE & IMS Stack");
            subtitleView.setText("OCTOLAB · Android 11 · IMS v2.10 · one wolverine unit verified");
            langButton.setText("中文");
            refreshButton.setText("Check Live VoLTE & SIM Status");
            readinessButton.setText("32-Bit Device Identification & AI Porting Check (Read-Only)");
            deployButton.setText("Activate VoLTE (wolverine · Root)");
            upgradeButton.setText("Update Qcom32VolteIms Bridge APK Only (Zero EFS Write)");
            rollbackButton.setText("Run Fixed EFS Cleanup & Uninstall");
            copyButton.setText("Copy Redacted Diagnostic / Porting Report");
            licenseNoteView.setText("Source-available: PolyForm Noncommercial 1.0.0; commercial license available: octoooo@octoooo.com");
        } else {
            titleView.setText("Qcom32 VoLTE 通话栈管理端");
            subtitleView.setText("OCTOLAB · Android 11 · IMS v2.10 · 仅一台 wolverine 已验证");
            langButton.setText("English");
            refreshButton.setText("检查当前 SIM / 4G / VoLTE 状态");
            readinessButton.setText("多机型识别与只读适配体检（AI + GitHub 自助适配）");
            deployButton.setText("激活 VoLTE（wolverine · 需 Root）");
            upgradeButton.setText("仅原位更新 Qcom32VolteIms 桥接 APK（零 EFS 写入）");
            rollbackButton.setText("执行固定基带清理并卸载桥接服务");
            copyButton.setText("复制脱敏诊断 / 适配体检摘要");
            licenseNoteView.setText("源码可见：PolyForm Noncommercial 1.0.0 + 商业授权：octoooo@octoooo.com");
        }
    }

    private void setBusy(boolean isBusy) {
        busy = isBusy;
        refreshButton.setEnabled(!isBusy);
        readinessButton.setEnabled(!isBusy);
        deployButton.setEnabled(!isBusy && VolteManagerLogic.isRootOperationAllowed("deploy", Build.MODEL, Build.DEVICE, Build.VERSION.SDK_INT));
        upgradeButton.setEnabled(!isBusy && VolteManagerLogic.isRootOperationAllowed("upgrade", Build.MODEL, Build.DEVICE, Build.VERSION.SDK_INT));
        rollbackButton.setEnabled(!isBusy);
        copyButton.setEnabled(!isBusy);
    }

    private void runCheckStatus() {
        if (busy) return;
        setBusy(true);
        statusBadgeView.setText(englishMode ? "Checking device & IMS state..." : "正在读取设备与 IMS 状态…");
        final boolean stageForTest = getIntent() != null
                && getIntent().getBooleanExtra("stage_assets_for_test", false);
        worker.execute(() -> {
            String model = Build.MODEL;
            String device = Build.DEVICE;
            int sdk = Build.VERSION.SDK_INT;
            String simNum = readLocalSimOperator();
            String netType = readSystemProp("gsm.network.type");
            String simStateProp = readSystemProp("gsm.sim.state");
            String basebandProp = readSystemProp("gsm.version.baseband");
            boolean airplaneMode = false;
            try {
                airplaneMode = android.provider.Settings.Global.getInt(
                        getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
            } catch (Exception ignored) {}

            boolean imsInstalled = false;
            String imsVer = null;
            for (String pkgName : new String[]{VolteManagerLogic.IMS_PKG, VolteManagerLogic.LEGACY_IMS_PKG}) {
                try {
                    PackageInfo pi = getPackageManager().getPackageInfo(pkgName, 0);
                    imsInstalled = true;
                    imsVer = pi.versionName;
                    break;
                } catch (PackageManager.NameNotFoundException ignored) {}
            }

            boolean assetsVerified = false;
            String stagedTestNote = "";
            try {
                if (stageForTest) {
                    File stagedDir = stageVerifiedAssets();
                    assetsVerified = stagedDir.isDirectory();
                    stagedTestNote = "STAGED_PAYLOADS_OK=" + assetsVerified + "\n";
                } else {
                    assetsVerified = verifyBundledAssetsOnly();
                }
            } catch (Exception e) {
                stagedTestNote = "ASSET_VERIFY_ERR=" + e.getClass().getSimpleName() + "\n";
            }

            boolean suOk = checkSuAvailable();
            String svcDump = imsInstalled ? "package=" + VolteManagerLogic.IMS_PKG + "\nversion=" + imsVer : "";
            String qmiOut = "";
            String phoneDump = "";

            String notifyKind = "Auto (Boot / SIM Swap)";
            Bundle providerStatus = queryReadOnlyStatusProvider();
            if (providerStatus != null) {
                String nk = providerStatus.getString("notification_kind", "");
                if (!nk.isEmpty() && !"NONE".equals(nk)) {
                    notifyKind = nk;
                }
                imsInstalled = true;
                String pv = providerStatus.getString("version");
                if (pv != null && !pv.isEmpty()) {
                    imsVer = pv;
                }
                String pSim = providerStatus.getString("sim_operator", "");
                if (!pSim.isEmpty()) {
                    simNum = pSim;
                }
                String pNet = providerStatus.getString("network_type", "");
                if (!pNet.isEmpty()) {
                    netType = pNet;
                }
                String pSimSt = providerStatus.getString("sim_state", "");
                if (simStateProp.isEmpty() && !pSimSt.isEmpty()) {
                    simStateProp = pSimSt;
                }
                String pBb = providerStatus.getString("baseband", "");
                if (basebandProp.isEmpty() && !pBb.isEmpty()) {
                    basebandProp = pBb;
                }
                boolean rilConn = providerStatus.getBoolean("ril_connected", false);
                boolean voiceReady = providerStatus.getBoolean("voice_ims_ready", false);
                boolean featReady = providerStatus.getBoolean("feature_ready", false);
                int pubReg = providerStatus.getInt("published_framework_reg", -1);
                svcDump = "QCOM32_VOLTE_IMS_V1\n"
                        + "version=" + (imsVer != null ? imsVer : "unknown") + "\n"
                        + "rilConnected=" + rilConn + "\n"
                        + "voiceImsReady=" + voiceReady + "\n"
                        + "featureReadyCalled=" + featReady + "\n"
                        + "publishedFrameworkReg=" + pubReg;
                qmiOut = "pdp_connected=" + providerStatus.getInt("pdp_connected", -1)
                        + " reg_status=" + providerStatus.getInt("reg_status", -1)
                        + " legacy_reg=" + providerStatus.getInt("legacy_reg", -1)
                        + " voip=" + providerStatus.getInt("voip", -1)
                        + " voip_rat=" + providerStatus.getInt("voip_rat", -1)
                        + " test_mode=" + providerStatus.getInt("test_mode", -1);
                if (pubReg == 1) {
                    phoneDump = "mImsRegistered=true";
                }
            } else if (suOk) {
                svcDump = execRoot("dumpsys activity service org.octolab.qcom32ims/.Qcom32VolteImsService --qmi-probe || true")
                        + "\n" + svcDump;
                phoneDump = execRoot("dumpsys activity service com.android.phone/.TelephonyDebugService | grep -E 'mImsRegistered=' | head -n 4 || true");
                try {
                    File staged = stageVerifiedAssets();
                    String probePath = new File(staged, "libimsa_probe.so").getAbsolutePath();
                    qmiOut = execRoot("cp " + VolteManagerLogic.quoteSh(probePath) + " /data/local/tmp/pp_imsa_probe && chmod 0700 /data/local/tmp/pp_imsa_probe && /data/local/tmp/pp_imsa_probe --probe-all; rm -f /data/local/tmp/pp_imsa_probe");
                } catch (Exception e) {
                    qmiOut = "probe_error=" + e.getMessage();
                }
            }

            VolteManagerLogic.StatusSnapshot snap = VolteManagerLogic.parseStatusSummary(
                    model, device, sdk, suOk, svcDump, qmiOut, phoneDump, simNum, netType, simStateProp, airplaneMode);
            String boardPlatform = readSystemProp("ro.board.platform");
            String cpuAbi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                    ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
            VolteManagerLogic.DeviceCatalogMatch catMatch = VolteManagerLogic.identifyDeviceCatalog(
                    Build.BRAND, model, device, boardPlatform, cpuAbi, sdk, Build.TAGS);
            String radioHint = "";
            if (VolteManagerLogic.isPassportFamilyIdentity(model, device, sdk)
                    && !airplaneMode && "LOADED".equals(simStateProp) && basebandProp.isEmpty()
                    && (netType.isEmpty() || "UNKNOWN".equalsIgnoreCase(netType))) {
                radioHint = englishMode
                        ? "SIM loaded; runtime baseband missing. Use the desktop read-only radio check.\n"
                        : "SIM 已加载，运行时基带版本为空。请用桌面只读体检确认蜂窝状态。\n";
            }

            String details = "Model / Device : " + model + " (" + device + ", SDK " + sdk + ")\n"
                    + "Catalog Family : " + (englishMode ? catMatch.displayNameEn : catMatch.displayNameZh) + "\n"
                    + "Device Profile : " + snap.matchedProfileId + "\n"
                    + "Baseband / SIM : " + (basebandProp.isEmpty() ? "unknown" : basebandProp)
                    + " | sim_state=" + (simStateProp.isEmpty() ? "<empty>" : simStateProp) + "\n"
                    + "SIM / Network  : " + snap.carrierSummary + "\n"
                    + radioHint
                    + "Local Root (su): " + (snap.suAvailable ? "YES (UID 0)" : "NO (Use PC ADB Root tool)") + "\n"
                    + "Bundled Assets : " + (assetsVerified ? "Verified (3 payloads SHA-256 OK)" : "Unverified") + "\n"
                    + "ImsService APK : " + (snap.imsServicePresent ? ("Installed (" + snap.imsServiceVersion + ")") : "Not installed") + "\n"
                    + "rild_ims0 Link : " + (snap.telemetryAvailable ? String.valueOf(snap.rilConnected) : "Requires su / PC") + "\n"
                    + "QMI_IMSA VoIP  : " + (snap.telemetryAvailable ? String.valueOf(snap.qmiRegistered) : "Requires su / PC") + "\n"
                    + "Framework IMS  : " + (snap.telemetryAvailable ? String.valueOf(snap.frameworkRegistered) : "Requires su / PC") + "\n"
                    + "System Alerts  : " + notifyKind;

            String rawLog = "[Sanitized Diagnostic Log]\n"
                    + details + "\n"
                    + stagedTestNote
                    + (!qmiOut.isEmpty() ? ("QMI: " + qmiOut.trim() + "\n") : "")
                    + (!phoneDump.isEmpty() ? ("Phone: " + phoneDump.trim() + "\n") : "");
            String cleanLog = VolteManagerLogic.redactSensitiveDigits(rawLog);

            runOnUiThread(() -> {
                statusBadgeView.setText(VolteManagerLogic.isExperimentalPassportEdition(model, device, sdk)
                        ? "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证 · Read-only / 只读"
                            + (snap.radioUnavailable ? "\n" + (englishMode ? snap.headlineEn : snap.headlineZh) : "")
                        : (englishMode ? snap.headlineEn : snap.headlineZh));
                statusBadgeView.setTextColor(snap.radioUnavailable ? COLOR_DANGER
                        : (snap.volteHealthy && !VolteManagerLogic.isExperimentalPassportEdition(model, device, sdk) ? COLOR_ACCENT : COLOR_TEXT));
                statusDetailsView.setText(details);
                logView.setText(cleanLog);
                lastSanitizedReport = cleanLog;
                setBusy(false);
            });
        });
    }

    private void runReadOnlyPortingCheck() {
        if (busy) return;
        setBusy(true);
        statusBadgeView.setText(englishMode
                ? "Running 100% read-only 4-Gate readiness check..."
                : "正在执行 100% 只读四门禁适配体检（零基带写入）…");
        worker.execute(() -> {
            String brand = Build.BRAND;
            String model = Build.MODEL;
            String device = Build.DEVICE;
            int sdk = Build.VERSION.SDK_INT;
            String boardPlatform = readSystemProp("ro.board.platform");
            String cpuAbi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                    ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
            String selinux = readSelinuxMode();
            boolean suOk = checkSuAvailable();
            boolean rildPresent = new File("/dev/socket/qmux_radio/rild_ims0").exists()
                    || (suOk && execRootQuiet("test -S /dev/socket/qmux_radio/rild_ims0 && echo YES || echo NO").contains("YES"));
            boolean diagPresent = new File("/dev/diag").exists()
                    || (suOk && execRootQuiet("test -c /dev/diag && echo YES || echo NO").contains("YES"));
            boolean audioOk = readAudioMarkersPresent(suOk);
            Boolean modemIms = null;
            String fwContents = "";
            if (suOk) {
                String hit = execRootQuiet(
                        "grep -a -l 'qp_ims_reg_config' /firmware/image/modem* /vendor/firmware_mnt/image/modem* 2>/dev/null | head -n 1 || true");
                modemIms = !hit.trim().isEmpty();
                fwContents = execRootQuiet(
                        "cat /firmware/IMAGE/CONTENTS.TXT /firmware/image/contents.txt /vendor/firmware_mnt/IMAGE/CONTENTS.TXT /vendor/firmware_mnt/image/contents.txt 2>/dev/null || true");
            }
            String basebandProp = readSystemProp("gsm.version.baseband");
            StringBuilder daemonSb = new StringBuilder();
            for (String svc : new String[]{"imsqmidaemon", "imsdatadaemon", "ims_rtp_daemon", "imscmservice"}) {
                String st = readSystemProp("init.svc." + svc);
                if (!st.isEmpty()) {
                    if (daemonSb.length() > 0) daemonSb.append(", ");
                    daemonSb.append(svc).append('=').append(st);
                }
            }
            for (String prop : new String[]{"sys.ims.QMI_DAEMON_STATUS", "sys.ims.DATA_DAEMON_STATUS"}) {
                String val = readSystemProp(prop);
                if ("1".equals(val)) {
                    if (daemonSb.length() > 0) daemonSb.append(", ");
                    daemonSb.append(prop).append("=1");
                }
            }

            String report = VolteManagerLogic.buildReadOnlyPortingReport(
                    brand, model, device, boardPlatform, cpuAbi, sdk, Build.TAGS,
                    selinux, suOk, rildPresent, diagPresent, audioOk, modemIms, englishMode,
                    basebandProp, fwContents, daemonSb.toString());
            String cleanReport = VolteManagerLogic.redactSensitiveDigits(report);
            VolteManagerLogic.DeviceCatalogMatch match = VolteManagerLogic.identifyDeviceCatalog(
                    brand, model, device, boardPlatform, cpuAbi, sdk, Build.TAGS);

            runOnUiThread(() -> {
                statusBadgeView.setText((englishMode ? match.displayNameEn : match.displayNameZh) + " · " + match.route);
                statusBadgeView.setTextColor("VERIFIED_PASSPORT_READY".equals(match.route) ? COLOR_ACCENT : COLOR_TEXT);
                logView.setText(cleanReport);
                lastSanitizedReport = cleanReport;
                setBusy(false);

                new AlertDialog.Builder(this)
                        .setTitle(englishMode
                                ? "Read-Only Readiness & AI Self-Adaptation"
                                : "多机型只读体检与 AI + GitHub 自助适配")
                        .setMessage(cleanReport)
                        .setPositiveButton(englishMode ? "Copy Report & AI Prompt" : "复制报告与 AI 提示词",
                                (d, w) -> copyReportToClipboard())
                        .setNeutralButton(englishMode ? "Open GitHub Repo" : "打开 GitHub 仓库", (d, w) -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(VolteManagerLogic.GITHUB_REPO_URL)));
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton(englishMode ? "Close" : "关闭", null)
                        .show();
            });
        });
    }

    private void confirmAndRunDeploy() {
        if (busy) return;
        String title = englishMode ? "Activate VoLTE" : "激活 VoLTE";
        String msg = englishMode
                ? "Only one wolverine unit has been verified. This checks for legacy IMS daemons and AAB982 compatibility, then writes modem EFS. The saved inspection log is not a restorable backup; first make an independent off-device EFS backup. A reboot is required. Proceed at your own risk?"
                : "仅一台 wolverine 已验证。本操作先检查旧 IMS 守护进程和 AAB982 基带，再写入 EFS；自动保存的检查日志并非可还原的完整备份。请先独立备份并保存在手机之外。完成后需重启，风险自负。继续？";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(englishMode ? "Activate" : "确认激活", (d, w) -> executeRootOperation("deploy"))
                .setNegativeButton(englishMode ? "Cancel" : "取消", null)
                .show();
    }

    private void confirmAndRunUpgrade() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(englishMode ? "Update Qcom32VolteIms APK" : "更新桥接服务 APK")
                .setMessage(englishMode
                        ? "Updates Qcom32VolteIms.apk v2.10 in place without touching modem EFS."
                        : "仅原位更新 Qcom32VolteIms.apk v2.10，不写入任何基带 EFS 项。")
                .setPositiveButton(englishMode ? "Update" : "确认更新", (d, w) -> executeRootOperation("upgrade"))
                .setNegativeButton(englishMode ? "Cancel" : "取消", null)
                .show();
    }

    private void confirmAndRunRollback() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(englishMode ? "Fixed EFS cleanup" : "固定基带清理并卸载")
                .setMessage(englishMode
                        ? "Clears CarrierConfig overrides, uninstalls Qcom32VolteIms, deletes all 52 Stage 6A EFS files, and restores IMS_enable=00 & voice_domain_pref=00."
                        : "将清除 CarrierConfig 持久覆盖、卸载 Qcom32VolteIms，删除全部 52 项新建 EFS 文件，并将 IMS_enable 与 voice_domain_pref 恢复为 00。")
                .setPositiveButton(englishMode ? "Restore" : "确认恢复", (d, w) -> executeRootOperation("rollback"))
                .setNegativeButton(englishMode ? "Cancel" : "取消", null)
                .show();
    }

    private void executeRootOperation(String mode) {
        setBusy(true);
        statusBadgeView.setText(englishMode ? "Running guarded root operation..." : "正在执行受控 Root 操作，请勿断开电源…");
        worker.execute(() -> {
            try {
                if (!VolteManagerLogic.isRootOperationAllowed(mode, Build.MODEL, Build.DEVICE, Build.VERSION.SDK_INT)) {
                    throw new IllegalStateException(englishMode
                            ? "EFS write is locked for " + Build.MODEL + " (" + Build.DEVICE + ") to protect your mobile data/MMS PDP slots. Tap '32-Bit Device Identification & AI Porting Check' above and use AI + " + VolteManagerLogic.GITHUB_REPO_URL + " (.agents/skills/qcom32-volte/) to adapt your model safely."
                            : "EXPERIMENTAL — NOT VERIFIED ON REAL HARDWARE / 实验性，未经真机验证：当前机型仅允许只读检查和回滚。");
                }
                if (!checkSuAvailable()) {
                    throw new IllegalStateException(englishMode
                            ? "Local su (Magisk) is not available. Please connect USB and use the Qcom32 VoLTE Desktop tool via ADB Root."
                            : "当前手机没有本地 su (Magisk) 权限。若是纯净 LineageOS 18.1，请连电脑使用 Qcom32 VoLTE 桌面激活器（走自带 ADB Root）。");
                }
                File staged = stageVerifiedAssets();
                String script;
                if ("deploy".equals(mode)) {
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                    File backupDir = new File(getFilesDir(), "efs-backups/backup-" + stamp);
                    backupDir.mkdirs();
                    script = VolteManagerLogic.buildRootDeployScript(staged.getAbsolutePath(), backupDir.getAbsolutePath());
                } else if ("upgrade".equals(mode)) {
                    script = VolteManagerLogic.buildRootUpgradeApkScript(staged.getAbsolutePath());
                } else {
                    script = VolteManagerLogic.buildRootRollbackScript(staged.getAbsolutePath());
                }
                String out = execRoot(script);
                String redacted = VolteManagerLogic.redactSensitiveDigits(out);
                runOnUiThread(() -> {
                    logView.setText(redacted);
                    lastSanitizedReport = redacted;
                    setBusy(false);
                    if ("rollback".equals(mode)) {
                        postLocalRollbackRebootNotification();
                    }
                    if ("upgrade".equals(mode)) {
                        runCheckStatus();
                    } else {
                        promptNormalReboot();
                    }
                });
            } catch (Exception e) {
                String err = VolteManagerLogic.redactSensitiveDigits("ERROR: " + e.getMessage());
                runOnUiThread(() -> {
                    statusBadgeView.setText(englishMode ? "Operation stopped safely" : "操作已安全中止");
                    statusBadgeView.setTextColor(COLOR_DANGER);
                    logView.setText(err);
                    setBusy(false);
                });
            }
        });
    }

    private void postLocalRollbackRebootNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            String channelId = "qcom32_volte_manager_alerts";
            NotificationChannel ch = new NotificationChannel(
                    channelId,
                    englishMode ? "VoLTE Manager Reboot Alerts" : "VoLTE 管理端重启提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);

            Intent tap = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = englishMode
                    ? "Fixed EFS Cleanup Completed — Reboot Required"
                    : "已执行固定基带清理 — 请重启手机生效";
            String body = englishMode
                    ? "Stage 6A EFS items removed and IMS_enable restored to 00. Reboot your phone once to complete rollback."
                    : "已完成基带原值恢复并卸载桥接服务；请正常重启一次手机使其完全生效。";

            Notification n = new Notification.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_stat_volte)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setColor(COLOR_DANGER)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(32012, n);
        } catch (Exception ignored) { }
    }

    private void promptNormalReboot() {
        new AlertDialog.Builder(this)
                .setTitle(englishMode ? "Completed — Reboot Required" : "已完成 — 请正常重启手机")
                .setMessage(englishMode
                        ? "Operation succeeded and EFS_SYNC completed. Reboot now to apply cleanly?"
                        : "操作与基带 EFS_SYNC 均已核验完成。是否现在正常重启手机使其生效？")
                .setPositiveButton(englishMode ? "Reboot Now" : "立即正常重启", (d, w) -> worker.execute(() -> execRoot("svc power reboot")))
                .setNegativeButton(englishMode ? "Reboot Later" : "稍后手动重启", (d, w) -> runCheckStatus())
                .show();
    }

    private void copyReportToClipboard() {
        if (lastSanitizedReport == null || lastSanitizedReport.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Qcom32VoLTE", lastSanitizedReport));
            Toast.makeText(this, englishMode ? "Redacted report copied" : "已复制脱敏诊断摘要", Toast.LENGTH_SHORT).show();
        }
    }

    private Bundle queryReadOnlyStatusProvider() {
        try {
            Uri uri = Uri.parse("content://" + VolteManagerLogic.STATUS_AUTHORITY);
            return getContentResolver().call(uri, VolteManagerLogic.STATUS_METHOD, null, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readLocalSimOperator() {
        try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            if (tm != null) {
                String op = tm.getSimOperator();
                if (op != null && !op.trim().isEmpty()) {
                    return op.trim();
                }
            }
        } catch (Exception ignored) {}
        return readSystemProp("gsm.sim.operator.numeric");
    }

    private boolean verifyBundledAssetsOnly() throws Exception {
        AssetManager am = getAssets();
        JSONObject manifest = new JSONObject(new String(readAssetBytes(am, "payload-manifest.json"), StandardCharsets.UTF_8));
        for (String name : BUNDLED_ASSET_NAMES) {
            String expectedSha = manifest.getString(name);
            byte[] raw = readAssetBytes(am, name);
            String actualSha = VolteManagerLogic.sha256Hex(raw);
            if (!expectedSha.equalsIgnoreCase(actualSha)) {
                throw new SecurityException("Asset SHA-256 mismatch for " + name);
            }
        }
        return true;
    }

    private File stageVerifiedAssets() throws Exception {
        AssetManager am = getAssets();
        JSONObject manifest = new JSONObject(new String(readAssetBytes(am, "payload-manifest.json"), StandardCharsets.UTF_8));
        File dir = new File(getFilesDir(), "staged-payloads");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create staged-payloads directory");
        }
        for (String name : BUNDLED_ASSET_NAMES) {
            String expectedSha = manifest.getString(name);
            byte[] raw = readAssetBytes(am, name);
            String actualSha = VolteManagerLogic.sha256Hex(raw);
            if (!expectedSha.equalsIgnoreCase(actualSha)) {
                throw new SecurityException("Asset SHA-256 mismatch for " + name);
            }
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(raw);
            }
            out.setReadable(true, false);
            if (!name.endsWith(".apk")) {
                out.setExecutable(true, false);
            }
        }
        return dir;
    }

    private static byte[] readAssetBytes(AssetManager am, String name) throws IOException {
        try (InputStream in = am.open(name); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String readSelinuxMode() {
        try {
            Process p = new ProcessBuilder("getenforce").redirectErrorStream(true).start();
            String out = new String(readStream(p.getInputStream()), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!out.isEmpty()) {
                return out;
            }
        } catch (Exception ignored) {}
        String prop = readSystemProp("ro.boot.selinux");
        return prop.isEmpty() ? "Enforcing/Default" : prop;
    }

    private static boolean readAudioMarkersPresent(boolean suOk) {
        String[] candidates = new String[]{
                "/vendor/etc/mixer_paths.xml",
                "/vendor/etc/mixer_paths_msm8974.xml",
                "/system/etc/mixer_paths.xml"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.isFile() && f.canRead() && f.length() < 4 * 1024 * 1024L) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    String txt = new String(readStream(fis), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (txt.contains("voice-mmode1") || txt.contains("voice-ims")
                            || txt.contains("compress-voip") || txt.contains("volte")) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        if (suOk) {
            String hit = execRootQuiet(
                    "grep -E -i -l 'Voice-MMode1|voice-ims|compress-voip|VoLTE' /vendor/etc/mixer_paths*.xml /system/etc/mixer_paths*.xml 2>/dev/null | head -n 1 || true");
            return !hit.trim().isEmpty();
        }
        return false;
    }

    private static String execRootQuiet(String script) {
        try {
            return execRoot(script);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readSystemProp(String key) {
        try {
            Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            byte[] out = readStream(p.getInputStream());
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean checkSuAvailable() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            String out = new String(readStream(p.getInputStream()), StandardCharsets.UTF_8).trim();
            int rc = p.waitFor();
            return rc == 0 && "0".equals(out);
        } catch (Exception e) {
            return false;
        }
    }

    private static String execRoot(String script) {
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(VolteManagerLogic.utf8Bytes(script + "\nexit\n"));
                os.flush();
            }
            String out = new String(readStream(p.getInputStream()), StandardCharsets.UTF_8);
            int rc = p.waitFor();
            if (rc != 0) {
                throw new IllegalStateException("su exited with code " + rc + ": " + out.trim());
            }
            return out;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Root execution failed: " + e.getMessage(), e);
        }
    }

    private static byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
