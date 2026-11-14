package org.octolab.qcom32ims;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.passport.imsbridge.legacy.CallObservation;
import org.passport.imsbridge.legacy.LegacyCommands;
import org.passport.imsbridge.legacy.LegacyFrameCodec;
import org.passport.imsbridge.legacy.RegistrationObservation;
import org.passport.imsbridge.legacy.ServiceStatusObservation;

/**
 * Pure-Java JDK-17 host qualification suite for Qcom32VolteIms v2.3.
 *
 * Exercises the exact production bytecode of RilImsWire, RilImsCoreClient, and CallStateEngine,
 * cross-checks wire frames byte-for-byte against frozen org.passport.imsbridge.legacy codecs,
 * and verifies incoming Caller ID CLIP->OIR mapping, TOA=145 E.164 formatting, and mid-ring updates.
 */
public final class Qcom32VolteImsHostTest {
    private static int assertions = 0;

    private static void check(boolean cond, String msg) {
        assertions++;
        if (!cond) {
            throw new AssertionError("Assertion failed (#" + assertions + "): " + msg);
        }
    }

    private static void checkBytesEqual(byte[] actual, byte[] expected, String label) {
        assertions++;
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError(label + " byte mismatch: actual=" + Arrays.toString(actual)
                    + " expected=" + Arrays.toString(expected));
        }
    }

    public static void main(String[] args) throws Exception {
        testWireParityWithFrozenLegacyCodec();
        testCallerIdClipToOirAndToaCodec();
        testCoreClientUnsolCoalescingAndReconnect();
        testCallStateEngineLifecycles();
        testQmiProbeSummaryParserAndZeroPiiSanitizer();
        System.out.println("PASS; assertions=" + assertions + "; forbidden_26_30=0");
    }

    private static void testWireParityWithFrozenLegacyCodec() throws Exception {
        // 1. Query frame byte-for-byte parity (1, 29, 6)
        for (int id : new int[] { 1, 29, 6 }) {
            int tok = 0x12345678 + id;
            byte[] ours = RilImsWire.encodeEmptyRequest(tok, id);
            byte[] frozen = LegacyFrameCodec.encodeQuery(tok, id);
            checkBytesEqual(ours, frozen, "query_" + id);

            LegacyFrameCodec.Frame lf = LegacyFrameCodec.read(new ByteArrayInputStream(ours));
            RilImsWire.Frame rf = RilImsWire.decodeFrame(ours);
            check(lf.token == tok && rf.token == tok, "token match");
            check(lf.id == id && rf.id == id, "id match");
            check(lf.type == 1 && rf.type == RilImsWire.MSG_TYPE_REQUEST, "type match");
            check(lf.error == 0 && rf.error == 0, "error match");
            check(rf.payloadLength() == 0, "empty payload");
        }

        // 2. Dial, Answer, Hangup (normal & reject 502), Hold, Resume, DTMF, StopDTMF parity
        checkBytesEqual(
                RilImsWire.encodeDial(42, "+86 186-0000-1234", 0, RilImsWire.IMS_CALL_DOMAIN_PS),
                LegacyCommands.dial(42, "+8618600001234", 2, 0),
                "dial_ps_clir0");
        checkBytesEqual(
                RilImsWire.encodeDial(43, "tel:10010", 1, RilImsWire.IMS_CALL_DOMAIN_PS),
                LegacyCommands.dial(43, "10010", 2, 1),
                "dial_tel_uri_clir1");
        checkBytesEqual(
                RilImsWire.encodeAnswer(44),
                LegacyCommands.answer(44),
                "answer");
        checkBytesEqual(
                RilImsWire.encodeHangup(45, 1, false, 0),
                LegacyCommands.hangup(45, 1, false),
                "hangup_normal");
        checkBytesEqual(
                RilImsWire.encodeHangup(46, 2, true, RilImsWire.CALL_FAIL_USER_REJECT),
                LegacyCommands.hangup(46, 2, true),
                "hangup_reject_502");
        checkBytesEqual(
                RilImsWire.encodeHold(47, 1),
                LegacyCommands.hold(47, 1),
                "hold");
        checkBytesEqual(
                RilImsWire.encodeResume(48, 1),
                LegacyCommands.resume(48, 1),
                "resume");
        for (char d : new char[] { '0', '9', '*', '#', 'A' }) {
            checkBytesEqual(
                    RilImsWire.encodeDtmf(50 + d, Character.toLowerCase(d), false),
                    LegacyCommands.dtmf(50 + d, d, false),
                    "dtmf_burst_" + d);
            checkBytesEqual(
                    RilImsWire.encodeDtmf(60 + d, d, true),
                    LegacyCommands.dtmf(60 + d, d, true),
                    "dtmf_start_" + d);
        }
        checkBytesEqual(
                RilImsWire.encodeStopDtmf(99),
                LegacyCommands.stopDtmf(99),
                "dtmf_stop");

        // 3. Registration & ServiceStatus decoding parity (RP32 Stage 6A LTE VoLTE shape)
        byte[] regBytes = new RilImsWire.ProtoWriter()
                .varint(1, RilImsWire.REG_STATE_REGISTERED)
                .fixed32(2, 0)
                .toByteArray();
        RegistrationObservation frozenReg = RegistrationObservation.decodePayload(regBytes);
        RilImsWire.RegObservation ourReg = RilImsWire.decodeRegistration(regBytes);
        check(frozenReg.state == RegistrationObservation.State.REGISTERED && ourReg.isRegistered(), "reg=1");

        byte[] svcBytes = buildStage6aVoiceLteServicePayload();
        ServiceStatusObservation frozenSvc = ServiceStatusObservation.decodePayload(svcBytes);
        RilImsWire.ServiceStatusSnapshot ourSvc = RilImsWire.decodeServiceStatusList(svcBytes);
        check(frozenSvc.services.size() == 1 && ourSvc.services.size() == 1, "svc count=1");
        check(ourSvc.isVoiceEnabledOnLte(), "voice enabled on LTE");
        check(ourSvc.anyRegisteredAccess(), "anyRegisteredAccess");

        // 4. CallList decoding parity with AMR-WB & EVS-WB extras
        byte[] callListBytes = buildCallListPayload(
                buildSingleCallProto(1, RilImsWire.CALL_STATE_ACTIVE, false, "10010", "Codec=AMR_WB", null));
        CallObservation frozenCalls = CallObservation.decodePayload(callListBytes);
        List<RilImsWire.DriverCall> ourCalls = RilImsWire.decodeCallList(callListBytes);
        check(frozenCalls.calls.size() == 1 && ourCalls.size() == 1, "1 call");
        check(ourCalls.get(0).index == 1 && ourCalls.get(0).state == RilImsWire.CALL_STATE_ACTIVE, "active idx 1");
        check(ourCalls.get(0).audioQualityFromExtras() == RilImsWire.AUDIO_QUALITY_AMR_WB, "AMR_WB mapped");
        check(RilImsWire.mapCodecToAudioQuality("EVS_WB") == RilImsWire.AUDIO_QUALITY_EVS_WB, "EVS_WB mapped");

        // 5. PII redaction check
        String redacted = RilImsWire.redactPii("imsi=460011234567890 msisdn=+8618612345678 mcc=460 rat=14");
        check(!redacted.contains("460011234567890") && !redacted.contains("8618612345678"), "PII stripped");
        check(redacted.contains("mcc=460") && redacted.contains("rat=14"), "short codes preserved");
    }

    private static void testCallerIdClipToOirAndToaCodec() throws Exception {
        // 1. Exact DriverCall.presentationFromCLIP(0..3) parity
        check(RilImsWire.clipToAndroidPresentation(RilImsWire.RIL_CLIP_ALLOWED)
                == RilImsWire.ANDROID_PRESENTATION_ALLOWED, "CLIP 0 -> PRESENTATION_ALLOWED (1)");
        check(RilImsWire.clipToAndroidPresentation(RilImsWire.RIL_CLIP_RESTRICTED)
                == RilImsWire.ANDROID_PRESENTATION_RESTRICTED, "CLIP 1 -> PRESENTATION_RESTRICTED (2)");
        check(RilImsWire.clipToAndroidPresentation(RilImsWire.RIL_CLIP_UNKNOWN)
                == RilImsWire.ANDROID_PRESENTATION_UNKNOWN, "CLIP 2 -> PRESENTATION_UNKNOWN (3)");
        check(RilImsWire.clipToAndroidPresentation(RilImsWire.RIL_CLIP_PAYPHONE)
                == RilImsWire.ANDROID_PRESENTATION_PAYPHONE, "CLIP 3 -> PRESENTATION_PAYPHONE (4)");
        check(RilImsWire.clipToAndroidPresentation(99)
                == RilImsWire.ANDROID_PRESENTATION_UNKNOWN, "CLIP invalid -> PRESENTATION_UNKNOWN (3)");

        // 2. Exact CLIP -> ImsCallProfile.OIR_* mapping (verified against ImsPhoneConnection.OIRToPresentation)
        check(RilImsWire.clipToOir(RilImsWire.RIL_CLIP_ALLOWED, "18612345678", true)
                == RilImsWire.OIR_PRESENTATION_NOT_RESTRICTED,
                "CLIP 0 + non-empty number -> OIR_PRESENTATION_NOT_RESTRICTED (2)");
        check(RilImsWire.clipToOir(RilImsWire.RIL_CLIP_ALLOWED, "", true)
                == RilImsWire.OIR_PRESENTATION_UNKNOWN,
                "CLIP 0 + empty number -> OIR_PRESENTATION_UNKNOWN (3)");
        check(RilImsWire.clipToOir(RilImsWire.RIL_CLIP_ALLOWED, "", false)
                == RilImsWire.OIR_PRESENTATION_NOT_RESTRICTED,
                "CLIP 0 + empty CNAP -> OIR_PRESENTATION_NOT_RESTRICTED (2)");
        check(RilImsWire.clipToOir(RilImsWire.RIL_CLIP_RESTRICTED, "", true)
                == RilImsWire.OIR_PRESENTATION_RESTRICTED,
                "CLIP 1 -> OIR_PRESENTATION_RESTRICTED (1)");
        check(RilImsWire.clipToOir(RilImsWire.RIL_CLIP_UNKNOWN, "18612345678", true)
                == RilImsWire.OIR_PRESENTATION_UNKNOWN,
                "CLIP 2 -> OIR_PRESENTATION_UNKNOWN (3)");
        check(RilImsWire.clipToOir(RilImsWire.RIL_CLIP_PAYPHONE, "18612345678", true)
                == RilImsWire.OIR_PRESENTATION_PAYPHONE,
                "CLIP 3 -> OIR_PRESENTATION_PAYPHONE (4)");

        // 3. PhoneNumberUtils.stringFromStringAndTOA parity
        check("+8618612345678".equals(RilImsWire.formatNumberWithToa("8618612345678", RilImsWire.TOA_INTERNATIONAL)),
                "TOA 145 prepends '+'");
        check("+8618612345678".equals(RilImsWire.formatNumberWithToa("+8618612345678", RilImsWire.TOA_INTERNATIONAL)),
                "TOA 145 preserves existing '+'");
        check("18612345678".equals(RilImsWire.formatNumberWithToa("18612345678", RilImsWire.TOA_UNKNOWN)),
                "TOA 129 keeps national digits");
        check("sip:186@ims.example".equals(RilImsWire.formatNumberWithToa("sip:186@ims.example", RilImsWire.TOA_INTERNATIONAL)),
                "TOA 145 never corrupts URI");
        check("".equals(RilImsWire.formatNumberWithToa("", RilImsWire.TOA_INTERNATIONAL)),
                "TOA 145 empty stays empty");

        // 4. Wire protobuf decoding of field 3 (toa), field 9 (number), field 10 (np), field 11 (name), field 12 (cp)
        byte[] intlMtBytes = buildCallListPayload(
                buildSingleCallProtoFull(
                        1, RilImsWire.CALL_STATE_INCOMING, RilImsWire.TOA_INTERNATIONAL,
                        true, "8618600009999", RilImsWire.RIL_CLIP_ALLOWED,
                        "China Unicom", RilImsWire.RIL_CLIP_ALLOWED, "Codec=AMR_WB", null));
        CallObservation frozenObs = CallObservation.decodePayload(intlMtBytes);
        List<RilImsWire.DriverCall> decoded = RilImsWire.decodeCallList(intlMtBytes);
        check(frozenObs.calls.size() == 1 && decoded.size() == 1, "decoded 1 MT call");
        check(frozenObs.calls.get(0).toa != null && frozenObs.calls.get(0).toa == 145, "frozen codec sees toa=145");
        RilImsWire.DriverCall dc = decoded.get(0);
        check(dc.toa == RilImsWire.TOA_INTERNATIONAL, "RilImsWire decoded toa=145");
        check("+8618600009999".equals(dc.number), "RilImsWire formatted +86 number from toa=145");
        check(dc.numberPresentation == RilImsWire.RIL_CLIP_ALLOWED, "np=0");
        check("China Unicom".equals(dc.name), "cna decoded");
        check(dc.namePresentation == RilImsWire.RIL_CLIP_ALLOWED, "cp=0");
    }

    private static void testCoreClientUnsolCoalescingAndReconnect() throws Exception {
        List<FakeRildServer> servers = new CopyOnWriteArrayList<>();
        AtomicInteger connectAttempt = new AtomicInteger(0);
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch disconnectedOnce = new CountDownLatch(1);
        CountDownLatch reconnectedReady = new CountDownLatch(1);
        List<List<RilImsWire.DriverCall>> callSnapshots = new CopyOnWriteArrayList<>();

        RilImsCoreClient.TransportFactory factory = () -> {
            int attempt = connectAttempt.incrementAndGet();
            if (attempt == 1) {
                // Attempt 1: wrong UID (2000) -> must be rejected immediately
                FakeRildServer bad = new FakeRildServer(2000, 400);
                servers.add(bad);
                return bad.transport();
            }
            int pid = (attempt == 2) ? 523 : 987;
            FakeRildServer srv = new FakeRildServer(1001, pid);
            // Delay REQ_6 slightly on server #1 so 3 rapid UNSOL 201 pokes coalesce
            srv.req6DelayMs = (attempt == 2) ? 90L : 0L;
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProto(1, RilImsWire.CALL_STATE_DIALING, false, "10010", "Codec=AMR_WB", null));
            servers.add(srv);
            srv.start();
            return srv.transport();
        };

        RilImsCoreClient client = new RilImsCoreClient(
                factory,
                new RilImsCoreClient.Listener() {
                    @Override
                    public void onBackendConnectionChanged(boolean connected, int peerPid) {
                        if (!connected && firstReady.getCount() == 0) {
                            disconnectedOnce.countDown();
                        }
                    }

                    @Override
                    public void onRegistrationAndCapabilityChanged(
                            int regState, boolean voiceEnabledOnLte, String serviceSummary) {
                        if (regState == RilImsWire.REG_STATE_REGISTERED && voiceEnabledOnLte) {
                            if (firstReady.getCount() > 0) {
                                firstReady.countDown();
                            } else if (disconnectedOnce.getCount() == 0) {
                                reconnectedReady.countDown();
                            }
                        }
                    }

                    @Override
                    public void onCallListSnapshot(List<RilImsWire.DriverCall> calls, boolean authoritativeSolicited) {
                        callSnapshots.add(calls);
                    }

                    @Override
                    public void onRingbackTone(boolean playLocalTone) { }

                    @Override
                    public void onSuppSvcNotification(RilImsWire.SuppSvcInfo info) { }

                    @Override
                    public boolean hasLiveCalls() {
                        return false;
                    }

                    @Override
                    public void onDiagnosticLog(String redactedEvent) { }
                },
                500L,
                10000L,
                500L,
                60L);

        client.start();
        try {
            check(firstReady.await(5, TimeUnit.SECONDS), "first server REGISTERED+LTE ready");
            check(client.getLastPeerPid() == 523, "bound to pid 523 after rejecting uid 2000");

            FakeRildServer srv1 = servers.get(1);
            int baseReq6 = srv1.countRequest(RilImsWire.REQ_GET_CURRENT_CALLS);
            // Burst 3 back-to-back 0-byte UNSOL 201 pokes
            srv1.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            srv1.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            srv1.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            Thread.sleep(320L);
            int deltaReq6 = srv1.countRequest(RilImsWire.REQ_GET_CURRENT_CALLS) - baseReq6;
            check(deltaReq6 >= 1 && deltaReq6 <= 2, "3 UNSOL_201 coalesced into 1..2 REQ_6, got " + deltaReq6);
            check(!callSnapshots.isEmpty() && callSnapshots.get(callSnapshots.size() - 1).size() == 1,
                    "0-byte UNSOL_201 never misinterpreted as empty call list");

            // Simulate rild crash/restart -> close srv1, verify auto-reconnect to pid 987
            srv1.closeServer();
            check(disconnectedOnce.await(3, TimeUnit.SECONDS), "disconnect detected");
            check(reconnectedReady.await(5, TimeUnit.SECONDS), "auto-reconnected to new rild pid 987");
            check(client.getLastPeerPid() == 987, "updated peerPid=987");

            for (FakeRildServer s : servers) {
                check(s.countRequest(26) == 0 && s.countRequest(30) == 0,
                        "never sent REQ_26 or REQ_30");
            }
        } finally {
            client.stop();
            for (FakeRildServer s : servers) {
                s.closeServer();
            }
        }
    }

    private static void testCallStateEngineLifecycles() throws Exception {
        FakeRildServer srv = new FakeRildServer(1001, 600);
        srv.start();
        List<CallStateEngine.SessionHandle> incomingHandles = new CopyOnWriteArrayList<>();
        final CallStateEngine[] engineRef = new CallStateEngine[1];

        RilImsCoreClient client = new RilImsCoreClient(
                srv::transport,
                new RilImsCoreClient.Listener() {
                    @Override
                    public void onBackendConnectionChanged(boolean connected, int peerPid) {
                        if (!connected && engineRef[0] != null) engineRef[0].onBackendDisconnected();
                    }

                    @Override
                    public void onRegistrationAndCapabilityChanged(
                            int regState, boolean voiceEnabledOnLte, String serviceSummary) {
                        if (engineRef[0] != null) {
                            engineRef[0].updateModemRegistrationAndCapability(regState, voiceEnabledOnLte);
                        }
                    }

                    @Override
                    public void onCallListSnapshot(List<RilImsWire.DriverCall> calls, boolean authoritativeSolicited) {
                        if (engineRef[0] != null) {
                            engineRef[0].onCallListSnapshot(calls, authoritativeSolicited);
                        }
                    }

                    @Override
                    public void onRingbackTone(boolean playLocalTone) {
                        if (engineRef[0] != null) engineRef[0].onRingbackTone(playLocalTone);
                    }

                    @Override
                    public void onSuppSvcNotification(RilImsWire.SuppSvcInfo info) {
                        if (engineRef[0] != null) engineRef[0].onSuppSvcNotification(info);
                    }

                    @Override
                    public boolean hasLiveCalls() {
                        return engineRef[0] != null && engineRef[0].hasLiveCalls();
                    }

                    @Override
                    public void onDiagnosticLog(String redactedEvent) { }
                },
                500L,
                10000L,
                500L,
                60L);

        CallStateEngine engine = new CallStateEngine(client, (h, waiting) -> incomingHandles.add(h));
        engineRef[0] = engine;
        client.start();

        try {
            long deadline = System.currentTimeMillis() + 4000L;
            while (!engine.isVoiceImsReady() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            check(engine.isVoiceImsReady(), "engine ready after passive 1+29");

            // 1. Routing rules: normal -> IMS; emergency & USSD -> CSFB
            check(engine.shouldProcessCall(new String[] { "10010" }, false) == CallStateEngine.PROCESS_CALL_IMS,
                    "10010 -> IMS");
            check(engine.shouldProcessCall(new String[] { "110" }, false) == CallStateEngine.PROCESS_CALL_CSFB,
                    "110 -> CSFB");
            check(engine.shouldProcessCall(new String[] { "120" }, false) == CallStateEngine.PROCESS_CALL_CSFB,
                    "120 -> CSFB");
            check(engine.shouldProcessCall(new String[] { "10010" }, true) == CallStateEngine.PROCESS_CALL_CSFB,
                    "platformEmergency -> CSFB");
            check(engine.shouldProcessCall(new String[] { "*101#" }, false) == CallStateEngine.PROCESS_CALL_CSFB,
                    "USSD -> CSFB");

            // 2. Full MO call: dial -> dialing -> ringback+alerting -> active -> hold -> resume -> DTMF -> remote end
            RecordingCallbacks moCb = new RecordingCallbacks();
            CallStateEngine.SessionHandle mo = engine.createOutgoingSession();
            check(!mo.isIncoming() && !mo.hasDriverMetadata(), "MO pre-dial metadata flags clean");
            mo.setCallbacks(moCb);
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProto(1, RilImsWire.CALL_STATE_DIALING, false, "10010", "Codec=AMR_WB", null));
            mo.startOutgoing("100-10", 0, RilImsWire.IMS_CALL_DOMAIN_PS);
            awaitCond(() -> moCb.progressingCount.get() >= 1, "MO DIALING progressing");
            check(mo.getCallIndex() == 1, "MO bound to index 1");

            // Network sends local ringback flag=1 then ALERTING
            srv.sendUnsol(RilImsWire.UNSOL_RINGBACK_TONE,
                    new RilImsWire.ProtoWriter().varint(1, 1).toByteArray());
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProto(1, RilImsWire.CALL_STATE_ALERTING, false, "10010", "Codec=AMR_WB", null));
            srv.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            awaitCond(() -> moCb.lastAudioDirection.get() == RilImsWire.DIRECTION_INACTIVE, "MO ALERTING ringback muted");

            // Remote answers -> ACTIVE
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProto(1, RilImsWire.CALL_STATE_ACTIVE, false, "10010", "Codec=EVS_WB", null));
            srv.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            awaitCond(() -> moCb.initiatedCount.get() == 1, "MO ACTIVE initiated");
            check(moCb.lastAudioQuality.get() == RilImsWire.AUDIO_QUALITY_EVS_WB, "EVS_WB reported");

            // Hold -> HOLDING -> Resume -> ACTIVE (must fire onResumed, NOT second onInitiated!)
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProto(1, RilImsWire.CALL_STATE_HOLDING, false, "10010", "Codec=EVS_WB", null));
            mo.hold();
            awaitCond(() -> moCb.heldCount.get() == 1, "MO held");

            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProto(1, RilImsWire.CALL_STATE_ACTIVE, false, "10010", "Codec=EVS_WB", null));
            mo.resume();
            awaitCond(() -> moCb.resumedCount.get() == 1, "MO resumed");
            check(moCb.initiatedCount.get() == 1, "resume did not duplicate onInitiated");

            // DTMF burst + continuous + SuppSvcNotification remote hold/resume
            CountDownLatch dtmfDone = new CountDownLatch(1);
            mo.sendDtmf('5', dtmfDone::countDown);
            check(dtmfDone.await(2, TimeUnit.SECONDS), "DTMF completed");
            mo.startDtmf('*');
            mo.stopDtmf();

            srv.sendUnsol(RilImsWire.UNSOL_SUPP_SVC_NOTIFICATION,
                    new RilImsWire.ProtoWriter().varint(1, 1).varint(2, 2).varint(6, 1).toByteArray());
            srv.sendUnsol(RilImsWire.UNSOL_SUPP_SVC_NOTIFICATION,
                    new RilImsWire.ProtoWriter().varint(1, 1).varint(2, 3).varint(6, 1).toByteArray());
            awaitCond(() -> moCb.holdReceivedCount.get() == 1 && moCb.resumeReceivedCount.get() == 1,
                    "remote hold/resume notifications");

            // Remote hangup: call disappears, REQ_5 returns CALL_FAIL_NORMAL (16) -> 510
            srv.lastFailCause = RilImsWire.CALL_FAIL_NORMAL;
            srv.activeCallsPayload = new byte[0];
            srv.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            awaitCond(() -> moCb.terminatedReason.get() == RilImsWire.IMS_REASON_USER_TERMINATED_BY_REMOTE,
                    "MO remote hangup -> 510");

            // 3. MO cancel before bind -> REQ_8 -> 501
            RecordingCallbacks cancelCb = new RecordingCallbacks();
            CallStateEngine.SessionHandle moCancel = engine.createOutgoingSession();
            moCancel.setCallbacks(cancelCb);
            srv.dialDelayMs = 150L;
            moCancel.startOutgoing("10010", 0, RilImsWire.IMS_CALL_DOMAIN_PS);
            moCancel.terminate(RilImsWire.IMS_REASON_USER_TERMINATED);
            awaitCond(() -> cancelCb.terminatedReason.get() == RilImsWire.IMS_REASON_USER_TERMINATED,
                    "MO cancel before bind -> 501");
            srv.dialDelayMs = 0L;

            // 4. MO dial failure -> REQ_5 returns CALL_FAIL_USER_BUSY (501) -> SIP_BUSY (338)
            RecordingCallbacks busyCb = new RecordingCallbacks();
            CallStateEngine.SessionHandle moBusy = engine.createOutgoingSession();
            moBusy.setCallbacks(busyCb);
            srv.nextDialRilError = 2;
            srv.lastFailCause = RilImsWire.CALL_FAIL_USER_BUSY;
            moBusy.startOutgoing("10010", 0, RilImsWire.IMS_CALL_DOMAIN_PS);
            awaitCond(() -> busyCb.initFailedReason.get() == RilImsWire.IMS_REASON_SIP_BUSY,
                    "MO busy -> 338");

            // 5. MT incoming call with TOA=145 + CLIP=0 -> verify getNumberOir()==2 & "+86..." -> accept -> active -> local hangup
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProtoFull(
                            2, RilImsWire.CALL_STATE_INCOMING, RilImsWire.TOA_INTERNATIONAL,
                            true, "8618612345678", RilImsWire.RIL_CLIP_ALLOWED,
                            "", RilImsWire.RIL_CLIP_ALLOWED, "Codec=AMR_WB", null));
            srv.sendUnsol(RilImsWire.UNSOL_CALL_RING, new byte[0]);
            awaitCond(() -> incomingHandles.size() == 1, "MT incoming created");
            CallStateEngine.SessionHandle mt1 = incomingHandles.get(0);
            check(mt1.isIncoming(), "mt1 isIncoming=true");
            check("+8618612345678".equals(mt1.getPeerNumber()), "mt1 peerNumber=+8618612345678");
            check(mt1.getNumberOir() == RilImsWire.OIR_PRESENTATION_NOT_RESTRICTED,
                    "mt1 numberOir == OIR_PRESENTATION_NOT_RESTRICTED (2)");
            check(mt1.getNameOir() == RilImsWire.OIR_PRESENTATION_NOT_RESTRICTED,
                    "mt1 nameOir == OIR_PRESENTATION_NOT_RESTRICTED (2)");

            RecordingCallbacks mt1Cb = new RecordingCallbacks();
            mt1.setCallbacks(mt1Cb);

            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProtoFull(
                            2, RilImsWire.CALL_STATE_ACTIVE, RilImsWire.TOA_INTERNATIONAL,
                            true, "8618612345678", RilImsWire.RIL_CLIP_ALLOWED,
                            "", RilImsWire.RIL_CLIP_ALLOWED, "Codec=AMR_WB", null));
            mt1.acceptIncoming();
            awaitCond(() -> mt1Cb.initiatedCount.get() == 1, "MT accepted -> ACTIVE");
            check(mt1.getNumberOir() == RilImsWire.OIR_PRESENTATION_NOT_RESTRICTED,
                    "mt1 numberOir preserved across ACTIVE");

            srv.activeCallsPayload = new byte[0];
            mt1.terminate(RilImsWire.IMS_REASON_USER_TERMINATED);
            awaitCond(() -> mt1Cb.terminatedReason.get() == RilImsWire.IMS_REASON_USER_TERMINATED,
                    "MT local hangup -> 501");

            // 6. MT incoming call with delayed mid-ring Caller ID update -> local decline -> REQ_4(502) -> onInitiatedFailed(143)
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProtoFull(
                            3, RilImsWire.CALL_STATE_INCOMING, RilImsWire.TOA_UNKNOWN,
                            true, "", RilImsWire.RIL_CLIP_UNKNOWN,
                            "", RilImsWire.RIL_CLIP_UNKNOWN, "Codec=AMR_WB", null));
            srv.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            awaitCond(() -> incomingHandles.size() == 2, "MT #2 incoming created");
            CallStateEngine.SessionHandle mt2 = incomingHandles.get(1);
            check(mt2.getNumberOir() == RilImsWire.OIR_PRESENTATION_UNKNOWN, "mt2 initial unknown OIR=3");
            RecordingCallbacks mt2Cb = new RecordingCallbacks();
            mt2.setCallbacks(mt2Cb);

            // Second snapshot delivers real Caller ID while still INCOMING
            srv.activeCallsPayload = buildCallListPayload(
                    buildSingleCallProtoFull(
                            3, RilImsWire.CALL_STATE_INCOMING, RilImsWire.TOA_UNKNOWN,
                            true, "18688887777", RilImsWire.RIL_CLIP_ALLOWED,
                            "Unicom Caller", RilImsWire.RIL_CLIP_ALLOWED, "Codec=AMR_WB", null));
            srv.sendUnsol(RilImsWire.UNSOL_RESPONSE_CALL_STATE_CHANGED, new byte[0]);
            awaitCond(() -> "18688887777".equals(mt2Cb.lastProfileNumber.get())
                    && mt2.getNumberOir() == RilImsWire.OIR_PRESENTATION_NOT_RESTRICTED,
                    "MT #2 mid-ring Caller ID updated to OIR=2");
            check("Unicom Caller".equals(mt2.getPeerName()), "MT #2 mid-ring CNAP updated");

            srv.activeCallsPayload = new byte[0];
            mt2.rejectIncoming(RilImsWire.IMS_REASON_LOCAL_CALL_DECLINE);
            awaitCond(() -> mt2Cb.initFailedReason.get() == RilImsWire.IMS_REASON_LOCAL_CALL_DECLINE,
                    "MT decline -> 143");

            check(srv.countRequest(26) == 0 && srv.countRequest(30) == 0, "zero 26/30 in CallStateEngine test");
        } finally {
            client.stop();
            srv.closeServer();
        }
    }

    private static void awaitCond(BoolSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                check(true, desc);
                return;
            }
            Thread.sleep(15L);
        }
        check(cond.getAsBoolean(), "Timed out waiting for: " + desc);
    }

    private interface BoolSupplier {
        boolean getAsBoolean();
    }

    private static byte[] buildStage6aVoiceLteServicePayload() {
        byte[] reg1 = new RilImsWire.ProtoWriter()
                .varint(1, RilImsWire.REG_STATE_REGISTERED)
                .toByteArray();
        byte[] accLte = new RilImsWire.ProtoWriter()
                .varint(1, RilImsWire.RADIO_TECH_LTE)
                .varint(2, RilImsWire.STATUS_ENABLED)
                .fixed32(3, 0)
                .bytes(4, reg1)
                .toByteArray();
        byte[] voiceSvc = new RilImsWire.ProtoWriter()
                .varint(1, 1)
                .varint(2, 0)
                .varint(3, RilImsWire.IMS_CALL_TYPE_VOICE)
                .varint(4, RilImsWire.STATUS_ENABLED)
                .bytes(7, accLte)
                .toByteArray();
        return new RilImsWire.ProtoWriter().bytes(2, voiceSvc).toByteArray();
    }

    private static byte[] buildCallListPayload(byte[]... calls) {
        RilImsWire.ProtoWriter w = new RilImsWire.ProtoWriter();
        for (byte[] c : calls) {
            w.bytes(2, c);
        }
        return w.toByteArray();
    }

    private static byte[] buildSingleCallProto(
            int index, int state, boolean isMt, String number, String extraKv, Integer failCause) {
        return buildSingleCallProtoFull(
                index, state, RilImsWire.TOA_UNKNOWN, isMt,
                number, RilImsWire.RIL_CLIP_ALLOWED, "", RilImsWire.RIL_CLIP_ALLOWED, extraKv, failCause);
    }

    private static byte[] buildSingleCallProtoFull(
            int index,
            int state,
            int toa,
            boolean isMt,
            String number,
            int np,
            String name,
            int cp,
            String extraKv,
            Integer failCause) {
        RilImsWire.ProtoWriter details = new RilImsWire.ProtoWriter()
                .varint(1, RilImsWire.IMS_CALL_TYPE_VOICE)
                .varint(2, RilImsWire.IMS_CALL_DOMAIN_PS);
        if (extraKv != null) {
            details.fixed32(3, 1).string(4, extraKv);
        }
        RilImsWire.ProtoWriter call = new RilImsWire.ProtoWriter()
                .varint(1, state)
                .fixed32(2, index)
                .fixed32(3, toa)
                .varint(4, 0)
                .varint(5, isMt ? 1 : 0)
                .varint(7, 1)
                .string(9, number)
                .fixed32(10, np)
                .string(11, name)
                .fixed32(12, cp)
                .bytes(13, details.toByteArray());
        if (failCause != null) {
            call.bytes(14, new RilImsWire.ProtoWriter().varint(1, failCause).toByteArray());
        }
        return call.toByteArray();
    }

    private static final class RecordingCallbacks implements CallStateEngine.SessionCallbacks {
        final AtomicInteger progressingCount = new AtomicInteger();
        final AtomicInteger initiatedCount = new AtomicInteger();
        final AtomicInteger heldCount = new AtomicInteger();
        final AtomicInteger resumedCount = new AtomicInteger();
        final AtomicInteger holdReceivedCount = new AtomicInteger();
        final AtomicInteger resumeReceivedCount = new AtomicInteger();
        final AtomicInteger lastAudioQuality = new AtomicInteger(-1);
        final AtomicInteger lastAudioDirection = new AtomicInteger(-1);
        final AtomicInteger initFailedReason = new AtomicInteger(-1);
        final AtomicInteger terminatedReason = new AtomicInteger(-1);
        final AtomicReference<String> lastProfileNumber = new AtomicReference<>("");

        @Override
        public void onCallProfileUpdated(
                String number, int np, String name, int cp, int aq, int ad, boolean mpty) {
            lastProfileNumber.set(number);
            lastAudioQuality.set(aq);
            lastAudioDirection.set(ad);
        }

        @Override
        public void onProgressing(int audioQuality, int audioDirection) {
            lastAudioQuality.set(audioQuality);
            lastAudioDirection.set(audioDirection);
            progressingCount.incrementAndGet();
        }

        @Override
        public void onInitiated(int audioQuality) {
            lastAudioQuality.set(audioQuality);
            initiatedCount.incrementAndGet();
        }

        @Override
        public void onInitiatedFailed(int imsReasonCode, int vendorExtraCode, String message) {
            initFailedReason.set(imsReasonCode);
        }

        @Override
        public void onTerminated(int imsReasonCode, int vendorExtraCode, String message) {
            terminatedReason.set(imsReasonCode);
        }

        @Override
        public void onHeld() {
            heldCount.incrementAndGet();
        }

        @Override
        public void onHoldFailed(int imsReasonCode, int vendorExtraCode, String message) { }

        @Override
        public void onHoldReceived() {
            holdReceivedCount.incrementAndGet();
        }

        @Override
        public void onResumed() {
            resumedCount.incrementAndGet();
        }

        @Override
        public void onResumeFailed(int imsReasonCode, int vendorExtraCode, String message) { }

        @Override
        public void onResumeReceived() {
            resumeReceivedCount.incrementAndGet();
        }

        @Override
        public void onMultipartyStateChanged(boolean isMultiparty) { }

        @Override
        public void onSuppServiceReceived(RilImsWire.SuppSvcInfo info) { }
    }

    private static final class TimeoutQueueStream {
        private final LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>();
        private volatile int timeoutMs = 1000;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        void setSoTimeout(int ms) {
            this.timeoutMs = Math.max(10, ms);
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                q.offer(-1);
            }
        }

        final InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    Integer v = q.poll(timeoutMs, TimeUnit.MILLISECONDS);
                    if (v == null) {
                        throw new SocketTimeoutException("timed out");
                    }
                    if (v == -1) {
                        q.offer(-1);
                        return -1;
                    }
                    return v & 0xff;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int first = read();
                if (first < 0) return -1;
                b[off] = (byte) first;
                int count = 1;
                while (count < len) {
                    Integer next = q.peek();
                    if (next == null || next == -1) break;
                    Integer taken = q.poll();
                    if (taken == null || taken == -1) {
                        if (taken != null) q.offer(-1);
                        break;
                    }
                    b[off + count++] = (byte) (taken & 0xff);
                }
                return count;
            }
        };

        final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                if (closed.get()) throw new IOException("stream closed");
                q.offer(b & 0xff);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (closed.get()) throw new IOException("stream closed");
                for (int i = 0; i < len; i++) {
                    q.offer(b[off + i] & 0xff);
                }
            }
        };
    }

    private static final class FakeRildServer {
        private final int uid;
        private final int pid;
        private final TimeoutQueueStream clientToServer = new TimeoutQueueStream();
        private final TimeoutQueueStream serverToClient = new TimeoutQueueStream();
        private final List<Integer> receivedReqIds = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean open = new AtomicBoolean(true);
        private Thread thread;

        volatile byte[] activeCallsPayload = new byte[0];
        volatile int lastFailCause = RilImsWire.CALL_FAIL_NORMAL;
        volatile int nextDialRilError = 0;
        volatile long req6DelayMs = 0L;
        volatile long dialDelayMs = 0L;

        FakeRildServer(int uid, int pid) {
            this.uid = uid;
            this.pid = pid;
        }

        void start() {
            thread = new Thread(this::serveLoop, "fake-rild-" + pid);
            thread.setDaemon(true);
            thread.start();
        }

        RilImsCoreClient.SocketTransport transport() {
            return new RilImsCoreClient.SocketTransport() {
                @Override
                public void connect() throws IOException {
                    if (open.get() && uid == 1001) {
                        // Match real libril-qc-qmi-1.so: emit UNSOL 213 (RADIO_STATE_ON) on connect
                        sendUnsol(RilImsWire.UNSOL_RADIO_STATE_CHANGED,
                                new RilImsWire.ProtoWriter().varint(1, RilImsWire.RADIO_STATE_ON).toByteArray());
                    }
                }

                @Override
                public int peerUid() {
                    return uid;
                }

                @Override
                public int peerPid() {
                    return pid;
                }

                @Override
                public InputStream inputStream() {
                    return serverToClient.in;
                }

                @Override
                public OutputStream outputStream() {
                    return clientToServer.out;
                }

                @Override
                public void setSoTimeout(int timeoutMs) {
                    serverToClient.setSoTimeout(timeoutMs);
                }

                @Override
                public void close() {
                    closeServer();
                }
            };
        }

        int countRequest(int reqId) {
            int n = 0;
            synchronized (receivedReqIds) {
                for (int id : receivedReqIds) {
                    if (id == reqId) n++;
                }
            }
            return n;
        }

        synchronized void sendUnsol(int unsolId, byte[] payload) throws IOException {
            byte[] wire = RilImsWire.encodeFrame(0xffffffff, RilImsWire.MSG_TYPE_UNSOL_RESPONSE, unsolId, 0, payload);
            serverToClient.out.write(wire);
        }

        private synchronized void sendSolicited(int token, int reqId, int error, byte[] payload) throws IOException {
            byte[] wire = RilImsWire.encodeFrame(token, RilImsWire.MSG_TYPE_RESPONSE, reqId, error, payload);
            serverToClient.out.write(wire);
        }

        void closeServer() {
            if (open.compareAndSet(true, false)) {
                clientToServer.close();
                serverToClient.close();
                if (thread != null) thread.interrupt();
            }
        }

        private void serveLoop() {
            try {
                while (open.get()) {
                    byte[] raw = RilImsWire.receiveRawFrameOrNull(
                            clientToServer.in, clientToServer::setSoTimeout, 200, 1000);
                    if (raw == null) continue;
                    RilImsWire.Frame req = RilImsWire.decodeFrame(raw);
                    receivedReqIds.add(req.id);
                    if (req.id == 26 || req.id == 30) {
                        throw new AssertionError("FORBIDDEN request sent to rild_ims0: " + req.id);
                    }
                    switch (req.id) {
                        case RilImsWire.REQ_IMS_REGISTRATION_STATE: {
                            byte[] reg = new RilImsWire.ProtoWriter()
                                    .varint(1, RilImsWire.REG_STATE_REGISTERED)
                                    .toByteArray();
                            sendSolicited(req.token, req.id, 0, reg);
                            break;
                        }
                        case RilImsWire.REQ_QUERY_SERVICE_STATUS: {
                            sendSolicited(req.token, req.id, 0, buildStage6aVoiceLteServicePayload());
                            break;
                        }
                        case RilImsWire.REQ_GET_CURRENT_CALLS: {
                            if (req6DelayMs > 0L) Thread.sleep(req6DelayMs);
                            sendSolicited(req.token, req.id, 0, activeCallsPayload);
                            break;
                        }
                        case RilImsWire.REQ_DIAL: {
                            if (dialDelayMs > 0L) Thread.sleep(dialDelayMs);
                            int err = nextDialRilError;
                            nextDialRilError = 0;
                            sendSolicited(req.token, req.id, err, new byte[0]);
                            break;
                        }
                        case RilImsWire.REQ_LAST_CALL_FAIL_CAUSE: {
                            byte[] fc = new RilImsWire.ProtoWriter().varint(1, lastFailCause).toByteArray();
                            sendSolicited(req.token, req.id, 0, fc);
                            break;
                        }
                        default: {
                            sendSolicited(req.token, req.id, 0, new byte[0]);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    private static void testQmiProbeSummaryParserAndZeroPiiSanitizer() {
        String realJson = "{\"event\":\"imss_reg_mgr\",\"tag\":\"probe\",\"matched\":true,\"test_mode\":0}\n"
                + "{\"event\":\"imsa_pdp\",\"tag\":\"probe\",\"matched\":true,\"pdp_connected\":1,\"pdp_fail_code\":-1}\n"
                + "{\"event\":\"imsa_reg\",\"tag\":\"probe\",\"matched\":true,\"legacy_reg\":1,\"reg_status\":2}\n"
                + "{\"event\":\"imsa_svc\",\"tag\":\"probe\",\"matched\":true,\"sms\":2,\"sms_rat\":1,\"voip\":2,\"voip_rat\":1}\n";
        QmiProbeSummaryParser.Summary sJson = QmiProbeSummaryParser.parse(realJson);
        check(sJson.pdpConnected == 1 && sJson.regStatus == 2 && sJson.legacyReg == 1, "JSON QMI reg parsed");
        check(sJson.voip == 2 && sJson.voipRat == 1 && sJson.sms == 2 && sJson.testMode == 0, "JSON QMI svc parsed");
        check(sJson.isRegisteredVoipReady(), "JSON QMI healthy ready");

        QmiProbeSummaryParser.Summary sKv = QmiProbeSummaryParser.parse(
                "pdp_connected=1 reg_status=2 legacy_reg=1 voip=2 voip_rat=1 test_mode=0");
        check(sKv.isRegisteredVoipReady() && sKv.voipRat == 1, "key=val QMI parsed");

        QmiProbeSummaryParser.Summary sDown = QmiProbeSummaryParser.parse("{\"event\":\"imsa_pdp\",\"pdp_connected\":0}");
        check(!sDown.isRegisteredVoipReady() && sDown.regStatus == -1, "unregistered QMI is not ready");

        check("46001".equals(QmiProbeSummaryParser.sanitizeSimOperator("46001")), "Unicom PLMN allowed");
        check("46000".equals(QmiProbeSummaryParser.sanitizeSimOperator("46000,")), "Mobile PLMN comma trimmed");
        check("46011".equals(QmiProbeSummaryParser.sanitizeSimOperator(" 46011 ")), "Telecom PLMN trimmed");
        check("".equals(QmiProbeSummaryParser.sanitizeSimOperator("460011234567890")), "IMSI rejected by PLMN sanitizer");
        check("".equals(QmiProbeSummaryParser.sanitizeSimOperator("18612345678")), "MSISDN rejected by PLMN sanitizer");
        check("LTE".equals(QmiProbeSummaryParser.sanitizeNetworkType("LTE")), "LTE RAT allowed");
        check("".equals(QmiProbeSummaryParser.sanitizeNetworkType("18612345678")), "Digits-only rejected by RAT sanitizer");
    }
}
