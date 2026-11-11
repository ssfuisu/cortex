#!/bin/bash
# Small C programs run under qemu-user with the AArch64 hook loaded, for what a mistake in the hook
# did to real programs before: each check is the call that crashed or failed in one of them.
#
#     tests/scripts/test_hook_qemu.sh
#
# Needs aarch64-linux-gnu-gcc (with its libraries in /usr/aarch64-linux-gnu) and qemu-aarch64. Without
# them the test says so and passes, unless CORTEX_REQUIRE_QEMU_TESTS=1.
set -u
umask 022 # the modes the tests expect are those of the usual umask
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
skip() {
  echo "skip: $1"
  [ "${CORTEX_REQUIRE_QEMU_TESTS:-0}" = 1 ] && exit 1
  exit 0
}
for tool in aarch64-linux-gnu-gcc qemu-aarch64; do
  command -v "$tool" >/dev/null 2>&1 || skip "$tool is not installed"
done
cross=/usr/aarch64-linux-gnu/lib
[ -e "$cross/ld-linux-aarch64.so.1" ] || skip "the AArch64 C library is not in $cross"

work=$(mktemp -d "${TMPDIR:-/tmp}/cortex-hook-qemu.XXXXXX")
[ -n "${KEEP_WORK:-}" ] && echo "work: $work" || trap 'rm -rf "$work"' EXIT
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "  ok    $1"; }
bad() { failed=$((failed + 1)); echo "  FAIL  $1"; }
check() { # name, then a command that succeeds when the check does
  local name=$1
  shift
  if "$@"; then ok "$name"; else bad "$name"; fi
}

echo "== building"
# Without -fno-delete-null-pointer-checks: the hook is meant to be right without that flag too
[ -e "$here/app/src/main/cpp/gosys/gosys_blob.h" ] || "$here/scripts/build-gosys.sh" >/dev/null 2>&1
aarch64-linux-gnu-gcc -std=gnu11 -shared -fPIC -O3 -w -Wl,-z,max-page-size=16384 -o "$work/hook.so" \
  "$here/app/src/main/cpp/cortex-hook.c" -ldl || { echo "the hook did not build"; exit 1; }
mkdir -p "$work/root/tmp" "$work/root/etc" "$work/root/lib/aarch64-linux-gnu"
# The C library inside the rootfs too, for the programs that start others (the hook maps absolute paths into it)
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libgcc_s.so.1; do cp -L "$cross/$f" "$work/root/lib/aarch64-linux-gnu/" 2>/dev/null; done

# Runs a program with the hook preloaded, as ld.so does for Ubuntu's programs in Cortex
run() {
  (cd "$work" && env -i CORTEX_ROOT="$work/root" HOME="$work" timeout 60 qemu-aarch64 "$cross/ld-linux-aarch64.so.1" \
    --preload "$work/hook.so" --library-path "$cross" "$@" 2>&1 | grep -v "cannot be preloaded")
}
prog() { # name, source on stdin
  aarch64-linux-gnu-gcc -std=gnu11 -O2 -w -o "$work/$1" -x c - || { echo "$1 did not build"; exit 1; }
}

echo "== programs"
# Rust's standard library asks whether statx exists by calling it with a NULL path and a NULL buffer
# (EFAULT: it does). glibc's header marks both nonnull, so the compiler removed the hook's NULL
# check and every Rust program (ripgrep, rustc, cargo...) crashed at its first file access.
prog statx_probe <<'EOC'
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
int main(void) {
    errno = 0;
    int r = statx(0, NULL, 0, STATX_ALL, NULL);
    printf("statx(NULL, NULL) = %d, %s\n", r, errno == EFAULT ? "EFAULT" : errno == ENOSYS ? "ENOSYS" : "other");
    return 0;
}
EOC
out=$(run "$work/statx_probe")
check "statx with a NULL path and buffer answers EFAULT, as Rust's probe expects" test "$out" = "statx(NULL, NULL) = -1, EFAULT"
[ "$out" = "statx(NULL, NULL) = -1, EFAULT" ] || echo "        got: $out"

# Ubuntu does not use SELinux: libselinux found Android's and pam_rootok then refused root, so chfn
# and `adduser --gecos` (the installation of PostgreSQL) failed
prog selinux <<'EOC'
#include <stdio.h>
extern int is_selinux_enabled(void) __attribute__((weak));
int main(void) {
    printf("is_selinux_enabled=%d\n", is_selinux_enabled ? is_selinux_enabled() : -1);
    return 0;
}
EOC
out=$(run "$work/selinux")
check "SELinux is off for Ubuntu's programs" test "$out" = "is_selinux_enabled=0"
[ "$out" = "is_selinux_enabled=0" ] || echo "        got: $out"

# The shadow database comes from the rootfs: libc reads the machine's own /etc/shadow, which Android
# does not have, and pam_unix's account check for root failed with "Authentication failure"
printf 'cortexuser:$6$salt$hash:19000:1:99999:7:::\ncortexlock:!:19001::::::\n' > "$work/root/etc/shadow"
prog shadow <<'EOC'
#define _GNU_SOURCE
#include <errno.h>
#include <shadow.h>
#include <stdio.h>
int main(void) {
    struct spwd *e = getspnam("cortexuser");
    if (e) printf("getspnam: %s %s last=%ld min=%ld max=%ld warn=%ld inact=%ld\n", e->sp_namp, e->sp_pwdp, e->sp_lstchg, e->sp_min, e->sp_max, e->sp_warn, e->sp_inact);
    else printf("getspnam: none (%d)\n", errno);
    struct spwd buf, *res = NULL;
    char raw[512];
    int rc = getspnam_r("cortexlock", &buf, raw, sizeof(raw), &res);
    printf("getspnam_r: rc=%d %s %s last=%ld min=%ld\n", rc, res ? res->sp_namp : "none", res ? res->sp_pwdp : "-", res ? res->sp_lstchg : 0, res ? res->sp_min : 0);
    printf("unknown: %s\n", getspnam("nosuchuser-cortex") ? "found" : "none");
    return 0;
}
EOC
out=$(run "$work/shadow")
expected_shadow=$'getspnam: cortexuser $6$salt$hash last=19000 min=1 max=99999 warn=7 inact=-1\ngetspnam_r: rc=0 cortexlock ! last=19001 min=-1\nunknown: none'
check "getspnam reads the shadow file of the rootfs" test "$out" = "$expected_shadow"
[ "$out" = "$expected_shadow" ] || printf '        got:\n%s\n' "$out"

# The ARM translator's memory files (ndk_translation keeps one at descriptor 3) survive close(),
# closefrom() and close_range(): the hook finds them at its start. Here a program of the test makes a
# memory file named like the translator's, puts it at descriptor 3 and starts the next one.
prog kept_child <<'EOC'
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/syscall.h>
int main(void) {
    close(3);
    syscall(SYS_close_range, 3u, ~0u, 0);
    printf("descriptor 3 after close(): %s\n", fcntl(3, F_GETFD) >= 0 ? "kept" : "gone");
    return 0;
}
EOC
prog kept_parent <<'EOC'
#define _GNU_SOURCE
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>
int main(int argc, char **argv) {
    // This program started without a translator's file (the memory file is made below), so the hook
    // would tell the next one that there is none; as on a machine that has a translator, it is not
    putenv("CORTEX_NT=1");
    int fd = memfd_create("child", 0);
    if (fd != 3) {
        if (dup2(fd, 3) != 3) return 2;
        close(fd);
    }
    char *args[] = { argv[1], "--preload", argv[2], "--library-path", argv[3], argv[4], NULL };
    execv(argv[1], args);
    return 3;
}
EOC
out=$(run "$work/kept_parent" "$work/root/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" "$work/hook.so" "$work/root/lib/aarch64-linux-gnu" "$work/kept_child")
check "a descriptor that is the translator's memory file stays open" test "$out" = "descriptor 3 after close(): kept"
[ "$out" = "descriptor 3 after close(): kept" ] || echo "        got: $out"

# A program that found no translator's files tells the ones it starts, which then do not look (the
# look is a system call to /proc/self/fd in every program that is started)
prog nt_child <<'EOC'
#include <stdio.h>
#include <stdlib.h>
int main(void) {
    const char *v = getenv("CORTEX_NT");
    printf("CORTEX_NT=%s\n", v ? v : "unset");
    return 0;
}
EOC
prog nt_parent <<'EOC'
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc > 1) putenv(argv[1]);
    char *args[] = { "nt_child", NULL };
    execv("/bin/nt_child", args);
    return 3;
}
EOC
mkdir -p "$work/root/bin" && cp "$work/nt_child" "$work/root/bin/nt_child"
out=$(run "$work/nt_parent")
check "a program started by one that found no translator is told so" test "$out" = "CORTEX_NT=0"
[ "$out" = "CORTEX_NT=0" ] || echo "        got: $out"
out=$(run "$work/nt_parent" CORTEX_NT=1)
check "a value of its own is left as it is" test "$out" = "CORTEX_NT=1"
[ "$out" = "CORTEX_NT=1" ] || echo "        got: $out"

# The descriptors of an ordinary program are closed as asked
prog plain_child <<'EOC'
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
int main(void) {
    int fd = open("/dev/null", O_RDONLY);
    close(fd);
    printf("descriptor %d after close(): %s\n", fd, fcntl(fd, F_GETFD) >= 0 ? "open" : "closed");
    return 0;
}
EOC
out=$(run "$work/plain_child")
check "other descriptors are closed as asked" bash -c "[[ '$out' == *'after close(): closed' ]]"

# dpkg makes each new directory with mode 0 and sets its mode a moment later. The hook hides a failing
# chmod (the app owns few of the files it is asked to change), so when that one failed the directory
# stayed at 0 and nothing could be put in it: "Permission denied" in /usr/share/applications and the
# other directories an installation made (#44). The app is root for the program, and root can enter
# every directory, so a new directory always keeps its owner's rights.
prog mkdir_zero <<'EOC'
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
int main(int argc, char **argv) {
    struct stat st;
    char inside[256];
    if (mkdir(argv[1], 0) != 0) { perror("mkdir"); return 1; }
    stat(argv[1], &st);
    printf("mkdir %o", st.st_mode & 07777);
    if (mkdirat(AT_FDCWD, argv[2], 0) != 0) { perror("mkdirat"); return 1; }
    stat(argv[2], &st);
    printf(" mkdirat %o", st.st_mode & 07777);
    if (mkdir(argv[3], 0555) != 0) { perror("mkdir"); return 1; }
    stat(argv[3], &st);
    printf(" readonly %o", st.st_mode & 07777);
    snprintf(inside, sizeof(inside), "%s/inside", argv[1]);
    int fd = open(inside, O_CREAT | O_WRONLY, 0644);
    printf(" inside %s\n", fd >= 0 ? "made" : "denied");
    return 0;
}
EOC
mkdir -p "$work/mkdir-test"
out=$(cd "$work/mkdir-test" && run "$work/mkdir_zero" zero-dir zero-at ro-dir)
check "a directory made with mode 0 keeps its owner's rights, a read-only one stays readable and enterable" \
  test "$out" = "mkdir 700 mkdirat 700 readonly 755 inside made"
[ "$out" = "mkdir 700 mkdirat 700 readonly 755 inside made" ] || echo "        got: $out"

# Directories that an earlier version left at mode 0 (dpkg makes a directory with mode 0 and sets the
# mode after; see above), or that a package made read-only, refuse every name, though the app is root
# for the program and root would not be refused. When a call that makes or removes a name is refused in
# a directory of the app's own that has lost its owner's rights, the rights are given back and the call
# made once more (#44). Run as another user than root (root is never refused by the kernel).
# (as root, or with passwordless sudo, as on the runners of GitHub: becoming another user needs one of them)
priv=""
[ "$(id -u)" = 0 ] || { sudo -n true >/dev/null 2>&1 && priv="sudo -n"; }
if { [ "$(id -u)" = 0 ] || [ -n "$priv" ]; } && command -v setpriv >/dev/null 2>&1 && id nobody >/dev/null 2>&1; then
  prog repair <<'EOC'
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
int main(int argc, char **argv) {
    char p[512];
    snprintf(p, sizeof(p), "%s/file", argv[1]);
    int fd = open(p, O_CREAT | O_WRONLY, 0644);
    printf("open %s;", fd >= 0 ? "ok" : strerror(errno));
    if (fd >= 0) close(fd);
    snprintf(p, sizeof(p), "%s/sub", argv[2]);
    printf(" mkdir %s;", mkdir(p, 0755) == 0 ? "ok" : strerror(errno));
    snprintf(p, sizeof(p), "%s/link", argv[3]);
    printf(" symlink %s;", symlink("target", p) == 0 ? "ok" : strerror(errno));
    snprintf(p, sizeof(p), "%s/moved", argv[4]);
    printf(" rename %s;", rename(argv[5], p) == 0 ? "ok" : strerror(errno));
    snprintf(p, sizeof(p), "%s/f", argv[6]);
    printf(" unlink %s;", unlink(p) == 0 ? "ok" : strerror(errno));
    snprintf(p, sizeof(p), "%s/sub", argv[7]);
    printf(" rmdir %s\n", rmdir(p) == 0 ? "ok" : strerror(errno));
    return 0;
}
EOC
  chmod 755 "$work"
  chmod -R a+rX "$work/root" "$work/hook.so" "$work/repair" 2>/dev/null
  # In the rootfs's /tmp (the program names them /tmp/nb/..., as Ubuntu's programs do)
  nb="$work/root/tmp/nb"
  $priv install -d -o nobody -g nogroup -m 755 "$nb"
  as_nobody() { $priv setpriv --reuid=65534 --regid=65534 --clear-groups "$@"; }
  for d in d1 d2 d3 d4; do as_nobody mkdir -m 000 "$nb/$d"; done
  as_nobody touch "$nb/src4"
  as_nobody mkdir "$nb/d5" && as_nobody touch "$nb/d5/f" && as_nobody chmod 000 "$nb/d5"
  as_nobody mkdir "$nb/d6" && as_nobody mkdir "$nb/d6/sub" && as_nobody chmod 000 "$nb/d6"
  out=$(cd "$nb" && as_nobody env -i CORTEX_ROOT="$work/root" HOME=/tmp timeout 60 qemu-aarch64 "$cross/ld-linux-aarch64.so.1" \
    --preload "$work/hook.so" --library-path "$cross" "$work/repair" /tmp/nb/d1 /tmp/nb/d2 /tmp/nb/d3 /tmp/nb/d4 /tmp/nb/src4 /tmp/nb/d5 /tmp/nb/d6 2>&1 | grep -v "cannot be preloaded")
  expected="open ok; mkdir ok; symlink ok; rename ok; unlink ok; rmdir ok"
  check "a directory without its owner's rights is given them when a name is refused in it" test "$out" = "$expected"
  [ "$out" = "$expected" ] || echo "        got: $out"
  modes=$(for d in d1 d2 d3 d4 d5 d6; do stat -c %a "$nb/$d"; done | tr '\n' ' ')
  check "and it keeps no other change than the owner's rights (modes: $modes)" test "$modes" = "700 700 700 700 700 700 "
  $priv rm -rf "$nb" # owned by nobody: the cleanup at the end could not remove it
elif [ "${CORTEX_REQUIRE_QEMU_TESTS:-0}" = 1 ]; then
  bad "the repair of directories needs root or passwordless sudo, setpriv and the user nobody (required by CORTEX_REQUIRE_QEMU_TESTS=1)"
else
  echo "  skip  the repair of directories (needs root or passwordless sudo, setpriv and the user nobody)"
fi

# The app connects to each server the hook reports, to see whether it serves web pages. Gradle's
# daemon and other programs that talk to each other over TCP let the kernel pick their port and
# accept one connection, which the app's took: they read EOF (#51). Only a port the program chose,
# below the kernel's own range, is reported.
prog listeners <<'EOC'
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
static int serve(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sin.sin_port = htons(port);
    if (bind(fd, (struct sockaddr *)&sin, sizeof(sin)) != 0 || listen(fd, 4) != 0) return -1;
    socklen_t len = sizeof(sin);
    getsockname(fd, (struct sockaddr *)&sin, &len);
    return ntohs(sin.sin_port);
}
int main(int argc, char **argv) {
    int picked = serve(0);
    // A port of its own below the kernel's range: the first one that is free
    int chosen = -1;
    for (int p = atoi(argv[1]); chosen < 0 && p < atoi(argv[1]) + 200; p++) chosen = serve(p);
    int unbound = socket(AF_INET, SOCK_STREAM, 0);
    listen(unbound, 4); // the kernel binds it to a port of its own
    struct sockaddr_in sin;
    socklen_t len = sizeof(sin);
    getsockname(unbound, (struct sockaddr *)&sin, &len);
    printf("%d %d %d\n", picked, chosen, ntohs(sin.sin_port));
    return 0;
}
EOC
mkdir -p "$work/root/run/cortex/listening"
# Candidates must sit below the range the kernel picks ports from (what the hook reads, 32768 if unknown)
ephemeral_low=$(awk '{print $1}' /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null)
case "$ephemeral_low" in ''|*[!0-9]*) ephemeral_low=32768 ;; esac
[ "$ephemeral_low" -gt 1424 ] || ephemeral_low=32768
chosen_port=$((1024 + $$ % (ephemeral_low - 1224)))
read -r picked chosen unbound <<<"$(run "$work/listeners" "$chosen_port")"
reported=$(ls "$work/root/run/cortex/listening" | sort -n | tr '\n' ' ')
check "a server on a port it chose is reported to the app" test "$chosen" -gt 0 -a -e "$work/root/run/cortex/listening/$chosen"
check "servers on ports the kernel picked are not (reported: $reported)" test "$reported" = "$chosen "
[ "$reported" = "$chosen " ] || echo "        ports: picked $picked, chosen $chosen, unbound $unbound"

# A program that finds its libraries through its RUNPATH (Ubuntu's adb: /usr/lib/<arch>/android)
# or its RPATH. ld.so opens those directories itself, without the hook, so they have to reach it
# mapped into the rootfs; before, adb stopped with "libbase.so.0: cannot open shared object file".
for kind in runpath rpath; do
  case $kind in
    runpath) libdir=/usr/lib/aarch64-linux-gnu/android; dtags=--enable-new-dtags ;;
    rpath) libdir=/usr/lib/private-$kind; dtags=--disable-new-dtags ;;
  esac
  mkdir -p "$work/root$libdir" "$work/root/usr/bin"
  echo 'int priv_answer(void) { return 42; }' | aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,libpriv_$kind.so.0 \
    -o "$work/root$libdir/libpriv_$kind.so.0" -x c - || { echo "libpriv_$kind did not build"; exit 1; }
  printf '#include <stdio.h>\nint priv_answer(void);\nint main(void) { printf("answer %%d\\n", priv_answer()); return 0; }\n' |
    aarch64-linux-gnu-gcc -O2 -w -o "$work/root/usr/bin/use_$kind" -x c - -x none "$work/root$libdir/libpriv_$kind.so.0" \
      -Wl,$dtags,-rpath,$libdir || { echo "use_$kind did not build"; exit 1; }
done
prog start_private <<'EOC'
#include <spawn.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
int main(int argc, char **argv) {
    char *args[] = { argv[1], NULL };
    if (argc > 2) { // posix_spawn(), as Python's subprocess and many others start programs
        pid_t pid;
        if (posix_spawn(&pid, argv[1], NULL, NULL, args, environ) != 0) return 4;
        int status = 0;
        waitpid(pid, &status, 0);
        return WIFEXITED(status) ? WEXITSTATUS(status) : 5;
    }
    execv(argv[1], args);
    perror("execv");
    return 3;
}
EOC
for kind in runpath rpath; do
  out=$(run "$work/start_private" /usr/bin/use_$kind)
  check "a program finds its libraries through its ${kind^^} (execve)" test "$out" = "answer 42"
  [ "$out" = "answer 42" ] || echo "        got: $out"
done
out=$(run "$work/start_private" /usr/bin/use_runpath spawn)
check "a program finds its libraries through its RUNPATH (posix_spawn)" test "$out" = "answer 42"
[ "$out" = "answer 42" ] || echo "        got: $out"

# A library found only through /etc/ld.so.conf.d (ld.so's cache on Linux, which ld.so cannot use here)
mkdir -p "$work/root/opt/conflib" "$work/root/etc/ld.so.conf.d"
echo 'int conf_answer(void) { return 7; }' | aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,libconf.so.0 \
  -o "$work/root/opt/conflib/libconf.so.0" -x c - || { echo "libconf did not build"; exit 1; }
printf '#include <stdio.h>\nint conf_answer(void);\nint main(void) { printf("conf %%d\\n", conf_answer()); return 0; }\n' |
  aarch64-linux-gnu-gcc -O2 -w -o "$work/root/usr/bin/use_conf" -x c - -x none "$work/root/opt/conflib/libconf.so.0" ||
  { echo "use_conf did not build"; exit 1; }
out=$(run "$work/start_private" /usr/bin/use_conf)
check "a library outside the usual directories is not found without ld.so.conf" test "$out" != "conf 7"
echo 'include /etc/ld.so.conf.d/*.conf' >"$work/root/etc/ld.so.conf"
echo '/opt/conflib  # added by hand' >"$work/root/etc/ld.so.conf.d/zz-conflib.conf"
out=$(run "$work/start_private" /usr/bin/use_conf)
check "it is found once its directory is in /etc/ld.so.conf.d" test "$out" = "conf 7"
[ "$out" = "conf 7" ] || echo "        got: $out"

# ldd runs ld.so itself on the program: it gets the same --library-path
prog ldlist <<'EOC'
#include <stdio.h>
#include <unistd.h>
int main(int argc, char **argv) {
    char *args[] = { "ld-linux-aarch64.so.1", "--list", argv[1], NULL };
    execv("/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", args);
    perror("execv");
    return 3;
}
EOC
out=$(run "$work/ldlist" /usr/bin/use_runpath)
check "ld.so --list (ldd) finds a library through the program's RUNPATH" sh -c 'echo "$1" | grep -q "libpriv_runpath.so.0 => .*/android/libpriv_runpath.so.0" && ! echo "$1" | grep -q "not found"' sh "$out"
echo "$out" | grep -q "not found" && echo "        got: $out"
out=$(run "$work/ldlist" /usr/bin/use_conf)
check "and one through ld.so.conf" sh -c 'echo "$1" | grep -q "libconf.so.0 => .*/opt/conflib/libconf.so.0"' sh "$out"

# A --library-path of the caller's own stays as it is, and the program is still found in the rootfs
prog ldlist_own <<'EOC'
#include <stdio.h>
#include <unistd.h>
int main(int argc, char **argv) {
    char *args[] = { "ld-linux-aarch64.so.1", "--library-path", "/nonexistent", "--list", argv[1], NULL };
    execv("/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", args);
    perror("execv");
    return 3;
}
EOC
out=$(run "$work/ldlist_own" /usr/bin/use_runpath)
check "ld.so --library-path DIR PROGRAM opens the program in the rootfs" sh -c 'echo "$1" | grep -q "libpriv_runpath.so.0"' sh "$out"
echo "$out" | grep -q "libpriv_runpath.so.0" || echo "        got: $out"

# ldconfig does nothing, but marks ld.so.cache changed: an edit of a .conf file counts after it, also in
# a process that read the configuration before
prog run_ldconfig <<'EOC'
#include <stdio.h>
#include <unistd.h>
int main(void) {
    char *args[] = { "ldconfig", NULL };
    execv("/sbin/ldconfig", args);
    perror("execv");
    return 3;
}
EOC
rm -f "$work/root/etc/ld.so.cache"
run "$work/run_ldconfig" >/dev/null
check "ldconfig marks /etc/ld.so.cache changed" test -e "$work/root/etc/ld.so.cache"

# The RUNPATH of a library the program needs (fio: librbd.so.1 names /usr/lib/<arch>/ceph, where its
# libceph-common.so.2 is). The exec does not read the libraries: their RUNPATH directories are in a
# file of /etc/ld.so.conf.d, written when it is missing and when ldconfig runs.
rp_conf="$work/root/etc/ld.so.conf.d/zz-cortex-runpath.conf"
mkdir -p "$work/root/usr/lib/aarch64-linux-gnu/ceph" "$work/root/usr/lib/aarch64-linux-gnu/origin/sub"
build_chain() { # name, the directory of the inner library, the RUNPATH the outer one names it by
  echo "int $1_inner(void) { return 5; }" | aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,lib$1inner.so.2 \
    -o "$2/lib$1inner.so.2" -x c - || { echo "lib$1inner did not build"; exit 1; }
  printf 'int %s_inner(void);\nint %s_outer(void) { return %s_inner() + 1; }\n' "$1" "$1" "$1" |
    aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,lib$1outer.so.1 -o "$work/root/usr/lib/aarch64-linux-gnu/lib$1outer.so.1" \
      -x c - -x none "$2/lib$1inner.so.2" "-Wl,--enable-new-dtags,-rpath,$3" || { echo "lib$1outer did not build"; exit 1; }
  printf '#include <stdio.h>\nint %s_outer(void);\nint main(void) { printf("%s %%d\\n", %s_outer()); return 0; }\n' "$1" "$1" "$1" |
    aarch64-linux-gnu-gcc -O2 -w -o "$work/root/usr/bin/use_$1" -x c - -x none "$work/root/usr/lib/aarch64-linux-gnu/lib$1outer.so.1" \
      -Wl,--allow-shlib-undefined || { echo "use_$1 did not build"; exit 1; }
}
build_chain rbd "$work/root/usr/lib/aarch64-linux-gnu/ceph" /usr/lib/aarch64-linux-gnu/ceph
rm -f "$rp_conf"
out=$(run "$work/start_private" /usr/bin/use_rbd)
check "a program finds what one of its libraries has in that library's RUNPATH" test "$out" = "rbd 6"
[ "$out" = "rbd 6" ] || echo "        got: $out"
check "the directory is listed in /etc/ld.so.conf.d, and only what is not searched already" \
  sh -c 'grep -qx "/usr/lib/aarch64-linux-gnu/ceph" "$1" && ! grep -q "android\|conflib" "$1"' sh "$rp_conf"
# one installed later counts after ldconfig, as on Linux
build_chain late "$work/root/usr/lib/aarch64-linux-gnu/origin/sub" /usr/lib/aarch64-linux-gnu/origin/sub
before=$(run "$work/start_private" /usr/bin/use_late)
run "$work/run_ldconfig" >/dev/null
out=$(run "$work/start_private" /usr/bin/use_late)
check "ldconfig reads the libraries again: one installed since is found after it" \
  sh -c '[ "$1" != "late 6" ] && [ "$2" = "late 6" ] && grep -qx "/usr/lib/aarch64-linux-gnu/origin/sub" "$3" && grep -qx "/usr/lib/aarch64-linux-gnu/ceph" "$3"' \
  sh "$before" "$out" "$rp_conf"
[ "$out" = "late 6" ] || echo "        got: $out (before ldconfig: $before)"

# System V shared memory (X's MIT-SHM): Android's seccomp filter blocks it, the hook keeps the segments
# in files. One process makes a segment, another one (started anew, so nothing is inherited) attaches it.
mkdir -p "$work/root/usr/lib" && cp "$work/hook.so" "$work/root/usr/lib/libcortex-hook.so" # children get the hook too
prog shm_peer <<'EOC'
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/shm.h>
int main(int argc, char **argv) {
    int id = argc > 2 ? shmget((key_t)strtol(argv[2], NULL, 16), 0, 0) : atoi(argv[1]);
    if (id < 0) { perror("shmget"); return 2; }
    char *p = shmat(id, NULL, 0);
    if (p == (void *)-1) { perror("shmat"); return 3; }
    printf("peer read: %s\n", p);
    strcpy(p + 512, "pong");
    shmdt(p);
    return 0;
}
EOC
cp "$work/shm_peer" "$work/root/usr/bin/shm_peer"
prog shm_main <<'EOC'
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/wait.h>
static int run_peer(char *a1, char *a2) {
    pid_t pid = fork();
    if (pid == 0) {
        char *args[] = { "shm_peer", a1, a2, NULL };
        execv("/usr/bin/shm_peer", args);
        _exit(127);
    }
    int st = 0;
    waitpid(pid, &st, 0);
    return WIFEXITED(st) ? WEXITSTATUS(st) : 99;
}
int main(void) {
    int id = shmget(IPC_PRIVATE, 4096, IPC_CREAT | 0600);
    if (id < 0) { perror("shmget"); return 2; }
    char *p = shmat(id, NULL, 0);
    if (p == (void *)-1) { perror("shmat"); return 3; }
    strcpy(p, "ping");
    char seg[64];
    snprintf(seg, sizeof(seg), "/tmp/.cortex-sysv-shm/seg-%d", id);
    printf("backed by %s\n", access(seg, F_OK) == 0 ? "a file" : "the kernel");
    char ids[32];
    snprintf(ids, sizeof(ids), "%d", id);
    int rc = run_peer(ids, NULL);
    printf("peer exit %d, wrote back: %s\n", rc, p + 512);
    struct shmid_ds ds;
    int stat_rc = shmctl(id, IPC_STAT, &ds);
    printf("stat %d size %zu\n", stat_rc, stat_rc == 0 ? (size_t)ds.shm_segsz : (size_t)0);
    printf("rmid %d\n", shmctl(id, IPC_RMID, NULL));
    printf("still mapped: %s\n", p);
    /* GTK and cairo mark a segment removed right after asking X to attach it: it still attaches */
    char *again = shmat(id, NULL, 0);
    printf("attach after rmid: %s\n", again == (void *)-1 ? "refused" : again);
    if (again != (void *)-1) shmdt(again);
    struct shmid_ds rds;
    printf("removed mode: %s\n", shmctl(id, IPC_STAT, &rds) == 0 && (rds.shm_perm.mode & 01000) ? "SHM_DEST" : "live");
    shmdt(p);
    /* a key: the peer finds the segment by it */
    int kid = shmget(0x5ca1ab1e, 8192, IPC_CREAT | 0600);
    char *k = shmat(kid, NULL, 0);
    strcpy(k, "by key");
    printf("same key again: %s\n", shmget(0x5ca1ab1e, 0, 0) == kid ? "same id" : "other id");
    printf("exclusive: %s\n", shmget(0x5ca1ab1e, 8192, IPC_CREAT | IPC_EXCL | 0600) < 0 ? "refused" : "allowed");
    rc = run_peer("0", "5ca1ab1e");
    printf("key peer exit %d, wrote back: %s\n", rc, k + 512);
    struct shmid_ds kds;
    memset(&kds, 0, sizeof(kds));
    kds.shm_perm.mode = 0400;
    int set_rc = shmctl(kid, IPC_SET, &kds);
    int kstat = shmctl(kid, IPC_STAT, &kds);
    printf("ipc_set %d mode %o, null %s\n", set_rc, kstat == 0 ? kds.shm_perm.mode & 0777u : 0u,
           shmctl(kid, IPC_SET, NULL) < 0 ? "refused" : "allowed");
    shmctl(kid, IPC_RMID, NULL);
    printf("key after rmid: %s\n", shmget(0x5ca1ab1e, 0, 0) < 0 ? "gone" : "still there");
    /* once their time is over, the next segment made deletes the removed ones' files */
    setenv("CORTEX_SHM_RMID_GRACE", "0", 1);
    int last = shmget(IPC_PRIVATE, 4096, IPC_CREAT | 0600);
    shmctl(last, IPC_RMID, NULL);
    return 0;
}
EOC
out=$(run "$work/shm_main")
check "the segment is the hook's (a file), not the kernel's" sh -c 'echo "$1" | grep -q "backed by a file"' sh "$out"
check "a segment is shared with another process" sh -c 'echo "$1" | grep -q "peer read: ping" && echo "$1" | grep -q "peer exit 0, wrote back: pong"' sh "$out"
check "IPC_STAT tells its size" sh -c 'echo "$1" | grep -q "stat 0 size 4096"' sh "$out"
check "IPC_RMID keeps the mapping and still lets it be attached (X's MIT-SHM)" sh -c 'echo "$1" | grep -q "rmid 0" && echo "$1" | grep -q "still mapped: ping" && echo "$1" | grep -q "attach after rmid: ping" && echo "$1" | grep -q "removed mode: SHM_DEST"' sh "$out"
check "a key finds its segment, IPC_EXCL is refused" sh -c 'echo "$1" | grep -q "same key again: same id" && echo "$1" | grep -q "exclusive: refused" && echo "$1" | grep -q "key peer exit 0, wrote back: pong" && echo "$1" | grep -q "key after rmid: gone"' sh "$out"
echo "$out" | grep -q "key after rmid: gone" || echo "$out" | sed 's/^/        /'
check "IPC_SET keeps the new rights (read-only), a NULL buffer is refused" sh -c 'echo "$1" | grep -q "ipc_set 0 mode 444, null refused"' sh "$out"
check "the removed segments leave no files" sh -c '[ -z "$(ls -A "$1" 2>/dev/null)" ]' sh "$work/root/tmp/.cortex-sysv-shm"

# A library loaded with dlopen() that finds its own dependencies through its RUNPATH (PulseAudio's
# modules, many plugins): ld.so looked for them outside the rootfs
mkdir -p "$work/root/usr/lib/aarch64-linux-gnu/plugdeps" "$work/root/usr/lib/plugins"
echo 'int dep_value(void) { return 99; }' | aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,libplugdep.so.1 \
  -o "$work/root/usr/lib/aarch64-linux-gnu/plugdeps/libplugdep.so.1" -x c - || { echo "libplugdep did not build"; exit 1; }
printf 'int dep_value(void);\nint plugin_value(void) { return dep_value() + 1; }\n' |
  aarch64-linux-gnu-gcc -shared -fPIC -w -o "$work/root/usr/lib/plugins/plugin.so" -x c - -x none \
    "$work/root/usr/lib/aarch64-linux-gnu/plugdeps/libplugdep.so.1" \
    -Wl,--enable-new-dtags,-rpath,/usr/lib/aarch64-linux-gnu/plugdeps || { echo "plugin did not build"; exit 1; }
prog load_plugin <<'EOC'
#include <dlfcn.h>
#include <stdio.h>
int main(void) {
    void *h = dlopen("/usr/lib/plugins/plugin.so", RTLD_NOW);
    if (!h) { printf("dlopen failed: %s\n", dlerror()); return 1; }
    int (*f)(void) = (int (*)(void))dlsym(h, "plugin_value");
    printf("plugin %d\n", f ? f() : -1);
    return 0;
}
EOC
out=$(run "$work/load_plugin")
check "dlopen() of a library whose dependency is in its RUNPATH" test "$out" = "plugin 100"
[ "$out" = "plugin 100" ] || echo "        got: $out"

# The RUNPATH of a dependency: a plugin without one needs a library that ld.so finds, which needs
# another in its own RUNPATH (XFCE's PulseAudio plugin: libpulse.so.0, then libpulsecommon). And a
# library asked for by its name alone (dlopen("libpulse.so.0"), as Firefox and SDL do).
mkdir -p "$work/root/usr/lib/aarch64-linux-gnu/deepdeps" "$work/root/usr/lib/mid"
echo 'int deep_value(void) { return 40; }' | aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,libdeep.so.1 \
  -o "$work/root/usr/lib/aarch64-linux-gnu/deepdeps/libdeep.so.1" -x c - || { echo "libdeep did not build"; exit 1; }
printf 'int deep_value(void);\nint mid_value(void) { return deep_value() + 1; }\n' |
  aarch64-linux-gnu-gcc -shared -fPIC -w -Wl,-soname,libmid.so.1 -o "$work/root/usr/lib/mid/libmid.so.1" -x c - -x none \
    "$work/root/usr/lib/aarch64-linux-gnu/deepdeps/libdeep.so.1" \
    -Wl,--enable-new-dtags,-rpath,/usr/lib/aarch64-linux-gnu/deepdeps || { echo "libmid did not build"; exit 1; }
printf 'int mid_value(void);\nint plugin_value(void) { return mid_value() + 1; }\n' |
  aarch64-linux-gnu-gcc -shared -fPIC -w -o "$work/root/usr/lib/plugins/plugin2.so" -x c - -x none \
    "$work/root/usr/lib/mid/libmid.so.1" || { echo "plugin2 did not build"; exit 1; }
prog load_plugin2 <<'EOC'
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
int main(int argc, char **argv) {
    void *h = dlopen(argv[1], RTLD_NOW);
    if (!h) {
        const char *e = dlerror();
        printf("dlopen failed: %s\n", e && strstr(e, "cannot open shared object file") ? "cannot open shared object file" : (e ? e : "no message"));
        return 1;
    }
    int (*f)(void) = (int (*)(void))dlsym(h, argv[2]);
    printf("%s %d\n", argv[2], f ? f() : -1);
    return 0;
}
EOC
run_mid() { # the directory to start in, then the library and the function
  local dir=$1
  shift
  (cd "$dir" && env -i CORTEX_ROOT="$work/root" HOME="$work" timeout 60 qemu-aarch64 "$cross/ld-linux-aarch64.so.1" \
    --preload "$work/hook.so" --library-path "$cross:$work/root/usr/lib/mid" "$work/load_plugin2" "$@" 2>&1 | grep -v "cannot be preloaded")
}
out="$(run_mid "$work" /usr/lib/plugins/plugin2.so plugin_value); $(run_mid "$work" libmid.so.1 mid_value)"
out="$out; $(run_mid "$work/root/usr/lib" ./plugins/plugin2.so plugin_value); $(run_mid "$work" libnothere.so.9 x)"
want="plugin_value 42; mid_value 41; plugin_value 42; dlopen failed: cannot open shared object file"
check "dlopen() finds what a dependency has in its own RUNPATH: by path, by name, by a relative path" test "$out" = "$want"
[ "$out" = "$want" ] || echo "        got: $out"

# Credentials over a Unix socket (SCM_CREDENTIALS) go out as the process's real ones: getuid() is
# root's 0 here, and the kernel refuses ids that are not the sender's (PulseAudio's clients failed).
# What comes in (SCM_CREDENTIALS, SO_PEERCRED) is shown as the ids a program sees for itself: a
# server that compares its peer with getuid() refused its own user (dbus-daemon, so XFCE's settings)
prog send_creds <<'EOC'
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/syscall.h>
int main(void) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return 2;
    int on = 1;
    setsockopt(sv[1], SOL_SOCKET, SO_PASSCRED, &on, sizeof(on));
    struct ucred peer = { 0, 12345, 12345 };
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(sv[0], SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) == 0)
        printf("peer ids %u %u, mine %u %u\n", (unsigned)peer.uid, (unsigned)peer.gid, (unsigned)getuid(), (unsigned)getgid());
    struct ucred cred = { getpid(), getuid(), getgid() };
    char ctrl[CMSG_SPACE(sizeof(cred))];
    char byte = 'x';
    struct iovec iov = { &byte, 1 };
    struct msghdr msg = { 0 };
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl;
    msg.msg_controllen = sizeof(ctrl);
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET;
    c->cmsg_type = SCM_CREDENTIALS;
    c->cmsg_len = CMSG_LEN(sizeof(cred));
    memcpy(CMSG_DATA(c), &cred, sizeof(cred));
    ssize_t n = sendmsg(sv[0], &msg, 0);
    printf("sendmsg %s\n", n == 1 ? "sent" : "refused");
    char rctrl[CMSG_SPACE(sizeof(cred))];
    struct msghdr rmsg = { 0 };
    char rbyte;
    struct iovec riov = { &rbyte, 1 };
    rmsg.msg_iov = &riov;
    rmsg.msg_iovlen = 1;
    rmsg.msg_control = rctrl;
    rmsg.msg_controllen = sizeof(rctrl);
    if (recvmsg(sv[1], &rmsg, 0) == 1) {
        struct cmsghdr *rc = CMSG_FIRSTHDR(&rmsg);
        struct ucred got;
        if (rc && rc->cmsg_type == SCM_CREDENTIALS) {
            memcpy(&got, CMSG_DATA(rc), sizeof(got));
            printf("received pid %s\n", got.pid == getpid() ? "mine" : "other");
            printf("received ids %u %u\n", (unsigned)got.uid, (unsigned)got.gid);
        }
    }
    return 0;
}
EOC
out=$(run "$work/send_creds")
# The kernel takes only the sender's real ids (this shell's: the hook emulates root only inside), so
# a message that went out had them; as root they are 0 as well and the rewrite cannot be told apart,
# CI runs the tests as a user
check "SCM_CREDENTIALS are sent with the real ids" sh -c 'echo "$1" | grep -q "sendmsg sent" && echo "$1" | grep -q "received pid mine"' sh "$out"
check "the peer of a Unix socket has the ids the program sees for itself" sh -c 'echo "$1" | grep -q "peer ids 0 0, mine 0 0" && echo "$1" | grep -q "received ids 0 0"' sh "$out"
echo "$out" | grep -q "received ids 0 0" || echo "$out" | sed 's/^/        /'

# A library loaded with RTLD_DEEPBIND (every PHP extension) looks for libc's functions in libc
# before the hook: its calls were not seen, so it opened Android's files instead of Ubuntu's
echo marker > "$work/root/etc/deepbind-marker"
printf '#include <unistd.h>\nint plugin_sees(void) { return access("/etc/deepbind-marker", F_OK) == 0; }\n' |
  aarch64-linux-gnu-gcc -shared -fPIC -w -o "$work/root/usr/lib/plugins/deepbind.so" -x c - || { echo "deepbind.so did not build"; exit 1; }
prog load_deepbind <<'EOC'
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
int main(void) {
    void *h = dlopen("/usr/lib/plugins/deepbind.so", RTLD_NOW | RTLD_DEEPBIND);
    if (!h) { printf("dlopen failed: %s\n", dlerror()); return 1; }
    int (*f)(void) = (int (*)(void))dlsym(h, "plugin_sees");
    printf("deepbind plugin %s\n", f && f() ? "sees Ubuntu's file" : "does not see Ubuntu's file");
    return 0;
}
EOC
out=$(run "$work/load_deepbind")
check "a library loaded with RTLD_DEEPBIND still goes through the hook (PHP's extensions)" test "$out" = "deepbind plugin sees Ubuntu's file"
[ "$out" = "deepbind plugin sees Ubuntu's file" ] || echo "        got: $out"

# Locks on a memfd: Android's SELinux policy refuses them (EACCES), and PHP keeps its opcache lock in
# one ("php -S": Cannot create lock - Permission denied). CORTEX_MEMFD_LOCKS=deny makes the hook act
# as on Android; the lock is then taken on a file of its own, which a forked child shares.
prog memfd_lock <<'EOC'
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>
static int try_lock(int fd) { // 1: taken, 0: someone else holds it
    struct flock try = { .l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = 0, .l_len = 1 };
    return fcntl(fd, F_SETLK, &try) == 0;
}
int main(void) {
    int fd = memfd_create("opcache_lock", MFD_CLOEXEC);
    if (fd < 0) { perror("memfd_create"); return 2; }
    struct flock lock = { .l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = 0, .l_len = 1 };
    int rc = fcntl(fd, F_SETLKW, &lock);
    printf("lock %d %s\n", rc, rc ? strerror(errno) : "taken");
    fflush(stdout);
    pid_t pid = fork();
    if (pid == 0) {
        int held = !try_lock(fd);
        struct flock who = { .l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = 0, .l_len = 1 };
        int asked = fcntl(fd, F_GETLK, &who);
        printf("child: %s, holder %s\n", held ? "refused" : "got it too", asked == 0 && who.l_type == F_WRLCK && who.l_pid == getppid() ? "is the parent" : "unknown");
        fflush(stdout);
        _exit(0);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    lock.l_type = F_UNLCK;
    rc = fcntl(fd, F_SETLK, &lock);
    printf("unlock %d\n", rc);

    // forked before either of them locked it: both still meet on the same lock
    int early = memfd_create("early", MFD_CLOEXEC);
    int to_child[2], to_parent[2];
    if (pipe(to_child) != 0 || pipe(to_parent) != 0) return 3;
    fflush(stdout);
    pid = fork();
    if (pid == 0) {
        char c;
        int mine = try_lock(early);
        if (write(to_parent[1], "x", 1) != 1 || read(to_child[0], &c, 1) != 1) _exit(1);
        _exit(mine ? 0 : 7);
    }
    char c;
    if (read(to_parent[0], &c, 1) != 1) return 4;
    int parent_got = try_lock(early); // the child holds it now
    // through a second descriptor of the memfd it is the same lock
    int alias = dup(early);
    int alias_got = try_lock(alias);
    if (write(to_child[1], "x", 1) != 1) return 5;
    waitpid(pid, &status, 0);
    printf("early fork: child %s, parent %s, alias %s\n", WIFEXITED(status) && WEXITSTATUS(status) == 0 ? "locked" : "failed",
           parent_got ? "got it too" : "refused", alias_got ? "got it too" : "refused");

    // closing the memfd gives its locks up, as closing a file does
    int closing = memfd_create("closing", MFD_CLOEXEC);
    int copy = dup(closing);
    rc = try_lock(closing);
    close(copy);
    fflush(stdout);
    pid = fork();
    if (pid == 0) _exit(try_lock(closing) ? 0 : 7);
    waitpid(pid, &status, 0);
    printf("after close: locked %d, child %s\n", rc, WIFEXITED(status) && WEXITSTATUS(status) == 0 ? "locked" : "refused");

    int other = memfd_create("second", MFD_CLOEXEC);
    rc = flock(other, LOCK_EX | LOCK_NB);
    int rc2 = flock(other, LOCK_UN);
    printf("flock %d %d\n", rc, rc2);
    // a file that is not a memfd is not touched, and a refusal of another kind stays one
    rc = fcntl(-1, F_SETLK, &lock);
    int bad = errno;
    printf("bad fd %d %s\n", rc, bad == EBADF ? "EBADF" : "other");
    return 0;
}
EOC
mkdir -p "$work/root/tmp"
out=$( (cd "$work" && env -i CORTEX_ROOT="$work/root" HOME="$work" CORTEX_MEMFD_LOCKS=deny timeout 60 qemu-aarch64 "$cross/ld-linux-aarch64.so.1" \
  --preload "$work/hook.so" --library-path "$cross" "$work/memfd_lock" 2>&1 | grep -v "cannot be preloaded") )
want="lock 0 taken
child: refused, holder is the parent
unlock 0
early fork: child locked, parent refused, alias refused
after close: locked 1, child locked
flock 0 0
bad fd -1 EBADF"
check "a memfd can be locked where Android refuses it: children share the lock, closing gives it up" test "$out" = "$want"
[ "$out" = "$want" ] || echo "$out" | sed 's/^/        /'

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
