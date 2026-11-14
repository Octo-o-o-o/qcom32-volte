/* Bounded EFS2 regular-file DIAG helpers (OPEN, READ, WRITE, CLOSE) + FIPS 180-4 SHA-256
 * layered on top of wide_diag_codec.h without modifying frozen prototype/efs-ims-audit/.
 * Packet layouts verified from Passport baseband fs_diag.c 0x08532530..0x08532ef0
 * and efs_open 0x0852ac18 (EP02, EP14). */
#ifndef PP_REGFILE_DIAG_CODEC_H
#define PP_REGFILE_DIAG_CODEC_H

#include <stddef.h>
#include <stdint.h>
#include "wide_diag_codec.h"

#define PP_REGFILE_MAX_DIAG_FD          5
#define PP_REGFILE_IO_CHUNK             512u
#define PP_REGFILE_MAX_SIZE             1024u

/* Verified Hexagon efs_open oflag & mode bits (EP14 0x0852ac18..0x0852ad84):
 *   O_RDONLY = 0x0000
 *   O_WRONLY = 0x0001
 *   O_CREAT  = 0x0040
 *   O_TRUNC  = 0x0200
 *   0666     = 0x01b6 */
#define PP_REGFILE_O_RDONLY             0x00000000
#define PP_REGFILE_O_WRONLY_CREAT_TRUNC 0x00000241
#define PP_REGFILE_MODE_0666            0x000001b6

/* EFS2_DIAG_OPEN (subcmd 2): req 4b 13 02 00 + oflag(i32) + mode(i32) + path\0 -> rsp 12B */
int pp_regfile_build_open(uint8_t *out, size_t cap, int32_t oflag, int32_t mode, const char *path);
int pp_regfile_parse_open(const uint8_t *pkt, size_t n, int32_t *fd_out, uint32_t *errno_out);

/* EFS2_DIAG_READ (subcmd 4): req 4b 13 04 00 + fd(i32) + nbyte(u32) + offset(u32) -> rsp 20B + data */
int pp_regfile_build_read(uint8_t *out, size_t cap, int32_t fd, uint32_t nbyte, uint32_t offset);
int pp_regfile_parse_read(const uint8_t *pkt, size_t n, int32_t expected_fd, uint32_t expected_offset,
                          int32_t *bytes_read_out, uint32_t *errno_out,
                          uint8_t *data_out, size_t data_cap);

/* EFS2_DIAG_WRITE (subcmd 5): req 4b 13 05 00 + fd(i32) + offset(u32) + data -> rsp 20B */
int pp_regfile_build_write(uint8_t *out, size_t cap, int32_t fd, uint32_t offset,
                           const uint8_t *data, uint32_t data_len);
int pp_regfile_parse_write(const uint8_t *pkt, size_t n, int32_t expected_fd, uint32_t expected_offset,
                           int32_t *bytes_written_out, uint32_t *errno_out);

/* EFS2_DIAG_CLOSE (subcmd 3): req 4b 13 03 00 + fd(i32) -> rsp 8B */
int pp_regfile_build_close(uint8_t *out, size_t cap, int32_t fd);
int pp_regfile_parse_close(const uint8_t *pkt, size_t n, uint32_t *errno_out);

/* FIPS 180-4 SHA-256 lowercase hex (writes 64 hex chars + NUL into hex_out[65]) */
void pp_regfile_sha256_hex(const uint8_t *data, size_t len, char hex_out[65]);

/* Structural validation for untouched non-IMS DS /pdp_profiles/profile{1,2} (789B S_IFREG):
 *   - buf[0] == 0x15 (ds_umts_pdp_profile_type version)
 *   - buf[35] == expected_prof_num (1 or 2)
 *   - buf[750] == 0 (im_cn_flag == 0) and buf[751] == 0 (request_pcscf_address_flag == 0)
 * Allows cross-unit / cross-carrier QDP stack padding bytes while guaranteeing profile1/2 remain untouched non-IMS profiles. */
int pp_regfile_validate_ds_profile_ref(uint8_t expected_prof_num, const uint8_t *buf, size_t len);

#endif
