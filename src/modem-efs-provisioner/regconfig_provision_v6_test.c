#include <stdio.h>
#include <string.h>
#include "regfile_diag_codec.h"
#include "regconfig_provision_v6_payloads.h"

static unsigned g_checks = 0u, g_failed = 0u;

#define CHECK(cond, msg) do { \
    ++g_checks; \
    if (!(cond)) { \
        ++g_failed; \
        fprintf(stderr, "FAIL [%s:%d]: %s\n", __FILE__, __LINE__, (msg)); \
    } \
} while (0)

typedef struct {
    uint8_t pkt[PP_WIDE_MAX_PAYLOAD];
    size_t len;
    int count;
} capture_pkt;

static void on_pkt(const uint8_t *p, size_t n, void *ctx) {
    capture_pkt *c = (capture_pkt *)ctx;
    if (n <= sizeof(c->pkt)) {
        memcpy(c->pkt, p, n);
        c->len = n;
        ++c->count;
    }
}

static int roundtrip_wide_frame(const uint8_t *raw_req, int raw_len, capture_pkt *cap) {
    uint8_t tx_framed[4600];
    uint8_t rx_chunk[4700];
    pp_wide_stream st;
    int flen;
    size_t hdlc_len;
    if (raw_len <= 0) return -1;
    flen = pp_wide_frame(raw_req, (size_t)raw_len, tx_framed, sizeof(tx_framed));
    if (flen <= 4) return -1;
    hdlc_len = (size_t)(flen - 4);
    memset(rx_chunk, 0, 12u);
    rx_chunk[0] = 0x20u;
    rx_chunk[4] = 0x01u;
    rx_chunk[8] = (uint8_t)(hdlc_len & 0xffu);
    rx_chunk[9] = (uint8_t)((hdlc_len >> 8) & 0xffu);
    memcpy(rx_chunk + 12u, tx_framed + 4u, hdlc_len);
    memset(&st, 0, sizeof(st));
    memset(cap, 0, sizeof(*cap));
    if (pp_wide_receive(&st, rx_chunk, 12u + hdlc_len, on_pkt, cap) != 1) return -1;
    return (cap->count == 1 && st.bad_crc == 0u && st.malformed == 0u && st.oversized == 0u) ? 0 : -1;
}

int main(void) {
    char hex[65];
    uint8_t req[PP_WIDE_MAX_PAYLOAD];
    capture_pkt cap;
    int rlen;

    pp_regfile_sha256_hex((const uint8_t *)"", 0u, hex);
    CHECK(strcmp(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") == 0, "SHA-256 empty");

    /* 1. Gate A checks: qp_ims_sms_config (582B) & qp_ims_rcs_auto_config (1024B) */
    CHECK(sizeof(PP_V6_QP_IMS_SMS_CONFIG) == 582u, "sms_config size 582");
    CHECK(PP_V6_QP_IMS_SMS_CONFIG[0] == '1' && PP_V6_QP_IMS_SMS_CONFIG[1] == '0', "sms_config format 3GPP");
    CHECK(PP_V6_QP_IMS_SMS_CONFIG[0x80] == 1u, "sms_config domain flag");
    CHECK(memcmp(PP_V6_QP_IMS_SMS_CONFIG + 0x101u, "0x644E\0", 7u) == 0, "sms_config ASCII RAT mask 0x644E");
    CHECK(PP_V6_QP_IMS_SMS_CONFIG[0x141] == 0x4eu && PP_V6_QP_IMS_SMS_CONFIG[0x142] == 0x64u &&
          PP_V6_QP_IMS_SMS_CONFIG[0x143] == 0x00u && PP_V6_QP_IMS_SMS_CONFIG[0x144] == 0x00u,
          "sms_config Singo+0x414 u32 RAT mask 0x0000644e (LTE bit 10 = 0x0400)");
    CHECK(PP_V6_QP_IMS_SMS_CONFIG[0x245] == 1u, "sms_config trailing flag");
    pp_regfile_sha256_hex(PP_V6_QP_IMS_SMS_CONFIG, sizeof(PP_V6_QP_IMS_SMS_CONFIG), hex);
    CHECK(strcmp(hex, PP_V6_SMS_CONFIG_SHA256) == 0, "sms_config sha256 macro");

    CHECK(sizeof(PP_V6_QP_IMS_RCS_AUTO_CONFIG) == 1024u, "rcs_auto_config size 1024");
    pp_regfile_sha256_hex(PP_V6_QP_IMS_RCS_AUTO_CONFIG, sizeof(PP_V6_QP_IMS_RCS_AUTO_CONFIG), hex);
    CHECK(strcmp(hex, PP_V6_RCS_AUTO_CONFIG_SHA256) == 0, "rcs_auto_config sha256 macro");

    /* 2. Gate B + EP36 checks: qp_ims_config (512B) & qipcall_config_items (512B, [0x21]==0) */
    CHECK(sizeof(PP_V6_QP_IMS_CONFIG) == 512u, "ims_config size 512");
    CHECK(PP_V6_QP_IMS_CONFIG[0] == 0x04u && PP_V6_QP_IMS_CONFIG[2] == 0x02u, "ims_config header");
    CHECK(PP_V6_QP_IMS_CONFIG[7] == 1u && PP_V6_QP_IMS_CONFIG[8] == 1u &&
          PP_V6_QP_IMS_CONFIG[9] == 1u && PP_V6_QP_IMS_CONFIG[10] == 1u,
          "ims_config enables VoLTE at boot for both op_mode 2 and op_mode 9");
    pp_regfile_sha256_hex(PP_V6_QP_IMS_CONFIG, sizeof(PP_V6_QP_IMS_CONFIG), hex);
    CHECK(strcmp(hex, PP_V6_IMS_CONFIG_SHA256) == 0, "ims_config sha256 macro");

    CHECK(sizeof(PP_V6_QIPCALL_CONFIG_ITEMS) == 512u, "qipcall_config_items size 512");
    CHECK(PP_V6_QIPCALL_CONFIG_ITEMS[0] == 0x11u && PP_V6_QIPCALL_CONFIG_ITEMS[1] == 0x00u &&
          PP_V6_QIPCALL_CONFIG_ITEMS[0x21] == 0x00u &&
          PP_V6_QIPCALL_CONFIG_ITEMS[0x131] == 0x01u && PP_V6_QIPCALL_CONFIG_ITEMS[0x1b5] == 0x01u,
          "qipcall_config_items commercial TMO_US flags + EP36 [0x21]==0 gate");
    pp_regfile_sha256_hex(PP_V6_QIPCALL_CONFIG_ITEMS, sizeof(PP_V6_QIPCALL_CONFIG_ITEMS), hex);
    CHECK(strcmp(hex, PP_V6_QIPCALL_CONFIG_ITEMS_SHA256) == 0, "qipcall_config_items sha256");

    /* 3. Gate C + EP36 checks: qp_ims_reg_config (405B) mode=0 & mode=2, [294]==10, [34..35]==1, no static P-CSCF */
    CHECK(sizeof(PP_V6_QP_IMS_REG_CONFIG_VODA_UK) == 405u, "reg_config voda_uk size 405");
    CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[0] == 0x01u && PP_V6_QP_IMS_REG_CONFIG_VODA_UK[1] == 0x02u, "reg_config voda_uk mode=2");
    CHECK(memcmp(PP_V6_QP_IMS_REG_CONFIG_VODA_UK + 2u, "ims\0", 4u) == 0, "reg_config APN ims");
    CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[34] == 1u && PP_V6_QP_IMS_REG_CONFIG_VODA_UK[35] == 1u, "reg_config dynamic PCO P-CSCF");
    CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[294] == 10u && PP_V6_QP_IMS_REG_CONFIG_MODE0[294] == 10u,
          "reg_config [294]==10 pure 3GPP EPC jump table");
    CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[0x12d] == 0x00u, "reg_config voda_uk has NO 7200 retry delay");
    CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[402] == 0x00u, "reg_config test_mode == 0");
    CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[403] == 0xc4u && PP_V6_QP_IMS_REG_CONFIG_VODA_UK[404] == 0x13u, "reg_config port 5060");
    for (size_t i = 38u; i < 294u; ++i) {
        CHECK(PP_V6_QP_IMS_REG_CONFIG_VODA_UK[i] == 0u, "reg_config voda_uk empty static P-CSCF");
        CHECK(PP_V6_QP_IMS_REG_CONFIG_MODE0[i] == 0u, "reg_config mode0 empty static P-CSCF");
    }
    pp_regfile_sha256_hex(PP_V6_QP_IMS_REG_CONFIG_VODA_UK, sizeof(PP_V6_QP_IMS_REG_CONFIG_VODA_UK), hex);
    CHECK(strcmp(hex, PP_V6_REG_CONFIG_VODA_UK_SHA256) == 0, "reg_config voda_uk sha256");

    CHECK(sizeof(PP_V6_QP_IMS_REG_CONFIG_MODE0) == 405u && PP_V6_QP_IMS_REG_CONFIG_MODE0[1] == 0x00u, "reg_config mode=0");
    CHECK(memcmp(PP_V6_QP_IMS_REG_CONFIG_MODE0 + 2u, "ims\0", 4u) == 0, "reg_config mode=0 APN ims");
    pp_regfile_sha256_hex(PP_V6_QP_IMS_REG_CONFIG_MODE0, sizeof(PP_V6_QP_IMS_REG_CONFIG_MODE0), hex);
    CHECK(strcmp(hex, PP_V6_REG_CONFIG_MODE0_SHA256) == 0, "reg_config mode=0 sha256");

    /* 4. RegConfigDB variants */
    CHECK(sizeof(PP_V6_REG_DB_TMO) == 1024u && sizeof(PP_V6_REG_DB_VODA_UK) == 1024u &&
          sizeof(PP_V6_REG_DB_MULTIRAT) == 1024u, "reg_db 1024B");

    /* 5. Fixed EP35/EP36 DS /pdp_profiles/profile4 checks: [750]=1, [751]=1, [752]=0, [753]=0 */
    CHECK(sizeof(PP_V6_PDP_PROFILE4_V4V6) == 789u &&
          PP_V6_PDP_PROFILE4_V4V6[35] == 0x04u &&
          PP_V6_PDP_PROFILE4_V4V6[36] == 0x03u &&
          PP_V6_PDP_PROFILE4_V4V6[39] == 0x0au &&
          PP_V6_PDP_PROFILE4_V4V6[750] == 0x01u &&
          PP_V6_PDP_PROFILE4_V4V6[751] == 0x01u &&
          PP_V6_PDP_PROFILE4_V4V6[752] == 0x00u &&
          PP_V6_PDP_PROFILE4_V4V6[753] == 0x00u, "profile4 v4v6 v6 exact offsets");
    pp_regfile_sha256_hex(PP_V6_PDP_PROFILE4_V4V6, sizeof(PP_V6_PDP_PROFILE4_V4V6), hex);
    CHECK(strcmp(hex, PP_V6_PDP_PROFILE4_V4V6_SHA256) == 0, "profile4 v4v6 v6 sha256");

    CHECK(sizeof(PP_V6_PDP_PROFILE4_IPV6) == 789u &&
          PP_V6_PDP_PROFILE4_IPV6[35] == 0x04u &&
          PP_V6_PDP_PROFILE4_IPV6[36] == 0x02u &&
          PP_V6_PDP_PROFILE4_IPV6[39] == 0x06u &&
          PP_V6_PDP_PROFILE4_IPV6[750] == 0x01u &&
          PP_V6_PDP_PROFILE4_IPV6[751] == 0x01u &&
          PP_V6_PDP_PROFILE4_IPV6[752] == 0x00u &&
          PP_V6_PDP_PROFILE4_IPV6[753] == 0x00u, "profile4 ipv6 v6 exact offsets");
    pp_regfile_sha256_hex(PP_V6_PDP_PROFILE4_IPV6, sizeof(PP_V6_PDP_PROFILE4_IPV6), hex);
    CHECK(strcmp(hex, PP_V6_PDP_PROFILE4_IPV6_SHA256) == 0, "profile4 ipv6 v6 sha256");

    rlen = pp_wide_build_unlink(req, sizeof(req), "/nv/item_files/ims/ims_hybrid_enable");
    CHECK(rlen > 0 && roundtrip_wide_frame(req, rlen, &cap) == 0, "UNLINK ims_hybrid_enable HDLC roundtrip");

    rlen = pp_wide_build_put(req, sizeof(req), "/nv/item_files/ims/qp_ims_sms_config",
                             PP_V6_QP_IMS_SMS_CONFIG, sizeof(PP_V6_QP_IMS_SMS_CONFIG), PP_EFS_PUT_FLAGS, 6001u);
    CHECK(rlen > 0 && roundtrip_wide_frame(req, rlen, &cap) == 0, "PUT 582B qp_ims_sms_config HDLC roundtrip");

    rlen = pp_wide_build_put(req, sizeof(req), "/nv/item_files/ims/qp_ims_rcs_auto_config",
                             PP_V6_QP_IMS_RCS_AUTO_CONFIG, sizeof(PP_V6_QP_IMS_RCS_AUTO_CONFIG), PP_EFS_PUT_FLAGS, 6002u);
    CHECK(rlen > 0 && roundtrip_wide_frame(req, rlen, &cap) == 0, "PUT 1024B qp_ims_rcs_auto_config HDLC roundtrip");

    /* 6. Cross-unit / multi-carrier DS profile1 & profile2 structural validation */
    {
        uint8_t p1[789];
        uint8_t p2[789];
        memset(p1, 0, sizeof(p1));
        p1[0] = 0x15u;
        p1[35] = 0x01u;
        CHECK(pp_regfile_validate_ds_profile_ref(1u, p1, sizeof(p1)) == 0, "DS profile1 baseline valid");
        p1[12] = 0xa5u;
        p1[29] = 0x7eu;
        CHECK(pp_regfile_validate_ds_profile_ref(1u, p1, sizeof(p1)) == 0, "DS profile1 QDP stack padding tolerated");
        CHECK(pp_regfile_validate_ds_profile_ref(2u, p1, sizeof(p1)) != 0, "DS profile1 rejected for slot 2");
        CHECK(pp_regfile_validate_ds_profile_ref(3u, p1, sizeof(p1)) != 0, "DS validator rejects slot 3");
        CHECK(pp_regfile_validate_ds_profile_ref(1u, p1, 788u) != 0, "DS validator rejects wrong length");
        CHECK(pp_regfile_validate_ds_profile_ref(1u, NULL, sizeof(p1)) != 0, "DS validator rejects NULL buffer");

        memset(p2, 0, sizeof(p2));
        p2[0] = 0x15u;
        p2[35] = 0x02u;
        CHECK(pp_regfile_validate_ds_profile_ref(2u, p2, sizeof(p2)) == 0, "DS profile2 baseline valid");
        p2[750] = 0x01u;
        CHECK(pp_regfile_validate_ds_profile_ref(2u, p2, sizeof(p2)) != 0, "DS validator rejects im_cn_flag=1");
        p2[750] = 0x00u;
        p2[751] = 0x01u;
        CHECK(pp_regfile_validate_ds_profile_ref(2u, p2, sizeof(p2)) != 0, "DS validator rejects request_pcscf=1");
        CHECK(pp_regfile_validate_ds_profile_ref(1u, PP_V6_PDP_PROFILE4_V4V6, sizeof(PP_V6_PDP_PROFILE4_V4V6)) != 0,
              "DS validator rejects IMS profile4 as profile1");
    }

    printf("{\"checks\":%u,\"failed\":%u}\n", g_checks, g_failed);
    return g_failed ? 1 : 0;
}
