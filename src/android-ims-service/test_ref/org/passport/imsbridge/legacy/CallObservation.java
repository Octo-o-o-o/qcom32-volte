package org.passport.imsbridge.legacy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, bounded call-list snapshot. Numbers/names are memory-only, never diagnostic text. */
public final class CallObservation {
    public static final int ACTIVE = 0, HOLDING = 1, DIALING = 2, ALERTING = 3,
            INCOMING = 4, WAITING = 5, END = 6;
    public static final class Details {
        public final Integer callType, domain, substate, mediaId;
        public final List<String> extras;
        public final ServiceStatusObservation localAbility, peerAbility;
        private Details(Integer type, Integer domain, Integer substate, Integer mediaId, List<String> extras,
                ServiceStatusObservation local, ServiceStatusObservation peer) {
            this.callType = type; this.domain = domain; this.substate = substate; this.mediaId = mediaId;
            this.extras = Collections.unmodifiableList(extras); this.localAbility = local; this.peerAbility = peer;
        }
    }
    public static final class Call {
        public final int index, state;
        public final Boolean incoming, multiparty, voice;
        public final String number, name;
        public final Integer numberPresentation, namePresentation, toa, failCause;
        public final Details details;
        private Call(int index, int state, Boolean incoming, Boolean multiparty, Boolean voice, String number,
                String name, Integer np, Integer cp, Integer toa, Integer failCause, Details details) {
            this.index = index; this.state = state; this.incoming = incoming; this.multiparty = multiparty;
            this.voice = voice; this.number = number; this.name = name; this.numberPresentation = np;
            this.namePresentation = cp; this.toa = toa; this.failCause = failCause; this.details = details;
        }
    }
    public final List<Call> calls;
    private CallObservation(List<Call> calls) { this.calls = Collections.unmodifiableList(calls); }
    public static CallObservation decodePayload(byte[] bytes) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(bytes);
        List<Call> calls = new ArrayList<>(); Set<Integer> ids = new HashSet<>();
        while (p.hasMore()) {
            int key = p.key();
            if (key == 18) {
                if (calls.size() >= 16) throw new IOException("Too many calls");
                Call call = call(p.bytes());
                if (!ids.add(call.index)) throw new IOException("Duplicate call id");
                calls.add(call);
            } else { ServiceStatusObservation.rejectKnownWire(key, 2); p.skip(key); }
        }
        return new CallObservation(calls);
    }
    private static Call call(byte[] bytes) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(bytes);
        Integer index = null, state = null, np = null, cp = null, toa = null, failCause = null;
        Boolean incoming = null, multi = null, voice = null;
        String number = null, name = null; Details details = null; int seen = 0;
        while (p.hasMore()) {
            int key = p.key(); seen = ServiceStatusObservation.unique(seen, key, 14);
            switch (key) {
                case 8: state = p.integer(); break;
                case 21: index = p.fixed32(); break;
                case 29: toa = p.fixed32(); break;
                case 32: multi = p.flag(); break;
                case 40: incoming = p.flag(); break;
                case 53: p.fixed32(); break; // ALS
                case 56: voice = p.flag(); break;
                case 64: p.flag(); break; // privacy flag, not caller presentation
                case 74: number = boundedString(p); break;
                case 85: np = p.fixed32(); break;
                case 90: name = boundedString(p); break;
                case 101: cp = p.fixed32(); break;
                case 106: details = details(p.bytes()); break;
                case 114: failCause = failure(p.bytes()); break;
                default: ServiceStatusObservation.rejectKnownWire(key, 1,2,3,4,5,6,7,8,9,10,11,12,13,14); p.skip(key);
            }
        }
        if (index == null || index <= 0 || index > 255 || state == null || state > END)
            throw new IOException("Missing/invalid call index or state");
        return new Call(index, state, incoming, multi, voice, number, name, np, cp, toa, failCause, details);
    }
    private static String boundedString(LegacyFrameCodec.Proto p) throws IOException {
        String value = p.string();
        if (value.length() > 1024) throw new IOException("Call string too large");
        return value;
    }
    private static Details details(byte[] bytes) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(bytes);
        Integer type = null, domain = null, substate = null, mediaId = null, count = null;
        ServiceStatusObservation local = null, peer = null;
        List<String> extras = new ArrayList<>(); int seen = 0;
        while (p.hasMore()) {
            int key = p.key();
            if ((key >>> 3) != 4) seen = ServiceStatusObservation.unique(seen, key, 8);
            switch (key) {
                case 8: type = p.integer(); break;
                case 16: domain = p.integer(); break;
                case 29: count = p.fixed32(); break;
                case 34:
                    if (extras.size() >= 32) throw new IOException("Too many extras");
                    extras.add(boundedString(p)); break;
                case 42: local = ServiceStatusObservation.decodePayload(p.bytes()); break;
                case 50: peer = ServiceStatusObservation.decodePayload(p.bytes()); break;
                case 56: substate = p.integer(); break;
                case 69: mediaId = p.fixed32(); break;
                default: ServiceStatusObservation.rejectKnownWire(key, 1,2,3,4,5,6,7,8); p.skip(key);
            }
        }
        if (count != null && count != extras.size()) throw new IOException("Extras length mismatch");
        return new Details(type, domain, substate, mediaId, extras, local, peer);
    }
    private static Integer failure(byte[] bytes) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(bytes); Integer cause = null; int seen = 0;
        while (p.hasMore()) {
            int key = p.key(); seen = ServiceStatusObservation.unique(seen, key, 3);
            if (key == 8) cause = p.integer();
            else if (key == 18 || key == 26) p.bytes(); // no error text/PII propagation
            else { ServiceStatusObservation.rejectKnownWire(key, 1,2,3); p.skip(key); }
        }
        return cause;
    }
}
