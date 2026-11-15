#include <stdio.h>
#include <string.h>
#include "../imsa_probe_v3.h"

static unsigned g_checks = 0u, g_failed = 0u;

#define CHECK(cond, msg) do { \
    ++g_checks; \
    if (!(cond)) { \
        ++g_failed; \
        fprintf(stderr, "FAIL [%s:%d]: %s\n", __FILE__, __LINE__, (msg)); \
    } \
} while (0)

/* Mock QMUX harness that ACTUALLY overwrites 40 bytes before msg_ptr to test 64-byte headroom
 * and verifies connection-aware routing across conn=0 (rmnet0), conn=1 (rmnet1), conn=2 (rmnet2)! */
static QmiRxCb g_mock_rx = 0;
static int g_mock_ims_enabled = 0;
static int g_mock_down_called = 0;
static int g_mock_alloc_count = 0;
static int g_mock_release_count = 0;
static int g_mock_headroom_ok = 1;
static int g_mock_conn0_wds_mutated = 0;
static int g_mock_conn1_started = 0;
static int g_mock_conn1_stopped = 0;
static uint8_t g_mock_volte_en = 0u;
static uint8_t g_mock_mdata_en = 0u;
static uint8_t g_mock_voip_en = 0u;
static uint8_t g_mock_test_mode = 1u;

static void noop_pause(unsigned ms) {
    (void)ms;
}

static int mock_init(QmiRxCb rx_cb, void *sys_cb, void *user_data, void **handle_out) {
    (void)sys_cb;
    (void)user_data;
    g_mock_rx = rx_cb;
    g_mock_down_called = 0;
    g_mock_alloc_count = 0;
    g_mock_release_count = 0;
    *handle_out = (void *)(uintptr_t)0x1234u;
    return 0;
}

static int mock_alloc(void *handle, int conn, int service, unsigned char *client_out, int *err_out) {
    if (handle != (void *)(uintptr_t)0x1234u || conn < 0 || conn > 2) return -1;
    if (conn > 0 && service != QMI_SVC_WDS) return -1;
    if (!g_mock_ims_enabled && (service == QMI_SVC_IMSS || service == QMI_SVC_IMSA)) {
        *client_out = 0u;
        *err_out = 31; /* INVALID_SERVICE_TYPE when IMS_enable == 00 */
        return -2;
    }
    ++g_mock_alloc_count;
    *client_out = (unsigned char)(service + 10 * conn + 1);
    *err_out = 0;
    return 0;
}

static int mock_send(void *handle, int conn, int service, unsigned char client, void *msg, int msg_len) {
    if (handle != (void *)(uintptr_t)0x1234u || conn < 0 || conn > 2 || msg_len < 7) return -1;
    uint8_t *req = (uint8_t *)msg;
    /* Simulate vendor libqmi_client_qmux writing 40 bytes immediately BEFORE req! */
    uint8_t saved_req[QMI_REQ_MAX];
    memcpy(saved_req, req, (size_t)msg_len);
    memset(req - 40, 0xA5, 40u);
    if (memcmp(saved_req, req, (size_t)msg_len) != 0) {
        g_mock_headroom_ok = 0;
        return -2;
    }

    uint16_t txn = (uint16_t)((unsigned)req[1] | ((unsigned)req[2] << 8));
    uint16_t msg_id = (uint16_t)((unsigned)req[3] | ((unsigned)req[4] << 8));
    uint8_t rsp[192];
    memset(rsp, 0, sizeof(rsp));
    rsp[0] = 0x02u;
    rsp[1] = (uint8_t)(txn & 0xffu);
    rsp[2] = (uint8_t)(txn >> 8);
    rsp[3] = (uint8_t)(msg_id & 0xffu);
    rsp[4] = (uint8_t)(msg_id >> 8);

    size_t pos = 7u;
    /* TLV 0x02: result=0, error=0 */
    rsp[pos++] = 0x02u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
    rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;

    if (service == QMI_SVC_WDS && (msg_id == QMI_WDS_START_NET_IFACE || msg_id == QMI_WDS_STOP_NET_IFACE || msg_id == QMI_WDS_SET_IP_FAMILY_PREF)) {
        if (conn == 0) g_mock_conn0_wds_mutated = 1;
    }

    if (service == QMI_SVC_WDS && msg_id == QMI_WDS_GET_PKT_SRVC_STATUS) {
        uint8_t st = (conn == 0 || (conn == 1 && g_mock_ims_enabled)) ? 2u : 1u;
        rsp[pos++] = 0x01u; rsp[pos++] = 0x02u; rsp[pos++] = 0x00u; rsp[pos++] = st; rsp[pos++] = 0x00u;
    } else if (service == QMI_SVC_WDS && msg_id == QMI_WDS_GET_PROFILE_SETTINGS) {
        rsp[pos++] = 0x10u; rsp[pos++] = 0x03u; rsp[pos++] = 0x00u;
        rsp[pos++] = 'i'; rsp[pos++] = 'm'; rsp[pos++] = 's';
        rsp[pos++] = 0x11u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x03u;
        rsp[pos++] = 0x14u; rsp[pos++] = 0x03u; rsp[pos++] = 0x00u;
        rsp[pos++] = 'i'; rsp[pos++] = 'm'; rsp[pos++] = 's';
        rsp[pos++] = 0x20u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x21u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x01u;
        rsp[pos++] = 0x2eu; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x01u;
        rsp[pos++] = 0x33u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
    } else if (service == QMI_SVC_WDS && msg_id == QMI_WDS_GET_RUNTIME_SETTINGS) {
        rsp[pos++] = 0x14u; rsp[pos++] = 0x03u; rsp[pos++] = 0x00u;
        rsp[pos++] = 'i'; rsp[pos++] = 'm'; rsp[pos++] = 's';
        rsp[pos++] = 0x27u; rsp[pos++] = 0x11u; rsp[pos++] = 0x00u; rsp[pos++] = 0x01u;
        pos += 16u;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_SET_QIPCALL_CONFIG) {
        size_t at = 7u;
        while (at + 4u <= (size_t)msg_len) {
            uint8_t t = req[at];
            uint16_t l = (uint16_t)(req[at + 1u] | (req[at + 2u] << 8));
            if (l == 1u && t == 0x11u) g_mock_mdata_en = req[at + 3u];
            if (l == 1u && t == 0x12u) g_mock_volte_en = req[at + 3u];
            at += 3u + l;
        }
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_GET_QIPCALL_CONFIG) {
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x11u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x12u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = g_mock_mdata_en;
        rsp[pos++] = 0x13u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = g_mock_volte_en;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_SET_SVC_ENABLE_CFG) {
        size_t at = 7u;
        while (at + 4u <= (size_t)msg_len) {
            uint8_t t = req[at];
            uint16_t l = (uint16_t)(req[at + 1u] | (req[at + 2u] << 8));
            if (l == 1u && t == 0x10u) g_mock_voip_en = req[at + 3u];
            at += 3u + l;
        }
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_GET_SVC_ENABLE_CFG) {
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x11u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = g_mock_voip_en;
        rsp[pos++] = 0x13u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = g_mock_mdata_en;
        rsp[pos++] = 0x14u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = g_mock_volte_en;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_SET_REG_MGR_CONFIG) {
        if (msg_len == 11 && req[7] == 0x12u && req[8] == 0x01u && req[9] == 0x00u) {
            g_mock_test_mode = req[10];
        }
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_GET_REG_MGR_CONFIG) {
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x11u; rsp[pos++] = 0x02u; rsp[pos++] = 0x00u; rsp[pos++] = 0xc4u; rsp[pos++] = 0x13u;
        rsp[pos++] = 0x13u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = g_mock_test_mode;
    } else if (service == QMI_SVC_IMSS && msg_id == QMI_IMSS_GET_SMS_CONFIG) {
        rsp[pos++] = 0x10u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x11u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x01u;
    } else if (service == QMI_SVC_IMSA && msg_id == QMI_IMSA_GET_REG_STATUS) {
        static const char ERR_STR[] = "SIP 403 user 4600112" "34567890";
        size_t slen = strlen(ERR_STR);
        rsp[pos++] = 0x10u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x11u; rsp[pos++] = 0x02u; rsp[pos++] = 0x00u; rsp[pos++] = 0x93u; rsp[pos++] = 0x01u;
        rsp[pos++] = 0x12u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x13u; rsp[pos++] = (uint8_t)slen; rsp[pos++] = 0x00u;
        memcpy(rsp + pos, ERR_STR, slen);
        pos += slen;
    } else if (service == QMI_SVC_IMSA && msg_id == QMI_IMSA_GET_PDP_STATUS) {
        rsp[pos++] = 0x10u; rsp[pos++] = 0x01u; rsp[pos++] = 0x00u; rsp[pos++] = 0x01u;
        rsp[pos++] = 0x11u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u; rsp[pos++] = 0x00u;
    } else if (service == QMI_SVC_WDS && msg_id == QMI_WDS_START_NET_IFACE) {
        if (conn == 1) g_mock_conn1_started = 1;
        rsp[pos++] = 0x01u; rsp[pos++] = 0x04u; rsp[pos++] = 0x00u;
        rsp[pos++] = 0x78u; rsp[pos++] = 0x56u; rsp[pos++] = 0x34u; rsp[pos++] = 0x12u;
    } else if (service == QMI_SVC_WDS && msg_id == QMI_WDS_STOP_NET_IFACE) {
        if (conn == 1) g_mock_conn1_stopped = 1;
    }

    uint16_t tlv_len = (uint16_t)(pos - 7u);
    rsp[5] = (uint8_t)(tlv_len & 0xffu);
    rsp[6] = (uint8_t)(tlv_len >> 8);

    /* Inject wrong-conn packet first to prove qmi_rx_cb filters conn == g_exp_conn! */
    int wrong_conn = (conn == 0) ? 1 : 0;
    g_mock_rx(wrong_conn, service, (int)client, 0x80u, rsp, (int)pos);
    uint8_t ind[7] = { 0x04u, 0x01u, 0x00u, 0x20u, 0x00u, 0x00u, 0x00u };
    g_mock_rx(conn, service, (int)client, 0x80u, ind, 7);
    g_mock_rx(conn, service, (int)client, 0x80u, rsp, (int)pos);
    return 0;
}

static int mock_release(void *handle, int conn, int service, unsigned char client, int *err_out) {
    if (handle != (void *)(uintptr_t)0x1234u || conn < 0 || conn > 2 ||
        client != (unsigned char)(service + 10 * conn + 1)) {
        return -1;
    }
    ++g_mock_release_count;
    *err_out = 0;
    return 0;
}

static int mock_down(void *handle) {
    if (handle != (void *)(uintptr_t)0x1234u) return -1;
    g_mock_down_called = 1;
    return 0;
}

int main(void) {
    char hex[65];
    imsa_sha256_hex((const uint8_t *)"", 0u, hex);
    CHECK(strcmp(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") == 0, "SHA-256 empty");
    imsa_sha256_hex((const uint8_t *)"abc", 3u, hex);
    CHECK(strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0, "SHA-256 abc");

    char red[64];
    const char *raw = "SIP 403 Forbidden port 5060 user 4600198" "76543210";
    qmi_redact_ascii((const uint8_t *)raw, strlen(raw), red, sizeof(red));
    CHECK(strcmp(red, "SIP 403 Forbidden port 5060 user <num>") == 0, "qmi_redact_ascii");

    /* Unit check on QIPCALL 0x0037 & WDS 0x002B profile4 decoders */
    {
        static const uint8_t QIP_RSP[] = {
            0x02u, 0x01u, 0x00u, 0x37u, 0x00u, 0x1au, 0x00u,
            0x02u, 0x04u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u,
            0x10u, 0x04u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u,
            0x11u, 0x01u, 0x00u, 0x00u,
            0x12u, 0x01u, 0x00u, 0x01u,
            0x13u, 0x01u, 0x00u, 0x01u
        };
        ImssQipcallDecoded qd;
        CHECK(imss_decode_qipcall(QIP_RSP, sizeof(QIP_RSP), &qd) == 0, "decode 0x37");
        CHECK(qd.settings_rsp == 0 && qd.vt_calling_enabled == 0 &&
              qd.mobile_data_enabled == 1 && qd.volte_enabled == 1, "0x37 fields");

        static const uint8_t WDS_PROF_RSP[] = {
            0x02u, 0x02u, 0x00u, 0x2bu, 0x00u, 0x21u, 0x00u,
            0x02u, 0x04u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u,
            0x14u, 0x03u, 0x00u, 'i', 'm', 's',
            0x11u, 0x01u, 0x00u, 0x03u,
            0x20u, 0x01u, 0x00u, 0x00u,
            0x21u, 0x01u, 0x00u, 0x01u,
            0x2eu, 0x01u, 0x00u, 0x01u,
            0x33u, 0x01u, 0x00u, 0x00u
        };
        WdsProfileDecoded wd;
        CHECK(wds_decode_profile(WDS_PROF_RSP, sizeof(WDS_PROF_RSP), &wd) == 0, "decode 0x2b");
        CHECK(wd.apn_is_ims == 1 && wd.pdp_type == 3u &&
              wd.has_tlv_20 && wd.tlv_20_u8 == 0u &&
              wd.has_tlv_21 && wd.tlv_21_u8 == 1u &&
              wd.has_tlv_2e && wd.tlv_2e_u8 == 1u &&
              wd.has_tlv_33 && wd.tlv_33_u8 == 0u, "0x2b EP35 fixed profile4 TLVs");
    }

    QmiPoints pts = { mock_init, mock_alloc, mock_send, mock_release, mock_down };

    /* Scenario 1: IMS_enable == 00 (WDS conn 0, 1, 2 allocated & released; IMSS/IMSA rejected) */
    g_mock_ims_enabled = 0;
    g_mock_headroom_ok = 1;
    CHECK(imsa_run_probe_all(&pts, noop_pause) == 0, "probe_all under IMS_enable=00");
    CHECK(g_mock_headroom_ok == 1 && g_mock_alloc_count == 3 && g_mock_release_count == 3 && g_mock_down_called == 1,
          "WDS conn 0..2 alloc/release balanced under IMS_enable=00");

    /* Scenario 2: IMS_enable == 01 --imss-enable-volte sets mobile_data=1, volte=1, voip=1, test_mode=0 */
    g_mock_ims_enabled = 1;
    g_mock_headroom_ok = 1;
    g_mock_volte_en = 0u;
    g_mock_mdata_en = 0u;
    g_mock_voip_en = 0u;
    g_mock_test_mode = 1u;
    CHECK(imsa_run_imss_enable_volte(&pts, noop_pause) == 0, "imss_enable_volte under IMS_enable=01");
    CHECK(g_mock_headroom_ok == 1 && g_mock_alloc_count == 2 && g_mock_release_count == 2 && g_mock_down_called == 1,
          "IMSS+IMSA balanced on imss_enable_volte");
    CHECK(g_mock_mdata_en == 1u && g_mock_volte_en == 1u && g_mock_voip_en == 1u && g_mock_test_mode == 0u,
          "QMI_IMSS 0x36/0x53/0x21 state updated");

    /* Scenario 3: IMS_enable == 01 --probe-all (WDS conn 0..2 + IMSS + IMSA = 5 clients) */
    g_mock_headroom_ok = 1;
    CHECK(imsa_run_probe_all(&pts, noop_pause) == 0, "probe_all under IMS_enable=01");
    CHECK(g_mock_headroom_ok == 1 && g_mock_alloc_count == 5 && g_mock_release_count == 5 && g_mock_down_called == 1,
          "WDS(conn0..2)+IMSS+IMSA balanced");

    /* Scenario 4: WDS IMS cycle MUST run on conn=1 (RMNET_1) and NEVER mutate conn=0 (3gnet)! */
    g_mock_headroom_ok = 1;
    g_mock_conn0_wds_mutated = 0;
    g_mock_conn1_started = 0;
    g_mock_conn1_stopped = 0;
    CHECK(imsa_run_wds_ims_cycle(&pts, noop_pause, 6u, 8u) == 0, "wds_ims_cycle v6 on conn=1");
    CHECK(g_mock_headroom_ok == 1 && g_mock_alloc_count == 2 && g_mock_release_count == 2 && g_mock_down_called == 1,
          "WDS(conn=1)+IMSA(conn=0) cycle balanced");
    CHECK(g_mock_conn0_wds_mutated == 0 && g_mock_conn1_started == 1 && g_mock_conn1_stopped == 1,
          "WDS start/stop strictly isolated to conn=1 (RMNET_1), leaving conn=0 (3gnet) untouched");

    printf("{\"synthetic_only\":true,\"checks\":%u,\"failures\":%u}\n", g_checks, g_failed);
    return g_failed ? 1 : 0;
}
