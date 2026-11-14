/* Passport ARM32 bounded Stage 6 standalone Hexagon DCM VoLTE + fixed DS /pdp_profiles/profile4
 * provisioner & unconditional rollback binary (v6).
 *
 * Root cause isolated by RP26 + EP35 + EP36:
 *   - In Stage 5CD (RP26), leaving /nv/item_files/ims/ims_hybrid_enable ENOENT properly selected
 *     standalone Hexagon DCM (*(uint8_t*)(dpl_ctx + 0xc19) = 0), and Gates A/B/C all passed
 *     (reg_status=2 REGISTERING), while /pdp_profiles/profile4 had byte[753]=1 (apn_disabled_flag,
 *     QMI_WDS 0x002B TLV 0x33) and bytes[750..751]=0 (im_cn_flag TLV 0x21 & request_pcscf TLV 0x2e).
 *   - Stage 6 v6 writes fixed 789B /pdp_profiles/profile4 with byte[750]=1 (im_cn_flag=1),
 *     byte[751]=1 (request_pcscf_address_flag=1), byte[752]=0, and byte[753]=0 (apn_disabled_flag=0).
 *
 * Modes:
 *   --inspect-state       Read-only inspection of 52 managed IMS/MMode items, ims_hybrid_enable,
 *                         ue_usage_setting, mid_call_srvcc_info, /pdp_profiles/profile4, and 4 DS refs.
 *   --rehearse-stage6     Same-boot guarded rehearsal while IMS_enable==00 & voice_domain_pref==00:
 *                         verify hybrid ENOENT, create fixed /pdp_profiles/profile4 + Gate A/B items,
 *                         verify STAT + exact SHA-256 read-back, full rollback + SYNC, verify ENOENT.
 *   --provision-stage6a   Standalone Hexagon DCM (ims_hybrid_enable ENOENT) + op_mode=02 +
 *                         MODE0 USIM auto-derived qp_ims_reg_config (mode=0) + TMO IPV4V6 RegConfigDB +
 *                         PP_V6_PDP_PROFILE4_V4V6 (im_cn=1, req_pcscf=1, apn_disabled=0) + 50 items + 03 + 01 + SYNC.
 *   --provision-stage6b   Standalone Hexagon DCM (ims_hybrid_enable ENOENT) + op_mode=02 +
 *                         Vodafone_UK qp_ims_reg_config (mode=2) + TMO IPV4V6 RegConfigDB +
 *                         PP_V6_PDP_PROFILE4_V4V6 + 50 items + 03 + 01 + SYNC.
 *   --provision-stage6c   Standalone Hexagon DCM (ims_hybrid_enable ENOENT) + op_mode=02 +
 *                         MODE0 USIM auto-derived qp_ims_reg_config (mode=0) + Vodafone_UK IPV6 RegConfigDB +
 *                         PP_V6_PDP_PROFILE4_IPV6 (pdp_type=2, ip_vsn=6, im_cn=1, req_pcscf=1, apn_disabled=0) +
 *                         50 items + 03 + 01 + SYNC.
 *   --provision-stage6d   Standalone Hexagon DCM (ims_hybrid_enable ENOENT) + op_mode=09 (TMO_US) +
 *                         MODE0 USIM auto-derived qp_ims_reg_config (mode=0) + multi-RAT 0x0077 RegConfigDB +
 *                         TMO sip_ext0/presence/reg_ext0/qos + PP_V6_PDP_PROFILE4_V4V6 + 50 items + 03 + 01 + SYNC.
 *   --rollback-all        Restore IMS_enable=00 & voice_domain_pref=00 FIRST, UNLINK /pdp_profiles/profile4,
 *                         ims_hybrid_enable, and all 50 created S_IFITM items, SYNC, verify restoration & DS refs.
 *   --restore-usb         Switch /dev/diag back to USB logging mode (1).
 */
#include <stddef.h>
#include <stdint.h>
#include "regfile_diag_codec.h"
#include "regconfig_provision_v6_payloads.h"

extern int open(const char *, int, ...);
extern int close(int);
extern int read(int, void *, unsigned int);
extern int write(int, const void *, unsigned int);
extern int ioctl(int, unsigned long, ...);
extern unsigned int alarm(unsigned int);
extern int usleep(unsigned int);
extern unsigned int getuid(void);
extern int getpid(void);
extern int printf(const char *, ...);
extern int sscanf(const char *, const char *, ...);
extern int fflush(void *);
extern int strcmp(const char *, const char *);
extern char *strstr(const char *, const char *);
extern int *__errno(void);

#define EFS_ERR_ENOENT 2u
#define EFS_S_IFMT     0170000u
#define EFS_S_IFREG    0100000u

#define PATH_IMS_ENABLE      "/nv/item_files/ims/IMS_enable"
#define PATH_VOICE_PREF      "/nv/item_files/modem/mmode/voice_domain_pref"
#define PATH_HYBRID_ENABLE   "/nv/item_files/ims/ims_hybrid_enable"
#define PATH_UE_USAGE        "/nv/item_files/modem/mmode/ue_usage_setting"
#define PATH_MID_SRVCC       "/nv/item_files/modem/mmode/mid_call_srvcc_info"
#define PATH_PDP_PROFILE4    "/pdp_profiles/profile4"
#define PATH_SMS_CONFIG      "/nv/item_files/ims/qp_ims_sms_config"
#define PATH_RCS_AUTO_CONFIG "/nv/item_files/ims/qp_ims_rcs_auto_config"
#define PATH_IMS_CONFIG      "/nv/item_files/ims/qp_ims_config"

enum {
    OP_NONE = 0,
    OP_INSPECT_STATE = 1,
    OP_REHEARSE_STAGE6 = 2,
    OP_PROVISION_STAGE6A = 3,
    OP_PROVISION_STAGE6B = 4,
    OP_PROVISION_STAGE6C = 5,
    OP_PROVISION_STAGE6D = 6,
    OP_ROLLBACK_ALL = 7,
    OP_RESTORE_USB = 8
};

typedef struct {
    const char *path;
    const uint8_t *stage6a_data;
    const uint8_t *stage6b_data;
    const uint8_t *stage6c_data;
    const uint8_t *stage6d_data;
    uint32_t target_len;
    int baseline_exists_00; /* 1: originally 1B 0x00; 0: originally ENOENT */
} managed_item_spec;

static const managed_item_spec MANAGED_ITEMS[] = {
    /* 50 EA02-verified missing S_IFITM items written BEFORE flipping voice_domain_pref & IMS_enable */
    { "/nv/item_files/ims/ims_operation_mode",                     PP_V6_SCALAR_02,                 PP_V6_SCALAR_02,                 PP_V6_SCALAR_02,                 PP_V6_SCALAR_09,                 1u, 0 },
    { "/nv/item_files/modem/mmode/sms_domain_pref",                PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/modem/mmode/sms_mandatory",                  PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/modem/mmode/sms_only",                       PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 1u, 0 },
    { "/nv/item_files/modem/mmode/supplement_service_domain_pref", PP_V6_SCALAR_03,                 PP_V6_SCALAR_03,                 PP_V6_SCALAR_03,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/modem/mmode/volte_sr_control",               PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/ims_scr_amr_nb_enabled",                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/ims_scr_amr_wb_enabled",                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_callerid_mode",                  PP_V6_U16_0010,                  PP_V6_U16_0010,                  PP_V6_U16_0010,                  PP_V6_U16_0010,                  2u, 0 },
    { "/nv/item_files/ims/qipcall_codec_mode_set",                 PP_V6_U32_00000000,              PP_V6_U32_00000000,              PP_V6_U32_00000000,              PP_V6_U32_00000000,              4u, 0 },
    { "/nv/item_files/ims/qipcall_codec_mode_set_amr_wb",          PP_V6_U32_00000000,              PP_V6_U32_00000000,              PP_V6_U32_00000000,              PP_V6_U32_00000000,              4u, 0 },
    { "/nv/item_files/ims/qipcall_confrd_uri",                     PP_V6_U16_0000,                  PP_V6_U16_0000,                  PP_V6_U16_0000,                  PP_V6_U16_0000,                  2u, 0 },
    { "/nv/item_files/ims/qipcall_dan_enable",                     PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_domain_selection_enable",        PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_enable_hd_voice",                PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_is_conference_aware",            PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_octet_aligned_mode_amr_nb",      PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_octet_aligned_mode_amr_wb",      PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_precondition_enable",            PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_qos_enabled",                    PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_qos_reservation_timer",          PP_V6_U32_00001F40,              PP_V6_U32_00001F40,              PP_V6_U32_00001F40,              PP_V6_U32_00001F40,              4u, 0 },
    { "/nv/item_files/ims/qipcall_ringback_timer",                 PP_V6_U32_00015F90,              PP_V6_U32_00015F90,              PP_V6_U32_00015F90,              PP_V6_U32_00015F90,              4u, 0 },
    { "/nv/item_files/ims/qipcall_ringing_timer",                  PP_V6_U32_0000AFC8,              PP_V6_U32_0000AFC8,              PP_V6_U32_0000AFC8,              PP_V6_U32_0000AFC8,              4u, 0 },
    { "/nv/item_files/ims/qipcall_rtcp_link_aliveness_timer",      PP_V6_U32_00000014,              PP_V6_U32_00000014,              PP_V6_U32_00000014,              PP_V6_U32_00000014,              4u, 0 },
    { "/nv/item_files/ims/qipcall_rtcp_reporting_interval",        PP_V6_U32_00000005,              PP_V6_U32_00000005,              PP_V6_U32_00000005,              PP_V6_U32_00000005,              4u, 0 },
    { "/nv/item_files/ims/qipcall_rtp_link_aliveness_timer",       PP_V6_U32_00000014,              PP_V6_U32_00000014,              PP_V6_U32_00000014,              PP_V6_U32_00000014,              4u, 0 },
    { "/nv/item_files/ims/qipcall_session_level_media_bw_enabled", PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 0 },
    { "/nv/item_files/ims/qipcall_signal_strength_threshold",      PP_V6_U16_FF8D,                  PP_V6_U16_FF8D,                  PP_V6_U16_FF8D,                  PP_V6_U16_FF8D,                  2u, 0 },
    { "/nv/item_files/ims/qipcall_vt_quality_selector",            PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 1u, 0 },
    { "/nv/item_files/ims/voip_prfrd_codec",                       PP_V6_U16_0000,                  PP_V6_U16_0000,                  PP_V6_U16_0000,                  PP_V6_U16_0000,                  2u, 0 },
    { "/nv/item_files/ims/qp_ims_test_mode",                       PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 PP_V6_SCALAR_00,                 1u, 0 },
    { "/nv/item_files/ims/qp_ims_reg_config",                      PP_V6_QP_IMS_REG_CONFIG_MODE0,   PP_V6_QP_IMS_REG_CONFIG_VODA_UK, PP_V6_QP_IMS_REG_CONFIG_MODE0,   PP_V6_QP_IMS_REG_CONFIG_MODE0, 405u, 0 },
    { "/nv/item_files/ims/qp_ims_voip_config",                     PP_V6_QP_IMS_VOIP_CONFIG,        PP_V6_QP_IMS_VOIP_CONFIG,        PP_V6_QP_IMS_VOIP_CONFIG,        PP_V6_QP_IMS_VOIP_CONFIG,      397u, 0 },
    { "/nv/item_files/ims/qp_ims_sip_extended_0_config",           PP_V6_SIP_EXT0_VODA,             PP_V6_SIP_EXT0_VODA,             PP_V6_SIP_EXT0_VODA,             PP_V6_SIP_EXT0_TMO,           1024u, 0 },
    { "/nv/item_files/ims/qp_ims_dpl_config",                      PP_V6_QP_IMS_DPL_CONFIG,         PP_V6_QP_IMS_DPL_CONFIG,         PP_V6_QP_IMS_DPL_CONFIG,         PP_V6_QP_IMS_DPL_CONFIG,        14u, 0 },
    { "/nv/item_files/ims/qp_ims_param_config",                    PP_V6_QP_IMS_PARAM_CONFIG,       PP_V6_QP_IMS_PARAM_CONFIG,       PP_V6_QP_IMS_PARAM_CONFIG,       PP_V6_QP_IMS_PARAM_CONFIG,     833u, 0 },
    { PATH_SMS_CONFIG,                                             PP_V6_QP_IMS_SMS_CONFIG,         PP_V6_QP_IMS_SMS_CONFIG,         PP_V6_QP_IMS_SMS_CONFIG,         PP_V6_QP_IMS_SMS_CONFIG,       582u, 0 },
    { "/nv/item_files/ims/qp_ims_presence_config",                 PP_V6_PRESENCE_VODA,             PP_V6_PRESENCE_VODA,             PP_V6_PRESENCE_VODA,             PP_V6_PRESENCE_TMO,            172u, 0 },
    { "/nv/item_files/ims/qp_ims_media_config",                    PP_V6_QP_IMS_MEDIA_CONFIG,       PP_V6_QP_IMS_MEDIA_CONFIG,       PP_V6_QP_IMS_MEDIA_CONFIG,       PP_V6_QP_IMS_MEDIA_CONFIG,     535u, 0 },
    { "/nv/item_files/ims/qipcall_config_items",                   PP_V6_QIPCALL_CONFIG_ITEMS,      PP_V6_QIPCALL_CONFIG_ITEMS,      PP_V6_QIPCALL_CONFIG_ITEMS,      PP_V6_QIPCALL_CONFIG_ITEMS,    512u, 0 },
    { PATH_IMS_CONFIG,                                             PP_V6_QP_IMS_CONFIG,             PP_V6_QP_IMS_CONFIG,             PP_V6_QP_IMS_CONFIG,             PP_V6_QP_IMS_CONFIG,           512u, 0 },
    { "/nv/item_files/ims/qp_ims_reg_extended_0_config",           PP_V6_REG_EXT0_VODA,             PP_V6_REG_EXT0_VODA,             PP_V6_REG_EXT0_VODA,             PP_V6_REG_EXT0_TMO,           1024u, 0 },
    { PATH_RCS_AUTO_CONFIG,                                        PP_V6_QP_IMS_RCS_AUTO_CONFIG,    PP_V6_QP_IMS_RCS_AUTO_CONFIG,    PP_V6_QP_IMS_RCS_AUTO_CONFIG,    PP_V6_QP_IMS_RCS_AUTO_CONFIG, 1024u, 0 },
    { "/nv/item_files/ims/qp_ims_vt_4G_media_capability",          PP_V6_QP_IMS_VT_4G_MEDIA,        PP_V6_QP_IMS_VT_4G_MEDIA,        PP_V6_QP_IMS_VT_4G_MEDIA,        PP_V6_QP_IMS_VT_4G_MEDIA,      582u, 0 },
    { "/nv/item_files/ims/qp_ims_ussd_config",                     PP_V6_QP_IMS_USSD_CONFIG,        PP_V6_QP_IMS_USSD_CONFIG,        PP_V6_QP_IMS_USSD_CONFIG,        PP_V6_QP_IMS_USSD_CONFIG,      511u, 0 },
    { "/nv/item_files/ims/qp_ims_presence_ext_config",             PP_V6_QP_IMS_PRESENCE_EXT,       PP_V6_QP_IMS_PRESENCE_EXT,       PP_V6_QP_IMS_PRESENCE_EXT,       PP_V6_QP_IMS_PRESENCE_EXT,     128u, 0 },
    { "/nv/item_files/ims/qp_ims_reg_config_db",                   PP_V6_REG_DB_TMO,                PP_V6_REG_DB_TMO,                PP_V6_REG_DB_VODA_UK,            PP_V6_REG_DB_MULTIRAT,        1024u, 0 },
    { "/nv/item_files/ims/qp_ims_wifi_config",                     PP_V6_QP_IMS_WIFI_CONFIG,        PP_V6_QP_IMS_WIFI_CONFIG,        PP_V6_QP_IMS_WIFI_CONFIG,        PP_V6_QP_IMS_WIFI_CONFIG,      512u, 0 },
    { "/nv/item_files/ims/qipcall_qos_config",                     PP_V6_QOS_CFG_VODA,              PP_V6_QOS_CFG_VODA,              PP_V6_QOS_CFG_VODA,              PP_V6_QOS_CFG_TMO,              32u, 0 },
    { "/nv/item_files/ims/qp_ims_cellid",                          PP_V6_QP_IMS_CELLID,             PP_V6_QP_IMS_CELLID,             PP_V6_QP_IMS_CELLID,             PP_V6_QP_IMS_CELLID,            32u, 0 },
    /* Existing 0x00 baseline items flipped LAST on provision, FIRST on rollback */
    { PATH_VOICE_PREF,                                             PP_V6_SCALAR_03,                 PP_V6_SCALAR_03,                 PP_V6_SCALAR_03,                 PP_V6_SCALAR_03,                 1u, 1 },
    { PATH_IMS_ENABLE,                                             PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 PP_V6_SCALAR_01,                 1u, 1 }
};

#define NUM_MANAGED_ITEMS (sizeof(MANAGED_ITEMS) / sizeof(MANAGED_ITEMS[0]))

typedef struct {
    const char *path;
    uint32_t expected_size;
    const char *expected_sha256;
} ds_ref_spec;

static const ds_ref_spec DS_REFS[4] = {
    { PP_V6_DS_REF_EMBEDDED_CALL_PROF_NUMS_PATH, PP_V6_DS_REF_EMBEDDED_CALL_PROF_NUMS_SIZE, PP_V6_DS_REF_EMBEDDED_CALL_PROF_NUMS_SHA256 },
    { PP_V6_DS_REF_PROFILE1_PATH,                PP_V6_DS_REF_PROFILE1_SIZE,                PP_V6_DS_REF_PROFILE1_SHA256 },
    { PP_V6_DS_REF_PROFILE2_PATH,                PP_V6_DS_REF_PROFILE2_SIZE,                PP_V6_DS_REF_PROFILE2_SHA256 },
    { PP_V6_DS_REF_PROFILE3_PATH,                PP_V6_DS_REF_PROFILE3_SIZE,                PP_V6_DS_REF_PROFILE3_SHA256 }
};

static uint8_t incoming[256 * 1024];
static uint8_t reply_buf[PP_WIDE_MAX_PAYLOAD];
static size_t reply_len;
static unsigned wanted_cmd;
static int got_reply, correlated_err;
static uint16_t g_seq = 6000u;

static int bytes_eq(const uint8_t *a, const uint8_t *b, size_t n) {
    for (size_t i = 0u; i < n; ++i) {
        if (a[i] != b[i]) return 0;
    }
    return 1;
}

static void copy_bytes(uint8_t *dst, const uint8_t *src, size_t n) {
    for (size_t i = 0u; i < n; ++i) dst[i] = src[i];
}

static int diag_status(int required_mode) {
    char buf[8192];
    int fd = open("/sys/kernel/debug/diag/status", 0);
    if (fd < 0) return -1;
    int n = read(fd, buf, sizeof(buf) - 1u);
    int cr = close(fd);
    if (n <= 0 || cr) return -2;
    buf[n] = 0;
    char *m = strstr(buf, "\nlogging_mode: ");
    char *r = strstr(buf, "\nreal_time_mode: ");
    char *u = strstr(buf, "\nusb_connected: ");
    int mode = -1, real = -1, usb = -1;
    if (!m || !r || !u || sscanf(m, "\nlogging_mode: %d", &mode) != 1 ||
        sscanf(r, "\nreal_time_mode: %d", &real) != 1 ||
        sscanf(u, "\nusb_connected: %d", &usb) != 1) return -3;
    printf("{\"event\":\"status\",\"mode\":%d,\"real\":%d,\"usb\":%d}\n", mode, real, usb);
    fflush(0);
    return (real == 1 && usb == 0 && mode == required_mode) ? 0 : -4;
}

static void on_packet(const uint8_t *p, size_t n, void *unused) {
    (void)unused;
    int corr = n >= 5u &&
        (p[0] == 0x13u || p[0] == 0x14u || p[0] == 0x15u || p[0] == 0x18u || p[0] == 0x42u || p[0] == 0x47u) &&
        p[1] == 0x4bu && p[2] == 0x13u && p[3] == wanted_cmd && p[4] == 0u;
    int direct = n >= 4u && p[0] == 0x4bu && p[1] == 0x13u && p[2] == wanted_cmd && p[3] == 0u;
    if (!corr && !direct) return;
    if (got_reply) { correlated_err = -90; return; }
    got_reply = 1;
    if (corr) {
        correlated_err = -100 - (int)p[0];
        return;
    }
    if (n > sizeof(reply_buf)) {
        correlated_err = -91;
        return;
    }
    copy_bytes(reply_buf, p, n);
    reply_len = n;
}

static int run_exchange(int fd, unsigned cmd, const uint8_t *req, int req_len) {
    uint8_t framed[4600];
    pp_wide_stream stream = {0};
    if (req_len <= 0) return 20;
    int fsize = pp_wide_frame(req, (size_t)req_len, framed, sizeof(framed));
    if (fsize <= 0) return 21;
    wanted_cmd = cmd;
    got_reply = 0;
    correlated_err = 0;
    reply_len = 0u;
    int sent = write(fd, framed, (unsigned)fsize);
    if (sent != 0 && sent != fsize) return 22;
    for (unsigned tries = 0u; tries < 48u && !got_reply; ++tries) {
        int n = read(fd, incoming, sizeof(incoming));
        if (n <= 0) return 23;
        int dec = pp_wide_receive(&stream, incoming, (size_t)n, on_packet, 0);
        if ((dec < 0 && dec != -2) || stream.bad_crc || stream.malformed || stream.oversized) return 24;
    }
    if (!got_reply || correlated_err != 0) return 25;
    return 0;
}

static int efs_stat_one(int fd, const char *path, pp_wide_stat *st_out) {
    uint8_t req[300];
    int slen = pp_wide_build_stat(req, sizeof(req), path);
    int rc = run_exchange(fd, 15u, req, slen);
    if (rc) return rc;
    return pp_wide_parse_stat(reply_buf, reply_len, st_out) == 0 ? 0 : 28;
}

static int efs_get_one(int fd, const char *path, uint32_t expected_len,
                       uint8_t *buf_out, size_t buf_cap, int32_t *nbytes_out, uint32_t *err_out) {
    uint8_t req[320];
    uint16_t seq = g_seq++;
    int glen = pp_wide_build_get(req, sizeof(req), path, expected_len, seq);
    int rc = run_exchange(fd, 39u, req, glen);
    if (rc) return rc;
    return pp_wide_parse_get(reply_buf, reply_len, seq, nbytes_out, err_out, buf_out, buf_cap) == 0 ? 0 : 29;
}

static int efs_put_one(int fd, const char *path, const uint8_t *data, uint32_t len, uint32_t *err_out) {
    uint8_t req[PP_WIDE_MAX_PAYLOAD];
    uint16_t seq = g_seq++;
    int plen = pp_wide_build_put(req, sizeof(req), path, data, len, PP_EFS_PUT_FLAGS, seq);
    int rc = run_exchange(fd, 38u, req, plen);
    if (rc) return rc;
    return pp_wide_parse_put(reply_buf, reply_len, seq, err_out) == 0 ? 0 : 31;
}

static int efs_unlink_one(int fd, const char *path, uint32_t *err_out) {
    uint8_t req[300];
    int ulen = pp_wide_build_unlink(req, sizeof(req), path);
    int rc = run_exchange(fd, 8u, req, ulen);
    if (rc) return rc;
    return pp_wide_parse_unlink(reply_buf, reply_len, err_out) == 0 ? 0 : 33;
}

static int efs_regfile_close(int fd, int32_t efs_fd, uint32_t *err_out) {
    uint8_t req[32];
    int clen = pp_regfile_build_close(req, sizeof(req), efs_fd);
    int rc = run_exchange(fd, 3u, req, clen);
    if (rc) return rc;
    return pp_regfile_parse_close(reply_buf, reply_len, err_out) == 0 ? 0 : 50;
}

static int efs_regfile_read_exact(int fd, const char *path, uint32_t total_len,
                                  uint8_t *out_buf, size_t out_cap, uint32_t *err_out) {
    uint8_t req[320];
    int32_t efs_fd = -1;
    uint32_t open_err = 0xffffffffu, close_err = 0xffffffffu, off = 0u;
    *err_out = 0xffffffffu;
    if (total_len > out_cap || total_len > PP_REGFILE_MAX_SIZE) return 51;

    int olen = pp_regfile_build_open(req, sizeof(req), PP_REGFILE_O_RDONLY, 0, path);
    int rc = run_exchange(fd, 2u, req, olen);
    if (rc) return rc;
    if (pp_regfile_parse_open(reply_buf, reply_len, &efs_fd, &open_err) != 0) return 52;
    if (open_err != 0u) {
        *err_out = open_err;
        return 53;
    }

    while (off < total_len) {
        uint32_t chunk = total_len - off;
        int32_t br = -1;
        uint32_t rerr = 0xffffffffu;
        if (chunk > PP_REGFILE_IO_CHUNK) chunk = PP_REGFILE_IO_CHUNK;
        int rlen = pp_regfile_build_read(req, sizeof(req), efs_fd, chunk, off);
        rc = run_exchange(fd, 4u, req, rlen);
        if (rc || pp_regfile_parse_read(reply_buf, reply_len, efs_fd, off, &br, &rerr, out_buf + off, (size_t)chunk) != 0) {
            (void)efs_regfile_close(fd, efs_fd, &close_err);
            return rc ? rc : 54;
        }
        if (rerr != 0u || br != (int32_t)chunk) {
            *err_out = (rerr != 0u) ? rerr : 999u;
            (void)efs_regfile_close(fd, efs_fd, &close_err);
            return 55;
        }
        off += chunk;
    }

    rc = efs_regfile_close(fd, efs_fd, &close_err);
    if (rc || close_err != 0u) {
        *err_out = close_err;
        return rc ? rc : 56;
    }
    *err_out = 0u;
    return 0;
}

static int efs_regfile_write_exact(int fd, const char *path, const uint8_t *data,
                                   uint32_t total_len, uint32_t *err_out) {
    uint8_t req[PP_WIDE_MAX_PAYLOAD];
    int32_t efs_fd = -1;
    uint32_t open_err = 0xffffffffu, close_err = 0xffffffffu, off = 0u;
    *err_out = 0xffffffffu;
    if (total_len == 0u || total_len > PP_REGFILE_MAX_SIZE) return 61;

    int olen = pp_regfile_build_open(req, sizeof(req), PP_REGFILE_O_WRONLY_CREAT_TRUNC, PP_REGFILE_MODE_0666, path);
    int rc = run_exchange(fd, 2u, req, olen);
    if (rc) return rc;
    if (pp_regfile_parse_open(reply_buf, reply_len, &efs_fd, &open_err) != 0) return 62;
    printf("{\"event\":\"regfile_open_write\",\"path\":\"%s\",\"efs_fd\":%d,\"open_errno\":%u}\n",
           path, (int)efs_fd, open_err);
    fflush(0);
    if (open_err != 0u) {
        *err_out = open_err;
        return 63;
    }

    while (off < total_len) {
        uint32_t chunk = total_len - off;
        int32_t bw = -1;
        uint32_t werr = 0xffffffffu;
        if (chunk > PP_REGFILE_IO_CHUNK) chunk = PP_REGFILE_IO_CHUNK;
        int wlen = pp_regfile_build_write(req, sizeof(req), efs_fd, off, data + off, chunk);
        rc = run_exchange(fd, 5u, req, wlen);
        if (rc || pp_regfile_parse_write(reply_buf, reply_len, efs_fd, off, &bw, &werr) != 0) {
            (void)efs_regfile_close(fd, efs_fd, &close_err);
            return rc ? rc : 64;
        }
        printf("{\"event\":\"regfile_write_chunk\",\"path\":\"%s\",\"offset\":%u,\"bytes_written\":%d,\"write_errno\":%u}\n",
               path, off, (int)bw, werr);
        fflush(0);
        if (werr != 0u || bw != (int32_t)chunk) {
            *err_out = (werr != 0u) ? werr : 999u;
            (void)efs_regfile_close(fd, efs_fd, &close_err);
            return 65;
        }
        off += chunk;
    }

    rc = efs_regfile_close(fd, efs_fd, &close_err);
    printf("{\"event\":\"regfile_close\",\"path\":\"%s\",\"efs_fd\":%d,\"close_errno\":%u}\n",
           path, (int)efs_fd, close_err);
    fflush(0);
    if (rc || close_err != 0u) {
        *err_out = close_err;
        return rc ? rc : 66;
    }
    *err_out = 0u;
    return 0;
}

static int do_sync(int fd, const char *phase) {
    uint8_t req[300];
    uint32_t token = 0u, serr = 0xffffffffu;
    uint16_t start_seq = g_seq++;
    int slen = pp_wide_build_sync_start(req, sizeof(req), PATH_IMS_ENABLE, start_seq);
    int rc = run_exchange(fd, 48u, req, slen);
    if (rc) return rc;
    if (pp_wide_parse_sync_start(reply_buf, reply_len, start_seq, &token, &serr) != 0) return 35;
    printf("{\"event\":\"sync_start\",\"phase\":\"%s\",\"seq\":%u,\"token\":%u,\"sync_errno\":%u}\n",
           phase, (unsigned)start_seq, token, serr);
    fflush(0);
    if (serr != 0u && serr != 306u) return 36;

    for (unsigned poll = 0u; poll < 60u; ++poll) {
        usleep(100000u);
        uint8_t status = 0xffu;
        uint32_t perr = 0xffffffffu;
        uint16_t pseq = g_seq++;
        int plen = pp_wide_build_sync_status(req, sizeof(req), PATH_IMS_ENABLE, pseq, token);
        rc = run_exchange(fd, 49u, req, plen);
        if (rc) return rc;
        if (pp_wide_parse_sync_status(reply_buf, reply_len, pseq, &status, &perr) != 0) return 37;
        printf("{\"event\":\"sync_poll\",\"phase\":\"%s\",\"poll\":%u,\"seq\":%u,\"token\":%u,\"status\":%u,\"poll_errno\":%u}\n",
               phase, poll, (unsigned)pseq, token, (unsigned)status, perr);
        fflush(0);
        if (perr != 0u) return 38;
        if (status == 1u) return 0;
    }
    return 39;
}

static int verify_ds_refs(int fd, const char *phase) {
    uint8_t buf[PP_REGFILE_MAX_SIZE];
    char sha_hex[65];

    for (size_t i = 0u; i < 4u; ++i) {
        pp_wide_stat st;
        uint32_t rerr = 0u;
        int rc = efs_stat_one(fd, DS_REFS[i].path, &st);
        if (rc || !st.present || st.size != DS_REFS[i].expected_size || (st.mode & EFS_S_IFMT) != EFS_S_IFREG) {
            printf("{\"event\":\"ds_ref_error\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"stat_errno\":%u,\"size\":%u}\n",
                   phase, DS_REFS[i].path, rc, st.diag_errno, st.size);
            fflush(0);
            return 70;
        }
        rc = efs_regfile_read_exact(fd, DS_REFS[i].path, DS_REFS[i].expected_size, buf, sizeof(buf), &rerr);
        if (rc) {
            printf("{\"event\":\"ds_ref_read_error\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"errno\":%u}\n",
                   phase, DS_REFS[i].path, rc, rerr);
            fflush(0);
            return 71;
        }
        pp_regfile_sha256_hex(buf, DS_REFS[i].expected_size, sha_hex);
        int sha_ok = (strcmp(sha_hex, DS_REFS[i].expected_sha256) == 0);
        printf("{\"event\":\"ds_ref\",\"phase\":\"%s\",\"path\":\"%s\",\"size\":%u,\"sha256\":\"%s\",\"match\":%s}\n",
               phase, DS_REFS[i].path, DS_REFS[i].expected_size, sha_hex, sha_ok ? "true" : "false");
        fflush(0);
        if (!sha_ok) return 72;
    }
    return 0;
}

static int inspect_single_item(int fd, const char *phase, const char *path,
                               pp_wide_stat *st_out, uint8_t *val_out, size_t val_cap) {
    pp_wide_stat st;
    uint8_t buf[PP_WIDE_MAX_ITEM];
    char sha_hex[65];
    int32_t nbytes = -1;
    uint32_t gerr = 0xffffffffu;

    int rc = efs_stat_one(fd, path, &st);
    if (rc) return rc;
    if (st_out) *st_out = st;
    if (!st.present) {
        printf("{\"event\":\"item\",\"phase\":\"%s\",\"path\":\"%s\",\"present\":false,\"stat_errno\":%u}\n",
               phase, path, st.diag_errno);
        fflush(0);
        return (st.diag_errno == EFS_ERR_ENOENT) ? 0 : 73;
    }
    if (st.size == 0u || st.size > PP_WIDE_MAX_ITEM) return 74;
    rc = efs_get_one(fd, path, st.size, buf, sizeof(buf), &nbytes, &gerr);
    if (rc || gerr != 0u || nbytes != (int32_t)st.size) {
        printf("{\"event\":\"item_get_error\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"errno\":%u,\"nbytes\":%d}\n",
               phase, path, rc, gerr, (int)nbytes);
        fflush(0);
        return 75;
    }
    if (val_out && st.size <= val_cap) copy_bytes(val_out, buf, st.size);
    pp_regfile_sha256_hex(buf, st.size, sha_hex);
    if (st.size == 1u) {
        printf("{\"event\":\"item\",\"phase\":\"%s\",\"path\":\"%s\",\"present\":true,\"mode\":%u,\"size\":1,\"byte0\":%u,\"sha256\":\"%s\"}\n",
               phase, path, st.mode, (unsigned)buf[0], sha_hex);
    } else {
        printf("{\"event\":\"item\",\"phase\":\"%s\",\"path\":\"%s\",\"present\":true,\"mode\":%u,\"size\":%u,\"sha256\":\"%s\"}\n",
               phase, path, st.mode, st.size, sha_hex);
    }
    fflush(0);
    return 0;
}

static int inspect_profile4(int fd, const char *phase, pp_wide_stat *st_out, char sha_out[65]) {
    pp_wide_stat st;
    uint8_t buf[PP_REGFILE_MAX_SIZE];
    uint32_t rerr = 0u;

    if (sha_out) sha_out[0] = '\0';
    int rc = efs_stat_one(fd, PATH_PDP_PROFILE4, &st);
    if (rc) return rc;
    if (st_out) *st_out = st;
    if (!st.present) {
        printf("{\"event\":\"regfile\",\"phase\":\"%s\",\"path\":\"%s\",\"present\":false,\"stat_errno\":%u}\n",
               phase, PATH_PDP_PROFILE4, st.diag_errno);
        fflush(0);
        return (st.diag_errno == EFS_ERR_ENOENT) ? 0 : 76;
    }
    if (st.size == 0u || st.size > PP_REGFILE_MAX_SIZE || (st.mode & EFS_S_IFMT) != EFS_S_IFREG) return 77;
    rc = efs_regfile_read_exact(fd, PATH_PDP_PROFILE4, st.size, buf, sizeof(buf), &rerr);
    if (rc) return rc;
    pp_regfile_sha256_hex(buf, st.size, sha_out);
    if (st.size == 789u) {
        printf("{\"event\":\"regfile\",\"phase\":\"%s\",\"path\":\"%s\",\"present\":true,\"mode\":%u,\"size\":%u,"
               "\"b750\":%u,\"b751\":%u,\"b752\":%u,\"b753\":%u,\"sha256\":\"%s\"}\n",
               phase, PATH_PDP_PROFILE4, st.mode, st.size,
               (unsigned)buf[750], (unsigned)buf[751], (unsigned)buf[752], (unsigned)buf[753], sha_out);
    } else {
        printf("{\"event\":\"regfile\",\"phase\":\"%s\",\"path\":\"%s\",\"present\":true,\"mode\":%u,\"size\":%u,\"sha256\":\"%s\"}\n",
               phase, PATH_PDP_PROFILE4, st.mode, st.size, sha_out);
    }
    fflush(0);
    return 0;
}

static int do_inspect_all(int fd, const char *phase) {
    pp_wide_stat st;
    uint8_t b0 = 0xffu;
    char p4_sha[65];

    for (size_t i = 0u; i < NUM_MANAGED_ITEMS; ++i) {
        int rc = inspect_single_item(fd, phase, MANAGED_ITEMS[i].path, &st, 0, 0u);
        if (rc) return rc;
    }
    int rc = inspect_single_item(fd, phase, PATH_HYBRID_ENABLE, &st, 0, 0u);
    if (rc) return rc;
    rc = inspect_single_item(fd, phase, PATH_UE_USAGE, &st, &b0, 1u);
    if (rc) return rc;
    if (!st.present || st.size != 1u || b0 != 0x00u) return 78;
    rc = inspect_single_item(fd, phase, PATH_MID_SRVCC, &st, 0, 0u);
    if (rc) return rc;
    rc = inspect_profile4(fd, phase, &st, p4_sha);
    if (rc) return rc;
    return verify_ds_refs(fd, phase);
}

static int do_rollback_all(int fd, const char *tag) {
    int first_err = 0;
    pp_wide_stat st;
    uint32_t err = 0u;

    /* 1. Restore IMS_enable=00 and voice_domain_pref=00 FIRST (reverse order) */
    for (size_t idx = NUM_MANAGED_ITEMS; idx > 0u; --idx) {
        const managed_item_spec *sp = &MANAGED_ITEMS[idx - 1u];
        if (!sp->baseline_exists_00) continue;
        int rc = efs_put_one(fd, sp->path, PP_V6_SCALAR_00, 1u, &err);
        printf("{\"event\":\"rollback_put\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"put_errno\":%u}\n",
               tag, sp->path, rc, err);
        fflush(0);
        if (rc || err != 0u) {
            if (!first_err) first_err = rc ? rc : 80;
        } else {
            uint8_t v = 0xffu;
            int vrc = inspect_single_item(fd, tag, sp->path, &st, &v, 1u);
            if (vrc || !st.present || v != 0x00u) {
                if (!first_err) first_err = vrc ? vrc : 81;
            }
        }
    }

    /* 2. UNLINK /pdp_profiles/profile4 */
    {
        int urc = efs_unlink_one(fd, PATH_PDP_PROFILE4, &err);
        printf("{\"event\":\"rollback_unlink\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"unlink_errno\":%u}\n",
               tag, PATH_PDP_PROFILE4, urc, err);
        fflush(0);
        if (urc || (err != 0u && err != EFS_ERR_ENOENT)) {
            if (!first_err) first_err = urc ? urc : 82;
        }
        int src = efs_stat_one(fd, PATH_PDP_PROFILE4, &st);
        if (src || st.present || st.diag_errno != EFS_ERR_ENOENT) {
            if (!first_err) first_err = src ? src : 83;
        }
    }

    /* 3. UNLINK ims_hybrid_enable (ensures standalone ENOENT baseline) */
    {
        int urc = efs_unlink_one(fd, PATH_HYBRID_ENABLE, &err);
        printf("{\"event\":\"rollback_unlink\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"unlink_errno\":%u}\n",
               tag, PATH_HYBRID_ENABLE, urc, err);
        fflush(0);
        if (urc || (err != 0u && err != EFS_ERR_ENOENT)) {
            if (!first_err) first_err = urc ? urc : 84;
        }
        int src = efs_stat_one(fd, PATH_HYBRID_ENABLE, &st);
        if (src || st.present || st.diag_errno != EFS_ERR_ENOENT) {
            if (!first_err) first_err = src ? src : 85;
        }
    }

    /* 4. UNLINK all 50 created S_IFITM items and verify ENOENT */
    for (size_t idx = NUM_MANAGED_ITEMS; idx > 0u; --idx) {
        const managed_item_spec *sp = &MANAGED_ITEMS[idx - 1u];
        if (sp->baseline_exists_00) continue;
        int urc = efs_unlink_one(fd, sp->path, &err);
        printf("{\"event\":\"rollback_unlink\",\"phase\":\"%s\",\"path\":\"%s\",\"rc\":%d,\"unlink_errno\":%u}\n",
               tag, sp->path, urc, err);
        fflush(0);
        if (urc || (err != 0u && err != EFS_ERR_ENOENT)) {
            if (!first_err) first_err = urc ? urc : 86;
        }
        int src = efs_stat_one(fd, sp->path, &st);
        if (src || st.present || st.diag_errno != EFS_ERR_ENOENT) {
            if (!first_err) first_err = src ? src : 87;
        }
    }

    int sy_rc = do_sync(fd, tag);
    if (sy_rc && !first_err) first_err = sy_rc;

    int ds_rc = verify_ds_refs(fd, tag);
    if (ds_rc && !first_err) first_err = ds_rc;

    return first_err;
}

static int rehearse_put_verify_item(int fd, const char *path, const uint8_t *data, uint32_t len) {
    pp_wide_stat st;
    uint8_t read_buf[PP_WIDE_MAX_ITEM];
    char sha_hex[65];
    uint32_t perr = 0xffffffffu, gerr = 0xffffffffu;
    int32_t nbytes = -1;

    int rc = efs_put_one(fd, path, data, len, &perr);
    if (rc || perr != 0u) return rc ? rc : 94;
    if (efs_stat_one(fd, path, &st) != 0 || !st.present || st.size != len) return 95;
    if (efs_get_one(fd, path, len, read_buf, sizeof(read_buf), &nbytes, &gerr) != 0 ||
        gerr != 0u || nbytes != (int32_t)len || !bytes_eq(read_buf, data, len)) {
        return 96;
    }
    pp_regfile_sha256_hex(read_buf, len, sha_hex);
    printf("{\"event\":\"rehearse_item_readback\",\"path\":\"%s\",\"mode\":%u,\"size\":%u,\"sha256\":\"%s\",\"exact_match\":true}\n",
           path, st.mode, st.size, sha_hex);
    fflush(0);
    return 0;
}

static int do_rehearse_stage6(int fd) {
    pp_wide_stat st;
    uint8_t ims_val = 0xffu, vdp_val = 0xffu;
    uint8_t read_p4[sizeof(PP_V6_PDP_PROFILE4_V4V6)];
    char sha_hex[65];
    uint32_t err = 0u;

    int rc = inspect_single_item(fd, "rehearse_before", PATH_IMS_ENABLE, &st, &ims_val, 1u);
    if (rc || !st.present || ims_val != 0x00u) return rc ? rc : 90;
    rc = inspect_single_item(fd, "rehearse_before", PATH_VOICE_PREF, &st, &vdp_val, 1u);
    if (rc || !st.present || vdp_val != 0x00u) return rc ? rc : 91;
    rc = inspect_single_item(fd, "rehearse_before", PATH_HYBRID_ENABLE, &st, 0, 0u);
    if (rc || st.present) return rc ? rc : 92;
    rc = inspect_profile4(fd, "rehearse_before", &st, sha_hex);
    if (rc || st.present) return rc ? rc : 93;
    rc = inspect_single_item(fd, "rehearse_before", PATH_SMS_CONFIG, &st, 0, 0u);
    if (rc || st.present) return rc ? rc : 93;
    rc = inspect_single_item(fd, "rehearse_before", PATH_RCS_AUTO_CONFIG, &st, 0, 0u);
    if (rc || st.present) return rc ? rc : 93;
    rc = inspect_single_item(fd, "rehearse_before", PATH_IMS_CONFIG, &st, 0, 0u);
    if (rc || st.present) return rc ? rc : 93;
    rc = verify_ds_refs(fd, "rehearse_before");
    if (rc) return rc;

    int wrc = efs_regfile_write_exact(fd, PATH_PDP_PROFILE4, PP_V6_PDP_PROFILE4_V4V6, sizeof(PP_V6_PDP_PROFILE4_V4V6), &err);
    int vrc = 0;
    if (!wrc) {
        if (efs_stat_one(fd, PATH_PDP_PROFILE4, &st) != 0 || !st.present ||
            st.size != sizeof(PP_V6_PDP_PROFILE4_V4V6) || (st.mode & EFS_S_IFMT) != EFS_S_IFREG) {
            vrc = 94;
        } else if (efs_regfile_read_exact(fd, PATH_PDP_PROFILE4, sizeof(PP_V6_PDP_PROFILE4_V4V6),
                                          read_p4, sizeof(read_p4), &err) != 0 ||
                   !bytes_eq(read_p4, PP_V6_PDP_PROFILE4_V4V6, sizeof(PP_V6_PDP_PROFILE4_V4V6)) ||
                   read_p4[750] != 1u || read_p4[751] != 1u || read_p4[752] != 0u || read_p4[753] != 0u) {
            vrc = 95;
        } else {
            pp_regfile_sha256_hex(read_p4, sizeof(read_p4), sha_hex);
            printf("{\"event\":\"rehearse_readback\",\"path\":\"%s\",\"mode\":%u,\"size\":%u,"
                   "\"b750\":%u,\"b751\":%u,\"b752\":%u,\"b753\":%u,\"sha256\":\"%s\",\"exact_match\":true}\n",
                   PATH_PDP_PROFILE4, st.mode, st.size,
                   (unsigned)read_p4[750], (unsigned)read_p4[751], (unsigned)read_p4[752], (unsigned)read_p4[753], sha_hex);
            fflush(0);
        }
    }
    if (!wrc && !vrc) {
        vrc = rehearse_put_verify_item(fd, PATH_SMS_CONFIG, PP_V6_QP_IMS_SMS_CONFIG, sizeof(PP_V6_QP_IMS_SMS_CONFIG));
    }
    if (!wrc && !vrc) {
        vrc = rehearse_put_verify_item(fd, PATH_RCS_AUTO_CONFIG, PP_V6_QP_IMS_RCS_AUTO_CONFIG, sizeof(PP_V6_QP_IMS_RCS_AUTO_CONFIG));
    }
    if (!wrc && !vrc) {
        vrc = rehearse_put_verify_item(fd, PATH_IMS_CONFIG, PP_V6_QP_IMS_CONFIG, sizeof(PP_V6_QP_IMS_CONFIG));
    }

    int rb_rc = do_rollback_all(fd, "rehearse_rollback");
    int post_p4 = inspect_profile4(fd, "rehearse_after", &st, sha_hex);
    int p4_absent = (!post_p4 && !st.present && st.diag_errno == EFS_ERR_ENOENT);
    int post_ims = inspect_single_item(fd, "rehearse_after", PATH_IMS_ENABLE, &st, &ims_val, 1u);
    int ims_ok = (!post_ims && st.present && ims_val == 0x00u);
    int post_vdp = inspect_single_item(fd, "rehearse_after", PATH_VOICE_PREF, &st, &vdp_val, 1u);
    int vdp_ok = (!post_vdp && st.present && vdp_val == 0x00u);

    if (wrc) return wrc;
    if (vrc) return vrc;
    if (rb_rc) return rb_rc;
    if (!p4_absent) return 97;
    if (!ims_ok || !vdp_ok) return 98;
    return 0;
}

static int do_provision_stage6_standalone(int fd, int stage_op) {
    const char *tag = (stage_op == OP_PROVISION_STAGE6A) ? "provision_stage6a"
                    : (stage_op == OP_PROVISION_STAGE6B) ? "provision_stage6b"
                    : (stage_op == OP_PROVISION_STAGE6C) ? "provision_stage6c"
                                                         : "provision_stage6d";
    const uint8_t *p4_target = (stage_op == OP_PROVISION_STAGE6C)
        ? PP_V6_PDP_PROFILE4_IPV6
        : PP_V6_PDP_PROFILE4_V4V6;

    pp_wide_stat st;
    uint8_t ue_val = 0xffu;
    uint8_t read_item[PP_WIDE_MAX_ITEM];
    uint8_t read_p4[sizeof(PP_V6_PDP_PROFILE4_V4V6)];
    char sha_hex[65];
    uint32_t err = 0u;

    /* 1. Verify untouched DS refs and ue_usage_setting==00 before touching anything */
    int rc = verify_ds_refs(fd, "provision_before");
    if (rc) return rc;
    rc = inspect_single_item(fd, "provision_before", PATH_UE_USAGE, &st, &ue_val, 1u);
    if (rc || !st.present || ue_val != 0x00u) return rc ? rc : 100;

    /* 2. Enforce standalone Hexagon DCM: /nv/item_files/ims/ims_hybrid_enable MUST be ENOENT
     *    so qpDplInitialize (0xd057dca8..0xd057dd18) sets *(uint8_t*)(dpl_ctx + 0xc19) = 0. */
    rc = efs_stat_one(fd, PATH_HYBRID_ENABLE, &st);
    if (rc) return rc;
    if (st.present || st.diag_errno != EFS_ERR_ENOENT) {
        rc = efs_unlink_one(fd, PATH_HYBRID_ENABLE, &err);
        if (rc || (err != 0u && err != EFS_ERR_ENOENT)) return rc ? rc : 101;
        rc = efs_stat_one(fd, PATH_HYBRID_ENABLE, &st);
        if (rc || st.present || st.diag_errno != EFS_ERR_ENOENT) return rc ? rc : 102;
    }
    printf("{\"event\":\"hybrid_enoent_verified\",\"stage\":\"%s\",\"path\":\"%s\",\"stat_errno\":%u}\n",
           tag, PATH_HYBRID_ENABLE, st.diag_errno);
    fflush(0);

    /* 3. Write & verify fixed /pdp_profiles/profile4 (789B S_IFREG) BEFORE flipping IMS_enable */
    rc = efs_regfile_write_exact(fd, PATH_PDP_PROFILE4, p4_target, sizeof(PP_V6_PDP_PROFILE4_V4V6), &err);
    if (rc || efs_stat_one(fd, PATH_PDP_PROFILE4, &st) != 0 || !st.present ||
        st.size != sizeof(PP_V6_PDP_PROFILE4_V4V6) || (st.mode & EFS_S_IFMT) != EFS_S_IFREG ||
        efs_regfile_read_exact(fd, PATH_PDP_PROFILE4, sizeof(PP_V6_PDP_PROFILE4_V4V6), read_p4, sizeof(read_p4), &err) != 0 ||
        !bytes_eq(read_p4, p4_target, sizeof(PP_V6_PDP_PROFILE4_V4V6)) ||
        read_p4[750] != 1u || read_p4[751] != 1u || read_p4[752] != 0u || read_p4[753] != 0u) {
        (void)do_rollback_all(fd, "provision_abort_profile4");
        return rc ? rc : 103;
    }
    pp_regfile_sha256_hex(read_p4, sizeof(read_p4), sha_hex);
    printf("{\"event\":\"provision_regfile_ok\",\"stage\":\"%s\",\"path\":\"%s\",\"size\":%u,"
           "\"b750\":%u,\"b751\":%u,\"b752\":%u,\"b753\":%u,\"sha256\":\"%s\"}\n",
           tag, PATH_PDP_PROFILE4, st.size,
           (unsigned)read_p4[750], (unsigned)read_p4[751], (unsigned)read_p4[752], (unsigned)read_p4[753], sha_hex);
    fflush(0);

    /* 4. Write & read-back verify all 52 managed S_IFITM items (IMS_enable=01 is LAST) */
    for (size_t i = 0u; i < NUM_MANAGED_ITEMS; ++i) {
        const managed_item_spec *sp = &MANAGED_ITEMS[i];
        const uint8_t *target = (stage_op == OP_PROVISION_STAGE6A) ? sp->stage6a_data
                              : (stage_op == OP_PROVISION_STAGE6B) ? sp->stage6b_data
                              : (stage_op == OP_PROVISION_STAGE6C) ? sp->stage6c_data
                                                                   : sp->stage6d_data;
        int32_t nbytes = -1;
        uint32_t gerr = 0xffffffffu;

        rc = efs_put_one(fd, sp->path, target, sp->target_len, &err);
        if (rc || err != 0u ||
            efs_stat_one(fd, sp->path, &st) != 0 || !st.present || st.size != sp->target_len ||
            efs_get_one(fd, sp->path, sp->target_len, read_item, sizeof(read_item), &nbytes, &gerr) != 0 ||
            gerr != 0u || nbytes != (int32_t)sp->target_len ||
            !bytes_eq(read_item, target, sp->target_len)) {
            printf("{\"event\":\"provision_item_error\",\"path\":\"%s\",\"rc\":%d,\"put_errno\":%u,\"get_errno\":%u}\n",
                   sp->path, rc, err, gerr);
            fflush(0);
            (void)do_rollback_all(fd, "provision_abort_item");
            return rc ? rc : 104;
        }
        pp_regfile_sha256_hex(read_item, sp->target_len, sha_hex);
        printf("{\"event\":\"provision_item_ok\",\"stage\":\"%s\",\"path\":\"%s\",\"size\":%u,\"sha256\":\"%s\"}\n",
               tag, sp->path, sp->target_len, sha_hex);
        fflush(0);
    }

    /* 5. Commit EFS2 SYNC and re-verify untouched DS profiles & ims_hybrid_enable ENOENT */
    rc = efs_stat_one(fd, PATH_HYBRID_ENABLE, &st);
    if (rc || st.present || st.diag_errno != EFS_ERR_ENOENT) {
        (void)do_rollback_all(fd, "provision_abort_hybrid_recheck");
        return rc ? rc : 105;
    }
    rc = do_sync(fd, tag);
    if (rc) {
        (void)do_rollback_all(fd, "provision_abort_sync");
        return rc;
    }
    rc = verify_ds_refs(fd, "provision_after");
    if (rc) {
        (void)do_rollback_all(fd, "provision_abort_ds");
        return rc;
    }
    return 0;
}

int main(int argc, char **argv, char **envp) {
    (void)envp;
    int op = OP_NONE;
    if (argc == 2) {
        if (!strcmp(argv[1], "--inspect-state")) op = OP_INSPECT_STATE;
        else if (!strcmp(argv[1], "--rehearse-stage6")) op = OP_REHEARSE_STAGE6;
        else if (!strcmp(argv[1], "--provision-stage6a")) op = OP_PROVISION_STAGE6A;
        else if (!strcmp(argv[1], "--provision-stage6b")) op = OP_PROVISION_STAGE6B;
        else if (!strcmp(argv[1], "--provision-stage6c")) op = OP_PROVISION_STAGE6C;
        else if (!strcmp(argv[1], "--provision-stage6d")) op = OP_PROVISION_STAGE6D;
        else if (!strcmp(argv[1], "--rollback-all")) op = OP_ROLLBACK_ALL;
        else if (!strcmp(argv[1], "--restore-usb")) op = OP_RESTORE_USB;
    }
    if (op == OP_NONE) {
        printf("{\"mode\":\"plan_only\",\"device_accessed\":false,\"version\":6,\"managed_items\":%u}\n",
               (unsigned)NUM_MANAGED_ITEMS);
        return argc == 1 ? 0 : 2;
    }

    alarm(180u);
    printf("{\"event\":\"identity\",\"version\":6,\"uid\":%u,\"pid\":%d,\"op\":%d}\n", getuid(), getpid(), op);
    fflush(0);
    if (getuid() != 0u || sizeof(unsigned long) != 4u) return 3;
    if (op == OP_RESTORE_USB && diag_status(1) == 0) return 0;
    if (op != OP_ROLLBACK_ALL && diag_status(op == OP_RESTORE_USB ? 2 : 1)) return 4;

    int fd = open("/dev/diag", 2 | 02000000);
    int saved_errno = *__errno();
    printf("{\"event\":\"open\",\"fd\":%d,\"errno\":%d}\n", fd, fd < 0 ? saved_errno : 0);
    fflush(0);
    if (fd < 0) return 5;

    int failure = 0;
    if (op == OP_RESTORE_USB) {
        int irc = ioctl(fd, 7ul, 1ul);
        saved_errno = *__errno();
        printf("{\"event\":\"ioctl\",\"request\":7,\"argument\":1,\"result\":%d,\"errno\":%d}\n",
               irc, irc < 0 ? saved_errno : 0);
        fflush(0);
        if (irc < 0 || diag_status(1)) failure = 6;
    } else {
        if (diag_status(2) != 0) {
            int irc = ioctl(fd, 7ul, 6ul);
            saved_errno = *__errno();
            printf("{\"event\":\"ioctl\",\"request\":7,\"argument\":6,\"result\":%d,\"errno\":%d}\n",
                   irc, irc < 0 ? saved_errno : 0);
            fflush(0);
            if (irc < 0 || diag_status(2)) failure = 6;
        }
        if (!failure) {
            if (op == OP_INSPECT_STATE) failure = do_inspect_all(fd, "inspect");
            else if (op == OP_REHEARSE_STAGE6) failure = do_rehearse_stage6(fd);
            else if (op == OP_PROVISION_STAGE6A || op == OP_PROVISION_STAGE6B ||
                     op == OP_PROVISION_STAGE6C || op == OP_PROVISION_STAGE6D) {
                failure = do_provision_stage6_standalone(fd, op);
            } else if (op == OP_ROLLBACK_ALL) failure = do_rollback_all(fd, "rollback_all");
        }
    }

    int crc = close(fd);
    printf("{\"event\":\"close\",\"result\":%d}\n", crc);
    fflush(0);
    if (crc || diag_status(1)) {
        int rfd = open("/dev/diag", 2 | 02000000);
        if (rfd >= 0) {
            (void)ioctl(rfd, 7ul, 1ul);
            (void)close(rfd);
        }
        if (crc || diag_status(1)) {
            if (!failure) failure = 8;
        }
    }
    printf("{\"event\":\"result\",\"version\":6,\"op\":%d,\"code\":%d,\"ok\":%s,\"functional_success\":false}\n",
           op, failure, !failure ? "true" : "false");
    fflush(0);
    return failure;
}
