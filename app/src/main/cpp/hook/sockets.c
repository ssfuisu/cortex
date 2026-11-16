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

// The first port of the kernel's range for ports it picks itself, from the text of
// /proc/sys/net/ipv4/ip_local_port_range ("32768\t60999"); 32768, Linux's default, when there is none.
static int ephemeral_port_low(const char *range) {
    if (!range) return 32768;
    while (*range == ' ' || *range == '\t') range++;
    int low = 0, digits = 0;
    while (*range >= '0' && *range <= '9' && digits < 6) low = low * 10 + (*range++ - '0'), digits++;
    return (digits > 0 && low > 0 && low <= 65535) ? low : 32768;
}

// Whether listen() tells the app about a server on `port`, which it then connects to and sends an
// HTTP request (LocalServers). Only for a port the program chose (`chosen`) below the kernel's own
// range: a server whose port the kernel picked, or one in that range, is how programs talk to
// each other (Gradle's daemon, the Kotlin daemon, a JVM's debugger, test runners). Such a server
// often accepts one connection, so the app's own took the place of the program it waited for:
// that one read EOF ("Did not receive connection preamble" from Gradle, #51). Web servers people
// open (npm run dev, vite, python3 -m http.server, jupyter) are on ports such as 3000, 5173, 8000.
static int listening_port_is_reported(int chosen, int port, int ephemeral_low) {
    return chosen && port > 0 && port < ephemeral_low;
}

#ifndef CORTEX_HOST_TEST

// The kernel's range, read once (raw system calls: nothing of this library runs again)
static int kernel_ephemeral_port_low(void) {
    static int low = 0;
    int v = __atomic_load_n(&low, __ATOMIC_RELAXED);
    if (v) return v;
    char text[64];
    ssize_t n = -1;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/sys/net/ipv4/ip_local_port_range", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        n = syscall(SYS_read, fd, text, sizeof(text) - 1);
        syscall(SYS_close, fd);
    }
    if (n > 0) text[n] = '\0';
    v = ephemeral_port_low(n > 0 ? text : NULL);
    __atomic_store_n(&low, v, __ATOMIC_RELAXED);
    return v;
}

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
        int port = is_chosen_port_fd(sockfd) ? listening_tcp_port(sockfd) : 0;
        if (listening_port_is_reported(port > 0, port, kernel_ephemeral_port_low())) {
            init_cortex_hook();
            extern char *program_invocation_short_name;
            report_listening_port(g_cortex_root, g_cortex_root_len, port, program_invocation_short_name);
        }
        errno = saved_errno;
    }
    return ret;
}

// Ports below 1024 are root's, and Android gives an app no way to them (EACCES). A server that asks
// for one gets the port 8000 above it instead (80 is 8080, 443 is 8443, 22 is 8022, as cortex-sshd
// already uses), quietly; a connection on this phone to a low port a server here moved goes to the
// moved one, so "curl http://localhost" reaches nginx.
#define CORTEX_LOW_PORT_OFFSET 8000

static int moved_low_port(int port) {
    return port > 0 && port < 1024 ? port + CORTEX_LOW_PORT_OFFSET : 0;
}

// The address with its port moved (0 when it is not a low IP port)
static socklen_t with_moved_port(const struct sockaddr *addr, socklen_t addrlen, struct sockaddr_storage *out, int *from, int *to) {
    if (!addr || addrlen > (socklen_t)sizeof(*out)) return 0;
    if (addr->sa_family == AF_INET && addrlen >= (socklen_t)sizeof(struct sockaddr_in)) {
        int port = ntohs(((const struct sockaddr_in *)addr)->sin_port);
        int moved = moved_low_port(port);
        if (!moved) return 0;
        memcpy(out, addr, addrlen);
        ((struct sockaddr_in *)out)->sin_port = htons((uint16_t)moved);
        *from = port;
        *to = moved;
        return addrlen;
    }
    if (addr->sa_family == AF_INET6 && addrlen >= (socklen_t)sizeof(struct sockaddr_in6)) {
        int port = ntohs(((const struct sockaddr_in6 *)addr)->sin6_port);
        int moved = moved_low_port(port);
        if (!moved) return 0;
        memcpy(out, addr, addrlen);
        ((struct sockaddr_in6 *)out)->sin6_port = htons((uint16_t)moved);
        *from = port;
        *to = moved;
        return addrlen;
    }
    return 0;
}

static int is_loopback_addr(const struct sockaddr *addr, socklen_t addrlen) {
    if (addr->sa_family == AF_INET && addrlen >= (socklen_t)sizeof(struct sockaddr_in))
        return (ntohl(((const struct sockaddr_in *)addr)->sin_addr.s_addr) >> 24) == 127;
    if (addr->sa_family == AF_INET6 && addrlen >= (socklen_t)sizeof(struct sockaddr_in6)) {
        const struct in6_addr *a = &((const struct sockaddr_in6 *)addr)->sin6_addr;
        if (IN6_IS_ADDR_LOOPBACK(a)) return 1;
        return IN6_IS_ADDR_V4MAPPED(a) && a->s6_addr[12] == 127;
    }
    return 0;
}

// $CORTEX_ROOT/run/cortex/moved-ports/PORT: a server of this phone listens on PORT + 8000
static int moved_port_path(int port, char *path, size_t size) {
    init_cortex_hook();
    if (g_cortex_root_len == 0) return 0;
    int n = snprintf(path, size, "%.*s/run/cortex/moved-ports/%d", (int)g_cortex_root_len, g_cortex_root, port);
    return n > 0 && (size_t)n < size;
}

static void remember_moved_port(int port) {
    char path[PATH_MAX];
    if (!moved_port_path(port, path, sizeof(path))) return;
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s", path);
    char *slash = strrchr(dir, '/');
    if (slash) {
        *slash = '\0';
        // run/cortex is the app's; a rootfs without it gets it here
        for (char *p = dir + g_cortex_root_len + 1; *p; p++) {
            if (*p != '/') continue;
            *p = '\0';
            syscall(SYS_mkdirat, AT_FDCWD, dir, 0755);
            *p = '/';
        }
        syscall(SYS_mkdirat, AT_FDCWD, dir, 0755);
    }
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
    if (fd >= 0) syscall(SYS_close, fd);
}

static int low_port_was_moved(int port) {
    char path[PATH_MAX];
    return moved_port_path(port, path, sizeof(path)) && syscall(SYS_faccessat, AT_FDCWD, path, F_OK, 0) == 0;
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
    int ret = orig_bind ? orig_bind(sockfd, addr, addrlen) : -1;
    if (ret != 0 && errno == EACCES && addr && orig_bind) {
        struct sockaddr_storage moved;
        int from = 0, to = 0;
        socklen_t moved_len = with_moved_port(addr, addrlen, &moved, &from, &to);
        if (moved_len && orig_bind(sockfd, (struct sockaddr *)&moved, moved_len) == 0) {
            remember_moved_port(from);
            // Nothing on the program's own output (it cluttered every server's log and terminal): the
            // app's notification for local servers names the port it listens on. CORTEX_PORT_NOTICE=1
            // prints it, for whoever wants to see the move.
            const char *notice = getenv("CORTEX_PORT_NOTICE");
            static int told = 0;
            if (notice && notice[0] == '1' && !__atomic_exchange_n(&told, 1, __ATOMIC_RELAXED)) {
                char msg[160];
                int n = snprintf(msg, sizeof(msg), "cortex: port %d is reserved on Android, listening on %d instead\n", from, to);
                if (n > 0) syscall(SYS_write, 2, msg, (size_t)n);
            }
            addr = (struct sockaddr *)&moved;
            addrlen = moved_len;
            ret = 0;
        } else {
            errno = EACCES;
        }
    }
    if (ret == 0 && addr) {
        int saved_errno = errno;
        int chosen = 0;
        if (addr->sa_family == AF_INET && addrlen >= (socklen_t)sizeof(struct sockaddr_in))
            chosen = ((const struct sockaddr_in *)addr)->sin_port != 0;
        else if (addr->sa_family == AF_INET6 && addrlen >= (socklen_t)sizeof(struct sockaddr_in6))
            chosen = ((const struct sockaddr_in6 *)addr)->sin6_port != 0;
        set_chosen_port_fd(sockfd, chosen);
        errno = saved_errno;
    }
    return ret;
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
    // A low port on this phone that a server here asked for: it is on the moved one (the server
    // left $CORTEX_ROOT/run/cortex/moved-ports/PORT). Nothing of Cortex can be on the low port
    // itself, so the connection goes straight to the moved one, blocking or not (curl).
    if (addr && orig_connect && is_loopback_addr(addr, addrlen)) {
        struct sockaddr_storage moved;
        int from = 0, to = 0;
        socklen_t moved_len = with_moved_port(addr, addrlen, &moved, &from, &to);
        if (moved_len && low_port_was_moved(from)) return orig_connect(sockfd, (struct sockaddr *)&moved, moved_len);
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

#if defined(__NR_getuid32)
#define CORTEX_NR_REAL_GETUID __NR_getuid32
#define CORTEX_NR_REAL_GETGID __NR_getgid32
#else
#define CORTEX_NR_REAL_GETUID __NR_getuid
#define CORTEX_NR_REAL_GETGID __NR_getgid
#endif

// Credentials sent over a Unix socket (SCM_CREDENTIALS) have to be the process's own: the kernel
// refuses others with EPERM, and programs fill them in with getuid(), which is root's 0 here.
// PulseAudio's clients send them on every connection, which then failed ("Connection terminated").
// Returns 1 with the message copied into mod (its control data into ctrl) and the ids made real.
static int real_scm_credentials(const struct msghdr *msg, struct msghdr *mod, char *ctrl, size_t ctrl_size) {
    if (!msg || !msg->msg_control || msg->msg_controllen < sizeof(struct cmsghdr) || msg->msg_controllen > ctrl_size) return 0;
    int found = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(msg); c; c = CMSG_NXTHDR((struct msghdr *)msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_CREDENTIALS) found = 1;
    }
    if (!found) return 0;
    memcpy(mod, msg, sizeof(*mod));
    memcpy(ctrl, msg->msg_control, msg->msg_controllen);
    mod->msg_control = ctrl;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(mod); c; c = CMSG_NXTHDR(mod, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_CREDENTIALS || c->cmsg_len < CMSG_LEN(sizeof(struct ucred))) continue;
        // a record that claims more than the buffer holds is the kernel's to refuse, not ours to read
        if (c->cmsg_len > mod->msg_controllen - (size_t)((char *)c - ctrl)) break;
        struct ucred cred;
        memcpy(&cred, CMSG_DATA(c), sizeof(cred));
        cred.pid = getpid();
        cred.uid = (uid_t)call_orig_syscall(CORTEX_NR_REAL_GETUID, 0, 0, 0, 0, 0, 0);
        cred.gid = (gid_t)call_orig_syscall(CORTEX_NR_REAL_GETGID, 0, 0, 0, 0, 0, 0);
        memcpy(CMSG_DATA(c), &cred, sizeof(cred));
    }
    return 1;
}

// On 32-bit ARM this library is built, as all of Ubuntu's armhf packages are, with a 64-bit time_t:
// the headers then give getsockopt(), recvmmsg() and fcntl() other names (__getsockopt64...), and
// the functions here are those. The one to pass a call on to is the one of the same name: the plain
// one takes the old layout of a timeout.
#if defined(__USE_TIME_BITS64) && !defined(__LP64__)
#define CORTEX_TIME64_NAME(name64, name) name64
#else
#define CORTEX_TIME64_NAME(name64, name) name
#endif

// The other end of a Unix socket as the kernel names it (SO_PEERCRED, a received SCM_CREDENTIALS)
// is the app's real user, while every program here sees itself as root (getuid() is 0). A server
// that compares the two refuses its own user: dbus-daemon closed every connection to the session
// bus, and XFCE stopped at "Unable to contact settings server: The connection is closed". The
// app's own ids are shown as the ids programs see for themselves; any other id is left as it is.
// The ids are the other process's own, as it sees them (ids.c): root's, or the user it runs as (psql
// started by "su postgres" is postgres to the server's peer authentication).
static void peer_cred_as_seen_here(struct ucred *cred) {
    int is_app_uid = cred->uid == (uid_t)call_orig_syscall(CORTEX_NR_REAL_GETUID, 0, 0, 0, 0, 0, 0);
    int is_app_gid = cred->gid == (gid_t)call_orig_syscall(CORTEX_NR_REAL_GETGID, 0, 0, 0, 0, 0, 0);
    if (!is_app_uid && !is_app_gid) return;
    cortex_ids_t peer;
    if (ids_of_pid(cred->pid, &peer) != 0) {
        // The process is gone: it is shown as this one sees itself, as before
        peer = *ids_now();
    }
    if (is_app_uid) cred->uid = peer.e;
    if (is_app_gid) cred->gid = peer.ge;
}

// The SCM_CREDENTIALS of a message that came in, shown the same way
static void received_creds_as_seen_here(struct msghdr *msg) {
    if (!msg || !msg->msg_control || msg->msg_controllen < sizeof(struct cmsghdr)) return;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(msg); c; c = CMSG_NXTHDR(msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_CREDENTIALS || c->cmsg_len < CMSG_LEN(sizeof(struct ucred))) continue;
        struct ucred cred;
        memcpy(&cred, CMSG_DATA(c), sizeof(cred));
        peer_cred_as_seen_here(&cred);
        memcpy(CMSG_DATA(c), &cred, sizeof(cred));
    }
}

ssize_t sendmsg(int sockfd, const struct msghdr *msg, int flags) {
    static ssize_t (*orig_sendmsg)(int, const struct msghdr *, int) = NULL;
    if (!orig_sendmsg) orig_sendmsg = (ssize_t (*)(int, const struct msghdr *, int))cortex_next((void *)sendmsg, "sendmsg");
    struct msghdr cred_msg;
    char cred_ctrl[512] __attribute__((aligned(8)));
    if (real_scm_credentials(msg, &cred_msg, cred_ctrl, sizeof(cred_ctrl))) msg = &cred_msg;

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
    if (!orig_recvmsg) orig_recvmsg = (ssize_t (*)(int, struct msghdr *, int))cortex_next((void *)recvmsg, "recvmsg");

    socklen_t cap = msg ? msg->msg_namelen : 0;
    ssize_t ret = orig_recvmsg ? orig_recvmsg(sockfd, msg, flags) : -1;
    if (ret > 0 && msg && msg->msg_name) {
        int saved_errno = errno;
        restore_dns_source(sockfd, (struct sockaddr *)msg->msg_name, cap, &msg->msg_namelen);
        errno = saved_errno;
    }
    if (ret >= 0) received_creds_as_seen_here(msg);
    return ret;
}

int recvmmsg(int sockfd, struct mmsghdr *msgvec, unsigned int vlen, int flags, struct timespec *timeout) {
    static int (*orig_recvmmsg)(int, struct mmsghdr *, unsigned int, int, struct timespec *) = NULL;
    if (!orig_recvmmsg) orig_recvmmsg = (int (*)(int, struct mmsghdr *, unsigned int, int, struct timespec *))dlsym(RTLD_NEXT, CORTEX_TIME64_NAME("__recvmmsg64", "recvmmsg"));
    if (!orig_recvmmsg) {
        errno = ENOSYS;
        return -1;
    }
    int ret = orig_recvmmsg(sockfd, msgvec, vlen, flags, timeout);
    for (int i = 0; i < ret && msgvec; i++) received_creds_as_seen_here(&msgvec[i].msg_hdr);
    return ret;
}

static void peer_cred_option_as_seen_here(int ret, int level, int optname, void *optval, socklen_t *optlen) {
    if (ret == 0 && level == SOL_SOCKET && optname == SO_PEERCRED && optval && optlen && *optlen >= sizeof(struct ucred)) {
        struct ucred cred;
        memcpy(&cred, optval, sizeof(cred));
        peer_cred_as_seen_here(&cred);
        memcpy(optval, &cred, sizeof(cred));
    }
}

int getsockopt(int sockfd, int level, int optname, void *optval, socklen_t *optlen) {
    static int (*orig_getsockopt)(int, int, int, void *, socklen_t *) = NULL;
    if (!orig_getsockopt) orig_getsockopt = (int (*)(int, int, int, void *, socklen_t *))dlsym(RTLD_NEXT, CORTEX_TIME64_NAME("__getsockopt64", "getsockopt"));
    if (!orig_getsockopt) orig_getsockopt = (int (*)(int, int, int, void *, socklen_t *))dlsym(RTLD_NEXT, "getsockopt");
    if (!orig_getsockopt) {
        errno = ENOSYS;
        return -1;
    }
    int ret = orig_getsockopt(sockfd, level, optname, optval, optlen);
    peer_cred_option_as_seen_here(ret, level, optname, optval, optlen);
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
    int fd = orig_socket ? orig_socket(domain, type, protocol) : -1;
    // A new socket has not chosen a port, whatever the descriptor's last one had (close_range() and
    // raw close system calls do not pass the hook's close())
    if (fd >= 0) set_chosen_port_fd(fd, 0);
    return fd;
}

#endif /* !CORTEX_HOST_TEST */
