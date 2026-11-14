#include "wide_diag_codec.h"

static uint16_t u16(const uint8_t *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}
static uint32_t u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void put16(uint8_t *p, uint16_t x) {
    p[0] = (uint8_t)(x & 0xffu);
    p[1] = (uint8_t)((x >> 8) & 0xffu);
}
static void put32(uint8_t *p, uint32_t x) {
    for (unsigned i = 0; i < 4u; ++i) p[i] = (uint8_t)((x >> (8u * i)) & 0xffu);
}
static int check_hdr(const uint8_t *p, size_t n, uint8_t cmd) {
    return p && n >= 4u && p[0] == 0x4bu && p[1] == 0x13u && p[2] == cmd && p[3] == 0u;
}
size_t pp_cstr_len(const char *s) {
    if (!s) return 0u;
    size_t n = 0u;
    while (s[n] != '\0' && n <= PP_WIDE_MAX_PATH) ++n;
    return n;
}
static int valid_efs_path(const char *path, size_t *len_with_nul) {
    if (!path || path[0] != '/') return 0;
    size_t n = pp_cstr_len(path);
    if (n < 5u || n > PP_WIDE_MAX_PATH || path[n] != '\0') return 0;
    *len_with_nul = n + 1u;
    return 1;
}

uint16_t pp_wide_crc_x25(const uint8_t *data, size_t size) {
    uint16_t value = 0xffffu;
    for (size_t i = 0u; i < size; ++i) {
        value ^= data[i];
        for (unsigned bit = 0u; bit < 8u; ++bit)
            value = (uint16_t)((value >> 1) ^ ((value & 1u) ? 0x8408u : 0u));
    }
    return (uint16_t)(value ^ 0xffffu);
}

int pp_wide_hdlc_encode(const uint8_t *data, size_t size, uint8_t *out, size_t capacity) {
    if (!data || !out || !size || size > PP_WIDE_MAX_PAYLOAD) return -1;
    uint16_t crc = pp_wide_crc_x25(data, size);
    size_t need = 1u;
    for (size_t i = 0u; i < size + 2u; ++i) {
        uint8_t b = i < size ? data[i] : (uint8_t)(crc >> (8u * (i - size)));
        need += (b == 0x7eu || b == 0x7du) ? 2u : 1u;
    }
    if (need > capacity) return -1;
    size_t pos = 0u;
    for (size_t i = 0u; i < size + 2u; ++i) {
        uint8_t b = i < size ? data[i] : (uint8_t)(crc >> (8u * (i - size)));
        if (b == 0x7eu || b == 0x7du) {
            out[pos++] = 0x7du;
            out[pos++] = (uint8_t)(b ^ 0x20u);
        } else {
            out[pos++] = b;
        }
    }
    out[pos++] = 0x7eu;
    return (int)pos;
}

int pp_wide_frame(const uint8_t *data, size_t size, uint8_t *out, size_t capacity) {
    if (!out || capacity < 5u) return -1;
    int encoded = pp_wide_hdlc_encode(data, size, out + 4u, capacity - 4u);
    if (encoded < 0) return -1;
    put32(out, 0x20u);
    return encoded + 4;
}

static void feed_wide(pp_wide_stream *st, const uint8_t *data, size_t size,
                      pp_wide_packet_fn emit, void *ctx) {
    for (size_t i = 0u; i < size; ++i) {
        uint8_t b = data[i];
        if (b == 0x7eu) {
            if (st->dropping) {
                ++st->oversized;
            } else if (st->escaped || (st->length && st->length < 3u)) {
                ++st->malformed;
            } else if (st->length >= 3u) {
                size_t payload = st->length - 2u;
                uint16_t supplied = (uint16_t)((uint16_t)st->bytes[payload] |
                                               ((uint16_t)st->bytes[payload + 1u] << 8));
                if (pp_wide_crc_x25(st->bytes, payload) != supplied) {
                    ++st->bad_crc;
                } else {
                    ++st->accepted;
                    emit(st->bytes, payload, ctx);
                }
            }
            st->length = 0u;
            st->escaped = 0u;
            st->dropping = 0u;
            continue;
        }
        if (st->dropping) continue;
        if (st->escaped) {
            b ^= 0x20u;
            st->escaped = 0u;
        } else if (b == 0x7du) {
            st->escaped = 1u;
            continue;
        }
        if (st->length == sizeof(st->bytes)) {
            st->dropping = 1u;
            continue;
        }
        st->bytes[st->length++] = b;
    }
}

int pp_wide_receive(pp_wide_stream *s, const uint8_t *p, size_t n,
                    pp_wide_packet_fn emit, void *ctx) {
    if (!s || !p || !emit || n < 4u || n > 131072u) return -1;
    uint32_t type = u32(p);
    if (type == 1u || type == 2u || type == 4u) return -2;
    if (type != 0x20u || n < 8u) return -7;
    uint32_t chunks = u32(p + 4u);
    if (chunks > 32u) return -3;
    size_t pos = 8u;
    for (uint32_t i = 0u; i < chunks; ++i) {
        if (n - pos < 4u) return -4;
        uint32_t len = u32(p + pos);
        pos += 4u;
        if (len > n - pos) return -5;
        pos += len;
    }
    if (pos != n) return -6;
    unsigned before = s->accepted;
    pos = 8u;
    for (uint32_t i = 0u; i < chunks; ++i) {
        uint32_t len = u32(p + pos);
        pos += 4u;
        feed_wide(s, p + pos, (size_t)len, emit, ctx);
        pos += len;
    }
    return (int)(s->accepted - before);
}

int pp_wide_build_stat(uint8_t *out, size_t cap, const char *path) {
    size_t plen = 0u;
    if (!out || !valid_efs_path(path, &plen) || cap < 4u + plen) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 15u; out[3] = 0u;
    for (size_t i = 0u; i < plen; ++i) out[4u + i] = (uint8_t)path[i];
    return (int)(4u + plen);
}

int pp_wide_parse_stat(const uint8_t *pkt, size_t n, pp_wide_stat *st) {
    if (st) { st->diag_errno = 0xffffffffu; st->mode = 0u; st->size = 0u; st->present = 0; }
    if (!st || !check_hdr(pkt, n, 15u) || n < 8u) return -1;
    uint32_t err = u32(pkt + 4u);
    st->diag_errno = err;
    if (err != 0u) {
        if (n != 32u && n != 8u) return -2;
        st->present = 0;
        return 0;
    }
    if (n != 32u) return -3;
    st->mode = u32(pkt + 8u);
    st->size = u32(pkt + 12u);
    st->present = 1;
    return 0;
}

int pp_wide_build_get(uint8_t *out, size_t cap, const char *path, uint32_t data_len, uint16_t seq) {
    size_t plen = 0u;
    if (!out || !valid_efs_path(path, &plen) || data_len > PP_WIDE_MAX_ITEM) return -1;
    if (cap < 14u + plen) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 39u; out[3] = 0u;
    put32(out + 4u, data_len);
    put32(out + 8u, (uint32_t)plen);
    put16(out + 12u, seq);
    for (size_t i = 0u; i < plen; ++i) out[14u + i] = (uint8_t)path[i];
    return (int)(14u + plen);
}

int pp_wide_parse_get(const uint8_t *pkt, size_t n, uint16_t expected_seq,
                      int32_t *num_bytes_out, uint32_t *errno_out,
                      uint8_t *data_out, size_t data_cap) {
    if (num_bytes_out) *num_bytes_out = -1;
    if (errno_out) *errno_out = 0xffffffffu;
    if (!num_bytes_out || !errno_out || !check_hdr(pkt, n, 39u) || n < 14u) return -1;
    if (u16(pkt + 12u) != expected_seq) return -2;
    int32_t num_bytes = (int32_t)u32(pkt + 4u);
    uint32_t err = u32(pkt + 8u);
    *num_bytes_out = num_bytes;
    *errno_out = err;
    if (err != 0u) {
        if (n != 14u) return -3;
        return 0;
    }
    if (num_bytes < 0 || (uint32_t)num_bytes > PP_WIDE_MAX_ITEM) return -4;
    if (n != 14u + (size_t)num_bytes) return -5;
    if (num_bytes > 0) {
        if (!data_out || data_cap < (size_t)num_bytes) return -6;
        for (int32_t i = 0; i < num_bytes; ++i) data_out[i] = pkt[14u + (size_t)i];
    }
    return 0;
}

int pp_wide_build_put(uint8_t *out, size_t cap, const char *path,
                      const uint8_t *data, uint32_t data_len,
                      uint32_t flags, uint16_t seq) {
    size_t plen = 0u;
    if (!out || !valid_efs_path(path, &plen) || data_len > PP_WIDE_MAX_ITEM) return -1;
    if (data_len > 0u && !data) return -1;
    size_t total = 14u + (size_t)data_len + plen;
    if (cap < total || total > PP_WIDE_MAX_PAYLOAD) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 38u; out[3] = 0u;
    put32(out + 4u, data_len);
    put32(out + 8u, flags);
    put16(out + 12u, seq);
    for (uint32_t i = 0u; i < data_len; ++i) out[14u + i] = data[i];
    for (size_t i = 0u; i < plen; ++i) out[14u + (size_t)data_len + i] = (uint8_t)path[i];
    return (int)total;
}

int pp_wide_parse_put(const uint8_t *pkt, size_t n, uint16_t expected_seq, uint32_t *errno_out) {
    if (errno_out) *errno_out = 0xffffffffu;
    if (!errno_out || !check_hdr(pkt, n, 38u) || n != 10u) return -1;
    if (u16(pkt + 4u) != expected_seq) return -2;
    *errno_out = u32(pkt + 6u);
    return 0;
}

int pp_wide_build_unlink(uint8_t *out, size_t cap, const char *path) {
    size_t plen = 0u;
    if (!out || !valid_efs_path(path, &plen) || cap < 4u + plen) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 8u; out[3] = 0u;
    for (size_t i = 0u; i < plen; ++i) out[4u + i] = (uint8_t)path[i];
    return (int)(4u + plen);
}

int pp_wide_parse_unlink(const uint8_t *pkt, size_t n, uint32_t *errno_out) {
    if (errno_out) *errno_out = 0xffffffffu;
    if (!errno_out || !check_hdr(pkt, n, 8u) || n != 8u) return -1;
    *errno_out = u32(pkt + 4u);
    return 0;
}

int pp_wide_build_sync_start(uint8_t *out, size_t cap, const char *path, uint16_t seq) {
    size_t plen = 0u;
    if (!out || !valid_efs_path(path, &plen) || cap < 6u + plen) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 48u; out[3] = 0u;
    put16(out + 4u, seq);
    for (size_t i = 0u; i < plen; ++i) out[6u + i] = (uint8_t)path[i];
    return (int)(6u + plen);
}

int pp_wide_parse_sync_start(const uint8_t *pkt, size_t n, uint16_t expected_seq,
                             uint32_t *token_out, uint32_t *errno_out) {
    if (token_out) *token_out = 0u;
    if (errno_out) *errno_out = 0xffffffffu;
    if (!token_out || !errno_out || !check_hdr(pkt, n, 48u) || n != 14u) return -1;
    if (u16(pkt + 4u) != expected_seq) return -2;
    *token_out = u32(pkt + 6u);
    *errno_out = u32(pkt + 10u);
    return 0;
}

int pp_wide_build_sync_status(uint8_t *out, size_t cap, const char *path, uint16_t seq, uint32_t token) {
    size_t plen = 0u;
    if (!out || !valid_efs_path(path, &plen) || cap < 10u + plen) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 49u; out[3] = 0u;
    put16(out + 4u, seq);
    put32(out + 6u, token);
    for (size_t i = 0u; i < plen; ++i) out[10u + i] = (uint8_t)path[i];
    return (int)(10u + plen);
}

int pp_wide_parse_sync_status(const uint8_t *pkt, size_t n, uint16_t expected_seq,
                              uint8_t *status_out, uint32_t *errno_out) {
    if (status_out) *status_out = 0xffu;
    if (errno_out) *errno_out = 0xffffffffu;
    if (!status_out || !errno_out || !check_hdr(pkt, n, 49u) || n != 11u) return -1;
    if (u16(pkt + 4u) != expected_seq) return -2;
    *status_out = pkt[6u];
    *errno_out = u32(pkt + 7u);
    return 0;
}
