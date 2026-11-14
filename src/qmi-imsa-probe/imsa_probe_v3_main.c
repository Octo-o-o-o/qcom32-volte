#include "imsa_probe_v3.h"

struct Timespec32 {
    long sec;
    long nsec;
};
_Static_assert(sizeof(long) == 4 && sizeof(struct Timespec32) == 8, "ARM32 ABI");

extern unsigned getuid(void);
extern int getpid(void);
extern unsigned alarm(unsigned);
extern int open(const char *, int, ...);
extern int read(int, void *, unsigned);
extern int close(int);
extern int strcmp(const char *, const char *);
extern int printf(const char *, ...);
extern int fflush(void *);
extern int nanosleep(const struct Timespec32 *, struct Timespec32 *);
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern void _exit(int) __attribute__((noreturn));
extern int __system_property_get(const char *, char *);

static void pause_ms(unsigned ms) {
    struct Timespec32 t = { (long)(ms / 1000u), (long)(ms % 1000u) * 1000000L };
    (void)nanosleep(&t, 0);
}

enum {
    MODE_NONE = 0,
    MODE_PROBE_ALL = 1,
    MODE_WDS_V6 = 2,
    MODE_WDS_V4 = 3,
    MODE_WDS_ANY = 4,
    MODE_IMSS_ENABLE_VOLTE = 5
};

int main(int argc, char **argv, char **env) {
    (void)env;
    int mode = MODE_NONE;
    if (argc == 2) {
        if (strcmp(argv[1], "--probe-all") == 0) mode = MODE_PROBE_ALL;
        else if (strcmp(argv[1], "--wds-ims-cycle-v6") == 0) mode = MODE_WDS_V6;
        else if (strcmp(argv[1], "--wds-ims-cycle-v4") == 0) mode = MODE_WDS_V4;
        else if (strcmp(argv[1], "--wds-ims-cycle-any") == 0) mode = MODE_WDS_ANY;
        else if (strcmp(argv[1], "--imss-enable-volte") == 0) mode = MODE_IMSS_ENABLE_VOLTE;
    }
    if (mode == MODE_NONE) {
        printf("{\"mode\":\"plan_only\",\"device_accessed\":false,\"probe_version\":3}\n");
        fflush(0);
        return (argc == 1) ? 0 : 2;
    }

    alarm(50u);
    char context[128] = {0};
    char baseband[128] = {0};
    int fd = open("/proc/self/attr/current", 0);
    int n = (fd >= 0) ? read(fd, context, sizeof(context) - 1u) : -1;
    if (fd >= 0) (void)close(fd);
    if (n > 0 && context[n - 1] == '\n') context[n - 1] = 0;
    for (int i = 0; i < n; ++i) {
        if (context[i] == '"' || context[i] == '\\') context[i] = '_';
    }
    __system_property_get("ro.baseband", baseband);

    unsigned uid = getuid();
    int allowed_id = (strcmp(baseband, "msm") == 0) &&
                     (uid == 0u || (uid == 1001u && strcmp(context, "u:r:radio:s0") == 0));
    printf("{\"event\":\"probe_identity\",\"version\":3,\"uid\":%u,\"pid\":%d,\"domain\":\"%s\",\"mode\":%d,\"allowed\":%s}\n",
           uid, getpid(), context, mode, allowed_id ? "true" : "false");
    fflush(0);
    if (!allowed_id) return 3;

    void *lib = dlopen("/vendor/lib/libqmi_client_qmux.so", 2);
    if (!lib) {
        printf("{\"event\":\"probe_library\",\"available\":false}\n");
        fflush(0);
        _exit(4);
    }

    QmiPoints pts = {
        (QmiInitFn)dlsym(lib, "qmi_qmux_if_pwr_up_init"),
        (QmiAllocFn)dlsym(lib, "qmi_qmux_if_alloc_service_client"),
        (QmiSendFn)dlsym(lib, "qmi_qmux_if_send_qmi_msg"),
        (QmiReleaseFn)dlsym(lib, "qmi_qmux_if_release_service_client"),
        (QmiDownFn)dlsym(lib, "qmi_qmux_if_pwr_down_release")
    };

    int rc = -99;
    if (mode == MODE_PROBE_ALL) {
        rc = imsa_run_probe_all(&pts, pause_ms);
    } else if (mode == MODE_WDS_V6) {
        rc = imsa_run_wds_ims_cycle(&pts, pause_ms, 6u, 16u);
    } else if (mode == MODE_WDS_V4) {
        rc = imsa_run_wds_ims_cycle(&pts, pause_ms, 4u, 16u);
    } else if (mode == MODE_WDS_ANY) {
        rc = imsa_run_wds_ims_cycle(&pts, pause_ms, 0u, 16u);
    } else if (mode == MODE_IMSS_ENABLE_VOLTE) {
        rc = imsa_run_imss_enable_volte(&pts, pause_ms);
    }
    printf("{\"event\":\"probe_done\",\"version\":3,\"mode\":%d,\"rc\":%d,\"functional_success\":false}\n", mode, rc);
    fflush(0);
    _exit(rc == 0 ? 0 : 5);
}
