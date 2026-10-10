#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <errno.h>

#define DEFAULT_PORT 4715
#define MAX_TOKEN_LEN 256
#define MAX_URL_LEN 2048

static int read_token(char *token, size_t max_len) {
    const char *env_token = getenv("CORTEX_URL_TOKEN");
    if (env_token && env_token[0] != '\0') {
        strncpy(token, env_token, max_len - 1);
        token[max_len - 1] = '\0';
        return 1;
    }

    const char *candidates[] = {
        "/etc/cortex_url_token",
        "/data/data/org.cortex.terminal/files/cortex_url_token",
        "/data/user/0/org.cortex.terminal/files/cortex_url_token",
        NULL
    };

    char dyn_candidate[512];
    const char *root = getenv("CORTEX_ROOT");
    if (root && root[0] != '\0') {
        snprintf(dyn_candidate, sizeof(dyn_candidate), "%s/etc/cortex_url_token", root);
        int fd = open(dyn_candidate, O_RDONLY);
        if (fd >= 0) {
            ssize_t n = read(fd, token, max_len - 1);
            close(fd);
            if (n > 0) {
                token[n] = '\0';
                char *sp = strpbrk(token, " \t\r\n");
                if (sp) *sp = '\0';
                if (token[0] != '\0') return 1;
            }
        }
    }

    for (int i = 0; candidates[i]; i++) {
        int fd = open(candidates[i], O_RDONLY);
        if (fd >= 0) {
            ssize_t n = read(fd, token, max_len - 1);
            close(fd);
            if (n > 0) {
                token[n] = '\0';
                char *sp = strpbrk(token, " \t\r\n");
                if (sp) *sp = '\0';
                if (token[0] != '\0') return 1;
            }
        }
    }
    token[0] = '\0';
    return 0;
}

static int open_via_server(const char *url, const char *token, int port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return -1;

    struct timeval tv;
    tv.tv_sec = 4;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");

    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(sock);
        return -1;
    }

    char req[MAX_URL_LEN + MAX_TOKEN_LEN + 32];
    if (token && token[0] != '\0') {
        snprintf(req, sizeof(req), "OPEN %s %s\n", token, url);
    } else {
        snprintf(req, sizeof(req), "OPEN %s\n", url);
    }

    size_t req_len = strlen(req);
    ssize_t sent = write(sock, req, req_len);
    if (sent < (ssize_t)req_len) {
        close(sock);
        return -1;
    }

    char resp[128] = {0};
    ssize_t r = read(sock, resp, sizeof(resp) - 1);
    close(sock);

    if (r > 0) {
        resp[r] = '\0';
        if (strncmp(resp, "OK", 2) == 0) {
            return 0;
        }
    }
    return -1;
}

static int open_via_am(const char *url) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        // Child: run in clean environment for Android host binaries
        unsetenv("LD_PRELOAD");
        unsetenv("LD_LIBRARY_PATH");
        unsetenv("GLIBC_TUNABLES");
        setenv("PATH", "/system/bin:/system/xbin:/vendor/bin", 1);
        char *argv[] = {
            "/system/bin/am",
            "start",
            "-a", "android.intent.action.VIEW",
            "-d", (char *)url,
            NULL
        };
        execv("/system/bin/am", argv);
        _exit(127);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    return (WIFEXITED(status) && WEXITSTATUS(status) == 0) ? 0 : -1;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "Usage: xdg-open <url>\n");
        return 1;
    }

    const char *url = NULL;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "http://", 7) == 0 ||
            strncmp(argv[i], "https://", 8) == 0 ||
            strncmp(argv[i], "ftp://", 6) == 0 ||
            strncmp(argv[i], "file://", 7) == 0) {
            url = argv[i];
            break;
        }
        if (argv[i][0] != '-' && !url) {
            url = argv[i];
        }
    }

    if (!url) {
        url = argv[1];
    }

    int port = DEFAULT_PORT;
    const char *p_env = getenv("CORTEX_URL_PORT");
    if (p_env && atoi(p_env) > 0) {
        port = atoi(p_env);
    }

    char token[MAX_TOKEN_LEN] = {0};
    read_token(token, sizeof(token));

    // 1. Try IPC socket to UrlOpenerServer with token
    if (open_via_server(url, token, port) == 0) {
        return 0;
    }

    // 2. If token was present, also try without token prefix
    if (token[0] != '\0') {
        if (open_via_server(url, NULL, port) == 0) {
            return 0;
        }
    }

    // 3. Fallback to Android am start
    if (open_via_am(url) == 0) {
        return 0;
    }

    fprintf(stderr, "xdg-open: failed to open '%s'\n", url);
    return 1;
}
