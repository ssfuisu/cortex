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

// Hook open
typedef int (*orig_open_f_type)(const char *pathname, int flags, ...);
int open(const char *pathname, int flags, ...) {
    static orig_open_f_type orig_open = NULL;
    if (!orig_open) {
        orig_open = (orig_open_f_type)dlsym(RTLD_NEXT, "open");
    }

    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (__OPEN_NEEDS_MODE(flags)) {
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
    if (__OPEN_NEEDS_MODE(flags)) {
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

// Hook execve
typedef int (*orig_execve_f_type)(const char *filename, char *const argv[], char *const envp[]);
int execve(const char *filename, char *const argv[], char *const envp[]) {
    static orig_execve_f_type orig_execve = NULL;
    if (!orig_execve) {
        orig_execve = (orig_execve_f_type)dlsym(RTLD_NEXT, "execve");
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_execve(target, argv, envp);
}
