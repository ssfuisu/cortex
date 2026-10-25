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
mkdir -p "$work/root/tmp" "$work/root/etc"

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

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
