#define _GNU_SOURCE
#ifndef CORTEX_HOST_TEST
#include <jni.h>
#include <android/log.h>
#define TAG "CortexPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGI(...) do { fprintf(stdout, __VA_ARGS__); fprintf(stdout, "\n"); } while (0)
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <limits.h>
#include <time.h>
#include <elf.h>
#include <dirent.h>
#include "zstd/zstd.h"

static char **clean_env_for_system(char *const envp[], int envCount) {
    char **new_env = malloc(sizeof(char *) * (envCount + 1));
    if (!new_env) return (char **)envp;
    int dst = 0;
    for (int i = 0; i < envCount; i++) {
        if (strncmp(envp[i], "LD_PRELOAD=", 11) != 0 &&
            strncmp(envp[i], "LD_LIBRARY_PATH=", 16) != 0 &&
            strncmp(envp[i], "GLIBC_TUNABLES=", 15) != 0) {
            new_env[dst++] = envp[i];
        }
    }
    new_env[dst] = NULL;
    return new_env;
}

static int find_dynamic_linker(const char *cortex_root, const char *cmd, char *out_ld_so, size_t max_len) {
    if (!cortex_root || cortex_root[0] == '\0') return 0;

    uint16_t e_machine = 0;
    if (cmd && cmd[0] != '\0') {
        int fd = open(cmd, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            unsigned char ehdr[20];
            ssize_t n = read(fd, ehdr, sizeof(ehdr));
            close(fd);
            if (n >= 20 && ehdr[0] == 0x7f && ehdr[1] == 'E' && ehdr[2] == 'L' && ehdr[3] == 'F') {
                e_machine = (uint16_t)(ehdr[18] | (ehdr[19] << 8));
            }
        }
    }

    const char *aarch64_cands[] = {
        "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/aarch64-linux-gnu/ld-2.39.so",
        "/lib/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/ld-linux-aarch64.so.1",
        "/usr/lib64/ld-linux-aarch64.so.1",
        "/lib64/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-2.39.so",
        NULL
    };

    const char *armhf_cands[] = {
        "/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/usr/lib/arm-linux-gnueabi/ld-linux.so.3",
        "/lib/arm-linux-gnueabi/ld-linux.so.3",
        NULL
    };

    const char *x86_64_cands[] = {
        "/lib64/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-2.39.so",
        "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        NULL
    };

    const char **primary = NULL;
    const char **secondary = NULL;

    if (e_machine == 183) { // EM_AARCH64
        primary = aarch64_cands;
        secondary = NULL;
    } else if (e_machine == 40) { // EM_ARM
        primary = armhf_cands;
        secondary = NULL;
    } else if (e_machine == 62) { // EM_X86_64
        primary = x86_64_cands;
        secondary = NULL;
    } else if (e_machine == 3) { // EM_386
        primary = NULL;
        secondary = NULL;
    } else {
        #if defined(__aarch64__)
        primary = aarch64_cands;
        secondary = armhf_cands;
        #elif defined(__arm__)
        primary = armhf_cands;
        secondary = aarch64_cands;
        #else
        primary = x86_64_cands;
        #endif
    }

    if (primary) {
        for (int i = 0; primary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", cortex_root, primary[i]);
            if (access(out_ld_so, F_OK) == 0) return 1;
        }
    }
    if (secondary) {
        for (int i = 0; secondary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", cortex_root, secondary[i]);
            if (access(out_ld_so, F_OK) == 0) return 1;
        }
    }

    return 0;
}

static int has_pt_interp(const char *path) {
    if (!path || path[0] == '\0') return 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    unsigned char buf[sizeof(Elf64_Ehdr)];
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n < (ssize_t)sizeof(Elf32_Ehdr)) {
        close(fd);
        return 0;
    }

    if (buf[EI_MAG0] != ELFMAG0 || buf[EI_MAG1] != ELFMAG1 ||
        buf[EI_MAG2] != ELFMAG2 || buf[EI_MAG3] != ELFMAG3 ||
        (buf[EI_CLASS] != ELFCLASS32 && buf[EI_CLASS] != ELFCLASS64) ||
        buf[EI_DATA] != ELFDATA2LSB) {
        close(fd);
        return 0;
    }

    int is_64 = (buf[EI_CLASS] == ELFCLASS64);
    uint64_t phoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;

    if (is_64) {
        if (n < (ssize_t)sizeof(Elf64_Ehdr)) {
            close(fd);
            return 0;
        }
        Elf64_Ehdr ehdr64;
        memcpy(&ehdr64, buf, sizeof(Elf64_Ehdr));
        phoff = ehdr64.e_phoff;
        phentsize = ehdr64.e_phentsize;
        phnum = ehdr64.e_phnum;
        if (phentsize < sizeof(Elf64_Phdr) || phentsize > 4096) {
            close(fd);
            return 0;
        }
    } else {
        Elf32_Ehdr ehdr32;
        memcpy(&ehdr32, buf, sizeof(Elf32_Ehdr));
        phoff = ehdr32.e_phoff;
        phentsize = ehdr32.e_phentsize;
        phnum = ehdr32.e_phnum;
        if (phentsize < sizeof(Elf32_Phdr) || phentsize > 4096) {
            close(fd);
            return 0;
        }
    }

    if (phoff == 0 || phentsize == 0 || phnum == 0 ||
        (off_t)phoff < 0 || (uint64_t)(off_t)phoff != phoff) {
        close(fd);
        return 0;
    }

    if (lseek(fd, (off_t)phoff, SEEK_SET) < 0) {
        close(fd);
        return 0;
    }

    for (int i = 0; i < phnum && i < 128; i++) {
        if (is_64) {
            Elf64_Phdr phdr;
            if (read(fd, &phdr, sizeof(Elf64_Phdr)) != (ssize_t)sizeof(Elf64_Phdr)) break;
            if (phdr.p_type == PT_INTERP) {
                close(fd);
                return 1;
            }
            if (phentsize > sizeof(Elf64_Phdr)) {
                if (lseek(fd, (off_t)(phentsize - sizeof(Elf64_Phdr)), SEEK_CUR) < 0) break;
            }
        } else {
            Elf32_Phdr phdr;
            if (read(fd, &phdr, sizeof(Elf32_Phdr)) != (ssize_t)sizeof(Elf32_Phdr)) break;
            if (phdr.p_type == PT_INTERP) {
                close(fd);
                return 1;
            }
            if (phentsize > sizeof(Elf32_Phdr)) {
                if (lseek(fd, (off_t)(phentsize - sizeof(Elf32_Phdr)), SEEK_CUR) < 0) break;
            }
        }
    }

    close(fd);
    return 0;
}

struct cortex_dirent64 {
    uint64_t d_ino;
    int64_t d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[];
};

// Closes the descriptors 3 .. max_fd-1. The open ones are read from /proc/self/fd with plain system
// calls (this runs between fork and exec in a multithreaded process: no malloc); trying every
// number took 32765 system calls per session with Android's limit of 32768 open files.
static void close_all_inherited_fds(int max_fd) {
    if (max_fd < 3) max_fd = 1024;
    int dir = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir >= 0) {
        char buf[4096] __attribute__((aligned(8)));
        int complete = 1;
        for (;;) {
            long n = syscall(SYS_getdents64, dir, buf, sizeof(buf));
            if (n == 0) break;
            if (n < 0) {
                complete = 0;
                break;
            }
            for (long off = 0; off < n;) {
                struct cortex_dirent64 *d = (struct cortex_dirent64 *)(buf + off);
                off += d->d_reclen;
                int fd = 0;
                const char *c = d->d_name;
                if (*c < '0' || *c > '9') continue;
                for (; *c >= '0' && *c <= '9' && fd < max_fd; c++) fd = fd * 10 + (*c - '0');
                // Closing a listed descriptor does not disturb the listing (it goes by number).
                if (fd > 2 && fd < max_fd && fd != dir) syscall(SYS_close, fd);
            }
        }
        syscall(SYS_close, dir);
        if (complete) return;
    }
    for (int fd = 3; fd < max_fd; fd++) {
        syscall(SYS_close, fd);
    }
}

// What the child of a new session does between vfork() and exec, all decided by the parent. The
// child shares the app's memory while the app's other threads go on, so it only makes plain system
// calls: no malloc, no locks, no fdsan bookkeeping (close() would mark the app's own descriptors as
// closed) and no sigaction() through ART's signal chain. fork() copied the whole app (a few ms with
// the main thread waiting, and copies of every page the app wrote until the shell started).
struct pty_exec {
    const char *path; // NULL ends the list
    char *const *argv;
    char *const *envp;
    const char *failure; // written when this one cannot be executed
};

struct pty_spawn {
    const char *slave_name;
    const char *cwd;
    const char *notice; // written to the terminal first, or NULL
    struct pty_exec exec[3]; // tried in order
    int max_fd;
};

static void pty_write(const char *text) {
    if (text) write(STDERR_FILENO, text, strlen(text));
}

static void pty_write_errno(int err) {
    char buf[24];
    int n = sizeof(buf);
    buf[--n] = '\n';
    buf[--n] = ')';
    do {
        buf[--n] = (char)('0' + err % 10);
        err /= 10;
    } while (err > 0 && n > 8);
    memcpy(buf + n - 8, " (error ", 8);
    write(STDERR_FILENO, buf + n - 8, sizeof(buf) - (size_t)(n - 8));
}

static void pty_child(const struct pty_spawn *s) {
    setsid();
    int fd = open(s->slave_name, O_RDWR);
    if (fd < 0) _exit(1);
#ifdef TIOCSCTTY
    ioctl(fd, TIOCSCTTY, 0);
#endif
    for (int target = 0; target <= 2; target++) {
        if (fd != target) syscall(SYS_dup3, fd, target, 0);
    }
    if (fd > STDERR_FILENO) syscall(SYS_close, fd);

    // The descriptors of the app (including the master side of this terminal) stay with the app
    close_all_inherited_fds(s->max_fd);

    // After TIOCSCTTY the session's own group is in the foreground already; said again for systems
    // that do not do it.
    pid_t pgrp = (pid_t)syscall(SYS_getpid);
    ioctl(STDIN_FILENO, TIOCSPGRP, &pgrp);

    struct termios tios;
    memset(&tios, 0, sizeof(struct termios));
    if (tcgetattr(STDIN_FILENO, &tios) == 0) {
        tios.c_iflag |= (ICRNL | IXON);
        tios.c_oflag |= (OPOST | ONLCR);
        tios.c_cflag |= (CS8 | CREAD);
        tios.c_lflag |= (ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHOCTL | ECHOKE | IEXTEN);
        tcsetattr(STDIN_FILENO, TCSANOW, &tios);
    }

    // Default dispositions for the signals the app may ignore (an ignored signal stays ignored
    // across exec), and nothing blocked. A zeroed kernel sigaction is SIG_DFL with an empty mask on
    // every architecture; the kernel's signal set is 8 bytes.
    static const int defaults[] = { SIGCHLD, SIGHUP, SIGINT, SIGQUIT, SIGTERM, SIGPIPE, SIGTSTP, SIGTTIN, SIGTTOU };
    unsigned long kernel_action[8];
    memset(kernel_action, 0, sizeof(kernel_action));
    for (size_t i = 0; i < sizeof(defaults) / sizeof(defaults[0]); i++) {
        syscall(SYS_rt_sigaction, defaults[i], kernel_action, NULL, 8);
    }
    uint64_t no_signals = 0;
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &no_signals, NULL, 8);

    if (s->cwd && chdir(s->cwd) != 0) {
        chdir("/");
    }

    pty_write(s->notice);
    for (int i = 0; i < 3 && s->exec[i].path; i++) {
        execve(s->exec[i].path, s->exec[i].argv, s->exec[i].envp);
        int err = errno;
        if (s->exec[i].failure) {
            pty_write(s->exec[i].failure);
            pty_write_errno(err);
        }
    }
    _exit(127);
}

// Starts the child of a session with vfork() (fork() when that fails). Returns its pid or -1.
static pid_t spawn_in_pty(const struct pty_spawn *s) {
    pid_t pid = vfork();
    if (pid < 0) pid = fork();
    if (pid == 0) pty_child(s);
    return pid;
}

#ifndef CORTEX_HOST_TEST

JNIEXPORT jintArray JNICALL
Java_org_cortex_terminal_pty_PtyNative_createPty(
    JNIEnv *env,
    jclass clazz,
    jstring cmdStr,
    jobjectArray argsArray,
    jobjectArray envArray,
    jstring cwdStr,
    jint rows,
    jint cols,
    jint widthPx,
    jint heightPx
) {
    int masterFd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (masterFd < 0) {
        masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    }
    if (masterFd < 0) {
        LOGE("Failed to open ptmx: %s", strerror(errno));
        return NULL;
    }
    fcntl(masterFd, F_SETFD, FD_CLOEXEC);

    if (grantpt(masterFd) < 0) {
        LOGE("Failed to grantpt: %s", strerror(errno));
        close(masterFd);
        return NULL;
    }

    if (unlockpt(masterFd) < 0) {
        LOGE("Failed to unlockpt: %s", strerror(errno));
        close(masterFd);
        return NULL;
    }

    char slaveName[64];
    if (ptsname_r(masterFd, slaveName, sizeof(slaveName)) != 0) {
        LOGE("Failed to get ptsname: %s", strerror(errno));
        close(masterFd);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ws.ws_xpixel = (unsigned short)widthPx;
    ws.ws_ypixel = (unsigned short)heightPx;
    ioctl(masterFd, TIOCSWINSZ, &ws);

    // Prepare command and arguments
    const char *cmd = (*env)->GetStringUTFChars(env, cmdStr, NULL);

    int argCount = argsArray ? (*env)->GetArrayLength(env, argsArray) : 0;
    char **argv = malloc(sizeof(char *) * (argCount + 2));
    argv[0] = strdup(cmd);
    for (int i = 0; i < argCount; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, argsArray, i);
        const char *argChars = (*env)->GetStringUTFChars(env, arg, NULL);
        argv[i + 1] = strdup(argChars);
        (*env)->ReleaseStringUTFChars(env, arg, argChars);
        (*env)->DeleteLocalRef(env, arg);
    }
    argv[argCount + 1] = NULL;

    // Prepare environment variables
    int envCount = envArray ? (*env)->GetArrayLength(env, envArray) : 0;
    char **envp = malloc(sizeof(char *) * (envCount + 1));
    for (int i = 0; i < envCount; i++) {
        jstring envItem = (jstring)(*env)->GetObjectArrayElement(env, envArray, i);
        const char *envChars = (*env)->GetStringUTFChars(env, envItem, NULL);
        envp[i] = strdup(envChars);
        (*env)->ReleaseStringUTFChars(env, envItem, envChars);
        (*env)->DeleteLocalRef(env, envItem);
    }
    envp[envCount] = NULL;

    const char *cwd = cwdStr ? (*env)->GetStringUTFChars(env, cwdStr, NULL) : NULL;

    // Pre-calculate max_fd and pre-allocate child structures before fork()
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0) max_fd = 1024;
    else if (max_fd > 65536) max_fd = 65536;

    char **new_argv = calloc(argCount + 5, sizeof(char *));
    char **sys_env = clean_env_for_system(envp, envCount);

    // Everything the child does is worked out here, before it exists (see struct pty_spawn)
    const char *cortex_root = NULL;
    for (int i = 0; i < envCount; i++) {
        if (strncmp(envp[i], "CORTEX_ROOT=", 12) == 0) {
            cortex_root = envp[i] + 12;
            break;
        }
    }
    int in_root = cortex_root && cortex_root[0] != '\0' && strncmp(cmd, cortex_root, strlen(cortex_root)) == 0;

    static char *const fallback_sh_argv[] = { "/system/bin/sh", NULL };
    char ld_so[PATH_MAX] = {0};
    char notice[PATH_MAX + 256];
    char ld_so_failure[PATH_MAX + 64];
    char cmd_failure[PATH_MAX + 64];
    snprintf(cmd_failure, sizeof(cmd_failure), "Cortex: failed to execute %s", cmd);
    struct pty_spawn spawn;
    memset(&spawn, 0, sizeof(spawn));
    spawn.slave_name = slaveName;
    spawn.cwd = cwd;
    spawn.max_fd = (int)max_fd;
    // A host program (outside the rootfs, e.g. /system/bin/sh) gets an environment without Cortex's
    // variables.
    struct pty_exec direct = { cmd, argv, in_root ? envp : sys_env, cmd_failure };
    spawn.exec[0] = direct;

    if (in_root) {
        // Ubuntu's dynamic programs are started through glibc's ld.so
        int is_glibc_elf = 0;
        int fd = open(cmd, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            char hdr[4];
            ssize_t n = read(fd, hdr, sizeof(hdr));
            close(fd);
            is_glibc_elf = n >= 4 && (unsigned char)hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F';
        }
        if (is_glibc_elf && has_pt_interp(cmd)) {
            if (find_dynamic_linker(cortex_root, cmd, ld_so, sizeof(ld_so)) && new_argv) {
                chmod(ld_so, 0755);
                chmod(cmd, 0755);
                const char *prog_name = strrchr(cmd, '/');
                prog_name = (prog_name != NULL) ? prog_name + 1 : cmd;
                new_argv[0] = ld_so;
                new_argv[1] = "--argv0";
                new_argv[2] = (char *)prog_name;
                new_argv[3] = (char *)cmd;
                for (int i = 1; i <= argCount; i++) {
                    new_argv[i + 3] = argv[i];
                }
                new_argv[argCount + 4] = NULL;
                snprintf(ld_so_failure, sizeof(ld_so_failure), "Cortex: failed to exec ld.so (%s)", ld_so);
                struct pty_exec through_ld_so = { ld_so, new_argv, envp, ld_so_failure };
                spawn.exec[0] = through_ld_so;
                spawn.exec[1] = direct;
            } else {
                snprintf(notice, sizeof(notice),
                    "Cortex: Dynamic linker (ld.so) not found in %s!\n"
                    "Ubuntu 24.04 environment may be corrupted or still initializing.\n"
                    "Falling back to Android system shell...\n\n", cortex_root);
                spawn.notice = notice;
                struct pty_exec android_sh = { "/system/bin/sh", fallback_sh_argv, sys_env, NULL };
                spawn.exec[0] = android_sh;
                spawn.exec[1] = direct;
            }
        }
    }

    struct timespec spawn_started, spawn_done;
    clock_gettime(CLOCK_MONOTONIC, &spawn_started);
    pid_t pid = spawn_in_pty(&spawn);
    clock_gettime(CLOCK_MONOTONIC, &spawn_done);
    LOGI("Session process %d started in %ld us", (int)pid,
         (long)((spawn_done.tv_sec - spawn_started.tv_sec) * 1000000L + (spawn_done.tv_nsec - spawn_started.tv_nsec) / 1000));
    if (pid < 0) {
        LOGE("Failed to start the session: %s", strerror(errno));
        close(masterFd);
        free(new_argv);
        if (sys_env && sys_env != envp) free(sys_env);
        if (argv) {
            for (int i = 0; i <= argCount; i++) {
                free(argv[i]);
            }
            free(argv);
        }
        if (envp) {
            for (int i = 0; i < envCount; i++) {
                free(envp[i]);
            }
            free(envp);
        }
        (*env)->ReleaseStringUTFChars(env, cmdStr, cmd);
        if (cwd) (*env)->ReleaseStringUTFChars(env, cwdStr, cwd);
        return NULL;
    }

    // Parent cleanup
    free(new_argv);
    if (sys_env && sys_env != envp) {
        free(sys_env);
    }
    for (int i = 0; i <= argCount; i++) {
        free(argv[i]);
    }
    free(argv);

    for (int i = 0; i < envCount; i++) {
        free(envp[i]);
    }
    free(envp);

    (*env)->ReleaseStringUTFChars(env, cmdStr, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, cwdStr, cwd);


    jintArray result = (*env)->NewIntArray(env, 2);
    jint values[2] = { masterFd, pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL
Java_org_cortex_terminal_pty_PtyNative_setPtyWindowSize(
    JNIEnv *env,
    jclass clazz,
    jint masterFd,
    jint rows,
    jint cols,
    jint widthPx,
    jint heightPx
) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ws.ws_xpixel = (unsigned short)widthPx;
    ws.ws_ypixel = (unsigned short)heightPx;
    ioctl(masterFd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_org_cortex_terminal_pty_PtyNative_waitForProcess(
    JNIEnv *env,
    jclass clazz,
    jint pid
) {
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) {
            return -1;
        }
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }
    return status;
}

JNIEXPORT void JNICALL
Java_org_cortex_terminal_pty_PtyNative_killProcess(
    JNIEnv *env,
    jclass clazz,
    jint pid,
    jint sig
) {
    kill(pid, sig);
}

JNIEXPORT void JNICALL
Java_org_cortex_terminal_pty_PtyNative_closeFd(
    JNIEnv *env,
    jclass clazz,
    jint fd
) {
    if (fd >= 0) {
        close(fd);
    }
}
#endif /* !CORTEX_HOST_TEST */

static int has_dotdot_component(const char *path) {
    if (!path) return 0;
    const char *p = path;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *end = p;
        while (*end && *end != '/') end++;
        if ((end - p) == 2 && p[0] == '.' && p[1] == '.') {
            return 1;
        }
        p = end;
    }
    return 0;
}

static const char *strip_tar_prefix(const char *path) {
    if (!path) return "";
    const char *p = path;
    while (*p) {
        if (*p == '/') {
            p++;
        } else if (p[0] == '.' && p[1] == '/') {
            p += 2;
        } else if (p[0] == '.' && p[1] == '\0') {
            p += 1;
        } else {
            break;
        }
    }
    return p;
}

static int is_tar_path_unsafe(const char *path) {
    if (!path || path[0] == '\0') return 0;
    if (path[0] == '/') return 1;
    if (has_dotdot_component(path)) return 1;
    return 0;
}

static int is_symlink_target_safe(const char *rel_entry_path, const char *linkname) {
    if (!rel_entry_path || !linkname || linkname[0] == '\0') return 0;
    if (linkname[0] == '/') return 0;

    int depth = 0;
    const char *p = rel_entry_path;
    const char *last_slash = strrchr(rel_entry_path, '/');
    if (last_slash) {
        while (p < last_slash) {
            while (p < last_slash && *p == '/') p++;
            if (p >= last_slash) break;
            const char *end = p;
            while (end < last_slash && *end != '/') end++;
            size_t len = (size_t)(end - p);
            if (len == 2 && p[0] == '.' && p[1] == '.') {
                return 0;
            } else if (len == 1 && p[0] == '.') {
                /* ignore */
            } else if (len > 0) {
                depth++;
            }
            p = end;
        }
    }

    p = linkname;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *end = p;
        while (*end && *end != '/') end++;
        size_t len = (size_t)(end - p);
        if (len == 2 && p[0] == '.' && p[1] == '.') {
            depth--;
            if (depth < 0) return 0;
        } else if (len == 1 && p[0] == '.') {
            /* ignore */
        } else if (len > 0) {
            depth++;
        }
        p = end;
    }

    return 1;
}

static int is_within_dir(const char *real_root, const char *candidate) {
    if (!real_root || !candidate) return 0;
    size_t rlen = strlen(real_root);
    if (rlen > 1 && real_root[rlen - 1] == '/') rlen--;
    if (strncmp(candidate, real_root, rlen) != 0) return 0;
    return (candidate[rlen] == '/' || candidate[rlen] == '\0');
}

static int mkdirs_for_path(const char *dest_dir, const char *dest_path) {
    if (!dest_dir || !dest_path) return -1;

    char real_dest_dir[PATH_MAX];
    if (!realpath(dest_dir, real_dest_dir)) {
        mkdir(dest_dir, 0755);
        if (!realpath(dest_dir, real_dest_dir)) {
            strncpy(real_dest_dir, dest_dir, sizeof(real_dest_dir) - 1);
            real_dest_dir[sizeof(real_dest_dir) - 1] = '\0';
        }
    }

    size_t dlen = strlen(dest_dir);
    while (dlen > 1 && dest_dir[dlen - 1] == '/') dlen--;
    if (strncmp(dest_path, dest_dir, dlen) != 0 || dest_path[dlen] != '/') {
        return -1;
    }

    char temp[PATH_MAX];
    size_t plen = strlen(dest_path);
    if (plen >= sizeof(temp)) return -1;
    memcpy(temp, dest_path, plen + 1);

    for (char *p = temp + dlen + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            struct stat st;
            if (lstat(temp, &st) == 0) {
                if (S_ISLNK(st.st_mode)) {
                    char link_buf[PATH_MAX];
                    ssize_t llen = readlink(temp, link_buf, sizeof(link_buf) - 1);
                    if (llen < 0) return -1;
                    link_buf[llen] = '\0';
                    if (link_buf[0] == '/' || has_dotdot_component(link_buf)) {
                        return -1;
                    }
                    char resolved[PATH_MAX];
                    if (!realpath(temp, resolved) || !is_within_dir(real_dest_dir, resolved)) {
                        return -1;
                    }
                } else if (!S_ISDIR(st.st_mode)) {
                    return -1;
                }
            } else {
                if (mkdir(temp, 0755) != 0 && errno != EEXIST) {
                    return -1;
                }
            }
            *p = '/';
        }
    }
    return 0;
}

static ssize_t read_full(int fd, void *buf, size_t count) {
    size_t total = 0;
    char *p = (char *)buf;
    while (total < count) {
        ssize_t n = read(fd, p + total, count - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) break;
        total += (size_t)n;
    }
    return (ssize_t)total;
}

static int verify_tar_checksum(const char *block) {
    const unsigned char *u = (const unsigned char *)block;
    const signed char *s = (const signed char *)block;
    const char *chk_ptr = block + 148;
    while (chk_ptr < block + 156 && (*chk_ptr == ' ' || *chk_ptr == '\t')) chk_ptr++;
    if (chk_ptr >= block + 156 || *chk_ptr < '0' || *chk_ptr > '7') return 0;

    char chk_buf[16];
    size_t rem = (size_t)((block + 156) - chk_ptr);
    if (rem >= sizeof(chk_buf)) rem = sizeof(chk_buf) - 1;
    memcpy(chk_buf, chk_ptr, rem);
    chk_buf[rem] = '\0';

    char *endptr = NULL;
    unsigned long expected = strtoul(chk_buf, &endptr, 8);
    if (endptr == chk_buf) return 0;

    unsigned long usum = 0;
    long ssum = 0;
    for (int i = 0; i < 512; i++) {
        if (i >= 148 && i < 156) {
            usum += ' ';
            ssum += ' ';
        } else {
            usum += u[i];
            ssum += s[i];
        }
    }
    return (usum == expected || (ssum >= 0 && (unsigned long)ssum == expected));
}

static int parse_pax_headers(const char *buf, size_t len,
                             char *out_path, size_t path_max,
                             char *out_linkpath, size_t link_max,
                             unsigned long long *out_size, int *has_size) {
    size_t pos = 0;
    while (pos < len) {
        if (buf[pos] == '\0') break;
        const char *rec = buf + pos;
        size_t rem = len - pos;

        size_t rec_len = 0;
        size_t i = 0;
        while (i < rem && rec[i] >= '0' && rec[i] <= '9') {
            if (rec_len > (SIZE_MAX - 9) / 10) return -1;
            rec_len = rec_len * 10 + (size_t)(rec[i] - '0');
            i++;
        }
        if (i == 0 || i >= rem || rec[i] != ' ' || rec_len <= i + 1 || rec_len > rem) {
            return -1;
        }
        if (rec[rec_len - 1] != '\n') {
            return -1;
        }

        const char *kv = rec + i + 1;
        size_t kv_len = rec_len - (i + 1) - 1;

        if (kv_len >= 5 && strncmp(kv, "path=", 5) == 0) {
            size_t vlen = kv_len - 5;
            if (vlen >= path_max) return -1;
            if (out_path) {
                memcpy(out_path, kv + 5, vlen);
                out_path[vlen] = '\0';
            }
        } else if (kv_len >= 9 && strncmp(kv, "linkpath=", 9) == 0) {
            size_t vlen = kv_len - 9;
            if (vlen >= link_max) return -1;
            if (out_linkpath) {
                memcpy(out_linkpath, kv + 9, vlen);
                out_linkpath[vlen] = '\0';
            }
        } else if (kv_len >= 5 && strncmp(kv, "size=", 5) == 0) {
            size_t vlen = kv_len - 5;
            if (vlen == 0 || vlen >= 32) return -1;
            unsigned long long parsed = 0;
            for (size_t k = 0; k < vlen; k++) {
                char c = kv[5 + k];
                if (c < '0' || c > '9') return -1;
                if (parsed > (ULLONG_MAX - 9ULL) / 10ULL) return -1;
                parsed = parsed * 10ULL + (unsigned long long)(c - '0');
            }
            if (out_size) *out_size = parsed;
            if (has_size) *has_size = 1;
        }

        pos += rec_len;
    }
    return 0;
}

#define TAR_COPY_BUF_SIZE 65536

// What the extractor reads: a tar stream, or one compressed with zstd (the bootstrap archive is a
// .tar.zst), which is decompressed on the way. The first four bytes tell which.
typedef struct {
    int fd;
    ZSTD_DStream *zstd;  // NULL for a plain tar
    unsigned char *in;   // compressed bytes read, in_buf says how far they are used
    size_t in_cap;
    ZSTD_inBuffer in_buf;
    int in_eof;
    size_t frame_left;   // ZSTD_decompressStream's last answer: 0 at the end of a frame
    unsigned char *out;  // decompressed bytes not yet taken
    size_t out_cap, out_pos, out_len;
    unsigned char head[4];
    size_t head_pos, head_len;
} tar_source;

static int tar_source_open(tar_source *src, int fd) {
    memset(src, 0, sizeof(*src));
    src->fd = fd;
    ssize_t n = read_full(fd, src->head, sizeof(src->head));
    if (n < 0) return -1;
    src->head_len = (size_t)n;
    if (n == 4 && src->head[0] == 0x28 && src->head[1] == 0xB5 && src->head[2] == 0x2F && src->head[3] == 0xFD) {
        src->zstd = ZSTD_createDStream();
        src->in_cap = ZSTD_DStreamInSize();
        src->out_cap = ZSTD_DStreamOutSize();
        src->in = malloc(src->in_cap);
        src->out = malloc(src->out_cap);
        if (!src->zstd || !src->in || !src->out || src->in_cap < 4) return -1;
        ZSTD_initDStream(src->zstd);
        memcpy(src->in, src->head, 4);
        src->in_buf.src = src->in;
        src->in_buf.size = 4;
        src->in_buf.pos = 0;
        src->head_len = 0;
    }
    return 0;
}

static void tar_source_close(tar_source *src) {
    if (src->zstd) ZSTD_freeDStream(src->zstd);
    free(src->in);
    free(src->out);
    src->zstd = NULL;
    src->in = src->out = NULL;
}

// Like read_full: count bytes, fewer only at the end, -1 on a read or decompression error (also
// when the compressed stream ends inside a frame).
static ssize_t tar_source_read(tar_source *src, void *buf, size_t count) {
    unsigned char *p = (unsigned char *)buf;
    size_t total = 0;
    if (!src->zstd) {
        while (total < count && src->head_pos < src->head_len) p[total++] = src->head[src->head_pos++];
        if (total == count) return (ssize_t)total;
        ssize_t n = read_full(src->fd, p + total, count - total);
        return n < 0 ? -1 : (ssize_t)(total + (size_t)n);
    }
    while (total < count) {
        if (src->out_pos < src->out_len) {
            size_t take = src->out_len - src->out_pos;
            if (take > count - total) take = count - total;
            memcpy(p + total, src->out + src->out_pos, take);
            src->out_pos += take;
            total += take;
            continue;
        }
        if (src->in_buf.pos == src->in_buf.size && !src->in_eof) {
            ssize_t n;
            do {
                n = read(src->fd, src->in, src->in_cap);
            } while (n < 0 && errno == EINTR);
            if (n < 0) return -1;
            if (n == 0) src->in_eof = 1;
            src->in_buf.size = (size_t)n;
            src->in_buf.pos = 0;
        }
        ZSTD_outBuffer out = { src->out, src->out_cap, 0 };
        size_t ret = ZSTD_decompressStream(src->zstd, &out, &src->in_buf);
        if (ZSTD_isError(ret)) {
            LOGE("The archive cannot be decompressed (zstd error %d)", (int)ZSTD_getErrorCode(ret));
            return -1;
        }
        src->frame_left = ret;
        src->out_pos = 0;
        src->out_len = out.pos;
        if (out.pos == 0 && src->in_eof && src->in_buf.pos == src->in_buf.size) {
            if (src->frame_left != 0) {
                LOGE("The compressed archive ends too early");
                return -1;
            }
            break;
        }
    }
    return (ssize_t)total;
}

static int extract_tar_source(tar_source *src, const char *dest_dir);

// Extracts the tar stream (or zstd-compressed tar stream) read from fd (a file or a pipe, which is
// not closed) into dest_dir.
static int extract_tar_fd(int fd, const char *dest_dir) {
    if (fd < 0 || !dest_dir) return -1;
    tar_source src;
    int res = tar_source_open(&src, fd) == 0 ? extract_tar_source(&src, dest_dir) : -1;
    tar_source_close(&src);
    return res;
}

static int extract_tar_source(tar_source *src, const char *dest_dir) {
    char *copy_buf = malloc(TAR_COPY_BUF_SIZE);
    if (!copy_buf) {
        return -1;
    }

    char block[512];
    char long_name[PATH_MAX] = {0};
    char long_link[PATH_MAX] = {0};
    unsigned long long pax_size = 0;
    // Android's SELinux policy refuses link() to apps: after the first refusal every hard link of
    // the archive becomes a relative symlink right away (each refusal also writes an audit message).
    int hard_links_allowed = 1;
    int has_pax_size = 0;

    for (;;) {
        ssize_t hdr_n = tar_source_read(src, block, 512);
        if (hdr_n == 0) break;
        if (hdr_n != 512) {
            free(copy_buf);
            return -1;
        }

        int all_zero = 1;
        for (int i = 0; i < 512; i++) {
            if (block[i] != 0) { all_zero = 0; break; }
        }
        if (all_zero) break;

        if (!verify_tar_checksum(block)) {
            LOGE("Tar header checksum verification failed");
            free(copy_buf);
            return -1;
        }

        unsigned long mode = strtoul(block + 100, NULL, 8);
        if (mode == 0) mode = 0755;
        unsigned long long size = strtoull(block + 124, NULL, 8);
        char typeflag = block[156];

        if (size > ULLONG_MAX - 511ULL) {
            free(copy_buf);
            return -1;
        }

        if (typeflag == 'L' || typeflag == 'K') {
            char *dst_buf = (typeflag == 'L') ? long_name : long_link;
            size_t dst_max = (typeflag == 'L') ? sizeof(long_name) : sizeof(long_link);
            if (size == 0 || size >= dst_max) {
                LOGE("Tar GNU LongName/LongLink size (%llu) exceeds PATH_MAX", size);
                free(copy_buf);
                return -1;
            }
            unsigned long long padded = ((size + 511ULL) / 512ULL) * 512ULL;
            size_t pos = 0;
            while (padded > 0) {
                char dblock[512];
                if (tar_source_read(src, dblock, 512) != 512) {
                    free(copy_buf);
                    return -1;
                }
                size_t rem_payload = (size > pos) ? (size_t)(size - pos) : 0;
                size_t chunk = (rem_payload < 512) ? rem_payload : 512;
                if (chunk > 0) {
                    memcpy(dst_buf + pos, dblock, chunk);
                    pos += chunk;
                }
                padded -= 512ULL;
            }
            dst_buf[pos] = '\0';
            continue;
        }

        if (typeflag == 'x' || typeflag == 'g') {
            if (size > 1024ULL * 1024ULL) {
                free(copy_buf);
                return -1;
            }
            unsigned long long padded = ((size + 511ULL) / 512ULL) * 512ULL;
            char *pax_buf = malloc((size_t)padded + 1);
            if (!pax_buf) {
                free(copy_buf);
                return -1;
            }
            if (tar_source_read(src, pax_buf, (size_t)padded) != (ssize_t)padded) {
                free(pax_buf);
                free(copy_buf);
                return -1;
            }
            pax_buf[size] = '\0';
            int rc = 0;
            if (typeflag == 'x') {
                rc = parse_pax_headers(pax_buf, (size_t)size,
                                       long_name, sizeof(long_name),
                                       long_link, sizeof(long_link),
                                       &pax_size, &has_pax_size);
            } else {
                rc = parse_pax_headers(pax_buf, (size_t)size,
                                       NULL, sizeof(long_name),
                                       NULL, sizeof(long_link),
                                       NULL, NULL);
            }
            free(pax_buf);
            if (rc != 0) {
                free(copy_buf);
                return -1;
            }
            continue;
        }

        if (has_pax_size) {
            size = pax_size;
            has_pax_size = 0;
            if (size > ULLONG_MAX - 511ULL) {
                free(copy_buf);
                return -1;
            }
        }

        char name[PATH_MAX] = {0};
        char linkname[PATH_MAX] = {0};

        if (long_name[0] != '\0') {
            strncpy(name, long_name, sizeof(name) - 1);
            name[sizeof(name) - 1] = '\0';
            long_name[0] = '\0';
        } else {
            if (strncmp(block + 257, "ustar", 5) == 0 && block[345] != '\0') {
                snprintf(name, sizeof(name), "%.155s/%.100s", block + 345, block);
            } else {
                snprintf(name, sizeof(name), "%.100s", block);
            }
        }

        if (long_link[0] != '\0') {
            strncpy(linkname, long_link, sizeof(linkname) - 1);
            linkname[sizeof(linkname) - 1] = '\0';
            long_link[0] = '\0';
        } else {
            snprintf(linkname, sizeof(linkname), "%.100s", block + 157);
        }

        if (is_tar_path_unsafe(name)) {
            LOGE("Tar-Slip security guard: rejecting unsafe entry name='%s'", name);
            free(copy_buf);
            return -1;
        }

        const char *rel = strip_tar_prefix(name);
        if (*rel == '\0') {
            unsigned long long padded = ((size + 511ULL) / 512ULL) * 512ULL;
            while (padded > 0) {
                size_t to_read = (padded < TAR_COPY_BUF_SIZE) ? (size_t)padded : TAR_COPY_BUF_SIZE;
                if (tar_source_read(src, copy_buf, to_read) != (ssize_t)to_read) {
                    free(copy_buf);
                    return -1;
                }
                padded -= to_read;
            }
            continue;
        }

        if (typeflag == '2' && !is_symlink_target_safe(rel, linkname)) {
            LOGE("Tar-Slip security guard: rejecting escaping symlink name='%s' -> '%s'", name, linkname);
            free(copy_buf);
            return -1;
        }

        if (typeflag == '1' && is_tar_path_unsafe(linkname)) {
            LOGE("Tar-Slip security guard: rejecting unsafe hardlink name='%s' -> '%s'", name, linkname);
            free(copy_buf);
            return -1;
        }

        // A directory entry ends in '/': only its parents are checked below. The directory itself
        // replaces whatever is in its place (a symlink is removed, never followed), so a link the
        // app made before extracting (dev/shm) no longer fails the whole archive.
        size_t rel_len = strlen(rel);
        while (rel_len > 0 && rel[rel_len - 1] == '/') rel_len--;
        char dest_path[PATH_MAX];
        int dplen = snprintf(dest_path, sizeof(dest_path), "%s/%.*s", dest_dir, (int)rel_len, rel);
        if (dplen < 0 || (size_t)dplen >= sizeof(dest_path)) {
            free(copy_buf);
            return -1;
        }

        if (mkdirs_for_path(dest_dir, dest_path) != 0) {
            LOGE("Tar-Slip security guard: unsafe parent directory for '%s'", dest_path);
            free(copy_buf);
            return -1;
        }

        size_t nlen = strlen(name);
        if (typeflag == '5' || (typeflag == '\0' && nlen > 0 && name[nlen - 1] == '/')) {
            struct stat dst_st;
            if (lstat(dest_path, &dst_st) == 0 && S_ISLNK(dst_st.st_mode)) {
                unlink(dest_path);
            }
            mkdir(dest_path, mode & 0777);
            chmod(dest_path, (mode & 0777) | 0700);
        } else if (typeflag == '2') {
            unlink(dest_path);
            rmdir(dest_path);
            if (symlink(linkname, dest_path) != 0) {
                free(copy_buf);
                return -1;
            }
        } else if (typeflag == '1') {
            const char *lrel = strip_tar_prefix(linkname);
            if (*lrel == '\0' || has_dotdot_component(lrel)) {
                free(copy_buf);
                return -1;
            }
            char target_path[PATH_MAX];
            int tplen = snprintf(target_path, sizeof(target_path), "%s/%s", dest_dir, lrel);
            if (tplen < 0 || (size_t)tplen >= sizeof(target_path) ||
                mkdirs_for_path(dest_dir, target_path) != 0) {
                free(copy_buf);
                return -1;
            }
            unlink(dest_path);
            rmdir(dest_path);
            int linked = 0;
            if (hard_links_allowed) {
                linked = link(target_path, dest_path) == 0;
                if (!linked && (errno == EACCES || errno == EPERM)) hard_links_allowed = 0;
            }
            if (!linked) {
                char rel_link[PATH_MAX] = {0};
                size_t rpos = 0;
                const char *rp = rel;
                const char *last_slash = strrchr(rel, '/');
                if (last_slash) {
                    while (rp < last_slash) {
                        while (rp < last_slash && *rp == '/') rp++;
                        if (rp >= last_slash) break;
                        const char *rend = rp;
                        while (rend < last_slash && *rend != '/') rend++;
                        if ((rend - rp) > 0 && !((rend - rp) == 1 && rp[0] == '.')) {
                            if (rpos + 3 >= sizeof(rel_link)) {
                                free(copy_buf);
                                return -1;
                            }
                            memcpy(rel_link + rpos, "../", 3);
                            rpos += 3;
                        }
                        rp = rend;
                    }
                }
                size_t lrel_len = strlen(lrel);
                if (rpos + lrel_len >= sizeof(rel_link)) {
                    free(copy_buf);
                    return -1;
                }
                memcpy(rel_link + rpos, lrel, lrel_len + 1);
                if (symlink(rel_link, dest_path) != 0) {
                    free(copy_buf);
                    return -1;
                }
            }
        } else {
            unlink(dest_path);
            rmdir(dest_path);
            int out_fd = open(dest_path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, (mode & 0777) | 0600);
            if (out_fd < 0) {
                free(copy_buf);
                return -1;
            }
            unsigned long long rem_data = size;
            unsigned long long total_to_read = ((size + 511ULL) / 512ULL) * 512ULL;

            while (total_to_read > 0) {
                size_t to_read = (total_to_read < TAR_COPY_BUF_SIZE) ? (size_t)total_to_read : TAR_COPY_BUF_SIZE;
                if (tar_source_read(src, copy_buf, to_read) != (ssize_t)to_read) {
                    close(out_fd);
                    free(copy_buf);
                    return -1;
                }
                total_to_read -= to_read;

                size_t to_write = (rem_data < (unsigned long long)to_read) ? (size_t)rem_data : to_read;
                if (to_write > 0) {
                    size_t written = 0;
                    while (written < to_write) {
                        ssize_t w = write(out_fd, copy_buf + written, to_write - written);
                        if (w < 0) {
                            if (errno == EINTR) continue;
                            close(out_fd);
                            free(copy_buf);
                            return -1;
                        }
                        if (w == 0) {
                            close(out_fd);
                            free(copy_buf);
                            return -1;
                        }
                        written += (size_t)w;
                    }
                    rem_data -= to_write;
                }
            }

            // The archive's mode, plus read and write for the owner: the files are meant for root,
            // and here the app's user stands in for root.
            fchmod(out_fd, (mode & 0777) | 0600);
            close(out_fd);
        }
    }

    free(copy_buf);
    return 0;
}

static int extract_tar_archive(const char *tar_path, const char *dest_dir) {
    if (!tar_path || !dest_dir) return -1;
    int fd = open(tar_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    int res = extract_tar_fd(fd, dest_dir);
    close(fd);
    return res;
}

#ifndef CORTEX_HOST_TEST
JNIEXPORT jint JNICALL
Java_org_cortex_terminal_pty_PtyNative_extractTar(
    JNIEnv *env,
    jclass clazz,
    jstring tarPathStr,
    jstring destDirStr
) {
    (void)clazz;
    const char *tarPath = (*env)->GetStringUTFChars(env, tarPathStr, NULL);
    const char *destDir = (*env)->GetStringUTFChars(env, destDirStr, NULL);

    int res = extract_tar_archive(tarPath, destDir);

    (*env)->ReleaseStringUTFChars(env, tarPathStr, tarPath);
    (*env)->ReleaseStringUTFChars(env, destDirStr, destDir);
    return res;
}

// Extracts the tar stream the app writes into a pipe (fd stays the caller's), so the archive need
// not be copied to a file first.
JNIEXPORT jint JNICALL
Java_org_cortex_terminal_pty_PtyNative_extractTarFd(
    JNIEnv *env,
    jclass clazz,
    jint fd,
    jstring destDirStr
) {
    (void)clazz;
    const char *destDir = (*env)->GetStringUTFChars(env, destDirStr, NULL);
    int res = extract_tar_fd(fd, destDir);
    (*env)->ReleaseStringUTFChars(env, destDirStr, destDir);
    return res;
}
#endif /* !CORTEX_HOST_TEST */
