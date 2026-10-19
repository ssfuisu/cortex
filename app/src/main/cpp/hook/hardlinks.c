// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Hard links: link() falls back to a copy that reports the link count of a real link.

// Emulated hard links. Android does not let apps create hard links, so link() falls back to a copy
// (cortex_emulate_hardlinkat). Programs that check the result through the link count, such as the
// lock files of shadow (link(file.PID, file.lock), then stat(file.PID).st_nlink == 2), are shown what
// a real link would give: every copy of one emulated link reports the inode number of the original
// and the number of copies as its link count. Copies are tracked by device and inode, so a relative
// name, an absolute name and an open descriptor of the same file all match. Per process.
typedef struct {
    dev_t dev;
    ino_t ino;        // this copy
    dev_t group_dev;  // the original, whose inode number every copy reports
    ino_t group_ino;
} cortex_hardlink_t;

#define CORTEX_MAX_HARDLINKS 4096
static cortex_hardlink_t *g_hardlinks = NULL;
static size_t g_hardlinks_len = 0;
static size_t g_hardlinks_cap = 0;
// Read without the lock by the stat() hooks: they only take the lock once a copy exists.
static volatile int g_hardlinks_any = 0;
static pthread_mutex_t g_hardlinks_lock = PTHREAD_MUTEX_INITIALIZER;

static ssize_t hardlink_find_locked(dev_t dev, ino_t ino) {
    for (size_t i = 0; i < g_hardlinks_len; i++) {
        if (g_hardlinks[i].ino == ino && g_hardlinks[i].dev == dev) return (ssize_t)i;
    }
    return -1;
}

static size_t hardlink_group_size_locked(dev_t group_dev, ino_t group_ino) {
    size_t n = 0;
    for (size_t i = 0; i < g_hardlinks_len; i++) {
        if (g_hardlinks[i].group_ino == group_ino && g_hardlinks[i].group_dev == group_dev) n++;
    }
    return n;
}

static void hardlink_remove_locked(size_t i) {
    memmove(&g_hardlinks[i], &g_hardlinks[i + 1], (g_hardlinks_len - i - 1) * sizeof(g_hardlinks[0]));
    g_hardlinks_len--;
}

// Adds or updates a copy. The table only grows up to CORTEX_MAX_HARDLINKS, after which the oldest
// entry is forgotten (that copy then shows a link count of 1 again, as before it was linked).
static int hardlink_put_locked(dev_t dev, ino_t ino, dev_t group_dev, ino_t group_ino) {
    ssize_t i = hardlink_find_locked(dev, ino);
    if (i >= 0) {
        g_hardlinks[i].group_dev = group_dev;
        g_hardlinks[i].group_ino = group_ino;
        return 0;
    }
    if (g_hardlinks_len == CORTEX_MAX_HARDLINKS) hardlink_remove_locked(0);
    if (g_hardlinks_len == g_hardlinks_cap) {
        size_t cap = g_hardlinks_cap ? g_hardlinks_cap * 2 : 16;
        if (cap > CORTEX_MAX_HARDLINKS) cap = CORTEX_MAX_HARDLINKS;
        cortex_hardlink_t *grown = (cortex_hardlink_t *)realloc(g_hardlinks, cap * sizeof(g_hardlinks[0]));
        if (!grown) return -1;
        g_hardlinks = grown;
        g_hardlinks_cap = cap;
    }
    g_hardlinks[g_hardlinks_len].dev = dev;
    g_hardlinks[g_hardlinks_len].ino = ino;
    g_hardlinks[g_hardlinks_len].group_dev = group_dev;
    g_hardlinks[g_hardlinks_len].group_ino = group_ino;
    g_hardlinks_len++;
    return 0;
}

// Records that the file (new_dev, new_ino) is an emulated hard link of (old_dev, old_ino).
static void register_emulated_hardlink(dev_t old_dev, ino_t old_ino, dev_t new_dev, ino_t new_ino) {
    pthread_mutex_lock(&g_hardlinks_lock);
    dev_t group_dev = old_dev;
    ino_t group_ino = old_ino;
    ssize_t i = hardlink_find_locked(old_dev, old_ino);
    if (i >= 0) {
        // Linking a copy again: the new copy joins the same group.
        group_dev = g_hardlinks[i].group_dev;
        group_ino = g_hardlinks[i].group_ino;
    } else {
        hardlink_put_locked(old_dev, old_ino, group_dev, group_ino);
    }
    hardlink_put_locked(new_dev, new_ino, group_dev, group_ino);
    g_hardlinks_any = g_hardlinks_len > 0;
    pthread_mutex_unlock(&g_hardlinks_lock);
}

// Forgets a copy whose name was removed; the last copy of a group goes back to a plain file.
static void unregister_emulated_hardlink(dev_t dev, ino_t ino) {
    pthread_mutex_lock(&g_hardlinks_lock);
    ssize_t i = hardlink_find_locked(dev, ino);
    if (i >= 0) {
        dev_t group_dev = g_hardlinks[i].group_dev;
        ino_t group_ino = g_hardlinks[i].group_ino;
        hardlink_remove_locked((size_t)i);
        if (hardlink_group_size_locked(group_dev, group_ino) == 1) {
            for (size_t j = 0; j < g_hardlinks_len; j++) {
                if (g_hardlinks[j].group_ino == group_ino && g_hardlinks[j].group_dev == group_dev) {
                    hardlink_remove_locked(j);
                    break;
                }
            }
        }
    }
    g_hardlinks_any = g_hardlinks_len > 0;
    pthread_mutex_unlock(&g_hardlinks_lock);
}

// Makes a stat() result of an emulated hard link look like a real one: the inode number of the
// original and the number of copies as the link count.
static void apply_emulated_hardlink(struct stat *st) {
    if (!st || !g_hardlinks_any) return;
    pthread_mutex_lock(&g_hardlinks_lock);
    ssize_t i = hardlink_find_locked(st->st_dev, st->st_ino);
    if (i >= 0) {
        size_t n = hardlink_group_size_locked(g_hardlinks[i].group_dev, g_hardlinks[i].group_ino);
        if (n > (size_t)st->st_nlink) st->st_nlink = (nlink_t)n;
        st->st_ino = g_hardlinks[i].group_ino;
    }
    pthread_mutex_unlock(&g_hardlinks_lock);
}

static int real_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
#ifdef CORTEX_HOST_TEST
    return fstatat(dirfd, path, st, flags);
#else
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    return orig_fstatat ? orig_fstatat(dirfd, path, st, flags) : fstatat(dirfd, path, st, flags);
#endif
}

// Before a name is removed or replaced (unlink, rename over it): whether it is an emulated hard
// link, and which. Costs nothing until the process has made one.
static int lookup_emulated_hardlink_at(int dirfd, const char *path, dev_t *dev, ino_t *ino) {
    if (!g_hardlinks_any || !path) return 0;
    int saved_errno = errno;
    struct stat st;
    int found = 0;
    if (real_fstatat(dirfd, path, &st, AT_SYMLINK_NOFOLLOW) == 0) {
        pthread_mutex_lock(&g_hardlinks_lock);
        found = hardlink_find_locked(st.st_dev, st.st_ino) >= 0;
        pthread_mutex_unlock(&g_hardlinks_lock);
        *dev = st.st_dev;
        *ino = st.st_ino;
    }
    errno = saved_errno;
    return found;
}

static int cortex_emulate_hardlinkat(int olddirfd, const char *rold, int newdirfd, const char *rnew) {
    if (!rold || !rnew) {
        errno = EINVAL;
        return -1;
    }
    struct stat st;
#ifdef CORTEX_HOST_TEST
    if (fstatat(olddirfd, rold, &st, 0) != 0) {
        return -1;
    }
#else
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    if (orig_fstatat) {
        if (orig_fstatat(olddirfd, rold, &st, 0) != 0) return -1;
    } else {
        if (fstatat(olddirfd, rold, &st, 0) != 0) return -1;
    }
#endif
    if (S_ISDIR(st.st_mode)) {
        errno = EPERM;
        return -1;
    }

    // Clean up stale dangling symlink or stale dead-process lock file at rnew
#ifndef CORTEX_HOST_TEST
    struct stat lst;
    int have_lst = 0;
    if (orig_fstatat) {
        if (orig_fstatat(newdirfd, rnew, &lst, AT_SYMLINK_NOFOLLOW) == 0) have_lst = 1;
    } else {
        if (fstatat(newdirfd, rnew, &lst, AT_SYMLINK_NOFOLLOW) == 0) have_lst = 1;
    }
    if (have_lst) {
        if (S_ISLNK(lst.st_mode)) {
            unlinkat(newdirfd, rnew, 0);
        } else if (strstr(rnew, ".lock")) {
            static int (*orig_openat)(int, const char *, int, ...) = NULL;
            if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
            int rfd = orig_openat ? orig_openat(newdirfd, rnew, O_RDONLY | O_CLOEXEC)
                                  : openat(newdirfd, rnew, O_RDONLY | O_CLOEXEC);
            if (rfd >= 0) {
                char pbuf[32] = {0};
                ssize_t pr = read(rfd, pbuf, sizeof(pbuf) - 1);
                close(rfd);
                if (pr > 0) {
                    pid_t lpid = (pid_t)atoi(pbuf);
                    if (lpid > 0 && kill(lpid, 0) == -1 && errno == ESRCH) {
                        unlinkat(newdirfd, rnew, 0);
                    }
                } else {
                    unlinkat(newdirfd, rnew, 0);
                }
            }
        }
    }
#endif

#ifdef CORTEX_HOST_TEST
    int fd_new = openat(newdirfd, rnew, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 0777);
    if (fd_new < 0) return -1;
    int fd_old = openat(olddirfd, rold, O_RDONLY | O_CLOEXEC);
#else
    static int (*orig_openat)(int, const char *, int, ...) = NULL;
    if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    int fd_new = orig_openat ? orig_openat(newdirfd, rnew, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 0777)
                             : openat(newdirfd, rnew, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 0777);
    if (fd_new < 0) return -1;
    int fd_old = orig_openat ? orig_openat(olddirfd, rold, O_RDONLY | O_CLOEXEC)
                             : openat(olddirfd, rold, O_RDONLY | O_CLOEXEC);
#endif
    if (fd_old < 0) {
        int err = errno;
        close(fd_new);
        unlinkat(newdirfd, rnew, 0);
        errno = err;
        return -1;
    }

    ssize_t sent = 0;
    int copy_failed = 0;
    while (sent < st.st_size) {
        ssize_t s = sendfile(fd_new, fd_old, NULL, (size_t)(st.st_size - sent));
        if (s > 0) {
            sent += s;
        } else {
            break;
        }
    }
    if (sent < st.st_size) {
        char cbuf[16384];
        ssize_t nread;
        while ((nread = read(fd_old, cbuf, sizeof(cbuf))) > 0) {
            ssize_t nwritten = 0;
            while (nwritten < nread) {
                ssize_t w = write(fd_new, cbuf + nwritten, nread - nwritten);
                if (w < 0) {
                    copy_failed = 1;
                    break;
                }
                nwritten += w;
            }
            if (copy_failed) break;
        }
        if (nread < 0) copy_failed = 1;
    }

    fchmod(fd_new, st.st_mode & 0777);
    // A hard link shares the times of the original (tar, dpkg and make compare them).
    const struct timespec times[2] = { st.st_atim, st.st_mtim };
    futimens(fd_new, times);
    struct stat st_new;
    memset(&st_new, 0, sizeof(st_new));
#ifndef CORTEX_HOST_TEST
    static int (*orig_fstat)(int, struct stat *) = NULL;
    if (!orig_fstat) orig_fstat = (int (*)(int, struct stat *))dlsym(RTLD_NEXT, "fstat");
    if (orig_fstat) orig_fstat(fd_new, &st_new);
    else fstat(fd_new, &st_new);
#else
    fstat(fd_new, &st_new);
#endif

    close(fd_old);
    close(fd_new);

    if (copy_failed) {
        unlinkat(newdirfd, rnew, 0);
        errno = EIO;
        return -1;
    }

    register_emulated_hardlink(st.st_dev, st.st_ino, st_new.st_dev, st_new.st_ino);
    return 0;
}

static inline int cortex_emulate_hardlink(const char *rold, const char *rnew) {
    return cortex_emulate_hardlinkat(AT_FDCWD, rold, AT_FDCWD, rnew);
}
