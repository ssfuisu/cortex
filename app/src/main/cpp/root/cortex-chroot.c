// cortex-chroot: root mode. Runs Cortex's Ubuntu as real root in a chroot of the same rootfs that
// normal mode uses through the preloaded hook. The app starts it through su:
//
//   cortex-chroot enter ROOT [--term T] [--colorterm C] [--lang L] [--tz Z] [-- COMMAND [ARG...]]
//   cortex-chroot check ROOT
//   cortex-chroot restore ROOT UID GID
//
// enter sets the session up in mount and UTS namespaces of its own, so nothing it mounts is seen by
// Android, by the app or by other sessions, and it all goes away with the session's last process:
//   /               ROOT bound onto itself without Android's nosuid and nodev;
//   /proc, /sys     fresh proc and sysfs mounts;
//   /dev            a tmpfs with the host's device nodes recreated, /dev/pts bound in, the usual
//                   fd/stdin/stdout/stderr links and /dev/shm bound to ROOT/tmp/shm (where normal
//                   mode keeps POSIX shared memory too);
//   /sdcard         the shared storage;
//   /data           a tmpfs in which ROOT's own Android path is a link to /, so the absolute paths
//                   normal mode writes into files (/data/user/0/<package>/files/cortex/...) resolve.
// Then it changes root to ROOT and starts a login bash (or COMMAND) with a clean environment.
//
// check does the same setup and runs `bash -c 'exit 0'` in it, so the app can tell whether root mode
// works before switching to it.
//
// restore gives every file in ROOT back to the app (UID:GID and the SELinux label of ROOT itself)
// after root mode, since normal mode runs as the app and cannot change files owned by root. It does
// not cross into other file systems.
//
// Built as a static executable (no dynamic linker is involved before the chroot exists).
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#define CORTEX_CHROOT_VERSION "1"
#define HOSTNAME "cortex-android"
#define SELINUX_XATTR "security.selinux"
#define DEV_DEPTH_MAX 8

enum {
    EXIT_USAGE = 2,
    EXIT_NAMESPACE = 3,
    EXIT_MOUNT = 4,
    EXIT_CHROOT = 5,
    EXIT_EXEC = 6,
    EXIT_RESTORE = 7
};

static void warn_msg(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs("cortex-chroot: ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

// For calls whose failure changes nothing (the step is best effort).
static inline void best_effort(int result) {
    (void)result;
}

static int join_path(char *out, size_t size, const char *a, const char *b) {
    int n = snprintf(out, size, "%s/%s", a, b);
    if (n < 0 || (size_t)n >= size) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static int ensure_dir(const char *path, mode_t mode) {
    struct stat st;
    if (lstat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) return 0;
        errno = ENOTDIR;
        return -1;
    }
    if (mkdir(path, mode) != 0 && errno != EEXIST) return -1;
    return 0;
}

// mkdir -p for a path below an existing directory.
static int ensure_dirs(const char *path, mode_t mode) {
    char buf[PATH_MAX];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(buf)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    memcpy(buf, path, len + 1);
    for (char *p = buf + 1; *p; p++) {
        if (*p != '/') continue;
        *p = '\0';
        if (ensure_dir(buf, mode) != 0) return -1;
        *p = '/';
    }
    return ensure_dir(buf, mode);
}

// The rootfs must look like Cortex's: an absolute, canonical path ending in /files/cortex with an
// Ubuntu userland in it. restore changes the owner of everything below it, so a wrong argument must
// never get through.
static int resolve_root(const char *arg, char *out) {
    if (!arg || arg[0] != '/') {
        warn_msg("the rootfs must be an absolute path");
        return -1;
    }
    if (!realpath(arg, out)) {
        warn_msg("%s: %s", arg, strerror(errno));
        return -1;
    }
    size_t len = strlen(out);
    const char *suffix = "/files/cortex";
    size_t slen = strlen(suffix);
    if (len <= slen || strcmp(out + len - slen, suffix) != 0) {
        warn_msg("%s is not a Cortex rootfs (expected .../files/cortex)", out);
        return -1;
    }
    char probe[PATH_MAX];
    struct stat st;
    if (join_path(probe, sizeof(probe), out, "usr/bin") != 0 || stat(probe, &st) != 0 || !S_ISDIR(st.st_mode) ||
        join_path(probe, sizeof(probe), out, "etc") != 0 || stat(probe, &st) != 0 || !S_ISDIR(st.st_mode)) {
        warn_msg("%s has no Ubuntu userland (usr/bin, etc)", out);
        return -1;
    }
    return 0;
}

static int bind_mount(const char *src, const char *dst, int recursive) {
    return mount(src, dst, NULL, MS_BIND | (recursive ? MS_REC : 0), NULL);
}

// ---------------------------------------------------------------------------------------------
// /dev

// Recreates the host's device nodes, links and directories below src in dst. Other file systems
// mounted below /dev (devpts, cgroups, binderfs, ...) are skipped; /dev/pts is bound separately.
static void copy_dev_tree(const char *src, const char *dst, dev_t dev_fs, int depth) {
    if (depth > DEV_DEPTH_MAX) return;
    DIR *d = opendir(src);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        if (depth == 0 && strcmp(e->d_name, "shm") == 0) continue;
        char s[PATH_MAX], t[PATH_MAX];
        if (join_path(s, sizeof(s), src, e->d_name) != 0 || join_path(t, sizeof(t), dst, e->d_name) != 0) continue;
        struct stat st;
        if (lstat(s, &st) != 0) continue;
        if (S_ISLNK(st.st_mode)) {
            char target[PATH_MAX];
            ssize_t n = readlink(s, target, sizeof(target) - 1);
            if (n <= 0) continue;
            target[n] = '\0';
            if (symlink(target, t) == 0) {
                best_effort(lchown(t, st.st_uid, st.st_gid));
            }
        } else if (S_ISDIR(st.st_mode)) {
            if (st.st_dev != dev_fs) continue;
            if (mkdir(t, st.st_mode & 07777) != 0 && errno != EEXIST) continue;
            copy_dev_tree(s, t, dev_fs, depth + 1);
            best_effort(chown(t, st.st_uid, st.st_gid));
            chmod(t, st.st_mode & 07777);
        } else if (S_ISCHR(st.st_mode) || S_ISBLK(st.st_mode)) {
            if (mknod(t, st.st_mode, st.st_rdev) == 0) {
                best_effort(chown(t, st.st_uid, st.st_gid));
                chmod(t, st.st_mode & 07777);
            }
        }
        // Sockets, FIFOs and regular files below /dev belong to Android's own services.
    }
    closedir(d);
}

static int setup_dev(const char *root) {
    char dev[PATH_MAX];
    if (join_path(dev, sizeof(dev), root, "dev") != 0 || ensure_dir(dev, 0755) != 0) {
        warn_msg("%s/dev: %s", root, strerror(errno));
        return -1;
    }
    struct stat host_dev;
    if (stat("/dev", &host_dev) != 0) {
        warn_msg("/dev: %s", strerror(errno));
        return -1;
    }
    if (mount("tmpfs", dev, "tmpfs", MS_NOSUID, "mode=0755,size=4m") != 0) {
        // Without a tmpfs the host's /dev itself has to do (nothing is created in it then).
        warn_msg("tmpfs on /dev: %s; using the host's /dev", strerror(errno));
        if (bind_mount("/dev", dev, 1) != 0) {
            warn_msg("binding /dev: %s", strerror(errno));
            return -1;
        }
        return 0;
    }
    copy_dev_tree("/dev", dev, host_dev.st_dev, 0);

    char path[PATH_MAX];
    if (join_path(path, sizeof(path), dev, "pts") == 0 && ensure_dir(path, 0755) == 0) {
        if (bind_mount("/dev/pts", path, 1) != 0) {
            warn_msg("binding /dev/pts: %s", strerror(errno));
        }
    }
    static const char *const links[][2] = {
        {"fd", "/proc/self/fd"},
        {"stdin", "/proc/self/fd/0"},
        {"stdout", "/proc/self/fd/1"},
        {"stderr", "/proc/self/fd/2"},
    };
    for (size_t i = 0; i < sizeof(links) / sizeof(links[0]); i++) {
        if (join_path(path, sizeof(path), dev, links[i][0]) == 0) {
            best_effort(symlink(links[i][1], path)); // kept if the host already has one
        }
    }

    // /dev/shm is ROOT/tmp/shm, as in normal mode, so both modes share it.
    char shm_src[PATH_MAX];
    if (join_path(shm_src, sizeof(shm_src), root, "tmp/shm") == 0 && ensure_dirs(shm_src, 01777) == 0 &&
        join_path(path, sizeof(path), dev, "shm") == 0 && ensure_dir(path, 01777) == 0) {
        chmod(shm_src, 01777);
        chmod(path, 01777);
        if (bind_mount(shm_src, path, 0) != 0) {
            warn_msg("binding /dev/shm: %s", strerror(errno));
        }
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------
// The session

static int mount_kernel_fs(const char *root, const char *name, const char *type) {
    char path[PATH_MAX], host[PATH_MAX];
    if (join_path(path, sizeof(path), root, name) != 0 || ensure_dir(path, 0555) != 0) {
        warn_msg("%s/%s: %s", root, name, strerror(errno));
        return -1;
    }
    if (mount(type, path, type, MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) == 0) return 0;
    int err = errno;
    snprintf(host, sizeof(host), "/%s", name);
    if (bind_mount(host, path, 1) == 0) return 0;
    warn_msg("mounting %s on /%s: %s", type, name, strerror(err));
    return -1;
}

static void setup_sdcard(const char *root) {
    char storage[PATH_MAX], path[PATH_MAX];
    struct stat st;
    if (!realpath("/sdcard", storage) || stat(storage, &st) != 0 || !S_ISDIR(st.st_mode)) return;
    if (join_path(path, sizeof(path), root, "sdcard") != 0 || ensure_dir(path, 0755) != 0) return;
    if (bind_mount(storage, path, 1) != 0) {
        warn_msg("binding /sdcard: %s", strerror(errno));
    }
}

// ROOT is /data/user/0/<package>/files/cortex (or /data/data/...): in the session, /data is a tmpfs
// in which that path is a link to /, with /data/data and /data/user/0 leading to it alike.
static void setup_data_alias(const char *root) {
    const char *prefix = "/data/";
    size_t plen = strlen(prefix);
    if (strncmp(root, prefix, plen) != 0) return;
    const char *rel = root + plen;

    char data[PATH_MAX], link_path[PATH_MAX], parent[PATH_MAX];
    if (join_path(data, sizeof(data), root, "data") != 0 || ensure_dir(data, 0755) != 0) return;
    if (mount("tmpfs", data, "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755,size=1m") != 0) {
        warn_msg("tmpfs on /data: %s", strerror(errno));
        return;
    }
    if (join_path(link_path, sizeof(link_path), data, rel) != 0) return;
    memcpy(parent, link_path, strlen(link_path) + 1);
    char *slash = strrchr(parent, '/');
    if (!slash) return;
    *slash = '\0';
    if (ensure_dirs(parent, 0755) != 0 || symlink("/", link_path) != 0) {
        warn_msg("/data alias: %s", strerror(errno));
        return;
    }
    // The app writes paths as /data/user/0/...; on many phones that is a link to /data/data, which
    // is then ROOT's canonical path. Both spellings must resolve.
    if (strncmp(rel, "user/0/", 7) == 0) {
        if (join_path(link_path, sizeof(link_path), data, "data") == 0) {
            best_effort(symlink("user/0", link_path));
        }
    } else if (strncmp(rel, "data/", 5) == 0) {
        char user[PATH_MAX];
        if (join_path(user, sizeof(user), data, "user") == 0 && ensure_dir(user, 0755) == 0 &&
            join_path(link_path, sizeof(link_path), user, "0") == 0) {
            best_effort(symlink("../data", link_path));
        }
    }
}

// Non-root users in the chroot (apt's sandbox, services) must be able to reach / and use /tmp.
// The app sets them to 0700 for normal mode; it is the same directory, so this is repeated here.
static void open_up_modes(const char *root) {
    struct stat st;
    if (stat(root, &st) == 0 && (st.st_mode & 0755) != 0755) {
        chmod(root, (st.st_mode | 0755) & 07777);
    }
    char tmp[PATH_MAX];
    if (join_path(tmp, sizeof(tmp), root, "tmp") == 0 && ensure_dir(tmp, 01777) == 0) {
        chmod(tmp, 01777);
    }
}

static int setup_session(const char *root) {
    int uts = 1;
    if (unshare(CLONE_NEWNS | CLONE_NEWUTS) != 0) {
        uts = 0;
        if (unshare(CLONE_NEWNS) != 0) {
            warn_msg("cannot create a mount namespace: %s", strerror(errno));
            return EXIT_NAMESPACE;
        }
    }
    // Nothing mounted from here on may reach Android's or the app's mount table.
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        warn_msg("cannot make the mount namespace private: %s", strerror(errno));
        return EXIT_NAMESPACE;
    }
    if (uts && sethostname(HOSTNAME, strlen(HOSTNAME)) != 0) {
        warn_msg("hostname: %s", strerror(errno));
    }
    // Android mounts /data nosuid and nodev. A bind of ROOT remounted without them gives the chroot
    // a / of its own, so set-user-ID programs and file capabilities work as on Ubuntu (sudo, passwd
    // and ping for other users) and / is in the mount table (df, findmnt).
    if (bind_mount(root, root, 1) != 0 || mount(NULL, root, NULL, MS_REMOUNT | MS_BIND, NULL) != 0) {
        warn_msg("remounting the rootfs with set-user-ID support: %s", strerror(errno));
    }

    if (mount_kernel_fs(root, "proc", "proc") != 0) return EXIT_MOUNT;
    mount_kernel_fs(root, "sys", "sysfs");
    if (setup_dev(root) != 0) return EXIT_MOUNT;
    setup_sdcard(root);
    setup_data_alias(root);
    open_up_modes(root);
    return 0;
}

static void reset_signals(void) {
    sigset_t all;
    sigemptyset(&all);
    sigprocmask(SIG_SETMASK, &all, NULL);
    for (int sig = 1; sig < NSIG; sig++) {
        if (sig == SIGKILL || sig == SIGSTOP) continue;
        signal(sig, SIG_DFL);
    }
}

struct session_opts {
    const char *term;
    const char *colorterm;
    const char *lang;
    const char *tz;
};

static const char *pick_shell(void) {
    static const char *const shells[] = {"/bin/bash", "/usr/bin/bash", "/bin/sh"};
    for (size_t i = 0; i < sizeof(shells) / sizeof(shells[0]); i++) {
        if (access(shells[i], X_OK) == 0) return shells[i];
    }
    return "/bin/sh";
}

static void set_environment(const struct session_opts *o, const char *home) {
    clearenv();
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("HOME", home, 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/bin/bash", 1);
    setenv("TERM", o->term && *o->term ? o->term : "xterm-256color", 1);
    setenv("COLORTERM", o->colorterm && *o->colorterm ? o->colorterm : "truecolor", 1);
    setenv("LANG", o->lang && *o->lang ? o->lang : "C.UTF-8", 1);
    if (o->tz && *o->tz) setenv("TZ", o->tz, 1);
    // Tells Cortex's own scripts (/etc/profile.d, su, sudo) that this is root mode.
    setenv("CORTEX_CHROOT", "1", 1);
}

static int run_session(const char *root, const struct session_opts *o, char **command, int check) {
    int rc = setup_session(root);
    if (rc != 0) return rc;
    if (chroot(root) != 0 || chdir("/") != 0) {
        warn_msg("chroot %s: %s", root, strerror(errno));
        return EXIT_CHROOT;
    }
    // Normal mode's home directory is the rootfs's /home: root mode uses the same one.
    const char *home = "/home";
    struct stat st;
    if (stat(home, &st) != 0 || !S_ISDIR(st.st_mode)) home = "/root";
    if (chdir(home) != 0) {
        home = "/";
        best_effort(chdir("/"));
    }
    set_environment(o, home);
    umask(022);
    reset_signals();

    const char *shell = pick_shell();
    if (check) {
        execl(shell, "bash", "-c", "exit 0", (char *)NULL);
        warn_msg("%s: %s", shell, strerror(errno));
        return EXIT_EXEC;
    }
    if (command && command[0]) {
        execvp(command[0], command);
        warn_msg("%s: %s", command[0], strerror(errno));
        return EXIT_EXEC;
    }
    execl(shell, "-bash", (char *)NULL); // a login shell, as on a console
    warn_msg("%s: %s", shell, strerror(errno));
    return EXIT_EXEC;
}

// ---------------------------------------------------------------------------------------------
// restore

static uid_t g_uid;
static gid_t g_gid;
static char g_label[256];
static ssize_t g_label_len = -1;
static unsigned long g_owners, g_labels, g_errors;

static int restore_entry(const char *path, const struct stat *sb, int type, struct FTW *ftw) {
    (void)ftw;
    if (type == FTW_NS) {
        g_errors++;
        return 0;
    }
    if (sb->st_uid != g_uid || sb->st_gid != g_gid) {
        if (lchown(path, g_uid, g_gid) == 0) {
            g_owners++;
            // chown clears set-user-ID and set-group-ID bits; give them back.
            if (!S_ISLNK(sb->st_mode) && (sb->st_mode & (S_ISUID | S_ISGID))) {
                chmod(path, sb->st_mode & 07777);
            }
        } else {
            g_errors++;
        }
    }
    if (g_label_len > 0) {
        char current[256];
        ssize_t n = lgetxattr(path, SELINUX_XATTR, current, sizeof(current));
        if (n != g_label_len || memcmp(current, g_label, (size_t)n) != 0) {
            if (lsetxattr(path, SELINUX_XATTR, g_label, (size_t)g_label_len, 0) == 0) {
                g_labels++;
            } else if (errno != ENOTSUP && errno != EOPNOTSUPP) {
                g_errors++;
            }
        }
    }
    return 0;
}

static int run_restore(const char *root, const char *uid_arg, const char *gid_arg) {
    char *end;
    errno = 0;
    unsigned long uid = strtoul(uid_arg, &end, 10);
    if (errno || *end || uid < 10000 || uid > 0x7fffffffUL) {
        warn_msg("invalid app uid: %s", uid_arg);
        return EXIT_USAGE;
    }
    unsigned long gid = strtoul(gid_arg, &end, 10);
    if (errno || *end || gid < 10000 || gid > 0x7fffffffUL) {
        warn_msg("invalid app gid: %s", gid_arg);
        return EXIT_USAGE;
    }
    g_uid = (uid_t)uid;
    g_gid = (gid_t)gid;
    // The rootfs directory is created by the app, so it has the app's label.
    g_label_len = lgetxattr(root, SELINUX_XATTR, g_label, sizeof(g_label));

    if (nftw(root, restore_entry, 64, FTW_PHYS | FTW_MOUNT) != 0) {
        warn_msg("walking %s: %s", root, strerror(errno));
        return EXIT_RESTORE;
    }
    printf("owners %lu labels %lu errors %lu\n", g_owners, g_labels, g_errors);
    return g_errors ? EXIT_RESTORE : 0;
}

// ---------------------------------------------------------------------------------------------

static void usage(void) {
    fputs("usage: cortex-chroot enter ROOT [--term T] [--colorterm C] [--lang L] [--tz Z] [-- COMMAND...]\n"
          "       cortex-chroot check ROOT\n"
          "       cortex-chroot restore ROOT UID GID\n", stderr);
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--version") == 0) {
        puts("cortex-chroot " CORTEX_CHROOT_VERSION);
        return 0;
    }
    if (argc < 3) {
        usage();
        return EXIT_USAGE;
    }
    if (geteuid() != 0) {
        warn_msg("must run as root");
        return EXIT_USAGE;
    }
    char root[PATH_MAX];
    if (resolve_root(argv[2], root) != 0) return EXIT_USAGE;

    if (strcmp(argv[1], "restore") == 0) {
        if (argc != 5) {
            usage();
            return EXIT_USAGE;
        }
        return run_restore(root, argv[3], argv[4]);
    }
    int check = strcmp(argv[1], "check") == 0;
    if (!check && strcmp(argv[1], "enter") != 0) {
        usage();
        return EXIT_USAGE;
    }

    struct session_opts o = {0};
    char **command = NULL;
    for (int i = 3; i < argc; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--") == 0) {
            command = argv + i + 1;
            break;
        }
        if (i + 1 >= argc) {
            usage();
            return EXIT_USAGE;
        }
        if (strcmp(a, "--term") == 0) o.term = argv[++i];
        else if (strcmp(a, "--colorterm") == 0) o.colorterm = argv[++i];
        else if (strcmp(a, "--lang") == 0) o.lang = argv[++i];
        else if (strcmp(a, "--tz") == 0) o.tz = argv[++i];
        else {
            usage();
            return EXIT_USAGE;
        }
    }
    return run_session(root, &o, command, check);
}
