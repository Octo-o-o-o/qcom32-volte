package org.passport.imsbridge.legacy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Presence-aware parsing; no Android capabilities are inferred while decoding. */
public final class ServiceStatusObservation {
    public static final class Access {
        public final Integer rat, status, restriction;
        public final RegistrationObservation registration;
        private Access(Integer rat, Integer status, Integer restriction, RegistrationObservation registration) {
            this.rat = rat; this.status = status; this.restriction = restriction; this.registration = registration;
        }
    }
    public static final class Service {
        public final Boolean valid;
        public final Integer type, callType, status, restriction;
        public final List<Access> access;
        private Service(Boolean valid, Integer type, Integer callType, Integer status, Integer restriction, List<Access> access) {
            this.valid = valid; this.type = type; this.callType = callType; this.status = status;
            this.restriction = restriction; this.access = Collections.unmodifiableList(access);
        }
    }
    public final List<Service> services;
    private ServiceStatusObservation(List<Service> services) { this.services = Collections.unmodifiableList(services); }

    public static ServiceStatusObservation decodePayload(byte[] payload) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(payload);
        List<Service> services = new ArrayList<>();
        while (p.hasMore()) {
            int key = p.key();
            if (key == 18) {
                if (services.size() >= 32) throw new IOException("Too many service entries");
                services.add(service(p.bytes()));
            } else { rejectKnownWire(key, 2); p.skip(key); }
        }
        return new ServiceStatusObservation(services);
    }

    private static Service service(byte[] bytes) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(bytes);
        Boolean valid = null;
        Integer type = null, callType = null, status = null, restriction = null;
        int seen = 0;
        List<Access> access = new ArrayList<>();
        while (p.hasMore()) {
            int key = p.key();
            if ((key >>> 3) != 7) seen = unique(seen, key, 6);
            switch (key) {
                case 8: valid = p.flag(); break;
                case 16: type = p.signedInt32(); break;
                case 24: callType = p.signedInt32(); break;
                case 32: status = p.signedInt32(); break;
                case 42: p.bytes(); break; // opaque userdata deliberately discarded
                case 53: restriction = p.fixed32(); break;
                case 58:
                    if (access.size() >= 16) throw new IOException("Too many access entries");
                    access.add(access(p.bytes())); break;
                default: rejectKnownWire(key, 1, 2, 3, 4, 5, 6, 7); p.skip(key);
            }
        }
        return new Service(valid, type, callType, status, restriction, access);
    }

    private static Access access(byte[] bytes) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(bytes);
        Integer rat = null, status = null, restriction = null;
        RegistrationObservation registration = null;
        int seen = 0;
        while (p.hasMore()) {
            int key = p.key();
            seen = unique(seen, key, 4);
            switch (key) {
                case 8: rat = p.signedInt32(); break;
                case 16: status = p.signedInt32(); break;
                case 29: restriction = p.fixed32(); break;
                case 34: registration = RegistrationObservation.decodePayload(p.bytes()); break;
                default: rejectKnownWire(key, 1, 2, 3, 4); p.skip(key);
            }
        }
        return new Access(rat, status, restriction, registration);
    }

    static int unique(int seen, int key, int last) throws IOException {
        int field = key >>> 3;
        if (field > last) return seen;
        int mask = 1 << field;
        if ((seen & mask) != 0) throw new IOException("Duplicate scalar field");
        return seen | mask;
    }

    static void rejectKnownWire(int key, int... fields) throws IOException {
        for (int field : fields) if ((key >>> 3) == field) throw new IOException("Wrong known-field wire type");
    }
}
