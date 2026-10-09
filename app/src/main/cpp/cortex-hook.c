#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <limits.h>
#include <errno.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <resolv.h>
#include <utime.h>
#include <sys/time.h>
#include <pwd.h>
#include <grp.h>
#include <pthread.h>

static int is_sigsys_synthetic_success_syscall(int sys_nr) {
#if defined(__NR_setuid)
    if (sys_nr == __NR_setuid) return 1;
#endif
#if defined(__NR_setuid32)
    if (sys_nr == __NR_setuid32) return 1;
#endif
#if defined(__NR_setgid)
    if (sys_nr == __NR_setgid) return 1;
#endif
#if defined(__NR_setgid32)
    if (sys_nr == __NR_setgid32) return 1;
#endif
#if defined(__NR_setreuid)
    if (sys_nr == __NR_setreuid) return 1;
#endif
#if defined(__NR_setreuid32)
    if (sys_nr == __NR_setreuid32) return 1;
#endif
#if defined(__NR_setregid)
    if (sys_nr == __NR_setregid) return 1;
#endif
#if defined(__NR_setregid32)
    if (sys_nr == __NR_setregid32) return 1;
#endif
#if defined(__NR_setresuid)
    if (sys_nr == __NR_setresuid) return 1;
#endif
#if defined(__NR_setresuid32)
    if (sys_nr == __NR_setresuid32) return 1;
#endif
#if defined(__NR_setresgid)
    if (sys_nr == __NR_setresgid) return 1;
#endif
#if defined(__NR_setresgid32)
    if (sys_nr == __NR_setresgid32) return 1;
#endif
#if defined(__NR_setfsuid)
    if (sys_nr == __NR_setfsuid) return 1;
#endif
#if defined(__NR_setfsuid32)
    if (sys_nr == __NR_setfsuid32) return 1;
#endif
#if defined(__NR_setfsgid)
    if (sys_nr == __NR_setfsgid) return 1;
#endif
#if defined(__NR_setfsgid32)
    if (sys_nr == __NR_setfsgid32) return 1;
#endif
#if defined(__NR_setgroups)
    if (sys_nr == __NR_setgroups) return 1;
#endif
#if defined(__NR_setgroups32)
    if (sys_nr == __NR_setgroups32) return 1;
#endif
#if defined(__NR_set_robust_list)
    if (sys_nr == __NR_set_robust_list) return 1;
#endif
#if defined(__NR_get_robust_list)
    if (sys_nr == __NR_get_robust_list) return 1;
#endif
    return 0;
}

static int is_handled_sigsys_syscall(int sys_nr) {
    if (is_sigsys_synthetic_success_syscall(sys_nr)) {
        return 1;
    }

    // Unified Linux 5.1+ syscall numbers across architectures
    if (sys_nr >= 424 && sys_nr < 512) {
        return 1;
    }

    // Architecture-specific syscall numbers guarded per target ABI
#if defined(__aarch64__)
    if (sys_nr == 97  /* __NR_unshare */ ||
        sys_nr == 217 /* __NR_add_key */ ||
        sys_nr == 218 /* __NR_request_key */ ||
        sys_nr == 219 /* __NR_keyctl */ ||
        sys_nr == 268 /* __NR_setns */ ||
        sys_nr == 277 /* __NR_seccomp */ ||
        sys_nr == 280 /* __NR_bpf */ ||
        sys_nr == 293 /* __NR_rseq */) {
        return 1;
    }
#elif defined(__arm__)
    if (sys_nr == 309 /* __NR_add_key */ ||
        sys_nr == 310 /* __NR_request_key */ ||
        sys_nr == 311 /* __NR_keyctl */ ||
        sys_nr == 337 /* __NR_unshare */ ||
        sys_nr == 375 /* __NR_setns */ ||
        sys_nr == 383 /* __NR_seccomp */ ||
        sys_nr == 386 /* __NR_bpf */ ||
        sys_nr == 398 /* __NR_rseq */) {
        return 1;
    }
#elif defined(__x86_64__)
    if (sys_nr == 248 /* __NR_add_key */ ||
        sys_nr == 249 /* __NR_request_key */ ||
        sys_nr == 250 /* __NR_keyctl */ ||
        sys_nr == 272 /* __NR_unshare */ ||
        sys_nr == 308 /* __NR_setns */ ||
        sys_nr == 317 /* __NR_seccomp */ ||
        sys_nr == 321 /* __NR_bpf */ ||
        sys_nr == 334 /* __NR_rseq */) {
        return 1;
    }
#elif defined(__i386__)
    if (sys_nr == 286 /* __NR_add_key */ ||
        sys_nr == 287 /* __NR_request_key */ ||
        sys_nr == 288 /* __NR_keyctl */ ||
        sys_nr == 310 /* __NR_unshare */ ||
        sys_nr == 346 /* __NR_setns */ ||
        sys_nr == 354 /* __NR_seccomp */ ||
        sys_nr == 357 /* __NR_bpf */ ||
        sys_nr == 386 /* __NR_rseq */) {
        return 1;
    }
#endif
    return 0;
}

#ifndef CORTEX_HOST_TEST

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

static int (*get_real_sigaction(void))(int, const struct sigaction *, struct sigaction *);

// Intercept SECCOMP blocked syscalls (SIGSYS), resolve sandbox/landlock gracefully, and advance PC
static void cortex_sigsys_handler(int sig, siginfo_t *info, void *ctx) {
    if (!ctx || !info) return;
    if (info->si_code != SYS_SECCOMP) {
        int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
        if (real_sig) {
            struct sigaction sa_dfl;
            memset(&sa_dfl, 0, sizeof(sa_dfl));
            sa_dfl.sa_handler = SIG_DFL;
            sigemptyset(&sa_dfl.sa_mask);
            real_sig(sig, &sa_dfl, NULL);
        }
        raise(sig);
        return;
    }
    ucontext_t *uctx = (ucontext_t *)ctx;

    int sys_nr = info->si_syscall;
    long ret_val = is_sigsys_synthetic_success_syscall(sys_nr) ? 0 : -ENOSYS;

#if defined(__aarch64__)
    uctx->uc_mcontext.regs[0] = (uint64_t)ret_val;
    if (uctx->uc_mcontext.pc >= 4) {
        uint32_t insn_at_pc = 0;
        uint32_t insn_before_pc = 0;
        memcpy(&insn_at_pc, (const void *)uctx->uc_mcontext.pc, sizeof(insn_at_pc));
        memcpy(&insn_before_pc, (const void *)(uctx->uc_mcontext.pc - 4), sizeof(insn_before_pc));
        // On SECCOMP_RET_TRAP, the kernel already advanced PC past svc #0 (*(pc - 4) is svc #0).
        // Only advance PC if PC itself points to svc #0 and *(pc - 4) does not.
        int at_is_svc = ((insn_at_pc & 0xffe0001fU) == 0xd4000001U);
        int prev_is_svc = ((insn_before_pc & 0xffe0001fU) == 0xd4000001U);
        if (at_is_svc && !prev_is_svc) {
            uctx->uc_mcontext.pc += 4;
        }
    }
#elif defined(__arm__)
    uctx->uc_mcontext.arm_r0 = (unsigned long)ret_val;
    if (uctx->uc_mcontext.arm_pc >= 4) {
        if (uctx->uc_mcontext.arm_cpsr & 0x20) {
            uint16_t insn_at_pc = 0;
            uint16_t insn_before_pc = 0;
            memcpy(&insn_at_pc, (const void *)uctx->uc_mcontext.arm_pc, sizeof(insn_at_pc));
            memcpy(&insn_before_pc, (const void *)(uctx->uc_mcontext.arm_pc - 2), sizeof(insn_before_pc));
            int at_is_svc = ((insn_at_pc & 0xff00U) == 0xdf00U);
            int prev_is_svc = ((insn_before_pc & 0xff00U) == 0xdf00U);
            if (at_is_svc && !prev_is_svc) {
                uctx->uc_mcontext.arm_pc += 2;
            }
        } else {
            uint32_t insn_at_pc = 0;
            uint32_t insn_before_pc = 0;
            memcpy(&insn_at_pc, (const void *)uctx->uc_mcontext.arm_pc, sizeof(insn_at_pc));
            memcpy(&insn_before_pc, (const void *)(uctx->uc_mcontext.arm_pc - 4), sizeof(insn_before_pc));
            int at_is_svc = ((insn_at_pc & 0x0f000000U) == 0x0f000000U);
            int prev_is_svc = ((insn_before_pc & 0x0f000000U) == 0x0f000000U);
            if (at_is_svc && !prev_is_svc) {
                uctx->uc_mcontext.arm_pc += 4;
            }
        }
    }
#elif defined(__x86_64__) && defined(REG_RAX)
    uctx->uc_mcontext.gregs[REG_RAX] = (greg_t)ret_val;
    if (uctx->uc_mcontext.gregs[REG_RIP] >= 2) {
        unsigned char insn_at[2] = {0};
        unsigned char insn_prev[2] = {0};
        memcpy(insn_at, (const void *)uctx->uc_mcontext.gregs[REG_RIP], sizeof(insn_at));
        memcpy(insn_prev, (const void *)(uctx->uc_mcontext.gregs[REG_RIP] - 2), sizeof(insn_prev));
        int at_is_sys = (insn_at[0] == 0x0f && insn_at[1] == 0x05);
        int prev_is_sys = (insn_prev[0] == 0x0f && insn_prev[1] == 0x05);
        if (at_is_sys && !prev_is_sys) {
            uctx->uc_mcontext.gregs[REG_RIP] += 2;
        }
    }
#elif defined(__i386__) && defined(REG_EAX)
    uctx->uc_mcontext.gregs[REG_EAX] = (greg_t)ret_val;
    if (uctx->uc_mcontext.gregs[REG_EIP] >= 2) {
        unsigned char insn_at[2] = {0};
        unsigned char insn_prev[2] = {0};
        memcpy(insn_at, (const void *)uctx->uc_mcontext.gregs[REG_EIP], sizeof(insn_at));
        memcpy(insn_prev, (const void *)(uctx->uc_mcontext.gregs[REG_EIP] - 2), sizeof(insn_prev));
        int at_is_sys = (insn_at[0] == 0xcd && insn_at[1] == 0x80);
        int prev_is_sys = (insn_prev[0] == 0xcd && insn_prev[1] == 0x80);
        if (at_is_sys && !prev_is_sys) {
            uctx->uc_mcontext.gregs[REG_EIP] += 2;
        }
    }
#endif
}

static int (*get_real_sigaction(void))(int, const struct sigaction *, struct sigaction *) {
    static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *) = NULL;
    if (!real_sigaction) {
        real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
        if (!real_sigaction) {
            real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_DEFAULT, "sigaction");
        }
    }
    return real_sigaction;
}

__attribute__((constructor(101))) static void install_sigsys_handler(void) {
    int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!real_sig) return;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = cortex_sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    real_sig(SIGSYS, &sa, NULL);
}

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!real_sig) return -1;

    if (signum == SIGSYS) {
        if (act && act->sa_sigaction == cortex_sigsys_handler) {
            return real_sig(signum, act, oldact);
        }
        if (oldact) {
            memset(oldact, 0, sizeof(*oldact));
            oldact->sa_sigaction = cortex_sigsys_handler;
            oldact->sa_flags = SA_SIGINFO | SA_RESTART;
        }
        return 0;
    }
    return real_sig(signum, act, oldact);
}

int rt_sigaction(int signum, const struct sigaction *act, struct sigaction *oldact, size_t sigsetsize) {
    static int (*orig_rt_sigaction)(int, const struct sigaction *, struct sigaction *, size_t) = NULL;
    if (!orig_rt_sigaction) orig_rt_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *, size_t))dlsym(RTLD_NEXT, "rt_sigaction");

    if (signum == SIGSYS) {
        if (act && act->sa_sigaction == cortex_sigsys_handler) {
            return orig_rt_sigaction ? orig_rt_sigaction(signum, act, oldact, sigsetsize) : 0;
        }
        if (oldact) {
            memset(oldact, 0, sizeof(*oldact));
            oldact->sa_sigaction = cortex_sigsys_handler;
            oldact->sa_flags = SA_SIGINFO | SA_RESTART;
        }
        return 0;
    }
    return orig_rt_sigaction ? orig_rt_sigaction(signum, act, oldact, sigsetsize) : -1;
}

int __libc_sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    return sigaction(signum, act, oldact);
}

typedef void (*sighandler_t)(int);
sighandler_t signal(int signum, sighandler_t handler) {
    static sighandler_t (*orig_signal)(int, sighandler_t) = NULL;
    if (!orig_signal) orig_signal = (sighandler_t (*)(int, sighandler_t))dlsym(RTLD_NEXT, "signal");
    if (signum == SIGSYS) {
        return (sighandler_t)cortex_sigsys_handler;
    }
    return orig_signal ? orig_signal(signum, handler) : NULL;
}

int sigprocmask(int how, const sigset_t *set, sigset_t *oldset) {
    static int (*orig_sigprocmask)(int, const sigset_t *, sigset_t *) = NULL;
    if (!orig_sigprocmask) orig_sigprocmask = (int (*)(int, const sigset_t *, sigset_t *))dlsym(RTLD_NEXT, "sigprocmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t mod_set = *set;
        sigdelset(&mod_set, SIGSYS);
        return orig_sigprocmask ? orig_sigprocmask(how, &mod_set, oldset) : 0;
    }
    return orig_sigprocmask ? orig_sigprocmask(how, set, oldset) : 0;
}

int pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset) {
    static int (*orig_pthread_sigmask)(int, const sigset_t *, sigset_t *) = NULL;
    if (!orig_pthread_sigmask) orig_pthread_sigmask = (int (*)(int, const sigset_t *, sigset_t *))dlsym(RTLD_NEXT, "pthread_sigmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t mod_set = *set;
        sigdelset(&mod_set, SIGSYS);
        return orig_pthread_sigmask ? orig_pthread_sigmask(how, &mod_set, oldset) : 0;
    }
    return orig_pthread_sigmask ? orig_pthread_sigmask(how, set, oldset) : 0;
}

int rt_sigprocmask(int how, const sigset_t *set, sigset_t *oldset, size_t sigsetsize) {
    static int (*orig_rt_sigprocmask)(int, const sigset_t *, sigset_t *, size_t) = NULL;
    if (!orig_rt_sigprocmask) orig_rt_sigprocmask = (int (*)(int, const sigset_t *, sigset_t *, size_t))dlsym(RTLD_NEXT, "rt_sigprocmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t mod_set;
        memcpy(&mod_set, set, sizeof(mod_set));
        sigdelset(&mod_set, SIGSYS);
        return orig_rt_sigprocmask ? orig_rt_sigprocmask(how, &mod_set, oldset, sigsetsize) : 0;
    }
    return orig_rt_sigprocmask ? orig_rt_sigprocmask(how, set, oldset, sigsetsize) : 0;
}


static char g_cortex_root[PATH_MAX] = {0};
static char g_real_exe[PATH_MAX] = {0};
static int g_initialized = 0;

static void init_cortex_hook(void) {
    if (g_initialized) return;
    const char *root = getenv("CORTEX_ROOT");
    if (root && strlen(root) > 0) {
        strncpy(g_cortex_root, root, sizeof(g_cortex_root) - 1);
        size_t len = strlen(g_cortex_root);
        if (len > 0 && g_cortex_root[len - 1] == '/') {
            g_cortex_root[len - 1] = '\0';
        }
    } else {
        Dl_info info;
        if (dladdr((void *)init_cortex_hook, &info) && info.dli_fname) {
            const char *p = strstr(info.dli_fname, "/usr/lib/libcortex-hook");
            if (p && p > info.dli_fname) {
                size_t len = p - info.dli_fname;
                if (len < sizeof(g_cortex_root)) {
                    strncpy(g_cortex_root, info.dli_fname, len);
                    g_cortex_root[len] = '\0';
                }
            }
        }
    }
    const char *curr_real_exe = getenv("CORTEX_REAL_EXE");
    if (curr_real_exe && curr_real_exe[0] != '\0') {
        strncpy(g_real_exe, curr_real_exe, sizeof(g_real_exe) - 1);
    }
    if (g_cortex_root[0] != '\0') {
        const char *curr_tzdir = getenv("TZDIR");
        if (!curr_tzdir || curr_tzdir[0] == '\0') {
            char tzdir_buf[PATH_MAX];
            snprintf(tzdir_buf, sizeof(tzdir_buf), "%s/usr/share/zoneinfo", g_cortex_root);
            setenv("TZDIR", tzdir_buf, 0);
        }

        const char *curr_cert = getenv("SSL_CERT_FILE");
        if (!curr_cert || curr_cert[0] == '\0' || strcmp(curr_cert, "/etc/ssl/certs/ca-certificates.crt") == 0) {
            char cert_buf[PATH_MAX];
            snprintf(cert_buf, sizeof(cert_buf), "%s/etc/ssl/certs/ca-certificates.crt", g_cortex_root);
            setenv("SSL_CERT_FILE", cert_buf, 1);
        }

        const char *curr_cert_dir = getenv("SSL_CERT_DIR");
        if (!curr_cert_dir || curr_cert_dir[0] == '\0' || strcmp(curr_cert_dir, "/etc/ssl/certs") == 0) {
            char cert_dir_buf[PATH_MAX];
            snprintf(cert_dir_buf, sizeof(cert_dir_buf), "%s/etc/ssl/certs:/system/etc/security/cacerts", g_cortex_root);
            setenv("SSL_CERT_DIR", cert_dir_buf, 1);
        }

        const char *curr_curl = getenv("CURL_CA_BUNDLE");
        if (!curr_curl || curr_curl[0] == '\0' || strcmp(curr_curl, "/etc/ssl/certs/ca-certificates.crt") == 0) {
            char curl_buf[PATH_MAX];
            snprintf(curl_buf, sizeof(curl_buf), "%s/etc/ssl/certs/ca-certificates.crt", g_cortex_root);
            setenv("CURL_CA_BUNDLE", curl_buf, 1);
        }

        const char *curr_godebug = getenv("GODEBUG");
        if (!curr_godebug || curr_godebug[0] == '\0') {
            setenv("GODEBUG", "netdns=cgo", 0);
        } else if (strstr(curr_godebug, "netdns") == NULL) {
            char godebug_buf[512];
            snprintf(godebug_buf, sizeof(godebug_buf), "%s,netdns=cgo", curr_godebug);
            setenv("GODEBUG", godebug_buf, 1);
        }
    }
    g_initialized = 1;
}

void tzset(void) {
    static void (*orig_tzset)(void) = NULL;
    if (!orig_tzset) orig_tzset = (void (*)(void))dlsym(RTLD_NEXT, "tzset");
    init_cortex_hook();
    if (g_cortex_root[0] != '\0') {
        const char *curr_tzdir = getenv("TZDIR");
        if (!curr_tzdir || curr_tzdir[0] == '\0') {
            char tzdir_buf[PATH_MAX];
            snprintf(tzdir_buf, sizeof(tzdir_buf), "%s/usr/share/zoneinfo", g_cortex_root);
            setenv("TZDIR", tzdir_buf, 0);
        }
    }
    if (orig_tzset) orig_tzset();
}

static int is_proc_self_exe(const char *path) {
    if (!path) return 0;
    if (strcmp(path, "/proc/self/exe") == 0 ||
        strcmp(path, "/proc/thread-self/exe") == 0) {
        return 1;
    }
    if (strncmp(path, "/proc/", 6) == 0) {
        const char *p = path + 6;
        while (*p >= '0' && *p <= '9') p++;
        if (strcmp(p, "/exe") == 0) {
            pid_t pid = (pid_t)atoi(path + 6);
            if (pid == getpid()) {
                return 1;
            }
        }
    }
    return 0;
}

static ssize_t handle_proc_self_exe(char *buf, size_t bufsiz) {
    if (!buf || bufsiz == 0) {
        errno = EINVAL;
        return -1;
    }
    init_cortex_hook();
    if (g_real_exe[0] == '\0') {
        int fd = open("/proc/self/cmdline", O_RDONLY);
        if (fd >= 0) {
            char cmdline[8192];
            ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
            close(fd);
            if (n > 0) {
                cmdline[n] = '\0';
                char *args[64] = {0};
                int ac = 0;
                char *p = cmdline;
                while (p < cmdline + n && ac < 64) {
                    args[ac++] = p;
                    p += strlen(p) + 1;
                }
                for (int i = 0; i < ac; i++) {
                    if (strcmp(args[i], "--argv0") == 0 && i + 2 < ac) {
                        strncpy(g_real_exe, args[i + 2], sizeof(g_real_exe) - 1);
                        g_real_exe[sizeof(g_real_exe) - 1] = '\0';
                        break;
                    }
                }
                if (g_real_exe[0] == '\0' && ac > 0 && args[0] && args[0][0] != '\0') {
                    strncpy(g_real_exe, args[0], sizeof(g_real_exe) - 1);
                    g_real_exe[sizeof(g_real_exe) - 1] = '\0';
                }
            }
        }
    }

    if (g_real_exe[0] != '\0') {
        char stripped[PATH_MAX];
        const char *out_path = g_real_exe;
        if (g_cortex_root[0] != '\0') {
            size_t rlen = strlen(g_cortex_root);
            if (strncmp(g_real_exe, g_cortex_root, rlen) == 0 &&
                (g_real_exe[rlen] == '/' || g_real_exe[rlen] == '\0')) {
                out_path = g_real_exe + rlen;
                if (out_path[0] == '\0') out_path = "/";
            }
        }
        strncpy(stripped, out_path, sizeof(stripped) - 1);
        stripped[sizeof(stripped) - 1] = '\0';

        size_t len = strlen(stripped);
        size_t copy_len = (len < bufsiz) ? len : bufsiz;
        memcpy(buf, stripped, copy_len);
        return (ssize_t)copy_len;
    }
    return -1;
}

static inline int is_path_prefix(const char *path, const char *prefix, size_t prefix_len) {
    if (strncmp(path, prefix, prefix_len) != 0) return 0;
    return path[prefix_len] == '/' || path[prefix_len] == '\0';
}

static inline const char *get_rootfs_subpath(const char *target) {
    if (!target || g_cortex_root[0] == '\0') return NULL;
    size_t root_len = strlen(g_cortex_root);
    if (strncmp(target, g_cortex_root, root_len) == 0 &&
        (target[root_len] == '/' || target[root_len] == '\0')) {
        return target + root_len;
    }
    return NULL;
}

static const char *rewrite_path(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    // Relative paths must NEVER be rewritten to rootfs root
    if (path[0] != '/') return path;
    init_cortex_hook();

    if (g_cortex_root[0] == '\0') {
        return path;
    }

    // Strip multiple redundant leading slashes: //foo -> /foo
    while (path[0] == '/' && path[1] == '/') {
        path++;
    }

    // Strip leading /./: /./boot -> /boot
    while (path[0] == '/' && path[1] == '.' && (path[2] == '/' || path[2] == '\0')) {
        if (path[2] == '/') {
            path += 2;
        } else {
            path = "/";
            break;
        }
    }

    // Root directory
    if (strcmp(path, "/") == 0) {
        snprintf(buffer, bufsize, "%s", g_cortex_root);
        return buffer;
    }

    // Already inside Cortex rootfs
    size_t root_len = strlen(g_cortex_root);
    if (strncmp(path, g_cortex_root, root_len) == 0 &&
        (path[root_len] == '/' || path[root_len] == '\0')) {
        return path;
    }

    // POSIX shared memory (/dev/shm) redirection to Cortex rootfs tmp/shm
    if (strncmp(path, "/dev/shm", 8) == 0 && (path[8] == '/' || path[8] == '\0')) {
        snprintf(buffer, bufsize, "%s/tmp/shm%s", g_cortex_root, path + 8);
        return buffer;
    }

    // Real host Android kernel & system mounts
    if (strcmp(path, "/sbin/su") == 0 || strcmp(path, "/su") == 0 ||
        is_path_prefix(path, "/proc", 5) ||
        is_path_prefix(path, "/dev", 4) ||
        is_path_prefix(path, "/sys", 4) ||
        is_path_prefix(path, "/system", 7) ||
        is_path_prefix(path, "/vendor", 7) ||
        is_path_prefix(path, "/apex", 5) ||
        is_path_prefix(path, "/product", 8) ||
        is_path_prefix(path, "/system_ext", 11) ||
        is_path_prefix(path, "/odm", 4) ||
        is_path_prefix(path, "/oem", 4) ||
        is_path_prefix(path, "/linkerconfig", 13) ||
        is_path_prefix(path, "/config", 7) ||
        is_path_prefix(path, "/d", 2) ||
        is_path_prefix(path, "/data", 5) ||
        is_path_prefix(path, "/sdcard", 7) ||
        is_path_prefix(path, "/storage", 8)) {
        return path;
    }

    // Any other absolute path belongs to Cortex rootfs (/boot, /media, /snap, /etc, /usr, ...)
    if (path[0] == '/') {
        snprintf(buffer, bufsize, "%s%s", g_cortex_root, path);
        return buffer;
    }

    return path;
}

static const char *rewrite_unix_socket_path(const char *sun_path, char *out_buf, size_t out_size) {
    if (!sun_path || out_size == 0) return sun_path;
    // Abstract sockets start with null byte (\0); leave untouched
    if (sun_path[0] == '\0') {
        return sun_path;
    }

    char full[PATH_MAX];
    const char *rw = (sun_path[0] == '/') ? rewrite_path(sun_path, full, sizeof(full)) : sun_path;
    size_t rw_len = strlen(rw);

    // If rewritten path fits safely in sockaddr_un.sun_path (usually 108 bytes)
    if (rw_len < out_size) {
        strncpy(out_buf, rw, out_size - 1);
        out_buf[out_size - 1] = '\0';
        return out_buf;
    }

    // Path exceeds sockaddr_un 108-byte limit!
    // Deterministically hash the path into /tmp to guarantee it fits safely (< 80 bytes)
    // while remaining 100% deterministic between server and client.
    unsigned long long hash = 14695981039346656037ULL;
    for (const char *p = rw; *p; p++) {
        hash ^= (unsigned char)(*p);
        hash *= 1099511628211ULL;
    }

    init_cortex_hook();
    if (g_cortex_root[0] != '\0') {
        snprintf(out_buf, out_size, "%s/tmp/.ctx_sock_%016llx", g_cortex_root, hash);
    } else {
        snprintf(out_buf, out_size, "/tmp/.ctx_sock_%016llx", hash);
    }
    out_buf[out_size - 1] = '\0';
    return out_buf;
}

static void unlink_mapped_unix_socket(const char *target) {
    if (!target) return;
    size_t t_len = strlen(target);
    if (t_len >= 107) {
        unsigned long long hash = 14695981039346656037ULL;
        for (const char *p = target; *p; p++) {
            hash ^= (unsigned char)(*p);
            hash *= 1099511628211ULL;
        }
        char short_path[PATH_MAX];
        init_cortex_hook();
        if (g_cortex_root[0] != '\0') {
            snprintf(short_path, sizeof(short_path), "%s/tmp/.ctx_sock_%016llx", g_cortex_root, hash);
        } else {
            snprintf(short_path, sizeof(short_path), "/tmp/.ctx_sock_%016llx", hash);
        }
        static int (*orig_unlink)(const char *) = NULL;
        if (!orig_unlink) orig_unlink = (int (*)(const char *))dlsym(RTLD_NEXT, "unlink");
        if (orig_unlink) orig_unlink(short_path);
    }
}

#ifndef O_TMPFILE
#define O_TMPFILE (020000000 | 00200000)
#endif

static inline int open_needs_mode(int flags) {
#ifdef O_TMPFILE
    return ((flags & O_CREAT) != 0) || ((flags & O_TMPFILE) == O_TMPFILE);
#else
    return (flags & O_CREAT) != 0;
#endif
}

// Hook dlopen and dlmopen
void *dlopen(const char *filename, int flags) {
    static void *(*orig_dlopen)(const char *, int) = NULL;
    if (!orig_dlopen) orig_dlopen = (void *(*)(const char *, int))dlsym(RTLD_NEXT, "dlopen");
    if (!filename) return orig_dlopen ? orig_dlopen(NULL, flags) : NULL;

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_dlopen ? orig_dlopen(target, flags) : NULL;
}

void *dlmopen(Lmid_t lmid, const char *filename, int flags) {
    static void *(*orig_dlmopen)(Lmid_t, const char *, int) = NULL;
    if (!orig_dlmopen) orig_dlmopen = (void *(*)(Lmid_t, const char *, int))dlsym(RTLD_NEXT, "dlmopen");
    if (!filename) return orig_dlmopen ? orig_dlmopen(lmid, NULL, flags) : NULL;

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_dlmopen ? orig_dlmopen(lmid, target, flags) : NULL;
}

// Hook open
int open(const char *pathname, int flags, ...) {
    static int (*orig_open)(const char *, int, ...) = NULL;
    if (!orig_open) orig_open = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    init_cortex_hook();
    char exe_buf[PATH_MAX];
    if (is_proc_self_exe(pathname)) {
        if (g_real_exe[0] != '\0') {
            pathname = g_real_exe;
        } else if (handle_proc_self_exe(exe_buf, sizeof(exe_buf)) > 0) {
            pathname = exe_buf;
        }
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_open(target, flags, mode);
    }
    return orig_open(target, flags);
}

// Hook openat
int openat(int dirfd, const char *pathname, int flags, ...) {
    static int (*orig_openat)(int, const char *, int, ...) = NULL;
    if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    init_cortex_hook();
    char exe_buf[PATH_MAX];
    if (is_proc_self_exe(pathname)) {
        if (g_real_exe[0] != '\0') {
            pathname = g_real_exe;
        } else if (handle_proc_self_exe(exe_buf, sizeof(exe_buf)) > 0) {
            pathname = exe_buf;
        }
    }
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_openat(dirfd, target, flags, mode);
    }
    return orig_openat(dirfd, target, flags);
}

// Hook creat
int creat(const char *pathname, mode_t mode) {
    return open(pathname, O_CREAT | O_WRONLY | O_TRUNC, mode);
}

// Fortified open variants used by GNU tar and other coreutils compiled with _FORTIFY_SOURCE=2/3
int __open_2(const char *pathname, int flags) {
    return open(pathname, flags);
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    return openat(dirfd, pathname, flags);
}

// openat2/open_how local definition: avoids depending on <linux/openat2.h>,
// which old NDK/cross headers may lack. Layout matches UAPI ver0
// (flags, mode, resolve = 3 x __u64); extra fields are never read here.
struct cortex_open_how {
    uint64_t flags;
    uint64_t mode;
    uint64_t resolve;
};
#ifndef RESOLVE_BENEATH
#define RESOLVE_BENEATH 0x08
#endif
#ifndef __NR_openat2
#define __NR_openat2 437
#endif
#ifndef __NR_fchmodat2
#define __NR_fchmodat2 452
#endif

static long call_orig_syscall(long number, unsigned long a1, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5, unsigned long a6) {
    static long (*orig_syscall)(long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long) = NULL;
    if (!orig_syscall) {
        orig_syscall = (long (*)(long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long))dlsym(RTLD_NEXT, "syscall");
    }
    return orig_syscall ? orig_syscall(number, a1, a2, a3, a4, a5, a6) : syscall(number, a1, a2, a3, a4, a5, a6);
}

// Hook openat2 (used by GNU tar 1.35+/gnulib for RESOLVE_BENEATH traversal).
// Rewrites absolute paths like openat, then delegates; on ENOSYS (kernels
// without openat2, e.g. Android < 5.6) emulates with plain openat instead of
// trusting every caller to implement the fallback itself.
int openat2(int dirfd, const char *pathname, struct cortex_open_how *how, size_t usize) {
    static int (*orig_openat2)(int, const char *, struct cortex_open_how *, size_t) = NULL;
    if (!orig_openat2) orig_openat2 = (int (*)(int, const char *, struct cortex_open_how *, size_t))dlsym(RTLD_NEXT, "openat2");
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }
    char exe_buf[PATH_MAX];
    if (is_proc_self_exe(pathname)) {
        if (g_real_exe[0] != '\0') {
            pathname = g_real_exe;
        } else if (handle_proc_self_exe(exe_buf, sizeof(exe_buf)) > 0) {
            pathname = exe_buf;
        }
    }
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (orig_openat2) {
        int ret = orig_openat2(dirfd, target, how, usize);
        if (ret >= 0 || errno != ENOSYS) return ret;
    }
#ifdef __NR_openat2
    long sret = call_orig_syscall(__NR_openat2, (unsigned long)dirfd, (unsigned long)target, (unsigned long)how, (unsigned long)usize, 0, 0);
    if (sret >= 0 || errno != ENOSYS) {
        return (int)sret;
    }
#endif
    if (!how || usize < 16) {
        errno = EINVAL;
        return -1;
    }
    int flags = (int)(how->flags & 0xffffffffu);
    mode_t mode = (mode_t)(how->mode & 07777u);

    // If resolve flags are specified on a kernel lacking openat2, ensure containment
    if (how->resolve != 0) {
        if ((how->resolve & 0x08 /* RESOLVE_BENEATH */) && target[0] == '/') {
            errno = EXDEV;
            return -1;
        }
        int depth = 0;
        const char *p = target;
        while (*p) {
            while (*p == '/') p++;
            if (!*p) break;
            const char *end = p;
            while (*end && *end != '/') end++;
            size_t clen = end - p;
            if (clen == 2 && p[0] == '.' && p[1] == '.') {
                depth--;
                if (depth < 0 && (how->resolve & 0x08 /* RESOLVE_BENEATH */)) {
                    errno = EXDEV;
                    return -1;
                }
            } else if (clen == 1 && p[0] == '.') {
                // current dir
            } else {
                depth++;
            }
            p = end;
        }
        if (how->resolve & 0x04 /* RESOLVE_NO_SYMLINKS */) {
            flags |= O_NOFOLLOW;
        }
    }

    if (open_needs_mode(flags)) {
        return openat(dirfd, target, flags, mode);
    }
    return openat(dirfd, target, flags);
}

// Hook fchmodat2 (used by GNU tar for lchmod-style symlink chmod).
// Delegates when the libc symbol exists, otherwise emulates: symlinks cannot
// be chmod'ed without kernel support, so skip them (return 0) like gnulib,
// and route everything else through fchmodat.
int fchmodat2(int dirfd, const char *pathname, mode_t mode, int flags) {
    static int (*orig_fchmodat2)(int, const char *, mode_t, int) = NULL;
    if (!orig_fchmodat2) orig_fchmodat2 = (int (*)(int, const char *, mode_t, int))dlsym(RTLD_NEXT, "fchmodat2");
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (orig_fchmodat2) {
        int ret = orig_fchmodat2(dirfd, target, mode, flags);
        if (ret == 0 || errno != ENOSYS) return ret;
    }
    if ((flags & AT_SYMLINK_NOFOLLOW) != 0) {
        struct stat st;
        if (fstatat(dirfd, target, &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode)) {
            return 0;
        }
    }
    return fchmodat(dirfd, target, mode, 0);
}


// Hook fopen
FILE *fopen(const char *pathname, const char *mode) {
    static FILE *(*orig_fopen)(const char *, const char *) = NULL;
    if (!orig_fopen) orig_fopen = (FILE *(*)(const char *, const char *))dlsym(RTLD_NEXT, "fopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_fopen(target, mode);
}

// Hook freopen
FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    static FILE *(*orig_freopen)(const char *, const char *, FILE *) = NULL;
    if (!orig_freopen) orig_freopen = (FILE *(*)(const char *, const char *, FILE *))dlsym(RTLD_NEXT, "freopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_freopen(target, mode, stream);
}

// Hook write
ssize_t write(int fd, const void *buf, size_t count) {
    static ssize_t (*orig_write)(int, const void *, size_t) = NULL;
    if (!orig_write) orig_write = (ssize_t (*)(int, const void *, size_t))dlsym(RTLD_NEXT, "write");
    return orig_write ? orig_write(fd, buf, count) : -1;
}

// Hook mkstemp
int mkstemp(char *template) {
    static int (*orig_mkstemp)(char *) = NULL;
    if (!orig_mkstemp) orig_mkstemp = (int (*)(char *))dlsym(RTLD_NEXT, "mkstemp");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkstemp ? orig_mkstemp(template) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkstemp ? orig_mkstemp(tmp_buf) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= 6 && tmp_len >= 6) {
            memcpy(template + orig_len - 6, tmp_buf + tmp_len - 6, 6);
        }
    }
    return fd;
}

// Hook mkostemp
int mkostemp(char *template, int flags) {
    static int (*orig_mkostemp)(char *, int) = NULL;
    if (!orig_mkostemp) orig_mkostemp = (int (*)(char *, int))dlsym(RTLD_NEXT, "mkostemp");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkostemp ? orig_mkostemp(template, flags) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkostemp ? orig_mkostemp(tmp_buf, flags) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= 6 && tmp_len >= 6) {
            memcpy(template + orig_len - 6, tmp_buf + tmp_len - 6, 6);
        }
    }
    return fd;
}

// Hook mkstemps
int mkstemps(char *template, int suffixlen) {
    static int (*orig_mkstemps)(char *, int) = NULL;
    if (!orig_mkstemps) orig_mkstemps = (int (*)(char *, int))dlsym(RTLD_NEXT, "mkstemps");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkstemps ? orig_mkstemps(template, suffixlen) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkstemps ? orig_mkstemps(tmp_buf, suffixlen) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= (size_t)(suffixlen + 6) && tmp_len >= (size_t)(suffixlen + 6)) {
            memcpy(template + orig_len - suffixlen - 6, tmp_buf + tmp_len - suffixlen - 6, 6);
        }
    }
    return fd;
}

// Hook mkostemps
int mkostemps(char *template, int suffixlen, int flags) {
    static int (*orig_mkostemps)(char *, int, int) = NULL;
    if (!orig_mkostemps) orig_mkostemps = (int (*)(char *, int, int))dlsym(RTLD_NEXT, "mkostemps");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkostemps ? orig_mkostemps(template, suffixlen, flags) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkostemps ? orig_mkostemps(tmp_buf, suffixlen, flags) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= (size_t)(suffixlen + 6) && tmp_len >= (size_t)(suffixlen + 6)) {
            memcpy(template + orig_len - suffixlen - 6, tmp_buf + tmp_len - suffixlen - 6, 6);
        }
    }
    return fd;
}

// Hook mkdtemp
char *mkdtemp(char *template) {
    static char *(*orig_mkdtemp)(char *) = NULL;
    if (!orig_mkdtemp) orig_mkdtemp = (char *(*)(char *))dlsym(RTLD_NEXT, "mkdtemp");
    if (!template) { errno = EINVAL; return NULL; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkdtemp ? orig_mkdtemp(template) : NULL;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    char *res = orig_mkdtemp ? orig_mkdtemp(tmp_buf) : NULL;
    if (res) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= 6 && tmp_len >= 6) {
            memcpy(template + orig_len - 6, tmp_buf + tmp_len - 6, 6);
        }
        return template;
    }
    return NULL;
}

// Hook tmpfile
FILE *tmpfile(void) {
    init_cortex_hook();
    char template_buf[PATH_MAX];
    if (g_cortex_root[0] != '\0') {
        snprintf(template_buf, sizeof(template_buf), "%s/tmp/tmpfile.XXXXXX", g_cortex_root);
    } else {
        snprintf(template_buf, sizeof(template_buf), "/tmp/tmpfile.XXXXXX");
    }
    int fd = mkstemp(template_buf);
    if (fd < 0) return NULL;
    unlink(template_buf);
    return fdopen(fd, "w+b");
}

// Hook opendir
DIR *opendir(const char *name) {
    static DIR *(*orig_opendir)(const char *) = NULL;
    if (!orig_opendir) orig_opendir = (DIR *(*)(const char *))dlsym(RTLD_NEXT, "opendir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(name, buf, sizeof(buf));
    return orig_opendir(target);
}

// Helper for path normalization
static void normalize_path(char *path) {
    if (!path || !*path) return;
    int is_abs = (path[0] == '/');
    char *stack[128];
    int top = 0;

    char tmp[4096];
    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';

    char *saveptr = NULL;
    char *tok = strtok_r(tmp, "/", &saveptr);
    while (tok) {
        if (strcmp(tok, ".") == 0) {
            // ignore
        } else if (strcmp(tok, "..") == 0) {
            if (top > 0) top--;
        } else {
            if (top < 128) stack[top++] = tok;
        }
        tok = strtok_r(NULL, "/", &saveptr);
    }

    char *dst = path;
    if (is_abs) *dst++ = '/';
    for (int i = 0; i < top; i++) {
        size_t len = strlen(stack[i]);
        memcpy(dst, stack[i], len);
        dst += len;
        if (i + 1 < top) *dst++ = '/';
    }
    if (dst == path && is_abs) *dst++ = '/';
    *dst = '\0';
}

static ssize_t postprocess_readlink(const char *linkpath, char *buf, ssize_t ret, size_t bufsiz) {
    if (ret <= 0) return ret;

    // 1. Strip g_cortex_root if target starts with it
    if (g_cortex_root[0] != '\0') {
        size_t rlen = strlen(g_cortex_root);
        if ((size_t)ret >= rlen && memcmp(buf, g_cortex_root, rlen) == 0 &&
            ((size_t)ret == rlen || buf[rlen] == '/')) {
            size_t new_len = ret - rlen;
            if (new_len == 0) {
                buf[0] = '/';
                return 1;
            } else {
                memmove(buf, buf + rlen, new_len);
                return (ssize_t)new_len;
            }
        }
    }

    // 2. Normalize relative alternatives links
    if (linkpath && linkpath[0] == '/' && (size_t)ret < PATH_MAX) {
        char link_copy[PATH_MAX];
        memcpy(link_copy, buf, ret);
        link_copy[ret] = '\0';

        if (strncmp(link_copy, "..", 2) == 0) {
            char parent[PATH_MAX];
            snprintf(parent, sizeof(parent), "%s", linkpath);
            char *last_slash = strrchr(parent, '/');
            if (last_slash) {
                if (last_slash == parent) {
                    *(last_slash + 1) = '\0';
                } else {
                    *last_slash = '\0';
                }
            }
            char combined[PATH_MAX * 2];
            snprintf(combined, sizeof(combined), "%s/%s", parent, link_copy);
            normalize_path(combined);

            if (strncmp(combined, "/etc/alternatives", 17) == 0 ||
                strncmp(linkpath, "/etc/alternatives", 17) == 0) {
                size_t clen = strlen(combined);
                if (clen <= bufsiz) {
                    memcpy(buf, combined, clen);
                    return (ssize_t)clen;
                }
            }
        }
    }

    return ret;
}

// Hook stat
int stat(const char *pathname, struct stat *statbuf) {
    static int (*orig_stat)(const char *, struct stat *) = NULL;
    if (!orig_stat) orig_stat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "stat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_stat ? orig_stat(target, statbuf) : -1;
    if (ret != 0 && errno == ENOENT) {
        const char *sub = get_rootfs_subpath(target);
        if (sub) {
            char alt[PATH_MAX];
            if (strncmp(sub, "/usr/bin/", 9) == 0) {
                snprintf(alt, sizeof(alt), "%s/bin/%s", g_cortex_root, sub + 9);
                ret = orig_stat(alt, statbuf);
            } else if (strncmp(sub, "/bin/", 5) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/bin/%s", g_cortex_root, sub + 5);
                ret = orig_stat(alt, statbuf);
            } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
                snprintf(alt, sizeof(alt), "%s/sbin/%s", g_cortex_root, sub + 10);
                ret = orig_stat(alt, statbuf);
            } else if (strncmp(sub, "/sbin/", 6) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/sbin/%s", g_cortex_root, sub + 6);
                ret = orig_stat(alt, statbuf);
            }
        }
    }
    return ret;
}

// Hook lstat
int lstat(const char *pathname, struct stat *statbuf) {
    static int (*orig_lstat)(const char *, struct stat *) = NULL;
    if (!orig_lstat) orig_lstat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "lstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_lstat ? orig_lstat(target, statbuf) : -1;
    if (ret != 0 && errno == ENOENT) {
        const char *sub = get_rootfs_subpath(target);
        if (sub) {
            char alt[PATH_MAX];
            if (strncmp(sub, "/usr/bin/", 9) == 0) {
                snprintf(alt, sizeof(alt), "%s/bin/%s", g_cortex_root, sub + 9);
                ret = orig_lstat(alt, statbuf);
                if (ret == 0) target = alt;
            } else if (strncmp(sub, "/bin/", 5) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/bin/%s", g_cortex_root, sub + 5);
                ret = orig_lstat(alt, statbuf);
                if (ret == 0) target = alt;
            } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
                snprintf(alt, sizeof(alt), "%s/sbin/%s", g_cortex_root, sub + 10);
                ret = orig_lstat(alt, statbuf);
                if (ret == 0) target = alt;
            } else if (strncmp(sub, "/sbin/", 6) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/sbin/%s", g_cortex_root, sub + 6);
                ret = orig_lstat(alt, statbuf);
                if (ret == 0) target = alt;
            }
        }
    }
    if (ret == 0 && statbuf && S_ISLNK(statbuf->st_mode) && g_cortex_root[0] != '\0' && target) {
        char link_buf[PATH_MAX];
        static ssize_t (*orig_readlink)(const char *, char *, size_t) = NULL;
        if (!orig_readlink) orig_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
        ssize_t llen = orig_readlink ? orig_readlink(target, link_buf, sizeof(link_buf) - 1) : -1;
        if (llen > 0) {
            ssize_t post_len = postprocess_readlink(pathname, link_buf, llen, sizeof(link_buf));
            if (post_len > 0) {
                statbuf->st_size = post_len;
            }
        }
    }
    return ret;
}

// Hook fstatat
int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    int ret = orig_fstatat ? orig_fstatat(dirfd, target, statbuf, flags) : -1;
    if (ret != 0 && errno == ENOENT && pathname && pathname[0] == '/') {
        const char *sub = get_rootfs_subpath(target);
        if (sub) {
            char alt[PATH_MAX];
            if (strncmp(sub, "/usr/bin/", 9) == 0) {
                snprintf(alt, sizeof(alt), "%s/bin/%s", g_cortex_root, sub + 9);
                ret = orig_fstatat(dirfd, alt, statbuf, flags);
                if (ret == 0) target = alt;
            } else if (strncmp(sub, "/bin/", 5) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/bin/%s", g_cortex_root, sub + 5);
                ret = orig_fstatat(dirfd, alt, statbuf, flags);
                if (ret == 0) target = alt;
            } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
                snprintf(alt, sizeof(alt), "%s/sbin/%s", g_cortex_root, sub + 10);
                ret = orig_fstatat(dirfd, alt, statbuf, flags);
                if (ret == 0) target = alt;
            } else if (strncmp(sub, "/sbin/", 6) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/sbin/%s", g_cortex_root, sub + 6);
                ret = orig_fstatat(dirfd, alt, statbuf, flags);
                if (ret == 0) target = alt;
            }
        }
    }
    if (ret == 0 && statbuf && (flags & AT_SYMLINK_NOFOLLOW) && S_ISLNK(statbuf->st_mode) && g_cortex_root[0] != '\0' && target) {
        char link_buf[PATH_MAX];
        static ssize_t (*orig_readlinkat)(int, const char *, char *, size_t) = NULL;
        if (!orig_readlinkat) orig_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
        ssize_t llen = orig_readlinkat ? orig_readlinkat(dirfd, target, link_buf, sizeof(link_buf) - 1) : -1;
        if (llen > 0) {
            ssize_t post_len = postprocess_readlink(pathname, link_buf, llen, sizeof(link_buf));
            if (post_len > 0) {
                statbuf->st_size = post_len;
            }
        }
    }
    return ret;
}

// Glibc __xstat compatibility hooks
int __xstat(int ver, const char *pathname, struct stat *statbuf) {
    static int (*orig___xstat)(int, const char *, struct stat *) = NULL;
    if (!orig___xstat) orig___xstat = (int (*)(int, const char *, struct stat *))dlsym(RTLD_NEXT, "__xstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___xstat ? orig___xstat(ver, target, statbuf) : stat(target, statbuf);
}

int __xstat64(int ver, const char *pathname, struct stat64 *statbuf) {
    static int (*orig___xstat64)(int, const char *, struct stat64 *) = NULL;
    if (!orig___xstat64) orig___xstat64 = (int (*)(int, const char *, struct stat64 *))dlsym(RTLD_NEXT, "__xstat64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___xstat64 ? orig___xstat64(ver, target, statbuf) : stat(target, (struct stat *)statbuf);
}

int __lxstat(int ver, const char *pathname, struct stat *statbuf) {
    static int (*orig___lxstat)(int, const char *, struct stat *) = NULL;
    if (!orig___lxstat) orig___lxstat = (int (*)(int, const char *, struct stat *))dlsym(RTLD_NEXT, "__lxstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___lxstat ? orig___lxstat(ver, target, statbuf) : lstat(target, statbuf);
}

int __lxstat64(int ver, const char *pathname, struct stat64 *statbuf) {
    static int (*orig___lxstat64)(int, const char *, struct stat64 *) = NULL;
    if (!orig___lxstat64) orig___lxstat64 = (int (*)(int, const char *, struct stat64 *))dlsym(RTLD_NEXT, "__lxstat64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___lxstat64 ? orig___lxstat64(ver, target, statbuf) : lstat(target, (struct stat *)statbuf);
}

int __fxstatat(int ver, int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static int (*orig___fxstatat)(int, int, const char *, struct stat *, int) = NULL;
    if (!orig___fxstatat) orig___fxstatat = (int (*)(int, int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "__fxstatat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig___fxstatat ? orig___fxstatat(ver, dirfd, target, statbuf, flags) : fstatat(dirfd, target, statbuf, flags);
}

int __fxstatat64(int ver, int dirfd, const char *pathname, struct stat64 *statbuf, int flags) {
    static int (*orig___fxstatat64)(int, int, const char *, struct stat64 *, int) = NULL;
    if (!orig___fxstatat64) orig___fxstatat64 = (int (*)(int, int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "__fxstatat64");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig___fxstatat64 ? orig___fxstatat64(ver, dirfd, target, statbuf, flags) : fstatat(dirfd, target, (struct stat *)statbuf, flags);
}

// Hook statx
struct statx;
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *statxbuf) {
    static int (*orig_statx)(int, const char *, int, unsigned int, struct statx *) = NULL;
    if (!orig_statx) orig_statx = (int (*)(int, const char *, int, unsigned int, struct statx *))dlsym(RTLD_NEXT, "statx");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_statx ? orig_statx(dirfd, target, flags, mask, statxbuf) : -1;
}

// Hook faccessat
int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    init_cortex_hook();
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }

    struct stat st;
    int stat_flags = (flags & AT_SYMLINK_NOFOLLOW) ? AT_SYMLINK_NOFOLLOW : 0;
    if (fstatat(dirfd, pathname, &st, stat_flags) != 0) {
        return -1;
    }

    if (mode == F_OK) {
        return 0;
    }

    if (mode & X_OK) {
        if (S_ISDIR(st.st_mode)) return 0;
        if ((st.st_mode & 0111) != 0) return 0;
        if (strstr(pathname, "/bin/") || strstr(pathname, "/sbin/")) return 0;
        errno = EACCES;
        return -1;
    }

    return 0;
}

int faccessat2(int dirfd, const char *pathname, int mode, int flags) {
    return faccessat(dirfd, pathname, mode, flags);
}

// Hook access
int access(const char *pathname, int mode) {
    return faccessat(AT_FDCWD, pathname, mode, 0);
}

// Hook euidaccess and eaccess
int euidaccess(const char *pathname, int mode) {
    return faccessat(AT_FDCWD, pathname, mode, AT_EACCESS);
}

int eaccess(const char *pathname, int mode) {
    return faccessat(AT_FDCWD, pathname, mode, AT_EACCESS);
}

// Hook chmod
int chmod(const char *pathname, mode_t mode) {
    static int (*orig_chmod)(const char *, mode_t) = NULL;
    if (!orig_chmod) orig_chmod = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "chmod");
    if (!orig_chmod) return -1;
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_chmod(target, mode);
    if (ret != 0 && ((mode & 06000) != 0 || errno == EPERM || errno == EACCES || errno == ENOSYS)) {
        ret = orig_chmod(target, mode & 01777);
    }
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        errno = 0;
        return 0;
    }
    return ret;
}

// Hook fchmod
int fchmod(int fd, mode_t mode) {
    static int (*orig_fchmod)(int, mode_t) = NULL;
    if (!orig_fchmod) orig_fchmod = (int (*)(int, mode_t))dlsym(RTLD_NEXT, "fchmod");
    if (!orig_fchmod) return -1;
    int ret = orig_fchmod(fd, mode);
    if (ret != 0 && ((mode & 06000) != 0 || errno == EPERM || errno == EACCES || errno == ENOSYS)) {
        ret = orig_fchmod(fd, mode & 01777);
    }
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        errno = 0;
        return 0;
    }
    return ret;
}

// Hook fchmodat
int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    static int (*orig_fchmodat)(int, const char *, mode_t, int) = NULL;
    if (!orig_fchmodat) orig_fchmodat = (int (*)(int, const char *, mode_t, int))dlsym(RTLD_NEXT, "fchmodat");
    if (!orig_fchmodat) return -1;
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    int ret = orig_fchmodat(dirfd, target, mode, flags);
    if (ret != 0 && ((mode & 06000) != 0 || errno == EPERM || errno == EACCES || errno == ENOSYS)) {
        ret = orig_fchmodat(dirfd, target, mode & 01777, flags);
    }
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        errno = 0;
        return 0;
    }
    return ret;
}

// Hook unlink
int unlink(const char *pathname) {
    static int (*orig_unlink)(const char *) = NULL;
    if (!orig_unlink) orig_unlink = (int (*)(const char *))dlsym(RTLD_NEXT, "unlink");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    unlink_mapped_unix_socket(target);
    return orig_unlink(target);
}

// Hook unlinkat
int unlinkat(int dirfd, const char *pathname, int flags) {
    static int (*orig_unlinkat)(int, const char *, int) = NULL;
    if (!orig_unlinkat) orig_unlinkat = (int (*)(int, const char *, int))dlsym(RTLD_NEXT, "unlinkat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (target) unlink_mapped_unix_socket(target);
    return orig_unlinkat ? orig_unlinkat(dirfd, target, flags) : -1;
}

// Hook rmdir
int rmdir(const char *pathname) {
    static int (*orig_rmdir)(const char *) = NULL;
    if (!orig_rmdir) orig_rmdir = (int (*)(const char *))dlsym(RTLD_NEXT, "rmdir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_rmdir(target);
}

// Hook mkdir
int mkdir(const char *pathname, mode_t mode) {
    static int (*orig_mkdir)(const char *, mode_t) = NULL;
    if (!orig_mkdir) orig_mkdir = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "mkdir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_mkdir(target, mode);
}

// Hook mkdirat
int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    static int (*orig_mkdirat)(int, const char *, mode_t) = NULL;
    if (!orig_mkdirat) orig_mkdirat = (int (*)(int, const char *, mode_t))dlsym(RTLD_NEXT, "mkdirat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_mkdirat(dirfd, target, mode);
}

static void ensure_dir_exists(const char *path) {
    if (!path || path[0] == '\0') return;
    char tmp[PATH_MAX];
    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    mkdir(tmp, 0755);
}

// Hook inotify_add_watch
int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
    static int (*orig_inotify_add_watch)(int, const char *, uint32_t) = NULL;
    if (!orig_inotify_add_watch) orig_inotify_add_watch = (int (*)(int, const char *, uint32_t))dlsym(RTLD_NEXT, "inotify_add_watch");
    if (!pathname) return -1;
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    if (target && access(target, F_OK) != 0) {
        ensure_dir_exists(target);
    }
    return orig_inotify_add_watch ? orig_inotify_add_watch(fd, target, mask) : -1;
}

// Hook rename
int rename(const char *oldpath, const char *newpath) {
    static int (*orig_rename)(const char *, const char *) = NULL;
    if (!orig_rename) orig_rename = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "rename");
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = rewrite_path(oldpath, buf1, sizeof(buf1));
    const char *target_new = rewrite_path(newpath, buf2, sizeof(buf2));
    return orig_rename(target_old, target_new);
}

// Hook renameat
int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    static int (*orig_renameat)(int, const char *, int, const char *) = NULL;
    if (!orig_renameat) orig_renameat = (int (*)(int, const char *, int, const char *))dlsym(RTLD_NEXT, "renameat");
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = (oldpath && oldpath[0] == '/') ? rewrite_path(oldpath, buf1, sizeof(buf1)) : oldpath;
    const char *target_new = (newpath && newpath[0] == '/') ? rewrite_path(newpath, buf2, sizeof(buf2)) : newpath;
    return orig_renameat(olddirfd, target_old, newdirfd, target_new);
}

// Hook renameat2
int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags) {
    static int (*orig_renameat2)(int, const char *, int, const char *, unsigned int) = NULL;
    if (!orig_renameat2) {
        orig_renameat2 = (int (*)(int, const char *, int, const char *, unsigned int))dlsym(RTLD_NEXT, "renameat2");
        if (!orig_renameat2) return renameat(olddirfd, oldpath, newdirfd, newpath);
    }
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = (oldpath && oldpath[0] == '/') ? rewrite_path(oldpath, buf1, sizeof(buf1)) : oldpath;
    const char *target_new = (newpath && newpath[0] == '/') ? rewrite_path(newpath, buf2, sizeof(buf2)) : newpath;
    return orig_renameat2(olddirfd, target_old, newdirfd, target_new, flags);
}

// Hook readlink
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    if (is_proc_self_exe(pathname)) {
        ssize_t exe_len = handle_proc_self_exe(buf, bufsiz);
        if (exe_len >= 0) return exe_len;
    }
    static ssize_t (*orig_readlink)(const char *, char *, size_t) = NULL;
    if (!orig_readlink) orig_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(pathname, pbuf, sizeof(pbuf));
    ssize_t ret = orig_readlink ? orig_readlink(target, buf, bufsiz) : -1;
    if (ret < 0 && errno == ENOENT) {
        const char *sub = get_rootfs_subpath(target);
        if (sub) {
            char alt[PATH_MAX];
            if (strncmp(sub, "/usr/bin/", 9) == 0) {
                snprintf(alt, sizeof(alt), "%s/bin/%s", g_cortex_root, sub + 9);
                ret = orig_readlink(alt, buf, bufsiz);
            } else if (strncmp(sub, "/bin/", 5) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/bin/%s", g_cortex_root, sub + 5);
                ret = orig_readlink(alt, buf, bufsiz);
            } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
                snprintf(alt, sizeof(alt), "%s/sbin/%s", g_cortex_root, sub + 10);
                ret = orig_readlink(alt, buf, bufsiz);
            } else if (strncmp(sub, "/sbin/", 6) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/sbin/%s", g_cortex_root, sub + 6);
                ret = orig_readlink(alt, buf, bufsiz);
            }
        }
    }
    return postprocess_readlink(pathname, buf, ret, bufsiz);
}

// Hook readlinkat
ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    if (is_proc_self_exe(pathname)) {
        ssize_t exe_len = handle_proc_self_exe(buf, bufsiz);
        if (exe_len >= 0) return exe_len;
    }
    static ssize_t (*orig_readlinkat)(int, const char *, char *, size_t) = NULL;
    if (!orig_readlinkat) orig_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
    char pbuf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, pbuf, sizeof(pbuf)) : pathname;
    ssize_t ret = orig_readlinkat ? orig_readlinkat(dirfd, target, buf, bufsiz) : -1;
    if (ret < 0 && errno == ENOENT && pathname && pathname[0] == '/') {
        const char *sub = get_rootfs_subpath(target);
        if (sub) {
            char alt[PATH_MAX];
            if (strncmp(sub, "/usr/bin/", 9) == 0) {
                snprintf(alt, sizeof(alt), "%s/bin/%s", g_cortex_root, sub + 9);
                ret = orig_readlinkat(dirfd, alt, buf, bufsiz);
            } else if (strncmp(sub, "/bin/", 5) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/bin/%s", g_cortex_root, sub + 5);
                ret = orig_readlinkat(dirfd, alt, buf, bufsiz);
            } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
                snprintf(alt, sizeof(alt), "%s/sbin/%s", g_cortex_root, sub + 10);
                ret = orig_readlinkat(dirfd, alt, buf, bufsiz);
            } else if (strncmp(sub, "/sbin/", 6) == 0) {
                snprintf(alt, sizeof(alt), "%s/usr/sbin/%s", g_cortex_root, sub + 6);
                ret = orig_readlinkat(dirfd, alt, buf, bufsiz);
            }
        }
    }
    return postprocess_readlink(pathname, buf, ret, bufsiz);
}

// Fortified readlink variants used by dpkg / update-alternatives
ssize_t __readlink_chk(const char *pathname, char *buf, size_t bufsiz, size_t buflen) {
    return readlink(pathname, buf, bufsiz);
}

ssize_t __readlinkat_chk(int dirfd, const char *pathname, char *buf, size_t bufsiz, size_t buflen) {
    return readlinkat(dirfd, pathname, buf, bufsiz);
}

// Hook symlink
int symlink(const char *target, const char *linkpath) {
    static int (*orig_symlink)(const char *, const char *) = NULL;
    if (!orig_symlink) orig_symlink = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "symlink");
    char pbuf[PATH_MAX], tbuf[PATH_MAX];
    const char *newlink = rewrite_path(linkpath, pbuf, sizeof(pbuf));
    const char *newtarget = (target && target[0] == '/') ? rewrite_path(target, tbuf, sizeof(tbuf)) : target;
    return orig_symlink ? orig_symlink(newtarget, newlink) : -1;
}

// Hook symlinkat
int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    static int (*orig_symlinkat)(const char *, int, const char *) = NULL;
    if (!orig_symlinkat) orig_symlinkat = (int (*)(const char *, int, const char *))dlsym(RTLD_NEXT, "symlinkat");
    char pbuf[PATH_MAX], tbuf[PATH_MAX];
    const char *newlink = (linkpath && linkpath[0] == '/') ? rewrite_path(linkpath, pbuf, sizeof(pbuf)) : linkpath;
    const char *newtarget = (target && target[0] == '/') ? rewrite_path(target, tbuf, sizeof(tbuf)) : target;
    return orig_symlinkat ? orig_symlinkat(newtarget, newdirfd, newlink) : -1;
}

// Hook link
int link(const char *oldpath, const char *newpath) {
    static int (*orig_link)(const char *, const char *) = NULL;
    if (!orig_link) orig_link = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "link");
    char obuf[PATH_MAX], nbuf[PATH_MAX];
    const char *rold = rewrite_path(oldpath, obuf, sizeof(obuf));
    const char *rnew = rewrite_path(newpath, nbuf, sizeof(nbuf));
    int ret = orig_link ? orig_link(rold, rnew) : -1;
    if (ret != 0 && (errno == EXDEV || errno == EPERM || errno == EACCES || errno == ENOTSUP || errno == ENOSYS)) {
        static int (*orig_symlink)(const char *, const char *) = NULL;
        if (!orig_symlink) orig_symlink = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "symlink");
        if (orig_symlink) {
            ret = orig_symlink(rold, rnew);
        }
    }
    return ret;
}

// Hook linkat
int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    static int (*orig_linkat)(int, const char *, int, const char *, int) = NULL;
    if (!orig_linkat) orig_linkat = (int (*)(int, const char *, int, const char *, int))dlsym(RTLD_NEXT, "linkat");
    char obuf[PATH_MAX], nbuf[PATH_MAX];
    const char *rold = (oldpath && oldpath[0] == '/') ? rewrite_path(oldpath, obuf, sizeof(obuf)) : oldpath;
    const char *rnew = (newpath && newpath[0] == '/') ? rewrite_path(newpath, nbuf, sizeof(nbuf)) : newpath;
    int ret = orig_linkat ? orig_linkat(olddirfd, rold, newdirfd, rnew, flags) : -1;
    if (ret != 0 && (errno == EXDEV || errno == EPERM || errno == EACCES || errno == ENOTSUP || errno == ENOSYS)) {
        static int (*orig_symlinkat)(const char *, int, const char *) = NULL;
        if (!orig_symlinkat) orig_symlinkat = (int (*)(const char *, int, const char *))dlsym(RTLD_NEXT, "symlinkat");
        if (orig_symlinkat) {
            ret = orig_symlinkat(rold, newdirfd, rnew);
        }
    }
    return ret;
}

// Hook utime / utimes / lutimes / futimesat / utimensat
int utime(const char *filename, const struct utimbuf *times) {
    static int (*orig_utime)(const char *, const struct utimbuf *) = NULL;
    if (!orig_utime) orig_utime = (int (*)(const char *, const struct utimbuf *))dlsym(RTLD_NEXT, "utime");
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_utime ? orig_utime(target, times) : -1;
}

int utimes(const char *filename, const struct timeval times[2]) {
    static int (*orig_utimes)(const char *, const struct timeval[2]) = NULL;
    if (!orig_utimes) orig_utimes = (int (*)(const char *, const struct timeval[2]))dlsym(RTLD_NEXT, "utimes");
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_utimes ? orig_utimes(target, times) : -1;
}

int lutimes(const char *filename, const struct timeval times[2]) {
    static int (*orig_lutimes)(const char *, const struct timeval[2]) = NULL;
    if (!orig_lutimes) orig_lutimes = (int (*)(const char *, const struct timeval[2]))dlsym(RTLD_NEXT, "lutimes");
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_lutimes ? orig_lutimes(target, times) : -1;
}

int futimesat(int dirfd, const char *pathname, const struct timeval times[2]) {
    static int (*orig_futimesat)(int, const char *, const struct timeval[2]) = NULL;
    if (!orig_futimesat) orig_futimesat = (int (*)(int, const char *, const struct timeval[2]))dlsym(RTLD_NEXT, "futimesat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_futimesat ? orig_futimesat(dirfd, target, times) : -1;
}

int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) {
    static int (*orig_utimensat)(int, const char *, const struct timespec[2], int) = NULL;
    if (!orig_utimensat) orig_utimensat = (int (*)(int, const char *, const struct timespec[2], int))dlsym(RTLD_NEXT, "utimensat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_utimensat ? orig_utimensat(dirfd, target, times, flags) : -1;
}

// Hook truncate
int truncate(const char *path, off_t length) {
    static int (*orig_truncate)(const char *, off_t) = NULL;
    if (!orig_truncate) orig_truncate = (int (*)(const char *, off_t))dlsym(RTLD_NEXT, "truncate");
    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));
    return orig_truncate(target, length);
}

// Hook statfs / statfs64 / statvfs / statvfs64
int statfs(const char *path, struct statfs *buf) {
    static int (*orig_statfs)(const char *, struct statfs *) = NULL;
    if (!orig_statfs) orig_statfs = (int (*)(const char *, struct statfs *))dlsym(RTLD_NEXT, "statfs");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statfs ? orig_statfs(target, buf) : -1;
}

int statvfs(const char *path, struct statvfs *buf) {
    static int (*orig_statvfs)(const char *, struct statvfs *) = NULL;
    if (!orig_statvfs) orig_statvfs = (int (*)(const char *, struct statvfs *))dlsym(RTLD_NEXT, "statvfs");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statvfs ? orig_statvfs(target, buf) : -1;
}

// Hook chdir
int chdir(const char *path) {
    static int (*orig_chdir)(const char *) = NULL;
    if (!orig_chdir) orig_chdir = (int (*)(const char *))dlsym(RTLD_NEXT, "chdir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));
    return orig_chdir ? orig_chdir(target) : -1;
}

// Hook fchdir
int fchdir(int fd) {
    static int (*orig_fchdir)(int) = NULL;
    if (!orig_fchdir) orig_fchdir = (int (*)(int))dlsym(RTLD_NEXT, "fchdir");
    return orig_fchdir ? orig_fchdir(fd) : -1;
}

// Hook getcwd
char *getcwd(char *buf, size_t size) {
    static char *(*orig_getcwd)(char *, size_t) = NULL;
    if (!orig_getcwd) orig_getcwd = (char *(*)(char *, size_t))dlsym(RTLD_NEXT, "getcwd");
    return orig_getcwd ? orig_getcwd(buf, size) : NULL;
}

char *__getcwd_chk(char *buf, size_t size, size_t buflen) {
    return getcwd(buf, size);
}

// Hook realpath and canonicalize_file_name
char *realpath(const char *path, char *resolved_path) {
    static char *(*orig_realpath)(const char *, char *) = NULL;
    if (!orig_realpath) orig_realpath = (char *(*)(const char *, char *))dlsym(RTLD_NEXT, "realpath");
    if (!path) {
        errno = EINVAL;
        return NULL;
    }
    init_cortex_hook();
    if (is_proc_self_exe(path)) {
        char tmp[PATH_MAX];
        ssize_t n = handle_proc_self_exe(tmp, sizeof(tmp) - 1);
        if (n > 0) {
            tmp[n] = '\0';
            if (resolved_path) {
                strncpy(resolved_path, tmp, PATH_MAX - 1);
                resolved_path[PATH_MAX - 1] = '\0';
                return resolved_path;
            } else {
                return strdup(tmp);
            }
        }
    }
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    char *res = orig_realpath ? orig_realpath(target, resolved_path) : NULL;
    if (res && g_cortex_root[0] != '\0') {
        size_t rlen = strlen(g_cortex_root);
        if (strncmp(res, g_cortex_root, rlen) == 0 && (res[rlen] == '/' || res[rlen] == '\0')) {
            size_t rem_len = strlen(res + rlen);
            if (rem_len == 0) {
                res[0] = '/';
                res[1] = '\0';
            } else {
                memmove(res, res + rlen, rem_len + 1);
            }
        }
    }
    return res;
}

char *__realpath_chk(const char *path, char *resolved_path, size_t resolved_len) {
    return realpath(path, resolved_path);
}

char *canonicalize_file_name(const char *path) {
    return realpath(path, NULL);
}

// Hook scandir and scandir64
int scandir(const char *dirp, struct dirent ***namelist,
            int (*filter)(const struct dirent *),
            int (*compar)(const struct dirent **, const struct dirent **)) {
    static int (*orig_scandir)(const char *, struct dirent ***,
                               int (*)(const struct dirent *),
                               int (*)(const struct dirent **, const struct dirent **)) = NULL;
    if (!orig_scandir) orig_scandir = (int (*)(const char *, struct dirent ***,
                               int (*)(const struct dirent *),
                               int (*)(const struct dirent **, const struct dirent **)))dlsym(RTLD_NEXT, "scandir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(dirp, buf, sizeof(buf));
    return orig_scandir ? orig_scandir(target, namelist, filter, compar) : -1;
}

#if defined(__LP64__)
// Export 64-bit Large File Support (LFS) symbol aliases on 64-bit platforms so that
// calls from libraries compiled with LFS (such as libstdc++ calling fopen64) are intercepted.
__asm__(
    ".globl open64\n"        ".set open64, open\n"
    ".globl openat64\n"      ".set openat64, openat\n"
    ".globl __open64_2\n"    ".set __open64_2, __open_2\n"
    ".globl __openat64_2\n"  ".set __openat64_2, __openat_2\n"
    ".globl creat64\n"       ".set creat64, creat\n"
    ".globl fopen64\n"     ".set fopen64, fopen\n"
    ".globl freopen64\n"   ".set freopen64, freopen\n"
    ".globl stat64\n"      ".set stat64, stat\n"
    ".globl lstat64\n"     ".set lstat64, lstat\n"
    ".globl fstatat64\n"   ".set fstatat64, fstatat\n"
    ".globl truncate64\n"  ".set truncate64, truncate\n"
    ".globl statfs64\n"    ".set statfs64, statfs\n"
    ".globl statvfs64\n"   ".set statvfs64, statvfs\n"
    ".globl scandir64\n"   ".set scandir64, scandir\n"
    ".globl mkstemp64\n"   ".set mkstemp64, mkstemp\n"
    ".globl mkostemp64\n"  ".set mkostemp64, mkostemp\n"
    ".globl mkstemps64\n"  ".set mkstemps64, mkstemps\n"
    ".globl mkostemps64\n" ".set mkostemps64, mkostemps\n"
    ".globl tmpfile64\n"   ".set tmpfile64, tmpfile\n"
);
#endif

// Fakeroot identity hooks for APT and DPKG to operate without superuser restrictions
uid_t getuid(void) { return 0; }
uid_t geteuid(void) { return 0; }
gid_t getgid(void) { return 0; }
gid_t getegid(void) { return 0; }

int setuid(uid_t uid) { (void)uid; return 0; }
int seteuid(uid_t uid) { (void)uid; return 0; }
int setgid(gid_t gid) { (void)gid; return 0; }
int setegid(gid_t gid) { (void)gid; return 0; }
int setreuid(uid_t ruid, uid_t euid) { (void)ruid; (void)euid; return 0; }
int setregid(gid_t rgid, gid_t egid) { (void)rgid; (void)egid; return 0; }
int setresuid(uid_t ruid, uid_t euid, uid_t suid) { (void)ruid; (void)euid; (void)suid; return 0; }
int setresgid(gid_t rgid, gid_t egid, gid_t sgid) { (void)rgid; (void)egid; (void)sgid; return 0; }

int getgroups(int size, gid_t list[]) {
    if (size > 0 && list != NULL) list[0] = 0;
    return 1;
}
int setgroups(size_t size, const gid_t *list) { (void)size; (void)list; return 0; }
int initgroups(const char *user, gid_t group) { (void)user; (void)group; return 0; }

int getgrouplist(const char *user, gid_t group, gid_t *groups, int *ngroups) {
    (void)user;
    if (ngroups && *ngroups > 0 && groups) {
        groups[0] = group;
        *ngroups = 1;
        return 1;
    }
    if (ngroups) *ngroups = 1;
    return 1;
}

#endif /* !CORTEX_HOST_TEST */

static uid_t real_uid(void) {
#ifdef CORTEX_HOST_TEST
    return 10000;
#else
    static uid_t (*orig_getuid)(void) = NULL;
    if (!orig_getuid) orig_getuid = (uid_t (*)(void))dlsym(RTLD_NEXT, "getuid");
    return orig_getuid ? orig_getuid() : 0;
#endif
}

static gid_t real_gid(void) {
#ifdef CORTEX_HOST_TEST
    return 10000;
#else
    static gid_t (*orig_getgid)(void) = NULL;
    if (!orig_getgid) orig_getgid = (gid_t (*)(void))dlsym(RTLD_NEXT, "getgid");
    return orig_getgid ? orig_getgid() : 0;
#endif
}

#ifdef CORTEX_HOST_TEST
#define CORTEX_NSS_SYM(name) hook_##name
static int g_test_orig_nss_r_ret = 0;
static int g_test_orig_nss_r_found = 0;
#else
#define CORTEX_NSS_SYM(name) name
#endif

struct group *CORTEX_NSS_SYM(getgrgid)(gid_t gid) {
#ifndef CORTEX_HOST_TEST
    static struct group *(*orig_getgrgid)(gid_t) = NULL;
    if (!orig_getgrgid) orig_getgrgid = (struct group *(*)(gid_t))dlsym(RTLD_NEXT, "getgrgid");
    struct group *res = orig_getgrgid ? orig_getgrgid(gid) : NULL;
    if (res) return res;
#endif

    if (gid != 0 && gid != real_gid()) {
        return NULL;
    }

    static struct group s_grp;
    static char *s_mem[] = {"root", NULL};
    s_grp.gr_name = "root";
    s_grp.gr_passwd = "x";
    s_grp.gr_gid = gid;
    s_grp.gr_mem = s_mem;
    return &s_grp;
}

struct group *CORTEX_NSS_SYM(getgrnam)(const char *name) {
#ifndef CORTEX_HOST_TEST
    static struct group *(*orig_getgrnam)(const char *) = NULL;
    if (!orig_getgrnam) orig_getgrnam = (struct group *(*)(const char *))dlsym(RTLD_NEXT, "getgrnam");
    struct group *res = orig_getgrnam ? orig_getgrnam(name) : NULL;
    if (res) return res;
#endif

    if (!name || strcmp(name, "root") != 0) {
        return NULL;
    }

    static struct group s_grp;
    static char *s_mem[] = {"root", NULL};
    s_grp.gr_name = "root";
    s_grp.gr_passwd = "x";
    s_grp.gr_gid = 0;
    s_grp.gr_mem = s_mem;
    return &s_grp;
}

int CORTEX_NSS_SYM(getgrgid_r)(gid_t gid, struct group *grp, char *buf, size_t buflen, struct group **result) {
#ifndef CORTEX_HOST_TEST
    static int (*orig_getgrgid_r)(gid_t, struct group *, char *, size_t, struct group **) = NULL;
    if (!orig_getgrgid_r) orig_getgrgid_r = (int (*)(gid_t, struct group *, char *, size_t, struct group **))dlsym(RTLD_NEXT, "getgrgid_r");
    int ret = orig_getgrgid_r ? orig_getgrgid_r(gid, grp, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? grp : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    if (gid != 0 && gid != real_gid()) {
        if (result) *result = NULL;
        return 0;
    }

    if (!grp || !buf || buflen < 64) {
        if (result) *result = NULL;
        return ERANGE;
    }
    snprintf(buf, buflen, "root");
    grp->gr_name = buf;
    grp->gr_passwd = "x";
    grp->gr_gid = gid;
    static char *s_members[] = {"root", NULL};
    grp->gr_mem = s_members;
    if (result) *result = grp;
    return 0;
}

int CORTEX_NSS_SYM(getgrnam_r)(const char *name, struct group *grp, char *buf, size_t buflen, struct group **result) {
#ifndef CORTEX_HOST_TEST
    static int (*orig_getgrnam_r)(const char *, struct group *, char *, size_t, struct group **) = NULL;
    if (!orig_getgrnam_r) orig_getgrnam_r = (int (*)(const char *, struct group *, char *, size_t, struct group **))dlsym(RTLD_NEXT, "getgrnam_r");
    int ret = orig_getgrnam_r ? orig_getgrnam_r(name, grp, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? grp : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    if (!name || strcmp(name, "root") != 0) {
        if (result) *result = NULL;
        return 0;
    }

    if (!grp || !buf || buflen < 64) {
        if (result) *result = NULL;
        return ERANGE;
    }
    snprintf(buf, buflen, "root");
    grp->gr_name = buf;
    grp->gr_passwd = "x";
    grp->gr_gid = 0;
    static char *s_members[] = {"root", NULL};
    grp->gr_mem = s_members;
    if (result) *result = grp;
    return 0;
}

struct passwd *CORTEX_NSS_SYM(getpwuid)(uid_t uid) {
#ifndef CORTEX_HOST_TEST
    static struct passwd *(*orig_getpwuid)(uid_t) = NULL;
    if (!orig_getpwuid) orig_getpwuid = (struct passwd *(*)(uid_t))dlsym(RTLD_NEXT, "getpwuid");
    struct passwd *res = orig_getpwuid ? orig_getpwuid(uid) : NULL;
    if (res) return res;
#endif

    if (uid != 0 && uid != real_uid()) {
        return NULL;
    }

    static struct passwd s_pwd;
    s_pwd.pw_name = "root";
    s_pwd.pw_passwd = "x";
    s_pwd.pw_uid = uid;
    s_pwd.pw_gid = 0;
    s_pwd.pw_gecos = "root";
    s_pwd.pw_dir = "/home";
    s_pwd.pw_shell = "/bin/bash";
    return &s_pwd;
}

struct passwd *CORTEX_NSS_SYM(getpwnam)(const char *name) {
#ifndef CORTEX_HOST_TEST
    static struct passwd *(*orig_getpwnam)(const char *) = NULL;
    if (!orig_getpwnam) orig_getpwnam = (struct passwd *(*)(const char *))dlsym(RTLD_NEXT, "getpwnam");
    struct passwd *res = orig_getpwnam ? orig_getpwnam(name) : NULL;
    if (res) return res;
#endif

    if (!name || strcmp(name, "root") != 0) {
        return NULL;
    }

    static struct passwd s_pwd;
    s_pwd.pw_name = "root";
    s_pwd.pw_passwd = "x";
    s_pwd.pw_uid = 0;
    s_pwd.pw_gid = 0;
    s_pwd.pw_gecos = "root";
    s_pwd.pw_dir = "/home";
    s_pwd.pw_shell = "/bin/bash";
    return &s_pwd;
}

int CORTEX_NSS_SYM(getpwuid_r)(uid_t uid, struct passwd *pwd, char *buf, size_t buflen, struct passwd **result) {
#ifndef CORTEX_HOST_TEST
    static int (*orig_getpwuid_r)(uid_t, struct passwd *, char *, size_t, struct passwd **) = NULL;
    if (!orig_getpwuid_r) orig_getpwuid_r = (int (*)(uid_t, struct passwd *, char *, size_t, struct passwd **))dlsym(RTLD_NEXT, "getpwuid_r");
    int ret = orig_getpwuid_r ? orig_getpwuid_r(uid, pwd, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? pwd : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    if (uid != 0 && uid != real_uid()) {
        if (result) *result = NULL;
        return 0;
    }

    if (!pwd || !buf || buflen < 128) {
        if (result) *result = NULL;
        return ERANGE;
    }
    snprintf(buf, buflen, "root");
    pwd->pw_name = buf;
    pwd->pw_passwd = "x";
    pwd->pw_uid = uid;
    pwd->pw_gid = 0;
    pwd->pw_gecos = buf;
    pwd->pw_dir = "/home";
    pwd->pw_shell = "/bin/bash";
    if (result) *result = pwd;
    return 0;
}

int CORTEX_NSS_SYM(getpwnam_r)(const char *name, struct passwd *pwd, char *buf, size_t buflen, struct passwd **result) {
#ifndef CORTEX_HOST_TEST
    static int (*orig_getpwnam_r)(const char *, struct passwd *, char *, size_t, struct passwd **) = NULL;
    if (!orig_getpwnam_r) orig_getpwnam_r = (int (*)(const char *, struct passwd *, char *, size_t, struct passwd **))dlsym(RTLD_NEXT, "getpwnam_r");
    int ret = orig_getpwnam_r ? orig_getpwnam_r(name, pwd, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? pwd : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    if (!name || strcmp(name, "root") != 0) {
        if (result) *result = NULL;
        return 0;
    }

    if (!pwd || !buf || buflen < 128) {
        if (result) *result = NULL;
        return ERANGE;
    }
    snprintf(buf, buflen, "root");
    pwd->pw_name = buf;
    pwd->pw_passwd = "x";
    pwd->pw_uid = 0;
    pwd->pw_gid = 0;
    pwd->pw_gecos = buf;
    pwd->pw_dir = "/home";
    pwd->pw_shell = "/bin/bash";
    if (result) *result = pwd;
    return 0;
}

#ifndef CORTEX_HOST_TEST

int chown(const char *pathname, uid_t owner, gid_t group) { (void)pathname; (void)owner; (void)group; return 0; }
int fchown(int fd, uid_t owner, gid_t group) { (void)fd; (void)owner; (void)group; return 0; }
int lchown(const char *pathname, uid_t owner, gid_t group) { (void)pathname; (void)owner; (void)group; return 0; }
int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    (void)dirfd; (void)pathname; (void)owner; (void)group; (void)flags; return 0;
}
int chroot(const char *path) { (void)path; return 0; }

// Hook mkfifo / mkfifoat / mknod / mknodat
int mkfifo(const char *pathname, mode_t mode);
int mkfifoat(int dirfd, const char *pathname, mode_t mode);

int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    (void)dev;
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (S_ISREG(mode)) {
        // mknod(2) returns 0 on success, never a file descriptor: close the
        // transient fd instead of leaking one per call (tar/dpkg extract
        // thousands of files; leaked fds exhaust the table -> EBADF noise).
        int fd = openat(dirfd, target, O_CREAT | O_WRONLY | O_TRUNC, mode);
        if (fd >= 0) {
            close(fd);
            return 0;
        }
        return -1;
    }
    if (S_ISFIFO(mode)) {
        return mkfifoat(dirfd, target, mode);
    }
    return 0;
}

int mknod(const char *pathname, mode_t mode, dev_t dev) {
    return mknodat(AT_FDCWD, pathname, mode, dev);
}

int mkfifoat(int dirfd, const char *pathname, mode_t mode) {
    static int (*orig_mkfifoat)(int, const char *, mode_t) = NULL;
    if (!orig_mkfifoat) orig_mkfifoat = (int (*)(int, const char *, mode_t))dlsym(RTLD_NEXT, "mkfifoat");
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (orig_mkfifoat) {
        return orig_mkfifoat(dirfd, target, mode);
    }
    static int (*orig_mkfifo)(const char *, mode_t) = NULL;
    if (!orig_mkfifo) orig_mkfifo = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "mkfifo");
    if (orig_mkfifo && dirfd == AT_FDCWD) {
        return orig_mkfifo(target, mode);
    }
    return -1;
}

int mkfifo(const char *pathname, mode_t mode) {
    return mkfifoat(AT_FDCWD, pathname, mode);
}

// Hook sync / syncfs to prevent Android flash storage stalls during dpkg operations
void sync(void) {
    // No-op
}

int syncfs(int fd) {
    (void)fd;
    return 0;
}

int capget(void *hdrp, void *datap) {
    static int (*orig_capget)(void *, void *) = NULL;
    if (!orig_capget) orig_capget = (int (*)(void *, void *))dlsym(RTLD_NEXT, "capget");
    if (orig_capget && orig_capget(hdrp, datap) == 0) {
        return 0;
    }
    if (datap) {
        uint32_t ver = hdrp ? *(const uint32_t *)hdrp : 0;
        size_t nbytes = (ver == 0x20071026U || ver == 0x20080522U) ? 24 : 12;
        memset(datap, 0, nbytes);
    }
    errno = 0;
    return 0;
}
int capset(void *hdrp, const void *datap) { (void)hdrp; (void)datap; return 0; }

int prctl(int option, ...) {
    va_list ap;
    va_start(ap, option);
    unsigned long arg2 = va_arg(ap, unsigned long);
    unsigned long arg3 = va_arg(ap, unsigned long);
    unsigned long arg4 = va_arg(ap, unsigned long);
    unsigned long arg5 = va_arg(ap, unsigned long);
    va_end(ap);

    // PR_SET_NO_NEW_PRIVS = 38, PR_GET_NO_NEW_PRIVS = 39
#ifdef PR_SET_NO_NEW_PRIVS
    if (option == PR_SET_NO_NEW_PRIVS) return 0;
#endif
    if (option == 38) return 0;
#ifdef PR_GET_NO_NEW_PRIVS
    if (option == PR_GET_NO_NEW_PRIVS) return 1;
#endif
    if (option == 39) return 1;

    // PR_SET_SECCOMP = 22, PR_GET_SECCOMP = 21
#ifdef PR_SET_SECCOMP
    if (option == PR_SET_SECCOMP) { errno = EINVAL; return -1; }
#endif
    if (option == 22) { errno = EINVAL; return -1; }
#ifdef PR_GET_SECCOMP
    if (option == PR_GET_SECCOMP) return 0;
#endif
    if (option == 21) return 0;

    // PR_SET_SPECULATION_CTRL = 47
    if (option == 47) return 0;

    static int (*orig_prctl)(int, unsigned long, unsigned long, unsigned long, unsigned long) = NULL;
    if (!orig_prctl) orig_prctl = (int (*)(int, unsigned long, unsigned long, unsigned long, unsigned long))dlsym(RTLD_NEXT, "prctl");
    return orig_prctl ? orig_prctl(option, arg2, arg3, arg4, arg5) : 0;
}

#ifndef LANDLOCK_CREATE_RULESET_VERSION
#define LANDLOCK_CREATE_RULESET_VERSION (1U << 0)
#endif

#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif
#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule 445
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif

#ifndef __NR_seccomp
#if defined(__aarch64__)
#define __NR_seccomp 277
#elif defined(__x86_64__)
#define __NR_seccomp 317
#elif defined(__arm__)
#define __NR_seccomp 383
#else
#define __NR_seccomp 277
#endif
#endif

#ifndef __NR_unshare
#if defined(__aarch64__)
#define __NR_unshare 97
#elif defined(__x86_64__)
#define __NR_unshare 272
#elif defined(__arm__)
#define __NR_unshare 337
#else
#define __NR_unshare 97
#endif
#endif

#ifndef __NR_setns
#if defined(__aarch64__)
#define __NR_setns 268
#elif defined(__x86_64__)
#define __NR_setns 308
#elif defined(__arm__)
#define __NR_setns 375
#else
#define __NR_setns 268
#endif
#endif

#ifndef __NR_clone3
#define __NR_clone3 435
#endif
#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_pidfd_send_signal
#define __NR_pidfd_send_signal 424
#endif
#ifndef __NR_pidfd_getfd
#define __NR_pidfd_getfd 438
#endif
#ifndef __NR_close_range
#define __NR_close_range 436
#endif
#ifndef __NR_faccessat
#if defined(__aarch64__)
#define __NR_faccessat 48
#elif defined(__arm__)
#define __NR_faccessat 334
#elif defined(__x86_64__)
#define __NR_faccessat 269
#endif
#endif
#ifndef __NR_faccessat2
#define __NR_faccessat2 439
#endif
#ifndef __NR_statx
#if defined(__aarch64__) || defined(__arm__)
#define __NR_statx 291
#elif defined(__x86_64__)
#define __NR_statx 332
#endif
#endif

#ifndef SECCOMP_SET_MODE_STRICT
#define SECCOMP_SET_MODE_STRICT 0
#endif
#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif
#ifndef SECCOMP_GET_ACTION_AVAIL
#define SECCOMP_GET_ACTION_AVAIL 2
#endif

int landlock_create_ruleset(const void *attr, size_t size, uint32_t flags) {
    (void)attr;
    (void)size;
    (void)flags;
    errno = ENOSYS;
    return -1;
}

int landlock_add_rule(int ruleset_fd, int rule_type, const void *rule_attr, uint32_t flags) {
    (void)ruleset_fd;
    (void)rule_type;
    (void)rule_attr;
    (void)flags;
    errno = ENOSYS;
    return -1;
}

int landlock_restrict_self(int ruleset_fd, uint32_t flags) {
    (void)ruleset_fd;
    (void)flags;
    errno = ENOSYS;
    return -1;
}

int seccomp(unsigned int operation, unsigned int flags, void *args) {
    (void)flags;
    (void)args;
    if (operation != SECCOMP_SET_MODE_STRICT &&
        operation != SECCOMP_SET_MODE_FILTER &&
        operation != SECCOMP_GET_ACTION_AVAIL &&
        operation != 3 /* SECCOMP_GET_NOTIF_SIZES */) {
        errno = EINVAL;
        return -1;
    }
    errno = ENOSYS;
    return -1;
}

int unshare(int flags) {
    (void)flags;
    errno = ENOSYS;
    return -1;
}

int setns(int fd, int nstype) {
    (void)fd;
    (void)nstype;
    errno = ENOSYS;
    return -1;
}

#ifndef CLOSE_RANGE_CLOEXEC
#define CLOSE_RANGE_CLOEXEC (1U << 2)
#endif

int close_range(unsigned int first, unsigned int last, int flags) {
    static int (*orig_close_range)(unsigned int, unsigned int, int) = NULL;
    if (!orig_close_range) {
        orig_close_range = (int (*)(unsigned int, unsigned int, int))dlsym(RTLD_NEXT, "close_range");
    }
#ifdef __NR_close_range
    static long (*orig_raw_syscall)(long, ...) = NULL;
    if (!orig_raw_syscall) orig_raw_syscall = (long (*)(long, ...))dlsym(RTLD_NEXT, "syscall");
    if (orig_raw_syscall) {
        long sret = orig_raw_syscall(__NR_close_range, first, last, flags);
        if (sret == 0) return 0;
        if (sret < 0 && errno != ENOSYS) return -1;
    }
#endif
    if (orig_close_range) {
        int cret = orig_close_range(first, last, flags);
        if (cret == 0) return 0;
        if (cret < 0 && errno != ENOSYS) return -1;
    }

    int set_cloexec = (flags & CLOSE_RANGE_CLOEXEC) != 0;

    DIR *d = opendir("/proc/self/fd");
    if (d) {
        int dfd = dirfd(d);
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            int fd = atoi(de->d_name);
            if (fd >= (int)first && (last == ~0U || (unsigned int)fd <= last) && fd != dfd) {
                if (set_cloexec) {
                    int fl = fcntl(fd, F_GETFD);
                    if (fl >= 0) {
                        fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
                    }
                } else {
                    close(fd);
                }
            }
        }
        closedir(d);
        return 0;
    }
    int max_fd = (int)sysconf(_SC_OPEN_MAX);
    if (max_fd <= 0 || max_fd > 1024) max_fd = 1024;
    int end = (last < (unsigned int)max_fd) ? (int)last : max_fd;
    for (int fd = (int)first; fd <= end; fd++) {
        if (set_cloexec) {
            int fl = fcntl(fd, F_GETFD);
            if (fl >= 0) {
                fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
            }
        } else {
            close(fd);
        }
    }
    return 0;
}

long syscall(long number, ...) {
    va_list ap;
    va_start(ap, number);
    unsigned long arg1 = va_arg(ap, unsigned long);
    unsigned long arg2 = va_arg(ap, unsigned long);
    unsigned long arg3 = va_arg(ap, unsigned long);
    unsigned long arg4 = va_arg(ap, unsigned long);
    unsigned long arg5 = va_arg(ap, unsigned long);
    unsigned long arg6 = va_arg(ap, unsigned long);
    va_end(ap);

#if defined(__NR_readlinkat)
    if (number == __NR_readlinkat) {
        return (long)readlinkat((int)arg1, (const char *)arg2, (char *)arg3, (size_t)arg4);
    }
#endif
#if defined(__NR_readlink)
    if (number == __NR_readlink) {
        return (long)readlink((const char *)arg1, (char *)arg2, (size_t)arg3);
    }
#endif

#ifdef __NR_landlock_create_ruleset
    if (number == __NR_landlock_create_ruleset) {
        return (long)landlock_create_ruleset((const void *)arg1, (size_t)arg2, (uint32_t)arg3);
    }
    if (number == __NR_landlock_add_rule) {
        return (long)landlock_add_rule((int)arg1, (int)arg2, (const void *)arg3, (uint32_t)arg4);
    }
    if (number == __NR_landlock_restrict_self) {
        return (long)landlock_restrict_self((int)arg1, (uint32_t)arg2);
    }
#endif

#ifdef __NR_seccomp
    if (number == __NR_seccomp) {
        return (long)seccomp((unsigned int)arg1, (unsigned int)arg2, (void *)arg3);
    }
#endif

#ifdef __NR_unshare
    if (number == __NR_unshare) {
        return (long)unshare((int)arg1);
    }
#endif

#ifdef __NR_setns
    if (number == __NR_setns) {
        return (long)setns((int)arg1, (int)arg2);
    }
#endif

#ifdef __NR_clone3
    if (number == __NR_clone3) {
        errno = ENOSYS;
        return -1;
    }
#endif

#ifdef __NR_pidfd_open
    if (number == __NR_pidfd_open) {
        errno = ENOSYS;
        return -1;
    }
#endif
#ifdef __NR_pidfd_send_signal
    if (number == __NR_pidfd_send_signal) {
        errno = ENOSYS;
        return -1;
    }
#endif
#ifdef __NR_pidfd_getfd
    if (number == __NR_pidfd_getfd) {
        errno = ENOSYS;
        return -1;
    }
#endif

#ifdef __NR_close_range
    if (number == __NR_close_range) {
        return (long)close_range((unsigned int)arg1, (unsigned int)arg2, (int)arg3);
    }
#endif

#ifdef __NR_openat2
    if (number == __NR_openat2) {
        return (long)openat2((int)arg1, (const char *)arg2, (struct cortex_open_how *)arg3, (size_t)arg4);
    }
#endif

#ifdef __NR_fchmodat2
    if (number == __NR_fchmodat2) {
        return (long)fchmodat2((int)arg1, (const char *)arg2, (mode_t)arg3, (int)arg4);
    }
#endif

#if defined(__NR_statx)
    if (number == __NR_statx) {
        return (long)statx((int)arg1, (const char *)arg2, (int)arg3, (unsigned int)arg4, (struct statx *)arg5);
    }
#endif

#if defined(__NR_faccessat)
    if (number == __NR_faccessat) {
        return (long)faccessat((int)arg1, (const char *)arg2, (int)arg3, 0);
    }
#endif

#if defined(__NR_faccessat2)
    if (number == __NR_faccessat2) {
        return (long)faccessat2((int)arg1, (const char *)arg2, (int)arg3, (int)arg4);
    }
#endif

#if defined(__NR_mknodat)
    if (number == __NR_mknodat) {
        return (long)mknodat((int)arg1, (const char *)arg2, (mode_t)arg3, (dev_t)arg4);
    }
#endif

#if defined(__NR_mknod)
    if (number == __NR_mknod) {
        return (long)mknod((const char *)arg1, (mode_t)arg2, (dev_t)arg3);
    }
#endif

#if defined(__NR_mkfifoat)
    if (number == __NR_mkfifoat) {
        return (long)mkfifoat((int)arg1, (const char *)arg2, (mode_t)arg3);
    }
#endif

#if defined(__NR_setresuid)
    if (number == __NR_setresuid) return 0;
#endif
#if defined(__NR_setresuid32)
    if (number == __NR_setresuid32) return 0;
#endif
#if defined(__NR_setresgid)
    if (number == __NR_setresgid) return 0;
#endif
#if defined(__NR_setresgid32)
    if (number == __NR_setresgid32) return 0;
#endif
#if defined(__NR_setuid)
    if (number == __NR_setuid) return 0;
#endif
#if defined(__NR_setuid32)
    if (number == __NR_setuid32) return 0;
#endif
#if defined(__NR_setgid)
    if (number == __NR_setgid) return 0;
#endif
#if defined(__NR_setgid32)
    if (number == __NR_setgid32) return 0;
#endif
#if defined(__NR_setreuid)
    if (number == __NR_setreuid) return 0;
#endif
#if defined(__NR_setreuid32)
    if (number == __NR_setreuid32) return 0;
#endif
#if defined(__NR_setregid)
    if (number == __NR_setregid) return 0;
#endif
#if defined(__NR_setregid32)
    if (number == __NR_setregid32) return 0;
#endif
#if defined(__NR_setgroups)
    if (number == __NR_setgroups) return 0;
#endif
#if defined(__NR_setgroups32)
    if (number == __NR_setgroups32) return 0;
#endif
#if defined(__NR_setfsuid)
    if (number == __NR_setfsuid) return 0;
#endif
#if defined(__NR_setfsuid32)
    if (number == __NR_setfsuid32) return 0;
#endif
#if defined(__NR_setfsgid)
    if (number == __NR_setfsgid) return 0;
#endif
#if defined(__NR_setfsgid32)
    if (number == __NR_setfsgid32) return 0;
#endif
#if defined(__NR_getuid)
    if (number == __NR_getuid) return 0;
#endif
#if defined(__NR_getuid32)
    if (number == __NR_getuid32) return 0;
#endif
#if defined(__NR_geteuid)
    if (number == __NR_geteuid) return 0;
#endif
#if defined(__NR_geteuid32)
    if (number == __NR_geteuid32) return 0;
#endif
#if defined(__NR_getgid)
    if (number == __NR_getgid) return 0;
#endif
#if defined(__NR_getgid32)
    if (number == __NR_getgid32) return 0;
#endif
#if defined(__NR_getegid)
    if (number == __NR_getegid) return 0;
#endif
#if defined(__NR_getegid32)
    if (number == __NR_getegid32) return 0;
#endif
#if defined(__NR_getresuid)
    if (number == __NR_getresuid) {
        uid_t *ruid = (uid_t *)arg1;
        uid_t *euid = (uid_t *)arg2;
        uid_t *suid = (uid_t *)arg3;
        if (ruid) *ruid = 0;
        if (euid) *euid = 0;
        if (suid) *suid = 0;
        return 0;
    }
#endif
#if defined(__NR_getresuid32)
    if (number == __NR_getresuid32) {
        uid_t *ruid = (uid_t *)arg1;
        uid_t *euid = (uid_t *)arg2;
        uid_t *suid = (uid_t *)arg3;
        if (ruid) *ruid = 0;
        if (euid) *euid = 0;
        if (suid) *suid = 0;
        return 0;
    }
#endif
#if defined(__NR_getresgid)
    if (number == __NR_getresgid) {
        gid_t *rgid = (gid_t *)arg1;
        gid_t *egid = (gid_t *)arg2;
        gid_t *sgid = (gid_t *)arg3;
        if (rgid) *rgid = 0;
        if (egid) *egid = 0;
        if (sgid) *sgid = 0;
        return 0;
    }
#endif
#if defined(__NR_getresgid32)
    if (number == __NR_getresgid32) {
        gid_t *rgid = (gid_t *)arg1;
        gid_t *egid = (gid_t *)arg2;
        gid_t *sgid = (gid_t *)arg3;
        if (rgid) *rgid = 0;
        if (egid) *egid = 0;
        if (sgid) *sgid = 0;
        return 0;
    }
#endif

    return call_orig_syscall(number, arg1, arg2, arg3, arg4, arg5, arg6);
}

// Hook lzma multi-threaded routines to enforce single-threaded execution
// This prevents clone/pthread_create failures in forked dpkg-deb child processes on Android
typedef struct {
    const uint8_t *next_in;
    size_t avail_in;
    uint64_t total_in;
    uint8_t *next_out;
    size_t avail_out;
    uint64_t total_out;
    const void *allocator;
    void *internal;
    void *reserved_ptr1;
    void *reserved_ptr2;
    void *reserved_ptr3;
    void *reserved_ptr4;
    uint64_t reserved_int1;
    uint64_t reserved_int2;
    size_t reserved_int3;
    size_t reserved_int4;
    uint32_t reserved_enum1;
    uint32_t reserved_enum2;
} cortex_lzma_stream;

typedef struct {
    uint32_t flags;
    uint32_t threads;
    uint64_t block_size;
    uint32_t timeout;
    uint32_t preset;
    const void *filters;
    int check;
    uint64_t memlimit_threading;
    uint64_t memlimit_stop;
} cortex_lzma_mt;

int lzma_stream_decoder_mt(cortex_lzma_stream *strm, const cortex_lzma_mt *options) {
    static int (*orig_decoder)(cortex_lzma_stream *, uint64_t, uint32_t) = NULL;
    if (!orig_decoder) {
        orig_decoder = (int (*)(cortex_lzma_stream *, uint64_t, uint32_t))dlsym(RTLD_DEFAULT, "lzma_stream_decoder");
        if (!orig_decoder) {
            orig_decoder = (int (*)(cortex_lzma_stream *, uint64_t, uint32_t))dlsym(RTLD_NEXT, "lzma_stream_decoder");
        }
    }
    uint64_t memlimit = (options && options->memlimit_threading > 0) ? options->memlimit_threading : UINT64_MAX;
    uint32_t flags = options ? options->flags : 0;
    if (orig_decoder) {
        return orig_decoder(strm, memlimit, flags);
    }
    static int (*orig_mt)(cortex_lzma_stream *, const cortex_lzma_mt *) = NULL;
    if (!orig_mt) {
        orig_mt = (int (*)(cortex_lzma_stream *, const cortex_lzma_mt *))dlsym(RTLD_NEXT, "lzma_stream_decoder_mt");
    }
    return orig_mt ? orig_mt(strm, options) : -1;
}

int lzma_stream_encoder_mt(cortex_lzma_stream *strm, const cortex_lzma_mt *options) {
    static int (*orig_easy)(cortex_lzma_stream *, uint32_t, int) = NULL;
    if (!orig_easy) {
        orig_easy = (int (*)(cortex_lzma_stream *, uint32_t, int))dlsym(RTLD_DEFAULT, "lzma_easy_encoder");
        if (!orig_easy) {
            orig_easy = (int (*)(cortex_lzma_stream *, uint32_t, int))dlsym(RTLD_NEXT, "lzma_easy_encoder");
        }
    }
    if (orig_easy) {
        uint32_t preset = options ? options->preset : 6;
        int check = options ? options->check : 4; // LZMA_CHECK_CRC64
        return orig_easy(strm, preset, check);
    }
    static int (*orig_mt)(cortex_lzma_stream *, const cortex_lzma_mt *) = NULL;
    if (!orig_mt) {
        orig_mt = (int (*)(cortex_lzma_stream *, const cortex_lzma_mt *))dlsym(RTLD_NEXT, "lzma_stream_encoder_mt");
    }
    return orig_mt ? orig_mt(strm, options) : -1;
}

uint32_t lzma_cputhreads(void) {
    return 1;
}

int ZSTD_CCtx_setParameter(void *cctx, int param, int value) {
    static int (*orig_param)(void *, int, int) = NULL;
    if (!orig_param) {
        orig_param = (int (*)(void *, int, int))dlsym(RTLD_DEFAULT, "ZSTD_CCtx_setParameter");
        if (!orig_param) {
            orig_param = (int (*)(void *, int, int))dlsym(RTLD_NEXT, "ZSTD_CCtx_setParameter");
        }
    }
    if (param == 400 /* ZSTD_c_nbWorkers */ && value > 1) {
        value = 1;
    }
    return orig_param ? orig_param(cctx, param, value) : -1;
}

static char **clean_env_for_system(char *const envp[]) {
    int count = 0;
    while (envp && envp[count]) count++;
    char **new_env = calloc(count + 3, sizeof(char *));
    int dst = 0;
    int has_path = 0;
    for (int i = 0; i < count; i++) {
        if (strncmp(envp[i], "LD_PRELOAD=", 11) != 0 &&
            strncmp(envp[i], "LD_LIBRARY_PATH=", 16) != 0 &&
            strncmp(envp[i], "GLIBC_TUNABLES=", 15) != 0) {
            if (strncmp(envp[i], "PATH=", 5) == 0) {
                has_path = 1;
                size_t plen = strlen(envp[i]);
                char *new_path = malloc(plen + 64);
                if (new_path) {
                    snprintf(new_path, plen + 64, "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:%s", envp[i] + 5);
                    new_env[dst++] = new_path;
                } else {
                    new_env[dst++] = envp[i];
                }
            } else {
                new_env[dst++] = envp[i];
            }
        }
    }
    if (!has_path) {
        new_env[dst++] = strdup("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin");
    }
    new_env[dst] = NULL;
    return new_env;
}

static char **prepare_cortex_env(char *const envp[], const char *real_exe) {
    init_cortex_hook();
    int count = 0;
    int has_preload = 0;
    int has_ld_library_path = 0;
    int has_root = 0;
    int has_tunables = 0;
    int has_path = 0;
    int has_tmp = 0;
    int has_threads_max = 0;
    int has_xz_opt = 0;
    int has_xz_defaults = 0;
    int has_frontend = 0;
    int has_debconf_frontend = 0;
    int has_debconf_seen = 0;

    char hook_path[PATH_MAX] = {0};
    if (g_cortex_root[0] != '\0') {
        snprintf(hook_path, sizeof(hook_path), "%s/usr/lib/libcortex-hook.so", g_cortex_root);
    }

    const char *chosen_real_exe = (real_exe && real_exe[0] != '\0') ? real_exe : (g_real_exe[0] != '\0' ? g_real_exe : NULL);

    while (envp && envp[count]) {
        if (strncmp(envp[count], "LD_PRELOAD=", 11) == 0) {
            has_preload = 1;
        } else if (strncmp(envp[count], "LD_LIBRARY_PATH=", 16) == 0) {
            has_ld_library_path = 1;
        } else if (strncmp(envp[count], "CORTEX_ROOT=", 12) == 0) {
            has_root = 1;
        } else if (strncmp(envp[count], "GLIBC_TUNABLES=", 15) == 0) {
            has_tunables = 1;
        } else if (strncmp(envp[count], "PATH=", 5) == 0) {
            has_path = 1;
        } else if (strncmp(envp[count], "TMPDIR=", 7) == 0) {
            has_tmp = 1;
        } else if (strncmp(envp[count], "DPKG_DEB_THREADS_MAX=", 21) == 0) {
            has_threads_max = 1;
        } else if (strncmp(envp[count], "XZ_OPT=", 7) == 0) {
            has_xz_opt = 1;
        } else if (strncmp(envp[count], "XZ_DEFAULTS=", 12) == 0) {
            has_xz_defaults = 1;
        } else if (strncmp(envp[count], "DEBIAN_FRONTEND=", 16) == 0) {
            has_frontend = 1;
        } else if (strncmp(envp[count], "DEBCONF_FRONTEND=", 17) == 0) {
            has_debconf_frontend = 1;
        } else if (strncmp(envp[count], "DEBCONF_NONINTERACTIVE_SEEN=", 28) == 0) {
            has_debconf_seen = 1;
        }
        count++;
    }

    char **new_env = calloc(count + 20, sizeof(char *));
    int dst = 0;
    for (int i = 0; i < count; i++) {
        // If we have an explicit real executable, drop the inherited entry to replace with the fresh target
        if (chosen_real_exe && strncmp(envp[i], "CORTEX_REAL_EXE=", 16) == 0) {
            continue;
        }
        new_env[dst++] = envp[i];
    }

    if (chosen_real_exe) {
        char *str = malloc(PATH_MAX + 32);
        if (str) {
            snprintf(str, PATH_MAX + 32, "CORTEX_REAL_EXE=%s", chosen_real_exe);
            new_env[dst++] = str;
        }
    }

    if (!has_preload && hook_path[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "LD_PRELOAD=%s", hook_path);
            new_env[dst++] = str;
        }
    }
    if (!has_ld_library_path && g_cortex_root[0] != '\0') {
        char *str = malloc(PATH_MAX * 4);
        if (str) {
            snprintf(str, PATH_MAX * 4, "LD_LIBRARY_PATH=%s/lib:%s/usr/lib:%s/lib/aarch64-linux-gnu:%s/usr/lib/aarch64-linux-gnu:%s/lib/arm-linux-gnueabihf:%s/usr/lib/arm-linux-gnueabihf:%s/usr/local/lib:%s/usr/lib/systemd:%s/lib/systemd:%s/usr/lib/aarch64-linux-gnu/systemd:%s/lib/aarch64-linux-gnu/systemd:%s/usr/lib/arm-linux-gnueabihf/systemd:%s/lib/arm-linux-gnueabihf/systemd",
                     g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root);
            new_env[dst++] = str;
        }
    }
    if (!has_root && g_cortex_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "CORTEX_ROOT=%s", g_cortex_root);
            new_env[dst++] = str;
        }
    }
    if (!has_tunables) {
        new_env[dst++] = strdup("GLIBC_TUNABLES=glibc.pthread.rseq=0");
    }
    if (!has_path) {
        new_env[dst++] = strdup("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    }
    if (!has_tmp && g_cortex_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "TMPDIR=%s/tmp", g_cortex_root);
            new_env[dst++] = str;
        }
    }
    if (!has_threads_max) {
        new_env[dst++] = strdup("DPKG_DEB_THREADS_MAX=1");
    }
    if (!has_xz_opt) {
        new_env[dst++] = strdup("XZ_OPT=-T1");
    }
    if (!has_xz_defaults) {
        new_env[dst++] = strdup("XZ_DEFAULTS=-T1");
    }
    if (!has_frontend) {
        new_env[dst++] = strdup("DEBIAN_FRONTEND=noninteractive");
    }
    if (!has_debconf_frontend) {
        new_env[dst++] = strdup("DEBCONF_FRONTEND=noninteractive");
    }
    if (!has_debconf_seen) {
        new_env[dst++] = strdup("DEBCONF_NONINTERACTIVE_SEEN=true");
    }
    new_env[dst] = NULL;
    return new_env;
}

static void free_modified_env(char **modified_env, char *const orig_env[]) {
    if (!modified_env) return;
    for (int i = 0; modified_env[i] != NULL; i++) {
        int is_orig = 0;
        if (orig_env) {
            for (int j = 0; orig_env[j] != NULL; j++) {
                if (modified_env[i] == orig_env[j]) {
                    is_orig = 1;
                    break;
                }
            }
        }
        if (!is_orig) {
            free(modified_env[i]);
        }
    }
    free(modified_env);
}

#endif /* !CORTEX_HOST_TEST */

static char s_cached_ld_so[PATH_MAX] = {0};
static char s_cached_ld_root[PATH_MAX] = {0};
static uint16_t s_cached_e_machine = 0;

#ifdef CORTEX_HOST_TEST
#define CORTEX_FIND_LD_SYM hook_find_dynamic_linker
#else
#define CORTEX_FIND_LD_SYM find_dynamic_linker
#endif

static int CORTEX_FIND_LD_SYM(const char *cortex_root, const char *cmd, char *out_ld_so, size_t max_len) {
    if (!cortex_root || cortex_root[0] == '\0' || !out_ld_so || max_len == 0) return 0;

    uint16_t e_machine = 0;
    if (cmd && cmd[0] != '\0') {
        int fd = open(cmd, O_RDONLY);
        if (fd >= 0) {
            unsigned char ehdr[20];
            ssize_t n = read(fd, ehdr, sizeof(ehdr));
            close(fd);
            if (n >= 20 && ehdr[0] == 0x7f && ehdr[1] == 'E' && ehdr[2] == 'L' && ehdr[3] == 'F') {
                e_machine = (uint16_t)(ehdr[18] | (ehdr[19] << 8));
            }
        }
    }

    if (s_cached_ld_so[0] != '\0' && strcmp(s_cached_ld_root, cortex_root) == 0 &&
        e_machine != 0 && e_machine == s_cached_e_machine) {
        strncpy(out_ld_so, s_cached_ld_so, max_len - 1);
        out_ld_so[max_len - 1] = '\0';
        return 1;
    }

    const char *aarch64_cands[] = {
        "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/aarch64-linux-gnu/ld-2.39.so",
        "/lib/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/ld-linux-aarch64.so.1",
        "/usr/lib64/ld-linux-aarch64.so.1",
        "/lib64/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-2.39.so",
        NULL
    };

    const char *armhf_cands[] = {
        "/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/usr/lib/arm-linux-gnueabi/ld-linux.so.3",
        "/lib/arm-linux-gnueabi/ld-linux.so.3",
        NULL
    };

    const char *x86_64_cands[] = {
        "/lib64/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-2.39.so",
        "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        NULL
    };

    const char **primary = NULL;
    const char **secondary = NULL;

    if (e_machine == 183) { // EM_AARCH64
        primary = aarch64_cands;
        secondary = NULL;
    } else if (e_machine == 40) { // EM_ARM
        primary = armhf_cands;
        secondary = NULL;
    } else if (e_machine == 62) { // EM_X86_64
        primary = x86_64_cands;
        secondary = NULL;
    } else if (e_machine == 3) { // EM_386
        primary = NULL;
        secondary = NULL;
    } else {
        #if defined(__aarch64__)
        primary = aarch64_cands;
        secondary = armhf_cands;
        #elif defined(__arm__)
        primary = armhf_cands;
        secondary = aarch64_cands;
        #else
        primary = x86_64_cands;
        #endif
    }

    if (primary) {
        for (int i = 0; primary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", cortex_root, primary[i]);
            if (access(out_ld_so, F_OK) == 0) {
                if (e_machine != 0) {
                    strncpy(s_cached_ld_so, out_ld_so, sizeof(s_cached_ld_so) - 1);
                    s_cached_ld_so[sizeof(s_cached_ld_so) - 1] = '\0';
                    strncpy(s_cached_ld_root, cortex_root, sizeof(s_cached_ld_root) - 1);
                    s_cached_ld_root[sizeof(s_cached_ld_root) - 1] = '\0';
                    s_cached_e_machine = e_machine;
                }
                return 1;
            }
        }
    }
    if (secondary) {
        for (int i = 0; secondary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", cortex_root, secondary[i]);
            if (access(out_ld_so, F_OK) == 0) {
                if (e_machine != 0) {
                    strncpy(s_cached_ld_so, out_ld_so, sizeof(s_cached_ld_so) - 1);
                    s_cached_ld_so[sizeof(s_cached_ld_so) - 1] = '\0';
                    strncpy(s_cached_ld_root, cortex_root, sizeof(s_cached_ld_root) - 1);
                    s_cached_ld_root[sizeof(s_cached_ld_root) - 1] = '\0';
                    s_cached_e_machine = e_machine;
                }
                return 1;
            }
        }
    }

    return 0;
}

static int check_elf_dynamic(int fd, const unsigned char *ehdr, ssize_t n) {
    if (n < 52 || ehdr[0] != 0x7f || ehdr[1] != 'E' || ehdr[2] != 'L' || ehdr[3] != 'F') {
        return 0;
    }
    if ((ehdr[4] != 1 && ehdr[4] != 2) || ehdr[5] != 1 /* ELFDATA2LSB */) {
        return 0;
    }
    int is_64 = (ehdr[4] == 2);
    uint64_t phoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;
    if (is_64) {
        if (n < 64) return 0;
        memcpy(&phoff, ehdr + 32, sizeof(uint64_t));
        memcpy(&phentsize, ehdr + 54, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 56, sizeof(uint16_t));
    } else {
        uint32_t phoff32 = 0;
        memcpy(&phoff32, ehdr + 28, sizeof(uint32_t));
        phoff = phoff32;
        memcpy(&phentsize, ehdr + 42, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 44, sizeof(uint16_t));
    }
    uint16_t min_phentsize = is_64 ? 56 : 32;
    if (phoff == 0 || phentsize < min_phentsize || phentsize > 4096 || phnum == 0) {
        return 0;
    }
    if ((off_t)phoff < 0 || (uint64_t)(off_t)phoff != phoff) {
        return 0;
    }
    if (lseek(fd, (off_t)phoff, SEEK_SET) < 0) {
        return 0;
    }
    for (int i = 0; i < phnum && i < 128; i++) {
        uint32_t p_type = 0;
        if (read(fd, &p_type, sizeof(p_type)) != sizeof(p_type)) break;
        if (p_type == 3 /* PT_INTERP */) {
            return 1;
        }
        if (lseek(fd, (off_t)(phentsize - sizeof(p_type)), SEEK_CUR) < 0) break;
    }
    return 0;
}

static int is_elf_binary(const char *path) {
    if (!path || path[0] == '\0') return 0;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    unsigned char magic[4];
    ssize_t n = read(fd, magic, sizeof(magic));
    close(fd);
    return (n == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F');
}

static int is_ld_linux(const char *path) {
    if (!path) return 0;
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    return (strncmp(base, "ld-linux", 8) == 0 || strncmp(base, "ld-2.", 5) == 0);
}

#ifndef CORTEX_HOST_TEST

static void resolve_symlinks(const char *path, char *out, size_t out_size, int depth) {
    if (!path || !out || out_size == 0) return;
    strncpy(out, path, out_size - 1);
    out[out_size - 1] = '\0';
    if (depth > 8) return;

    char link_target[PATH_MAX];
    ssize_t len = readlink(path, link_target, sizeof(link_target) - 1);
    if (len < 0) return;
    link_target[len] = '\0';

    char next_path[PATH_MAX];
    if (link_target[0] == '/') {
        char rw_buf[PATH_MAX];
        const char *rewritten = rewrite_path(link_target, rw_buf, sizeof(rw_buf));
        strncpy(next_path, rewritten, sizeof(next_path) - 1);
        next_path[sizeof(next_path) - 1] = '\0';
    } else {
        char dir_buf[PATH_MAX];
        strncpy(dir_buf, path, sizeof(dir_buf) - 1);
        dir_buf[sizeof(dir_buf) - 1] = '\0';
        char *slash = strrchr(dir_buf, '/');
        if (slash) {
            *slash = '\0';
            snprintf(next_path, sizeof(next_path), "%s/%s", dir_buf, link_target);
        } else {
            strncpy(next_path, link_target, sizeof(next_path) - 1);
            next_path[sizeof(next_path) - 1] = '\0';
        }
    }
    resolve_symlinks(next_path, out, out_size, depth + 1);
}

static int has_pt_interp(const char *path) {
    if (!path || path[0] == '\0') return 0;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    unsigned char ehdr[64];
    ssize_t n = read(fd, ehdr, sizeof(ehdr));
    int ret = check_elf_dynamic(fd, ehdr, n);
    close(fd);
    return ret;
}

static char **ensure_hook_in_envp(char *const envp[], const char *real_exe) {
    return prepare_cortex_env(envp, real_exe);
}

static void build_ld_library_path(char *const envp[], char *out_buf, size_t out_size) {
    if (!out_buf || out_size == 0) return;
    out_buf[0] = '\0';

    const char *caller_ld = NULL;
    if (envp) {
        for (int i = 0; envp[i] != NULL; i++) {
            if (strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) {
                caller_ld = envp[i] + 16;
                break;
            }
        }
    }
    if (!caller_ld) {
        caller_ld = getenv("LD_LIBRARY_PATH");
    }

    char rewritten_caller[PATH_MAX * 2] = {0};
    if (caller_ld && caller_ld[0] != '\0') {
        char copy[PATH_MAX * 2];
        strncpy(copy, caller_ld, sizeof(copy) - 1);
        copy[sizeof(copy) - 1] = '\0';
        size_t used = 0;
        char *saveptr = NULL;
        char *tok = strtok_r(copy, ":", &saveptr);
        while (tok) {
            if (tok[0] != '\0') {
                char rw_buf[PATH_MAX];
                const char *rw = (tok[0] == '/') ? rewrite_path(tok, rw_buf, sizeof(rw_buf)) : tok;
                int n = snprintf(rewritten_caller + used, sizeof(rewritten_caller) - used,
                                 "%s%s", (used > 0 ? ":" : ""), rw);
                if (n > 0 && used + (size_t)n < sizeof(rewritten_caller)) {
                    used += (size_t)n;
                }
            }
            tok = strtok_r(NULL, ":", &saveptr);
        }
    }

    if (rewritten_caller[0] != '\0') {
        snprintf(out_buf, out_size,
                 "%s:%s/lib:%s/usr/lib:%s/lib/aarch64-linux-gnu:%s/usr/lib/aarch64-linux-gnu:"
                 "%s/usr/lib/aarch64-linux-gnu/systemd:%s/lib/aarch64-linux-gnu/systemd:"
                 "%s/usr/lib/systemd:%s/lib/systemd:%s/lib/arm-linux-gnueabihf:%s/usr/lib/arm-linux-gnueabihf:"
                 "%s/usr/lib/arm-linux-gnueabihf/systemd:%s/lib/arm-linux-gnueabihf/systemd:%s/usr/local/lib",
                 rewritten_caller,
                 g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root,
                 g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root,
                 g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root);
    } else {
        snprintf(out_buf, out_size,
                 "%s/lib:%s/usr/lib:%s/lib/aarch64-linux-gnu:%s/usr/lib/aarch64-linux-gnu:"
                 "%s/usr/lib/aarch64-linux-gnu/systemd:%s/lib/aarch64-linux-gnu/systemd:"
                 "%s/usr/lib/systemd:%s/lib/systemd:%s/lib/arm-linux-gnueabihf:%s/usr/lib/arm-linux-gnueabihf:"
                 "%s/usr/lib/arm-linux-gnueabihf/systemd:%s/lib/arm-linux-gnueabihf/systemd:%s/usr/local/lib",
                 g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root,
                 g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root,
                 g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root, g_cortex_root);
    }
}

// Hook execve
typedef int (*orig_execve_f_type)(const char *filename, char *const argv[], char *const envp[]);
int execve(const char *filename, char *const argv[], char *const envp[]) {
    static orig_execve_f_type orig_execve = NULL;
    if (!orig_execve) orig_execve = (orig_execve_f_type)dlsym(RTLD_NEXT, "execve");

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));

    init_cortex_hook();

    const char *f_base = (filename != NULL) ? strrchr(filename, '/') : NULL;
    f_base = (f_base != NULL) ? f_base + 1 : (filename != NULL ? filename : "");

    const char *prog_name = strrchr(target, '/');
    prog_name = (prog_name != NULL) ? prog_name + 1 : target;

    // Intercept utilities that fail or cause issues in unprivileged Android environment
    if (strcmp(f_base, "ldconfig") == 0 ||
        strcmp(f_base, "ldconfig.real") == 0 ||
        strcmp(f_base, "systemd-machine-id-setup") == 0 ||
        strcmp(f_base, "systemd-sysusers") == 0 ||
        strcmp(f_base, "systemd-tmpfiles") == 0 ||
        strcmp(f_base, "start-stop-daemon") == 0 ||
        strcmp(f_base, "mandb") == 0 ||
        strcmp(f_base, "mandb.real") == 0 ||
        strcmp(f_base, "update-mime-database") == 0 ||
        strcmp(f_base, "update-desktop-database") == 0 ||
        strcmp(f_base, "install-info") == 0 ||
        strcmp(f_base, "install-sgmlcatalog") == 0 ||
        strcmp(prog_name, "ldconfig") == 0 ||
        strcmp(prog_name, "ldconfig.real") == 0 ||
        strcmp(prog_name, "systemd-machine-id-setup") == 0 ||
        strcmp(prog_name, "systemd-sysusers") == 0 ||
        strcmp(prog_name, "systemd-tmpfiles") == 0 ||
        strcmp(prog_name, "start-stop-daemon") == 0 ||
        strcmp(prog_name, "mandb") == 0 ||
        strcmp(prog_name, "mandb.real") == 0 ||
        strcmp(prog_name, "update-mime-database") == 0 ||
        strcmp(prog_name, "update-desktop-database") == 0 ||
        strcmp(prog_name, "install-info") == 0 ||
        strcmp(prog_name, "install-sgmlcatalog") == 0) {
        _exit(0);
    }
    if (strcmp(f_base, "mountpoint") == 0 || strcmp(prog_name, "mountpoint") == 0) {
        int is_virtual_fs = 0;
        for (int i = 1; argv && argv[i]; i++) {
            if (strcmp(argv[i], "/proc") == 0 || strcmp(argv[i], "/proc/") == 0 ||
                strcmp(argv[i], "/sys") == 0 || strcmp(argv[i], "/sys/") == 0 ||
                strcmp(argv[i], "/dev") == 0 || strcmp(argv[i], "/dev/") == 0 ||
                strcmp(argv[i], "/dev/pts") == 0) {
                is_virtual_fs = 1;
                break;
            }
        }
        if (is_virtual_fs) {
            _exit(0);
        }
    }
    if (strcmp(f_base, "policy-rc.d") == 0 || strcmp(prog_name, "policy-rc.d") == 0) {
        _exit(101);
    }

    // Only intercept binaries/scripts within CORTEX_ROOT
    if (g_cortex_root[0] != '\0' && strncmp(target, g_cortex_root, strlen(g_cortex_root)) == 0) {
        char resolved_target[PATH_MAX];
        resolve_symlinks(target, resolved_target, sizeof(resolved_target), 0);
        const char *elf_target = target;
        if (is_elf_binary(resolved_target) && !is_ld_linux(resolved_target)) {
            elf_target = resolved_target;
        }

        int fd = open(elf_target, O_RDONLY);
        if (fd >= 0) {
            char hdr[256];
            ssize_t n = read(fd, hdr, sizeof(hdr) - 1);

            // 1. Transparently route dynamically linked glibc ELF binaries through ld.so
            // Note: Statically linked binaries (PT_INTERP absent, e.g. Meta Muse Code) must execute directly!
            if (n >= 4 && (unsigned char)hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                int is_dynamic = check_elf_dynamic(fd, (const unsigned char *)hdr, n);
                close(fd);
                if (is_dynamic) {
                    char ld_so[PATH_MAX] = {0};
                    if (find_dynamic_linker(g_cortex_root, elf_target, ld_so, sizeof(ld_so)) && strcmp(elf_target, ld_so) != 0) {
                        static char s_last_chmoded_ld[PATH_MAX] = {0};
                        if (strcmp(s_last_chmoded_ld, ld_so) != 0) {
                            chmod(ld_so, 0755);
                            strncpy(s_last_chmoded_ld, ld_so, sizeof(s_last_chmoded_ld) - 1);
                        }
                        if (access(elf_target, X_OK) != 0) {
                            chmod(elf_target, 0755);
                        }

                        strncpy(g_real_exe, elf_target, sizeof(g_real_exe) - 1);
                        g_real_exe[sizeof(g_real_exe) - 1] = '\0';

                        char *const *arg_ptr = argv;
                        int argc = 0;
                        while (arg_ptr && *arg_ptr) {
                            argc++;
                            arg_ptr++;
                        }

                        const char *prog_name = strrchr(target, '/');
                        prog_name = (prog_name != NULL) ? prog_name + 1 : target;

                        char hook_so[PATH_MAX] = {0};
                        snprintf(hook_so, sizeof(hook_so), "%s/usr/lib/libcortex-hook.so", g_cortex_root);
                        if (access(hook_so, F_OK) != 0) {
                            snprintf(hook_so, sizeof(hook_so), "%s/lib/libcortex-hook.so", g_cortex_root);
                        }

                        char ld_lib_path[PATH_MAX * 6] = {0};
                        build_ld_library_path(envp, ld_lib_path, sizeof(ld_lib_path));

                        char **new_argv = (char **)calloc(argc + 16, sizeof(char *));
                        int nidx = 0;
                        new_argv[nidx++] = ld_so;
                        if (hook_so[0] != '\0' && access(hook_so, F_OK) == 0) {
                            new_argv[nidx++] = (char *)"--preload";
                            new_argv[nidx++] = hook_so;
                        }
                        new_argv[nidx++] = (char *)"--library-path";
                        new_argv[nidx++] = ld_lib_path;
                        new_argv[nidx++] = (char *)"--argv0";
                        new_argv[nidx++] = (char *)((argc > 0 && argv[0]) ? argv[0] : prog_name);
                        new_argv[nidx++] = (char *)elf_target;
                        for (int i = 1; i < argc; i++) {
                            new_argv[nidx++] = (char *)argv[i];
                        }
                        new_argv[nidx] = NULL;
                        char **new_env = ensure_hook_in_envp(envp, elf_target);
                        int ret = orig_execve(ld_so, new_argv, new_env);
                        free(new_argv);
                        free_modified_env(new_env, envp);
                        return ret;
                    }
                }
            } else {
                close(fd);
            }

            // 2. Handle scripts with shebang lines (e.g., #!/bin/sh, #!/usr/bin/perl)
            if (n >= 2 && hdr[0] == '#' && hdr[1] == '!') {
                hdr[n] = '\0';
                char *newline = strchr(hdr, '\n');
                if (newline) *newline = '\0';

                char *interp = hdr + 2;
                while (*interp == ' ' || *interp == '\t') interp++;
                char *interp_arg = strchr(interp, ' ');
                if (interp_arg) {
                    *interp_arg = '\0';
                    interp_arg++;
                    while (*interp_arg == ' ' || *interp_arg == '\t') interp_arg++;
                }

                char interp_buf[PATH_MAX];
                const char *rewritten_interp = rewrite_path(interp, interp_buf, sizeof(interp_buf));
                if (access(rewritten_interp, F_OK) != 0 && strcmp(interp, "/bin/sh") == 0) {
                    if (access("/system/bin/sh", X_OK) == 0) {
                        rewritten_interp = "/system/bin/sh";
                    }
                }

                char *const *arg_ptr = argv;
                int orig_argc = 0;
                while (arg_ptr && *arg_ptr) {
                    orig_argc++;
                    arg_ptr++;
                }

                int has_arg = (interp_arg && *interp_arg) ? 1 : 0;
                char **new_argv = (char **)calloc(orig_argc + has_arg + 3, sizeof(char *));
                int idx = 0;
                new_argv[idx++] = (char *)rewritten_interp;
                if (has_arg) {
                    new_argv[idx++] = interp_arg;
                }
                new_argv[idx++] = (char *)target;
                for (int i = 1; i < orig_argc; i++) {
                    new_argv[idx++] = argv[i];
                }
                new_argv[idx] = NULL;

                if (strncmp(rewritten_interp, "/system", 7) == 0 ||
                    (g_cortex_root[0] != '\0' && strncmp(rewritten_interp, g_cortex_root, strlen(g_cortex_root)) != 0)) {
                    char **sys_env = clean_env_for_system(envp);
                    int ret = orig_execve(rewritten_interp, new_argv, sys_env);
                    free(new_argv);
                    free_modified_env(sys_env, envp);
                    return ret;
                }
                char **cortex_env = prepare_cortex_env(envp, NULL);
                int ret = execve(rewritten_interp, new_argv, cortex_env);
                free(new_argv);
                free_modified_env(cortex_env, envp);
                return ret;
            }
        }
    } else {
        // Any binary outside CORTEX_ROOT is an Android host binary (e.g. /system/bin/su, /sbin/su, /data/adb/...)
        char **sys_env = clean_env_for_system(envp);
        int ret = orig_execve(target, argv, sys_env);
        free_modified_env(sys_env, envp);
        return ret;
    }

    char **cortex_env = prepare_cortex_env(envp, target);
    int ret = orig_execve(target, argv, cortex_env);
    free_modified_env(cortex_env, envp);
    return ret;
}

extern char **environ;

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execl(const char *path, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execv(path, argv);
    free(argv);
    return ret;
}

int execlp(const char *file, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execvp(file, argv);
    free(argv);
    return ret;
}

int execle(const char *path, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    char *const *envp = va_arg(args, char *const *);
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execve(path, argv, (char *const *)envp);
    free(argv);
    return ret;
}

int execvp(const char *file, char *const argv[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) {
        return execve(file, argv, environ);
    }
    const char *path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, environ);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, environ);
        }
    }
    return execve(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) {
        return execve(file, argv, envp);
    }
    const char *path_env = NULL;
    if (envp) {
        for (char *const *ep = envp; *ep; ep++) {
            if (strncmp(*ep, "PATH=", 5) == 0) {
                path_env = *ep + 5;
                break;
            }
        }
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, envp);
        }
    }
    return execve(file, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path,
                const posix_spawn_file_actions_t *file_actions,
                const posix_spawnattr_t *attrp,
                char *const argv[], char *const envp[]) {
    static int (*orig_posix_spawn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                  const posix_spawnattr_t *, char *const [], char *const []) = NULL;
    if (!orig_posix_spawn) orig_posix_spawn = (int (*)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                                      const posix_spawnattr_t *, char *const [], char *const []))
                                             dlsym(RTLD_NEXT, "posix_spawn");

    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));

    init_cortex_hook();

    const char *f_base = (path != NULL) ? strrchr(path, '/') : NULL;
    f_base = (f_base != NULL) ? f_base + 1 : (path != NULL ? path : "");

    const char *prog_name = strrchr(target, '/');
    prog_name = (prog_name != NULL) ? prog_name + 1 : target;

    if (strcmp(f_base, "ldconfig") == 0 ||
        strcmp(f_base, "ldconfig.real") == 0 ||
        strcmp(f_base, "systemd-machine-id-setup") == 0 ||
        strcmp(f_base, "systemd-sysusers") == 0 ||
        strcmp(f_base, "systemd-tmpfiles") == 0 ||
        strcmp(f_base, "start-stop-daemon") == 0 ||
        strcmp(prog_name, "ldconfig") == 0 ||
        strcmp(prog_name, "ldconfig.real") == 0 ||
        strcmp(prog_name, "systemd-machine-id-setup") == 0 ||
        strcmp(prog_name, "systemd-sysusers") == 0 ||
        strcmp(prog_name, "systemd-tmpfiles") == 0 ||
        strcmp(prog_name, "start-stop-daemon") == 0) {
        pid_t child = fork();
        if (child == 0) {
            _exit(0);
        } else if (child > 0) {
            if (pid) *pid = child;
            return 0;
        }
        return errno;
    }
    if (strcmp(f_base, "mountpoint") == 0 || strcmp(prog_name, "mountpoint") == 0) {
        int is_virtual_fs = 0;
        for (int i = 1; argv && argv[i]; i++) {
            if (strcmp(argv[i], "/proc") == 0 || strcmp(argv[i], "/proc/") == 0 ||
                strcmp(argv[i], "/sys") == 0 || strcmp(argv[i], "/sys/") == 0 ||
                strcmp(argv[i], "/dev") == 0 || strcmp(argv[i], "/dev/") == 0 ||
                strcmp(argv[i], "/dev/pts") == 0) {
                is_virtual_fs = 1;
                break;
            }
        }
        if (is_virtual_fs) {
            pid_t child = fork();
            if (child == 0) {
                _exit(0);
            } else if (child > 0) {
                if (pid) *pid = child;
                return 0;
            }
            return errno;
        }
    }
    if (strcmp(f_base, "policy-rc.d") == 0 || strcmp(prog_name, "policy-rc.d") == 0) {
        pid_t child = fork();
        if (child == 0) {
            _exit(101);
        } else if (child > 0) {
            if (pid) *pid = child;
            return 0;
        }
        return errno;
    }

    char resolved_target[PATH_MAX];
    resolve_symlinks(target, resolved_target, sizeof(resolved_target), 0);
    const char *elf_target = target;
    if (is_elf_binary(resolved_target) && !is_ld_linux(resolved_target)) {
        elf_target = resolved_target;
    }

    int is_elf = 0;
    int is_dynamic = 0;
    if (g_cortex_root[0] != '\0' && strncmp(target, g_cortex_root, strlen(g_cortex_root)) == 0) {
        int fd = open(elf_target, O_RDONLY);
        if (fd >= 0) {
            unsigned char hdr[256];
            ssize_t n = read(fd, hdr, sizeof(hdr));
            if (n >= 4 && hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                is_elf = 1;
                is_dynamic = check_elf_dynamic(fd, hdr, n);
            }
            close(fd);
        }
    }

    char ld_so[PATH_MAX] = {0};
    int has_ld_so = (is_elf && is_dynamic && g_cortex_root[0] != '\0') ? find_dynamic_linker(g_cortex_root, elf_target, ld_so, sizeof(ld_so)) : 0;

    char *const *orig_ep = envp ? envp : environ;
    char **new_envp = ensure_hook_in_envp((char *const *)orig_ep, elf_target);

    int ret = -1;
    if (is_elf && has_ld_so && strcmp(elf_target, ld_so) != 0) {
        static char s_last_chmoded_ld_spawn[PATH_MAX] = {0};
        if (strcmp(s_last_chmoded_ld_spawn, ld_so) != 0) {
            chmod(ld_so, 0755);
            strncpy(s_last_chmoded_ld_spawn, ld_so, sizeof(s_last_chmoded_ld_spawn) - 1);
        }
        if (access(elf_target, X_OK) != 0) {
            chmod(elf_target, 0755);
        }

        int argc = 0;
        while (argv && argv[argc]) argc++;

        const char *prog_name = strrchr(target, '/');
        prog_name = (prog_name != NULL) ? prog_name + 1 : target;

        char hook_so[PATH_MAX] = {0};
        snprintf(hook_so, sizeof(hook_so), "%s/usr/lib/libcortex-hook.so", g_cortex_root);
        if (access(hook_so, F_OK) != 0) {
            snprintf(hook_so, sizeof(hook_so), "%s/lib/libcortex-hook.so", g_cortex_root);
        }

        char ld_lib_path[PATH_MAX * 6] = {0};
        build_ld_library_path((char *const *)orig_ep, ld_lib_path, sizeof(ld_lib_path));

        char **new_argv = (char **)calloc(argc + 16, sizeof(char *));
        if (new_argv) {
            int nidx = 0;
            new_argv[nidx++] = ld_so;
            if (hook_so[0] != '\0' && access(hook_so, F_OK) == 0) {
                new_argv[nidx++] = (char *)"--preload";
                new_argv[nidx++] = hook_so;
            }
            new_argv[nidx++] = (char *)"--library-path";
            new_argv[nidx++] = ld_lib_path;
            new_argv[nidx++] = (char *)"--argv0";
            new_argv[nidx++] = (char *)((argc > 0 && argv && argv[0]) ? argv[0] : prog_name);
            new_argv[nidx++] = (char *)elf_target;
            for (int i = 1; i < argc; i++) {
                new_argv[nidx++] = (char *)argv[i];
            }
            new_argv[nidx] = NULL;

            if (orig_posix_spawn) {
                ret = orig_posix_spawn(pid, ld_so, file_actions, attrp, new_argv, new_envp);
            }
            free(new_argv);
        }
    } else {
        if (orig_posix_spawn) {
            ret = orig_posix_spawn(pid, target, file_actions, attrp, argv, new_envp);
        }
    }

    if (ret != 0) {
        pid_t child = fork();
        if (child < 0) {
            free_modified_env(new_envp, (char *const *)orig_ep);
            return errno;
        } else if (child == 0) {
            execve(target, argv, new_envp);
            _exit(127);
        } else {
            if (pid) *pid = child;
            free_modified_env(new_envp, (char *const *)orig_ep);
            return 0;
        }
    }
    free_modified_env(new_envp, (char *const *)orig_ep);
    return ret;
}

int posix_spawnp(pid_t *pid, const char *file,
                 const posix_spawn_file_actions_t *file_actions,
                 const posix_spawnattr_t *attrp,
                 char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        return ENOENT;
    }
    if (strchr(file, '/')) {
        return posix_spawn(pid, file, file_actions, attrp, argv, envp);
    }
    const char *path_env = NULL;
    if (envp) {
        for (char *const *ep = envp; *ep; ep++) {
            if (strncmp(*ep, "PATH=", 5) == 0) {
                path_env = *ep + 5;
                break;
            }
        }
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return posix_spawn(pid, candidate, file_actions, attrp, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return posix_spawn(pid, candidate, file_actions, attrp, argv, envp);
        }
    }
    // If not found in PATH or standard paths, return ENOENT without spawning zombie
    return ENOENT;
}

// DNS resolution hooking and localhost DNS redirect
#define DNS_REDIRECT_MAX 64
static struct {
    int fd;
    in_addr_t orig_ip;
    in_port_t orig_port;
    uint32_t active;
} g_dns_redirects[DNS_REDIRECT_MAX];
static pthread_mutex_t g_dns_redirect_mutex = PTHREAD_MUTEX_INITIALIZER;

static void record_dns_redirect(int fd, in_addr_t orig_ip, in_port_t orig_port) {
    if (fd < 0) return;
    pthread_mutex_lock(&g_dns_redirect_mutex);
    int target_idx = (fd >= 0 ? fd : -fd) % DNS_REDIRECT_MAX;
    g_dns_redirects[target_idx].fd = fd;
    g_dns_redirects[target_idx].orig_ip = orig_ip;
    g_dns_redirects[target_idx].orig_port = orig_port;
    g_dns_redirects[target_idx].active = 1;
    pthread_mutex_unlock(&g_dns_redirect_mutex);
}

static void clear_dns_redirect(int fd) {
    if (fd < 0) return;
    int target_idx = (fd >= 0 ? fd : -fd) % DNS_REDIRECT_MAX;
    if (!g_dns_redirects[target_idx].active || g_dns_redirects[target_idx].fd != fd) {
        return;
    }
    pthread_mutex_lock(&g_dns_redirect_mutex);
    if (g_dns_redirects[target_idx].active && g_dns_redirects[target_idx].fd == fd) {
        g_dns_redirects[target_idx].active = 0;
        g_dns_redirects[target_idx].fd = -1;
        g_dns_redirects[target_idx].orig_ip = 0;
        g_dns_redirects[target_idx].orig_port = 0;
    }
    pthread_mutex_unlock(&g_dns_redirect_mutex);
}

int close(int fd) {
    static int (*orig_close)(int) = NULL;
    if (!orig_close) orig_close = (int (*)(int))dlsym(RTLD_NEXT, "close");
    clear_dns_redirect(fd);
    return orig_close ? orig_close(fd) : -1;
}

int dup2(int oldfd, int newfd) {
    static int (*orig_dup2)(int, int) = NULL;
    if (!orig_dup2) orig_dup2 = (int (*)(int, int))dlsym(RTLD_NEXT, "dup2");
    if (oldfd != newfd) {
        clear_dns_redirect(newfd);
    }
    return orig_dup2 ? orig_dup2(oldfd, newfd) : -1;
}

int dup3(int oldfd, int newfd, int flags) {
    static int (*orig_dup3)(int, int, int) = NULL;
    if (!orig_dup3) orig_dup3 = (int (*)(int, int, int))dlsym(RTLD_NEXT, "dup3");
    if (oldfd != newfd) {
        clear_dns_redirect(newfd);
    }
    return orig_dup3 ? orig_dup3(oldfd, newfd, flags) : -1;
}

static int get_dns_redirect(int fd, in_addr_t *orig_ip, in_port_t *orig_port) {
    if (fd < 0) return 0;
    int found = 0;
    pthread_mutex_lock(&g_dns_redirect_mutex);
    int target_idx = (fd >= 0 ? fd : -fd) % DNS_REDIRECT_MAX;
    if (g_dns_redirects[target_idx].active && g_dns_redirects[target_idx].fd == fd) {
        if (orig_ip) *orig_ip = g_dns_redirects[target_idx].orig_ip;
        if (orig_port) *orig_port = g_dns_redirects[target_idx].orig_port;
        found = 1;
    }
    pthread_mutex_unlock(&g_dns_redirect_mutex);
    return found;
}

static inline int is_loopback_dns(const struct sockaddr *addr, socklen_t addrlen) {
    if (!addr || addrlen < sizeof(struct sockaddr_in)) return 0;
    if (addr->sa_family != AF_INET) return 0;
    const struct sockaddr_in *sin = (const struct sockaddr_in *)addr;
    return (sin->sin_port == htons(53) && ((ntohl(sin->sin_addr.s_addr) >> 24) == 127));
}

static in_addr_t get_primary_dns(void) {
    static in_addr_t cached_dns = 0;
    static time_t last_check = 0;
    static time_t last_resolv_mtime = 0;
    static pthread_mutex_t dns_mutex = PTHREAD_MUTEX_INITIALIZER;

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    time_t now = ts.tv_sec;

    // Fast path: cached value valid within 5s TTL
    if (cached_dns != 0 && (now - last_check < 5)) {
        return cached_dns;
    }

    pthread_mutex_lock(&dns_mutex);
    if (cached_dns != 0 && (now - last_check < 5)) {
        in_addr_t val = cached_dns;
        pthread_mutex_unlock(&dns_mutex);
        return val;
    }
    last_check = now;

    in_addr_t new_dns = 0;
    init_cortex_hook();

    // 1. Check Android system property net.dns1 via Bionic libc
    static int (*orig_sys_prop_get)(const char *, char *) = NULL;
    static int prop_lookup_done = 0;
    if (!prop_lookup_done) {
        orig_sys_prop_get = (int (*)(const char *, char *))dlsym(RTLD_DEFAULT, "__system_property_get");
        prop_lookup_done = 1;
    }
    if (orig_sys_prop_get) {
        char prop_val[256];
        if (orig_sys_prop_get("net.dns1", prop_val) > 0 && prop_val[0] != '\0') {
            struct in_addr a;
            if (inet_aton(prop_val, &a)) {
                if ((ntohl(a.s_addr) >> 24) != 127 && a.s_addr != 0) {
                    new_dns = a.s_addr;
                }
            }
        }
    }

    // 2. Parse /etc/resolv.conf in Cortex rootfs
    char resolv_path[PATH_MAX];
    if (g_cortex_root[0] != '\0') {
        snprintf(resolv_path, sizeof(resolv_path), "%s/etc/resolv.conf", g_cortex_root);
    } else {
        snprintf(resolv_path, sizeof(resolv_path), "/etc/resolv.conf");
    }

    struct stat st;
    if (stat(resolv_path, &st) == 0) {
        last_resolv_mtime = st.st_mtime;
        if (new_dns == 0) {
            FILE *f = fopen(resolv_path, "r");
            if (f) {
                char line[256];
                while (fgets(line, sizeof(line), f)) {
                    char *p = line;
                    while (*p == ' ' || *p == '\t') p++;
                    if (strncmp(p, "nameserver", 10) == 0) {
                        p += 10;
                        while (*p == ' ' || *p == '\t') p++;
                        char *end = p;
                        while (*end && *end != ' ' && *end != '\t' && *end != '\r' && *end != '\n') end++;
                        *end = '\0';
                        struct in_addr a;
                        if (inet_aton(p, &a)) {
                            // Only accept valid, non-loopback DNS servers
                            if ((ntohl(a.s_addr) >> 24) != 127 && a.s_addr != 0) {
                                new_dns = a.s_addr;
                                break;
                            }
                        }
                    }
                }
                fclose(f);
            }
        }
    }

    if (new_dns == 0) {
        new_dns = inet_addr("8.8.8.8");
    }

    cached_dns = new_dns;
    pthread_mutex_unlock(&dns_mutex);
    return cached_dns;
}


#define CORTEX_ADDRINFO_MAGIC 0x434f5254

static struct addrinfo *alloc_one_addrinfo(const char *node, const char *ip_str, int port, int socktype, int protocol) {
    struct addrinfo *ai = (struct addrinfo *)calloc(1, sizeof(struct addrinfo));
    if (!ai) return NULL;
    struct sockaddr_in *sa = (struct sockaddr_in *)calloc(1, sizeof(struct sockaddr_in));
    if (!sa) {
        free(ai);
        return NULL;
    }
    sa->sin_family = AF_INET;
    sa->sin_port = htons((uint16_t)port);
    inet_pton(AF_INET, ip_str, &sa->sin_addr);

    ai->ai_flags = CORTEX_ADDRINFO_MAGIC;
    ai->ai_family = AF_INET;
    ai->ai_socktype = socktype ? socktype : SOCK_STREAM;
    ai->ai_protocol = protocol ? protocol : IPPROTO_TCP;
    ai->ai_addrlen = sizeof(struct sockaddr_in);
    ai->ai_addr = (struct sockaddr *)sa;
    ai->ai_canonname = node ? strdup(node) : NULL;
    ai->ai_next = NULL;
    return ai;
}

static uint16_t generate_dns_txid(void) {
    uint16_t txid = 0;
    static ssize_t (*libc_getrandom)(void *, size_t, unsigned int) = NULL;
    static int getrandom_checked = 0;
    if (!getrandom_checked) {
        libc_getrandom = (ssize_t (*)(void *, size_t, unsigned int))dlsym(RTLD_DEFAULT, "getrandom");
        getrandom_checked = 1;
    }
    if (libc_getrandom) {
        if (libc_getrandom(&txid, sizeof(txid), 1 /* GRND_NONBLOCK */) == sizeof(txid) && txid != 0) {
            return txid;
        }
    }
#if defined(SYS_getrandom) || defined(__NR_getrandom)
#ifndef __NR_getrandom
#define __NR_getrandom SYS_getrandom
#endif
    long r = syscall(__NR_getrandom, &txid, sizeof(txid), 1 /* GRND_NONBLOCK */);
    if (r == (long)sizeof(txid) && txid != 0) {
        return txid;
    }
#endif
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint32_t val = (uint32_t)(ts.tv_nsec ^ (ts.tv_sec << 16) ^ (uintptr_t)&txid);
    txid = (uint16_t)((val & 0xffff) ^ (val >> 16));
    if (txid == 0) txid = 0x5a6b;
    return txid;
}

static int dns_lookup_ipv4(const char *hostname, struct in_addr *out_addr) {
    if (!hostname || !out_addr) return -1;

    if (inet_aton(hostname, out_addr)) {
        return 0;
    }

    unsigned char packet[512];
    memset(packet, 0, sizeof(packet));

    uint16_t txid = generate_dns_txid();
    packet[0] = (unsigned char)(txid >> 8);
    packet[1] = (unsigned char)(txid & 0xff);
    packet[2] = 0x01;
    packet[3] = 0x00;
    packet[4] = 0x00;
    packet[5] = 0x01;

    int pos = 12;
    const char *p = hostname;
    while (*p) {
        const char *dot = strchr(p, '.');
        size_t len = dot ? (size_t)(dot - p) : strlen(p);
        if (len > 63 || pos + len + 1 >= (int)sizeof(packet) - 10) return -1;
        packet[pos++] = (unsigned char)len;
        memcpy(&packet[pos], p, len);
        pos += len;
        if (!dot) break;
        p = dot + 1;
    }
    packet[pos++] = 0;

    packet[pos++] = 0x00;
    packet[pos++] = 0x01; // QTYPE A
    packet[pos++] = 0x00;
    packet[pos++] = 0x01; // QCLASS IN
    int packet_len = pos;

    in_addr_t dns_servers[4];
    int server_count = 0;
    in_addr_t p_dns = get_primary_dns();
    if (p_dns != 0 && (ntohl(p_dns) >> 24) != 127) {
        dns_servers[server_count++] = p_dns;
    }
    in_addr_t g_dns = inet_addr("8.8.8.8");
    if (g_dns != p_dns) dns_servers[server_count++] = g_dns;
    in_addr_t c_dns = inet_addr("1.1.1.1");
    if (c_dns != p_dns && c_dns != g_dns) dns_servers[server_count++] = c_dns;
    in_addr_t o_dns = inet_addr("9.9.9.9");
    if (o_dns != p_dns && o_dns != g_dns && o_dns != c_dns) dns_servers[server_count++] = o_dns;

    for (int s = 0; s < server_count; s++) {
        if (dns_servers[s] == 0) continue;

        int sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) continue;

        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 600000; // 600ms fast failover
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        struct sockaddr_in dest;
        memset(&dest, 0, sizeof(dest));
        dest.sin_family = AF_INET;
        dest.sin_port = htons(53);
        dest.sin_addr.s_addr = dns_servers[s];

        sendto(sock, packet, packet_len, 0, (struct sockaddr *)&dest, sizeof(dest));

        unsigned char resp[1024];
        ssize_t n = recvfrom(sock, resp, sizeof(resp), 0, NULL, NULL);
        close(sock);

        if (n > 12 && resp[0] == packet[0] && resp[1] == packet[1]) {
            int ancount = (resp[6] << 8) | resp[7];
            if (ancount > 0) {
                int offset = 12;
                while (offset < n && resp[offset] != 0) {
                    if ((resp[offset] & 0xc0) == 0xc0) {
                        offset += 2;
                        break;
                    }
                    offset += 1 + resp[offset];
                }
                if (offset < n && resp[offset] == 0) offset++;
                offset += 4; // Skip QTYPE and QCLASS

                for (int a = 0; a < ancount && offset + 10 <= n; a++) {
                    if ((resp[offset] & 0xc0) == 0xc0) {
                        offset += 2;
                    } else {
                        while (offset < n && resp[offset] != 0) offset++;
                        if (offset < n) offset++;
                    }
                    if (offset + 10 > n) break;
                    int rtype = (resp[offset] << 8) | resp[offset + 1];
                    int rdlen = (resp[offset + 8] << 8) | resp[offset + 9];
                    offset += 10;
                    if (rtype == 1 && rdlen == 4 && offset + 4 <= n) {
                        memcpy(&out_addr->s_addr, &resp[offset], 4);
                        return 0;
                    }
                    offset += rdlen;
                }
            }
        }
    }
    return -1;
}

static int domain_matches(const char *node, const char *suffix) {
    if (!node || !suffix) return 0;
    size_t nlen = strlen(node);
    size_t slen = strlen(suffix);
    if (nlen < slen) return 0;
    if (strcasecmp(node + (nlen - slen), suffix) != 0) return 0;
    if (nlen == slen) return 1;
    return (node[nlen - slen - 1] == '.');
}

static int synthesize_fallback_addrinfo(const char *node, const char *service,
                                        const struct addrinfo *hints,
                                        struct addrinfo **res) {
    if (!node || !res) return EAI_NONAME;

    int port = 80;
    if (service) {
        if (strcmp(service, "https") == 0 || strcmp(service, "443") == 0) {
            port = 443;
        } else if (strcmp(service, "http") == 0 || strcmp(service, "80") == 0) {
            port = 80;
        } else {
            int p = 0;
            const char *sp = service;
            while (*sp >= '0' && *sp <= '9') {
                p = p * 10 + (*sp - '0');
                sp++;
            }
            if (p > 0 && p < 65536) port = p;
        }
    }

    int socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
    int protocol = (hints && hints->ai_protocol) ? hints->ai_protocol : IPPROTO_TCP;

    struct in_addr resolved_addr;
    if (dns_lookup_ipv4(node, &resolved_addr) == 0) {
        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &resolved_addr, ip_str, sizeof(ip_str));
        struct addrinfo *ai = alloc_one_addrinfo(node, ip_str, port, socktype, protocol);
        if (ai) {
            *res = ai;
            return 0;
        }
    }

    if (domain_matches(node, "ubuntu.com")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "91.189.91.103", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "91.189.92.21", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "debian.org")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "151.101.130.132", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "151.101.2.132", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "opencode.ai")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "172.65.90.22", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "172.65.90.23", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "github.com")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "140.82.121.6", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "140.82.121.4", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "githubusercontent.com")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "185.199.110.133", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "185.199.108.133", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "npmjs.org") || domain_matches(node, "npmjs.com")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "104.16.2.34", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "104.16.3.34", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "googleapis.com") || domain_matches(node, "accounts.google.com")) {
        struct addrinfo *ai1 = alloc_one_addrinfo(node, "142.251.127.95", port, socktype, protocol);
        if (!ai1) return EAI_MEMORY;
        struct addrinfo *ai2 = alloc_one_addrinfo(node, "142.251.127.84", port, socktype, protocol);
        if (ai2) {
            ai1->ai_next = ai2;
        }
        *res = ai1;
        return 0;
    }

    if (domain_matches(node, "meta.ai") || domain_matches(node, "meta.com") || domain_matches(node, "facebook.com")) {
        const char *ip = domain_matches(node, "lookaside.facebook.com") ? "57.144.36.128" : "57.144.36.141";
        struct addrinfo *ai = alloc_one_addrinfo(node, ip, port, socktype, protocol);
        if (ai) {
            *res = ai;
            return 0;
        }
    }

    return EAI_NONAME;
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints,
                struct addrinfo **res) {
    static int (*orig_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **) = NULL;
    if (!orig_getaddrinfo) orig_getaddrinfo = (int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))dlsym(RTLD_NEXT, "getaddrinfo");

    struct addrinfo mod_hints;
    if (hints) {
        mod_hints = *hints;
        // On Android, unprivileged apps are blocked by SELinux from querying netlink routing interfaces.
        // Glibc's AI_ADDRCONFIG flag causes getaddrinfo to fail or return no addresses when netlink fails.
        // Clearing AI_ADDRCONFIG ensures normal, robust DNS resolution.
        mod_hints.ai_flags &= ~AI_ADDRCONFIG;
    } else {
        memset(&mod_hints, 0, sizeof(mod_hints));
        mod_hints.ai_family = AF_UNSPEC;
        mod_hints.ai_flags = 0;
    }

    int ret = orig_getaddrinfo ? orig_getaddrinfo(node, service, &mod_hints, res) : EAI_FAIL;
    if (ret != 0 || !res || !*res) {
        if (node) {
            ret = synthesize_fallback_addrinfo(node, service, hints, res);
        }
    }
    return ret;
}

void freeaddrinfo(struct addrinfo *res) {
    static void (*orig_freeaddrinfo)(struct addrinfo *) = NULL;
    if (!orig_freeaddrinfo) orig_freeaddrinfo = (void (*)(struct addrinfo *))dlsym(RTLD_NEXT, "freeaddrinfo");

    if (!res) return;
    if (res->ai_flags == CORTEX_ADDRINFO_MAGIC) {
        struct addrinfo *curr = res;
        while (curr) {
            struct addrinfo *next = curr->ai_next;
            if (curr->ai_canonname) free(curr->ai_canonname);
            if (curr->ai_addr) free(curr->ai_addr);
            free(curr);
            curr = next;
        }
        return;
    }
    if (orig_freeaddrinfo) orig_freeaddrinfo(res);
}

int res_init(void) {
    static int (*orig_res_init)(void) = NULL;
    if (!orig_res_init) orig_res_init = (int (*)(void))dlsym(RTLD_NEXT, "res_init");
    int ret = orig_res_init ? orig_res_init() : 0;
    res_state statp = __res_state();
    if (statp) {
        int has_non_loopback = 0;
        for (int i = 0; i < statp->nscount; i++) {
            if ((ntohl(statp->nsaddr_list[i].sin_addr.s_addr) >> 24) != 127 && statp->nsaddr_list[i].sin_addr.s_addr != 0) {
                has_non_loopback = 1;
                break;
            }
        }
        if (!has_non_loopback || statp->nscount <= 0) {
            statp->nscount = 2;
            statp->nsaddr_list[0].sin_family = AF_INET;
            statp->nsaddr_list[0].sin_port = htons(53);
            statp->nsaddr_list[0].sin_addr.s_addr = get_primary_dns();
            statp->nsaddr_list[1].sin_family = AF_INET;
            statp->nsaddr_list[1].sin_port = htons(53);
            inet_pton(AF_INET, "8.8.8.8", &statp->nsaddr_list[1].sin_addr);
        }
        statp->retrans = 1;
        statp->retry = 2;
    }
    return ret;
}

int res_ninit(res_state statp) {
    static int (*orig_res_ninit)(res_state) = NULL;
    if (!orig_res_ninit) orig_res_ninit = (int (*)(res_state))dlsym(RTLD_NEXT, "res_ninit");
    if (statp) {
        memset(statp, 0, sizeof(*statp));
        statp->_vcsock = -1;
        for (int i = 0; i < MAXNS; i++) {
            statp->_u._ext.nssocks[i] = -1;
        }
    }
    int ret = orig_res_ninit ? orig_res_ninit(statp) : 0;
    if (statp) {
        int has_non_loopback = 0;
        for (int i = 0; i < statp->nscount; i++) {
            if ((ntohl(statp->nsaddr_list[i].sin_addr.s_addr) >> 24) != 127 && statp->nsaddr_list[i].sin_addr.s_addr != 0) {
                has_non_loopback = 1;
                break;
            }
        }
        if (!has_non_loopback || statp->nscount <= 0) {
            statp->nscount = 2;
            statp->nsaddr_list[0].sin_family = AF_INET;
            statp->nsaddr_list[0].sin_port = htons(53);
            statp->nsaddr_list[0].sin_addr.s_addr = get_primary_dns();
            statp->nsaddr_list[1].sin_family = AF_INET;
            statp->nsaddr_list[1].sin_port = htons(53);
            inet_pton(AF_INET, "8.8.8.8", &statp->nsaddr_list[1].sin_addr);
        }
        statp->retrans = 1;
        statp->retry = 2;
    }
    return ret;
}

int res_nquery(res_state statp, const char *dname, int class, int type,
               unsigned char *answer, int anslen) {
    static int (*orig_res_nquery)(res_state, const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_nquery) orig_res_nquery = (int (*)(res_state, const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_nquery");
    // DNS SRV queries are optional for APT mirrors and frequently cause delays or issues
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_nquery ? orig_res_nquery(statp, dname, class, type, answer, anslen) : -1;
}

int res_query(const char *dname, int class, int type,
              unsigned char *answer, int anslen) {
    static int (*orig_res_query)(const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_query) orig_res_query = (int (*)(const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_query");
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_query ? orig_res_query(dname, class, type, answer, anslen) : -1;
}

int res_nsearch(res_state statp, const char *dname, int class, int type,
                unsigned char *answer, int anslen) {
    static int (*orig_res_nsearch)(res_state, const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_nsearch) orig_res_nsearch = (int (*)(res_state, const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_nsearch");
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_nsearch ? orig_res_nsearch(statp, dname, class, type, answer, anslen) : -1;
}

int res_search(const char *dname, int class, int type,
               unsigned char *answer, int anslen) {
    static int (*orig_res_search)(const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_search) orig_res_search = (int (*)(const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_search");
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_search ? orig_res_search(dname, class, type, answer, anslen) : -1;
}

static struct hostent s_fallback_hostent;
static char *s_fallback_aliases[1] = { NULL };
static in_addr_t s_fallback_addr1;
static in_addr_t s_fallback_addr2;
static char *s_fallback_addr_list[3] = { NULL, NULL, NULL };

static struct hostent *get_fallback_hostent(const char *name) {
    if (!name) return NULL;
    if (strstr(name, "ubuntu.com") != NULL) {
        inet_pton(AF_INET, "91.189.91.103", &s_fallback_addr1);
        inet_pton(AF_INET, "91.189.92.21", &s_fallback_addr2);
    } else if (strstr(name, "debian.org") != NULL) {
        inet_pton(AF_INET, "151.101.130.132", &s_fallback_addr1);
        inet_pton(AF_INET, "151.101.2.132", &s_fallback_addr2);
    } else {
        return NULL;
    }
    s_fallback_addr_list[0] = (char *)&s_fallback_addr1;
    s_fallback_addr_list[1] = (char *)&s_fallback_addr2;
    s_fallback_addr_list[2] = NULL;

    s_fallback_hostent.h_name = (char *)name;
    s_fallback_hostent.h_aliases = s_fallback_aliases;
    s_fallback_hostent.h_addrtype = AF_INET;
    s_fallback_hostent.h_length = sizeof(in_addr_t);
    s_fallback_hostent.h_addr_list = s_fallback_addr_list;
    return &s_fallback_hostent;
}

struct hostent *gethostbyname(const char *name) {
    static struct hostent *(*orig_gethostbyname)(const char *) = NULL;
    if (!orig_gethostbyname) orig_gethostbyname = (struct hostent *(*)(const char *))dlsym(RTLD_NEXT, "gethostbyname");
    struct hostent *ret = orig_gethostbyname ? orig_gethostbyname(name) : NULL;
    if (!ret && name && (strstr(name, "ubuntu.com") || strstr(name, "debian.org"))) {
        return get_fallback_hostent(name);
    }
    return ret;
}

struct hostent *gethostbyname2(const char *name, int af) {
    static struct hostent *(*orig_gethostbyname2)(const char *, int) = NULL;
    if (!orig_gethostbyname2) orig_gethostbyname2 = (struct hostent *(*)(const char *, int))dlsym(RTLD_NEXT, "gethostbyname2");
    struct hostent *ret = orig_gethostbyname2 ? orig_gethostbyname2(name, af) : NULL;
    if (!ret && (af == AF_INET || af == AF_UNSPEC) && name && (strstr(name, "ubuntu.com") || strstr(name, "debian.org"))) {
        return get_fallback_hostent(name);
    }
    return ret;
}

int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*orig_bind)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_bind) orig_bind = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "bind");

    if (addr && addrlen >= sizeof(sa_family_t) && addr->sa_family == AF_UNIX) {
        const struct sockaddr_un *sun = (const struct sockaddr_un *)addr;
        if (sun->sun_path[0] != '\0') {
            struct sockaddr_un mod_sun;
            memset(&mod_sun, 0, sizeof(mod_sun));
            mod_sun.sun_family = AF_UNIX;
            rewrite_unix_socket_path(sun->sun_path, mod_sun.sun_path, sizeof(mod_sun.sun_path));
            return orig_bind ? orig_bind(sockfd, (struct sockaddr *)&mod_sun, sizeof(mod_sun)) : -1;
        }
    }
    return orig_bind ? orig_bind(sockfd, addr, addrlen) : -1;
}

int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*orig_connect)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_connect) orig_connect = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "connect");

    if (addr && addrlen >= sizeof(sa_family_t) && addr->sa_family == AF_UNIX) {
        const struct sockaddr_un *sun = (const struct sockaddr_un *)addr;
        if (sun->sun_path[0] != '\0') {
            struct sockaddr_un mod_sun;
            memset(&mod_sun, 0, sizeof(mod_sun));
            mod_sun.sun_family = AF_UNIX;
            rewrite_unix_socket_path(sun->sun_path, mod_sun.sun_path, sizeof(mod_sun.sun_path));
            return orig_connect ? orig_connect(sockfd, (struct sockaddr *)&mod_sun, sizeof(mod_sun)) : -1;
        }
    }

    if (addr && is_loopback_dns(addr, addrlen)) {
        struct sockaddr_in redirected;
        memcpy(&redirected, addr, sizeof(redirected));
        redirected.sin_addr.s_addr = get_primary_dns();
        int ret = orig_connect ? orig_connect(sockfd, (struct sockaddr *)&redirected, sizeof(redirected)) : -1;
        if (ret == 0) {
            record_dns_redirect(sockfd, ((const struct sockaddr_in *)addr)->sin_addr.s_addr, ((const struct sockaddr_in *)addr)->sin_port);
        }
        return ret;
    }
    return orig_connect ? orig_connect(sockfd, addr, addrlen) : -1;
}

ssize_t sendto(int sockfd, const void *buf, size_t len, int flags,
               const struct sockaddr *dest_addr, socklen_t addrlen) {
    static ssize_t (*orig_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_sendto) orig_sendto = (ssize_t (*)(int, const void *, size_t, int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "sendto");

    if (dest_addr && is_loopback_dns(dest_addr, addrlen)) {
        struct sockaddr_in redirected;
        memcpy(&redirected, dest_addr, sizeof(redirected));
        redirected.sin_addr.s_addr = get_primary_dns();
        record_dns_redirect(sockfd, ((const struct sockaddr_in *)dest_addr)->sin_addr.s_addr, ((const struct sockaddr_in *)dest_addr)->sin_port);
        return orig_sendto ? orig_sendto(sockfd, buf, len, flags, (struct sockaddr *)&redirected, sizeof(redirected)) : -1;
    }
    return orig_sendto ? orig_sendto(sockfd, buf, len, flags, dest_addr, addrlen) : -1;
}

ssize_t sendmsg(int sockfd, const struct msghdr *msg, int flags) {
    static ssize_t (*orig_sendmsg)(int, const struct msghdr *, int) = NULL;
    if (!orig_sendmsg) orig_sendmsg = (ssize_t (*)(int, const struct msghdr *, int))dlsym(RTLD_NEXT, "sendmsg");

    if (msg && msg->msg_name && is_loopback_dns((const struct sockaddr *)msg->msg_name, msg->msg_namelen)) {
        struct sockaddr_in redirected;
        memcpy(&redirected, msg->msg_name, sizeof(redirected));
        redirected.sin_addr.s_addr = get_primary_dns();
        struct msghdr mod_msg;
        memcpy(&mod_msg, msg, sizeof(mod_msg));
        mod_msg.msg_name = &redirected;
        record_dns_redirect(sockfd, ((const struct sockaddr_in *)msg->msg_name)->sin_addr.s_addr, ((const struct sockaddr_in *)msg->msg_name)->sin_port);
        return orig_sendmsg ? orig_sendmsg(sockfd, &mod_msg, flags) : -1;
    }
    return orig_sendmsg ? orig_sendmsg(sockfd, msg, flags) : -1;
}

ssize_t recvfrom(int sockfd, void *buf, size_t len, int flags,
                 struct sockaddr *src_addr, socklen_t *addrlen) {
    static ssize_t (*orig_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *) = NULL;
    if (!orig_recvfrom) orig_recvfrom = (ssize_t (*)(int, void *, size_t, int, struct sockaddr *, socklen_t *))dlsym(RTLD_NEXT, "recvfrom");

    ssize_t ret = orig_recvfrom ? orig_recvfrom(sockfd, buf, len, flags, src_addr, addrlen) : -1;
    if (ret > 0 && src_addr && addrlen && *addrlen >= sizeof(struct sockaddr_in)) {
        if (src_addr->sa_family == AF_INET) {
            struct sockaddr_in *sin = (struct sockaddr_in *)src_addr;
            if (sin->sin_port == htons(53)) {
                in_addr_t orig_ip;
                in_port_t orig_port;
                if (get_dns_redirect(sockfd, &orig_ip, &orig_port)) {
                    sin->sin_addr.s_addr = orig_ip;
                    sin->sin_port = orig_port;
                }
            }
        }
    }
    return ret;
}

ssize_t recvmsg(int sockfd, struct msghdr *msg, int flags) {
    static ssize_t (*orig_recvmsg)(int, struct msghdr *, int) = NULL;
    if (!orig_recvmsg) orig_recvmsg = (ssize_t (*)(int, struct msghdr *, int))dlsym(RTLD_NEXT, "recvmsg");

    ssize_t ret = orig_recvmsg ? orig_recvmsg(sockfd, msg, flags) : -1;
    if (ret > 0 && msg && msg->msg_name && msg->msg_namelen >= sizeof(struct sockaddr_in)) {
        struct sockaddr_in *sin = (struct sockaddr_in *)msg->msg_name;
        if (sin->sin_family == AF_INET && sin->sin_port == htons(53)) {
            in_addr_t orig_ip;
            in_port_t orig_port;
            if (get_dns_redirect(sockfd, &orig_ip, &orig_port)) {
                sin->sin_addr.s_addr = orig_ip;
                sin->sin_port = orig_port;
            }
        }
    }
    return ret;
}

int getpeername(int sockfd, struct sockaddr *addr, socklen_t *addrlen) {
    static int (*orig_getpeername)(int, struct sockaddr *, socklen_t *) = NULL;
    if (!orig_getpeername) orig_getpeername = (int (*)(int, struct sockaddr *, socklen_t *))dlsym(RTLD_NEXT, "getpeername");

    int ret = orig_getpeername ? orig_getpeername(sockfd, addr, addrlen) : -1;
    if (ret == 0 && addr && addrlen && *addrlen >= sizeof(struct sockaddr_in)) {
        if (addr->sa_family == AF_INET) {
            struct sockaddr_in *sin = (struct sockaddr_in *)addr;
            if (sin->sin_port == htons(53)) {
                in_addr_t orig_ip;
                in_port_t orig_port;
                if (get_dns_redirect(sockfd, &orig_ip, &orig_port)) {
                    sin->sin_addr.s_addr = orig_ip;
                    sin->sin_port = orig_port;
                }
            }
        }
    }
    return ret;
}

#ifndef AF_NETLINK
#define AF_NETLINK 16
#endif
#ifndef NETLINK_AUDIT
#define NETLINK_AUDIT 9
#endif

int socket(int domain, int type, int protocol) {
    static int (*orig_socket)(int, int, int) = NULL;
    if (!orig_socket) orig_socket = (int (*)(int, int, int))dlsym(RTLD_NEXT, "socket");
    if (domain == AF_NETLINK && protocol == NETLINK_AUDIT) {
        errno = EPROTONOSUPPORT;
        return -1;
    }
    return orig_socket ? orig_socket(domain, type, protocol) : -1;
}

#endif /* !CORTEX_HOST_TEST */
