#define _GNU_SOURCE
#include <jni.h>
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
#include <limits.h>
#include <elf.h>
#include <dirent.h>
#include <android/log.h>

#define TAG "CortexPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
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
        int fd = open(cmd, O_RDONLY);
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
        secondary = armhf_cands;
    } else if (e_machine == 40) { // EM_ARM
        primary = armhf_cands;
        secondary = aarch64_cands;
    } else if (e_machine == 62) { // EM_X86_64
        primary = x86_64_cands;
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
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;

    unsigned char buf[sizeof(Elf64_Ehdr)];
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n < (ssize_t)sizeof(Elf32_Ehdr)) {
        close(fd);
        return 0;
    }

    if (buf[EI_MAG0] != ELFMAG0 || buf[EI_MAG1] != ELFMAG1 ||
        buf[EI_MAG2] != ELFMAG2 || buf[EI_MAG3] != ELFMAG3) {
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
        if (phentsize < sizeof(Elf64_Phdr)) {
            close(fd);
            return 0;
        }
    } else if (buf[EI_CLASS] == ELFCLASS32) {
        Elf32_Ehdr ehdr32;
        memcpy(&ehdr32, buf, sizeof(Elf32_Ehdr));
        phoff = ehdr32.e_phoff;
        phentsize = ehdr32.e_phentsize;
        phnum = ehdr32.e_phnum;
        if (phentsize < sizeof(Elf32_Phdr)) {
            close(fd);
            return 0;
        }
    } else {
        close(fd);
        return 0;
    }

    if (phoff == 0 || phentsize == 0 || phnum == 0) {
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

static void close_all_inherited_fds(void) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir != NULL) {
        int dfd = dirfd(dir);
        struct dirent *de;
        while ((de = readdir(dir)) != NULL) {
            if (de->d_name[0] == '.') continue;
            char *endptr = NULL;
            long fd = strtol(de->d_name, &endptr, 10);
            if (endptr && *endptr == '\0' && fd > STDERR_FILENO && fd != dfd) {
                close((int)fd);
            }
        }
        closedir(dir);
    } else {
        long max_fd = sysconf(_SC_OPEN_MAX);
        if (max_fd < 0 || max_fd > 65536) max_fd = 1024;
        for (int fd = 3; fd < (int)max_fd; fd++) {
            close(fd);
        }
    }
}

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

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Failed to fork: %s", strerror(errno));
        close(masterFd);
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

    if (pid == 0) {
        // Child process
        setsid();

        int slaveFd = open(slaveName, O_RDWR);
        if (slaveFd < 0) {
            _exit(1);
        }

        #ifdef TIOCSCTTY
        ioctl(slaveFd, TIOCSCTTY, 0);
        #endif

        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);

        if (slaveFd > STDERR_FILENO) {
            close(slaveFd);
        }
        close(masterFd);

        // Close all open file descriptors fd > 2 to prevent descriptor inheritance leaks
        close_all_inherited_fds();

        // Set foreground process group to child process
        pid_t pgrp = getpid();
        signal(SIGTTOU, SIG_IGN);
        signal(SIGTTIN, SIG_IGN);
        tcsetpgrp(STDIN_FILENO, pgrp);
        signal(SIGTTOU, SIG_DFL);
        signal(SIGTTIN, SIG_DFL);

        // Configure standard terminal attributes
        struct termios tios;
        memset(&tios, 0, sizeof(struct termios));
        if (tcgetattr(STDIN_FILENO, &tios) == 0) {
            tios.c_iflag |= (ICRNL | IXON);
            tios.c_oflag |= (OPOST | ONLCR);
            tios.c_cflag |= (CS8 | CREAD);
            tios.c_lflag |= (ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHOCTL | ECHOKE | IEXTEN);
            tcsetattr(STDIN_FILENO, TCSANOW, &tios);
        }

        // Reset signal mask so no inherited signals are blocked
        sigset_t empty_mask;
        sigemptyset(&empty_mask);
        sigprocmask(SIG_SETMASK, &empty_mask, NULL);

        // Reset signal handlers to default
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_DFL;
        sigaction(SIGCHLD, &sa, NULL);
        sigaction(SIGHUP, &sa, NULL);
        sigaction(SIGINT, &sa, NULL);
        sigaction(SIGQUIT, &sa, NULL);
        sigaction(SIGTERM, &sa, NULL);
        sigaction(SIGPIPE, &sa, NULL);
        sigaction(SIGTSTP, &sa, NULL);
        sigaction(SIGTTIN, &sa, NULL);
        sigaction(SIGTTOU, &sa, NULL);

        if (cwd && chdir(cwd) != 0) {
            // If cwd fails, fallback to root
            chdir("/");
        }

        // Route Debian glibc ELF binaries through ld.so if inside CORTEX_ROOT
        const char *cortex_root = NULL;
        for (int i = 0; i < envCount; i++) {
            if (strncmp(envp[i], "CORTEX_ROOT=", 12) == 0) {
                cortex_root = envp[i] + 12;
                break;
            }
        }

        int is_glibc_elf = 0;
        int is_dynamic = 0;
        if (cortex_root && strlen(cortex_root) > 0 && strncmp(cmd, cortex_root, strlen(cortex_root)) == 0) {
            int fd = open(cmd, O_RDONLY);
            if (fd >= 0) {
                char hdr[4];
                ssize_t n = read(fd, hdr, sizeof(hdr));
                close(fd);
                if (n >= 4 && (unsigned char)hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                    is_glibc_elf = 1;
                    is_dynamic = has_pt_interp(cmd);
                }
            }
        }

        if (is_glibc_elf && is_dynamic) {
            char ld_so[PATH_MAX] = {0};
            if (find_dynamic_linker(cortex_root, cmd, ld_so, sizeof(ld_so))) {
                chmod(ld_so, 0755);
                chmod(cmd, 0755);

                const char *prog_name = strrchr(cmd, '/');
                prog_name = (prog_name != NULL) ? prog_name + 1 : cmd;

                char **new_argv = calloc(argCount + 5, sizeof(char *));
                new_argv[0] = ld_so;
                new_argv[1] = "--argv0";
                new_argv[2] = (char *)prog_name;
                new_argv[3] = (char *)cmd;
                for (int i = 1; i <= argCount; i++) {
                    new_argv[i + 3] = argv[i];
                }
                new_argv[argCount + 4] = NULL;
                execve(ld_so, new_argv, envp);

                char err_buf[256];
                snprintf(err_buf, sizeof(err_buf), "Cortex: failed to exec ld.so (%s): %s\n", ld_so, strerror(errno));
                write(STDERR_FILENO, err_buf, strlen(err_buf));
            } else {
                char err_buf[512];
                snprintf(err_buf, sizeof(err_buf),
                    "Cortex: Dynamic linker (ld.so) not found in %s!\n"
                    "Ubuntu 24.04 environment may be corrupted or still initializing.\n"
                    "Falling back to Android system shell...\n\n", cortex_root);
                write(STDERR_FILENO, err_buf, strlen(err_buf));

                const char *fallback_sh = "/system/bin/sh";
                if (access(fallback_sh, X_OK) == 0) {
                    char **sys_env = clean_env_for_system(envp, envCount);
                    char *fb_argv[] = { (char *)fallback_sh, NULL };
                    execve(fallback_sh, fb_argv, sys_env);
                }
            }
        }

        // If command is outside cortex_root (e.g. host /system/bin/sh), clean env
        if (!cortex_root || strncmp(cmd, cortex_root, strlen(cortex_root)) != 0) {
            char **sys_env = clean_env_for_system(envp, envCount);
            execve(cmd, argv, sys_env);
        } else {
            execve(cmd, argv, envp);
        }

        // If execve fails, print diagnostic and exit
        char errMsg[256];
        snprintf(errMsg, sizeof(errMsg), "Cortex: failed to execute %s: %s\n", cmd, strerror(errno));
        write(STDERR_FILENO, errMsg, strlen(errMsg));
        _exit(127);
    }

    // Parent cleanup
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

static void mkdirs_for_path(const char *path) {
    char temp[PATH_MAX];
    strncpy(temp, path, sizeof(temp) - 1);
    temp[sizeof(temp) - 1] = '\0';
    for (char *p = temp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(temp, 0755);
            *p = '/';
        }
    }
}

static int is_tar_path_unsafe(const char *path) {
    if (!path || path[0] == '\0') return 0;
    // Reject leading '/'
    if (path[0] == '/') return 1;
    // Reject any occurrence of ".."
    if (strstr(path, "..") != NULL) return 1;
    return 0;
}

#define TAR_COPY_BUF_SIZE 65536

static int extract_tar_archive(const char *tar_path, const char *dest_dir) {
    int fd = open(tar_path, O_RDONLY);
    if (fd < 0) return -1;

    char *copy_buf = malloc(TAR_COPY_BUF_SIZE);
    if (!copy_buf) {
        close(fd);
        return -1;
    }

    char block[512];
    char long_name[PATH_MAX] = {0};
    char long_link[PATH_MAX] = {0};

    while (read(fd, block, 512) == 512) {
        int all_zero = 1;
        for (int i = 0; i < 512; i++) {
            if (block[i] != 0) { all_zero = 0; break; }
        }
        if (all_zero) break;

        char name[PATH_MAX] = {0};
        char linkname[PATH_MAX] = {0};

        if (long_name[0] != '\0') {
            strncpy(name, long_name, sizeof(name) - 1);
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
            long_link[0] = '\0';
        } else {
            snprintf(linkname, sizeof(linkname), "%.100s", block + 157);
        }

        unsigned long mode = strtoul(block + 100, NULL, 8);
        if (mode == 0) mode = 0755;
        unsigned long long size = strtoull(block + 124, NULL, 8);
        char typeflag = block[156];

        if (typeflag == 'L') {
            unsigned long long rem = size;
            size_t pos = 0;
            while (rem > 0) {
                char dblock[512];
                ssize_t n = read(fd, dblock, 512);
                if (n <= 0) break;
                size_t chunk = (rem < 512) ? rem : 512;
                if (pos + chunk < sizeof(long_name)) {
                    memcpy(long_name + pos, dblock, chunk);
                    pos += chunk;
                }
                rem -= (rem < 512) ? rem : 512;
            }
            long_name[pos] = '\0';
            continue;
        }

        if (typeflag == 'K') {
            unsigned long long rem = size;
            size_t pos = 0;
            while (rem > 0) {
                char dblock[512];
                ssize_t n = read(fd, dblock, 512);
                if (n <= 0) break;
                size_t chunk = (rem < 512) ? rem : 512;
                if (pos + chunk < sizeof(long_link)) {
                    memcpy(long_link + pos, dblock, chunk);
                    pos += chunk;
                }
                rem -= (rem < 512) ? rem : 512;
            }
            long_link[pos] = '\0';
            continue;
        }

        if (typeflag == 'x' || typeflag == 'g') {
            unsigned long long rem = size;
            while (rem > 0) {
                char dblock[512];
                ssize_t n = read(fd, dblock, 512);
                if (n <= 0) break;
                rem -= (rem < 512) ? rem : 512;
            }
            continue;
        }

        // Tar-Slip path traversal protection: reject paths/symlink targets containing .. or leading /
        if (is_tar_path_unsafe(name) ||
            ((typeflag == '1' || typeflag == '2') && is_tar_path_unsafe(linkname))) {
            LOGE("Tar-Slip security guard: rejecting unsafe entry: name='%s', link='%s'", name, linkname);
            unsigned long long rem = ((size + 511) / 512) * 512;
            while (rem > 0) {
                size_t to_read = (rem < TAR_COPY_BUF_SIZE) ? (size_t)rem : TAR_COPY_BUF_SIZE;
                ssize_t n = read(fd, copy_buf, to_read);
                if (n <= 0) break;
                rem -= (size_t)n;
            }
            continue;
        }

        const char *rel = name;
        while (*rel == '.' || *rel == '/') rel++;
        if (*rel == '\0') continue;

        char dest_path[PATH_MAX];
        snprintf(dest_path, sizeof(dest_path), "%s/%s", dest_dir, rel);

        mkdirs_for_path(dest_path);

        if (typeflag == '5' || (typeflag == '\0' && name[strlen(name) - 1] == '/')) {
            mkdir(dest_path, mode & 0777);
            chmod(dest_path, (mode & 0777) | 0700);
        } else if (typeflag == '2') {
            unlink(dest_path);
            rmdir(dest_path);
            symlink(linkname, dest_path);
        } else if (typeflag == '1') {
            char target_path[PATH_MAX];
            const char *lrel = linkname;
            while (*lrel == '.' || *lrel == '/') lrel++;
            snprintf(target_path, sizeof(target_path), "%s/%s", dest_dir, lrel);
            unlink(dest_path);
            rmdir(dest_path);
            if (link(target_path, dest_path) != 0) {
                symlink(target_path, dest_path);
            }
        } else {
            unlink(dest_path);
            rmdir(dest_path);
            int out_fd = open(dest_path, O_WRONLY | O_CREAT | O_TRUNC, (mode & 0777) | 0600);
            unsigned long long rem_data = size;
            unsigned long long total_to_read = ((size + 511) / 512) * 512;

            while (total_to_read > 0) {
                size_t to_read = (total_to_read < TAR_COPY_BUF_SIZE) ? (size_t)total_to_read : TAR_COPY_BUF_SIZE;
                ssize_t n = read(fd, copy_buf, to_read);
                if (n <= 0) break;
                total_to_read -= (size_t)n;

                size_t to_write = (rem_data < (unsigned long long)n) ? (size_t)rem_data : (size_t)n;
                if (out_fd >= 0 && to_write > 0) {
                    size_t written = 0;
                    while (written < to_write) {
                        ssize_t w = write(out_fd, copy_buf + written, to_write - written);
                        if (w <= 0) break;
                        written += (size_t)w;
                    }
                }
                rem_data -= to_write;
            }

            if (out_fd >= 0) {
                close(out_fd);
                chmod(dest_path, mode & 0777);
            }
        }
    }

    free(copy_buf);
    close(fd);
    return 0;
}

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
