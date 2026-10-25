#!/bin/bash
# Small C programs run under qemu-user with the AArch64 hook loaded, for what a mistake in the hook
# did to real programs before: each check is the call that crashed or failed in one of them.
#
#     tests/scripts/test_hook_qemu.sh
#
# Needs aarch64-linux-gnu-gcc (with its libraries in /usr/aarch64-linux-gnu) and qemu-aarch64. Without
# them the test says so and passes, unless CORTEX_REQUIRE_QEMU_TESTS=1.
set -u
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
trap 'rm -rf "$work"' EXIT
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

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
