package org.passport.imsbridge.legacy;

import java.io.IOException;

/** Evidence-only decoding. No LTE tech, voice support or Android registration is inferred here. */
public final class RegistrationObservation {
    public enum State { UNKNOWN, REGISTERED, NOT_REGISTERED, REGISTERING }
    public final State state;
    public final boolean hasVendorError;
    public final int vendorError;
    public final boolean hasState;

    private RegistrationObservation(State state, boolean present, boolean hasError, int error) {
        this.state = state; this.hasState = present; this.hasVendorError = hasError; this.vendorError = error;
    }

    public static RegistrationObservation decode(LegacyFrameCodec.Frame frame, Integer expectedToken)
            throws IOException {
        boolean response = frame.type == 2 && frame.id == LegacyFrameCodec.REGISTRATION_QUERY
                && expectedToken != null && frame.token == expectedToken;
        boolean unsol = frame.type == 3 && frame.id == LegacyFrameCodec.REGISTRATION_UNSOL;
        if ((!response && !unsol) || frame.error != 0) throw new IOException("Not a successful registration observation");
        return decodePayload(frame.payload());
    }

    public static RegistrationObservation decodePayload(byte[] payload) throws IOException {
        LegacyFrameCodec.Proto p = new LegacyFrameCodec.Proto(payload);
        State state = State.UNKNOWN;
        int error = 0;
        boolean hasError = false;
        boolean present = false;
        int seen = 0;
        while (p.hasMore()) {
            int key = p.key();
            seen = ServiceStatusObservation.unique(seen, key, 3);
            if (key == 8) {
                present = true;
                int value = p.integer();
                state = value == 1 ? State.REGISTERED : value == 2 ? State.NOT_REGISTERED
                        : value == 3 ? State.REGISTERING : State.UNKNOWN;
            } else if (key == 21) {
                error = p.fixed32(); hasError = true;
            } else {
                // Vendor text can contain identities; validate/skip without retaining or logging it.
                if ((key >>> 3) == 1 || (key >>> 3) == 2 || ((key >>> 3) == 3 && key != 26)) {
                    throw new IOException("Wrong Registration wire type");
                }
                p.skip(key);
            }
        }
        // The old generated DTO defaults state to 1 even when absent. Never adopt that default.
        return new RegistrationObservation(state, present, hasError, error);
    }
}
