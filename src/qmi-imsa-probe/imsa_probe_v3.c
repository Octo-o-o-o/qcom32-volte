#include "imsa_probe_v3.h"

extern int printf(const char *, ...);
extern int fflush(void *);

static void zero_mem(void *dst, size_t n) {
    uint8_t *p = (uint8_t *)dst;
    for (size_t i = 0u; i < n; ++i) p[i] = 0u;
}

static void copy_mem(void *dst, const void *src, size_t n) {
    uint8_t *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;
    for (size_t i = 0u; i < n; ++i) d[i] = s[i];
}

static uint16_t rd_u16(const uint8_t *p) {
    return (uint16_t)((unsigned)p[0] | ((unsigned)p[1] << 8));
}

static uint32_t rd_u32(const uint8_t *p) {
    return (uint32_t)p[0]
         | ((uint32_t)p[1] << 8)
         | ((uint32_t)p[2] << 16)
         | ((uint32_t)p[3] << 24);
}

static int parse_settings_rsp_tlv10(const uint8_t *sdu, size_t n) {
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x10u, &v, &l) == 1) {
        if (l == 4u) return (int)rd_u32(v);
        if (l >= 1u) return (int)v[0];
    }
    return -1;
}

static int contains_ascii_sub(const uint8_t *buf, uint16_t len, const char *needle) {
    size_t nlen = 0u;
    while (needle[nlen] != '\0') ++nlen;
    if (nlen == 0u || len < nlen) return 0;
    for (size_t i = 0u; i + nlen <= (size_t)len; ++i) {
        size_t j = 0u;
        while (j < nlen && buf[i + j] == (uint8_t)needle[j]) ++j;
        if (j == nlen) return 1;
    }
    return 0;
}

/* FIPS 180-4 SHA-256 */
static const uint32_t K256[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

static uint32_t k256_at(unsigned i) {
    return K256[i];
}

static uint32_t rotr32(uint32_t x, unsigned n) {
    return (x >> n) | (x << (32u - n));
}

static void sha256_block(uint32_t st[8], const uint8_t blk[64]) {
    uint32_t w[64];
    for (unsigned i = 0u; i < 16u; ++i) {
        w[i] = ((uint32_t)blk[i * 4u] << 24)
             | ((uint32_t)blk[i * 4u + 1u] << 16)
             | ((uint32_t)blk[i * 4u + 2u] << 8)
             | (uint32_t)blk[i * 4u + 3u];
    }
    for (unsigned i = 16u; i < 64u; ++i) {
        uint32_t s0 = rotr32(w[i - 15u], 7u) ^ rotr32(w[i - 15u], 18u) ^ (w[i - 15u] >> 3u);
        uint32_t s1 = rotr32(w[i - 2u], 17u) ^ rotr32(w[i - 2u], 19u) ^ (w[i - 2u] >> 10u);
        w[i] = w[i - 16u] + s0 + w[i - 7u] + s1;
    }
    uint32_t a = st[0], b = st[1], c = st[2], d = st[3];
    uint32_t e = st[4], f = st[5], g = st[6], h = st[7];
    for (unsigned i = 0u; i < 64u; ++i) {
        uint32_t S1 = rotr32(e, 6u) ^ rotr32(e, 11u) ^ rotr32(e, 25u);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + S1 + ch + k256_at(i) + w[i];
        uint32_t S0 = rotr32(a, 2u) ^ rotr32(a, 13u) ^ rotr32(a, 22u);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    st[0] += a; st[1] += b; st[2] += c; st[3] += d;
    st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}

void imsa_sha256_hex(const uint8_t *data, size_t len, char hex_out[65]) {
    static const char HEX[] = "0123456789abcdef";
    uint32_t st[8] = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    };
    uint8_t pad[128];
    size_t off = 0u;
    while (len - off >= 64u) {
        sha256_block(st, data + off);
        off += 64u;
    }
    size_t rem = len - off;
    zero_mem(pad, sizeof(pad));
    if (rem > 0u) copy_mem(pad, data + off, rem);
    pad[rem] = 0x80u;
    size_t total_pad = (rem < 56u) ? 64u : 128u;
    uint64_t bits = (uint64_t)len * 8ull;
    for (unsigned i = 0u; i < 8u; ++i) {
        pad[total_pad - 1u - i] = (uint8_t)((bits >> (i * 8u)) & 0xffu);
    }
    sha256_block(st, pad);
    if (total_pad == 128u) sha256_block(st, pad + 64u);
    for (unsigned i = 0u; i < 8u; ++i) {
        for (unsigned j = 0u; j < 4u; ++j) {
            uint8_t byte = (uint8_t)((st[i] >> (24u - j * 8u)) & 0xffu);
            hex_out[(i * 4u + j) * 2u] = HEX[byte >> 4u];
            hex_out[(i * 4u + j) * 2u + 1u] = HEX[byte & 0x0fu];
        }
    }
    hex_out[64] = '\0';
}

int qmi_build_sdu_req(uint8_t *out, size_t cap, uint16_t txn_id, uint16_t msg_id,
                      const uint8_t *tlvs, size_t tlvs_len) {
    if (!out || cap < 7u + tlvs_len || tlvs_len > 0xffffu) return -1;
    if (tlvs_len > 0u && !tlvs) return -2;
    out[0] = 0x00u;
    out[1] = (uint8_t)(txn_id & 0xffu);
    out[2] = (uint8_t)((txn_id >> 8) & 0xffu);
    out[3] = (uint8_t)(msg_id & 0xffu);
    out[4] = (uint8_t)((msg_id >> 8) & 0xffu);
    out[5] = (uint8_t)(tlvs_len & 0xffu);
    out[6] = (uint8_t)((tlvs_len >> 8) & 0xffu);
    if (tlvs_len > 0u) copy_mem(out + 7u, tlvs, tlvs_len);
    return (int)(7u + tlvs_len);
}

static int validate_sdu_rsp(const uint8_t *sdu, size_t n) {
    if (!sdu || n < 7u || n > QMI_RSP_MAX) return -1;
    if (sdu[0] != 0x02u) return -2;
    uint16_t tlv_total = rd_u16(sdu + 5u);
    if ((size_t)tlv_total != n - 7u) return -3;
    size_t at = 7u;
    while (at < n) {
        if (n - at < 3u) return -4;
        uint16_t len = rd_u16(sdu + at + 1u);
        at += 3u;
        if (len > n - at) return -5;
        at += len;
    }
    return 0;
}

int qmi_find_tlv(const uint8_t *sdu, size_t sdu_len, uint8_t target_type,
                 const uint8_t **val_out, uint16_t *len_out) {
    if (validate_sdu_rsp(sdu, sdu_len) != 0) return -1;
    size_t at = 7u;
    while (at < sdu_len) {
        uint8_t t = sdu[at];
        uint16_t len = rd_u16(sdu + at + 1u);
        at += 3u;
        if (t == target_type) {
            if (val_out) *val_out = sdu + at;
            if (len_out) *len_out = len;
            return 1;
        }
        at += len;
    }
    return 0;
}

int qmi_parse_result_tlv(const uint8_t *sdu, size_t sdu_len,
                         uint16_t *result_out, uint16_t *error_out) {
    const uint8_t *val = 0;
    uint16_t len = 0u;
    int found = qmi_find_tlv(sdu, sdu_len, 0x02u, &val, &len);
    if (found != 1 || len != 4u) return -1;
    if (result_out) *result_out = rd_u16(val);
    if (error_out) *error_out = rd_u16(val + 2u);
    return 0;
}

/* Redacts any run of >= 6 digits (such as MSISDN/IMSI) while preserving 3-digit SIP codes & text. */
void qmi_redact_ascii(const uint8_t *src, size_t src_len, char *dst, size_t dst_cap) {
    if (!dst || dst_cap == 0u) return;
    size_t out = 0u;
    size_t i = 0u;
    while (i < src_len && src[i] != 0u && out + 1u < dst_cap) {
        if (src[i] >= '0' && src[i] <= '9') {
            size_t j = i;
            while (j < src_len && src[j] >= '0' && src[j] <= '9') ++j;
            if (j - i >= 6u) {
                static const char RED[] = "<num>";
                for (size_t k = 0u; RED[k] && out + 1u < dst_cap; ++k) dst[out++] = RED[k];
                i = j;
                continue;
            }
        }
        uint8_t c = src[i++];
        if (c == '"' || c == '\\') {
            dst[out++] = '_';
        } else if (c >= 32u && c <= 126u) {
            dst[out++] = (char)c;
        } else {
            dst[out++] = '?';
        }
    }
    dst[out] = '\0';
}

int imsa_decode_reg_status(const uint8_t *sdu, size_t n, ImsaRegDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x10u, &v, &l) == 1 && l == 1u) {
        out->has_legacy_reg = 1;
        out->legacy_reg = v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 2u) {
        out->has_error_code = 1;
        out->error_code = rd_u16(v);
    }
    if (qmi_find_tlv(sdu, n, 0x12u, &v, &l) == 1 && l == 4u) {
        out->has_reg_status = 1;
        out->reg_status = rd_u32(v);
    }
    if (qmi_find_tlv(sdu, n, 0x13u, &v, &l) == 1) {
        out->has_error_string = 1;
        qmi_redact_ascii(v, l, out->error_string_redacted, sizeof(out->error_string_redacted));
    }
    return 0;
}

int imsa_decode_svc_status(const uint8_t *sdu, size_t n, ImsaSvcDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    out->sms_status = out->voip_status = out->vt_status = out->ut_status = -1;
    out->sms_rat = out->voip_rat = out->vt_rat = out->ut_rat = -1;
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x10u, &v, &l) == 1 && l == 4u) out->sms_status = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 4u) out->voip_status = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x12u, &v, &l) == 1 && l == 4u) out->vt_status = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x13u, &v, &l) == 1 && l == 4u) out->sms_rat = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x14u, &v, &l) == 1 && l == 4u) out->voip_rat = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x15u, &v, &l) == 1 && l == 4u) out->vt_rat = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x16u, &v, &l) == 1 && l == 4u) out->ut_status = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x17u, &v, &l) == 1 && l == 4u) out->ut_rat = (int32_t)rd_u32(v);
    return 0;
}

int imsa_decode_pdp_status(const uint8_t *sdu, size_t n, ImsaPdpDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x10u, &v, &l) == 1 && l == 1u) {
        out->has_pdp_connected = 1;
        out->is_ims_pdp_connected = v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 4u) {
        out->has_pdp_fail_code = 1;
        out->ims_pdp_failure_error_code = rd_u32(v);
    }
    return 0;
}

int imss_decode_reg_mgr(const uint8_t *sdu, size_t n, ImssRegMgrDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x10u, &v, &l) == 1 && (l == 1u || l == 4u)) {
        out->has_settings_rsp = 1;
        out->settings_rsp = (l == 4u) ? rd_u32(v) : (uint32_t)v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 2u) {
        out->has_cscf_port = 1;
        out->cscf_port = rd_u16(v);
    }
    if (qmi_find_tlv(sdu, n, 0x12u, &v, &l) == 1) {
        out->has_cs_domain = 1;
        out->cs_domain_len = l;
    }
    if (qmi_find_tlv(sdu, n, 0x13u, &v, &l) == 1 && l == 1u) {
        out->has_test_mode = 1;
        out->test_mode = v[0];
    }
    return 0;
}

int imss_decode_qipcall(const uint8_t *sdu, size_t n, ImssQipcallDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    out->settings_rsp = out->vt_calling_enabled = out->mobile_data_enabled = out->volte_enabled = -1;
    out->call_mode_prefer = -1;
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    out->settings_rsp = parse_settings_rsp_tlv10(sdu, n);
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 1u) out->vt_calling_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x12u, &v, &l) == 1 && l == 1u) out->mobile_data_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x13u, &v, &l) == 1 && l == 1u) out->volte_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x14u, &v, &l) == 1 && l == 4u) out->call_mode_prefer = (int32_t)rd_u32(v);
    return 0;
}

int imss_decode_svc_enable(const uint8_t *sdu, size_t n, ImssSvcEnableDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    out->settings_rsp = -1;
    out->voip_calling_enabled = out->vt_calling_enabled = out->mobile_data_enabled = out->volte_enabled = -1;
    out->tlv15_u32 = out->tlv16_u32 = out->tlv17_u32 = -1;
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    out->settings_rsp = parse_settings_rsp_tlv10(sdu, n);
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 1u) out->voip_calling_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x12u, &v, &l) == 1 && l == 1u) out->vt_calling_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x13u, &v, &l) == 1 && l == 1u) out->mobile_data_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x14u, &v, &l) == 1 && l == 1u) out->volte_enabled = (int32_t)v[0];
    if (qmi_find_tlv(sdu, n, 0x15u, &v, &l) == 1 && l == 4u) out->tlv15_u32 = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x16u, &v, &l) == 1 && l == 4u) out->tlv16_u32 = (int32_t)rd_u32(v);
    if (qmi_find_tlv(sdu, n, 0x17u, &v, &l) == 1 && l == 4u) out->tlv17_u32 = (int32_t)rd_u32(v);
    return 0;
}

static int is_exact_ims_ascii(const uint8_t *v, uint16_t l) {
    if (l == 3u && v[0] == 'i' && v[1] == 'm' && v[2] == 's') return 1;
    if (l == 4u && v[0] == 'i' && v[1] == 'm' && v[2] == 's' && v[3] == 0u) return 1;
    return 0;
}

int wds_decode_profile(const uint8_t *sdu, size_t n, WdsProfileDecoded *out) {
    if (!out) return -1;
    zero_mem(out, sizeof(*out));
    if (qmi_parse_result_tlv(sdu, n, &out->qmi_result, &out->qmi_error) != 0) return -2;
    out->valid = 1;
    const uint8_t *v = 0;
    uint16_t l = 0u;
    if (qmi_find_tlv(sdu, n, 0xe0u, &v, &l) == 1 && l == 2u) {
        out->has_ext_err = 1;
        out->ext_err = rd_u16(v);
    }
    if (qmi_find_tlv(sdu, n, 0x10u, &v, &l) == 1) {
        out->has_prof_name = 1;
        out->prof_name_is_ims = is_exact_ims_ascii(v, l);
    }
    if (qmi_find_tlv(sdu, n, 0x11u, &v, &l) == 1 && l == 1u) {
        out->has_pdp_type = 1;
        out->pdp_type = v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x14u, &v, &l) == 1) {
        out->has_apn = 1;
        out->apn_len = l;
        out->apn_is_ims = is_exact_ims_ascii(v, l);
    }
    if (qmi_find_tlv(sdu, n, 0x20u, &v, &l) == 1 && l == 1u) {
        out->has_tlv_20 = 1;
        out->tlv_20_u8 = v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x21u, &v, &l) == 1 && l == 1u) {
        out->has_tlv_21 = 1;
        out->tlv_21_u8 = v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x2eu, &v, &l) == 1 && l == 1u) {
        out->has_tlv_2e = 1;
        out->tlv_2e_u8 = v[0];
    }
    if (qmi_find_tlv(sdu, n, 0x33u, &v, &l) == 1 && l == 1u) {
        out->has_tlv_33 = 1;
        out->tlv_33_u8 = v[0];
    }
    return 0;
}

/* Synchronous QMUX exchange state (connection-aware in v3!) */
static int g_rx_phase;
static int g_exp_conn;
static int g_exp_service;
static int g_exp_client;
static uint16_t g_exp_txn;
static uint16_t g_exp_msg;
static unsigned char g_rx_flags;
static int g_rx_len;
static uint8_t g_rx_buf[QMI_RSP_MAX];
static uint16_t g_next_txn = 0x5200u;

static void qmi_rx_cb(int conn, int service, int client, unsigned char flags, unsigned char *buf, int len) {
    if (__atomic_load_n(&g_rx_phase, __ATOMIC_ACQUIRE) != 1) return;
    if (conn != g_exp_conn || service != g_exp_service || client != g_exp_client || !buf ||
        len < 7 || len > QMI_RSP_MAX || buf[0] != 0x02u) {
        return;
    }
    if (rd_u16(buf + 1u) != g_exp_txn || rd_u16(buf + 3u) != g_exp_msg) return;
    int expected = 1;
    if (!__atomic_compare_exchange_n(&g_rx_phase, &expected, 2, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
    copy_mem(g_rx_buf, buf, (size_t)len);
    g_rx_len = len;
    g_rx_flags = flags;
    __atomic_store_n(&g_rx_phase, 3, __ATOMIC_RELEASE);
}

static int qmi_exchange(const QmiPoints *pts, void (*pause_ms)(unsigned), void *handle,
                        int conn_id, int service, unsigned char client, uint16_t msg_id,
                        const uint8_t *tlvs, size_t tlvs_len, unsigned max_polls_50ms,
                        QmiExchangeResult *res) {
    QmiTxBuf tx;
    zero_mem(&tx, sizeof(tx));
    zero_mem(res, sizeof(*res));
    res->send_rc = -999;
    uint16_t txn = ++g_next_txn;
    int req_len = qmi_build_sdu_req(tx.request, sizeof(tx.request), txn, msg_id, tlvs, tlvs_len);
    if (req_len < 7) return -1;

    g_exp_conn = conn_id;
    g_exp_service = service;
    g_exp_client = (int)client;
    g_exp_txn = txn;
    g_exp_msg = msg_id;
    g_rx_len = 0;
    __atomic_store_n(&g_rx_phase, 1, __ATOMIC_RELEASE);

    res->send_rc = pts->send(handle, conn_id, service, client, tx.request, req_len);
    if (res->send_rc == 0) {
        for (unsigned i = 0u; i < max_polls_50ms; ++i) {
            if (__atomic_load_n(&g_rx_phase, __ATOMIC_ACQUIRE) == 3) break;
            pause_ms(50u);
        }
    }
    if (__atomic_load_n(&g_rx_phase, __ATOMIC_ACQUIRE) == 3) {
        res->matched = 1;
        res->rx_flags = g_rx_flags;
        res->rsp_len = g_rx_len;
        copy_mem(res->rsp, g_rx_buf, (size_t)g_rx_len);
        if (qmi_parse_result_tlv(res->rsp, (size_t)res->rsp_len, &res->qmi_result, &res->qmi_error) == 0) {
            res->has_result_tlv = 1;
        }
    }
    __atomic_store_n(&g_rx_phase, 0, __ATOMIC_RELEASE);
    return (res->send_rc == 0 && res->matched) ? 0 : -2;
}

/* Prints safe TLV tag:len(=scalar_u8_if_1b) inventory without raw IP/identity bytes */
static void print_tlv_inventory(const uint8_t *sdu, size_t n, int include_1b_values) {
    printf("\"tlvs\":[");
    if (validate_sdu_rsp(sdu, n) == 0) {
        size_t at = 7u;
        int first = 1;
        while (at < n) {
            uint8_t t = sdu[at];
            uint16_t l = rd_u16(sdu + at + 1u);
            at += 3u;
            if (!first) printf(",");
            first = 0;
            if (include_1b_values && l == 1u) {
                printf("{\"t\":%u,\"l\":1,\"u8\":%u}", (unsigned)t, (unsigned)sdu[at]);
            } else {
                printf("{\"t\":%u,\"l\":%u}", (unsigned)t, (unsigned)l);
            }
            at += l;
        }
    }
    printf("]");
}

static void query_wds_runtime_once(const QmiPoints *pts, void (*pause_ms)(unsigned),
                                   void *handle, int conn_id, unsigned char client, const char *ev_name) {
    static const uint8_t REQ_RT[7] = { 0x10u, 0x04u, 0x00u, 0xffu, 0xffu, 0x00u, 0x00u };
    QmiExchangeResult ex;
    char sha_hex[65];
    (void)qmi_exchange(pts, pause_ms, handle, conn_id, QMI_SVC_WDS, client, QMI_WDS_GET_RUNTIME_SETTINGS,
                       REQ_RT, sizeof(REQ_RT), 50u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        int apn_ims = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x14u, &v, &l) == 1) ? is_exact_ims_ascii(v, l) : 0;
        int has_v4_addr = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x1eu, &v, &l) == 1 && l == 4u);
        int has_v6_addr = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x25u, &v, &l) == 1 && l >= 16u);
        int pcscf_pco = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x16u, &v, &l) == 1 && l == 1u) ? (int)v[0] : -1;
        int pcscf_v4_cnt = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x17u, &v, &l) == 1 && l >= 1u) ? (int)v[0] : -1;
        int pcscf_v6_cnt = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x27u, &v, &l) == 1 && l >= 1u) ? (int)v[0] : -1;
        int pcscf_v6_alt = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x28u, &v, &l) == 1 && l >= 1u) ? (int)v[0] : -1;
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"%s\",\"conn\":%d,\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"apn_is_ims\":%s,\"has_v4_addr\":%s,\"has_v6_addr\":%s,"
               "\"pcscf_pco\":%d,\"pcscf_v4_cnt\":%d,\"pcscf_v6_27_cnt\":%d,\"pcscf_v6_28_cnt\":%d,"
               "\"rsp_len\":%d,\"sha256\":\"%s\",",
               ev_name, conn_id, (unsigned)ex.qmi_result, (unsigned)ex.qmi_error,
               apn_ims ? "true" : "false",
               has_v4_addr ? "true" : "false",
               has_v6_addr ? "true" : "false",
               pcscf_pco, pcscf_v4_cnt, pcscf_v6_cnt, pcscf_v6_alt,
               ex.rsp_len, sha_hex);
        print_tlv_inventory(ex.rsp, (size_t)ex.rsp_len, 0);
        printf("}\n");
    } else {
        printf("{\"event\":\"%s\",\"conn\":%d,\"matched\":false,\"send_rc\":%d}\n",
               ev_name, conn_id, ex.send_rc);
    }
    fflush(0);
}

static void probe_wds_conn_one(const QmiPoints *pts, void (*pause_ms)(unsigned),
                               void *handle, int conn_id, int query_prof4) {
    unsigned char client = 0u;
    int aerr = -1, rerr = -1;
    int arc = pts->alloc(handle, conn_id, QMI_SVC_WDS, &client, &aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":%d,\"service\":1,\"name\":\"WDS\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           conn_id, arc, aerr, (unsigned)client);
    fflush(0);
    if (arc != 0 || aerr != 0) return;

    QmiExchangeResult ex;
    char sha_hex[65];
    int conn_st = -1;

    /* 1. 0x0022 GET_PKT_SRVC_STATUS on conn_id */
    (void)qmi_exchange(pts, pause_ms, handle, conn_id, QMI_SVC_WDS, client, QMI_WDS_GET_PKT_SRVC_STATUS, 0, 0u, 50u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        if (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x01u, &v, &l) == 1 && l >= 1u) conn_st = (int)v[0];
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"wds_pkt_status\",\"conn\":%d,\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"conn_status\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               conn_id, (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, conn_st, ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"wds_pkt_status\",\"conn\":%d,\"matched\":false,\"send_rc\":%d}\n", conn_id, ex.send_rc);
    }
    fflush(0);

    /* If secondary connection (conn 1 or 2) is CONNECTED (2), inspect its live runtime settings (IPv4/IPv6 + PCO P-CSCF)! */
    if (conn_id > 0 && conn_st == 2) {
        query_wds_runtime_once(pts, pause_ms, handle, conn_id, client, "wds_conn_runtime");
    }

    /* 2. 0x002B GET_PROFILE_SETTINGS for 3GPP profile_index = 4 (TLV 0x01 len 2: {0x00, 0x04}) */
    if (query_prof4) {
        static const uint8_t REQ_PROF4[5] = { 0x01u, 0x02u, 0x00u, 0x00u, 0x04u };
        (void)qmi_exchange(pts, pause_ms, handle, conn_id, QMI_SVC_WDS, client, QMI_WDS_GET_PROFILE_SETTINGS,
                           REQ_PROF4, sizeof(REQ_PROF4), 50u, &ex);
        if (ex.matched) {
            WdsProfileDecoded d;
            (void)wds_decode_profile(ex.rsp, (size_t)ex.rsp_len, &d);
            imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
            printf("{\"event\":\"wds_profile4\",\"matched\":true,\"result\":%u,\"error\":%u,"
                   "\"ext_err\":%d,\"pdp_type\":%d,\"apn_is_ims\":%s,\"prof_name_is_ims\":%s,"
                   "\"tlv20\":%d,\"tlv21\":%d,\"tlv2e\":%d,\"tlv33\":%d,\"rsp_len\":%d,\"sha256\":\"%s\",",
                   (unsigned)d.qmi_result, (unsigned)d.qmi_error,
                   d.has_ext_err ? (int)d.ext_err : -1,
                   d.has_pdp_type ? (int)d.pdp_type : -1,
                   d.apn_is_ims ? "true" : "false",
                   d.prof_name_is_ims ? "true" : "false",
                   d.has_tlv_20 ? (int)d.tlv_20_u8 : -1,
                   d.has_tlv_21 ? (int)d.tlv_21_u8 : -1,
                   d.has_tlv_2e ? (int)d.tlv_2e_u8 : -1,
                   d.has_tlv_33 ? (int)d.tlv_33_u8 : -1,
                   ex.rsp_len, sha_hex);
            print_tlv_inventory(ex.rsp, (size_t)ex.rsp_len, 1);
            printf("}\n");
        } else {
            printf("{\"event\":\"wds_profile4\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
        }
        fflush(0);
    }

    int rrc = pts->release(handle, conn_id, QMI_SVC_WDS, client, &rerr);
    printf("{\"event\":\"qmi_release\",\"conn\":%d,\"service\":1,\"rc\":%d,\"err\":%d}\n", conn_id, rrc, rerr);
    fflush(0);
}

static void probe_wds_readonly(const QmiPoints *pts, void (*pause_ms)(unsigned), void *handle) {
    probe_wds_conn_one(pts, pause_ms, handle, QMI_CONN_RMNET_0, 1);
    probe_wds_conn_one(pts, pause_ms, handle, QMI_CONN_RMNET_1, 0);
    probe_wds_conn_one(pts, pause_ms, handle, QMI_CONN_RMNET_2, 0);
}

static void query_imss_core(const QmiPoints *pts, void (*pause_ms)(unsigned),
                            void *handle, unsigned char client, const char *tag) {
    QmiExchangeResult ex;
    char sha_hex[65];

    /* 1. 0x0026 GET_REG_MGR_CONFIG */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_REG_MGR_CONFIG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        ImssRegMgrDecoded d;
        (void)imss_decode_reg_mgr(ex.rsp, (size_t)ex.rsp_len, &d);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_reg_mgr\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"cscf_port\":%d,\"cs_domain_len\":%d,\"test_mode\":%d,"
               "\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               tag, (unsigned)d.qmi_result, (unsigned)d.qmi_error,
               d.has_settings_rsp ? (int)d.settings_rsp : -1,
               d.has_cscf_port ? (int)d.cscf_port : -1,
               d.has_cs_domain ? (int)d.cs_domain_len : -1,
               d.has_test_mode ? (int)d.test_mode : -1,
               ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imss_reg_mgr\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);

    /* 2. 0x0037 GET_QIPCALL_CONFIG (vt / mobile_data / volte_enabled) */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_QIPCALL_CONFIG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        ImssQipcallDecoded d;
        (void)imss_decode_qipcall(ex.rsp, (size_t)ex.rsp_len, &d);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_qipcall\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"vt_enabled\":%d,\"mobile_data_enabled\":%d,\"volte_enabled\":%d,"
               "\"call_mode_prefer\":%d,\"rsp_len\":%d,\"sha256\":\"%s\",",
               tag, (unsigned)d.qmi_result, (unsigned)d.qmi_error,
               (int)d.settings_rsp, (int)d.vt_calling_enabled,
               (int)d.mobile_data_enabled, (int)d.volte_enabled,
               (int)d.call_mode_prefer, ex.rsp_len, sha_hex);
        print_tlv_inventory(ex.rsp, (size_t)ex.rsp_len, 1);
        printf("}\n");
    } else {
        printf("{\"event\":\"imss_qipcall\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);

    /* 3. 0x0054 GET_IMS_SERVICE_ENABLE_CONFIG (voip / vt / mobile_data / volte_enabled + masks) */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_SVC_ENABLE_CFG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        ImssSvcEnableDecoded d;
        (void)imss_decode_svc_enable(ex.rsp, (size_t)ex.rsp_len, &d);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_svc_enable\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"voip_enabled\":%d,\"vt_enabled\":%d,\"mobile_data_enabled\":%d,"
               "\"volte_enabled\":%d,\"tlv15_u32\":%d,\"tlv16_u32\":%d,\"tlv17_u32\":%d,"
               "\"rsp_len\":%d,\"sha256\":\"%s\",",
               tag, (unsigned)d.qmi_result, (unsigned)d.qmi_error,
               (int)d.settings_rsp, (int)d.voip_calling_enabled, (int)d.vt_calling_enabled,
               (int)d.mobile_data_enabled, (int)d.volte_enabled,
               (int)d.tlv15_u32, (int)d.tlv16_u32, (int)d.tlv17_u32,
               ex.rsp_len, sha_hex);
        print_tlv_inventory(ex.rsp, (size_t)ex.rsp_len, 1);
        printf("}\n");
    } else {
        printf("{\"event\":\"imss_svc_enable\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);
}

static void probe_imss_readonly(const QmiPoints *pts, void (*pause_ms)(unsigned), void *handle) {
    unsigned char client = 0u;
    int aerr = -1, rerr = -1;
    int arc = pts->alloc(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, &client, &aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":0,\"service\":18,\"name\":\"IMSS\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           arc, aerr, (unsigned)client);
    fflush(0);
    if (arc != 0 || aerr != 0) return;

    query_imss_core(pts, pause_ms, handle, client, "probe");

    QmiExchangeResult ex;
    char sha_hex[65];

    /* 4. 0x0041 GET_REG_MGR_EXTENDED_CONFIG */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_REG_MGR_EXT_CFG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
        int rereg_delay = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x11u, &v, &l) == 1 && l == 4u) ? (int)rd_u32(v) : -1;
        int tlv12_len = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x12u, &v, &l) == 1) ? (int)l : -1;
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_reg_mgr_ext\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"rereg_delay\":%d,\"tlv12_len\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, rereg_delay, tlv12_len, ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imss_reg_mgr_ext\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
    }
    fflush(0);

    /* 5. 0x0027 GET_SMS_CONFIG */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_SMS_CONFIG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
        int sms_fmt = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x11u, &v, &l) == 1 && l == 1u) ? (int)v[0] : -1;
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_sms\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"sms_format\":%d,\"rsp_len\":%d,\"sha256\":\"%s\",",
               (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, sms_fmt, ex.rsp_len, sha_hex);
        print_tlv_inventory(ex.rsp, (size_t)ex.rsp_len, 1);
        printf("}\n");
    } else {
        printf("{\"event\":\"imss_sms\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
    }
    fflush(0);

    /* 6. 0x0028 GET_USER_CONFIG (privacy-safe: logs only length & whether auto-derived ims.mnc domain is present) */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_USER_CONFIG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
        int dom_len = -1, has_ims_mnc = 0;
        if (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x11u, &v, &l) == 1) {
            dom_len = (int)l;
            has_ims_mnc = contains_ascii_sub(v, l, "ims.mnc");
        }
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_user\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"ims_domain_len\":%d,\"has_ims_mnc_domain\":%s,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, dom_len,
               has_ims_mnc ? "true" : "false", ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imss_user\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
    }
    fflush(0);

    /* 7. 0x0029 GET_VOIP_CONFIG */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_VOIP_CONFIG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_voip_true\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"rsp_len\":%d,\"sha256\":\"%s\",",
               (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, ex.rsp_len, sha_hex);
        print_tlv_inventory(ex.rsp, (size_t)ex.rsp_len, 1);
        printf("}\n");
    } else {
        printf("{\"event\":\"imss_voip_true\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
    }
    fflush(0);

    /* 8. 0x0025 GET_SIP_CONFIG */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, QMI_IMSS_GET_SIP_CONFIG, 0, 0u, 60u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
        int sip_port = (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x11u, &v, &l) == 1 && l == 2u) ? (int)rd_u16(v) : -1;
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imss_sip\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"settings_rsp\":%d,\"sip_local_port\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, sip_port, ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imss_sip\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
    }
    fflush(0);

    int rrc = pts->release(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, client, &rerr);
    printf("{\"event\":\"qmi_release\",\"conn\":0,\"service\":18,\"rc\":%d,\"err\":%d}\n", rrc, rerr);
    fflush(0);
}

static void query_imsa_client_once(const QmiPoints *pts, void (*pause_ms)(unsigned),
                                   void *handle, unsigned char client, const char *tag) {
    QmiExchangeResult ex;
    char sha_hex[65];

    /* 1. 0x0028 GET_PDP_STATUS */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, client, QMI_IMSA_GET_PDP_STATUS, 0, 0u, 50u, &ex);
    if (ex.matched) {
        ImsaPdpDecoded d;
        (void)imsa_decode_pdp_status(ex.rsp, (size_t)ex.rsp_len, &d);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imsa_pdp\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"pdp_connected\":%d,\"pdp_fail_code\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               tag, (unsigned)d.qmi_result, (unsigned)d.qmi_error,
               d.has_pdp_connected ? (int)d.is_ims_pdp_connected : -1,
               d.has_pdp_fail_code ? (int)d.ims_pdp_failure_error_code : -1,
               ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imsa_pdp\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);

    /* 2. 0x0020 GET_REGISTRATION_STATUS */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, client, QMI_IMSA_GET_REG_STATUS, 0, 0u, 50u, &ex);
    if (ex.matched) {
        ImsaRegDecoded d;
        (void)imsa_decode_reg_status(ex.rsp, (size_t)ex.rsp_len, &d);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imsa_reg\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"legacy_reg\":%d,\"reg_status\":%d,\"reg_error_code\":%d,\"reg_error_str\":\"%s\","
               "\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               tag, (unsigned)d.qmi_result, (unsigned)d.qmi_error,
               d.has_legacy_reg ? (int)d.legacy_reg : -1,
               d.has_reg_status ? (int)d.reg_status : -1,
               d.has_error_code ? (int)d.error_code : -1,
               d.has_error_string ? d.error_string_redacted : "",
               ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imsa_reg\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);

    /* 3. 0x0021 GET_SERVICE_STATUS */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, client, QMI_IMSA_GET_SERVICE_STATUS, 0, 0u, 50u, &ex);
    if (ex.matched) {
        ImsaSvcDecoded d;
        (void)imsa_decode_svc_status(ex.rsp, (size_t)ex.rsp_len, &d);
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imsa_svc\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"sms\":%d,\"sms_rat\":%d,\"voip\":%d,\"voip_rat\":%d,\"vt\":%d,\"vt_rat\":%d,"
               "\"ut\":%d,\"ut_rat\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               tag, (unsigned)d.qmi_result, (unsigned)d.qmi_error,
               (int)d.sms_status, (int)d.sms_rat, (int)d.voip_status, (int)d.voip_rat,
               (int)d.vt_status, (int)d.vt_rat, (int)d.ut_status, (int)d.ut_rat,
               ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imsa_svc\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);

    /* 4. 0x002A GET_LTE_ATTACH_PARAMETERS */
    (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, client, QMI_IMSA_GET_LTE_ATTACH_PARMS, 0, 0u, 50u, &ex);
    if (ex.matched) {
        const uint8_t *v = 0;
        uint16_t l = 0u;
        int op_pco = -1;
        if (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x10u, &v, &l) == 1 && l == 1u) op_pco = (int)v[0];
        imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
        printf("{\"event\":\"imsa_attach\",\"tag\":\"%s\",\"matched\":true,\"result\":%u,\"error\":%u,"
               "\"operator_reserved_pco\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
               tag, (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, op_pco, ex.rsp_len, sha_hex);
    } else {
        printf("{\"event\":\"imsa_attach\",\"tag\":\"%s\",\"matched\":false,\"send_rc\":%d}\n", tag, ex.send_rc);
    }
    fflush(0);
}

static void probe_imsa_readonly(const QmiPoints *pts, void (*pause_ms)(unsigned), void *handle) {
    unsigned char client = 0u;
    int aerr = -1, rerr = -1;
    int arc = pts->alloc(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, &client, &aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":0,\"service\":33,\"name\":\"IMSA\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           arc, aerr, (unsigned)client);
    fflush(0);
    if (arc != 0 || aerr != 0) return;
    query_imsa_client_once(pts, pause_ms, handle, client, "probe");
    int rrc = pts->release(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, client, &rerr);
    printf("{\"event\":\"qmi_release\",\"conn\":0,\"service\":33,\"rc\":%d,\"err\":%d}\n", rrc, rerr);
    fflush(0);
}

int imsa_run_probe_all(const QmiPoints *pts, void (*pause_ms)(unsigned)) {
    if (!pts || !pts->init || !pts->alloc || !pts->send || !pts->release || !pts->down || !pause_ms) return -10;
    void *handle = 0;
    __atomic_store_n(&g_rx_phase, 0, __ATOMIC_RELEASE);
    int irc = pts->init(qmi_rx_cb, 0, 0, &handle);
    printf("{\"event\":\"qmux_init\",\"mode\":\"probe_all\",\"version\":3,\"rc\":%d,\"handle_present\":%s}\n",
           irc, handle ? "true" : "false");
    fflush(0);
    if (irc != 0 || !handle) {
        if (handle) (void)pts->down(handle);
        return -11;
    }

    probe_wds_readonly(pts, pause_ms, handle);
    probe_imss_readonly(pts, pause_ms, handle);
    probe_imsa_readonly(pts, pause_ms, handle);

    int drc = pts->down(handle);
    printf("{\"event\":\"qmux_down\",\"rc\":%d}\n", drc);
    fflush(0);
    return (drc == 0) ? 0 : -12;
}

int imsa_run_imss_enable_volte(const QmiPoints *pts, void (*pause_ms)(unsigned)) {
    if (!pts || !pts->init || !pts->alloc || !pts->send || !pts->release || !pts->down || !pause_ms) return -10;
    void *handle = 0;
    __atomic_store_n(&g_rx_phase, 0, __ATOMIC_RELEASE);
    int irc = pts->init(qmi_rx_cb, 0, 0, &handle);
    printf("{\"event\":\"qmux_init\",\"mode\":\"imss_enable_volte\",\"version\":3,\"rc\":%d,\"handle_present\":%s}\n",
           irc, handle ? "true" : "false");
    fflush(0);
    if (irc != 0 || !handle) {
        if (handle) (void)pts->down(handle);
        return -11;
    }

    unsigned char imss_client = 0u, imsa_client = 0u;
    int imss_aerr = -1, imsa_aerr = -1, rerr = -1;
    int imss_arc = pts->alloc(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, &imss_client, &imss_aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":0,\"service\":18,\"name\":\"IMSS\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           imss_arc, imss_aerr, (unsigned)imss_client);
    fflush(0);
    int imsa_arc = pts->alloc(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, &imsa_client, &imsa_aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":0,\"service\":33,\"name\":\"IMSA\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           imsa_arc, imsa_aerr, (unsigned)imsa_client);
    fflush(0);

    if (imss_arc == 0 && imss_aerr == 0) {
        QmiExchangeResult ex;
        char sha_hex[65];

        query_imss_core(pts, pause_ms, handle, imss_client, "before_set");

        /* 1. 0x0036 SET_QIPCALL_CONFIG: TLV 0x11 mobile_data_enabled=1, TLV 0x12 volte_enabled=1 */
        {
            static const uint8_t REQ_SET_QIPCALL[8] = {
                0x11u, 0x01u, 0x00u, 0x01u,
                0x12u, 0x01u, 0x00u, 0x01u
            };
            (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, imss_client, QMI_IMSS_SET_QIPCALL_CONFIG,
                               REQ_SET_QIPCALL, sizeof(REQ_SET_QIPCALL), 60u, &ex);
            if (ex.matched) {
                int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
                imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
                printf("{\"event\":\"imss_set_qipcall\",\"matched\":true,\"result\":%u,\"error\":%u,"
                       "\"settings_rsp\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
                       (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, ex.rsp_len, sha_hex);
            } else {
                printf("{\"event\":\"imss_set_qipcall\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
            }
            fflush(0);
        }

        /* 2. 0x0053 SET_IMS_SERVICE_ENABLE_CONFIG: TLV 0x10 voip=1, TLV 0x12 mobile_data=1, TLV 0x13 volte=1 */
        {
            static const uint8_t REQ_SET_SVC_EN[12] = {
                0x10u, 0x01u, 0x00u, 0x01u,
                0x12u, 0x01u, 0x00u, 0x01u,
                0x13u, 0x01u, 0x00u, 0x01u
            };
            (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, imss_client, QMI_IMSS_SET_SVC_ENABLE_CFG,
                               REQ_SET_SVC_EN, sizeof(REQ_SET_SVC_EN), 60u, &ex);
            if (ex.matched) {
                int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
                imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
                printf("{\"event\":\"imss_set_svc_enable\",\"matched\":true,\"result\":%u,\"error\":%u,"
                       "\"settings_rsp\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
                       (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, ex.rsp_len, sha_hex);
            } else {
                printf("{\"event\":\"imss_set_svc_enable\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
            }
            fflush(0);
        }

        /* 3. 0x0021 SET_REG_MGR_CONFIG: TLV 0x12 ims_test_mode=0 */
        {
            static const uint8_t REQ_SET_REGMGR[4] = { 0x12u, 0x01u, 0x00u, 0x00u };
            (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, imss_client, QMI_IMSS_SET_REG_MGR_CONFIG,
                               REQ_SET_REGMGR, sizeof(REQ_SET_REGMGR), 60u, &ex);
            if (ex.matched) {
                int srsp = parse_settings_rsp_tlv10(ex.rsp, (size_t)ex.rsp_len);
                imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
                printf("{\"event\":\"imss_set_reg_mgr\",\"matched\":true,\"result\":%u,\"error\":%u,"
                       "\"settings_rsp\":%d,\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
                       (unsigned)ex.qmi_result, (unsigned)ex.qmi_error, srsp, ex.rsp_len, sha_hex);
            } else {
                printf("{\"event\":\"imss_set_reg_mgr\",\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
            }
            fflush(0);
        }

        pause_ms(500u);
        query_imss_core(pts, pause_ms, handle, imss_client, "after_set");
    }

    if (imsa_arc == 0 && imsa_aerr == 0) {
        query_imsa_client_once(pts, pause_ms, handle, imsa_client, "after_imss_set");
    }

    if (imsa_arc == 0 && imsa_aerr == 0) {
        int rrc = pts->release(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, imsa_client, &rerr);
        printf("{\"event\":\"qmi_release\",\"conn\":0,\"service\":33,\"rc\":%d,\"err\":%d}\n", rrc, rerr);
        fflush(0);
    }
    if (imss_arc == 0 && imss_aerr == 0) {
        int rrc = pts->release(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSS, imss_client, &rerr);
        printf("{\"event\":\"qmi_release\",\"conn\":0,\"service\":18,\"rc\":%d,\"err\":%d}\n", rrc, rerr);
        fflush(0);
    }
    int drc = pts->down(handle);
    printf("{\"event\":\"qmux_down\",\"rc\":%d}\n", drc);
    fflush(0);
    return (drc == 0) ? 0 : -12;
}

int imsa_run_wds_ims_cycle(const QmiPoints *pts, void (*pause_ms)(unsigned),
                           uint8_t ip_family_pref, unsigned hold_seconds) {
    if (!pts || !pts->init || !pts->alloc || !pts->send || !pts->release || !pts->down || !pause_ms) return -10;
    void *handle = 0;
    __atomic_store_n(&g_rx_phase, 0, __ATOMIC_RELEASE);
    int irc = pts->init(qmi_rx_cb, 0, 0, &handle);
    printf("{\"event\":\"qmux_init\",\"mode\":\"wds_ims_cycle\",\"version\":3,\"wds_conn\":1,\"rc\":%d,\"handle_present\":%s}\n",
           irc, handle ? "true" : "false");
    fflush(0);
    if (irc != 0 || !handle) {
        if (handle) (void)pts->down(handle);
        return -11;
    }

    /* CRITICAL v3 FIX: Allocate WDS on QMI_CONN_RMNET_1 (conn=1), NEVER QMI_CONN_RMNET_0 (conn=0 / 3gnet)! */
    unsigned char wds_client = 0u, imsa_client = 0u;
    int wds_aerr = -1, imsa_aerr = -1, rerr = -1;
    int wds_arc = pts->alloc(handle, QMI_CONN_RMNET_1, QMI_SVC_WDS, &wds_client, &wds_aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":1,\"service\":1,\"name\":\"WDS\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           wds_arc, wds_aerr, (unsigned)wds_client);
    fflush(0);
    int imsa_arc = pts->alloc(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, &imsa_client, &imsa_aerr);
    printf("{\"event\":\"qmi_alloc\",\"conn\":0,\"service\":33,\"name\":\"IMSA\",\"rc\":%d,\"err\":%d,\"client\":%u}\n",
           imsa_arc, imsa_aerr, (unsigned)imsa_client);
    fflush(0);

    if (wds_arc == 0 && wds_aerr == 0) {
        QmiExchangeResult ex;
        char sha_hex[65];
        if (ip_family_pref == 4u || ip_family_pref == 6u) {
            uint8_t pref_tlv[4] = { 0x01u, 0x01u, 0x00u, ip_family_pref };
            (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_1, QMI_SVC_WDS, wds_client, QMI_WDS_SET_IP_FAMILY_PREF,
                               pref_tlv, sizeof(pref_tlv), 40u, &ex);
            printf("{\"event\":\"wds_set_ip_family\",\"conn\":1,\"pref\":%u,\"matched\":%s,\"result\":%u,\"error\":%u}\n",
                   (unsigned)ip_family_pref, ex.matched ? "true" : "false",
                   (unsigned)ex.qmi_result, (unsigned)ex.qmi_error);
            fflush(0);
        }

        /* START_NETWORK_INTERFACE on 3GPP profile_index = 4 (TLV 0x31 len 1 = 0x04) on conn=1 */
        static const uint8_t START_PROF4[4] = { 0x31u, 0x01u, 0x00u, 0x04u };
        (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_1, QMI_SVC_WDS, wds_client, QMI_WDS_START_NET_IFACE,
                           START_PROF4, sizeof(START_PROF4), 160u, &ex);
        uint32_t pkt_handle = 0u;
        int has_handle = 0;
        if (ex.matched) {
            const uint8_t *v = 0;
            uint16_t l = 0u;
            int ce_legacy = -1, ce_type = -1, ce_code = -1;
            if (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x01u, &v, &l) == 1 && l == 4u && ex.qmi_result == 0u) {
                pkt_handle = rd_u32(v);
                has_handle = 1;
            }
            if (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x10u, &v, &l) == 1 && l == 2u) ce_legacy = (int)rd_u16(v);
            if (qmi_find_tlv(ex.rsp, (size_t)ex.rsp_len, 0x11u, &v, &l) == 1 && l == 4u) {
                ce_type = (int)rd_u16(v);
                ce_code = (int)rd_u16(v + 2u);
            }
            imsa_sha256_hex(ex.rsp, (size_t)ex.rsp_len, sha_hex);
            printf("{\"event\":\"wds_start_ims\",\"conn\":1,\"matched\":true,\"result\":%u,\"error\":%u,"
                   "\"has_pkt_handle\":%s,\"call_end_legacy\":%d,\"call_end_type\":%d,\"call_end_verbose\":%d,"
                   "\"rsp_len\":%d,\"sha256\":\"%s\"}\n",
                   (unsigned)ex.qmi_result, (unsigned)ex.qmi_error,
                   has_handle ? "true" : "false", ce_legacy, ce_type, ce_code, ex.rsp_len, sha_hex);
        } else {
            printf("{\"event\":\"wds_start_ims\",\"conn\":1,\"matched\":false,\"send_rc\":%d}\n", ex.send_rc);
        }
        fflush(0);

        if (has_handle) {
            query_wds_runtime_once(pts, pause_ms, handle, QMI_CONN_RMNET_1, wds_client, "wds_runtime_ims");

            if (hold_seconds > 20u) hold_seconds = 20u;
            for (unsigned s = 0u; s < hold_seconds; s += 4u) {
                pause_ms(4000u);
                if (imsa_arc == 0 && imsa_aerr == 0) {
                    query_imsa_client_once(pts, pause_ms, handle, imsa_client, "wds_held");
                }
            }

            /* Unconditionally STOP_NETWORK_INTERFACE using exact pkt_handle on conn=1 */
            uint8_t stop_tlv[7] = {
                0x01u, 0x04u, 0x00u,
                (uint8_t)(pkt_handle & 0xffu),
                (uint8_t)((pkt_handle >> 8) & 0xffu),
                (uint8_t)((pkt_handle >> 16) & 0xffu),
                (uint8_t)((pkt_handle >> 24) & 0xffu)
            };
            (void)qmi_exchange(pts, pause_ms, handle, QMI_CONN_RMNET_1, QMI_SVC_WDS, wds_client, QMI_WDS_STOP_NET_IFACE,
                               stop_tlv, sizeof(stop_tlv), 80u, &ex);
            printf("{\"event\":\"wds_stop_ims\",\"conn\":1,\"matched\":%s,\"result\":%u,\"error\":%u}\n",
                   ex.matched ? "true" : "false", (unsigned)ex.qmi_result, (unsigned)ex.qmi_error);
            fflush(0);
        }
    }

    if (imsa_arc == 0 && imsa_aerr == 0) {
        int rrc = pts->release(handle, QMI_CONN_RMNET_0, QMI_SVC_IMSA, imsa_client, &rerr);
        printf("{\"event\":\"qmi_release\",\"conn\":0,\"service\":33,\"rc\":%d,\"err\":%d}\n", rrc, rerr);
        fflush(0);
    }
    if (wds_arc == 0 && wds_aerr == 0) {
        int rrc = pts->release(handle, QMI_CONN_RMNET_1, QMI_SVC_WDS, wds_client, &rerr);
        printf("{\"event\":\"qmi_release\",\"conn\":1,\"service\":1,\"rc\":%d,\"err\":%d}\n", rrc, rerr);
        fflush(0);
    }
    int drc = pts->down(handle);
    printf("{\"event\":\"qmux_down\",\"rc\":%d}\n", drc);
    fflush(0);
    return (drc == 0) ? 0 : -12;
}
