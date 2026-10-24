// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Sockets: Unix socket paths in the rootfs, the redirected DNS queries of loopback DNS, and the
// TCP servers programs start (the app offers to open them in the browser).

// The TCP port a listening socket was bound to, 0 for other sockets.
static int listening_tcp_port(int fd) {
    struct sockaddr_storage ss;
    socklen_t len = sizeof(ss);
    memset(&ss, 0, sizeof(ss));
    if (syscall(SYS_getsockname, fd, (struct sockaddr *)&ss, &len) != 0) return 0;
    if (ss.ss_family == AF_INET && len >= (socklen_t)sizeof(struct sockaddr_in))
        return ntohs(((struct sockaddr_in *)&ss)->sin_port);
    if (ss.ss_family == AF_INET6 && len >= (socklen_t)sizeof(struct sockaddr_in6))
        return ntohs(((struct sockaddr_in6 *)&ss)->sin6_port);
    return 0;
}

// Tells the app that this program listens on [port]: $CORTEX_ROOT/run/cortex/listening/PORT with
// "PID NAME" (ListeningPorts). Raw system calls: nothing of this library runs again.
static void report_listening_port(const char *root, size_t root_len, int port, const char *name) {
    if (root_len == 0 || port <= 0 || port > 65535) return;
    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), "%.*s/run/cortex/listening/%d", (int)root_len, root, port);
    if (n <= 0 || n >= (int)sizeof(path)) return;
    int out = (int)syscall(SYS_openat, AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (out < 0) return; // no app watching (the directory is the app's)
    char line[160];
    int m = snprintf(line, sizeof(line), "%d %.100s\n", (int)getpid(), name && name[0] ? name : "?");
    if (m > 0) syscall(SYS_write, out, line, (size_t)(m < (int)sizeof(line) ? m : (int)sizeof(line) - 1));
    syscall(SYS_close, out);
}

#ifndef CORTEX_HOST_TEST

int listen(int sockfd, int backlog) {
    static int (*orig_listen)(int, int) = NULL;
    if (!orig_listen) orig_listen = (int (*)(int, int))dlsym(RTLD_NEXT, "listen");
    if (!orig_listen) {
        errno = ENOSYS;
        return -1;
    }
    int ret = orig_listen(sockfd, backlog);
    if (ret == 0) {
        int saved_errno = errno;
        int port = listening_tcp_port(sockfd);
        if (port > 0) {
            init_cortex_hook();
            extern char *program_invocation_short_name;
            report_listening_port(g_cortex_root, g_cortex_root_len, port, program_invocation_short_name);
        }
        errno = saved_errno;
    }
    return ret;
}

int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*orig_bind)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_bind) orig_bind = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "bind");

    if (addr && addrlen > offsetof(struct sockaddr_un, sun_path) && addr->sa_family == AF_UNIX) {
        const struct sockaddr_un *sun = (const struct sockaddr_un *)addr;
        if (sun->sun_path[0] != '\0') {
            struct sockaddr_un mod_sun;
            memset(&mod_sun, 0, sizeof(mod_sun));
            mod_sun.sun_family = AF_UNIX;
            rewrite_unix_socket_path(sun->sun_path, mod_sun.sun_path, sizeof(mod_sun.sun_path));
            return orig_bind ? orig_bind(sockfd, (struct sockaddr *)&mod_sun, sizeof(mod_sun)) : -1;
        }
    }
    return orig_bind ? orig_bind(sockfd, addr, addrlen) : -1;
}

int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*orig_connect)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_connect) orig_connect = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "connect");

    if (addr && addrlen > offsetof(struct sockaddr_un, sun_path) && addr->sa_family == AF_UNIX) {
        const struct sockaddr_un *sun = (const struct sockaddr_un *)addr;
        if (sun->sun_path[0] != '\0') {
            struct sockaddr_un mod_sun;
            memset(&mod_sun, 0, sizeof(mod_sun));
            mod_sun.sun_family = AF_UNIX;
            rewrite_unix_socket_path(sun->sun_path, mod_sun.sun_path, sizeof(mod_sun.sun_path));
            return orig_connect ? orig_connect(sockfd, (struct sockaddr *)&mod_sun, sizeof(mod_sun)) : -1;
        }
    }

    if (addr && is_loopback_dns(addr, addrlen)) {
        struct sockaddr_storage redirected;
        socklen_t redirected_len = dns_redirect_target(addr, &redirected);
        int ret = orig_connect ? orig_connect(sockfd, (struct sockaddr *)&redirected, redirected_len) : -1;
        if (ret == 0 || errno == EINPROGRESS) {
            int saved_errno = errno;
            record_dns_redirect(sockfd, addr, addrlen);
            errno = saved_errno;
        }
        return ret;
    }
    return orig_connect ? orig_connect(sockfd, addr, addrlen) : -1;
}

ssize_t sendto(int sockfd, const void *buf, size_t len, int flags,
               const struct sockaddr *dest_addr, socklen_t addrlen) {
    static ssize_t (*orig_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_sendto) orig_sendto = (ssize_t (*)(int, const void *, size_t, int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "sendto");

    if (dest_addr && is_loopback_dns(dest_addr, addrlen)) {
        struct sockaddr_storage redirected;
        socklen_t redirected_len = dns_redirect_target(dest_addr, &redirected);
        record_dns_redirect(sockfd, dest_addr, addrlen);
        return orig_sendto ? orig_sendto(sockfd, buf, len, flags, (struct sockaddr *)&redirected, redirected_len) : -1;
    }
    return orig_sendto ? orig_sendto(sockfd, buf, len, flags, dest_addr, addrlen) : -1;
}

ssize_t sendmsg(int sockfd, const struct msghdr *msg, int flags) {
    static ssize_t (*orig_sendmsg)(int, const struct msghdr *, int) = NULL;
    if (!orig_sendmsg) orig_sendmsg = (ssize_t (*)(int, const struct msghdr *, int))dlsym(RTLD_NEXT, "sendmsg");

    if (msg && msg->msg_name && is_loopback_dns((const struct sockaddr *)msg->msg_name, msg->msg_namelen)) {
        struct sockaddr_storage redirected;
        socklen_t redirected_len = dns_redirect_target((const struct sockaddr *)msg->msg_name, &redirected);
        struct msghdr mod_msg;
        memcpy(&mod_msg, msg, sizeof(mod_msg));
        mod_msg.msg_name = &redirected;
        mod_msg.msg_namelen = redirected_len;
        record_dns_redirect(sockfd, (const struct sockaddr *)msg->msg_name, msg->msg_namelen);
        return orig_sendmsg ? orig_sendmsg(sockfd, &mod_msg, flags) : -1;
    }
    return orig_sendmsg ? orig_sendmsg(sockfd, msg, flags) : -1;
}

ssize_t recvfrom(int sockfd, void *buf, size_t len, int flags,
                 struct sockaddr *src_addr, socklen_t *addrlen) {
    static ssize_t (*orig_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *) = NULL;
    if (!orig_recvfrom) orig_recvfrom = (ssize_t (*)(int, void *, size_t, int, struct sockaddr *, socklen_t *))dlsym(RTLD_NEXT, "recvfrom");

    socklen_t cap = addrlen ? *addrlen : 0;
    ssize_t ret = orig_recvfrom ? orig_recvfrom(sockfd, buf, len, flags, src_addr, addrlen) : -1;
    if (ret > 0 && src_addr && addrlen) {
        int saved_errno = errno;
        restore_dns_source(sockfd, src_addr, cap, addrlen);
        errno = saved_errno;
    }
    return ret;
}

// What programs built with _FORTIFY_SOURCE call when the buffer's size is known; the check is
// glibc's
extern void __chk_fail(void) __attribute__((__noreturn__));
ssize_t __recvfrom_chk(int sockfd, void *buf, size_t len, size_t buflen, int flags,
                       struct sockaddr *src_addr, socklen_t *addrlen) {
    if (len > buflen) __chk_fail();
    return recvfrom(sockfd, buf, len, flags, src_addr, addrlen);
}

ssize_t recvmsg(int sockfd, struct msghdr *msg, int flags) {
    static ssize_t (*orig_recvmsg)(int, struct msghdr *, int) = NULL;
    if (!orig_recvmsg) orig_recvmsg = (ssize_t (*)(int, struct msghdr *, int))dlsym(RTLD_NEXT, "recvmsg");

    socklen_t cap = msg ? msg->msg_namelen : 0;
    ssize_t ret = orig_recvmsg ? orig_recvmsg(sockfd, msg, flags) : -1;
    if (ret > 0 && msg && msg->msg_name) {
        int saved_errno = errno;
        restore_dns_source(sockfd, (struct sockaddr *)msg->msg_name, cap, &msg->msg_namelen);
        errno = saved_errno;
    }
    return ret;
}

int getpeername(int sockfd, struct sockaddr *addr, socklen_t *addrlen) {
    static int (*orig_getpeername)(int, struct sockaddr *, socklen_t *) = NULL;
    if (!orig_getpeername) orig_getpeername = (int (*)(int, struct sockaddr *, socklen_t *))dlsym(RTLD_NEXT, "getpeername");

    socklen_t cap = addrlen ? *addrlen : 0;
    int ret = orig_getpeername ? orig_getpeername(sockfd, addr, addrlen) : -1;
    if (ret == 0 && addr && addrlen) restore_dns_source(sockfd, addr, cap, addrlen);
    return ret;
}

// accept() as accept4() without flags, the call bionic makes for both: Android's seccomp filter
// for apps is made from what bionic calls. On the x86_64 emulator a translated accept() answered
// with its system call number (43) as the new descriptor, and sshd failed every connection with
// "fcntl(43, F_GETFL): Bad file descriptor" (Perl, Python and nginx use accept4 themselves).
int accept(int sockfd, struct sockaddr *addr, socklen_t *addrlen) {
    static int (*orig_accept4)(int, struct sockaddr *, socklen_t *, int) = NULL;
    if (!orig_accept4) orig_accept4 = (int (*)(int, struct sockaddr *, socklen_t *, int))dlsym(RTLD_NEXT, "accept4");
    if (!orig_accept4) {
        errno = ENOSYS;
        return -1;
    }
    return orig_accept4(sockfd, addr, addrlen, 0);
}

#ifndef AF_NETLINK
#define AF_NETLINK 16
#endif
#ifndef NETLINK_AUDIT
#define NETLINK_AUDIT 9
#endif

int socket(int domain, int type, int protocol) {
    static int (*orig_socket)(int, int, int) = NULL;
    if (!orig_socket) orig_socket = (int (*)(int, int, int))dlsym(RTLD_NEXT, "socket");
    if (domain == AF_NETLINK && protocol == NETLINK_AUDIT) {
        errno = EPROTONOSUPPORT;
        return -1;
    }
    return orig_socket ? orig_socket(domain, type, protocol) : -1;
}

#endif /* !CORTEX_HOST_TEST */
