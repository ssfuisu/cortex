#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <resolv.h>
#include <utime.h>
#include <sys/time.h>

// Intercept SECCOMP blocked syscalls (SIGSYS) and return -ENOSYS so glibc falls back gracefully
static void cortex_sigsys_handler(int sig, siginfo_t *info, void *ctx) {
    (void)sig;
    (void)info;
    if (!ctx) return;
    ucontext_t *uctx = (ucontext_t *)ctx;
#if defined(__aarch64__)
    uctx->uc_mcontext.regs[0] = -ENOSYS;
#elif defined(__arm__)
    uctx->uc_mcontext.arm_r0 = -ENOSYS;
#elif defined(__x86_64__) && defined(REG_RAX)
    uctx->uc_mcontext.gregs[REG_RAX] = -ENOSYS;
#elif defined(__i386__) && defined(REG_EAX)
    uctx->uc_mcontext.gregs[REG_EAX] = -ENOSYS;
#endif
}

static int (*get_real_sigaction(void))(int, const struct sigaction *, struct sigaction *) {
    static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *) = NULL;
    if (!real_sigaction) {
        real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
    }
    return real_sigaction;
}

__attribute__((constructor(101))) static void install_sigsys_handler(void) {
    int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!real_sig) return;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = cortex_sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_RESTART;
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
            oldact->sa_flags = SA_SIGINFO | SA_NODEFER | SA_RESTART;
        }
        return 0;
    }
    return real_sig(signum, act, oldact);
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

static char g_cortex_root[PATH_MAX] = {0};
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
    g_initialized = 1;
}

static inline int is_path_prefix(const char *path, const char *prefix, size_t prefix_len) {
    if (strncmp(path, prefix, prefix_len) != 0) return 0;
    return path[prefix_len] == '/' || path[prefix_len] == '\0';
}

static const char *rewrite_path(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    init_cortex_hook();

    if (g_cortex_root[0] == '\0') {
        return path;
    }

    while (path[0] == '/' && path[1] == '/') {
        path++;
    }

    if (strncmp(path, g_cortex_root, strlen(g_cortex_root)) == 0 ||
        strncmp(path, "/proc", 5) == 0 ||
        strncmp(path, "/dev", 4) == 0 ||
        strncmp(path, "/sys", 4) == 0 ||
        strncmp(path, "/system", 7) == 0 ||
        strncmp(path, "/data", 5) == 0 ||
        strncmp(path, "/sdcard", 7) == 0 ||
        strncmp(path, "/storage", 8) == 0) {
        return path;
    }

    if (strcmp(path, "/") == 0) {
        snprintf(buffer, bufsize, "%s", g_cortex_root);
        return buffer;
    }

    if (is_path_prefix(path, "/usr", 4) ||
        is_path_prefix(path, "/bin", 4) ||
        is_path_prefix(path, "/sbin", 5) ||
        is_path_prefix(path, "/lib", 4) ||
        is_path_prefix(path, "/lib64", 6) ||
        is_path_prefix(path, "/etc", 4) ||
        is_path_prefix(path, "/var", 4) ||
        is_path_prefix(path, "/opt", 4) ||
        is_path_prefix(path, "/tmp", 4) ||
        is_path_prefix(path, "/root", 5) ||
        is_path_prefix(path, "/home", 5) ||
        is_path_prefix(path, "/run", 4) ||
        is_path_prefix(path, "/srv", 4) ||
        is_path_prefix(path, "/mnt", 4)) {
        
        snprintf(buffer, bufsize, "%s%s", g_cortex_root, path);
        return buffer;
    }

    return path;
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

// Fortified open variants used by GNU tar and other coreutils compiled with _FORTIFY_SOURCE=2
int __open_2(const char *pathname, int flags) {
    return open(pathname, flags);
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    return openat(dirfd, pathname, flags);
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

// Hook stat
int stat(const char *pathname, struct stat *statbuf) {
    static int (*orig_stat)(const char *, struct stat *) = NULL;
    if (!orig_stat) orig_stat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "stat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_stat(target, statbuf);
}

// Hook lstat
int lstat(const char *pathname, struct stat *statbuf) {
    static int (*orig_lstat)(const char *, struct stat *) = NULL;
    if (!orig_lstat) orig_lstat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "lstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_lstat(target, statbuf);
}

// Hook fstatat
int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_fstatat(dirfd, target, statbuf, flags);
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

// Hook access
int access(const char *pathname, int mode) {
    static int (*orig_access)(const char *, int) = NULL;
    if (!orig_access) orig_access = (int (*)(const char *, int))dlsym(RTLD_NEXT, "access");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_access ? orig_access(target, mode) : -1;
}

// Hook faccessat
int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    static int (*orig_faccessat)(int, const char *, int, int) = NULL;
    if (!orig_faccessat) orig_faccessat = (int (*)(int, const char *, int, int))dlsym(RTLD_NEXT, "faccessat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_faccessat ? orig_faccessat(dirfd, target, mode, flags) : -1;
}

int faccessat2(int dirfd, const char *pathname, int mode, int flags) {
    return faccessat(dirfd, pathname, mode, flags);
}

// Hook chmod
int chmod(const char *pathname, mode_t mode) {
    static int (*orig_chmod)(const char *, mode_t) = NULL;
    if (!orig_chmod) orig_chmod = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "chmod");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_chmod(target, mode);
}

// Hook fchmodat
int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    static int (*orig_fchmodat)(int, const char *, mode_t, int) = NULL;
    if (!orig_fchmodat) orig_fchmodat = (int (*)(int, const char *, mode_t, int))dlsym(RTLD_NEXT, "fchmodat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_fchmodat(dirfd, target, mode, flags);
}

// Hook unlink
int unlink(const char *pathname) {
    static int (*orig_unlink)(const char *) = NULL;
    if (!orig_unlink) orig_unlink = (int (*)(const char *))dlsym(RTLD_NEXT, "unlink");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_unlink(target);
}

// Hook unlinkat
int unlinkat(int dirfd, const char *pathname, int flags) {
    static int (*orig_unlinkat)(int, const char *, int) = NULL;
    if (!orig_unlinkat) orig_unlinkat = (int (*)(int, const char *, int))dlsym(RTLD_NEXT, "unlinkat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_unlinkat(dirfd, target, flags);
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
    const char *target_old = (oldpath[0] == '/') ? rewrite_path(oldpath, buf1, sizeof(buf1)) : oldpath;
    const char *target_new = (newpath[0] == '/') ? rewrite_path(newpath, buf2, sizeof(buf2)) : newpath;
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
    const char *target_old = (oldpath[0] == '/') ? rewrite_path(oldpath, buf1, sizeof(buf1)) : oldpath;
    const char *target_new = (newpath[0] == '/') ? rewrite_path(newpath, buf2, sizeof(buf2)) : newpath;
    return orig_renameat2(olddirfd, target_old, newdirfd, target_new, flags);
}

// Hook readlink
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    static ssize_t (*orig_readlink)(const char *, char *, size_t) = NULL;
    if (!orig_readlink) orig_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(pathname, pbuf, sizeof(pbuf));
    ssize_t n = orig_readlink ? orig_readlink(target, buf, bufsiz) : -1;
    if (n > 0 && g_cortex_root[0] != '\0') {
        size_t root_len = strlen(g_cortex_root);
        if ((size_t)n >= root_len && strncmp(buf, g_cortex_root, root_len) == 0 &&
            ((size_t)n == root_len || buf[root_len] == '/')) {
            size_t new_len = (size_t)n - root_len;
            if (new_len == 0) {
                buf[0] = '/';
                new_len = 1;
            } else {
                memmove(buf, buf + root_len, new_len);
            }
            n = (ssize_t)new_len;
        }
    }
    return n;
}

// Hook readlinkat
ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    static ssize_t (*orig_readlinkat)(int, const char *, char *, size_t) = NULL;
    if (!orig_readlinkat) orig_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
    char pbuf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, pbuf, sizeof(pbuf)) : pathname;
    ssize_t n = orig_readlinkat ? orig_readlinkat(dirfd, target, buf, bufsiz) : -1;
    if (n > 0 && g_cortex_root[0] != '\0') {
        size_t root_len = strlen(g_cortex_root);
        if ((size_t)n >= root_len && strncmp(buf, g_cortex_root, root_len) == 0 &&
            ((size_t)n == root_len || buf[root_len] == '/')) {
            size_t new_len = (size_t)n - root_len;
            if (new_len == 0) {
                buf[0] = '/';
                new_len = 1;
            } else {
                memmove(buf, buf + root_len, new_len);
            }
            n = (ssize_t)new_len;
        }
    }
    return n;
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

// Hook getcwd
char *getcwd(char *buf, size_t size) {
    static char *(*orig_getcwd)(char *, size_t) = NULL;
    if (!orig_getcwd) orig_getcwd = (char *(*)(char *, size_t))dlsym(RTLD_NEXT, "getcwd");
    init_cortex_hook();
    char temp[PATH_MAX];
    char *res = orig_getcwd ? orig_getcwd(temp, sizeof(temp)) : NULL;
    if (!res) return NULL;

    size_t root_len = strlen(g_cortex_root);
    const char *final_path = temp;
    if (root_len > 0 && strncmp(temp, g_cortex_root, root_len) == 0) {
        if (temp[root_len] == '\0') {
            final_path = "/";
        } else if (temp[root_len] == '/') {
            final_path = temp + root_len;
        }
    }

    size_t len = strlen(final_path);
    if (!buf) {
        size_t alloc_size = (size > len + 1) ? size : (len + 1);
        char *allocated = (char *)malloc(alloc_size);
        if (!allocated) {
            errno = ENOMEM;
            return NULL;
        }
        memcpy(allocated, final_path, len + 1);
        return allocated;
    } else {
        if (size < len + 1) {
            errno = ERANGE;
            return NULL;
        }
        memcpy(buf, final_path, len + 1);
        return buf;
    }
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
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    char resolved_temp[PATH_MAX];
    char *res = orig_realpath ? orig_realpath(target, resolved_temp) : NULL;
    if (!res) return NULL;

    size_t root_len = strlen(g_cortex_root);
    const char *final_path = resolved_temp;
    if (root_len > 0 && strncmp(resolved_temp, g_cortex_root, root_len) == 0) {
        if (resolved_temp[root_len] == '\0') {
            final_path = "/";
        } else if (resolved_temp[root_len] == '/') {
            final_path = resolved_temp + root_len;
        }
    }

    size_t len = strlen(final_path);
    if (!resolved_path) {
        char *allocated = (char *)malloc(len + 1);
        if (!allocated) {
            errno = ENOMEM;
            return NULL;
        }
        memcpy(allocated, final_path, len + 1);
        return allocated;
    } else {
        memcpy(resolved_path, final_path, len + 1);
        return resolved_path;
    }
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
    ".globl open64\n"      ".set open64, open\n"
    ".globl openat64\n"    ".set openat64, openat\n"
    ".globl creat64\n"     ".set creat64, creat\n"
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

int chown(const char *pathname, uid_t owner, gid_t group) { (void)pathname; (void)owner; (void)group; return 0; }
int fchown(int fd, uid_t owner, gid_t group) { (void)fd; (void)owner; (void)group; return 0; }
int lchown(const char *pathname, uid_t owner, gid_t group) { (void)pathname; (void)owner; (void)group; return 0; }
int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    (void)dirfd; (void)pathname; (void)owner; (void)group; (void)flags; return 0;
}
int chroot(const char *path) { (void)path; return 0; }

// Hook mknod / mknodat
int mknod(const char *pathname, mode_t mode, dev_t dev) {
    (void)dev;
    if (S_ISREG(mode)) {
        return creat(pathname, mode);
    }
    if (S_ISFIFO(mode)) {
        static int (*orig_mkfifo)(const char *, mode_t) = NULL;
        if (!orig_mkfifo) orig_mkfifo = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "mkfifo");
        char buf[PATH_MAX];
        const char *target = rewrite_path(pathname, buf, sizeof(buf));
        return orig_mkfifo ? orig_mkfifo(target, mode) : 0;
    }
    return 0;
}

int mkfifoat(int dirfd, const char *pathname, mode_t mode) {
    static int (*orig_mkfifoat)(int, const char *, mode_t) = NULL;
    if (!orig_mkfifoat) orig_mkfifoat = (int (*)(int, const char *, mode_t))dlsym(RTLD_NEXT, "mkfifoat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_mkfifoat ? orig_mkfifoat(dirfd, target, mode) : 0;
}

int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    (void)dev;
    if (S_ISREG(mode)) {
        return openat(dirfd, pathname, O_CREAT | O_WRONLY | O_TRUNC, mode);
    }
    if (S_ISFIFO(mode)) {
        return mkfifoat(dirfd, pathname, mode);
    }
    return 0;
}

// Hook sync / syncfs to prevent Android flash storage stalls during dpkg operations
void sync(void) {
    // No-op
}

int syncfs(int fd) {
    (void)fd;
    return 0;
}
int capget(void *hdrp, void *datap) { (void)hdrp; (void)datap; return 0; }
int capset(void *hdrp, const void *datap) { (void)hdrp; (void)datap; return 0; }
int prctl(int option, ...) {
    va_list ap;
    va_start(ap, option);
    unsigned long arg2 = va_arg(ap, unsigned long);
    unsigned long arg3 = va_arg(ap, unsigned long);
    unsigned long arg4 = va_arg(ap, unsigned long);
    unsigned long arg5 = va_arg(ap, unsigned long);
    va_end(ap);

    if (option == PR_SET_NO_NEW_PRIVS) {
        return 0;
    }
#ifdef PR_SET_SECCOMP
    if (option == PR_SET_SECCOMP) {
        return 0;
    }
#endif
    static int (*orig_prctl)(int, unsigned long, unsigned long, unsigned long, unsigned long) = NULL;
    if (!orig_prctl) orig_prctl = (int (*)(int, unsigned long, unsigned long, unsigned long, unsigned long))dlsym(RTLD_NEXT, "prctl");
    return orig_prctl ? orig_prctl(option, arg2, arg3, arg4, arg5) : 0;
}
int seccomp(unsigned int operation, unsigned int flags, void *args) {
    (void)operation;
    (void)flags;
    (void)args;
    return 0;
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
    char **new_env = calloc(count + 1, sizeof(char *));
    int dst = 0;
    for (int i = 0; i < count; i++) {
        if (strncmp(envp[i], "LD_PRELOAD=", 11) != 0 &&
            strncmp(envp[i], "LD_LIBRARY_PATH=", 16) != 0) {
            new_env[dst++] = envp[i];
        }
    }
    new_env[dst] = NULL;
    return new_env;
}

static char **prepare_cortex_env(char *const envp[]) {
    init_cortex_hook();
    int count = 0;
    int has_preload = 0;
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

    while (envp && envp[count]) {
        if (strncmp(envp[count], "LD_PRELOAD=", 11) == 0) {
            has_preload = 1;
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

    char **new_env = calloc(count + 16, sizeof(char *));
    int dst = 0;
    for (int i = 0; i < count; i++) {
        new_env[dst++] = envp[i];
    }

    if (!has_preload && hook_path[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "LD_PRELOAD=%s", hook_path);
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
        new_env[dst++] = "GLIBC_TUNABLES=glibc.pthread.rseq=0";
    }
    if (!has_path) {
        new_env[dst++] = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    }
    if (!has_tmp && g_cortex_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "TMPDIR=%s/tmp", g_cortex_root);
            new_env[dst++] = str;
        }
    }
    if (!has_threads_max) {
        new_env[dst++] = "DPKG_DEB_THREADS_MAX=1";
    }
    if (!has_xz_opt) {
        new_env[dst++] = "XZ_OPT=-T1";
    }
    if (!has_xz_defaults) {
        new_env[dst++] = "XZ_DEFAULTS=-T1";
    }
    if (!has_frontend) {
        new_env[dst++] = "DEBIAN_FRONTEND=noninteractive";
    }
    if (!has_debconf_frontend) {
        new_env[dst++] = "DEBCONF_FRONTEND=noninteractive";
    }
    if (!has_debconf_seen) {
        new_env[dst++] = "DEBCONF_NONINTERACTIVE_SEEN=true";
    }
    new_env[dst] = NULL;
    return new_env;
}

// Hook execve
typedef int (*orig_execve_f_type)(const char *filename, char *const argv[], char *const envp[]);
int execve(const char *filename, char *const argv[], char *const envp[]) {
    static orig_execve_f_type orig_execve = NULL;
    if (!orig_execve) orig_execve = (orig_execve_f_type)dlsym(RTLD_NEXT, "execve");

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));

    init_cortex_hook();

    // Only intercept binaries/scripts within CORTEX_ROOT
    if (g_cortex_root[0] != '\0' && strncmp(target, g_cortex_root, strlen(g_cortex_root)) == 0) {
        int fd = open(target, O_RDONLY);
        if (fd >= 0) {
            char hdr[256];
            ssize_t n = read(fd, hdr, sizeof(hdr) - 1);
            close(fd);

            // 1. Transparently route glibc ELF binaries through ld.so
            if (n >= 4 && (unsigned char)hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                char ld_so[PATH_MAX] = {0};
                #if defined(__aarch64__)
                snprintf(ld_so, sizeof(ld_so), "%s/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", g_cortex_root);
                if (access(ld_so, F_OK) != 0) {
                    snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-aarch64.so.1", g_cortex_root);
                }
                if (access(ld_so, F_OK) != 0) {
                    snprintf(ld_so, sizeof(ld_so), "%s/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", g_cortex_root);
                }
                #elif defined(__arm__)
                snprintf(ld_so, sizeof(ld_so), "%s/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3", g_cortex_root);
                if (access(ld_so, F_OK) != 0) {
                    snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-armhf.so.3", g_cortex_root);
                }
                if (access(ld_so, F_OK) != 0) {
                    snprintf(ld_so, sizeof(ld_so), "%s/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3", g_cortex_root);
                }
                #else
                snprintf(ld_so, sizeof(ld_so), "%s/lib64/ld-linux-x86-64.so.2", g_cortex_root);
                #endif

                if (access(ld_so, F_OK) == 0 && strcmp(target, ld_so) != 0) {
                    chmod(ld_so, 0755);
                    chmod(target, 0755);

                    char *const *arg_ptr = argv;
                    int argc = 0;
                    while (arg_ptr && *arg_ptr) {
                        argc++;
                        arg_ptr++;
                    }

                    const char *prog_name = strrchr(target, '/');
                    prog_name = (prog_name != NULL) ? prog_name + 1 : target;

                    char **new_argv = (char **)calloc(argc + 5, sizeof(char *));
                    new_argv[0] = ld_so;
                    new_argv[1] = (char *)"--argv0";
                    new_argv[2] = (char *)((argc > 0 && argv[0]) ? argv[0] : prog_name);
                    new_argv[3] = (char *)target;
                    for (int i = 1; i < argc; i++) {
                        new_argv[i + 3] = argv[i];
                    }
                    new_argv[argc + 3] = NULL;
                    return orig_execve(ld_so, new_argv, prepare_cortex_env(envp));
                }
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

                if (strncmp(rewritten_interp, "/system", 7) == 0) {
                    char **sys_env = clean_env_for_system(envp);
                    return orig_execve(rewritten_interp, new_argv, sys_env);
                }
                return execve(rewritten_interp, new_argv, prepare_cortex_env(envp));
            }
        }
    } else {
        if (strncmp(target, "/system", 7) == 0) {
            char **sys_env = clean_env_for_system(envp);
            return orig_execve(target, argv, sys_env);
        }
    }

    return orig_execve(target, argv, prepare_cortex_env(envp));
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
    char ld_so[PATH_MAX] = {0};
#if defined(__aarch64__)
    snprintf(ld_so, sizeof(ld_so), "%s/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", g_cortex_root);
    if (access(ld_so, F_OK) != 0) snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-aarch64.so.1", g_cortex_root);
    if (access(ld_so, F_OK) != 0) snprintf(ld_so, sizeof(ld_so), "%s/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", g_cortex_root);
#elif defined(__arm__)
    snprintf(ld_so, sizeof(ld_so), "%s/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3", g_cortex_root);
    if (access(ld_so, F_OK) != 0) snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-armhf.so.3", g_cortex_root);
    if (access(ld_so, F_OK) != 0) snprintf(ld_so, sizeof(ld_so), "%s/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3", g_cortex_root);
#else
    snprintf(ld_so, sizeof(ld_so), "%s/lib64/ld-linux-x86-64.so.2", g_cortex_root);
#endif

    char **new_envp = prepare_cortex_env(envp ? envp : environ);

    int is_elf = 0;
    if (g_cortex_root[0] != '\0' && strncmp(target, g_cortex_root, strlen(g_cortex_root)) == 0) {
        int fd = open(target, O_RDONLY);
        if (fd >= 0) {
            char hdr[4];
            ssize_t n = read(fd, hdr, sizeof(hdr));
            close(fd);
            if (n >= 4 && (unsigned char)hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                is_elf = 1;
            }
        }
    }

    int ret = -1;
    if (is_elf && access(ld_so, F_OK) == 0 && strcmp(target, ld_so) != 0) {
        chmod(ld_so, 0755);
        chmod(target, 0755);

        int argc = 0;
        while (argv && argv[argc]) argc++;

        const char *prog_name = strrchr(target, '/');
        prog_name = (prog_name != NULL) ? prog_name + 1 : target;

        char **new_argv = (char **)calloc(argc + 5, sizeof(char *));
        if (new_argv) {
            new_argv[0] = ld_so;
            new_argv[1] = (char *)"--argv0";
            new_argv[2] = (char *)((argc > 0 && argv && argv[0]) ? argv[0] : prog_name);
            new_argv[3] = (char *)target;
            for (int i = 1; i < argc; i++) {
                new_argv[i + 3] = argv[i];
            }
            new_argv[argc + 3] = NULL;

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
            return errno;
        } else if (child == 0) {
            execve(target, argv, new_envp);
            _exit(127);
        } else {
            if (pid) *pid = child;
            return 0;
        }
    }
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
    return posix_spawn(pid, file, file_actions, attrp, argv, envp);
}

// DNS resolution hooking and localhost DNS redirect
static in_addr_t get_primary_dns(void) {
    static in_addr_t primary_dns = 0;
    if (primary_dns != 0) return primary_dns;

    init_cortex_hook();
    char resolv_path[PATH_MAX];
    if (g_cortex_root[0] != '\0') {
        snprintf(resolv_path, sizeof(resolv_path), "%s/etc/resolv.conf", g_cortex_root);
    } else {
        snprintf(resolv_path, sizeof(resolv_path), "/etc/resolv.conf");
    }

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
                    primary_dns = a.s_addr;
                    break;
                }
            }
        }
        fclose(f);
    }
    if (primary_dns == 0) {
        primary_dns = inet_addr("8.8.8.8");
    }
    return primary_dns;
}


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

    ai->ai_family = AF_INET;
    ai->ai_socktype = socktype ? socktype : SOCK_STREAM;
    ai->ai_protocol = protocol ? protocol : IPPROTO_TCP;
    ai->ai_addrlen = sizeof(struct sockaddr_in);
    ai->ai_addr = (struct sockaddr *)sa;
    ai->ai_canonname = node ? strdup(node) : NULL;
    ai->ai_next = NULL;
    return ai;
}

static int synthesize_fallback_addrinfo(const char *node, const char *service,
                                        const struct addrinfo *hints,
                                        struct addrinfo **res) {
    if (!node || !res) return EAI_NONAME;

    int is_debian = (strstr(node, "debian.org") != NULL);
    if (!is_debian) return EAI_NONAME;

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

    struct addrinfo *ai1 = alloc_one_addrinfo(node, "151.101.130.132", port, socktype, protocol);
    if (!ai1) return EAI_MEMORY;
    struct addrinfo *ai2 = alloc_one_addrinfo(node, "151.101.2.132", port, socktype, protocol);
    if (ai2) {
        ai1->ai_next = ai2;
    }
    *res = ai1;
    return 0;
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
        if (node && strstr(node, "debian.org")) {
            ret = synthesize_fallback_addrinfo(node, service, hints, res);
        }
    }
    return ret;
}

int res_init(void) {
    static int (*orig_res_init)(void) = NULL;
    if (!orig_res_init) orig_res_init = (int (*)(void))dlsym(RTLD_NEXT, "res_init");
    int ret = orig_res_init ? orig_res_init() : 0;
    res_state statp = __res_state();
    if (statp) {
        if (statp->nscount <= 0 || (statp->nscount == 1 && statp->nsaddr_list[0].sin_addr.s_addr == htonl(INADDR_LOOPBACK))) {
            statp->nscount = 2;
            statp->nsaddr_list[0].sin_family = AF_INET;
            statp->nsaddr_list[0].sin_port = htons(53);
            inet_pton(AF_INET, "8.8.8.8", &statp->nsaddr_list[0].sin_addr);
            statp->nsaddr_list[1].sin_family = AF_INET;
            statp->nsaddr_list[1].sin_port = htons(53);
            inet_pton(AF_INET, "1.1.1.1", &statp->nsaddr_list[1].sin_addr);
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
        if (statp->nscount <= 0 || (statp->nscount == 1 && statp->nsaddr_list[0].sin_addr.s_addr == htonl(INADDR_LOOPBACK))) {
            statp->nscount = 2;
            statp->nsaddr_list[0].sin_family = AF_INET;
            statp->nsaddr_list[0].sin_port = htons(53);
            inet_pton(AF_INET, "8.8.8.8", &statp->nsaddr_list[0].sin_addr);
            statp->nsaddr_list[1].sin_family = AF_INET;
            statp->nsaddr_list[1].sin_port = htons(53);
            inet_pton(AF_INET, "1.1.1.1", &statp->nsaddr_list[1].sin_addr);
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
    if (!name || strstr(name, "debian.org") == NULL) return NULL;
    inet_pton(AF_INET, "151.101.130.132", &s_fallback_addr1);
    inet_pton(AF_INET, "151.101.2.132", &s_fallback_addr2);
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
    if (!ret && name && strstr(name, "debian.org")) {
        return get_fallback_hostent(name);
    }
    return ret;
}

struct hostent *gethostbyname2(const char *name, int af) {
    static struct hostent *(*orig_gethostbyname2)(const char *, int) = NULL;
    if (!orig_gethostbyname2) orig_gethostbyname2 = (struct hostent *(*)(const char *, int))dlsym(RTLD_NEXT, "gethostbyname2");
    struct hostent *ret = orig_gethostbyname2 ? orig_gethostbyname2(name, af) : NULL;
    if (!ret && (af == AF_INET || af == AF_UNSPEC) && name && strstr(name, "debian.org")) {
        return get_fallback_hostent(name);
    }
    return ret;
}

int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*orig_bind)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_bind) orig_bind = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "bind");

    if (addr && addrlen >= sizeof(sa_family_t) && addr->sa_family == AF_UNIX) {
        const struct sockaddr_un *sun = (const struct sockaddr_un *)addr;
        if (sun->sun_path[0] == '/') {
            struct sockaddr_un mod_sun;
            memset(&mod_sun, 0, sizeof(mod_sun));
            mod_sun.sun_family = AF_UNIX;
            rewrite_path(sun->sun_path, mod_sun.sun_path, sizeof(mod_sun.sun_path));
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
        if (sun->sun_path[0] == '/') {
            struct sockaddr_un mod_sun;
            memset(&mod_sun, 0, sizeof(mod_sun));
            mod_sun.sun_family = AF_UNIX;
            rewrite_path(sun->sun_path, mod_sun.sun_path, sizeof(mod_sun.sun_path));
            return orig_connect ? orig_connect(sockfd, (struct sockaddr *)&mod_sun, sizeof(mod_sun)) : -1;
        }
    }

    if (addr && addr->sa_family == AF_INET && addrlen >= sizeof(struct sockaddr_in)) {
        struct sockaddr_in *sin = (struct sockaddr_in *)addr;
        if (sin->sin_port == htons(53) && sin->sin_addr.s_addr == htonl(INADDR_LOOPBACK)) {
            struct sockaddr_in redirected;
            memcpy(&redirected, sin, sizeof(redirected));
            redirected.sin_addr.s_addr = get_primary_dns();
            return orig_connect ? orig_connect(sockfd, (struct sockaddr *)&redirected, sizeof(redirected)) : -1;
        }
    }
    return orig_connect ? orig_connect(sockfd, addr, addrlen) : -1;
}

ssize_t sendto(int sockfd, const void *buf, size_t len, int flags,
               const struct sockaddr *dest_addr, socklen_t addrlen) {
    static ssize_t (*orig_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_sendto) orig_sendto = (ssize_t (*)(int, const void *, size_t, int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "sendto");

    if (dest_addr && dest_addr->sa_family == AF_INET && addrlen >= sizeof(struct sockaddr_in)) {
        struct sockaddr_in *sin = (struct sockaddr_in *)dest_addr;
        if (sin->sin_port == htons(53) && sin->sin_addr.s_addr == htonl(INADDR_LOOPBACK)) {
            struct sockaddr_in redirected;
            memcpy(&redirected, sin, sizeof(redirected));
            redirected.sin_addr.s_addr = get_primary_dns();
            return orig_sendto ? orig_sendto(sockfd, buf, len, flags, (struct sockaddr *)&redirected, sizeof(redirected)) : -1;
        }
    }
    return orig_sendto ? orig_sendto(sockfd, buf, len, flags, dest_addr, addrlen) : -1;
}

ssize_t sendmsg(int sockfd, const struct msghdr *msg, int flags) {
    static ssize_t (*orig_sendmsg)(int, const struct msghdr *, int) = NULL;
    if (!orig_sendmsg) orig_sendmsg = (ssize_t (*)(int, const struct msghdr *, int))dlsym(RTLD_NEXT, "sendmsg");

    if (msg && msg->msg_name && msg->msg_namelen >= sizeof(struct sockaddr_in)) {
        struct sockaddr_in *sin = (struct sockaddr_in *)msg->msg_name;
        if (sin->sin_family == AF_INET && sin->sin_port == htons(53) && sin->sin_addr.s_addr == htonl(INADDR_LOOPBACK)) {
            struct sockaddr_in redirected;
            memcpy(&redirected, sin, sizeof(redirected));
            redirected.sin_addr.s_addr = get_primary_dns();
            struct msghdr mod_msg;
            memcpy(&mod_msg, msg, sizeof(mod_msg));
            mod_msg.msg_name = &redirected;
            return orig_sendmsg ? orig_sendmsg(sockfd, &mod_msg, flags) : -1;
        }
    }
    return orig_sendmsg ? orig_sendmsg(sockfd, msg, flags) : -1;
}

