// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Calls an app may not make (chown, mknod, capabilities, prctl, landlock, seccomp, namespaces,
// close_range) and the generic syscall() entry point.

// The ARM translator of x86 devices and emulators (ndk_translation, houdini) keeps memory files of
// its own open in the process (ndk_translation at descriptor 3) and needs them whenever it
// translates more code: a program that closes every descriptor it did not open (sshd at start,
// closefrom(3)) made it abort later ("CHECK failed" in mmap_posix.cc). Its memory files open when
// the hook starts survive close(), close_range() and closefrom(); phones that run ARM code
// themselves have none.
#define CORTEX_MAX_KEPT_FDS 4
static int g_kept_fds[CORTEX_MAX_KEPT_FDS]; // ascending
static int g_kept_fd_count = 0;

static int is_kept_fd(int fd) {
    for (int i = 0; i < g_kept_fd_count; i++) {
        if (g_kept_fds[i] == fd) return 1;
    }
    return 0;
}

// Calls fn for each part of [first, last] that leaves out the descriptors in kept (ascending);
// the first nonzero answer of fn is returned, else 0.
static int for_each_range_without(unsigned int first, unsigned int last, const int *kept, int nkept,
                                  int (*fn)(unsigned int, unsigned int, void *), void *arg) {
    unsigned int from = first;
    for (int i = 0; i < nkept && from <= last; i++) {
        if (kept[i] < 0 || (unsigned int)kept[i] < from || (unsigned int)kept[i] > last) continue;
        unsigned int k = (unsigned int)kept[i];
        if (k > from) {
            int r = fn(from, k - 1, arg);
            if (r != 0) return r;
        }
        if (k == last) return 0;
        from = k + 1;
    }
    return from <= last ? fn(from, last, arg) : 0;
}

#ifndef CORTEX_HOST_TEST

// ndk_translation's file is "/memfd:child"; any memory file counts in a process whose executable is
// a translator's runner (houdini). ndk_translation shows a program its own path as /proc/self/exe,
// so the name is what tells there.
//
// This runs at the start of every program, so it costs three system calls on a phone that runs ARM
// code itself: the descriptors that are open (/proc/self/fd, read at once) are all that are asked
// about, where asking about each of the numbers from 3 to 63 was 61 readlink() calls in every
// process that was started.
// Set once this process knows that it has none (it looked, or was told by CORTEX_NT=0 in its
// environment, which prepare_cortex_env hands on to the programs it starts)
static int g_no_translator = 0;

__attribute__((constructor(102))) static void find_translator_files(void) {
    // The first program of a session looks; the ones it starts are told (a translator is there for
    // all the programs of a machine or for none, and where it is not known they look as before)
    const char *known = getenv("CORTEX_NT");
    if (known && known[0] == '0' && known[1] == '\0') {
        g_no_translator = 1;
        return;
    }
    ssize_t (*real_readlink)(const char *, char *, size_t) = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    int (*real_open)(const char *, int, ...) = (int (*)(const char *, int, ...))cortex_next((void *)open, "open");
    int (*real_close)(int) = (int (*)(int))dlsym(RTLD_NEXT, "close");
    ssize_t (*real_getdents64)(int, void *, size_t) = (ssize_t (*)(int, void *, size_t))dlsym(RTLD_NEXT, "getdents64");
    if (!real_readlink || !real_open || !real_close) return;
    int dir = real_getdents64 ? real_open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC) : -1;
    if (dir < 0) return;
    // The numbers of the descriptors open now, those from 3 to 63 (the directory's own left out)
    char entries[2048];
    unsigned char open_fds[64];
    int nopen = 0;
    for (;;) {
        long n = real_getdents64(dir, entries, sizeof(entries));
        if (n <= 0) break;
        for (long off = 0; off < n;) {
            // struct linux_dirent64: d_ino (8), d_off (8), d_reclen (2), d_type (1), then d_name
            unsigned short reclen;
            memcpy(&reclen, entries + off + 16, sizeof(reclen));
            const char *name = entries + off + 19;
            off += reclen ? reclen : n; // a record without a length ends the buffer
            if (name[0] < '0' || name[0] > '9') continue; // "." and ".."
            int fd = atoi(name);
            if (fd >= 3 && fd < 64 && fd != dir) open_fds[nopen++] = (unsigned char)fd;
        }
        // A short answer is the whole directory; asking again would only be told "no more"
        if ((size_t)n + 64 <= sizeof(entries)) break;
    }
    real_close(dir);
    if (nopen == 0) {
        g_no_translator = 1;
        return;
    }

    char path[PATH_MAX];
    int runner = 0;
    ssize_t n = real_readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (n > 0) {
        path[n] = '\0';
        const char *base = strrchr(path, '/');
        base = base ? base + 1 : path;
        runner = strncmp(base, "ndk_translation", 15) == 0 || strncmp(base, "houdini", 7) == 0;
    }
    // In ascending order, as the list is kept
    for (int i = 0; i < nopen - 1; i++) {
        for (int j = i + 1; j < nopen; j++) {
            if (open_fds[j] < open_fds[i]) {
                unsigned char t = open_fds[i];
                open_fds[i] = open_fds[j];
                open_fds[j] = t;
            }
        }
    }
    for (int i = 0; i < nopen && g_kept_fd_count < CORTEX_MAX_KEPT_FDS; i++) {
        char link[32], target[64];
        snprintf(link, sizeof(link), "/proc/self/fd/%d", open_fds[i]);
        ssize_t m = real_readlink(link, target, sizeof(target) - 1);
        if (m <= 0) continue;
        target[m] = '\0';
        if (strncmp(target, "/memfd:child", 12) == 0 || (runner && strncmp(target, "/memfd:", 7) == 0)) {
            g_kept_fds[g_kept_fd_count++] = open_fds[i];
        }
    }
    if (g_kept_fd_count == 0) g_no_translator = 1;
}

// The owner is recorded for the programs to see (ids.c): the kernel lets the app give its files to
// no other user
int chown(const char *pathname, uid_t owner, gid_t group) {
    char buf[PATH_MAX];
    const char *target = pathname ? rewrite_path(pathname, buf, sizeof(buf)) : NULL;
    if (!target) {
        errno = EFAULT;
        return -1;
    }
    return fake_chown(AT_FDCWD, target, owner, group, 0);
}
int fchown(int fd, uid_t owner, gid_t group) { return fake_chown(fd, NULL, owner, group, 0); }
int lchown(const char *pathname, uid_t owner, gid_t group) {
    char buf[PATH_MAX];
    const char *target = pathname ? rewrite_path(pathname, buf, sizeof(buf)) : NULL;
    if (!target) {
        errno = EFAULT;
        return -1;
    }
    return fake_chown(AT_FDCWD, target, owner, group, AT_SYMLINK_NOFOLLOW);
}
int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (!target) {
        errno = EFAULT;
        return -1;
    }
    if (target[0] == '\0' && (flags & AT_EMPTY_PATH)) return fake_chown(dirfd, NULL, owner, group, 0);
    return fake_chown(dirfd, target, owner, group, flags & AT_SYMLINK_NOFOLLOW);
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
    // A file type of 0 means a regular file, as in mknod(2), which also fails with EEXIST instead
    // of touching an existing file.
    if (S_ISREG(mode) || (mode & S_IFMT) == 0) {
        // mknod(2) returns 0 on success, never a file descriptor: close the
        // transient fd instead of leaking one per call (tar/dpkg extract
        // thousands of files; leaked fds exhaust the table -> EBADF noise).
        int fd = openat(dirfd, target, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, mode & 07777);
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
    if (!orig_prctl) orig_prctl = (int (*)(int, unsigned long, unsigned long, unsigned long, unsigned long))cortex_next((void *)prctl, "prctl");
    return orig_prctl ? orig_prctl(option, arg2, arg3, arg4, arg5) : 0;
}

// Limits that leave a process no new descriptors (RLIMIT_NOFILE below 16) or no file writes
// (RLIMIT_FSIZE 0) are not applied. Sandboxes give them to their unprivileged part (sshd's before
// login sets 1 and 0, with the seccomp filter above), and Android's ARM translator (emulators, x86
// devices) needs new descriptors and memory files in the process: sshd's died in it ("CHECK failed"
// in its mmap) and the client's connection was reset. Other limits are set as asked.
static int sandbox_only_limit(int resource, const struct rlimit *rlim) {
    if (!rlim) return 0;
    if (resource == RLIMIT_NOFILE) return rlim->rlim_cur < 16;
    if (resource == RLIMIT_FSIZE) return rlim->rlim_cur == 0;
    return 0;
}

int setrlimit(__rlimit_resource_t resource, const struct rlimit *rlim) {
    if (sandbox_only_limit((int)resource, rlim)) return 0;
    static int (*orig_setrlimit)(__rlimit_resource_t, const struct rlimit *) = NULL;
#if defined(__USE_FILE_OFFSET64) && !defined(__LP64__)
    // With 64-bit file offsets on a 32-bit system this function is setrlimit64
    if (!orig_setrlimit) orig_setrlimit = (int (*)(__rlimit_resource_t, const struct rlimit *))dlsym(RTLD_NEXT, "setrlimit64");
#else
    if (!orig_setrlimit) orig_setrlimit = (int (*)(__rlimit_resource_t, const struct rlimit *))cortex_next((void *)setrlimit, "setrlimit");
#endif
    if (!orig_setrlimit) {
        errno = ENOSYS;
        return -1;
    }
    return orig_setrlimit(resource, rlim);
}
#if defined(__LP64__)
__asm__(".globl setrlimit64\n" ".set setrlimit64, setrlimit\n");
#endif

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

static int close_range_all(unsigned int first, unsigned int last, int flags);

static int close_range_part(unsigned int first, unsigned int last, void *flags) {
    return close_range_all(first, last, *(int *)flags);
}

int close_range(unsigned int first, unsigned int last, int flags) {
    // Close-on-exec only (CLOSE_RANGE_CLOEXEC) leaves the translator's files open
    if (g_kept_fd_count > 0 && !(flags & CLOSE_RANGE_CLOEXEC)) {
        return for_each_range_without(first, last, g_kept_fds, g_kept_fd_count, close_range_part, &flags);
    }
    return close_range_all(first, last, flags);
}

// glibc's closefrom() makes the close_range system call itself, past the function above
void closefrom(int lowfd) {
    close_range(lowfd < 0 ? 0U : (unsigned int)lowfd, ~0U, 0);
}

// glibc's posix_spawn() executes file_actions inside the vfork child via direct syscalls (bypassing
// close() / close_range() hooks), so filter kept translator descriptors while building the actions
// list (Python 3.14 subprocess.Popen passes POSIX_SPAWN_CLOSEFROM=3 by default).
int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *file_actions, int fd) {
    static int (*orig_addclose)(posix_spawn_file_actions_t *, int) = NULL;
    if (!orig_addclose) {
        orig_addclose = (int (*)(posix_spawn_file_actions_t *, int))dlsym(RTLD_NEXT, "posix_spawn_file_actions_addclose");
    }
    if (g_kept_fd_count > 0 && is_kept_fd(fd)) return 0;
    return orig_addclose ? orig_addclose(file_actions, fd) : ENOSYS;
}

int posix_spawn_file_actions_addclosefrom_np(posix_spawn_file_actions_t *file_actions, int from) {
    static int (*orig_addclosefrom)(posix_spawn_file_actions_t *, int) = NULL;
    if (!orig_addclosefrom) {
        orig_addclosefrom = (int (*)(posix_spawn_file_actions_t *, int))dlsym(RTLD_NEXT, "posix_spawn_file_actions_addclosefrom_np");
    }
    if (!orig_addclosefrom) return ENOSYS;
    if (from < 0 || g_kept_fd_count == 0) return orig_addclosefrom(file_actions, from);

    int cur = from;
    for (int i = 0; i < g_kept_fd_count; i++) {
        int k = g_kept_fds[i];
        if (k < cur) continue;
        for (int fd = cur; fd < k; fd++) {
            int r = posix_spawn_file_actions_addclose(file_actions, fd);
            if (r != 0) return r;
        }
        cur = k + 1;
    }
    return orig_addclosefrom(file_actions, cur);
}

static int close_range_all(unsigned int first, unsigned int last, int flags) {
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
#if defined(__NR_linkat)
    if (number == __NR_linkat) {
        return (long)linkat((int)arg1, (const char *)arg2, (int)arg3, (const char *)arg4, (int)arg5);
    }
#endif
#if defined(__NR_link)
    if (number == __NR_link) {
        return (long)link((const char *)arg1, (const char *)arg2);
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
        return (long)openat2((int)arg1, (const char *)arg2, (cortex_open_how_arg_t)arg3, (size_t)arg4);
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

    // The ids, as the functions of identity.c answer them (ids.c). 32-bit ARM also has the old
    // calls with 16-bit ids (without "32"): their ids and -1 are 16 bits wide.
#if defined(__NR_getresuid32) && defined(__NR_getresuid)
    if (number == __NR_getresuid || number == __NR_getresgid) {
        uint16_t *out[3] = {(uint16_t *)arg1, (uint16_t *)arg2, (uint16_t *)arg3};
        uid_t r, e, sv;
        if (number == __NR_getresuid) getresuid(&r, &e, &sv);
        else getresgid((gid_t *)&r, (gid_t *)&e, (gid_t *)&sv);
        uid_t v[3] = {r, e, sv};
        for (int i = 0; i < 3; i++) {
            if (out[i]) *out[i] = (uint16_t)(v[i] > 0xfffe ? 0xfffe : v[i]);
        }
        return 0;
    }
#endif
#if defined(__NR_getgroups32) && defined(__NR_getgroups)
    if (number == __NR_getgroups) {
        gid_t groups[CORTEX_IDS_MAX_GROUPS];
        int n = getgroups(CORTEX_IDS_MAX_GROUPS, groups);
        if (n < 0 || (int)arg1 == 0) return n;
        if ((int)arg1 < n) {
            errno = EINVAL;
            return -1;
        }
        for (int i = 0; i < n; i++) ((uint16_t *)arg2)[i] = (uint16_t)groups[i];
        return n;
    }
#endif
    {
        long r = sigsys_id_call(number, arg1, arg2, arg3);
        if (r != LONG_MIN) return r;
    }
    switch (number) {
#if defined(__NR_getuid)
    case __NR_getuid:
#endif
#if defined(__NR_getuid32)
    case __NR_getuid32:
#endif
        return (long)ids_now()->r;
#if defined(__NR_geteuid)
    case __NR_geteuid:
#endif
#if defined(__NR_geteuid32)
    case __NR_geteuid32:
#endif
        return (long)ids_now()->e;
#if defined(__NR_getgid)
    case __NR_getgid:
#endif
#if defined(__NR_getgid32)
    case __NR_getgid32:
#endif
        return (long)ids_now()->gr;
#if defined(__NR_getegid)
    case __NR_getegid:
#endif
#if defined(__NR_getegid32)
    case __NR_getegid32:
#endif
        return (long)ids_now()->ge;
#if defined(__NR_getresuid)
    case __NR_getresuid:
#endif
#if defined(__NR_getresuid32)
    case __NR_getresuid32:
#endif
        return getresuid((uid_t *)arg1, (uid_t *)arg2, (uid_t *)arg3);
#if defined(__NR_getresgid)
    case __NR_getresgid:
#endif
#if defined(__NR_getresgid32)
    case __NR_getresgid32:
#endif
        return getresgid((gid_t *)arg1, (gid_t *)arg2, (gid_t *)arg3);
#if defined(__NR_getgroups)
    case __NR_getgroups:
#endif
#if defined(__NR_getgroups32)
    case __NR_getgroups32:
#endif
        return getgroups((int)arg1, (gid_t *)arg2);
    default:
        break;
    }

    return call_orig_syscall(number, arg1, arg2, arg3, arg4, arg5, arg6);
}

static void cortex_read_hostname(char *out, size_t cap) {
    if (cap == 0) return;
    if (g_cortex_root[0] != '\0') {
        char path[PATH_MAX];
        size_t n = cortex_append(path, sizeof(path), 0, g_cortex_root, g_cortex_root_len);
        cortex_append(path, sizeof(path), n, "/etc/hostname", 13);
        int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
        if (fd >= 0) {
            char buf[256];
            ssize_t r = (ssize_t)syscall(SYS_read, fd, buf, sizeof(buf) - 1);
            syscall(SYS_close, fd);
            if (r > 0) {
                size_t len = 0;
                while (len < (size_t)r && buf[len] != '\n' && buf[len] != '\r' && buf[len] != ' ' && buf[len] != '\t') {
                    len++;
                }
                if (len > 0) {
                    if (len >= cap) len = cap - 1;
                    memcpy(out, buf, len);
                    out[len] = '\0';
                    return;
                }
            }
        }
    }
    cortex_copy_cstr(out, cap, "localhost");
}

int gethostname(char *name, size_t len) {
    if (!name) {
        errno = EFAULT;
        return -1;
    }
    char host[256];
    cortex_read_hostname(host, sizeof(host));
    size_t hlen = strlen(host);
    if (len == 0 || hlen >= len) {
        if (len > 0) memcpy(name, host, len);
        errno = ENAMETOOLONG;
        return -1;
    }
    memcpy(name, host, hlen + 1);
    return 0;
}

int uname(struct utsname *buf) {
    static int (*orig_uname)(struct utsname *) = NULL;
    if (!orig_uname) orig_uname = (int (*)(struct utsname *))dlsym(RTLD_NEXT, "uname");
    int ret = orig_uname ? orig_uname(buf) : (int)call_orig_syscall(SYS_uname, (unsigned long)buf, 0, 0, 0, 0, 0);
    if (ret == 0 && buf) {
        cortex_read_hostname(buf->nodename, sizeof(buf->nodename));
    }
    return ret;
}

#endif /* !CORTEX_HOST_TEST */
