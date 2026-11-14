/* Bounded wide-payload EFS2/DIAG HDLC codec for Passport msm8974 (up to 1024B items).
 * Does not modify or replace frozen prototype/passport-diag32-codec/legacy_codec.*. */
#ifndef PP_WIDE_DIAG_CODEC_H
#define PP_WIDE_DIAG_CODEC_H

#include <stddef.h>
#include <stdint.h>

#define PP_WIDE_MAX_PAYLOAD 2048u
#define PP_WIDE_MAX_ITEM    1024u
#define PP_WIDE_MAX_PATH    240u

/* Verified Passport EFS2_DIAG_PUT oflags (from EP02 0x08535060 & EP04 0x08529650):
 *   O_CREAT   = 0x00000040 (used by Hexagon qpDplIODevicePutItem 0xd05a4d40)
 *   O_AUTODIR = 0x00080000 (tested via bit 0x13 at 0x085296cc) */
#define PP_EFS_O_CREAT   0x00000040u
#define PP_EFS_O_AUTODIR 0x00080000u
#define PP_EFS_PUT_FLAGS (PP_EFS_O_CREAT | PP_EFS_O_AUTODIR)

typedef void (*pp_wide_packet_fn)(const uint8_t *, size_t, void *);

typedef struct {
    uint8_t bytes[PP_WIDE_MAX_PAYLOAD + 2u];
    size_t length;
    unsigned escaped, dropping, accepted, bad_crc, malformed, oversized;
} pp_wide_stream;

typedef struct {
    uint32_t diag_errno;
    uint32_t mode;
    uint32_t size;
    int present;
} pp_wide_stat;

size_t pp_cstr_len(const char *s);
uint16_t pp_wide_crc_x25(const uint8_t *data, size_t size);
int pp_wide_hdlc_encode(const uint8_t *data, size_t size, uint8_t *out, size_t capacity);
int pp_wide_frame(const uint8_t *data, size_t size, uint8_t *out, size_t capacity);
int pp_wide_receive(pp_wide_stream *s, const uint8_t *p, size_t n, pp_wide_packet_fn emit, void *ctx);

/* EFS2_DIAG_STAT (subcmd 15) */
int pp_wide_build_stat(uint8_t *out, size_t cap, const char *path);
int pp_wide_parse_stat(const uint8_t *pkt, size_t n, pp_wide_stat *st);

/* EFS2_DIAG_GET (subcmd 39) */
int pp_wide_build_get(uint8_t *out, size_t cap, const char *path, uint32_t data_len, uint16_t seq);
int pp_wide_parse_get(const uint8_t *pkt, size_t n, uint16_t expected_seq,
                      int32_t *num_bytes_out, uint32_t *errno_out,
                      uint8_t *data_out, size_t data_cap);

/* EFS2_DIAG_PUT (subcmd 38) */
int pp_wide_build_put(uint8_t *out, size_t cap, const char *path,
                      const uint8_t *data, uint32_t data_len,
                      uint32_t flags, uint16_t seq);
int pp_wide_parse_put(const uint8_t *pkt, size_t n, uint16_t expected_seq, uint32_t *errno_out);

/* EFS2_DIAG_UNLINK (subcmd 8) */
int pp_wide_build_unlink(uint8_t *out, size_t cap, const char *path);
int pp_wide_parse_unlink(const uint8_t *pkt, size_t n, uint32_t *errno_out);

/* EFS2_DIAG_SYNC_NO_WAIT (subcmd 48) & EFS2_DIAG_SYNC_GET_STATUS (subcmd 49) */
int pp_wide_build_sync_start(uint8_t *out, size_t cap, const char *path, uint16_t seq);
int pp_wide_parse_sync_start(const uint8_t *pkt, size_t n, uint16_t expected_seq,
                             uint32_t *token_out, uint32_t *errno_out);
int pp_wide_build_sync_status(uint8_t *out, size_t cap, const char *path, uint16_t seq, uint32_t token);
int pp_wide_parse_sync_status(const uint8_t *pkt, size_t n, uint16_t expected_seq,
                              uint8_t *status_out, uint32_t *errno_out);

#endif
