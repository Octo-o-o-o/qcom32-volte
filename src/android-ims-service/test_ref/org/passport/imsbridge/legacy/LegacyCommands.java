package org.passport.imsbridge.legacy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Typed offline command encoding. This class has no transport; no generic request escape hatch. */
public final class LegacyCommands {
    private LegacyCommands() {}
    public static byte[] dial(int token, String address, int modernCallType, int clir) {
        if (address == null || !address.matches("\\+?[0-9]{1,32}") || clir < 0 || clir > 2 || modernCallType != 2)
            throw new IllegalArgumentException("Only explicit normal voice dial format supported");
        Writer details = new Writer().integer(1, 0).integer(2, 2); // VOICE, PS; never AUTO/CS
        return LegacyFrameCodec.encodeRequest(token, 2, new Writer().string(1, address).fixed(2, clir)
                .bytes(3, details.value()).value());
    }
    public static byte[] answer(int token) {
        return LegacyFrameCodec.encodeRequest(token, 3, new Writer().integer(1, 0).value());
    }
    public static byte[] hangup(int token, int callId, boolean reject) {
        Writer w = new Writer().fixed(1, validId(callId));
        if (reject) w.bytes(5, new Writer().integer(1, 502).value()); // vendor USER_REJECT
        return LegacyFrameCodec.encodeRequest(token, 4, w.value());
    }
    public static byte[] hold(int token, int callId) { return indexed(token, 37, callId); }
    public static byte[] resume(int token, int callId) { return indexed(token, 38, callId); }
    private static byte[] indexed(int token, int request, int callId) {
        return LegacyFrameCodec.encodeRequest(token, request, new Writer().fixed(1, validId(callId)).value());
    }
    private static int validId(int id) {
        if (id < 1 || id > 255) throw new IllegalArgumentException("Invalid call id");
        return id;
    }
    public static byte[] dtmf(int token, char digit, boolean continuous) {
        if ("0123456789*#ABCD".indexOf(digit) < 0) throw new IllegalArgumentException("Invalid DTMF");
        return LegacyFrameCodec.encodeRequest(token, continuous ? 14 : 13, new Writer().string(1, "" + digit).value());
    }
    public static byte[] stopDtmf(int token) { return LegacyFrameCodec.encodeRequest(token, 15, new byte[0]); }

    // Minimal writer only for fields present in the extracted stock schema.
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private void varint(long value) {
            while ((value & ~127L) != 0) { out.write((int)value & 127 | 128); value >>>= 7; }
            out.write((int)value);
        }
        Writer integer(int field, int value) { varint(field << 3); varint(value); return this; }
        Writer fixed(int field, int value) {
            varint(field << 3 | 5); for (int i = 0; i < 4; i++) out.write(value >>> (8 * i) & 255); return this;
        }
        Writer bytes(int field, byte[] value) {
            varint(field << 3 | 2); varint(value.length); out.write(value, 0, value.length); return this;
        }
        Writer string(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
        byte[] value() { return out.toByteArray(); }
    }
}
