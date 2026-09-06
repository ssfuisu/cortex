#define _GNU_SOURCE
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
#include <android/log.h>

#define TAG "CortexPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

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
    int masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    if (masterFd < 0) {
        LOGE("Failed to open ptmx: %s", strerror(errno));
        return NULL;
    }

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
        free(argv);
        free(envp);
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

        // Reset signal handlers
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_DFL;
        sigaction(SIGCHLD, &sa, NULL);
        sigaction(SIGHUP, &sa, NULL);
        sigaction(SIGINT, &sa, NULL);
        sigaction(SIGQUIT, &sa, NULL);
        sigaction(SIGTERM, &sa, NULL);

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

        if (cortex_root && strlen(cortex_root) > 0 && strncmp(cmd, cortex_root, strlen(cortex_root)) == 0) {
            char ld_so[PATH_MAX] = {0};
            #if defined(__aarch64__)
            snprintf(ld_so, sizeof(ld_so), "%s/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", cortex_root);
            if (access(ld_so, F_OK) != 0) {
                snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-aarch64.so.1", cortex_root);
            }
            if (access(ld_so, F_OK) != 0) {
                snprintf(ld_so, sizeof(ld_so), "%s/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", cortex_root);
            }
            #elif defined(__arm__)
            snprintf(ld_so, sizeof(ld_so), "%s/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3", cortex_root);
            if (access(ld_so, F_OK) != 0) {
                snprintf(ld_so, sizeof(ld_so), "%s/lib/ld-linux-armhf.so.3", cortex_root);
            }
            if (access(ld_so, F_OK) != 0) {
                snprintf(ld_so, sizeof(ld_so), "%s/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3", cortex_root);
            }
            #endif

            if (access(ld_so, F_OK) == 0) {
                chmod(ld_so, 0755);
                chmod(cmd, 0755);
                char **new_argv = malloc(sizeof(char *) * (argCount + 5));
                new_argv[0] = ld_so;
                new_argv[1] = "--argv0";
                new_argv[2] = argv[0];
                new_argv[3] = (char *)cmd;
                for (int i = 1; i <= argCount; i++) {
                    new_argv[i + 3] = argv[i];
                }
                execve(ld_so, new_argv, envp);
            }
        }

        // Execute command normally if not glibc or if ld.so not found
        execve(cmd, argv, envp);

        // If execve fails, print diagnostic and exit
        const char *errMsg = "Cortex: failed to execute process.\n";
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

static int extract_tar_archive(const char *tar_path, const char *dest_dir) {
    int fd = open(tar_path, O_RDONLY);
    if (fd < 0) return -1;

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
            symlink(linkname, dest_path);
        } else if (typeflag == '1') {
            char target_path[PATH_MAX];
            const char *lrel = linkname;
            while (*lrel == '.' || *lrel == '/') lrel++;
            snprintf(target_path, sizeof(target_path), "%s/%s", dest_dir, lrel);
            unlink(dest_path);
            link(target_path, dest_path);
        } else {
            unlink(dest_path);
            int out_fd = open(dest_path, O_WRONLY | O_CREAT | O_TRUNC, (mode & 0777) | 0600);
            unsigned long long rem = size;
            while (rem > 0) {
                char dblock[512];
                ssize_t n = read(fd, dblock, 512);
                if (n <= 0) break;
                size_t chunk = (rem < 512) ? rem : 512;
                if (out_fd >= 0) {
                    ssize_t written = write(out_fd, dblock, chunk);
                    (void)written;
                }
                rem -= (rem < 512) ? rem : 512;
            }
            if (out_fd >= 0) {
                close(out_fd);
                chmod(dest_path, mode & 0777);
            }
        }
    }

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
