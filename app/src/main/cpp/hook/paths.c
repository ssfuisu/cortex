// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Path translation: Ubuntu paths (/usr, /etc, ...) mapped into the rootfs, Android's own mounts
// left alone, and the library directories of the rootfs.

// ---- Path translation -------------------------------------------------------------------
// Pure string functions (no syscalls), so tests/native can check them directly. They run on
// every file-system call, so they avoid snprintf and compute the rootfs prefix length once.

static char g_cortex_root[PATH_MAX] = {0};
static size_t g_cortex_root_len = 0;

static void cortex_set_root(const char *root, size_t len) {
    if (!root) len = 0;
    if (len > sizeof(g_cortex_root) - 1) len = sizeof(g_cortex_root) - 1;
    if (len > 0) memcpy(g_cortex_root, root, len);
    g_cortex_root[len] = '\0';
    g_cortex_root_len = len;
}

// Appends s[0..len) to out[0..used) and NUL-terminates, truncating like snprintf.
// Returns the new length.
static size_t cortex_append(char *out, size_t outsz, size_t used, const char *s, size_t len) {
    if (outsz == 0) return 0;
    size_t room = used < outsz - 1 ? outsz - 1 - used : 0;
    if (len > room) len = room;
    if (len > 0) memcpy(out + used, s, len);
    out[used + len] = '\0';
    return used + len;
}

static inline int is_path_prefix(const char *path, const char *prefix, size_t prefix_len) {
    if (strncmp(path, prefix, prefix_len) != 0) return 0;
    return path[prefix_len] == '/' || path[prefix_len] == '\0';
}

static inline const char *get_rootfs_subpath(const char *target) {
    if (!target || g_cortex_root_len == 0) return NULL;
    if (strncmp(target, g_cortex_root, g_cortex_root_len) == 0 &&
        (target[g_cortex_root_len] == '/' || target[g_cortex_root_len] == '\0')) {
        return target + g_cortex_root_len;
    }
    return NULL;
}

// Android's own mounts and files, which must never be redirected into the rootfs.
// `path` starts with '/'; a prefix can only match when its second character does.
static int is_android_host_path(const char *path) {
    switch (path[1]) {
    case 'a': return is_path_prefix(path, "/apex", 5);
    case 'c': return is_path_prefix(path, "/config", 7);
    case 'd': return is_path_prefix(path, "/dev", 4) || is_path_prefix(path, "/d", 2) ||
                     is_path_prefix(path, "/data", 5);
    case 'l': return is_path_prefix(path, "/linkerconfig", 13);
    case 'o': return is_path_prefix(path, "/odm", 4) || is_path_prefix(path, "/oem", 4);
    case 'p': return is_path_prefix(path, "/proc", 5) || is_path_prefix(path, "/product", 8);
    case 's': return strcmp(path, "/sbin/su") == 0 || strcmp(path, "/su") == 0 ||
                     is_path_prefix(path, "/sys", 4) || is_path_prefix(path, "/system", 7) ||
                     is_path_prefix(path, "/system_ext", 11) || is_path_prefix(path, "/sdcard", 7) ||
                     is_path_prefix(path, "/storage", 8);
    case 'v': return is_path_prefix(path, "/vendor", 7);
    default:  return 0;
    }
}

// Maps an absolute Ubuntu path into the rootfs. Returns `path` (possibly advanced past
// redundant leading "/" and "/." components) when it needs no change, else `buffer`.
static const char *rewrite_path_in_root(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    if (path[0] != '/' || g_cortex_root_len == 0) return path;

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
    if (path[1] == '\0') {
        cortex_append(buffer, bufsize, 0, g_cortex_root, g_cortex_root_len);
        return buffer;
    }

    // Already inside Cortex rootfs
    if (get_rootfs_subpath(path) != NULL) {
        return path;
    }

    // POSIX shared memory (/dev/shm) redirection to Cortex rootfs tmp/shm
    if (strncmp(path, "/dev/shm", 8) == 0 && (path[8] == '/' || path[8] == '\0')) {
        size_t n = cortex_append(buffer, bufsize, 0, g_cortex_root, g_cortex_root_len);
        n = cortex_append(buffer, bufsize, n, "/tmp/shm", 8);
        cortex_append(buffer, bufsize, n, path + 8, strlen(path + 8));
        return buffer;
    }

    // Real host Android kernel & system mounts
    if (is_android_host_path(path)) {
        return path;
    }

    // Any other absolute path belongs to Cortex rootfs (/boot, /media, /snap, /etc, /usr, ...)
    size_t n = cortex_append(buffer, bufsize, 0, g_cortex_root, g_cortex_root_len);
    cortex_append(buffer, bufsize, n, path, strlen(path));
    return buffer;
}

// On ENOENT, stat()/lstat()/readlink() retry /usr/bin <-> /bin and /usr/sbin <-> /sbin, for
// rootfs trees where bin and sbin are real directories. Returns 1 for the bin pair, 2 for the
// sbin pair (with the other spelling in `alt`), 0 when `sub` is in neither.
#define CORTEX_USRMERGE_BIN 1
#define CORTEX_USRMERGE_SBIN 2
static int usrmerge_alt_path(const char *sub, char *alt, size_t altsz) {
    const char *dir;
    const char *rest;
    int pair;
    if (strncmp(sub, "/usr/bin/", 9) == 0) {
        dir = "/bin/"; rest = sub + 9; pair = CORTEX_USRMERGE_BIN;
    } else if (strncmp(sub, "/bin/", 5) == 0) {
        dir = "/usr/bin/"; rest = sub + 5; pair = CORTEX_USRMERGE_BIN;
    } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
        dir = "/sbin/"; rest = sub + 10; pair = CORTEX_USRMERGE_SBIN;
    } else if (strncmp(sub, "/sbin/", 6) == 0) {
        dir = "/usr/sbin/"; rest = sub + 6; pair = CORTEX_USRMERGE_SBIN;
    } else {
        return 0;
    }
    size_t n = cortex_append(alt, altsz, 0, g_cortex_root, g_cortex_root_len);
    n = cortex_append(alt, altsz, n, dir, strlen(dir));
    cortex_append(alt, altsz, n, rest, strlen(rest));
    return pair;
}

// Whether postprocess_readlink() below can change a link's target of length target_len, read
// through linkpath: only a target that starts with the rootfs path (at least that long) or a relative
// one into /etc/alternatives (at least "../alternatives", 15 characters, from a directory next to it;
// any "../" under /etc/alternatives itself). lstat() of a link reads it only then, to report its
// rewritten length.
static int readlink_result_may_change(const char *linkpath, long long target_len) {
    if (g_cortex_root_len > 0 && target_len >= (long long)g_cortex_root_len) return 1;
    if (!linkpath || linkpath[0] != '/') return 0;
    if (strncmp(linkpath, "/etc/alternatives", 17) == 0) return target_len >= 2;
    return target_len >= 15;
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

// /proc/self/fd/N, /proc/thread-self/fd/N and /proc/PID/fd/N: the real path of an open file. A program
// that finds a path this way (Bun and Zig programs do: their realpath() is an open and a readlink of
// this) goes on with system calls of its own, which the hook does not see, so a path inside the rootfs
// has to keep its real beginning there: "/home" for $CORTEX_ROOT/home is a path that does not exist
// for them ("ENOENT, realpath '/home'" in Kilo). It is what getcwd() answers, too.
static int is_proc_fd_link(const char *path) {
    if (!path || strncmp(path, "/proc/", 6) != 0) return 0;
    const char *p = path + 6;
    if (strncmp(p, "self/", 5) == 0) {
        p += 5;
    } else if (strncmp(p, "thread-self/", 12) == 0) {
        p += 12;
    } else {
        if (*p < '0' || *p > '9') return 0;
        while (*p >= '0' && *p <= '9') p++;
        if (*p != '/') return 0;
        p++;
    }
    return strncmp(p, "fd/", 3) == 0;
}

static ssize_t postprocess_readlink(const char *linkpath, char *buf, ssize_t ret, size_t bufsiz) {
    if (ret <= 0) return ret;

    // 1. Strip g_cortex_root if target starts with it
    if (g_cortex_root_len > 0 && !is_proc_fd_link(linkpath)) {
        size_t rlen = g_cortex_root_len;
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

// Android's SELinux label, which the xattr hooks keep away from Ubuntu programs.
static const char k_selinux_xattr[] = "security.selinux";

static int is_selinux_xattr(const char *name) {
    return name != NULL && strcmp(name, k_selinux_xattr) == 0;
}

// Removes "security.selinux" from a listxattr() result (NUL-separated names); returns the new length.
static ssize_t drop_selinux_xattr(char *list, ssize_t len) {
    if (!list || len <= 0) return len;
    ssize_t out = 0;
    ssize_t i = 0;
    while (i < len) {
        size_t name_len = strnlen(list + i, (size_t)(len - i));
        ssize_t entry = (ssize_t)name_len + 1;
        if (entry > len - i) entry = len - i;
        if (name_len != sizeof(k_selinux_xattr) - 1 || memcmp(list + i, k_selinux_xattr, name_len) != 0) {
            memmove(list + out, list + i, (size_t)entry);
            out += entry;
        }
        i += entry;
    }
    return out;
}

// Library directories of the rootfs, in the order ld.so searches them. ld.so tries every
// directory for every library, so the multiarch ones that hold nearly all libraries come first
// (as in glibc's own default order). Keep in sync with Environment.kt, 00-env.sh and su.sh.
static const char *const k_rootfs_lib_dirs[] = {
    "/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
    "/lib/arm-linux-gnueabihf", "/usr/lib/arm-linux-gnueabihf",
    "/lib", "/usr/lib", "/usr/local/lib",
    "/usr/lib/systemd", "/lib/systemd",
    "/usr/lib/aarch64-linux-gnu/systemd", "/lib/aarch64-linux-gnu/systemd",
    "/usr/lib/arm-linux-gnueabihf/systemd", "/lib/arm-linux-gnueabihf/systemd",
};

// Whether the ':'-separated list holds the directory dir[0..dir_len) as one of its entries.
static int lib_dir_list_has(const char *list, const char *dir, size_t dir_len) {
    if (!list) return 0;
    for (const char *p = list; *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len == dir_len && memcmp(p, dir, len) == 0) return 1;
        if (!end) break;
        p = end + 1;
    }
    return 0;
}

// Appends the rootfs library directories as a ':'-separated list (with a leading ':' when
// `separate` is set), leaving out those that `have` (a list too, or NULL) names already: the
// caller's LD_LIBRARY_PATH is usually this very list, and ld.so spends time on every directory of
// the list it is given, in every program that is started, and tries it again for each library that
// is not found. Returns the new length.
static size_t append_rootfs_lib_dirs_missing_from(char *out, size_t outsz, size_t used, int separate, const char *have) {
    for (size_t i = 0; i < sizeof(k_rootfs_lib_dirs) / sizeof(k_rootfs_lib_dirs[0]); i++) {
        size_t dir_len = strlen(k_rootfs_lib_dirs[i]);
        if (have && have[0] != '\0') {
            char full[PATH_MAX];
            size_t n = cortex_append(full, sizeof(full), 0, g_cortex_root, g_cortex_root_len);
            n = cortex_append(full, sizeof(full), n, k_rootfs_lib_dirs[i], dir_len);
            if (lib_dir_list_has(have, full, n)) continue;
        }
        if (separate) used = cortex_append(out, outsz, used, ":", 1);
        separate = 1;
        used = cortex_append(out, outsz, used, g_cortex_root, g_cortex_root_len);
        used = cortex_append(out, outsz, used, k_rootfs_lib_dirs[i], dir_len);
    }
    return used;
}

// All of them.
static size_t append_rootfs_lib_dirs(char *out, size_t outsz, size_t used, int separate) {
    return append_rootfs_lib_dirs_missing_from(out, outsz, used, separate, NULL);
}
