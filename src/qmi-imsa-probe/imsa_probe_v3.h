#ifndef PP_IMSA_PROBE_V3_H
#define PP_IMSA_PROBE_V3_H

#include <stddef.h>
#include <stdint.h>

#define QMI_SVC_WDS   1
#define QMI_SVC_IMSS  18
#define QMI_SVC_IMSA  33

/* QMUX connection IDs (0 = RMNET_0 / default 3gnet; 1 = RMNET_1 / secondary ims PDN; 2 = RMNET_2) */
#define QMI_CONN_RMNET_0  0
#define QMI_CONN_RMNET_1  1
#define QMI_CONN_RMNET_2  2

/* QMI_WDS (0x01) messages */
#define QMI_WDS_START_NET_IFACE       0x0020u
#define QMI_WDS_STOP_NET_IFACE        0x0021u
#define QMI_WDS_GET_PKT_SRVC_STATUS   0x0022u
#define QMI_WDS_GET_PROFILE_SETTINGS  0x002Bu
#define QMI_WDS_GET_RUNTIME_SETTINGS  0x002Du
#define QMI_WDS_SET_IP_FAMILY_PREF    0x004Du

/* QMI_IMSS (0x12 v1.30) messages (verified in EP29 & EP33 libqmiservices.so + libril-qc-qmi-1.so) */
#define QMI_IMSS_SET_REG_MGR_CONFIG   0x0021u
#define QMI_IMSS_GET_SIP_CONFIG       0x0025u
#define QMI_IMSS_GET_REG_MGR_CONFIG   0x0026u
#define QMI_IMSS_GET_SMS_CONFIG       0x0027u
#define QMI_IMSS_GET_USER_CONFIG      0x0028u
#define QMI_IMSS_GET_VOIP_CONFIG      0x0029u
#define QMI_IMSS_SET_QIPCALL_CONFIG   0x0036u
#define QMI_IMSS_GET_QIPCALL_CONFIG   0x0037u
#define QMI_IMSS_GET_REG_MGR_EXT_CFG  0x0041u
#define QMI_IMSS_SET_SVC_ENABLE_CFG   0x0053u
#define QMI_IMSS_GET_SVC_ENABLE_CFG   0x0054u

/* QMI_IMSA (0x21 v1.17) read-only GET messages */
#define QMI_IMSA_GET_REG_STATUS       0x0020u
#define QMI_IMSA_GET_SERVICE_STATUS   0x0021u
#define QMI_IMSA_GET_PDP_STATUS       0x0028u
#define QMI_IMSA_GET_LTE_ATTACH_PARMS 0x002Au

#define QMI_SEND_HEADROOM 64
#define QMI_REQ_MAX       96
#define QMI_RSP_MAX       1024

/* Exact local vendor libqmi_client_qmux.so prepends 32 IF bytes + 8 platform bytes before request[]. */
typedef struct {
    uint32_t prefix[QMI_SEND_HEADROOM / 4];
    uint8_t request[QMI_REQ_MAX];
} QmiTxBuf;

_Static_assert(offsetof(QmiTxBuf, request) == QMI_SEND_HEADROOM, "Writable 64-byte QMI headroom required");

typedef void (*QmiRxCb)(int conn, int service, int client, unsigned char flags, unsigned char *buf, int len);
typedef int (*QmiInitFn)(QmiRxCb rx_cb, void *sys_cb, void *user_data, void **handle_out);
typedef int (*QmiAllocFn)(void *handle, int conn, int service, unsigned char *client_out, int *err_out);
typedef int (*QmiSendFn)(void *handle, int conn, int service, unsigned char client, void *msg, int msg_len);
typedef int (*QmiReleaseFn)(void *handle, int conn, int service, unsigned char client, int *err_out);
typedef int (*QmiDownFn)(void *handle);

typedef struct {
    QmiInitFn init;
    QmiAllocFn alloc;
    QmiSendFn send;
    QmiReleaseFn release;
    QmiDownFn down;
} QmiPoints;

typedef struct {
    int send_rc;
    int matched;
    unsigned char rx_flags;
    int rsp_len;
    uint8_t rsp[QMI_RSP_MAX];
    int has_result_tlv;
    uint16_t qmi_result;
    uint16_t qmi_error;
} QmiExchangeResult;

/* Decoded QMI_IMSA 0x0020 GET_REGISTRATION_STATUS */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int has_legacy_reg;
    uint8_t legacy_reg;
    int has_error_code;
    uint16_t error_code;
    int has_reg_status;
    uint32_t reg_status;
    int has_error_string;
    char error_string_redacted[128];
} ImsaRegDecoded;

/* Decoded QMI_IMSA 0x0021 GET_SERVICE_STATUS */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int32_t sms_status;
    int32_t voip_status;
    int32_t vt_status;
    int32_t sms_rat;
    int32_t voip_rat;
    int32_t vt_rat;
    int32_t ut_status;
    int32_t ut_rat;
} ImsaSvcDecoded;

/* Decoded QMI_IMSA 0x0028 GET_PDP_STATUS */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int has_pdp_connected;
    uint8_t is_ims_pdp_connected;
    int has_pdp_fail_code;
    uint32_t ims_pdp_failure_error_code;
} ImsaPdpDecoded;

/* Decoded QMI_IMSS 0x0026 GET_REG_MGR_CONFIG */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int has_settings_rsp;
    uint32_t settings_rsp;
    int has_cscf_port;
    uint16_t cscf_port;
    int has_cs_domain;
    uint16_t cs_domain_len;
    int has_test_mode;
    uint8_t test_mode;
} ImssRegMgrDecoded;

/* Decoded QMI_IMSS 0x0037 GET_QIPCALL_CONFIG */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int32_t settings_rsp;
    int32_t vt_calling_enabled;
    int32_t mobile_data_enabled;
    int32_t volte_enabled;
    int32_t call_mode_prefer;
} ImssQipcallDecoded;

/* Decoded QMI_IMSS 0x0054 GET_IMS_SERVICE_ENABLE_CONFIG */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int32_t settings_rsp;
    int32_t voip_calling_enabled;
    int32_t vt_calling_enabled;
    int32_t mobile_data_enabled;
    int32_t volte_enabled;
    int32_t tlv15_u32;
    int32_t tlv16_u32;
    int32_t tlv17_u32;
} ImssSvcEnableDecoded;

/* Decoded QMI_WDS 0x002B GET_PROFILE_SETTINGS */
typedef struct {
    int valid;
    uint16_t qmi_result;
    uint16_t qmi_error;
    int has_ext_err;
    uint16_t ext_err;
    int has_pdp_type;
    uint8_t pdp_type;
    int has_apn;
    uint16_t apn_len;
    int apn_is_ims;
    int has_prof_name;
    int prof_name_is_ims;
    int has_tlv_20;
    uint8_t tlv_20_u8;
    int has_tlv_21;
    uint8_t tlv_21_u8;
    int has_tlv_2e;
    uint8_t tlv_2e_u8;
    int has_tlv_33;
    uint8_t tlv_33_u8;
} WdsProfileDecoded;

int qmi_build_sdu_req(uint8_t *out, size_t cap, uint16_t txn_id, uint16_t msg_id,
                      const uint8_t *tlvs, size_t tlvs_len);

int qmi_find_tlv(const uint8_t *sdu, size_t sdu_len, uint8_t target_type,
                 const uint8_t **val_out, uint16_t *len_out);

int qmi_parse_result_tlv(const uint8_t *sdu, size_t sdu_len,
                         uint16_t *result_out, uint16_t *error_out);

void qmi_redact_ascii(const uint8_t *src, size_t src_len, char *dst, size_t dst_cap);

int imsa_decode_reg_status(const uint8_t *sdu, size_t n, ImsaRegDecoded *out);
int imsa_decode_svc_status(const uint8_t *sdu, size_t n, ImsaSvcDecoded *out);
int imsa_decode_pdp_status(const uint8_t *sdu, size_t n, ImsaPdpDecoded *out);
int imss_decode_reg_mgr(const uint8_t *sdu, size_t n, ImssRegMgrDecoded *out);
int imss_decode_qipcall(const uint8_t *sdu, size_t n, ImssQipcallDecoded *out);
int imss_decode_svc_enable(const uint8_t *sdu, size_t n, ImssSvcEnableDecoded *out);
int wds_decode_profile(const uint8_t *sdu, size_t n, WdsProfileDecoded *out);

void imsa_sha256_hex(const uint8_t *data, size_t len, char hex_out[65]);

/* Full read-only --probe-all across WDS (conn=0,1,2), IMSS (18), and IMSA (33), printing JSON lines. */
int imsa_run_probe_all(const QmiPoints *pts, void (*pause_ms)(unsigned));

/* Active QMI_IMSS VoLTE + mobile_data + test_mode=0 enable & read-back (--imss-enable-volte). */
int imsa_run_imss_enable_volte(const QmiPoints *pts, void (*pause_ms)(unsigned));

/* Bounded WDS profile 4 start -> runtime settings -> IMSA poll -> stop cycle on conn=1 (RMNET_1, NEVER conn=0!). */
int imsa_run_wds_ims_cycle(const QmiPoints *pts, void (*pause_ms)(unsigned),
                           uint8_t ip_family_pref, unsigned hold_seconds);

#endif
