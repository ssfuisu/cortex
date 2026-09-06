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
#include <sys/wait.h>
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

        // Execute command
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
