// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// System V shared memory (shmget, shmat, shmdt, shmctl). Android's seccomp filter blocks these system
// calls for apps (the SIGSYS handler turns them into ENOSYS), but X uses them: a program draws an image
// into a segment and the X server reads it from there (the MIT-SHM extension), and Qt, GTK and SDL
// programs expect it. The segments are files of their own in /tmp/.cortex-sysv-shm of the rootfs,
// which every process of the app can open and map:
//
//   id-N    the segment's description: "PID KEY MODE SIZE" (who made it, its key, its rights, its size)
//   seg-N   its memory (a file of SIZE bytes, mapped MAP_SHARED)
//   key-K   for a segment with a key: the N it belongs to (K in hex)
//
// N is chosen at random and claimed with O_EXCL, so processes never pick the same one. IPC_RMID
// removes the files at once: programs that attached the segment keep their mapping (as on Linux),
// later attaches fail, which is what X programs expect (they remove a segment as soon as the X
// server has attached it). The segments of processes that ended without removing theirs are deleted
// by the next shmget() that creates one.

#ifndef CORTEX_HOST_TEST

#define SHM_DIR "/tmp/.cortex-sysv-shm"
#define SHM_MAX_ATTACH 512

struct shm_meta {
    int pid;
    long key;
    unsigned mode;
    size_t size;
};

struct shm_attach {
    void *addr;
    size_t size;
    int id;
};

static struct shm_attach g_shm_attach[SHM_MAX_ATTACH];
static pthread_mutex_t g_shm_lock = PTHREAD_MUTEX_INITIALIZER;

// root + /tmp/.cortex-sysv-shm[/name]
static int shm_path(char *out, size_t outsz, const char *name) {
    int n = snprintf(out, outsz, "%s%s%s%s", g_cortex_root, SHM_DIR, name ? "/" : "", name ? name : "");
    return n > 0 && (size_t)n < outsz ? 0 : -1;
}

static int shm_open_file(const char *name, int flags, unsigned mode) {
    char path[PATH_MAX];
    if (shm_path(path, sizeof(path), name) != 0) return -1;
    return (int)syscall(SYS_openat, AT_FDCWD, path, flags | O_CLOEXEC, mode);
}

static void shm_unlink_file(const char *name) {
    char path[PATH_MAX];
    if (shm_path(path, sizeof(path), name) == 0) syscall(SYS_unlinkat, AT_FDCWD, path, 0);
}

static int shm_ensure_dir(void) {
    char path[PATH_MAX];
    if (shm_path(path, sizeof(path), NULL) != 0) return -1;
    if (syscall(SYS_mkdirat, AT_FDCWD, path, 0700) == 0 || errno == EEXIST) return 0;
    // /tmp of the rootfs missing too (a rootfs being set up)
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s/tmp", g_cortex_root);
    syscall(SYS_mkdirat, AT_FDCWD, tmp, 01777);
    return (syscall(SYS_mkdirat, AT_FDCWD, path, 0700) == 0 || errno == EEXIST) ? 0 : -1;
}

static int shm_read_small(const char *name, char *buf, size_t bufsz) {
    int fd = shm_open_file(name, O_RDONLY, 0);
    if (fd < 0) return -1;
    ssize_t n = (ssize_t)syscall(SYS_read, fd, buf, bufsz - 1);
    syscall(SYS_close, fd);
    if (n < 0) return -1;
    buf[n] = '\0';
    return (int)n;
}

// The description of segment `id`; waits a moment for one being written. 0, or -1 (errno EINVAL).
static int shm_read_meta(int id, struct shm_meta *meta) {
    char name[32], text[128];
    snprintf(name, sizeof(name), "id-%d", id);
    for (int attempt = 0; attempt < 50; attempt++) {
        int n = shm_read_small(name, text, sizeof(text));
        if (n < 0) break;
        unsigned long size = 0;
        if (n > 0 && sscanf(text, "%d %lx %o %lu", &meta->pid, &meta->key, &meta->mode, &size) == 4) {
            meta->size = (size_t)size;
            return 0;
        }
        struct timespec pause = { 0, 1000000 };
        nanosleep(&pause, NULL);
    }
    errno = EINVAL;
    return -1;
}

static int shm_process_alive(int pid) {
    return pid > 0 && (kill(pid, 0) == 0 || errno == EPERM);
}

static void shm_remove(int id, long key) {
    char name[32];
    if (key != IPC_PRIVATE) {
        // only when the key still names this segment
        char text[32];
        snprintf(name, sizeof(name), "key-%lx", key);
        if (shm_read_small(name, text, sizeof(text)) > 0 && atoi(text) == id) shm_unlink_file(name);
    }
    snprintf(name, sizeof(name), "seg-%d", id);
    shm_unlink_file(name);
    snprintf(name, sizeof(name), "id-%d", id);
    shm_unlink_file(name);
}

// Deletes the segments whose process has ended (a segment being written has no description yet and is
// left alone).
static void shm_collect(void) {
    char path[PATH_MAX];
    if (shm_path(path, sizeof(path), NULL) != 0) return;
    int dfd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dfd < 0) return;
    char dents[4096];
    int dead[64];
    int ndead = 0;
    for (;;) {
        long n = syscall(SYS_getdents64, dfd, dents, sizeof(dents));
        if (n <= 0) break;
        for (long at = 0; at < n;) {
            unsigned short reclen;
            memcpy(&reclen, dents + at + 16, sizeof(reclen));
            if (reclen == 0) break;
            const char *entry = dents + at + 19;
            if (strncmp(entry, "id-", 3) == 0 && ndead < 64) {
                int id = atoi(entry + 3);
                char text[128];
                struct shm_meta meta;
                if (shm_read_small(entry, text, sizeof(text)) > 0 &&
                    sscanf(text, "%d %lx %o", &meta.pid, &meta.key, &meta.mode) == 3 && !shm_process_alive(meta.pid)) {
                    dead[ndead++] = id;
                }
            }
            at += reclen;
        }
    }
    syscall(SYS_close, dfd);
    for (int i = 0; i < ndead; i++) {
        struct shm_meta meta;
        if (shm_read_meta(dead[i], &meta) == 0) shm_remove(dead[i], meta.key);
    }
}

// A new segment: claims an id, makes its memory, then writes its description. Returns the id or -1.
static int shm_create(long key, size_t size, unsigned mode) {
    unsigned seed = (unsigned)getpid() * 2654435761u ^ (unsigned)time(NULL);
    for (int attempt = 0; attempt < 64; attempt++) {
        seed = seed * 1103515245u + 12345u;
        int id = (int)((seed >> 1) & 0x3fffffff);
        if (id == 0) continue;
        char name[32];
        snprintf(name, sizeof(name), "id-%d", id);
        int meta_fd = shm_open_file(name, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (meta_fd < 0) {
            if (errno == EEXIST) continue;
            return -1;
        }
        snprintf(name, sizeof(name), "seg-%d", id);
        int seg_fd = shm_open_file(name, O_RDWR | O_CREAT | O_TRUNC, 0600);
        long page = sysconf(_SC_PAGESIZE);
        size_t rounded = page > 0 ? (size + (size_t)page - 1) / (size_t)page * (size_t)page : size;
        if (seg_fd < 0 || ftruncate(seg_fd, (off_t)rounded) != 0) {
            int saved = errno;
            if (seg_fd >= 0) syscall(SYS_close, seg_fd);
            syscall(SYS_close, meta_fd);
            shm_remove(id, IPC_PRIVATE);
            errno = saved == EFBIG ? EINVAL : (saved ? saved : ENOMEM);
            return -1;
        }
        syscall(SYS_close, seg_fd);
        char text[128];
        int len = snprintf(text, sizeof(text), "%d %lx %o %lu\n", getpid(), key, mode & 0777, (unsigned long)size);
        ssize_t written = (ssize_t)syscall(SYS_write, meta_fd, text, (size_t)len);
        syscall(SYS_close, meta_fd);
        if (written != len) {
            shm_remove(id, IPC_PRIVATE);
            errno = ENOSPC;
            return -1;
        }
        return id;
    }
    errno = ENOSPC;
    return -1;
}

int shmget(key_t key, size_t size, int shmflg) {
    init_cortex_hook();
    if (g_cortex_root[0] == '\0') {
        errno = ENOSYS;
        return -1;
    }
    if (shm_ensure_dir() != 0) return -1;
    if (shmflg & IPC_CREAT) shm_collect();
    if (key == IPC_PRIVATE) {
        if (size == 0) {
            errno = EINVAL;
            return -1;
        }
        return shm_create(IPC_PRIVATE, size, (unsigned)shmflg);
    }
    char key_name[32];
    snprintf(key_name, sizeof(key_name), "key-%lx", (long)key);
    for (int attempt = 0; attempt < 8; attempt++) {
        char text[32];
        if (shm_read_small(key_name, text, sizeof(text)) > 0) {
            int id = atoi(text);
            struct shm_meta meta;
            if (id > 0 && shm_read_meta(id, &meta) == 0) {
                if ((shmflg & IPC_CREAT) && (shmflg & IPC_EXCL)) {
                    errno = EEXIST;
                    return -1;
                }
                if (size > meta.size) {
                    errno = EINVAL;
                    return -1;
                }
                return id;
            }
            shm_unlink_file(key_name); // names a segment that is gone
        }
        if (!(shmflg & IPC_CREAT)) {
            errno = ENOENT;
            return -1;
        }
        if (size == 0) {
            errno = EINVAL;
            return -1;
        }
        int id = shm_create((long)key, size, (unsigned)shmflg);
        if (id < 0) return -1;
        int kfd = shm_open_file(key_name, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (kfd >= 0) {
            int len = snprintf(text, sizeof(text), "%d\n", id);
            syscall(SYS_write, kfd, text, (size_t)len);
            syscall(SYS_close, kfd);
            return id;
        }
        // another process made the key's segment at the same time: use that one
        shm_remove(id, IPC_PRIVATE);
        if (errno != EEXIST) return -1;
    }
    errno = EAGAIN;
    return -1;
}

void *shmat(int shmid, const void *shmaddr, int shmflg) {
    init_cortex_hook();
    struct shm_meta meta;
    if (g_cortex_root[0] == '\0' || shm_read_meta(shmid, &meta) != 0) {
        errno = EINVAL;
        return (void *)-1;
    }
    int readonly = (shmflg & SHM_RDONLY) != 0;
    char name[32];
    snprintf(name, sizeof(name), "seg-%d", shmid);
    int fd = shm_open_file(name, readonly ? O_RDONLY : O_RDWR, 0);
    if (fd < 0) {
        errno = EINVAL;
        return (void *)-1;
    }
    void *want = (void *)shmaddr;
    int flags = MAP_SHARED;
    if (want) {
        if (shmflg & SHM_RND) want = (void *)((uintptr_t)want & ~((uintptr_t)SHMLBA - 1));
        else if ((uintptr_t)want & ((uintptr_t)sysconf(_SC_PAGESIZE) - 1)) {
            syscall(SYS_close, fd);
            errno = EINVAL;
            return (void *)-1;
        }
        flags |= MAP_FIXED;
    }
    void *addr = mmap(want, meta.size, readonly ? PROT_READ : (PROT_READ | PROT_WRITE), flags, fd, 0);
    int saved = errno;
    syscall(SYS_close, fd);
    if (addr == MAP_FAILED) {
        errno = saved == ENOMEM ? ENOMEM : EINVAL;
        return (void *)-1;
    }
    pthread_mutex_lock(&g_shm_lock);
    for (int i = 0; i < SHM_MAX_ATTACH; i++) {
        if (!g_shm_attach[i].addr) {
            g_shm_attach[i].addr = addr;
            g_shm_attach[i].size = meta.size;
            g_shm_attach[i].id = shmid;
            break;
        }
    }
    pthread_mutex_unlock(&g_shm_lock);
    return addr;
}

int shmdt(const void *shmaddr) {
    pthread_mutex_lock(&g_shm_lock);
    for (int i = 0; i < SHM_MAX_ATTACH; i++) {
        if (g_shm_attach[i].addr && g_shm_attach[i].addr == shmaddr) {
            void *addr = g_shm_attach[i].addr;
            size_t size = g_shm_attach[i].size;
            g_shm_attach[i].addr = NULL;
            pthread_mutex_unlock(&g_shm_lock);
            munmap(addr, size);
            return 0;
        }
    }
    pthread_mutex_unlock(&g_shm_lock);
    errno = EINVAL;
    return -1;
}

static int shm_attached_here(int id) {
    int count = 0;
    pthread_mutex_lock(&g_shm_lock);
    for (int i = 0; i < SHM_MAX_ATTACH; i++) {
        if (g_shm_attach[i].addr && g_shm_attach[i].id == id) count++;
    }
    pthread_mutex_unlock(&g_shm_lock);
    return count;
}

int shmctl(int shmid, int cmd, struct shmid_ds *buf) {
    init_cortex_hook();
    if (g_cortex_root[0] == '\0') {
        errno = ENOSYS;
        return -1;
    }
    switch (cmd) {
    case IPC_INFO:
    case SHM_INFO:
        if (buf) {
            memset(buf, 0, sizeof(struct shminfo) > sizeof(*buf) ? sizeof(*buf) : sizeof(struct shminfo));
            if (cmd == IPC_INFO) {
                struct shminfo *info = (struct shminfo *)buf;
                info->shmmax = (unsigned long)1 << 40;
                info->shmmin = 1;
                info->shmmni = 4096;
                info->shmseg = 4096;
                info->shmall = (unsigned long)1 << 30;
            }
        }
        return 0;
    case SHM_LOCK:
    case SHM_UNLOCK:
        return 0;
    default:
        break;
    }
    struct shm_meta meta;
    if (shm_read_meta(shmid, &meta) != 0) {
        errno = EINVAL;
        return -1;
    }
    switch (cmd) {
    case IPC_STAT:
    case SHM_STAT:
    case SHM_STAT_ANY:
        if (!buf) {
            errno = EFAULT;
            return -1;
        }
        memset(buf, 0, sizeof(*buf));
        buf->shm_perm.__key = (key_t)meta.key;
        // The owner's rights for everyone of the app: X compares the uid of its client with the owner's,
        // and the two can differ in Cortex (root emulated for one, not the other)
        unsigned owner = meta.mode & 0600;
        buf->shm_perm.mode = (unsigned short)(owner | (owner >> 3) | (owner >> 6));
        buf->shm_perm.uid = buf->shm_perm.cuid = geteuid();
        buf->shm_perm.gid = buf->shm_perm.cgid = getegid();
        buf->shm_segsz = meta.size;
        buf->shm_cpid = meta.pid;
        buf->shm_lpid = getpid();
        buf->shm_nattch = (shmatt_t)(shm_attached_here(shmid) + (meta.pid != getpid() ? 1 : 0));
        buf->shm_ctime = time(NULL);
        return cmd == IPC_STAT ? 0 : shmid;
    case IPC_SET:
        return 0;
    case IPC_RMID:
        shm_remove(shmid, meta.key);
        return 0;
    default:
        errno = EINVAL;
        return -1;
    }
}

#endif /* !CORTEX_HOST_TEST */
