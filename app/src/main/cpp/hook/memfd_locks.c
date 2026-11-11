// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// File locks on a memfd. Android's SELinux policy gives an app's memory files (memfd_create) no
// "lock" permission, so fcntl(F_SETLK...) and flock() on one fail with EACCES, which they never do
// on Linux. PHP keeps the lock of its opcache in a memfd: "php -S" ended at once with "Cannot
// create lock - Permission denied (13)". A lock that is refused that way is taken on a file of the
// hook's own instead (in the rootfs /tmp, without a name), one per memfd: a process and the children
// it forks afterwards share it as they share the memfd, so they still exclude each other.

#ifndef CORTEX_HOST_TEST

#define MEMFD_LOCK_SLOTS 16

static struct {
    int fd;       // the memfd as the program knows it, -1: free
    uint64_t ino; // its inode: the number can be another file's later
    int shadow;   // the file the locks are taken on
} g_memfd_locks[MEMFD_LOCK_SLOTS];
static int g_memfd_locks_ready;
static pthread_mutex_t g_memfd_locks_lock = PTHREAD_MUTEX_INITIALIZER;

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

// The inode of fd when it is a memfd (its name in /proc is "/memfd:NAME (deleted)"), else 0
static uint64_t memfd_inode(int fd) {
    char link[64], target[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t len = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, link, target, sizeof(target) - 1);
    if (len < 7 || memcmp(target, "/memfd:", 7) != 0) return 0;
    struct statx stx;
    if (syscall(SYS_statx, fd, "", AT_EMPTY_PATH, STATX_INO, &stx) != 0) return 0;
    return stx.stx_ino ? stx.stx_ino : 1;
}

// The file that stands in for the locks of the memfd fd, made when there is none yet; -1 without one
static int memfd_lock_shadow(int fd, int (*orig_fcntl)(int, int, ...)) {
    uint64_t ino = memfd_inode(fd);
    if (ino == 0 || g_cortex_root[0] == '\0') return -1;
    int shadow = -1;
    pthread_mutex_lock(&g_memfd_locks_lock);
    if (!g_memfd_locks_ready) {
        for (int i = 0; i < MEMFD_LOCK_SLOTS; i++) g_memfd_locks[i].fd = -1;
        g_memfd_locks_ready = 1;
    }
    int slot = -1;
    for (int i = 0; i < MEMFD_LOCK_SLOTS; i++) {
        if (g_memfd_locks[i].fd == fd) {
            slot = i;
            break;
        }
        if (slot < 0 && g_memfd_locks[i].fd < 0) slot = i;
    }
    if (slot < 0) slot = fd % MEMFD_LOCK_SLOTS; // all taken: the oldest use of this place goes
    if (g_memfd_locks[slot].fd == fd && g_memfd_locks[slot].ino == ino) {
        shadow = g_memfd_locks[slot].shadow;
    } else {
        if (g_memfd_locks[slot].fd >= 0) syscall(SYS_close, g_memfd_locks[slot].shadow);
        g_memfd_locks[slot].fd = -1;
        char dir[PATH_MAX];
        size_t n = cortex_append(dir, sizeof(dir), 0, g_cortex_root, g_cortex_root_len);
        cortex_append(dir, sizeof(dir), n, "/tmp", 4);
        int made = (int)syscall(SYS_openat, AT_FDCWD, dir, O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
        if (made < 0) {
            // a file system without O_TMPFILE: a name for a moment
            char path[PATH_MAX];
            snprintf(path, sizeof(path), "%s/.cortex-memfd-lock-%d-%d", dir, (int)getpid(), fd);
            made = (int)syscall(SYS_openat, AT_FDCWD, path, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0600);
            if (made >= 0) syscall(SYS_unlinkat, AT_FDCWD, path, 0);
        }
        if (made >= 0) {
            // out of the way of the numbers a program gives its own files (and of a loop that closes them)
            int high = orig_fcntl(made, F_DUPFD_CLOEXEC, 700);
            if (high >= 0) {
                syscall(SYS_close, made);
                made = high;
            }
            g_memfd_locks[slot].fd = fd;
            g_memfd_locks[slot].ino = ino;
            g_memfd_locks[slot].shadow = made;
            shadow = made;
        }
    }
    pthread_mutex_unlock(&g_memfd_locks_lock);
    return shadow;
}

// For tests on Linux, where a memfd can be locked: CORTEX_MEMFD_LOCKS=deny makes the hook act as if not
static int memfd_locks_denied_for_test(void) {
    static int state; // 0: not read, 1: no, 2: yes
    int s = __atomic_load_n(&state, __ATOMIC_RELAXED);
    if (s == 0) {
        const char *v = getenv("CORTEX_MEMFD_LOCKS");
        s = (v && strcmp(v, "deny") == 0) ? 2 : 1;
        __atomic_store_n(&state, s, __ATOMIC_RELAXED);
    }
    return s == 2;
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
    if (memfd_locks_denied_for_test() && memfd_inode(fd) != 0) {
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

int flock(int fd, int operation) {
    static int (*orig_flock)(int, int) = NULL;
    static int (*orig_fcntl)(int, int, ...) = NULL;
    if (!orig_flock) orig_flock = (int (*)(int, int))dlsym(RTLD_NEXT, "flock");
    if (!orig_flock) {
        errno = ENOSYS;
        return -1;
    }
    int ret;
    if (memfd_locks_denied_for_test() && memfd_inode(fd) != 0) {
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
