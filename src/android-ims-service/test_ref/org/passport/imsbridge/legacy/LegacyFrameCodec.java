package org.passport.imsbridge.legacy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Bounded wire adapter for the device's ImsSenderRxr/ImsQmiIF schema. No socket API. */
public final class LegacyFrameCodec {
    public static final int MAX_FRAME_BYTES = 8192;
    public static final int REGISTRATION_QUERY = 1;
    public static final int SERVICE_STATUS_QUERY = 29;
    public static final int REGISTRATION_UNSOL = 204;
    public static final int SERVICE_STATUS_UNSOL = 210;
    public static final int CALL_LIST_QUERY = 6;
    public static final int CALL_LIST_UNSOL = 201;

    public static final class Frame {
        public final int token, type, id, error;
        private final byte[] payload;
        Frame(int token, int type, int id, int error, byte[] payload) {
            this.token = token; this.type = type; this.id = id; this.error = error;
            this.payload = payload.clone();
        }
        public byte[] payload() { return payload.clone(); }
    }

    /** Only the three read queries; never dial/registration-change requests. */
    public static byte[] encodeQuery(int token, int id) {
        if (id != REGISTRATION_QUERY && id != SERVICE_STATUS_QUERY && id != CALL_LIST_QUERY) {
            throw new IllegalArgumentException("Unsupported offline query");
        }
        return encodeRequest(token, id, new byte[0]);
    }

    // Only the typed command encoder can use this package-private entry point.
    static byte[] encodeRequest(int token, int id, byte[] payload) {
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write(13); // fixed32 field 1, LITTLE endian. It is not a varint token.
        for (int i = 0; i < 4; i++) tag.write(token >>> (8 * i) & 255);
        tag.write(16); tag.write(1); // request
        tag.write(24); tag.write(id);
        tag.write(32); tag.write(0);
        byte[] t = tag.toByteArray();
        byte[] frame = new byte[5 + t.length + payload.length];
        if (frame.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("Frame too large");
        int body = 1 + t.length + payload.length;
        for (int i = 0; i < 4; i++) frame[i] = (byte)(body >>> (24 - 8 * i));
        frame[4] = (byte)t.length;
        System.arraycopy(t, 0, frame, 5, t.length);
        System.arraycopy(payload, 0, frame, 5 + t.length, payload.length);
        return frame;
    }

    public static Frame read(InputStream in) throws IOException {
        byte[] length = fully(in, 4);
        long n = 0;
        for (byte b : length) n = n << 8 | b & 255;
        if (n < 2 || n > MAX_FRAME_BYTES - 4) throw new IOException("Invalid frame size");
        byte[] body = fully(in, (int)n);
        int tagLength = body[0] & 255;
        if (tagLength == 0 || tagLength > body.length - 1) throw new IOException("Invalid tag size");
        Proto tag = new Proto(Arrays.copyOfRange(body, 1, tagLength + 1));
        int token = 0, type = 0, id = 0, error = 0, seen = 0;
        while (tag.hasMore()) {
            int key = tag.key();
            if ((key >>> 3) <= 4 && (seen & (1 << ((key >>> 3) - 1))) != 0)
                throw new IOException("Duplicate MsgTag field");
            switch (key) {
                case 13: token = tag.fixed32(); seen |= 1; break;
                case 16: type = tag.integer(); seen |= 2; break;
                case 24: id = tag.integer(); seen |= 4; break;
                case 32: error = tag.integer(); seen |= 8; break;
                default:
                    if ((key >>> 3) <= 4) throw new IOException("Wrong MsgTag wire type");
                    tag.skip(key);
            }
        }
        if (seen != 15 || type < 1 || type > 3 || id <= 0) throw new IOException("Incomplete/invalid MsgTag");
        return new Frame(token, type, id, error, Arrays.copyOfRange(body, 1 + tagLength, body.length));
    }

    static byte[] fully(InputStream in, int n) throws IOException {
        byte[] result = new byte[n];
        int offset = 0;
        while (offset < n) {
            int count = in.read(result, offset, n - offset);
            if (count < 0) throw new EOFException("Truncated frame");
            if (count == 0) {
                int b = in.read();
                if (b < 0) throw new EOFException("Truncated frame");
                result[offset++] = (byte)b;
            } else offset += count;
        }
        return result;
    }

    static final class Proto {
        private final byte[] data;
        private int pos;
        Proto(byte[] data) throws IOException {
            if (data.length > MAX_FRAME_BYTES) throw new IOException("Oversized protobuf payload");
            this.data = data;
        }
        boolean hasMore() { return pos < data.length; }
        int octet() throws IOException {
            if (!hasMore()) throw new EOFException("Truncated protobuf field");
            return data[pos++] & 255;
        }
        long varint() throws IOException {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int b = octet();
                if (i == 9 && b > 1) throw new IOException("Varint overflow");
                value |= (long)(b & 127) << (7 * i);
                if ((b & 128) == 0) return value;
            }
            throw new IOException("Unterminated varint");
        }
        int integer() throws IOException {
            long value = varint();
            if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid enum/integer");
            return (int)value;
        }
        int signedInt32() throws IOException {
            long value = varint();
            if (value < Integer.MIN_VALUE || value > 0xffffffffL) throw new IOException("Invalid int32");
            return (int)value;
        }
        boolean flag() throws IOException {
            int value = integer();
            if (value > 1) throw new IOException("Invalid boolean");
            return value == 1;
        }
        byte[] bytes() throws IOException {
            int count = integer();
            if (count > data.length - pos) throw new EOFException("Truncated bytes field");
            byte[] value = Arrays.copyOfRange(data, pos, pos + count);
            pos += count;
            return value;
        }
        String string() throws IOException {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes())).toString();
        }
        int key() throws IOException {
            int key = integer();
            if ((key >>> 3) == 0) throw new IOException("Zero protobuf field");
            return key;
        }
        int fixed32() throws IOException {
            return octet() | octet() << 8 | octet() << 16 | octet() << 24;
        }
        void advance(int count) throws IOException {
            if (count < 0 || count > data.length - pos) throw new IOException("Invalid field length");
            pos += count;
        }
        void skip(int key) throws IOException {
            switch (key & 7) {
                case 0: varint(); break;
                case 1: advance(8); break;
                case 2: advance(integer()); break;
                case 5: advance(4); break;
                default: throw new IOException("Unsupported protobuf wire type");
            }
        }
    }
}
