// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Sockets: Unix socket paths in the rootfs, and the redirected DNS queries of loopback DNS.

#ifndef CORTEX_HOST_TEST

int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*orig_bind)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!orig_bind) orig_bind = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "bind");

    if (addr && addrlen >= sizeof(sa_family_t) && addr->sa_family == AF_UNIX) {
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

    if (addr && addrlen >= sizeof(sa_family_t) && addr->sa_family == AF_UNIX) {
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
