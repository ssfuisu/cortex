// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Calls an app may not make (chown, mknod, capabilities, prctl, landlock, seccomp, namespaces,
// close_range) and the generic syscall() entry point.

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

#endif /* !CORTEX_HOST_TEST */
