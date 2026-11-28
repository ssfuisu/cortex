// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Who a program is, and who owns a file, as the programs see it.
//
// The kernel knows one user, the app's. The programs see users of Ubuntu's own: every process
// starts as root, and one that changes its ids (su, runuser, a server that drops to its user)
// becomes that user for itself and for what it starts. Its ids are kept here and handed to every
// program it starts in CORTEX_IDS, which the exec code writes from the ids of the process that
// starts it (an environment that was cleared or kept from before the change does not matter). A
// process without it is root, as before.
//
// A file the app owns is root's, unless it was given to another user: chown() records the owner
// in a table that every process of the rootfs maps ($CORTEX_ROOT/var/lib/cortex/owners), by
// device and inode, and a file a process of another user makes is recorded as that user's. The
// records go with the file when it is renamed and when it is removed. PostgreSQL checks that its
// data directory is its own, and pg_ctlcluster starts the server as the owner of that directory.
//
// "cortex-api no-fakeroot" starts a shell whose ids are the app's real ones: then nothing is
// presented, files are the app user's and the programs run as an ordinary user.

// --- Ids ------------------------------------------------------------------------------------

#define CORTEX_IDS_MAX_GROUPS 32

typedef struct {
    uid_t r, e, s;
    gid_t gr, ge, gs;
    int ngroups; // 0: the one group of the default (ge)
    gid_t groups[CORTEX_IDS_MAX_GROUPS];
} cortex_ids_t;

static cortex_ids_t g_ids;  // all 0: root
static int g_ids_loaded = 0;
// Changes of the ids from two threads at once (setuid() and setgid()) are made one after the other.
// A spin lock: the SIGSYS handler can take it too (no system call is made while it is held, so the
// handler never runs in a thread that holds it).
static int g_ids_lock = 0;

static void ids_lock(void) {
    while (__atomic_exchange_n(&g_ids_lock, 1, __ATOMIC_ACQUIRE)) {
        while (__atomic_load_n(&g_ids_lock, __ATOMIC_RELAXED)) {
        }
    }
}

static void ids_unlock(void) {
    __atomic_store_n(&g_ids_lock, 0, __ATOMIC_RELEASE);
}
// The app's real user and group (hardlinks.c keeps them: g_app_uid, g_app_gid, -1 until known)
static void ids_load_app(void) {
    if (__atomic_load_n(&g_app_uid, __ATOMIC_RELAXED) == (uid_t)-1) load_app_ids();
}

static int ids_parse_list(const char *p, unsigned long *out, int max, char end, const char **rest) {
    int n = 0;
    while (*p && *p != end) {
        if (n >= max || *p < '0' || *p > '9') return -1;
        char *stop;
        unsigned long v = strtoul(p, &stop, 10);
        if (stop == p || v > 0xffffffffUL) return -1;
        out[n++] = v;
        p = stop;
        if (*p == ',') p++;
        else if (*p && *p != end) return -1;
    }
    *rest = p;
    return n;
}

// CORTEX_IDS=ruid,euid,suid:rgid,egid,sgid[:group,group...]
static int ids_parse(const char *s, cortex_ids_t *out) {
    if (!s || !*s) return -1;
    unsigned long u[3], g[3], groups[CORTEX_IDS_MAX_GROUPS];
    const char *p = s;
    if (ids_parse_list(p, u, 3, ':', &p) != 3 || *p != ':') return -1;
    if (ids_parse_list(p + 1, g, 3, ':', &p) != 3) return -1;
    int n = 0;
    if (*p == ':') {
        n = ids_parse_list(p + 1, groups, CORTEX_IDS_MAX_GROUPS, '\0', &p);
        if (n < 0) return -1;
    }
    if (*p) return -1;
    memset(out, 0, sizeof(*out));
    out->r = (uid_t)u[0];
    out->e = (uid_t)u[1];
    out->s = (uid_t)u[2];
    out->gr = (gid_t)g[0];
    out->ge = (gid_t)g[1];
    out->gs = (gid_t)g[2];
    out->ngroups = n;
    for (int i = 0; i < n; i++) out->groups[i] = (gid_t)groups[i];
    return 0;
}

static int ids_format(const cortex_ids_t *ids, char *buf, size_t size) {
    int n = snprintf(buf, size, "%u,%u,%u:%u,%u,%u", (unsigned)ids->r, (unsigned)ids->e, (unsigned)ids->s,
                     (unsigned)ids->gr, (unsigned)ids->ge, (unsigned)ids->gs);
    if (n < 0 || (size_t)n >= size) return -1;
    for (int i = 0; i < ids->ngroups; i++) {
        int m = snprintf(buf + n, size - (size_t)n, "%c%u", i == 0 ? ':' : ',', (unsigned)ids->groups[i]);
        if (m < 0 || (size_t)m >= size - (size_t)n) return -1;
        n += m;
    }
    return n;
}

static int ids_are_root(const cortex_ids_t *ids) {
    return ids->r == 0 && ids->e == 0 && ids->s == 0 && ids->gr == 0 && ids->ge == 0 && ids->gs == 0 &&
           (ids->ngroups == 0 || (ids->ngroups == 1 && ids->groups[0] == 0));
}

#ifdef CORTEX_HOST_TEST
static char *cortex_libc_getenv(const char *name) {
    return getenv(name);
}
#else
// Programs such as GNU bash export their own getenv()/setenv() symbols in the main executable.
// Calling bash's setenv() from an LD_PRELOAD constructor before main() prematurely creates
// bash's shell_variables table, causing subsequent bash getenv() calls to stop scanning environ
// (so ids_load() saw NULL for CORTEX_IDS when bash started with a clean env such as from sshd).
// Bypass main-executable interposition by resolving libc's getenv/setenv via RTLD_NEXT.
static char *cortex_libc_getenv(const char *name) {
    static char *(*orig_getenv)(const char *) = NULL;
    if (!orig_getenv) orig_getenv = (char *(*)(const char *))dlsym(RTLD_NEXT, "getenv");
    return orig_getenv ? orig_getenv(name) : getenv(name);
}

static int cortex_libc_setenv(const char *name, const char *value, int overwrite) {
    static int (*orig_setenv)(const char *, const char *, int) = NULL;
    if (!orig_setenv) orig_setenv = (int (*)(const char *, const char *, int))dlsym(RTLD_NEXT, "setenv");
    return orig_setenv ? orig_setenv(name, value, overwrite) : setenv(name, value, overwrite);
}
#endif

// Read once, before the program runs (the constructor) or at the first question
static void ids_load(void) {
    if (__atomic_load_n(&g_ids_loaded, __ATOMIC_ACQUIRE)) return;
    cortex_ids_t ids;
    if (ids_parse(cortex_libc_getenv("CORTEX_IDS"), &ids) == 0) g_ids = ids;
    __atomic_store_n(&g_ids_loaded, 1, __ATOMIC_RELEASE);
}

static const cortex_ids_t *ids_now(void) {
    ids_load();
    return &g_ids;
}

// The app's real user, which "cortex-api no-fakeroot" runs as: nothing is presented for it
static int ids_app_mode(void) {
    const cortex_ids_t *ids = ids_now();
    if (ids->e == 0) return 0;
    ids_load_app();
    uid_t app = __atomic_load_n(&g_app_uid, __ATOMIC_RELAXED);
    return app != (uid_t)-1 && ids->e == app;
}

static void ids_set_app(cortex_ids_t *ids) {
    ids_load_app();
    memset(ids, 0, sizeof(*ids));
    ids->r = ids->e = ids->s = g_app_uid;
    ids->gr = ids->ge = ids->gs = g_app_gid;
}

// A file of a user other than root that this process makes is recorded as its own
static int ids_records_new_files(void) {
    const cortex_ids_t *ids = ids_now();
    return (ids->e != 0 || ids->ge != 0) && !ids_app_mode();
}

static int ids_in_groups(const cortex_ids_t *ids, gid_t gid) {
    if (gid == ids->ge) return 1;
    for (int i = 0; i < ids->ngroups; i++) {
        if (ids->groups[i] == gid) return 1;
    }
    return 0;
}

// setresuid() and setresgid() as the kernel does them: the privileged (effective user root) may set
// any id, the others only one they have (-1 leaves one as it is)
static int ids_setres_locked(int group, unsigned long r, unsigned long e, unsigned long s) {
    cortex_ids_t ids = g_ids;
    int privileged = ids.e == 0;
    uid_t *cur = group ? (uid_t *)&ids.gr : &ids.r;
    uid_t want[3] = {(uid_t)r, (uid_t)e, (uid_t)s};
    uid_t have[3] = {cur[0], cur[1], cur[2]};
    for (int i = 0; i < 3; i++) {
        if (want[i] == (uid_t)-1) continue;
        if (!privileged && want[i] != have[0] && want[i] != have[1] && want[i] != have[2]) {
            errno = EPERM;
            return -1;
        }
    }
    for (int i = 0; i < 3; i++) {
        if (want[i] != (uid_t)-1) cur[i] = want[i];
    }
    g_ids = ids;
    return 0;
}

static int ids_setres(int group, unsigned long r, unsigned long e, unsigned long s) {
    ids_load();
    ids_lock();
    int ret = ids_setres_locked(group, r, e, s);
    ids_unlock();
    return ret;
}

static int ids_setuid(int group, unsigned long id) {
    ids_load();
    ids_lock();
    int ret = g_ids.e == 0 ? ids_setres_locked(group, id, id, id) : ids_setres_locked(group, (unsigned long)-1, id, (unsigned long)-1);
    ids_unlock();
    return ret;
}

// setreuid(): the real id may become the effective one, the effective one any of the three; the
// saved id follows the effective one when the real one is set, or the effective one is set to
// something other than the old real one
static int ids_setre(int group, unsigned long r, unsigned long e) {
    ids_load();
    ids_lock();
    int ret = 0;
    cortex_ids_t ids = g_ids;
    uid_t *cur = group ? (uid_t *)&ids.gr : &ids.r;
    int privileged = ids.e == 0;
    uid_t old_r = cur[0], old_e = cur[1], old_s = cur[2];
    if (!privileged) {
        if (((uid_t)r != (uid_t)-1 && (uid_t)r != old_r && (uid_t)r != old_e) ||
            ((uid_t)e != (uid_t)-1 && (uid_t)e != old_r && (uid_t)e != old_e && (uid_t)e != old_s)) {
            errno = EPERM;
            ret = -1;
        }
    }
    if (ret == 0) {
        if ((uid_t)r != (uid_t)-1) cur[0] = (uid_t)r;
        if ((uid_t)e != (uid_t)-1) cur[1] = (uid_t)e;
        if ((uid_t)r != (uid_t)-1 || ((uid_t)e != (uid_t)-1 && (uid_t)e != old_r)) cur[2] = cur[1];
        g_ids = ids;
    }
    ids_unlock();
    return ret;
}

static int ids_setgroups(size_t n, const gid_t *list) {
    ids_load();
    if (n > 0 && !list) {
        errno = EFAULT;
        return -1;
    }
    ids_lock();
    if (g_ids.e != 0) {
        ids_unlock();
        errno = EPERM;
        return -1;
    }
    if (n > CORTEX_IDS_MAX_GROUPS) n = CORTEX_IDS_MAX_GROUPS;
    g_ids.ngroups = (int)n;
    for (size_t i = 0; i < n; i++) g_ids.groups[i] = list[i];
    // An empty list (setgroups(0, NULL), done before dropping to a user) leaves no group: the
    // default of one group stands for "not changed", so keep the effective group
    if (n == 0) {
        g_ids.ngroups = 1;
        g_ids.groups[0] = g_ids.ge;
    }
    ids_unlock();
    return 0;
}

// --- Owners ----------------------------------------------------------------------------------

#define CORTEX_OW_MAGIC 0x6e774f43u // "COwn"
#define CORTEX_OW_VERSION 1u
#define CORTEX_OW_SLOTS 8192u      // a power of two
#define CORTEX_OW_MAX_USED 6144u   // at most 3/4 full
#define CORTEX_OW_EMPTY 0u
#define CORTEX_OW_USED 1u
#define CORTEX_OW_DELETED 2u

typedef struct {
    uint64_t dev, ino;
    uint32_t uid, gid;
    uint32_t state;
    uint32_t pad;
} cortex_ow_slot_t;

typedef struct {
    uint32_t magic, version;
    uint32_t seq;                  // odd while a writer changes the table
    uint32_t used, deleted;
    uint32_t pad[11];
    cortex_ow_slot_t slots[CORTEX_OW_SLOTS];
} cortex_ow_table_t;

#define CORTEX_OW_MAP_SIZE ((sizeof(cortex_ow_table_t) + 0xffff) & ~(size_t)0xffff)

static cortex_ow_table_t *g_ow_table = NULL;
static long g_ow_next_try = 0;
static pthread_mutex_t g_ow_lock = PTHREAD_MUTEX_INITIALIZER;

#ifndef CORTEX_HOST_TEST
static void init_cortex_hook(void);
#endif

static size_t ow_table_path(char *buf, size_t size) {
#ifndef CORTEX_HOST_TEST
    init_cortex_hook();
#endif
    if (g_cortex_root_len == 0) return 0;
    static const char rel[] = "/var/lib/cortex/owners";
    if (g_cortex_root_len + sizeof(rel) > size) return 0;
    memcpy(buf, g_cortex_root, g_cortex_root_len);
    memcpy(buf + g_cortex_root_len, rel, sizeof(rel));
    return g_cortex_root_len + sizeof(rel) - 1;
}

static long ow_now_seconds(void) {
    struct timespec ts;
#ifdef CLOCK_MONOTONIC_COARSE
    if (clock_gettime(CLOCK_MONOTONIC_COARSE, &ts) == 0) return (long)ts.tv_sec;
#endif
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) return (long)ts.tv_sec;
    return 0;
}

// Maps the table: readers (create = 0) get NULL while no process has made the file, and look for
// it again a second later; writers make it. Readers never wait for the lock.
static cortex_ow_table_t *ow_table(int create) {
    cortex_ow_table_t *t = __atomic_load_n(&g_ow_table, __ATOMIC_ACQUIRE);
    if (t) return t;
    if (!create && __atomic_load_n(&g_ow_next_try, __ATOMIC_RELAXED) > ow_now_seconds()) return NULL;
    int saved_errno = errno;
    if (create) {
        pthread_mutex_lock(&g_ow_lock);
    } else if (pthread_mutex_trylock(&g_ow_lock) != 0) {
        errno = saved_errno;
        return NULL;
    }
    t = g_ow_table;
    if (!t) {
        char path[PATH_MAX];
        if (ow_table_path(path, sizeof(path))) {
            if (create) {
                // Its directory: var/lib/cortex exists in every rootfs, but a test's may not
                char dir[PATH_MAX];
                size_t n = strlen(path);
                memcpy(dir, path, n + 1);
                char *slash = strrchr(dir, '/');
                if (slash) {
                    *slash = '\0';
                    for (char *p = dir + g_cortex_root_len + 1; *p; p++) {
                        if (*p != '/') continue;
                        *p = '\0';
                        syscall(SYS_mkdirat, AT_FDCWD, dir, 0755);
                        *p = '/';
                    }
                    syscall(SYS_mkdirat, AT_FDCWD, dir, 0755);
                }
            }
            int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_CLOEXEC | (create ? O_CREAT : 0), 0600);
            if (fd >= 0) {
                off_t size = lseek(fd, 0, SEEK_END);
                if (size < (off_t)CORTEX_OW_MAP_SIZE && create) {
                    if (ftruncate(fd, (off_t)CORTEX_OW_MAP_SIZE) == 0) size = (off_t)CORTEX_OW_MAP_SIZE;
                }
                if (size >= (off_t)CORTEX_OW_MAP_SIZE) {
                    void *m = mmap(NULL, CORTEX_OW_MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                    if (m != MAP_FAILED) {
                        t = (cortex_ow_table_t *)m;
                        if (__atomic_load_n(&t->magic, __ATOMIC_ACQUIRE) != CORTEX_OW_MAGIC) {
                            // A new file: its header, under the writers' lock
                            struct flock fl;
                            memset(&fl, 0, sizeof(fl));
                            fl.l_type = F_WRLCK;
                            fl.l_whence = SEEK_SET;
                            while (fcntl(fd, F_SETLKW, &fl) != 0 && errno == EINTR) {
                            }
                            if (__atomic_load_n(&t->magic, __ATOMIC_ACQUIRE) != CORTEX_OW_MAGIC) {
                                memset(t, 0, sizeof(*t));
                                t->version = CORTEX_OW_VERSION;
                                __atomic_store_n(&t->magic, CORTEX_OW_MAGIC, __ATOMIC_RELEASE);
                            }
                        }
                        if (t->version != CORTEX_OW_VERSION) {
                            munmap(m, CORTEX_OW_MAP_SIZE);
                            t = NULL;
                        }
                    }
                }
                syscall(SYS_close, fd);
            }
        }
        if (t) {
            __atomic_store_n(&g_ow_table, t, __ATOMIC_RELEASE);
        } else {
            __atomic_store_n(&g_ow_next_try, ow_now_seconds() + 1, __ATOMIC_RELAXED);
        }
    }
    pthread_mutex_unlock(&g_ow_lock);
    errno = saved_errno;
    return t;
}

static uint32_t ow_hash(uint64_t dev, uint64_t ino) {
    uint64_t h = (dev * 0x9e3779b97f4a7c15ULL) ^ (ino * 0xc2b2ae3d27d4eb4fULL);
    h ^= h >> 29;
    return (uint32_t)h & (CORTEX_OW_SLOTS - 1);
}

// Whether some file has an owner of its own: a stat() of everything else stops here
static int ow_maybe_any(void) {
    cortex_ow_table_t *t = ow_table(0);
    return t && __atomic_load_n(&t->used, __ATOMIC_RELAXED) > 0;
}

static int ow_get(uint64_t dev, uint64_t ino, uid_t *uid, gid_t *gid) {
    if (!ow_maybe_any()) return 0;
    cortex_ow_table_t *t = g_ow_table;
    // Writers hold the table for a moment; a sequence that stays odd is one a writer left when it
    // died (the next writer repairs it): after a while the table is read as it is
    for (int attempt = 0; attempt < 4096; attempt++) {
        uint32_t seq = __atomic_load_n(&t->seq, __ATOMIC_ACQUIRE);
        int last = attempt == 4095;
        if ((seq & 1) && !last) {
            if ((attempt & 63) == 63) sched_yield();
            continue;
        }
        int found = 0;
        uint32_t u = 0, g = 0;
        uint32_t i = ow_hash(dev, ino);
        for (uint32_t n = 0; n < CORTEX_OW_SLOTS; n++, i = (i + 1) & (CORTEX_OW_SLOTS - 1)) {
            const cortex_ow_slot_t *s = &t->slots[i];
            uint32_t state = __atomic_load_n(&s->state, __ATOMIC_RELAXED);
            if (state == CORTEX_OW_EMPTY) break;
            if (state == CORTEX_OW_USED && s->dev == dev && s->ino == ino) {
                u = s->uid;
                g = s->gid;
                found = 1;
                break;
            }
        }
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (!last && __atomic_load_n(&t->seq, __ATOMIC_RELAXED) != seq) continue;
        if (found) {
            *uid = (uid_t)u;
            *gid = (gid_t)g;
        }
        return found;
    }
    return 0;
}

static int ow_lock_file(void) {
    char path[PATH_MAX];
    if (!ow_table_path(path, sizeof(path))) return -1;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct flock fl;
    memset(&fl, 0, sizeof(fl));
    fl.l_type = F_WRLCK;
    fl.l_whence = SEEK_SET;
    for (;;) {
        if (fcntl(fd, F_SETLKW, &fl) == 0) return fd;
        if (errno != EINTR) break;
    }
    syscall(SYS_close, fd);
    return -1;
}

// Records the owner of a file (removing the entry when it matches the default owner for its
// directory: 0:0 in system dirs, 1000:1000 under $CORTEX_ROOT/home); with set = 0 only removes it.
static void ow_put_for(uint64_t dev, uint64_t ino, uid_t uid, gid_t gid, uid_t def_uid, gid_t def_gid, int set) {
    if (set && uid == def_uid && gid == def_gid) set = 0;
    // Nothing to remove without a table or records: no lock
    if (!set && !ow_maybe_any()) return;
    cortex_ow_table_t *t = ow_table(set);
    if (!t) return;
    int saved_errno = errno;
    pthread_mutex_lock(&g_ow_lock);
    int fd = ow_lock_file();
    if (fd < 0) {
        // Without the lock other processes could write at the same time: the change is not made
        pthread_mutex_unlock(&g_ow_lock);
        errno = saved_errno;
        return;
    }
    // A writer that died while it wrote left the sequence odd: make it even again
    if (t->seq & 1) __atomic_store_n(&t->seq, t->seq + 1, __ATOMIC_RELEASE);
    __atomic_store_n(&t->seq, t->seq + 1, __ATOMIC_RELEASE);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    uint32_t i = ow_hash(dev, ino);
    cortex_ow_slot_t *free_slot = NULL;
    cortex_ow_slot_t *hit = NULL;
    for (uint32_t n = 0; n < CORTEX_OW_SLOTS; n++, i = (i + 1) & (CORTEX_OW_SLOTS - 1)) {
        cortex_ow_slot_t *s = &t->slots[i];
        if (s->state == CORTEX_OW_EMPTY) {
            if (!free_slot) free_slot = s;
            break;
        }
        if (s->state == CORTEX_OW_DELETED) {
            if (!free_slot) free_slot = s;
            continue;
        }
        if (s->dev == dev && s->ino == ino) {
            hit = s;
            break;
        }
    }
    if (hit) {
        if (set) {
            hit->uid = (uint32_t)uid;
            hit->gid = (uint32_t)gid;
        } else {
            __atomic_store_n(&hit->state, CORTEX_OW_DELETED, __ATOMIC_RELAXED);
            t->used--;
            t->deleted++;
        }
    } else if (set && free_slot && t->used < CORTEX_OW_MAX_USED) {
        if (free_slot->state == CORTEX_OW_DELETED) t->deleted--;
        free_slot->dev = dev;
        free_slot->ino = ino;
        free_slot->uid = (uint32_t)uid;
        free_slot->gid = (uint32_t)gid;
        __atomic_store_n(&free_slot->state, CORTEX_OW_USED, __ATOMIC_RELAXED);
        t->used++;
    }
    // Many removals leave long chains of deleted slots: rebuild the table in place
    if (t->deleted > CORTEX_OW_SLOTS / 4) {
        static cortex_ow_slot_t live[CORTEX_OW_MAX_USED];
        uint32_t n_live = 0;
        for (uint32_t k = 0; k < CORTEX_OW_SLOTS; k++) {
            if (t->slots[k].state == CORTEX_OW_USED && n_live < CORTEX_OW_MAX_USED) live[n_live++] = t->slots[k];
        }
        memset(t->slots, 0, sizeof(t->slots));
        for (uint32_t k = 0; k < n_live; k++) {
            uint32_t j = ow_hash(live[k].dev, live[k].ino);
            while (t->slots[j].state != CORTEX_OW_EMPTY) j = (j + 1) & (CORTEX_OW_SLOTS - 1);
            t->slots[j] = live[k];
        }
        t->used = n_live;
        t->deleted = 0;
    }
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    __atomic_store_n(&t->seq, t->seq + 1, __ATOMIC_RELEASE);
    syscall(SYS_close, fd);
    pthread_mutex_unlock(&g_ow_lock);
    errno = saved_errno;
}

static void ow_put(uint64_t dev, uint64_t ino, uid_t uid, gid_t gid, int set) {
    ow_put_for(dev, ino, uid, gid, 0, 0, set);
}

#define CORTEX_HOME_FD_WORDS 16
static uint64_t g_home_fds[CORTEX_HOME_FD_WORDS];
static int g_cwd_in_home = -1;

static int is_in_cortex_home(const char *path) {
    if (!path || g_cortex_root_len == 0) return 0;
    if (strncmp(path, g_cortex_root, g_cortex_root_len) != 0) return 0;
    const char *sub = path + g_cortex_root_len;
    return strncmp(sub, "/home", 5) == 0 && (sub[5] == '/' || sub[5] == '\0');
}

static void home_fd_set(int fd, int in_home) {
    if (fd < 0 || fd >= CORTEX_HOME_FD_WORDS * 64) return;
    uint64_t mask = 1ULL << (fd & 63);
    if (in_home) __atomic_fetch_or(&g_home_fds[fd >> 6], mask, __ATOMIC_RELAXED);
    else __atomic_fetch_and(&g_home_fds[fd >> 6], ~mask, __ATOMIC_RELAXED);
}

static int home_fd_is(int fd) {
    if (fd < 0 || fd >= CORTEX_HOME_FD_WORDS * 64) return 0;
    return (__atomic_load_n(&g_home_fds[fd >> 6], __ATOMIC_RELAXED) & (1ULL << (fd & 63))) != 0;
}

static int cwd_is_home(void) {
    int v = __atomic_load_n(&g_cwd_in_home, __ATOMIC_RELAXED);
    if (v >= 0) return v;
    char cwd[PATH_MAX];
    int in_home = 0;
#ifdef CORTEX_HOST_TEST
    if (getcwd(cwd, sizeof(cwd)) != NULL) in_home = is_in_cortex_home(cwd);
#else
    ssize_t n = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, "/proc/self/cwd", cwd, sizeof(cwd) - 1);
    if (n > 0) {
        cwd[n] = '\0';
        in_home = is_in_cortex_home(cwd);
    }
#endif
    __atomic_store_n(&g_cwd_in_home, in_home, __ATOMIC_RELAXED);
    return in_home;
}

static void cwd_update_chdir(const char *target) {
    if (target && target[0] == '/' && !strstr(target, "..")) {
        __atomic_store_n(&g_cwd_in_home, is_in_cortex_home(target), __ATOMIC_RELAXED);
    } else {
        __atomic_store_n(&g_cwd_in_home, -1, __ATOMIC_RELAXED);
    }
}

static void cwd_update_fchdir(int fd) {
    __atomic_store_n(&g_cwd_in_home, fd >= 0 ? home_fd_is(fd) : -1, __ATOMIC_RELAXED);
}

static int path_is_home_at(int dirfd, const char *path) {
    if (g_cortex_root_len == 0) return 0;
    if (path && path[0] == '/') {
        if (!strstr(path, "..")) return is_in_cortex_home(path);
        return 0;
    }
    if (path && strstr(path, "..")) return 0;
    return dirfd == AT_FDCWD ? cwd_is_home() : home_fd_is(dirfd);
}

static int ow_real_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
#ifdef CORTEX_HOST_TEST
    return fstatat(dirfd, path, st, flags);
#else
    // glibc's: this library's fstatat() presents what this code records
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))cortex_next((void *)fstatat, "fstatat");
    return orig_fstatat ? orig_fstatat(dirfd, path, st, flags) : -1;
#endif
}

static int ow_real_fstat(int fd, struct stat *st) {
    return ow_real_fstatat(fd, "", st, AT_EMPTY_PATH);
}

// The owner a program sees for a file the kernel says is the app's (uid or gid NULL: not asked).
// dev and ino 0: no file to look up (the default only).
static void present_owner_ex(uid_t *uid, gid_t *gid, uint64_t dev, uint64_t ino, uid_t def_uid, gid_t def_gid) {
    ids_load_app();
    uid_t app_uid = __atomic_load_n(&g_app_uid, __ATOMIC_RELAXED);
    gid_t app_gid = __atomic_load_n(&g_app_gid, __ATOMIC_RELAXED);
    if (app_uid == (uid_t)-1) return;
    int is_app_uid = uid && *uid == app_uid;
    int is_app_gid = gid && *gid == app_gid;
    if (!is_app_uid && !is_app_gid) return;
    uid_t u = def_uid;
    gid_t g = def_gid;
    if (ids_app_mode()) {
        u = app_uid;
        g = app_gid;
    }
    if (dev || ino) ow_get(dev, ino, &u, &g);
    if (is_app_uid) *uid = u;
    if (is_app_gid) *gid = g;
}

// The owner a program sees for an open file or a name (NULL path: the descriptor)
static int ow_presented_at_for(int dirfd, const char *path, int flags, struct stat *st, uid_t def_uid, gid_t def_gid) {
    int ret = path ? ow_real_fstatat(dirfd, path, st, flags) : ow_real_fstat(dirfd, st);
    if (ret == 0) present_owner_ex(&st->st_uid, &st->st_gid, (uint64_t)st->st_dev, (uint64_t)st->st_ino, def_uid, def_gid);
    return ret;
}

static int ow_presented_at(int dirfd, const char *path, int flags, struct stat *st) {
    return ow_presented_at_for(dirfd, path, flags, st, 0, 0);
}

// chown() and its relatives: root records the new owner of a file of the app; an ordinary user may
// only give its own files to a group it is in, as on Linux. A file that is not the app's (Android's
// /dev, /sdcard) has an owner the kernel tells: there is nothing to record. (path NULL: dirfd is
// the file.)
static int fake_chown(int dirfd, const char *path, uid_t owner, gid_t group, int flags) {
    int is_home = path ? path_is_home_at(dirfd, path) : home_fd_is(dirfd);
    uid_t def_u = is_home ? 1000 : 0;
    gid_t def_g = is_home ? 1000 : 0;
    struct stat st;
    if (ow_presented_at_for(dirfd, path, flags, &st, def_u, def_g) != 0) {
        // Only a name that is not there is an error, as before (the call was always "done")
        if (errno == ENOENT || errno == ENOTDIR || errno == ELOOP || errno == EBADF) return -1;
        return 0;
    }
    const cortex_ids_t *ids = ids_now();
    uid_t new_uid = owner == (uid_t)-1 ? st.st_uid : owner;
    gid_t new_gid = group == (gid_t)-1 ? st.st_gid : group;
    if (ids->e != 0) {
        // Not root: only the owner, only to a group it is in, and never to another user
        int changes = owner != (uid_t)-1 || group != (gid_t)-1;
        if (changes && (st.st_uid != ids->e || new_uid != st.st_uid || (new_gid != st.st_gid && !ids_in_groups(ids, new_gid)))) {
            errno = EPERM;
            return -1;
        }
        if (ids_app_mode()) return 0;
    }
    ids_load_app();
    struct stat real;
    int ret = path ? ow_real_fstatat(dirfd, path, &real, flags) : ow_real_fstat(dirfd, &real);
    if (ret != 0 || real.st_uid != g_app_uid) return 0;
    if (new_uid == st.st_uid && new_gid == st.st_gid) return 0;
    ow_put_for((uint64_t)real.st_dev, (uint64_t)real.st_ino, new_uid, new_gid, def_u, def_g, 1);
    return 0;
}

// A file a process of another user just made is that user's (path NULL: the descriptor dirfd)
static void ow_record_new_at(int dirfd, const char *path, int flags) {
    if (!ids_records_new_files()) return;
    int is_home = path ? path_is_home_at(dirfd, path) : home_fd_is(dirfd);
    const cortex_ids_t *ids = ids_now();
    if (is_home && ids->e == 1000 && ids->ge == 1000) return;
    struct stat st;
    int ret = path ? ow_real_fstatat(dirfd, path, &st, flags) : ow_real_fstat(dirfd, &st);
    ids_load_app();
    if (ret != 0 || st.st_uid != g_app_uid) return;
    ow_put_for((uint64_t)st.st_dev, (uint64_t)st.st_ino, ids->e, ids->ge,
               is_home ? 1000 : 0, is_home ? 1000 : 0, 1);
}

// The record of a name about to be removed (unlink, rmdir, a rename over it): its key, or 0
static int ow_key_at(int dirfd, const char *path, uint64_t *dev, uint64_t *ino) {
    if (!path || !ow_maybe_any()) return 0;
    struct stat st;
    if (ow_real_fstatat(dirfd, path, &st, AT_SYMLINK_NOFOLLOW) != 0) return 0;
    uid_t u;
    gid_t g;
    if (!ow_get((uint64_t)st.st_dev, (uint64_t)st.st_ino, &u, &g)) return 0;
    *dev = (uint64_t)st.st_dev;
    *ino = (uint64_t)st.st_ino;
    return 1;
}

static void ow_forget(uint64_t dev, uint64_t ino) {
    ow_put(dev, ino, 0, 0, 0);
}

// Whether what an open of a name with O_CREAT opens does not exist yet (a dangling symbolic link
// is followed, as open() follows it)
static int ow_name_is_new(int dirfd, const char *path) {
    struct stat st;
    int saved_errno = errno;
    int is_new = path && ow_real_fstatat(dirfd, path, &st, 0) != 0 && errno == ENOENT;
    errno = saved_errno;
    return is_new;
}

// --- At exec ---------------------------------------------------------------------------------

// The ids a program started with path runs with: the starter's, with the set-user-ID and
// set-group-ID bits of the file (sudo, passwd, ping), and the saved ids set to the effective ones.
// CORTEX_BECOME in the new environment asks for root ("root", su and sudo of a no-fakeroot shell)
// or the app's own user ("app", cortex-api no-fakeroot). Returns 1 when they are root's (the
// variable is then left out).
static int ids_for_exec(const char *path, const char *become, cortex_ids_t *out) {
    *out = *ids_now();
    if (become && strcmp(become, "root") == 0) {
        memset(out, 0, sizeof(*out));
        return 1;
    }
    if (become && strcmp(become, "app") == 0) {
        ids_set_app(out);
        return 0;
    }
    // Root's set-user-ID programs change nothing for root; only a file given to another user does,
    // and there are none while no owner is recorded
    if (ids_are_root(out) && !ow_maybe_any()) return 1;
    if (path && !ids_app_mode()) {
        struct stat st;
        if (ow_presented_at(AT_FDCWD, path, 0, &st) == 0 && S_ISREG(st.st_mode)) {
            if (st.st_mode & S_ISUID) out->e = st.st_uid;
            if ((st.st_mode & S_ISGID) && (st.st_mode & S_IXGRP)) out->ge = st.st_gid;
        }
    }
    out->s = out->e;
    out->gs = out->ge;
    return ids_are_root(out);
}

// "CORTEX_IDS=..." for the environment of a program started with path (malloc'ed), or NULL
static char *ids_env_for_exec(const char *path, const char *become) {
    cortex_ids_t ids;
    if (ids_for_exec(path, become, &ids)) return NULL;
    char buf[64 + CORTEX_IDS_MAX_GROUPS * 11];
    memcpy(buf, "CORTEX_IDS=", 11);
    if (ids_format(&ids, buf + 11, sizeof(buf) - 11) < 0) return NULL;
    return strdup(buf);
}

// The ids of another process of the app, as it sees them (the other end of a Unix socket): from the
// CORTEX_IDS it was started with. A process that changed its ids since without starting a program
// is not seen (the servers that do are the ones that ask, not the clients).
static int ids_of_pid(pid_t pid, cortex_ids_t *out) {
    memset(out, 0, sizeof(*out));
    if (pid <= 0) return -1;
    if (pid == getpid()) {
        *out = *ids_now();
        return 0;
    }
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/environ", (int)pid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    char buf[16384];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (ssize_t i = 0; i < n;) {
        const char *e = buf + i;
        size_t len = strnlen(e, (size_t)(n - i));
        if (len > 11 && memcmp(e, "CORTEX_IDS=", 11) == 0) {
            cortex_ids_t ids;
            if (ids_parse(e + 11, &ids) == 0) *out = ids;
            return 0;
        }
        i += (ssize_t)len + 1;
    }
    return 0;
}

// An id call that reached the SIGSYS handler (Android's filter refuses them to apps): what the
// call returns (-1 with errno), or LONG_MIN for another call
// An id of an old 16-bit call (32-bit ARM's calls without "32"): 0xffff is -1
__attribute__((unused)) static long ids_arg16(long a) {
    return (a & 0xffff) == 0xffff ? -1L : (a & 0xffff);
}

__attribute__((unused)) static long sigsys_id_call(int nr, long a0, long a1, long a2) {
#if defined(__NR_setuid32) && defined(__NR_setuid)
    switch (nr) {
    case __NR_setuid:
    case __NR_setgid:
    case __NR_setreuid:
    case __NR_setregid:
    case __NR_setresuid:
    case __NR_setresgid:
    case __NR_setfsuid:
    case __NR_setfsgid:
        a0 = ids_arg16(a0);
        a1 = ids_arg16(a1);
        a2 = ids_arg16(a2);
        break;
    case __NR_setgroups: {
        // 16-bit ids in the list
        gid_t list[CORTEX_IDS_MAX_GROUPS];
        size_t n = (size_t)a0 > CORTEX_IDS_MAX_GROUPS ? CORTEX_IDS_MAX_GROUPS : (size_t)a0;
        for (size_t i = 0; i < n; i++) list[i] = ((const uint16_t *)a1)[i];
        return ids_setgroups(n, list);
    }
    default:
        break;
    }
#endif
    switch (nr) {
#if defined(__NR_setresuid)
    case __NR_setresuid:
#endif
#if defined(__NR_setresuid32)
    case __NR_setresuid32:
#endif
        return ids_setres(0, (unsigned long)(uid_t)a0, (unsigned long)(uid_t)a1, (unsigned long)(uid_t)a2);
#if defined(__NR_setresgid)
    case __NR_setresgid:
#endif
#if defined(__NR_setresgid32)
    case __NR_setresgid32:
#endif
        return ids_setres(1, (unsigned long)(gid_t)a0, (unsigned long)(gid_t)a1, (unsigned long)(gid_t)a2);
#if defined(__NR_setuid)
    case __NR_setuid:
#endif
#if defined(__NR_setuid32)
    case __NR_setuid32:
#endif
        return ids_setuid(0, (unsigned long)(uid_t)a0);
#if defined(__NR_setgid)
    case __NR_setgid:
#endif
#if defined(__NR_setgid32)
    case __NR_setgid32:
#endif
        return ids_setuid(1, (unsigned long)(gid_t)a0);
#if defined(__NR_setreuid)
    case __NR_setreuid:
#endif
#if defined(__NR_setreuid32)
    case __NR_setreuid32:
#endif
        return ids_setre(0, (unsigned long)(uid_t)a0, (unsigned long)(uid_t)a1);
#if defined(__NR_setregid)
    case __NR_setregid:
#endif
#if defined(__NR_setregid32)
    case __NR_setregid32:
#endif
        return ids_setre(1, (unsigned long)(gid_t)a0, (unsigned long)(gid_t)a1);
#if defined(__NR_setgroups)
    case __NR_setgroups:
#endif
#if defined(__NR_setgroups32)
    case __NR_setgroups32:
#endif
        return ids_setgroups((size_t)a0, (const gid_t *)a1);
#if defined(__NR_setfsuid)
    case __NR_setfsuid:
#endif
#if defined(__NR_setfsuid32)
    case __NR_setfsuid32:
#endif
        return (long)ids_now()->e;
#if defined(__NR_setfsgid)
    case __NR_setfsgid:
#endif
#if defined(__NR_setfsgid32)
    case __NR_setfsgid32:
#endif
        return (long)ids_now()->ge;
    default:
        return LONG_MIN;
    }
}
