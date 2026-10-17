/* Micro-benchmarks for libcortex-hook.so (see run-micro.sh, which builds and drives this).
 *
 * usage: micro <test> <root-prefix> <iterations>
 *
 *   <root-prefix>  "" when the hook has to translate the paths ("/etc/passwd" -> $CORTEX_ROOT/etc/passwd), or the
 *                  real root directory to measure the same call without the hook.
 *
 * Prints "<seconds> <checksum>". Any failing call ends the run with a message and exit code 2, so a broken run can
 * never look fast. BENCH_CPU=<n> pins the process (and so its children) to one CPU, BENCH_LDSO=<path> names the
 * dynamic linker used by the spawn_ldso test.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char **environ;
static volatile unsigned long long sink;

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static void die(const char *what, const char *path) {
    fprintf(stderr, "micro: %s failed on '%s': %s\n", what, path, strerror(errno));
    exit(2);
}

static void path_of(char *out, const char *root, const char *rel) {
    size_t a = strlen(root), b = strlen(rel);
    if (a + b >= PATH_MAX) {
        fprintf(stderr, "micro: path too long\n");
        exit(2);
    }
    memcpy(out, root, a);
    memcpy(out + a, rel, b + 1);
}

typedef void (*test_fn)(long n, const char *root);

/* control: no call the hook can see, so any difference between configurations is noise */
static void t_compute(long n, const char *root) {
    (void)root;
    unsigned long long x = 88172645463325252ULL, acc = 0;
    for (long i = 0; i < n * 200; i++) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; acc += x & 0xff; }
    sink += acc;
}

static void t_stat(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc/passwd");
    struct stat st;
    for (long i = 0; i < n; i++) { if (stat(p, &st) != 0) die("stat", p); sink += (unsigned long long)st.st_size; }
}

static void t_stat_deep(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/usr/lib/aarch64-linux-gnu/libc.so.6");
    struct stat st;
    for (long i = 0; i < n; i++) { if (stat(p, &st) != 0) die("stat", p); sink += (unsigned long long)st.st_size; }
}

/* what a shell does for every directory of PATH that lacks the command */
static void t_stat_miss(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/usr/local/bin/cortex-no-such-command");
    struct stat st;
    for (long i = 0; i < n; i++) { if (stat(p, &st) == 0 || errno != ENOENT) die("stat(miss)", p); sink++; }
}

static void t_stat_miss_usrbin(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/usr/bin/cortex-no-such-command");
    struct stat st;
    for (long i = 0; i < n; i++) { if (stat(p, &st) == 0 || errno != ENOENT) die("stat(miss)", p); sink++; }
}

static void t_lstat_file(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc/passwd");
    struct stat st;
    for (long i = 0; i < n; i++) { if (lstat(p, &st) != 0) die("lstat", p); sink += (unsigned long long)st.st_size; }
}

static void t_lstat_link(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/bin"); /* a symlink to usr/bin on Ubuntu 24.04 */
    struct stat st;
    for (long i = 0; i < n; i++) {
        if (lstat(p, &st) != 0 || !S_ISLNK(st.st_mode)) die("lstat(link)", p);
        sink++;
    }
}

static void t_access(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/usr/bin/bash");
    for (long i = 0; i < n; i++) { if (access(p, X_OK) != 0) die("access", p); sink++; }
}

static void t_open(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc/passwd");
    char b[32];
    for (long i = 0; i < n; i++) {
        int fd = open(p, O_RDONLY | O_CLOEXEC);
        if (fd < 0) die("open", p);
        ssize_t r = read(fd, b, sizeof b);
        if (r <= 0) die("read", p);
        sink += (unsigned long long)r;
        close(fd);
    }
}

/* the path is relative to a directory descriptor: nothing to translate, only the interposition itself */
static void t_openat_rel(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc");
    int dfd = open(p, O_RDONLY | O_DIRECTORY);
    if (dfd < 0) die("open(dir)", p);
    char b[32];
    for (long i = 0; i < n; i++) {
        int fd = openat(dfd, "passwd", O_RDONLY | O_CLOEXEC);
        if (fd < 0) die("openat", "passwd");
        ssize_t r = read(fd, b, sizeof b);
        if (r <= 0) die("read", "passwd");
        sink += (unsigned long long)r;
        close(fd);
    }
    close(dfd);
}

static void t_fopen(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc/passwd");
    for (long i = 0; i < n; i++) {
        FILE *f = fopen(p, "r");
        if (!f) die("fopen", p);
        sink += (unsigned long long)fgetc(f);
        fclose(f);
    }
}

static void t_readlink(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/bin");
    char b[PATH_MAX];
    for (long i = 0; i < n; i++) { ssize_t r = readlink(p, b, sizeof b); if (r <= 0) die("readlink", p); sink += (unsigned long long)r; }
}

static void t_readdir(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc/profile.d");
    for (long i = 0; i < n; i++) {
        DIR *d = opendir(p);
        if (!d) die("opendir", p);
        struct dirent *e;
        while ((e = readdir(d)) != NULL) sink += (unsigned char)e->d_name[0];
        closedir(d);
    }
}

/* needs the hook: Android's seccomp filter kills the process on the system call glibc's realpath() uses */
static void t_realpath(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/usr/bin/../lib/aarch64-linux-gnu");
    char out[PATH_MAX];
    for (long i = 0; i < n; i++) { if (!realpath(p, out)) die("realpath", p); sink += (unsigned long long)strlen(out); }
}

static void t_getcwd(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/etc");
    if (chdir(p) != 0) die("chdir", p);
    char out[PATH_MAX];
    for (long i = 0; i < n; i++) { if (!getcwd(out, sizeof out)) die("getcwd", p); sink += (unsigned long long)out[1]; }
}

/* calls the hook interposes but has nothing to translate: the cost of being interposed at all */
static void t_write_null(long n, const char *root) {
    (void)root;
    int fd = open("/dev/null", O_WRONLY);
    if (fd < 0) die("open", "/dev/null");
    for (long i = 0; i < n; i++) { if (write(fd, "x", 1) != 1) die("write", "/dev/null"); }
    close(fd);
}

static void t_dup_close(long n, const char *root) {
    (void)root;
    for (long i = 0; i < n; i++) { int fd = dup(0); if (fd < 0) die("dup", "0"); close(fd); }
}

static void t_sigprocmask(long n, const char *root) {
    (void)root;
    sigset_t set, old;
    sigemptyset(&set);
    sigaddset(&set, SIGUSR1);
    for (long i = 0; i < n; i++) {
        if (sigprocmask(SIG_BLOCK, &set, &old) != 0) die("sigprocmask", "block");
        if (sigprocmask(SIG_SETMASK, &old, NULL) != 0) die("sigprocmask", "restore");
    }
}

static void spawn_wait(const char *path, char *const argv[], long n) {
    for (long i = 0; i < n; i++) {
        pid_t pid;
        int rc = posix_spawn(&pid, path, NULL, NULL, argv, environ);
        if (rc != 0) { errno = rc; die("posix_spawn", path); }
        int status;
        if (waitpid(pid, &status, 0) != pid || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
            fprintf(stderr, "micro: child of '%s' did not exit cleanly (status 0x%x)\n", path, status);
            exit(2);
        }
        sink++;
    }
}

/* the way a shell starts "true": the hook sends the Ubuntu program through ld.so */
static void t_spawn_true(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/usr/bin/true");
    char *argv[] = { "true", NULL };
    spawn_wait(p, argv, n);
}

/* the floor: ld.so started by hand, so the hook only has to load into the child (if it is in LD_PRELOAD) */
static void t_spawn_ldso(long n, const char *root) {
    const char *real = getenv("CORTEX_ROOT");
    const char *base = real && *real ? real : root;
    const char *given = getenv("BENCH_LDSO");
    char ld[PATH_MAX], prog[PATH_MAX];
    if (given && *given) path_of(ld, given, "");
    else {
        path_of(ld, base, "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1");
    }
    path_of(prog, base, "/usr/bin/true");
    char *argv[] = { ld, "--argv0", "true", prog, NULL };
    spawn_wait(ld, argv, n);
}

/* a "#!/bin/sh" script in the rootfs */
static void t_spawn_script(long n, const char *root) {
    char p[PATH_MAX]; path_of(p, root, "/tmp/cortex-bench-script.sh");
    FILE *f = fopen(p, "w");
    if (!f) die("fopen", p);
    fputs("#!/bin/sh\nexit 0\n", f);
    fclose(f);
    if (chmod(p, 0755) != 0) die("chmod", p);
    char *argv[] = { "cortex-bench-script.sh", NULL };
    spawn_wait(p, argv, n);
}

/* an Android program (needs Android's /system/bin/sh) */
static void t_spawn_host(long n, const char *root) {
    (void)root;
    char *argv[] = { "sh", "-c", ":", NULL };
    spawn_wait("/system/bin/sh", argv, n);
}

static const struct { const char *name; test_fn fn; } TESTS[] = {
    { "compute", t_compute }, { "stat", t_stat }, { "stat_deep", t_stat_deep }, { "stat_miss", t_stat_miss },
    { "stat_miss_usrbin", t_stat_miss_usrbin }, { "lstat_file", t_lstat_file }, { "lstat_link", t_lstat_link },
    { "access", t_access }, { "open", t_open }, { "openat_rel", t_openat_rel }, { "fopen", t_fopen },
    { "readlink", t_readlink }, { "readdir", t_readdir }, { "realpath", t_realpath }, { "getcwd", t_getcwd },
    { "write_null", t_write_null }, { "dup_close", t_dup_close }, { "sigprocmask", t_sigprocmask },
    { "spawn_ldso", t_spawn_ldso }, { "spawn_true", t_spawn_true }, { "spawn_script", t_spawn_script },
    { "spawn_host", t_spawn_host },
};

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--list") == 0) {
        for (size_t i = 0; i < sizeof TESTS / sizeof TESTS[0]; i++) printf("%s\n", TESTS[i].name);
        return 0;
    }
    if (argc < 4) {
        fprintf(stderr, "usage: micro <test> <root-prefix> <iterations>   (micro --list shows the tests)\n");
        return 64;
    }
    const char *cpu = getenv("BENCH_CPU");
    if (cpu && *cpu) {
        cpu_set_t set;
        CPU_ZERO(&set);
        CPU_SET(atoi(cpu), &set);
        sched_setaffinity(0, sizeof set, &set);
    }
    long n = atol(argv[3]);
    for (size_t i = 0; i < sizeof TESTS / sizeof TESTS[0]; i++) {
        if (strcmp(TESTS[i].name, argv[1]) != 0) continue;
        TESTS[i].fn(n / 20 + 1, argv[2]); /* warm-up: lazy symbol binding, caches, CPU ramp-up */
        double t0 = now();
        TESTS[i].fn(n, argv[2]);
        double dt = now() - t0;
        printf("%.6f %llu\n", dt, sink);
        return 0;
    }
    fprintf(stderr, "micro: unknown test '%s'\n", argv[1]);
    return 64;
}
