#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <limits.h>
#include <errno.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>

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

#if !defined(__LP64__)
int open64(const char *pathname, int flags, ...) {
    static int (*orig_open64)(const char *, int, ...) = NULL;
    if (!orig_open64) orig_open64 = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open64");
    if (!orig_open64) orig_open64 = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_open64(target, flags, mode);
    }
    return orig_open64(target, flags);
}
#endif

// Hook openat
int openat(int dirfd, const char *pathname, int flags, ...) {
    static int (*orig_openat)(int, const char *, int, ...) = NULL;
    if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_openat(dirfd, target, flags, mode);
    }
    return orig_openat(dirfd, target, flags);
}

#if !defined(__LP64__)
int openat64(int dirfd, const char *pathname, int flags, ...) {
    static int (*orig_openat64)(int, const char *, int, ...) = NULL;
    if (!orig_openat64) orig_openat64 = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat64");
    if (!orig_openat64) orig_openat64 = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_openat64(dirfd, target, flags, mode);
    }
    return orig_openat64(dirfd, target, flags);
}
#endif

// Hook creat
int creat(const char *pathname, mode_t mode) {
    return open(pathname, O_CREAT | O_WRONLY | O_TRUNC, mode);
}

#if !defined(__LP64__)
int creat64(const char *pathname, mode_t mode) {
    return open(pathname, O_CREAT | O_WRONLY | O_TRUNC, mode);
}
#endif

// Hook fopen
FILE *fopen(const char *pathname, const char *mode) {
    static FILE *(*orig_fopen)(const char *, const char *) = NULL;
    if (!orig_fopen) orig_fopen = (FILE *(*)(const char *, const char *))dlsym(RTLD_NEXT, "fopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_fopen(target, mode);
}

#if !defined(__LP64__)
FILE *fopen64(const char *pathname, const char *mode) {
    static FILE *(*orig_fopen64)(const char *, const char *) = NULL;
    if (!orig_fopen64) orig_fopen64 = (FILE *(*)(const char *, const char *))dlsym(RTLD_NEXT, "fopen64");
    if (!orig_fopen64) orig_fopen64 = (FILE *(*)(const char *, const char *))dlsym(RTLD_NEXT, "fopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_fopen64(target, mode);
}
#endif

// Hook freopen
FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    static FILE *(*orig_freopen)(const char *, const char *, FILE *) = NULL;
    if (!orig_freopen) orig_freopen = (FILE *(*)(const char *, const char *, FILE *))dlsym(RTLD_NEXT, "freopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_freopen(target, mode, stream);
}

#if !defined(__LP64__)
FILE *freopen64(const char *pathname, const char *mode, FILE *stream) {
    static FILE *(*orig_freopen64)(const char *, const char *, FILE *) = NULL;
    if (!orig_freopen64) orig_freopen64 = (FILE *(*)(const char *, const char *, FILE *))dlsym(RTLD_NEXT, "freopen64");
    if (!orig_freopen64) orig_freopen64 = (FILE *(*)(const char *, const char *, FILE *))dlsym(RTLD_NEXT, "freopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_freopen64(target, mode, stream);
}
#endif

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

#if !defined(__LP64__)
int stat64(const char *pathname, struct stat64 *statbuf) {
    static int (*orig_stat64)(const char *, struct stat64 *) = NULL;
    if (!orig_stat64) orig_stat64 = (int (*)(const char *, struct stat64 *))dlsym(RTLD_NEXT, "stat64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_stat64 ? orig_stat64(target, statbuf) : -1;
}
#endif

// Hook lstat
int lstat(const char *pathname, struct stat *statbuf) {
    static int (*orig_lstat)(const char *, struct stat *) = NULL;
    if (!orig_lstat) orig_lstat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "lstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_lstat(target, statbuf);
}

#if !defined(__LP64__)
int lstat64(const char *pathname, struct stat64 *statbuf) {
    static int (*orig_lstat64)(const char *, struct stat64 *) = NULL;
    if (!orig_lstat64) orig_lstat64 = (int (*)(const char *, struct stat64 *))dlsym(RTLD_NEXT, "lstat64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_lstat64 ? orig_lstat64(target, statbuf) : -1;
}
#endif

// Hook fstatat
int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_fstatat(dirfd, target, statbuf, flags);
}

#if !defined(__LP64__)
int fstatat64(int dirfd, const char *pathname, struct stat64 *statbuf, int flags) {
    static int (*orig_fstatat64)(int, const char *, struct stat64 *, int) = NULL;
    if (!orig_fstatat64) orig_fstatat64 = (int (*)(int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "fstatat64");
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_fstatat64 ? orig_fstatat64(dirfd, target, statbuf, flags) : -1;
}
#endif

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
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig___fxstatat ? orig___fxstatat(ver, dirfd, target, statbuf, flags) : fstatat(dirfd, target, statbuf, flags);
}

int __fxstatat64(int ver, int dirfd, const char *pathname, struct stat64 *statbuf, int flags) {
    static int (*orig___fxstatat64)(int, int, const char *, struct stat64 *, int) = NULL;
    if (!orig___fxstatat64) orig___fxstatat64 = (int (*)(int, int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "__fxstatat64");
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig___fxstatat64 ? orig___fxstatat64(ver, dirfd, target, statbuf, flags) : fstatat(dirfd, target, (struct stat *)statbuf, flags);
}

// Hook statx
struct statx;
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *statxbuf) {
    static int (*orig_statx)(int, const char *, int, unsigned int, struct statx *) = NULL;
    if (!orig_statx) orig_statx = (int (*)(int, const char *, int, unsigned int, struct statx *))dlsym(RTLD_NEXT, "statx");
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
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
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
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
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
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
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
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
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
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
    return orig_readlink(target, buf, bufsiz);
}

// Hook readlinkat
ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    static ssize_t (*orig_readlinkat)(int, const char *, char *, size_t) = NULL;
    if (!orig_readlinkat) orig_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
    char pbuf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, pbuf, sizeof(pbuf)) : pathname;
    return orig_readlinkat(dirfd, target, buf, bufsiz);
}

// Hook symlink
int symlink(const char *target, const char *linkpath) {
    static int (*orig_symlink)(const char *, const char *) = NULL;
    if (!orig_symlink) orig_symlink = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "symlink");
    char pbuf[PATH_MAX];
    const char *newlink = rewrite_path(linkpath, pbuf, sizeof(pbuf));
    return orig_symlink(target, newlink);
}

// Hook symlinkat
int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    static int (*orig_symlinkat)(const char *, int, const char *) = NULL;
    if (!orig_symlinkat) orig_symlinkat = (int (*)(const char *, int, const char *))dlsym(RTLD_NEXT, "symlinkat");
    char pbuf[PATH_MAX];
    const char *newlink = (linkpath[0] == '/') ? rewrite_path(linkpath, pbuf, sizeof(pbuf)) : linkpath;
    return orig_symlinkat(target, newdirfd, newlink);
}

// Hook truncate
int truncate(const char *path, off_t length) {
    static int (*orig_truncate)(const char *, off_t) = NULL;
    if (!orig_truncate) orig_truncate = (int (*)(const char *, off_t))dlsym(RTLD_NEXT, "truncate");
    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));
    return orig_truncate(target, length);
}

#if !defined(__LP64__)
int truncate64(const char *path, off64_t length) {
    static int (*orig_truncate64)(const char *, off64_t) = NULL;
    if (!orig_truncate64) orig_truncate64 = (int (*)(const char *, off64_t))dlsym(RTLD_NEXT, "truncate64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));
    return orig_truncate64 ? orig_truncate64(target, length) : truncate(target, length);
}
#endif

// Hook statfs / statfs64 / statvfs / statvfs64
int statfs(const char *path, struct statfs *buf) {
    static int (*orig_statfs)(const char *, struct statfs *) = NULL;
    if (!orig_statfs) orig_statfs = (int (*)(const char *, struct statfs *))dlsym(RTLD_NEXT, "statfs");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statfs ? orig_statfs(target, buf) : -1;
}

#if !defined(__LP64__)
int statfs64(const char *path, struct statfs64 *buf) {
    static int (*orig_statfs64)(const char *, struct statfs64 *) = NULL;
    if (!orig_statfs64) orig_statfs64 = (int (*)(const char *, struct statfs64 *))dlsym(RTLD_NEXT, "statfs64");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statfs64 ? orig_statfs64(target, buf) : -1;
}
#endif

int statvfs(const char *path, struct statvfs *buf) {
    static int (*orig_statvfs)(const char *, struct statvfs *) = NULL;
    if (!orig_statvfs) orig_statvfs = (int (*)(const char *, struct statvfs *))dlsym(RTLD_NEXT, "statvfs");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statvfs ? orig_statvfs(target, buf) : -1;
}

#if !defined(__LP64__)
int statvfs64(const char *path, struct statvfs64 *buf) {
    static int (*orig_statvfs64)(const char *, struct statvfs64 *) = NULL;
    if (!orig_statvfs64) orig_statvfs64 = (int (*)(const char *, struct statvfs64 *))dlsym(RTLD_NEXT, "statvfs64");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statvfs64 ? orig_statvfs64(target, buf) : -1;
}
#endif

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

#if !defined(__LP64__)
int scandir64(const char *dirp, struct dirent64 ***namelist,
              int (*filter)(const struct dirent64 *),
              int (*compar)(const struct dirent64 **, const struct dirent64 **)) {
    static int (*orig_scandir64)(const char *, struct dirent64 ***,
                                 int (*)(const struct dirent64 *),
                                 int (*)(const struct dirent64 **, const struct dirent64 **)) = NULL;
    if (!orig_scandir64) orig_scandir64 = (int (*)(const char *, struct dirent64 ***,
                                 int (*)(const struct dirent64 *),
                                 int (*)(const struct dirent64 **, const struct dirent64 **)))dlsym(RTLD_NEXT, "scandir64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(dirp, buf, sizeof(buf));
    return orig_scandir64 ? orig_scandir64(target, namelist, filter, compar) : -1;
}
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
int capget(void *hdrp, void *datap) { (void)hdrp; (void)datap; return 0; }
int capset(void *hdrp, const void *datap) { (void)hdrp; (void)datap; return 0; }

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

static char **ensure_glibc_tunables(char *const envp[]) {
    int count = 0;
    int has_tunables = 0;
    while (envp && envp[count]) {
        if (strncmp(envp[count], "GLIBC_TUNABLES=", 15) == 0) {
            has_tunables = 1;
        }
        count++;
    }
    if (has_tunables) {
        return (char **)envp;
    }
    char **new_env = calloc(count + 2, sizeof(char *));
    for (int i = 0; i < count; i++) {
        new_env[i] = envp[i];
    }
    new_env[count] = "GLIBC_TUNABLES=glibc.pthread.rseq=0";
    new_env[count + 1] = NULL;
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

                    int argc = 0;
                    while (argv[argc]) argc++;

                    const char *prog_name = strrchr(target, '/');
                    prog_name = (prog_name != NULL) ? prog_name + 1 : target;

                    char **new_argv = (char **)calloc(argc + 5, sizeof(char *));
                    new_argv[0] = ld_so;
                    new_argv[1] = (char *)"--argv0";
                    new_argv[2] = (char *)(argv[0] ? argv[0] : prog_name);
                    new_argv[3] = (char *)target;
                    for (int i = 1; i < argc; i++) {
                        new_argv[i + 3] = argv[i];
                    }
                    new_argv[argc + 3] = NULL;
                    return orig_execve(ld_so, new_argv, ensure_glibc_tunables(envp));
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

                int orig_argc = 0;
                while (argv[orig_argc]) orig_argc++;

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
                return execve(rewritten_interp, new_argv, envp);
            }
        }
    } else {
        if (strncmp(target, "/system", 7) == 0) {
            char **sys_env = clean_env_for_system(envp);
            return orig_execve(target, argv, sys_env);
        }
    }

    return orig_execve(target, argv, envp);
}

extern char **environ;

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
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
        if (access(candidate, X_OK) == 0) {
            return execve(candidate, argv, environ);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    return execve(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (strchr(file, '/')) {
        return execve(file, argv, envp);
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
        if (access(candidate, X_OK) == 0) {
            return execve(candidate, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    return execve(file, argv, envp);
}
