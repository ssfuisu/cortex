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
#include <limits.h>

static char g_cortex_root[PATH_MAX] = {0};
static int g_initialized = 0;

static void init_cortex_hook(void) {
    if (g_initialized) return;
    const char *root = getenv("CORTEX_ROOT");
    if (root && strlen(root) > 0) {
        strncpy(g_cortex_root, root, sizeof(g_cortex_root) - 1);
        // Remove trailing slash if present
        size_t len = strlen(g_cortex_root);
        if (len > 0 && g_cortex_root[len - 1] == '/') {
            g_cortex_root[len - 1] = '\0';
        }
    }
    g_initialized = 1;
}

static const char *rewrite_path(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    init_cortex_hook();

    if (g_cortex_root[0] == '\0') {
        return path;
    }

    // Do not rewrite if already within CORTEX_ROOT or /proc or /dev or /sys or /data
    if (strncmp(path, g_cortex_root, strlen(g_cortex_root)) == 0 ||
        strncmp(path, "/proc", 5) == 0 ||
        strncmp(path, "/dev", 4) == 0 ||
        strncmp(path, "/sys", 4) == 0 ||
        strncmp(path, "/system", 7) == 0 ||
        strncmp(path, "/data", 5) == 0) {
        return path;
    }

    // Intercept standard Linux filesystem hierarchies
    if (strncmp(path, "/usr", 4) == 0 ||
        strncmp(path, "/bin", 4) == 0 ||
        strncmp(path, "/sbin", 5) == 0 ||
        strncmp(path, "/lib", 4) == 0 ||
        strncmp(path, "/etc", 4) == 0 ||
        strncmp(path, "/var", 4) == 0 ||
        strncmp(path, "/opt", 4) == 0 ||
        strncmp(path, "/tmp", 4) == 0) {
        
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
typedef int (*orig_open_f_type)(const char *pathname, int flags, ...);
static orig_open_f_type orig_open = NULL;

int open(const char *pathname, int flags, ...) {
    if (!orig_open) {
        orig_open = (orig_open_f_type)dlsym(RTLD_NEXT, "open");
    }

    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
        return orig_open(target, flags, mode);
    }
    return orig_open(target, flags);
}

// Hook openat
typedef int (*orig_openat_f_type)(int dirfd, const char *pathname, int flags, ...);
int openat(int dirfd, const char *pathname, int flags, ...) {
    static orig_openat_f_type orig_openat = NULL;
    if (!orig_openat) {
        orig_openat = (orig_openat_f_type)dlsym(RTLD_NEXT, "openat");
    }

    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
        return orig_openat(dirfd, target, flags, mode);
    }
    return orig_openat(dirfd, target, flags);
}

// Hook stat
typedef int (*orig_stat_f_type)(const char *pathname, struct stat *statbuf);
int stat(const char *pathname, struct stat *statbuf) {
    static orig_stat_f_type orig_stat = NULL;
    if (!orig_stat) {
        orig_stat = (orig_stat_f_type)dlsym(RTLD_NEXT, "stat");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_stat(target, statbuf);
}

// Hook lstat
typedef int (*orig_lstat_f_type)(const char *pathname, struct stat *statbuf);
int lstat(const char *pathname, struct stat *statbuf) {
    static orig_lstat_f_type orig_lstat = NULL;
    if (!orig_lstat) {
        orig_lstat = (orig_lstat_f_type)dlsym(RTLD_NEXT, "lstat");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_lstat(target, statbuf);
}

// Hook access
typedef int (*orig_access_f_type)(const char *pathname, int mode);
int access(const char *pathname, int mode) {
    static orig_access_f_type orig_access = NULL;
    if (!orig_access) {
        orig_access = (orig_access_f_type)dlsym(RTLD_NEXT, "access");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_access(target, mode);
}

// Hook unlink
typedef int (*orig_unlink_f_type)(const char *pathname);
int unlink(const char *pathname) {
    static orig_unlink_f_type orig_unlink = NULL;
    if (!orig_unlink) {
        orig_unlink = (orig_unlink_f_type)dlsym(RTLD_NEXT, "unlink");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_unlink(target);
}

// Hook unlinkat
typedef int (*orig_unlinkat_f_type)(int dirfd, const char *pathname, int flags);
int unlinkat(int dirfd, const char *pathname, int flags) {
    static orig_unlinkat_f_type orig_unlinkat = NULL;
    if (!orig_unlinkat) {
        orig_unlinkat = (orig_unlinkat_f_type)dlsym(RTLD_NEXT, "unlinkat");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_unlinkat(dirfd, target, flags);
}

// Hook rmdir
typedef int (*orig_rmdir_f_type)(const char *pathname);
int rmdir(const char *pathname) {
    static orig_rmdir_f_type orig_rmdir = NULL;
    if (!orig_rmdir) {
        orig_rmdir = (orig_rmdir_f_type)dlsym(RTLD_NEXT, "rmdir");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_rmdir(target);
}

// Hook mkdir
typedef int (*orig_mkdir_f_type)(const char *pathname, mode_t mode);
int mkdir(const char *pathname, mode_t mode) {
    static orig_mkdir_f_type orig_mkdir = NULL;
    if (!orig_mkdir) {
        orig_mkdir = (orig_mkdir_f_type)dlsym(RTLD_NEXT, "mkdir");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_mkdir(target, mode);
}

// Hook mkdirat
typedef int (*orig_mkdirat_f_type)(int dirfd, const char *pathname, mode_t mode);
int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    static orig_mkdirat_f_type orig_mkdirat = NULL;
    if (!orig_mkdirat) {
        orig_mkdirat = (orig_mkdirat_f_type)dlsym(RTLD_NEXT, "mkdirat");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_mkdirat(dirfd, target, mode);
}

// Hook rename
typedef int (*orig_rename_f_type)(const char *oldpath, const char *newpath);
int rename(const char *oldpath, const char *newpath) {
    static orig_rename_f_type orig_rename = NULL;
    if (!orig_rename) {
        orig_rename = (orig_rename_f_type)dlsym(RTLD_NEXT, "rename");
    }
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = rewrite_path(oldpath, buf1, sizeof(buf1));
    const char *target_new = rewrite_path(newpath, buf2, sizeof(buf2));
    return orig_rename(target_old, target_new);
}

// Hook readlink
typedef ssize_t (*orig_readlink_f_type)(const char *pathname, char *buf, size_t bufsiz);
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    static orig_readlink_f_type orig_readlink = NULL;
    if (!orig_readlink) {
        orig_readlink = (orig_readlink_f_type)dlsym(RTLD_NEXT, "readlink");
    }
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(pathname, pbuf, sizeof(pbuf));
    return orig_readlink(target, buf, bufsiz);
}

// Hook symlink
typedef int (*orig_symlink_f_type)(const char *target, const char *linkpath);
int symlink(const char *target, const char *linkpath) {
    static orig_symlink_f_type orig_symlink = NULL;
    if (!orig_symlink) {
        orig_symlink = (orig_symlink_f_type)dlsym(RTLD_NEXT, "symlink");
    }
    char pbuf[PATH_MAX];
    const char *newlink = rewrite_path(linkpath, pbuf, sizeof(pbuf));
    return orig_symlink(target, newlink);
}

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

int chown(const char *pathname, uid_t owner, gid_t group) { (void)pathname; (void)owner; (void)group; return 0; }
int fchown(int fd, uid_t owner, gid_t group) { (void)fd; (void)owner; (void)group; return 0; }
int lchown(const char *pathname, uid_t owner, gid_t group) { (void)pathname; (void)owner; (void)group; return 0; }
int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    (void)dirfd; (void)pathname; (void)owner; (void)group; (void)flags; return 0;
}
int chroot(const char *path) { (void)path; return 0; }

// Hook execve
typedef int (*orig_execve_f_type)(const char *filename, char *const argv[], char *const envp[]);
int execve(const char *filename, char *const argv[], char *const envp[]) {
    static orig_execve_f_type orig_execve = NULL;
    if (!orig_execve) {
        orig_execve = (orig_execve_f_type)dlsym(RTLD_NEXT, "execve");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));

    // Transparently route glibc ELF binaries through ld.so if present
    init_cortex_hook();
    if (g_cortex_root[0] != '\0') {
        int fd = orig_open ? orig_open(target, O_RDONLY) : open(target, O_RDONLY);
        if (fd >= 0) {
            unsigned char elf_hdr[16];
            ssize_t n = read(fd, elf_hdr, 16);
            close(fd);
            if (n >= 4 && elf_hdr[0] == 0x7f && elf_hdr[1] == 'E' && elf_hdr[2] == 'L' && elf_hdr[3] == 'F') {
                char ld_so[PATH_MAX];
                #if defined(__aarch64__)
                snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-aarch64.so.1", g_cortex_root);
                #elif defined(__arm__)
                snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-armhf.so.3", g_cortex_root);
                #else
                snprintf(ld_so, sizeof(ld_so), "%s/lib64/ld-linux-x86-64.so.2", g_cortex_root);
                #endif

                if (access(ld_so, X_OK) == 0 && strcmp(target, ld_so) != 0) {
                    int argc = 0;
                    while (argv && argv[argc]) argc++;

                    char **new_argv = (char **)malloc(sizeof(char *) * (argc + 4));
                    new_argv[0] = ld_so;
                    new_argv[1] = (char *)"--argv0";
                    new_argv[2] = (char *)argv[0];
                    new_argv[3] = (char *)target;
                    for (int i = 1; i <= argc; i++) {
                        new_argv[i + 3] = argv[i];
                    }
                    return orig_execve(ld_so, new_argv, envp);
                }
            }
        }
    }

    return orig_execve(target, argv, envp);
}
