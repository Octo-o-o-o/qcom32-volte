package org.octolab.qcom32ims;

import java.util.Locale;

/**
 * Pure, zero-Android-dependency decision engine for boot, SIM-swap, and pending-reboot
 * system notifications in {@code org.octolab.qcom32ims}.
 *
 * Key design guarantees:
 * 1. Zero PII: uses only sanitized MCC/MNC (e.g. {@code 46001}) and RAT (e.g. {@code LTE}).
 * 2. Zero mid-day spam: notifies {@code ACTIVATED} once per {@code (bootToken, simOperator, swapSeq)}
 *    session; transient elevator/subway signal drops after activation never re-spam notifications.
 * 3. Actionable diagnostics: after a post-boot / post-SIM-swap settle window, distinguishes
 *    pending reboot, hot SIM swap requiring reboot, non-4G fallback (including China Mobile FDD
 *    B3/B8 hardware guidance on Passport {@code wolverine}), inactive modem EFS, and carrier
 *    IMS provisioning wait.
 */
public final class VolteStatusNotificationLogic {
    private VolteStatusNotificationLogic() {}

    public enum Action {
        NOOP,
        POST,
        CANCEL
    }

    public enum Kind {
        NONE,
        REBOOT_REQUIRED,
        ACTIVATED,
        SIM_SWAP_REBOOT_NEEDED,
        AWAITING_4G_LTE,
        MODEM_CONFIG_INACTIVE,
        LTE_IMS_PENDING,
        RADIO_UNAVAILABLE
    }

    public static final class SessionState {
        public String bootToken = "";
        public String activeSimOperator = "";
        public int swapSeq = 0;
        public boolean sawSimAbsentInBoot = false;
        public boolean hotSwappedInBoot = false;
        public String pendingRebootBootToken = "";
        public boolean notifiedActivatedInSession = false;
        public String lastPostedFingerprint = "";
        public String lastPostedKind = Kind.NONE.name();
        public String lastPostedTitle = "";

        public SessionState copy() {
            SessionState c = new SessionState();
            c.bootToken = this.bootToken;
            c.activeSimOperator = this.activeSimOperator;
            c.swapSeq = this.swapSeq;
            c.sawSimAbsentInBoot = this.sawSimAbsentInBoot;
            c.hotSwappedInBoot = this.hotSwappedInBoot;
            c.pendingRebootBootToken = this.pendingRebootBootToken;
            c.notifiedActivatedInSession = this.notifiedActivatedInSession;
            c.lastPostedFingerprint = this.lastPostedFingerprint;
            c.lastPostedKind = this.lastPostedKind;
            c.lastPostedTitle = this.lastPostedTitle;
            return c;
        }
    }

    public static final class EvalInput {
        public final String bootToken;
        public final boolean bootstrapDisabled;
        public final boolean simAbsent;
        public final boolean simReady;
        public final String simOperator;
        public final String networkType;
        public final String deviceCodename;
        public final boolean imsHealthy;
        public final boolean settleWindowElapsed;
        public final int qmiTestMode;
        public final boolean englishLocale;
        public final long uptimeMs;
        public final boolean radioUnavailable;

        public EvalInput(
                String bootToken,
                boolean bootstrapDisabled,
                boolean simAbsent,
                boolean simReady,
                String simOperator,
                String networkType,
                String deviceCodename,
                boolean imsHealthy,
                boolean settleWindowElapsed,
                int qmiTestMode,
                boolean englishLocale) {
            this(
                    bootToken,
                    bootstrapDisabled,
                    simAbsent,
                    simReady,
                    simOperator,
                    networkType,
                    deviceCodename,
                    imsHealthy,
                    settleWindowElapsed,
                    qmiTestMode,
                    englishLocale,
                    60_000L,
                    false);
        }

        public EvalInput(
                String bootToken,
                boolean bootstrapDisabled,
                boolean simAbsent,
                boolean simReady,
                String simOperator,
                String networkType,
                String deviceCodename,
                boolean imsHealthy,
                boolean settleWindowElapsed,
                int qmiTestMode,
                boolean englishLocale,
                long uptimeMs) {
            this(
                    bootToken,
                    bootstrapDisabled,
                    simAbsent,
                    simReady,
                    simOperator,
                    networkType,
                    deviceCodename,
                    imsHealthy,
                    settleWindowElapsed,
                    qmiTestMode,
                    englishLocale,
                    uptimeMs,
                    false);
        }

        public EvalInput(
                String bootToken,
                boolean bootstrapDisabled,
                boolean simAbsent,
                boolean simReady,
                String simOperator,
                String networkType,
                String deviceCodename,
                boolean imsHealthy,
                boolean settleWindowElapsed,
                int qmiTestMode,
                boolean englishLocale,
                long uptimeMs,
                boolean radioUnavailable) {
            this.bootToken = safeStr(bootToken);
            this.bootstrapDisabled = bootstrapDisabled;
            this.simAbsent = simAbsent;
            this.simReady = simReady;
            this.simOperator = QmiProbeSummaryParser.sanitizeSimOperator(simOperator);
            this.networkType = QmiProbeSummaryParser.sanitizeNetworkType(networkType);
            this.deviceCodename = safeStr(deviceCodename);
            this.imsHealthy = imsHealthy;
            this.settleWindowElapsed = settleWindowElapsed;
            this.qmiTestMode = qmiTestMode;
            this.englishLocale = englishLocale;
            this.uptimeMs = uptimeMs;
            this.radioUnavailable = radioUnavailable;
        }
    }

    public static final class Decision {
        public final Action action;
        public final Kind kind;
        public final String title;
        public final String body;
        public final String reason;
        public final SessionState nextState;

        private Decision(
                Action action,
                Kind kind,
                String title,
                String body,
                String reason,
                SessionState nextState) {
            this.action = action;
            this.kind = kind;
            this.title = title;
            this.body = body;
            this.reason = reason;
            this.nextState = nextState;
        }

        static Decision noop(String reason, SessionState nextState) {
            return new Decision(Action.NOOP, Kind.NONE, "", "", reason, nextState);
        }

        static Decision cancel(String reason, SessionState nextState) {
            nextState.lastPostedFingerprint = "";
            nextState.lastPostedKind = Kind.NONE.name();
            nextState.lastPostedTitle = "";
            return new Decision(Action.CANCEL, Kind.NONE, "", "", reason, nextState);
        }

        static Decision post(
                Kind kind,
                String title,
                String body,
                String fingerprint,
                String reason,
                SessionState nextState) {
            nextState.lastPostedFingerprint = fingerprint;
            nextState.lastPostedKind = kind.name();
            nextState.lastPostedTitle = title;
            return new Decision(Action.POST, kind, title, body, reason, nextState);
        }
    }

    private static String safeStr(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Strict action whitelist for {@code CarrierConfigBootstrapReceiver} (which is exported
     * without a sender permission because system broadcasts come from both UID 1000 and UID 1001).
     * Every whitelisted action is a system {@code <protected-broadcast>} on Android 11, blocking
     * explicit-component intent spoofing from unprivileged third-party apps.
     */
    public static boolean isAllowedBootstrapBroadcastAction(String action) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case "android.telephony.action.CARRIER_CONFIG_CHANGED":
            case "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED":
            case "android.intent.action.SIM_STATE_CHANGED":
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.BOOT_COMPLETED":
            case "android.intent.action.USER_UNLOCKED":
            case "android.intent.action.MY_PACKAGE_REPLACED":
                return true;
            default:
                return false;
        }
    }

    public static boolean isLteNetwork(String networkType) {
        String n = safeStr(networkType).toUpperCase(Locale.ROOT);
        return "LTE".equals(n) || "LTE_CA".equals(n) || n.startsWith("LTE");
    }

    /** Legacy 3GPP2 CDMA / EVDO / eHRPD bitmask constants matching {@code TelephonyManager}. */
    public static final long BITMASK_CDMA_LEGACY =
            8L | 16L | 32L | 64L | 2048L | 8192L;

    /** TD-SCDMA bitmask (`1 << (NETWORK_TYPE_TD_SCDMA - 1) = 65536`). */
    public static final long BITMASK_TD_SCDMA = 65536L;

    /** GSM/GPRS/EDGE + UMTS/HSPA bitmask (`32771 | 17284 = 50055`). */
    public static final long BITMASK_GSM_WCDMA = 32771L | 17284L;

    /** Pure LTE + LTE_CA bitmask (`4096 | 262144 = 266240`, mapping to `NETWORK_MODE_LTE_ONLY = 11`). */
    public static final long BITMASK_LTE_ONLY = 4096L | 262144L;

    /** LTE + WCDMA + GSM bitmask (`32771 | 17284 | 266240 = 316295`, `NETWORK_MODE_LTE_GSM_WCDMA = 9`). */
    public static final long BITMASK_LTE_GSM_WCDMA = BITMASK_GSM_WCDMA | BITMASK_LTE_ONLY;

    public static final int NETWORK_MODE_LTE_GSM_WCDMA = 9;
    public static final int NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA = 10;
    public static final int NETWORK_MODE_LTE_ONLY = 11;

    /**
     * Returns true for all BlackBerry Passport unified {@code lineage_wseries} hardware editions
     * sharing the identical vendor {@code libril.so} + {@code NON-HLOS.bin} baseband:
     * {@code wolverine} (SQW100-1/2/3), {@code oslo} (SQW100-4 Silver Edition),
     * {@code wichita} (AT&T Rounded Edition), {@code keian} (Porsche Design), and {@code wseries}.
     */
    public static boolean isWolverineDevice(String deviceCodename) {
        switch (safeStr(deviceCodename).toLowerCase(Locale.US)) {
            case "wolverine":
            case "oslo":
            case "wichita":
            case "keian":
            case "wseries":
            case "windermere":
                return true;
            default:
                return false;
        }
    }

    public static boolean isChinaTelecom(String simOperator) {
        String op = QmiProbeSummaryParser.sanitizeSimOperator(simOperator);
        switch (op) {
            case "46003":
            case "46005":
            case "46011":
            case "46012":
                return true;
            default:
                return false;
        }
    }

    public static boolean isLteGsmWcdmaMask(long bitmask) {
        if (bitmask <= 0L) {
            return false;
        }
        if ((bitmask & (BITMASK_CDMA_LEGACY | BITMASK_TD_SCDMA)) != 0L) {
            return false;
        }
        boolean hasLte = (bitmask & 4096L) != 0L;
        boolean hasGsmOrWcdma = (bitmask & BITMASK_GSM_WCDMA) != 0L;
        return hasLte && hasGsmOrWcdma;
    }

    /**
     * Target {@code persist.radio.fallbackmode} property value for BlackBerry Passport
     * ({@code wolverine}) vendor {@code libril.so} (`fallback_thread` at `0x191c8` and
     * `RadioImpl::setRadioPower` at `0x19e9a`).
     *
     * On China Telecom (`46003/46005/46011/46012`), LTE attaches EPS-only (`EMM #18 CS domain not
     * available`), so `voice_rat == 0` briefly until IMS SIP REGISTER finishes. Aligning both
     * `preferred_network_mode = 9` (`LTE_GSM_WCDMA`, `qmi_mode_pref = 28`) and
     * `persist.radio.fallbackmode = "9"` matches `libril.so` initial `cur_net_mode = 9` (`0x32fb0`)
     * AND QCRIL's same-`mode_pref` no-op guard (`0x26eae0 -> 0x26ecb6`), eliminating the 3-second
     * `SET_PREFERRED_NETWORK_TYPE(17 <-> user_mode)` `post_cfg_ban` LTE detach loop while keeping
     * GSM/WCDMA roaming intact. Non-China-Telecom SIMs (e.g. China Unicom `46001` CSFB) keep stock
     * `""` (`fallback_mode = 17`) with zero 1s requests once `voice_rat != 0`.
     */
    public static String targetWolverineFallbackModeProp(String deviceCodename, String simOperator) {
        if (isWolverineDevice(deviceCodename) && isChinaTelecom(simOperator)) {
            return String.valueOf(NETWORK_MODE_LTE_GSM_WCDMA);
        }
        return "";
    }

    public static boolean needsChinaTelecomWolverineAlignment(
            String deviceCodename,
            String simOperator,
            int currentNetMode,
            long currentBitmask,
            String currentFallbackProp) {
        if (!isWolverineDevice(deviceCodename) || !isChinaTelecom(simOperator)) {
            return false;
        }
        return currentNetMode != NETWORK_MODE_LTE_GSM_WCDMA
                || !isLteGsmWcdmaMask(currentBitmask)
                || !"9".equals(safeStr(currentFallbackProp));
    }

    public static boolean isChinaMobile(String simOperator) {
        String op = QmiProbeSummaryParser.sanitizeSimOperator(simOperator);
        switch (op) {
            case "46000":
            case "46002":
            case "46004":
            case "46007":
            case "46008":
                return true;
            default:
                return false;
        }
    }

    public static String formatCarrierName(String simOperator, boolean english) {
        String op = QmiProbeSummaryParser.sanitizeSimOperator(simOperator);
        switch (op) {
            case "46001":
            case "46006":
            case "46009":
                return english ? "China Unicom" : "中国联通";
            case "46003":
            case "46005":
            case "46011":
            case "46012":
                return english ? "China Telecom" : "中国电信";
            case "46000":
            case "46002":
            case "46004":
            case "46007":
            case "46008":
                return english ? "China Mobile" : "中国移动";
            case "46015":
            case "46020":
                return english ? "China Broadnet" : "中国广电";
            default:
                if (op.isEmpty()) {
                    return english ? "Active SIM" : "当前 SIM";
                }
                return "PLMN " + op;
        }
    }

    private static String formatCarrierWithCode(String simOperator, boolean english) {
        String op = QmiProbeSummaryParser.sanitizeSimOperator(simOperator);
        String name = formatCarrierName(op, english);
        if (op.isEmpty() || name.startsWith("PLMN ")) {
            return name;
        }
        return name + " (" + op + ")";
    }

    private static void syncBootBoundary(SessionState st, String currentBootToken) {
        String cur = safeStr(currentBootToken);
        if (!cur.isEmpty() && !cur.equals(st.bootToken)) {
            if (!cur.equals(st.pendingRebootBootToken)) {
                st.pendingRebootBootToken = "";
            }
            st.bootToken = cur;
            st.activeSimOperator = "";
            st.swapSeq = 0;
            st.sawSimAbsentInBoot = false;
            st.hotSwappedInBoot = false;
            st.notifiedActivatedInSession = false;
            st.lastPostedFingerprint = "";
            st.lastPostedKind = Kind.NONE.name();
            st.lastPostedTitle = "";
        }
    }

    public static Decision markRebootRequired(
            SessionState prev,
            String currentBootToken,
            boolean englishLocale) {
        SessionState st = prev != null ? prev.copy() : new SessionState();
        syncBootBoundary(st, currentBootToken);
        st.pendingRebootBootToken = safeStr(currentBootToken);
        st.notifiedActivatedInSession = false;

        String title = englishLocale
                ? "VoLTE Configured — Reboot Required"
                : "VoLTE 配置已就绪 — 请重启手机生效";
        String body = englishLocale
                ? "Modem Stage 6A EFS & Android 11 IMS binding saved. Reboot your phone once to complete activation."
                : "基带 Stage 6A 配置与 Android 11 IMS 持久绑定已写入，请正常重启一次手机完成激活。";
        String fp = "boot=" + st.bootToken + "|kind=" + Kind.REBOOT_REQUIRED.name() + "|en=" + englishLocale;
        if (fp.equals(st.lastPostedFingerprint)) {
            return Decision.noop("duplicate_reboot_required", st);
        }
        return Decision.post(Kind.REBOOT_REQUIRED, title, body, fp, "reboot_required_marked", st);
    }

    public static SessionState onPackageReplaced(SessionState prev, String currentBootToken) {
        SessionState st = prev != null ? prev.copy() : new SessionState();
        syncBootBoundary(st, currentBootToken);
        // Package upgrade/reinstall cancels active package notifications in NotificationManagerService
        // while DeviceProtectedStorage SharedPreferences survives; clear cached shade flags so the
        // live session status can be re-posted cleanly once.
        st.notifiedActivatedInSession = false;
        st.lastPostedFingerprint = "";
        st.lastPostedKind = Kind.NONE.name();
        st.lastPostedTitle = "";
        return st;
    }

    public static Decision onSimAbsent(SessionState prev, String currentBootToken, long uptimeMs) {
        SessionState st = prev != null ? prev.copy() : new SessionState();
        syncBootBoundary(st, currentBootToken);
        boolean hadActiveSimInThisBoot = !st.activeSimOperator.isEmpty();
        if (hadActiveSimInThisBoot || uptimeMs >= 40_000L) {
            st.sawSimAbsentInBoot = true;
        }
        st.activeSimOperator = "";
        st.notifiedActivatedInSession = false;
        if (!st.pendingRebootBootToken.isEmpty() && st.pendingRebootBootToken.equals(st.bootToken)) {
            return Decision.noop("sim_absent_keep_reboot_required", st);
        }
        if (st.lastPostedFingerprint.isEmpty()) {
            return Decision.noop("sim_absent_already_clean", st);
        }
        return Decision.cancel("sim_absent", st);
    }

    public static Decision evaluate(SessionState prev, EvalInput in) {
        SessionState st = prev != null ? prev.copy() : new SessionState();
        syncBootBoundary(st, in.bootToken);

        // 1. Rollback / bootstrap disabled -> clear notification cleanly
        if (in.bootstrapDisabled) {
            st.pendingRebootBootToken = "";
            st.notifiedActivatedInSession = false;
            if (!st.lastPostedFingerprint.isEmpty()) {
                return Decision.cancel("bootstrap_disabled", st);
            }
            return Decision.noop("bootstrap_disabled_idle", st);
        }

        // 2. Pending reboot from provisioning in this exact boot takes top priority
        if (!st.pendingRebootBootToken.isEmpty() && st.pendingRebootBootToken.equals(st.bootToken)) {
            return markRebootRequired(st, st.bootToken, in.englishLocale);
        }

        // 3. SIM card absent -> clear stale SIM status and mark hot-swap ready
        if (in.simAbsent) {
            return onSimAbsent(st, st.bootToken, in.uptimeMs);
        }

        // 4. Detect hot SIM swap within the same boot
        if (!in.simOperator.isEmpty()) {
            if (st.activeSimOperator.isEmpty()) {
                st.activeSimOperator = in.simOperator;
                if (st.sawSimAbsentInBoot) {
                    st.hotSwappedInBoot = true;
                    st.sawSimAbsentInBoot = false;
                    st.swapSeq++;
                    st.notifiedActivatedInSession = false;
                    st.lastPostedFingerprint = "";
                }
            } else if (!st.activeSimOperator.equals(in.simOperator)) {
                st.activeSimOperator = in.simOperator;
                st.hotSwappedInBoot = true;
                st.sawSimAbsentInBoot = false;
                st.swapSeq++;
                st.notifiedActivatedInSession = false;
                st.lastPostedFingerprint = "";
            }
        }

        String carrierShort = formatCarrierName(in.simOperator, in.englishLocale);
        String carrierFull = formatCarrierWithCode(in.simOperator, in.englishLocale);
        String sessionKey = "boot=" + st.bootToken + "|sim=" + st.activeSimOperator + "|seq=" + st.swapSeq;

        // 5. IMS & VoLTE healthy! Notify once per (boot, SIM, swapSeq) session
        if (in.imsHealthy) {
            if (st.notifiedActivatedInSession) {
                return Decision.noop("already_notified_activated_in_session", st);
            }
            st.notifiedActivatedInSession = true;
            String title;
            String body;
            if (st.hotSwappedInBoot) {
                title = in.englishLocale
                        ? ("SIM Swapped: VoLTE Active · " + carrierShort)
                        : ("换卡成功：VoLTE 已激活 · " + carrierShort);
                body = in.englishLocale
                        ? (carrierFull + " 4G HD Voice (AMR-WB) & LTE data continuity are ready.")
                        : (carrierFull + " 4G 高清语音 (AMR-WB) 与通话不断网已就绪，可直接拨号测试。");
            } else {
                title = in.englishLocale
                        ? ("VoLTE Active & Ready · " + carrierShort)
                        : ("VoLTE 已激活就绪 · " + carrierShort);
                body = in.englishLocale
                        ? (carrierFull + " 4G HD Voice (AMR-WB) & LTE data continuity are active. Tap for live status.")
                        : (carrierFull + " 4G 高清语音 (AMR-WB) 与通话不断网已就绪，点击可查看实时状态。");
            }
            String fp = sessionKey + "|kind=" + Kind.ACTIVATED.name() + "|en=" + in.englishLocale;
            return Decision.post(Kind.ACTIVATED, title, body, fp, "volte_activated", st);
        }

        // 6. If already activated earlier in this exact session, ignore routine elevator/tunnel drops
        if (st.notifiedActivatedInSession) {
            return Decision.noop("transient_drop_after_session_activation", st);
        }

        // 7. Before settle window expires, wait quietly so normal boot registration doesn't flicker
        if (!in.settleWindowElapsed) {
            return Decision.noop("awaiting_settle_window", st);
        }

        if (!in.simReady && in.simOperator.isEmpty()) {
            if (in.radioUnavailable && in.uptimeMs >= 50_000L) {
                String rTitle = in.englishLocale
                        ? "Modem/SIM Not Ready (No Signal) — Rollback VoLTE"
                        : "基带或 SIM 未就绪（无信号）— 请执行 VoLTE 回滚恢复";
                String rBody = in.englishLocale
                        ? "Modem failed to initialize after boot (typically caused by legacy imsqmidaemon on older Permissive ROMs or non-AAB982 modem). Tap to open Manager or run PC rollback to restore signal."
                        : "开机后基带未完成初始化（常见于旧版 Permissive ROM 残留 imsqmidaemon 或非 AAB982 底包）。点击打开管理端或连电脑运行回滚即可恢复信号。";
                String rFp = sessionKey + "|kind=" + Kind.RADIO_UNAVAILABLE.name() + "|en=" + in.englishLocale;
                if (rFp.equals(st.lastPostedFingerprint)) {
                    return Decision.noop("duplicate_diagnostic_" + Kind.RADIO_UNAVAILABLE.name(), st);
                }
                return Decision.post(
                        Kind.RADIO_UNAVAILABLE,
                        rTitle,
                        rBody,
                        rFp,
                        "diagnostic_" + Kind.RADIO_UNAVAILABLE.name(),
                        st);
            }
            return Decision.noop("sim_not_ready_yet", st);
        }

        // 8. Settle window elapsed and still not registered -> give accurate, actionable alert
        Kind kind;
        String title;
        String body;
        String netLabel = in.networkType.isEmpty() ? "UNKNOWN" : in.networkType;

        if (st.hotSwappedInBoot) {
            kind = Kind.SIM_SWAP_REBOOT_NEEDED;
            title = in.englishLocale
                    ? "SIM Swap Detected — Reboot to Activate VoLTE"
                    : "检测到已换卡 — 请重启手机激活 VoLTE";
            body = in.englishLocale
                    ? ("Persistent binding saved for " + carrierFull
                            + ", but modem IMS has not registered yet. Please reboot your phone once.")
                    : ("已为 " + carrierFull + " 写入持久绑定，但基带尚未完成新卡注册；请正常重启一次手机以完成激活。");
        } else if (!isLteNetwork(in.networkType)) {
            kind = Kind.AWAITING_4G_LTE;
            boolean cmccOnWolverine = isChinaMobile(in.simOperator)
                    && (isWolverineDevice(in.deviceCodename) || in.deviceCodename.isEmpty());
            if (cmccOnWolverine) {
                title = in.englishLocale
                        ? "Not on 4G LTE · VoLTE Standby (China Mobile)"
                        : "未驻留 4G 网络 · VoLTE 待机中 (中国移动)";
                body = in.englishLocale
                        ? ("Current network: " + netLabel
                                + " (Passport supports FDD B3/B8 only, not TDD B38–B41). VoLTE activates automatically on 4G.")
                        : ("当前网络为 " + netLabel
                                + "（Passport 射频仅支持移动 FDD B3/B8 4G 频段，不支持 TDD B38–B41）；进入 FDD 4G 覆盖后将自动激活 VoLTE。");
            } else {
                title = in.englishLocale
                        ? ("Awaiting 4G LTE · VoLTE Standby (" + carrierShort + ")")
                        : ("未驻留 4G 网络 · VoLTE 待机中 (" + carrierShort + ")");
                body = in.englishLocale
                        ? ("Current network: " + netLabel + ". VoLTE will activate automatically once 4G LTE connects.")
                        : ("当前网络为 " + netLabel + "，进入 4G LTE 信号覆盖后将自动完成 VoLTE 激活。");
            }
        } else if (in.qmiTestMode > 0) {
            kind = Kind.MODEM_CONFIG_INACTIVE;
            title = in.englishLocale
                    ? "Modem VoLTE Inactive — Reboot or Provision"
                    : "基带 VoLTE 配置未生效 — 请重启手机";
            body = in.englishLocale
                    ? ("Connected to " + carrierFull
                            + " 4G LTE, but Stage 6A modem config is not loaded. Reboot once or run Qcom32 VoLTE provisioning.")
                    : ("已连接 " + carrierFull
                            + " 4G 网络，但基带尚未加载 Stage 6A 配置；请正常重启一次手机或运行激活工具。");
        } else {
            kind = Kind.LTE_IMS_PENDING;
            title = in.englishLocale
                    ? ("On 4G LTE, VoLTE Not Registered · " + carrierShort)
                    : ("4G 已连接但 VoLTE 尚未注册 · " + carrierShort);
            body = in.englishLocale
                    ? (carrierFull + " is on 4G LTE, awaiting carrier IMS registration. Verify VoLTE is enabled on this SIM or reboot once.")
                    : (carrierFull + " 已驻留 4G，但运营商侧尚未完成 IMS 注册；请确认该卡已开通 VoLTE 业务，或正常重启一次手机重试。");
        }

        String fp = sessionKey + "|kind=" + kind.name() + "|en=" + in.englishLocale;
        if (fp.equals(st.lastPostedFingerprint)) {
            return Decision.noop("duplicate_diagnostic_" + kind.name(), st);
        }
        return Decision.post(kind, title, body, fp, "diagnostic_" + kind.name(), st);
    }
}
