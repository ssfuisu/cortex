// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// File locks on a memfd. Android's SELinux policy gives an app's memory files (memfd_create) no
// "lock" permission, so fcntl(F_SETLK...) and flock() on one fail with EACCES, which they never do
// on Linux. PHP keeps the lock of its opcache in a memfd: "php -S" ended at once with "Cannot
// create lock - Permission denied (13)". A lock that is refused that way is taken on a file of the
// hook's own instead: /tmp/.cortex-memfd-locks/INODE, named after the memfd, so that every process
// that has the memfd (forked before or after its first lock) and every descriptor of it reach the
// same file and still exclude each other. As with a real file, closing the memfd gives its locks up.

#ifndef CORTEX_HOST_TEST

#define MEMFD_LOCK_SLOTS 32
#define MEMFD_LOCK_DIR "/tmp/.cortex-memfd-locks"

// One memfd of this process whose locks are on a file. No lock guards the table (a fork while
// another thread held one would leave it held in the child for good): a place is taken by its
// `ino` (0: free), and is used once `shadow` is set (the file's descriptor plus one).
static struct {
    uint64_t ino;
    uint64_t dev;
    int shadow;
} g_memfd_locks[MEMFD_LOCK_SLOTS];
static int g_memfd_locks_used; // whether any was ever taken: close() looks only then

static int fcntl_is_lock_cmd(int cmd) {
    switch (cmd) {
    case F_GETLK:
    case F_SETLK:
    case F_SETLKW:
#if defined(F_GETLK64) && F_GETLK64 != F_GETLK
    case F_GETLK64:
    case F_SETLK64:
    case F_SETLKW64:
#endif
#ifdef F_OFD_GETLK
    case F_OFD_GETLK:
    case F_OFD_SETLK:
    case F_OFD_SETLKW:
#endif
        return 1;
    default:
        return 0;
    }
}

// Whether fd is a memfd (its name in /proc is "/memfd:NAME (deleted)"), with its inode and device
static int memfd_identity(int fd, uint64_t *ino, uint64_t *dev) {
    char link[64], target[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t len = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, link, target, sizeof(target) - 1);
    if (len < 7 || memcmp(target, "/memfd:", 7) != 0) return 0;
    struct statx stx;
    if (syscall(SYS_statx, fd, "", AT_EMPTY_PATH, STATX_INO, &stx) != 0 || stx.stx_ino == 0) return 0;
    *ino = stx.stx_ino;
    *dev = ((uint64_t)stx.stx_dev_major << 32) | stx.stx_dev_minor;
    return 1;
}

static int memfd_lock_find(uint64_t ino, uint64_t dev) {
    for (int i = 0; i < MEMFD_LOCK_SLOTS; i++) {
        if (__atomic_load_n(&g_memfd_locks[i].ino, __ATOMIC_ACQUIRE) == ino && g_memfd_locks[i].dev == dev) {
            int shadow = __atomic_load_n(&g_memfd_locks[i].shadow, __ATOMIC_ACQUIRE);
            if (shadow > 0) return shadow - 1;
        }
    }
    return -1;
}

// The files of memfds that are gone with the last boot (a memfd does not outlive it)
static void memfd_lock_sweep(const char *dir) {
    struct timespec up, now;
    if (clock_gettime(CLOCK_BOOTTIME, &up) != 0 || clock_gettime(CLOCK_REALTIME, &now) != 0) return;
    int64_t booted = (int64_t)now.tv_sec - (int64_t)up.tv_sec - 60;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return;
    char dents[2048];
    for (;;) {
        long n = syscall(SYS_getdents64, fd, dents, sizeof(dents));
        if (n <= 0) break;
        for (long at = 0; at < n;) {
            unsigned short reclen;
            memcpy(&reclen, dents + at + 16, sizeof(reclen));
            if (reclen == 0) break;
            const char *name = dents + at + 19;
            at += reclen;
            if (name[0] == '.') continue;
            struct statx stx;
            if (syscall(SYS_statx, fd, name, AT_SYMLINK_NOFOLLOW, STATX_MTIME, &stx) == 0 && (int64_t)stx.stx_mtime.tv_sec < booted) {
                syscall(SYS_unlinkat, fd, name, 0);
            }
        }
    }
    syscall(SYS_close, fd);
}

// The file that stands in for the locks of the memfd fd, opened when this process has none yet;
// -1 without one (not a memfd, no room, no file: the caller keeps the refusal)
static int memfd_lock_shadow(int fd, int (*orig_fcntl)(int, int, ...)) {
    uint64_t ino, dev;
    if (g_cortex_root[0] == '\0' || !memfd_identity(fd, &ino, &dev)) return -1;
    int shadow = memfd_lock_find(ino, dev);
    if (shadow >= 0) return shadow;

    char dir[PATH_MAX], path[PATH_MAX];
    size_t n = cortex_append(dir, sizeof(dir), 0, g_cortex_root, g_cortex_root_len);
    cortex_append(dir, sizeof(dir), n, MEMFD_LOCK_DIR, sizeof(MEMFD_LOCK_DIR) - 1);
    snprintf(path, sizeof(path), "%s/%llu", dir, (unsigned long long)ino);
    int made = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (made < 0 && errno == ENOENT) {
        syscall(SYS_mkdirat, AT_FDCWD, dir, 0700);
        made = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    }
    if (made < 0) return -1;
    // out of the way of the numbers a program gives its own files (and of a loop that closes them)
    int high = orig_fcntl(made, F_DUPFD_CLOEXEC, 700);
    if (high >= 0) {
        syscall(SYS_close, made);
        made = high;
    }
    for (int i = 0; i < MEMFD_LOCK_SLOTS; i++) {
        uint64_t free_slot = 0;
        if (__atomic_compare_exchange_n(&g_memfd_locks[i].ino, &free_slot, ino, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            g_memfd_locks[i].dev = dev;
            __atomic_store_n(&g_memfd_locks[i].shadow, made + 1, __ATOMIC_RELEASE);
            if (!__atomic_exchange_n(&g_memfd_locks_used, 1, __ATOMIC_ACQ_REL)) memfd_lock_sweep(dir);
            return made;
        }
    }
    // no place left: refused, rather than a lock that the next close of some memfd would give up
    syscall(SYS_close, made);
    return -1;
}

// close() of fd: a process that closes a file gives up its locks on it, whichever descriptor it used
static void memfd_lock_closing(int fd) {
    uint64_t ino, dev;
    if (!memfd_identity(fd, &ino, &dev)) return;
    for (int i = 0; i < MEMFD_LOCK_SLOTS; i++) {
        if (__atomic_load_n(&g_memfd_locks[i].ino, __ATOMIC_ACQUIRE) != ino || g_memfd_locks[i].dev != dev) continue;
        int shadow = __atomic_exchange_n(&g_memfd_locks[i].shadow, 0, __ATOMIC_ACQ_REL);
        if (shadow > 0) syscall(SYS_close, shadow - 1);
        __atomic_store_n(&g_memfd_locks[i].ino, 0, __ATOMIC_RELEASE);
    }
}

// For tests on Linux, where a memfd can be locked: CORTEX_MEMFD_LOCKS=deny makes the hook act as if not
static int memfd_locks_denied_for_test(int fd) {
    static int state; // 0: not read, 1: no, 2: yes
    int s = __atomic_load_n(&state, __ATOMIC_RELAXED);
    if (s == 0) {
        const char *v = getenv("CORTEX_MEMFD_LOCKS");
        s = (v && strcmp(v, "deny") == 0) ? 2 : 1;
        __atomic_store_n(&state, s, __ATOMIC_RELAXED);
    }
    uint64_t ino, dev;
    return s == 2 && memfd_identity(fd, &ino, &dev);
}

static int cortex_fcntl(int fd, int cmd, void *arg, const char *symbol, int (**orig_slot)(int, int, ...)) {
    int (*orig)(int, int, ...) = __atomic_load_n(orig_slot, __ATOMIC_RELAXED);
    if (!orig) {
        orig = (int (*)(int, int, ...))dlsym(RTLD_NEXT, symbol);
        if (!orig) {
            errno = ENOSYS;
            return -1;
        }
        __atomic_store_n(orig_slot, orig, __ATOMIC_RELAXED);
    }
    if (!fcntl_is_lock_cmd(cmd)) return orig(fd, cmd, arg);
    int ret;
    if (memfd_locks_denied_for_test(fd)) {
        ret = -1;
        errno = EACCES;
    } else {
        ret = orig(fd, cmd, arg);
    }
    if (ret == -1 && errno == EACCES) {
        int shadow = memfd_lock_shadow(fd, orig);
        if (shadow >= 0) return orig(shadow, cmd, arg);
        errno = EACCES;
    }
    return ret;
}

// The third argument is read as glibc's own fcntl() reads it: always, as a pointer
int fcntl(int fd, int cmd, ...) {
    static int (*orig_fcntl)(int, int, ...) = NULL;
    va_list ap;
    va_start(ap, cmd);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return cortex_fcntl(fd, cmd, arg, "fcntl", &orig_fcntl);
}

// What programs built with 64-bit file offsets call (glibc 2.28 and later)
int fcntl64(int fd, int cmd, ...) {
    static int (*orig_fcntl64)(int, int, ...) = NULL;
    va_list ap;
    va_start(ap, cmd);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return cortex_fcntl(fd, cmd, arg, "fcntl64", &orig_fcntl64);
}

#if !defined(__LP64__)
// And with a 64-bit time_t on a 32-bit system, as all of Ubuntu's armhf packages are built
int __fcntl_time64(int fd, int cmd, ...) {
    static int (*orig_fcntl_time64)(int, int, ...) = NULL;
    va_list ap;
    va_start(ap, cmd);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return cortex_fcntl(fd, cmd, arg, "__fcntl_time64", &orig_fcntl_time64);
}
#endif

int flock(int fd, int operation) {
    static int (*orig_flock)(int, int) = NULL;
    static int (*orig_fcntl)(int, int, ...) = NULL;
    if (!orig_flock) orig_flock = (int (*)(int, int))dlsym(RTLD_NEXT, "flock");
    if (!orig_flock) {
        errno = ENOSYS;
        return -1;
    }
    int ret;
    if (memfd_locks_denied_for_test(fd)) {
        ret = -1;
        errno = EACCES;
    } else {
        ret = orig_flock(fd, operation);
    }
    if (ret == -1 && errno == EACCES) {
        if (!orig_fcntl) orig_fcntl = (int (*)(int, int, ...))dlsym(RTLD_NEXT, "fcntl");
        int shadow = orig_fcntl ? memfd_lock_shadow(fd, orig_fcntl) : -1;
        if (shadow >= 0) return orig_flock(shadow, operation);
        errno = EACCES;
    }
    return ret;
}

#endif /* !CORTEX_HOST_TEST */
