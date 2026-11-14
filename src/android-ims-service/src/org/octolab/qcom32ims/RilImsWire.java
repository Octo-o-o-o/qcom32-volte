package org.octolab.qcom32ims;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Self-contained pure-Java wire framing and protobuf codec for BlackBerry Passport
 * msm8974 QCRIL IMS socket (/dev/socket/qmux_radio/rild_ims0, imsIF.proto).
 *
 * Deliberately free of Android runtime dependencies so host JVM tests exercise
 * the exact production bytecode packaged into Qcom32VolteIms.apk.
 */
public final class RilImsWire {
    public static final int MAX_FRAME_BYTES = 16384;

    public static final int MSG_TYPE_REQUEST = 1;
    public static final int MSG_TYPE_RESPONSE = 2;
    public static final int MSG_TYPE_UNSOL_RESPONSE = 3;

    // Solicited Request IDs (imsIF.proto MsgId)
    public static final int REQ_IMS_REGISTRATION_STATE = 1;
    public static final int REQ_DIAL = 2;
    public static final int REQ_ANSWER = 3;
    public static final int REQ_HANGUP = 4;
    public static final int REQ_LAST_CALL_FAIL_CAUSE = 5;
    public static final int REQ_GET_CURRENT_CALLS = 6;
    public static final int REQ_HANGUP_WAITING_OR_BACKGROUND = 7;
    public static final int REQ_HANGUP_FOREGROUND_RESUME_BACKGROUND = 8;
    public static final int REQ_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE = 9;
    public static final int REQ_CONFERENCE = 10;
    public static final int REQ_DTMF = 13;
    public static final int REQ_DTMF_START = 14;
    public static final int REQ_DTMF_STOP = 15;
    public static final int REQ_QUERY_SERVICE_STATUS = 29;
    public static final int REQ_HOLD = 37;
    public static final int REQ_RESUME = 38;
    // Note: REQ_IMS_REG_STATE_CHANGE (26) and REQ_SET_SERVICE_STATUS (30) are intentionally omitted
    // from normal operation so AP never disturbs Hexagon Stage 6A autonomous VoLTE registration.

    // Unsolicited Indication IDs (imsIF.proto MsgId)
    public static final int UNSOL_RESPONSE_CALL_STATE_CHANGED = 201;
    public static final int UNSOL_CALL_RING = 202;
    public static final int UNSOL_RINGBACK_TONE = 203;
    public static final int UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED = 204;
    public static final int UNSOL_ENTER_EMERGENCY_CALLBACK_MODE = 205;
    public static final int UNSOL_EXIT_EMERGENCY_CALLBACK_MODE = 206;
    public static final int UNSOL_REFRESH_CONF_INFO = 209;
    public static final int UNSOL_SRV_STATUS_UPDATE = 210;
    public static final int UNSOL_SUPP_SVC_NOTIFICATION = 211;
    public static final int UNSOL_TTY_NOTIFICATION = 212;
    public static final int UNSOL_RADIO_STATE_CHANGED = 213;
    public static final int UNSOL_MWI = 214;

    // DriverCallIms states (imsIF.proto CallState)
    public static final int CALL_STATE_ACTIVE = 0;
    public static final int CALL_STATE_HOLDING = 1;
    public static final int CALL_STATE_DIALING = 2;
    public static final int CALL_STATE_ALERTING = 3;
    public static final int CALL_STATE_INCOMING = 4;
    public static final int CALL_STATE_WAITING = 5;
    public static final int CALL_STATE_END = 6;

    // imsIF.proto CallType & CallDomain
    public static final int IMS_CALL_TYPE_VOICE = 0;
    public static final int IMS_CALL_DOMAIN_UNKNOWN = 0;
    public static final int IMS_CALL_DOMAIN_CS = 1;
    public static final int IMS_CALL_DOMAIN_PS = 2;
    public static final int IMS_CALL_DOMAIN_AUTOMATIC = 3;

    // imsIF.proto Registration.RegState
    public static final int REG_STATE_REGISTERED = 1;
    public static final int REG_STATE_NOT_REGISTERED = 2;
    public static final int REG_STATE_REGISTERING = 3;

    // imsIF.proto StatusType & RadioTechType
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_PARTIALLY_ENABLED = 1;
    public static final int STATUS_ENABLED = 2;
    public static final int STATUS_NOT_SUPPORTED = 3;
    public static final int RADIO_TECH_LTE = 14;
    public static final int RADIO_TECH_IWLAN = 18;
    public static final int RADIO_TECH_WIFI = 19;

    // imsIF.proto RadioStateChanged
    public static final int RADIO_STATE_OFF = 0;
    public static final int RADIO_STATE_UNAVAILABLE = 1;
    public static final int RADIO_STATE_ON = 2;

    // imsIF.proto CallFailCause selected constants
    public static final int CALL_FAIL_UNOBTAINABLE_NUMBER = 1;
    public static final int CALL_FAIL_NORMAL = 16;
    public static final int CALL_FAIL_BUSY = 17;
    public static final int CALL_FAIL_CONGESTION = 34;
    public static final int CALL_FAIL_INCOMPATIBILITY_DESTINATION = 88;
    public static final int CALL_FAIL_CALL_BARRED = 240;
    public static final int CALL_FAIL_FDN_BLOCKED = 241;
    public static final int CALL_FAIL_EMERGENCY_TEMP_FAILURE = 325;
    public static final int CALL_FAIL_EMERGENCY_PERM_FAILURE = 326;
    public static final int CALL_FAIL_HO_NOT_FEASIBLE = 382;
    public static final int CALL_FAIL_USER_BUSY = 501;
    public static final int CALL_FAIL_USER_REJECT = 502;
    public static final int CALL_FAIL_LOW_BATTERY = 503;
    public static final int CALL_FAIL_BLACKLISTED_CALL_ID = 504;
    public static final int CALL_FAIL_CS_RETRY_REQUIRED = 505;
    public static final int CALL_FAIL_NETWORK_UNAVAILABLE = 1010;
    public static final int CALL_FAIL_FEATURE_UNAVAILABLE = 1011;
    public static final int CALL_FAIL_SIP_Error = 1012;

    // SDK-30 ImsReasonInfo integer constants (kept pure-Java so CallStateEngine runs on host JVM)
    public static final int IMS_REASON_UNSPECIFIED = 0;
    public static final int IMS_REASON_LOCAL_ILLEGAL_ARGUMENT = 101;
    public static final int IMS_REASON_LOCAL_IMS_SERVICE_DOWN = 106;
    public static final int IMS_REASON_LOCAL_INTERNAL_ERROR = 103;
    public static final int IMS_REASON_LOCAL_NETWORK_NO_LTE_COVERAGE = 122;
    public static final int IMS_REASON_LOCAL_NOT_REGISTERED = 132;
    public static final int IMS_REASON_LOCAL_CALL_DECLINE = 143;
    public static final int IMS_REASON_LOCAL_CALL_CS_RETRY_REQUIRED = 146;
    public static final int IMS_REASON_TIMEOUT_NO_ANSWER = 335;
    public static final int IMS_REASON_SIP_BAD_REQUEST = 331;
    public static final int IMS_REASON_SIP_FORBIDDEN = 332;
    public static final int IMS_REASON_SIP_NOT_FOUND = 333;
    public static final int IMS_REASON_SIP_NOT_SUPPORTED = 334;
    public static final int IMS_REASON_SIP_REQUEST_TIMEOUT = 335;
    public static final int IMS_REASON_SIP_TEMPORARILY_UNAVAILABLE = 336;
    public static final int IMS_REASON_SIP_BAD_ADDRESS = 337;
    public static final int IMS_REASON_SIP_BUSY = 338;
    public static final int IMS_REASON_SIP_REQUEST_CANCELLED = 339;
    public static final int IMS_REASON_SIP_NOT_ACCEPTABLE = 340;
    public static final int IMS_REASON_SIP_NOT_REACHABLE = 341;
    public static final int IMS_REASON_SIP_SERVER_INTERNAL_ERROR = 351;
    public static final int IMS_REASON_SIP_SERVICE_UNAVAILABLE = 353;
    public static final int IMS_REASON_SIP_USER_REJECTED = 361;
    public static final int IMS_REASON_USER_TERMINATED = 501;
    public static final int IMS_REASON_LOW_BATTERY = 505;
    public static final int IMS_REASON_BLACKLISTED_CALL_ID = 506;
    public static final int IMS_REASON_USER_TERMINATED_BY_REMOTE = 510;
    public static final int IMS_REASON_CALL_BARRED = 240;
    public static final int IMS_REASON_FDN_BLOCKED = 241;

    // SDK-30 ImsStreamMediaProfile audio quality & direction constants
    public static final int AUDIO_QUALITY_NONE = 0;
    public static final int AUDIO_QUALITY_AMR = 1;
    public static final int AUDIO_QUALITY_AMR_WB = 2;
    public static final int AUDIO_QUALITY_QCELP13K = 3;
    public static final int AUDIO_QUALITY_EVRC = 4;
    public static final int AUDIO_QUALITY_EVRC_B = 5;
    public static final int AUDIO_QUALITY_EVRC_WB = 6;
    public static final int AUDIO_QUALITY_EVRC_NW = 7;
    public static final int AUDIO_QUALITY_GSM_EFR = 8;
    public static final int AUDIO_QUALITY_GSM_FR = 9;
    public static final int AUDIO_QUALITY_GSM_HR = 10;
    public static final int AUDIO_QUALITY_EVS_NB = 17;
    public static final int AUDIO_QUALITY_EVS_WB = 18;
    public static final int AUDIO_QUALITY_EVS_SWB = 19;
    public static final int AUDIO_QUALITY_EVS_FB = 20;

    public static final int DIRECTION_INACTIVE = 0;
    public static final int DIRECTION_INVALID = -1;
    public static final int DIRECTION_SEND_RECEIVE = 3;

    // 3GPP TS 27.007 +CLIP / RIL presentation codes carried on rild_ims0 Call.{numberPresentation,namePresentation}
    public static final int RIL_CLIP_ALLOWED = 0;
    public static final int RIL_CLIP_RESTRICTED = 1;
    public static final int RIL_CLIP_UNKNOWN = 2;
    public static final int RIL_CLIP_PAYPHONE = 3;

    // Android PhoneConstants.PRESENTATION_* codes (returned by DriverCall.presentationFromCLIP)
    public static final int ANDROID_PRESENTATION_ALLOWED = 1;
    public static final int ANDROID_PRESENTATION_RESTRICTED = 2;
    public static final int ANDROID_PRESENTATION_UNKNOWN = 3;
    public static final int ANDROID_PRESENTATION_PAYPHONE = 4;

    // Android ImsCallProfile.OIR_* codes (stored in EXTRA_OIR / EXTRA_CNAP and read via ImsCallProfile.OIRToPresentation)
    public static final int OIR_DEFAULT = 0;
    public static final int OIR_PRESENTATION_RESTRICTED = 1;
    public static final int OIR_PRESENTATION_NOT_RESTRICTED = 2;
    public static final int OIR_PRESENTATION_UNKNOWN = 3;
    public static final int OIR_PRESENTATION_PAYPHONE = 4;

    // 3GPP TS 24.008 Type-of-Address constants (PhoneNumberUtils.TOA_*)
    public static final int TOA_UNKNOWN = 129;       // 0x81
    public static final int TOA_INTERNATIONAL = 145; // 0x91

    public interface TimeoutSetter {
        void setTimeout(int timeoutMs) throws IOException;
    }

    private RilImsWire() { }

    public static final class Frame {
        public final int token;
        public final int type;
        public final int id;
        public final int error;
        private final byte[] payload;

        public Frame(int token, int type, int id, int error, byte[] payload) {
            this.token = token;
            this.type = type;
            this.id = id;
            this.error = error;
            this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        }

        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        public int payloadLength() {
            return payload.length;
        }
    }

    public static final class RegObservation {
        public final int state; // REG_STATE_* or -1
        public final Integer errorCode;
        public final Integer radioTech;

        public RegObservation(int state, Integer errorCode, Integer radioTech) {
            this.state = state;
            this.errorCode = errorCode;
            this.radioTech = radioTech;
        }

        public boolean isRegistered() {
            return state == REG_STATE_REGISTERED;
        }

        public boolean isRegistering() {
            return state == REG_STATE_REGISTERING;
        }
    }

    public static final class AccessTech {
        public final Integer networkMode;
        public final Integer status;
        public final Integer restrictCause;
        public final RegObservation registration;

        public AccessTech(Integer networkMode, Integer status, Integer restrictCause, RegObservation registration) {
            this.networkMode = networkMode;
            this.status = status;
            this.restrictCause = restrictCause;
            this.registration = registration;
        }
    }

    public static final class ServiceInfo {
        public final Boolean isValid;
        public final Integer type;
        public final Integer callType;
        public final Integer status;
        public final List<AccessTech> access;

        public ServiceInfo(Boolean isValid, Integer type, Integer callType, Integer status, List<AccessTech> access) {
            this.isValid = isValid;
            this.type = type;
            this.callType = callType;
            this.status = status;
            this.access = Collections.unmodifiableList(new ArrayList<>(access));
        }
    }

    public static final class ServiceStatusSnapshot {
        public final List<ServiceInfo> services;

        public ServiceStatusSnapshot(List<ServiceInfo> services) {
            this.services = Collections.unmodifiableList(new ArrayList<>(services));
        }

        public boolean isVoiceEnabledOnLte() {
            for (ServiceInfo s : services) {
                if (s.callType != null && s.callType == IMS_CALL_TYPE_VOICE) {
                    if (Boolean.FALSE.equals(s.isValid)) continue;
                    for (AccessTech a : s.access) {
                        if (a.networkMode != null && a.networkMode == RADIO_TECH_LTE
                                && a.status != null
                                && (a.status == STATUS_ENABLED || a.status == STATUS_PARTIALLY_ENABLED)) {
                            return true;
                        }
                    }
                    if (s.status != null && (s.status == STATUS_ENABLED || s.status == STATUS_PARTIALLY_ENABLED)
                            && s.access.isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        public boolean anyRegisteredAccess() {
            for (ServiceInfo s : services) {
                for (AccessTech a : s.access) {
                    if (a.registration != null && a.registration.isRegistered()) {
                        return true;
                    }
                }
            }
            return false;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (ServiceInfo s : services) {
                sb.append("ct=").append(s.callType).append("/st=").append(s.status).append('[');
                for (AccessTech a : s.access) {
                    sb.append(a.networkMode).append('/').append(a.status).append('/')
                            .append(a.registration == null ? "-" : a.registration.state).append(',');
                }
                sb.append("];");
            }
            return sb.toString();
        }
    }

    public static final class DriverCall {
        public final int index;
        public final int state;
        public final int toa;
        public final boolean isMt;
        public final boolean isMpty;
        public final boolean isVoice;
        public final String number;
        public final int numberPresentation;
        public final String name;
        public final int namePresentation;
        public final int callType;
        public final int callDomain;
        public final int callSubstate;
        public final int mediaId;
        public final List<String> extras;
        public final Integer failCause;

        public DriverCall(
                int index,
                int state,
                boolean isMt,
                boolean isMpty,
                boolean isVoice,
                String number,
                int numberPresentation,
                String name,
                int namePresentation,
                int callType,
                int callDomain,
                int callSubstate,
                int mediaId,
                List<String> extras,
                Integer failCause) {
            this(index, state, 0, isMt, isMpty, isVoice, number, numberPresentation,
                    name, namePresentation, callType, callDomain, callSubstate, mediaId, extras, failCause);
        }

        public DriverCall(
                int index,
                int state,
                int toa,
                boolean isMt,
                boolean isMpty,
                boolean isVoice,
                String number,
                int numberPresentation,
                String name,
                int namePresentation,
                int callType,
                int callDomain,
                int callSubstate,
                int mediaId,
                List<String> extras,
                Integer failCause) {
            this.index = index;
            this.state = state;
            this.toa = toa;
            this.isMt = isMt;
            this.isMpty = isMpty;
            this.isVoice = isVoice;
            this.number = formatNumberWithToa(number, toa);
            this.numberPresentation = numberPresentation;
            this.name = name == null ? "" : name;
            this.namePresentation = namePresentation;
            this.callType = callType;
            this.callDomain = callDomain;
            this.callSubstate = callSubstate;
            this.mediaId = mediaId;
            this.extras = Collections.unmodifiableList(new ArrayList<>(extras));
            this.failCause = failCause;
        }

        public int audioQualityFromExtras() {
            for (String kv : extras) {
                if (kv == null) continue;
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq).trim();
                int colon = k.lastIndexOf(':');
                if (colon >= 0) k = k.substring(colon + 1);
                if ("Codec".equalsIgnoreCase(k)) {
                    return mapCodecToAudioQuality(kv.substring(eq + 1).trim());
                }
            }
            return AUDIO_QUALITY_AMR_WB;
        }
    }

    public static final class SuppSvcInfo {
        public final int notificationType;
        public final int code;
        public final int index;
        public final int type;
        public final String number;
        public final int connId;
        public final String historyInfo;

        public SuppSvcInfo(int notificationType, int code, int index, int type, String number, int connId, String historyInfo) {
            this.notificationType = notificationType;
            this.code = code;
            this.index = index;
            this.type = type;
            this.number = number == null ? "" : number;
            this.connId = connId;
            this.historyInfo = historyInfo == null ? "" : historyInfo;
        }
    }

    /**
     * Mirrors PhoneNumberUtils.stringFromStringAndTOA(String, int) used in stock
     * Passport org.codeaurora.ims.ImsSenderRxr.responseCallList(byte[]).
     */
    public static String formatNumberWithToa(String rawNumber, int toa) {
        if (rawNumber == null || rawNumber.isEmpty()) {
            return "";
        }
        if (toa == TOA_INTERNATIONAL && rawNumber.charAt(0) != '+' && rawNumber.indexOf(':') < 0) {
            return "+" + rawNumber;
        }
        return rawNumber;
    }

    /**
     * Mirrors com.android.internal.telephony.DriverCall.presentationFromCLIP(int):
     * RIL CLIP 0..3 -> Android PhoneConstants.PRESENTATION_* 1..4.
     */
    public static int clipToAndroidPresentation(int rilClip) {
        switch (rilClip) {
            case RIL_CLIP_ALLOWED:
                return ANDROID_PRESENTATION_ALLOWED;
            case RIL_CLIP_RESTRICTED:
                return ANDROID_PRESENTATION_RESTRICTED;
            case RIL_CLIP_UNKNOWN:
                return ANDROID_PRESENTATION_UNKNOWN;
            case RIL_CLIP_PAYPHONE:
                return ANDROID_PRESENTATION_PAYPHONE;
            default:
                return ANDROID_PRESENTATION_UNKNOWN;
        }
    }

    /**
     * Converts RIL CLIP (0..3) to Android ImsCallProfile.OIR_* (1..4) matching stock
     * Passport ImsSenderRxr.responseCallList + ImsCallSessionImpl.updateImsCallProfile
     * and LineageOS 18.1 ImsPhoneConnection (ImsCallProfile.OIRToPresentation):
     * - RIL 0 (ALLOWED) -> OIR_PRESENTATION_NOT_RESTRICTED (2) -> OIRToPresentation(2) == 1 (ALLOWED)
     * - RIL 1 (RESTRICTED) -> OIR_PRESENTATION_RESTRICTED (1) -> OIRToPresentation(1) == 2 (RESTRICTED)
     * - RIL 2 (UNKNOWN) -> OIR_PRESENTATION_UNKNOWN (3) -> OIRToPresentation(3) == 3 (UNKNOWN)
     * - RIL 3 (PAYPHONE) -> OIR_PRESENTATION_PAYPHONE (4) -> OIRToPresentation(4) == 4 (PAYPHONE)
     *
     * If isNumberField is true and value is empty while rilClip == 0, returns OIR_PRESENTATION_UNKNOWN (3)
     * so an empty number string is never presented as ALLOWED.
     */
    public static int clipToOir(int rilClip, String value, boolean isNumberField) {
        if (isNumberField && (value == null || value.isEmpty()) && rilClip == RIL_CLIP_ALLOWED) {
            return OIR_PRESENTATION_UNKNOWN;
        }
        switch (clipToAndroidPresentation(rilClip)) {
            case ANDROID_PRESENTATION_ALLOWED:
                return OIR_PRESENTATION_NOT_RESTRICTED;
            case ANDROID_PRESENTATION_RESTRICTED:
                return OIR_PRESENTATION_RESTRICTED;
            case ANDROID_PRESENTATION_PAYPHONE:
                return OIR_PRESENTATION_PAYPHONE;
            case ANDROID_PRESENTATION_UNKNOWN:
            default:
                return OIR_PRESENTATION_UNKNOWN;
        }
    }

    public static boolean isReceiveTimeout(IOException ex) {
        if (ex instanceof SocketTimeoutException) return true;
        String msg = ex.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("try again")
                || lower.contains("temporarily unavailable")
                || lower.contains("timed out");
    }

    public static byte[] receiveRawFrameOrNull(
            InputStream in, TimeoutSetter setter, int firstByteTimeoutMs, int bodyTimeoutMs)
            throws IOException {
        if (setter != null) setter.setTimeout(firstByteTimeoutMs);
        int b0;
        try {
            b0 = in.read();
        } catch (IOException ex) {
            if (isReceiveTimeout(ex)) return null;
            throw ex;
        }
        if (b0 < 0) throw new EOFException("rild_ims0 EOF");

        if (setter != null) setter.setTimeout(bodyTimeoutMs);
        byte[] restLen = readExact(in, 3);
        long frameBodyLen = ((long) (b0 & 0xff) << 24)
                | ((long) (restLen[0] & 0xff) << 16)
                | ((long) (restLen[1] & 0xff) << 8)
                | ((long) (restLen[2] & 0xff));
        if (frameBodyLen < 2 || frameBodyLen > MAX_FRAME_BYTES - 4) {
            throw new IOException("Invalid rild_ims0 frame length: " + frameBodyLen);
        }
        byte[] body = readExact(in, (int) frameBodyLen);
        byte[] wire = new byte[(int) frameBodyLen + 4];
        wire[0] = (byte) b0;
        System.arraycopy(restLen, 0, wire, 1, 3);
        System.arraycopy(body, 0, wire, 4, body.length);
        return wire;
    }

    public static Frame decodeFrame(byte[] wire) throws IOException {
        if (wire == null || wire.length < 6 || wire.length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid wire buffer length");
        }
        long bodyLen = ((long) (wire[0] & 0xff) << 24)
                | ((long) (wire[1] & 0xff) << 16)
                | ((long) (wire[2] & 0xff) << 8)
                | ((long) (wire[3] & 0xff));
        if (bodyLen + 4 != wire.length) {
            throw new IOException("Frame size header mismatch: header=" + bodyLen + " total=" + wire.length);
        }
        int tagLen = wire[4] & 0xff;
        if (tagLen < 1 || 5 + tagLen > wire.length) {
            throw new IOException("Invalid MsgTag length: " + tagLen);
        }
        byte[] tagBytes = Arrays.copyOfRange(wire, 5, 5 + tagLen);
        byte[] payload = Arrays.copyOfRange(wire, 5 + tagLen, wire.length);

        ProtoReader p = new ProtoReader(tagBytes);
        Integer token = null, type = null, id = null, error = null;
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 1) {
                token = p.readIntFlexible(wt);
            } else if (field == 2) {
                type = p.readIntFlexible(wt);
            } else if (field == 3) {
                id = p.readIntFlexible(wt);
            } else if (field == 4) {
                error = p.readIntFlexible(wt);
            } else {
                p.skip(wt);
            }
        }
        if (token == null || type == null || id == null || error == null) {
            throw new IOException("Incomplete MsgTag");
        }
        return new Frame(token, type, id, error, payload);
    }

    public static byte[] encodeFrame(int token, int type, int id, int error, byte[] payload) {
        byte[] bodyPayload = payload == null ? new byte[0] : payload;
        byte[] tag = new ProtoWriter()
                .fixed32(1, token)
                .varint(2, type)
                .varint(3, id)
                .varint(4, error)
                .toByteArray();
        int bodyLen = 1 + tag.length + bodyPayload.length;
        byte[] out = new byte[4 + bodyLen];
        out[0] = (byte) ((bodyLen >>> 24) & 0xff);
        out[1] = (byte) ((bodyLen >>> 16) & 0xff);
        out[2] = (byte) ((bodyLen >>> 8) & 0xff);
        out[3] = (byte) (bodyLen & 0xff);
        out[4] = (byte) tag.length;
        System.arraycopy(tag, 0, out, 5, tag.length);
        System.arraycopy(bodyPayload, 0, out, 5 + tag.length, bodyPayload.length);
        return out;
    }

    public static byte[] encodeRequest(int token, int id, byte[] payload) {
        return encodeFrame(token, MSG_TYPE_REQUEST, id, 0, payload);
    }

    public static byte[] encodeEmptyRequest(int token, int id) {
        return encodeRequest(token, id, new byte[0]);
    }

    public static String normalizeDialAddress(String rawCallee) {
        if (rawCallee == null) return "";
        String trimmed = rawCallee.trim();
        if (trimmed.regionMatches(true, 0, "sip:", 0, 4)) {
            return trimmed;
        }
        if (trimmed.regionMatches(true, 0, "tel:", 0, 4)) {
            trimmed = trimmed.substring(4).trim();
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '+' && sb.length() == 0) {
                sb.append(c);
            } else if ((c >= '0' && c <= '9') || c == '*' || c == '#' || c == ',' || c == ';'
                    || (c >= 'A' && c <= 'D') || (c >= 'a' && c <= 'd')) {
                sb.append(c);
            }
            // Strip visual formatting separators: spaces, '-', '(', ')', '.', NBSP, etc.
        }
        return sb.toString();
    }

    public static byte[] encodeDial(int token, String rawAddress, int clirMode, int callDomain) {
        String clean = normalizeDialAddress(rawAddress);
        if (clean.isEmpty() || clean.length() > 128) {
            throw new IllegalArgumentException("Invalid dial address length");
        }
        int safeClir = (clirMode >= 0 && clirMode <= 2) ? clirMode : 0;
        int safeDomain = (callDomain == IMS_CALL_DOMAIN_PS || callDomain == IMS_CALL_DOMAIN_AUTOMATIC)
                ? callDomain : IMS_CALL_DOMAIN_PS;
        byte[] details = new ProtoWriter()
                .varint(1, IMS_CALL_TYPE_VOICE)
                .varint(2, safeDomain)
                .toByteArray();
        ProtoWriter dial = new ProtoWriter()
                .string(1, clean)
                .fixed32(2, safeClir)
                .bytes(3, details);
        return encodeRequest(token, REQ_DIAL, dial.toByteArray());
    }

    public static byte[] encodeAnswer(int token) {
        byte[] body = new ProtoWriter().varint(1, IMS_CALL_TYPE_VOICE).toByteArray();
        return encodeRequest(token, REQ_ANSWER, body);
    }

    public static byte[] encodeHangup(int token, int callIndex, boolean userReject, int rejectFailCause) {
        if (callIndex < 1 || callIndex > 255) {
            throw new IllegalArgumentException("Invalid callIndex: " + callIndex);
        }
        ProtoWriter w = new ProtoWriter().fixed32(1, callIndex);
        if (userReject) {
            int cause = rejectFailCause > 0 ? rejectFailCause : CALL_FAIL_USER_REJECT;
            byte[] failResp = new ProtoWriter().varint(1, cause).toByteArray();
            w.bytes(5, failResp);
        }
        return encodeRequest(token, REQ_HANGUP, w.toByteArray());
    }

    public static byte[] encodeHold(int token, int callIndex) {
        if (callIndex < 1 || callIndex > 255) {
            throw new IllegalArgumentException("Invalid callIndex: " + callIndex);
        }
        return encodeRequest(token, REQ_HOLD, new ProtoWriter().fixed32(1, callIndex).toByteArray());
    }

    public static byte[] encodeResume(int token, int callIndex) {
        if (callIndex < 1 || callIndex > 255) {
            throw new IllegalArgumentException("Invalid callIndex: " + callIndex);
        }
        return encodeRequest(token, REQ_RESUME, new ProtoWriter().fixed32(1, callIndex).toByteArray());
    }

    public static byte[] encodeDtmf(int token, char digit, boolean startContinuous) {
        char upper = Character.toUpperCase(digit);
        if ("0123456789*#ABCD".indexOf(upper) < 0) {
            throw new IllegalArgumentException("Unsupported DTMF digit");
        }
        int reqId = startContinuous ? REQ_DTMF_START : REQ_DTMF;
        byte[] body = new ProtoWriter().string(1, String.valueOf(upper)).toByteArray();
        return encodeRequest(token, reqId, body);
    }

    public static byte[] encodeStopDtmf(int token) {
        return encodeEmptyRequest(token, REQ_DTMF_STOP);
    }

    public static RegObservation decodeRegistration(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return new RegObservation(-1, null, null);
        }
        ProtoReader p = new ProtoReader(payload);
        int state = -1;
        Integer errCode = null;
        Integer radioTech = null;
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 1) {
                state = p.readIntFlexible(wt);
            } else if (field == 2) {
                errCode = p.readIntFlexible(wt);
            } else if (field == 4) {
                radioTech = p.readIntFlexible(wt);
            } else {
                p.skip(wt);
            }
        }
        return new RegObservation(state, errCode, radioTech);
    }

    public static ServiceStatusSnapshot decodeServiceStatusList(byte[] payload) throws IOException {
        List<ServiceInfo> list = new ArrayList<>();
        if (payload == null || payload.length == 0) {
            return new ServiceStatusSnapshot(list);
        }
        ProtoReader p = new ProtoReader(payload);
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 2 && wt == 2) {
                list.add(decodeSingleServiceInfo(p.readBytes()));
            } else {
                p.skip(wt);
            }
        }
        return new ServiceStatusSnapshot(list);
    }

    private static ServiceInfo decodeSingleServiceInfo(byte[] bytes) throws IOException {
        ProtoReader p = new ProtoReader(bytes);
        Boolean isValid = null;
        Integer type = null, callType = null, status = null;
        List<AccessTech> access = new ArrayList<>();
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            switch (field) {
                case 1: isValid = p.readIntFlexible(wt) != 0; break;
                case 2: type = p.readIntFlexible(wt); break;
                case 3: callType = p.readIntFlexible(wt); break;
                case 4: status = p.readIntFlexible(wt); break;
                case 7:
                    if (wt == 2) access.add(decodeAccessTech(p.readBytes()));
                    else p.skip(wt);
                    break;
                default: p.skip(wt); break;
            }
        }
        return new ServiceInfo(isValid, type, callType, status, access);
    }

    private static AccessTech decodeAccessTech(byte[] bytes) throws IOException {
        ProtoReader p = new ProtoReader(bytes);
        Integer mode = null, status = null, restrict = null;
        RegObservation reg = null;
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            switch (field) {
                case 1: mode = p.readIntFlexible(wt); break;
                case 2: status = p.readIntFlexible(wt); break;
                case 3: restrict = p.readIntFlexible(wt); break;
                case 4:
                    if (wt == 2) reg = decodeRegistration(p.readBytes());
                    else p.skip(wt);
                    break;
                default: p.skip(wt); break;
            }
        }
        return new AccessTech(mode, status, restrict, reg);
    }

    public static List<DriverCall> decodeCallList(byte[] payload) throws IOException {
        List<DriverCall> out = new ArrayList<>();
        if (payload == null || payload.length == 0) {
            return out;
        }
        ProtoReader p = new ProtoReader(payload);
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 2 && wt == 2) {
                DriverCall dc = decodeSingleCall(p.readBytes());
                if (dc != null) out.add(dc);
            } else {
                p.skip(wt);
            }
        }
        return out;
    }

    private static DriverCall decodeSingleCall(byte[] bytes) throws IOException {
        ProtoReader p = new ProtoReader(bytes);
        Integer state = null, index = null;
        int toa = 0;
        boolean isMpty = false, isMt = false, isVoice = true;
        int np = RIL_CLIP_ALLOWED, cp = RIL_CLIP_ALLOWED;
        String number = "", name = "";
        int callType = IMS_CALL_TYPE_VOICE, callDomain = IMS_CALL_DOMAIN_PS, substate = 0, mediaId = -1;
        List<String> extras = new ArrayList<>();
        Integer failCause = null;

        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            switch (field) {
                case 1: state = p.readIntFlexible(wt); break;
                case 2: index = p.readIntFlexible(wt); break;
                case 3: toa = p.readIntFlexible(wt); break;
                case 4: isMpty = p.readIntFlexible(wt) != 0; break;
                case 5: isMt = p.readIntFlexible(wt) != 0; break;
                case 7: isVoice = p.readIntFlexible(wt) != 0; break;
                case 9:
                    if (wt == 2) number = p.readStringBounded(1024);
                    else p.skip(wt);
                    break;
                case 10: np = p.readIntFlexible(wt); break;
                case 11:
                    if (wt == 2) name = p.readStringBounded(1024);
                    else p.skip(wt);
                    break;
                case 12: cp = p.readIntFlexible(wt); break;
                case 13:
                    if (wt == 2) {
                        ProtoReader dp = new ProtoReader(p.readBytes());
                        while (dp.hasMore()) {
                            int dk = dp.readKey();
                            int df = dk >>> 3;
                            int dw = dk & 7;
                            switch (df) {
                                case 1: callType = dp.readIntFlexible(dw); break;
                                case 2: callDomain = dp.readIntFlexible(dw); break;
                                case 4:
                                    if (dw == 2 && extras.size() < 32) {
                                        extras.add(dp.readStringBounded(512));
                                    } else {
                                        dp.skip(dw);
                                    }
                                    break;
                                case 7: substate = dp.readIntFlexible(dw); break;
                                case 8: mediaId = dp.readIntFlexible(dw); break;
                                default: dp.skip(dw); break;
                            }
                        }
                    } else {
                        p.skip(wt);
                    }
                    break;
                case 14:
                    if (wt == 2) failCause = decodeCallFailCause(p.readBytes());
                    else p.skip(wt);
                    break;
                default: p.skip(wt); break;
            }
        }
        if (index == null || index < 1 || index > 255 || state == null || state < 0 || state > CALL_STATE_END) {
            return null;
        }
        return new DriverCall(
                index, state, toa, isMt, isMpty, isVoice,
                number, np, name, cp,
                callType, callDomain, substate, mediaId, extras, failCause);
    }

    public static Integer decodeCallFailCause(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return null;
        ProtoReader p = new ProtoReader(payload);
        Integer cause = null;
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 1) {
                cause = p.readIntFlexible(wt);
            } else {
                p.skip(wt);
            }
        }
        return cause;
    }

    public static boolean decodeRingbackTonePlayLocal(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return false;
        ProtoReader p = new ProtoReader(payload);
        int flag = 0;
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 1) {
                flag = p.readIntFlexible(wt);
            } else {
                p.skip(wt);
            }
        }
        return flag == 1;
    }

    public static int decodeRadioState(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return RADIO_STATE_ON;
        ProtoReader p = new ProtoReader(payload);
        int state = RADIO_STATE_ON;
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            if (field == 1) {
                state = p.readIntFlexible(wt);
            } else {
                p.skip(wt);
            }
        }
        return state;
    }

    public static SuppSvcInfo decodeSuppSvcNotification(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return null;
        ProtoReader p = new ProtoReader(payload);
        int notifType = 0, code = 0, index = 0, type = 0, connId = 0;
        String number = "", history = "";
        while (p.hasMore()) {
            int key = p.readKey();
            int field = key >>> 3;
            int wt = key & 7;
            switch (field) {
                case 1: notifType = p.readIntFlexible(wt); break;
                case 2: code = p.readIntFlexible(wt); break;
                case 3: index = p.readIntFlexible(wt); break;
                case 4: type = p.readIntFlexible(wt); break;
                case 5:
                    if (wt == 2) number = p.readStringBounded(256);
                    else p.skip(wt);
                    break;
                case 6: connId = p.readIntFlexible(wt); break;
                case 7:
                    if (wt == 2) history = p.readStringBounded(512);
                    else p.skip(wt);
                    break;
                default: p.skip(wt); break;
            }
        }
        return new SuppSvcInfo(notifType, code, index, type, number, connId, history);
    }

    public static int mapFailCauseToImsReason(Integer vendorCause, int rilError, boolean locallyTerminated) {
        if (locallyTerminated) {
            return IMS_REASON_USER_TERMINATED;
        }
        if (vendorCause != null) {
            int c = vendorCause;
            switch (c) {
                case CALL_FAIL_NORMAL:
                    return IMS_REASON_USER_TERMINATED_BY_REMOTE;
                case CALL_FAIL_BUSY:
                case CALL_FAIL_USER_BUSY:
                    return IMS_REASON_SIP_BUSY;
                case CALL_FAIL_USER_REJECT:
                    return IMS_REASON_SIP_USER_REJECTED;
                case CALL_FAIL_UNOBTAINABLE_NUMBER:
                    return IMS_REASON_SIP_NOT_FOUND;
                case CALL_FAIL_CALL_BARRED:
                    return IMS_REASON_CALL_BARRED;
                case CALL_FAIL_FDN_BLOCKED:
                    return IMS_REASON_FDN_BLOCKED;
                case CALL_FAIL_LOW_BATTERY:
                    return IMS_REASON_LOW_BATTERY;
                case CALL_FAIL_BLACKLISTED_CALL_ID:
                    return IMS_REASON_BLACKLISTED_CALL_ID;
                case CALL_FAIL_CS_RETRY_REQUIRED:
                case IMS_REASON_LOCAL_CALL_CS_RETRY_REQUIRED:
                    return IMS_REASON_LOCAL_CALL_CS_RETRY_REQUIRED;
                case CALL_FAIL_NETWORK_UNAVAILABLE:
                    return IMS_REASON_LOCAL_NETWORK_NO_LTE_COVERAGE;
                case CALL_FAIL_FEATURE_UNAVAILABLE:
                    return IMS_REASON_LOCAL_NOT_REGISTERED;
                default:
                    // Pass directly if already in standard ImsReasonInfo SIP/local range (100..600)
                    if (c >= 100 && c <= 600) return c;
                    break;
            }
        }
        if (rilError == 28) return IMS_REASON_SIP_USER_REJECTED;
        if (rilError == 27) return IMS_REASON_LOCAL_ILLEGAL_ARGUMENT;
        if (rilError == 1) return IMS_REASON_LOCAL_IMS_SERVICE_DOWN;
        if (rilError != 0) return IMS_REASON_LOCAL_INTERNAL_ERROR;
        return IMS_REASON_UNSPECIFIED;
    }

    public static int mapCodecToAudioQuality(String codec) {
        if (codec == null || codec.isEmpty()) return AUDIO_QUALITY_AMR_WB;
        String upper = codec.toUpperCase(Locale.ROOT);
        if (upper.contains("EVS_FB")) return AUDIO_QUALITY_EVS_FB;
        if (upper.contains("EVS_SWB")) return AUDIO_QUALITY_EVS_SWB;
        if (upper.contains("EVS_WB")) return AUDIO_QUALITY_EVS_WB;
        if (upper.contains("EVS")) return AUDIO_QUALITY_EVS_NB;
        if (upper.contains("AMR_WB") || upper.contains("AMR-WB")) return AUDIO_QUALITY_AMR_WB;
        if (upper.contains("AMR")) return AUDIO_QUALITY_AMR;
        if (upper.contains("GSM_EFR")) return AUDIO_QUALITY_GSM_EFR;
        if (upper.contains("GSM_FR")) return AUDIO_QUALITY_GSM_FR;
        if (upper.contains("GSM_HR")) return AUDIO_QUALITY_GSM_HR;
        if (upper.contains("EVRC_NW")) return AUDIO_QUALITY_EVRC_NW;
        if (upper.contains("EVRC_WB")) return AUDIO_QUALITY_EVRC_WB;
        if (upper.contains("EVRC_B")) return AUDIO_QUALITY_EVRC_B;
        if (upper.contains("EVRC")) return AUDIO_QUALITY_EVRC;
        if (upper.contains("QCELP")) return AUDIO_QUALITY_QCELP13K;
        return AUDIO_QUALITY_AMR_WB;
    }

    /** Redacts any sequence of 5+ digits or URI user-parts so logs/dumpsys never expose PII. */
    public static String redactPii(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        int runStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digitLike = (c >= '0' && c <= '9');
            if (digitLike) {
                if (runStart < 0) runStart = i;
            } else {
                if (runStart >= 0) {
                    int len = i - runStart;
                    if (len >= 5) {
                        out.append("<redacted-").append(len).append("d>");
                    } else {
                        out.append(text, runStart, i);
                    }
                    runStart = -1;
                }
                out.append(c);
            }
        }
        if (runStart >= 0) {
            int len = text.length() - runStart;
            if (len >= 5) {
                out.append("<redacted-").append(len).append("d>");
            } else {
                out.append(text, runStart, text.length());
            }
        }
        return out.toString();
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int got = in.read(buf, off, n - off);
            if (got < 0) throw new EOFException("Unexpected EOF reading frame body");
            if (got == 0) throw new IOException("Zero-byte read on stream");
            off += got;
        }
        return buf;
    }

    static final class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        ProtoWriter varint(int field, long value) {
            writeRawVarint((long) field << 3);
            writeRawVarint(value);
            return this;
        }

        ProtoWriter fixed32(int field, int value) {
            writeRawVarint(((long) field << 3) | 5L);
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
            out.write((value >>> 16) & 0xff);
            out.write((value >>> 24) & 0xff);
            return this;
        }

        ProtoWriter bytes(int field, byte[] data) {
            writeRawVarint(((long) field << 3) | 2L);
            writeRawVarint(data.length);
            out.write(data, 0, data.length);
            return this;
        }

        ProtoWriter string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private void writeRawVarint(long value) {
            long v = value;
            while ((v & ~0x7fL) != 0L) {
                out.write(((int) v & 0x7f) | 0x80);
                v >>>= 7;
            }
            out.write((int) v);
        }
    }

    static final class ProtoReader {
        private final byte[] buf;
        private int pos;

        ProtoReader(byte[] buf) {
            this.buf = buf;
        }

        boolean hasMore() {
            return pos < buf.length;
        }

        int readKey() throws IOException {
            long k = readVarint64();
            if (k <= 0 || k > Integer.MAX_VALUE) {
                throw new IOException("Invalid protobuf key: " + k);
            }
            return (int) k;
        }

        int readIntFlexible(int wireType) throws IOException {
            if (wireType == 0) {
                return (int) readVarint64();
            } else if (wireType == 5) {
                return readFixed32();
            } else {
                throw new IOException("Expected integer wireType (0 or 5), got " + wireType);
            }
        }

        int readFixed32() throws IOException {
            if (pos + 4 > buf.length) throw new EOFException("Truncated fixed32");
            int v = (buf[pos] & 0xff)
                    | ((buf[pos + 1] & 0xff) << 8)
                    | ((buf[pos + 2] & 0xff) << 16)
                    | ((buf[pos + 3] & 0xff) << 24);
            pos += 4;
            return v;
        }

        long readVarint64() throws IOException {
            long result = 0L;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= buf.length) throw new EOFException("Truncated varint");
                int b = buf[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("Malformed varint");
        }

        byte[] readBytes() throws IOException {
            long len = readVarint64();
            if (len < 0 || len > buf.length - pos) {
                throw new IOException("Invalid length-delimited field length: " + len);
            }
            byte[] slice = Arrays.copyOfRange(buf, pos, pos + (int) len);
            pos += (int) len;
            return slice;
        }

        String readStringBounded(int maxChars) throws IOException {
            String s = new String(readBytes(), StandardCharsets.UTF_8);
            if (s.length() > maxChars) {
                return s.substring(0, maxChars);
            }
            return s;
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0:
                    readVarint64();
                    break;
                case 1:
                    if (pos + 8 > buf.length) throw new EOFException("Truncated fixed64");
                    pos += 8;
                    break;
                case 2:
                    long len = readVarint64();
                    if (len < 0 || len > buf.length - pos) throw new EOFException("Truncated bytes skip");
                    pos += (int) len;
                    break;
                case 5:
                    if (pos + 4 > buf.length) throw new EOFException("Truncated fixed32 skip");
                    pos += 4;
                    break;
                default:
                    throw new IOException("Unsupported protobuf wireType: " + wireType);
            }
        }
    }
}
