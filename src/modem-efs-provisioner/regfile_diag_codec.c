#include "regfile_diag_codec.h"

static void put_u32_le(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xffu);
    p[1] = (uint8_t)((v >> 8) & 0xffu);
    p[2] = (uint8_t)((v >> 16) & 0xffu);
    p[3] = (uint8_t)((v >> 24) & 0xffu);
}

static uint32_t get_u32_le(const uint8_t *p) {
    return (uint32_t)p[0]
         | ((uint32_t)p[1] << 8)
         | ((uint32_t)p[2] << 16)
         | ((uint32_t)p[3] << 24);
}

/* Unframed raw packet builders (4b 13 <subcmd> 00 ...), matching wide_diag_codec.c contract. */
int pp_regfile_build_open(uint8_t *out, size_t cap, int32_t oflag, int32_t mode, const char *path) {
    size_t plen, raw_len, i;
    if (!out || !path || path[0] != '/') return -1;
    if (!(oflag == PP_REGFILE_O_RDONLY || oflag == PP_REGFILE_O_WRONLY_CREAT_TRUNC)) return -1;
    if (oflag == PP_REGFILE_O_RDONLY && mode != 0) return -1;
    if (oflag == PP_REGFILE_O_WRONLY_CREAT_TRUNC && mode != PP_REGFILE_MODE_0666) return -1;
    plen = pp_cstr_len(path);
    if (plen < 5u || plen > PP_WIDE_MAX_PATH || path[plen] != '\0') return -1;
    raw_len = 12u + plen + 1u;
    if (cap < raw_len || raw_len > PP_WIDE_MAX_PAYLOAD) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 0x02u; out[3] = 0x00u;
    put_u32_le(out + 4u, (uint32_t)oflag);
    put_u32_le(out + 8u, (uint32_t)mode);
    for (i = 0u; i <= plen; ++i) out[12u + i] = (uint8_t)path[i];
    return (int)raw_len;
}

int pp_regfile_parse_open(const uint8_t *pkt, size_t n, int32_t *fd_out, uint32_t *errno_out) {
    int32_t fd;
    uint32_t err;
    if (!pkt || !fd_out || !errno_out || n != 12u) return -1;
    if (pkt[0] != 0x4bu || pkt[1] != 0x13u || pkt[2] != 0x02u || pkt[3] != 0x00u) return -1;
    fd = (int32_t)get_u32_le(pkt + 4u);
    err = get_u32_le(pkt + 8u);
    if (err == 0u && (fd < 0 || fd > PP_REGFILE_MAX_DIAG_FD)) return -1;
    *fd_out = fd;
    *errno_out = err;
    return 0;
}

int pp_regfile_build_read(uint8_t *out, size_t cap, int32_t fd, uint32_t nbyte, uint32_t offset) {
    if (!out || cap < 16u || fd < 0 || fd > PP_REGFILE_MAX_DIAG_FD) return -1;
    if (nbyte == 0u || nbyte > PP_REGFILE_IO_CHUNK) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 0x04u; out[3] = 0x00u;
    put_u32_le(out + 4u, (uint32_t)fd);
    put_u32_le(out + 8u, nbyte);
    put_u32_le(out + 12u, offset);
    return 16;
}

int pp_regfile_parse_read(const uint8_t *pkt, size_t n, int32_t expected_fd, uint32_t expected_offset,
                          int32_t *bytes_read_out, uint32_t *errno_out,
                          uint8_t *data_out, size_t data_cap) {
    int32_t fd, br;
    uint32_t off, err;
    size_t i;
    if (!pkt || !bytes_read_out || !errno_out || n < 20u) return -1;
    if (pkt[0] != 0x4bu || pkt[1] != 0x13u || pkt[2] != 0x04u || pkt[3] != 0x00u) return -1;
    fd = (int32_t)get_u32_le(pkt + 4u);
    off = get_u32_le(pkt + 8u);
    br = (int32_t)get_u32_le(pkt + 12u);
    err = get_u32_le(pkt + 16u);
    if (fd != expected_fd || off != expected_offset) return -1;
    if (err == 0u) {
        if (br < 0 || (size_t)br > PP_REGFILE_IO_CHUNK) return -1;
        if (n != 20u + (size_t)br) return -1;
        if (br > 0) {
            if (!data_out || data_cap < (size_t)br) return -1;
            for (i = 0u; i < (size_t)br; ++i) data_out[i] = pkt[20u + i];
        }
    } else {
        if (n != 20u) return -1;
    }
    *bytes_read_out = br;
    *errno_out = err;
    return 0;
}

int pp_regfile_build_write(uint8_t *out, size_t cap, int32_t fd, uint32_t offset,
                           const uint8_t *data, uint32_t data_len) {
    uint32_t i;
    size_t raw_len;
    if (!out || !data || fd < 0 || fd > PP_REGFILE_MAX_DIAG_FD) return -1;
    if (data_len == 0u || data_len > PP_REGFILE_IO_CHUNK) return -1;
    if (offset > PP_REGFILE_MAX_SIZE || offset + data_len > PP_REGFILE_MAX_SIZE) return -1;
    raw_len = 12u + (size_t)data_len;
    if (cap < raw_len || raw_len > PP_WIDE_MAX_PAYLOAD) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 0x05u; out[3] = 0x00u;
    put_u32_le(out + 4u, (uint32_t)fd);
    put_u32_le(out + 8u, offset);
    for (i = 0u; i < data_len; ++i) out[12u + i] = data[i];
    return (int)raw_len;
}

int pp_regfile_parse_write(const uint8_t *pkt, size_t n, int32_t expected_fd, uint32_t expected_offset,
                           int32_t *bytes_written_out, uint32_t *errno_out) {
    int32_t fd, bw;
    uint32_t off, err;
    if (!pkt || !bytes_written_out || !errno_out || n != 20u) return -1;
    if (pkt[0] != 0x4bu || pkt[1] != 0x13u || pkt[2] != 0x05u || pkt[3] != 0x00u) return -1;
    fd = (int32_t)get_u32_le(pkt + 4u);
    off = get_u32_le(pkt + 8u);
    bw = (int32_t)get_u32_le(pkt + 12u);
    err = get_u32_le(pkt + 16u);
    if (fd != expected_fd || off != expected_offset) return -1;
    if (err == 0u && (bw <= 0 || (uint32_t)bw > PP_REGFILE_IO_CHUNK)) return -1;
    *bytes_written_out = bw;
    *errno_out = err;
    return 0;
}

int pp_regfile_build_close(uint8_t *out, size_t cap, int32_t fd) {
    if (!out || cap < 8u || fd < 0 || fd > PP_REGFILE_MAX_DIAG_FD) return -1;
    out[0] = 0x4bu; out[1] = 0x13u; out[2] = 0x03u; out[3] = 0x00u;
    put_u32_le(out + 4u, (uint32_t)fd);
    return 8;
}

int pp_regfile_parse_close(const uint8_t *pkt, size_t n, uint32_t *errno_out) {
    if (!pkt || !errno_out || n != 8u) return -1;
    if (pkt[0] != 0x4bu || pkt[1] != 0x13u || pkt[2] != 0x03u || pkt[3] != 0x00u) return -1;
    *errno_out = get_u32_le(pkt + 4u);
    return 0;
}

/* FIPS 180-4 SHA-256 */
static uint32_t rotr32(uint32_t x, unsigned n) {
    return (x >> n) | (x << (32u - n));
}

static void sha256_block(uint32_t h[8], const uint8_t block[64]) {
    static const uint32_t K[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
    };
    uint32_t w[64];
    uint32_t a, b, c, d, e, f, g, hh, t1, t2;
    unsigned i;
    for (i = 0u; i < 16u; ++i) {
        w[i] = ((uint32_t)block[i * 4u] << 24)
             | ((uint32_t)block[i * 4u + 1u] << 16)
             | ((uint32_t)block[i * 4u + 2u] << 8)
             | (uint32_t)block[i * 4u + 3u];
    }
    for (i = 16u; i < 64u; ++i) {
        uint32_t s0 = rotr32(w[i - 15u], 7u) ^ rotr32(w[i - 15u], 18u) ^ (w[i - 15u] >> 3);
        uint32_t s1 = rotr32(w[i - 2u], 17u) ^ rotr32(w[i - 2u], 19u) ^ (w[i - 2u] >> 10);
        w[i] = w[i - 16u] + s0 + w[i - 7u] + s1;
    }
    a = h[0]; b = h[1]; c = h[2]; d = h[3];
    e = h[4]; f = h[5]; g = h[6]; hh = h[7];
    for (i = 0u; i < 64u; ++i) {
        uint32_t S1 = rotr32(e, 6u) ^ rotr32(e, 11u) ^ rotr32(e, 25u);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t S0 = rotr32(a, 2u) ^ rotr32(a, 13u) ^ rotr32(a, 22u);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        t1 = hh + S1 + ch + K[i] + w[i];
        t2 = S0 + maj;
        hh = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d;
    h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
}

void pp_regfile_sha256_hex(const uint8_t *data, size_t len, char hex_out[65]) {
    static const char HEX[16] = "0123456789abcdef";
    uint32_t h[8] = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    };
    uint8_t tail[128];
    uint64_t bit_len = (uint64_t)len * 8ull;
    size_t full_len = (len & ~(size_t)63u);
    size_t rem = len - full_len;
    size_t pad_len, i;

    for (i = 0u; i < full_len; i += 64u) {
        sha256_block(h, data + i);
    }
    for (i = 0u; i < sizeof(tail); ++i) tail[i] = 0u;
    for (i = 0u; i < rem; ++i) tail[i] = data[full_len + i];
    tail[rem] = 0x80u;
    pad_len = (rem < 56u) ? 64u : 128u;
    for (i = 0u; i < 8u; ++i) {
        tail[pad_len - 8u + i] = (uint8_t)((bit_len >> (56u - i * 8u)) & 0xffull);
    }
    sha256_block(h, tail);
    if (pad_len == 128u) sha256_block(h, tail + 64u);

    for (i = 0u; i < 8u; ++i) {
        uint32_t v = h[i];
        hex_out[i * 8u + 0u] = HEX[(v >> 28) & 0xfu];
        hex_out[i * 8u + 1u] = HEX[(v >> 24) & 0xfu];
        hex_out[i * 8u + 2u] = HEX[(v >> 20) & 0xfu];
        hex_out[i * 8u + 3u] = HEX[(v >> 16) & 0xfu];
        hex_out[i * 8u + 4u] = HEX[(v >> 12) & 0xfu];
        hex_out[i * 8u + 5u] = HEX[(v >> 8) & 0xfu];
        hex_out[i * 8u + 6u] = HEX[(v >> 4) & 0xfu];
        hex_out[i * 8u + 7u] = HEX[v & 0xfu];
    }
    hex_out[64] = '\0';
}
