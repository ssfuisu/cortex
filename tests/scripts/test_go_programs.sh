#!/usr/bin/env bash
# Tests the support for static Go programs: the code that is added to their file (app/src/main/cpp/
# gosys, hook/go_programs.c) so that they see Ubuntu's paths and can start Ubuntu's programs.
#
# It builds a static AArch64 Go program (tests/go/gotest), a small root file system (Ubuntu's own
# bash and libtinfo for arm64, the C library of the cross compiler, the hook and a C program) and
# runs the Go program in it with qemu-user, after the hook's own code has patched it, and compares
# what it sees with tests/go/expected.txt. Also checked: that the patch is made once, that it is
# replaced when the added code changes, that the Go versions of the supported range are patched
# and that programs which are not Go are left alone.
#
# Needs: go, gcc, aarch64-linux-gnu-gcc, qemu-aarch64 (qemu-user), curl or apt access to
# ports.ubuntu.com, and root (or sudo) to register the qemu handler with the kernel and to make
# /system/bin/true. Without them the test is skipped, unless CORTEX_REQUIRE_GO_TESTS=1.
#
#   CORTEX_GO_TEST_VERSIONS="1.19.13 1.23.12"   more Go versions to build the program with
#   CORTEX_GO_TEST_DEBS=dir                     where the arm64 packages are kept between runs
set -uo pipefail

here=$(cd "$(dirname "$0")/../.." && pwd)
require=${CORTEX_REQUIRE_GO_TESTS:-0}
SUDO=""
[ "$(id -u)" = 0 ] || SUDO="sudo"

skip() {
  echo "SKIPPED: $*"
  [ "$require" = 1 ] && { echo "(required by CORTEX_REQUIRE_GO_TESTS=1)"; exit 1; }
  exit 0
}

for tool in go gcc aarch64-linux-gnu-gcc qemu-aarch64 dpkg-deb apt-get readelf; do
  command -v "$tool" >/dev/null 2>&1 || skip "$tool is not installed"
done

# Programs started by programs under qemu (bash starting the C program) need the kernel to know
# that AArch64 files are run by qemu
ensure_binfmt() {
  [ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ] && return 0
  if [ ! -e /proc/sys/fs/binfmt_misc/register ]; then
    $SUDO mount -t binfmt_misc none /proc/sys/fs/binfmt_misc 2>/dev/null || return 1
  fi
  local q
  q=$(command -v qemu-aarch64)
  printf ':qemu-aarch64:M::\\x7fELF\\x02\\x01\\x01\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x02\\x00\\xb7\\x00:\\xff\\xff\\xff\\xff\\xff\\xff\\xff\\x00\\xff\\xff\\xff\\xff\\xff\\xff\\xff\\xff\\xfe\\xff\\xff\\xff:%s:\n' "$q" \
    | $SUDO tee /proc/sys/fs/binfmt_misc/register >/dev/null 2>&1
  [ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]
}
ensure_binfmt || skip "cannot register qemu-aarch64 with binfmt_misc (needs root)"

work=$(mktemp -d "${TMPDIR:-/tmp}/cortex-go-test.XXXXXX")
made_host_program=0
cleanup() {
  [ "$made_host_program" = 1 ] && $SUDO rm -f /system/bin/true && $SUDO rmdir /system/bin /system 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT

passed=0
failed=0
ok() { passed=$((passed + 1)); echo "  ok    $1"; }
bad() { failed=$((failed + 1)); echo "  FAIL  $1"; }
check() { # name, then a command that succeeds when the check does
  local name=$1
  shift
  if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi
}

echo "== building"
"$here/scripts/build-gosys.sh" || { echo "the added code did not build"; exit 1; }
hook=$work/libcortex-hook.so
aarch64-linux-gnu-gcc -std=gnu11 -shared -fPIC -O2 -fno-delete-null-pointer-checks -w -Wl,-z,max-page-size=16384 -o "$hook" "$here/app/src/main/cpp/cortex-hook.c" -ldl \
  || { echo "the hook did not build"; exit 1; }
gcc -std=gnu11 -O1 -w -o "$work/gopatch" "$here/tests/go/gopatch.c" -ldl -lpthread || { echo "gopatch did not build"; exit 1; }

# The same program built as an older Cortex would have: another code in the file, to be replaced
old=$work/oldtree
mkdir -p "$old/scripts" "$old/tests" "$old/app/src/main"
cp -r "$here/app/src/main/cpp" "$old/app/src/main/"
cp "$here/scripts/build-gosys.sh" "$old/scripts/"
cp -r "$here/tests/go" "$old/tests/"
sed -i 's/#define SLOT_PATH_CAP 4096u/#define SLOT_PATH_CAP 4000u/' "$old/app/src/main/cpp/gosys/gosys.c"
"$old/scripts/build-gosys.sh" >/dev/null && gcc -std=gnu11 -O1 -w -o "$work/gopatch-old" "$old/tests/go/gopatch.c" -ldl -lpthread \
  || { echo "the older code did not build"; exit 1; }

build_go() { # output, then the toolchain ("" for the installed one) and extra go build flags
  local out=$1 tc=$2
  shift 2
  (cd "$here/tests/go/gotest" && GOTOOLCHAIN=${tc:+go$tc} GOFLAGS=-mod=mod CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
    go build -trimpath "$@" -o "$out" .)
}
build_go "$work/gotest" "" -ldflags "-s -w" || { echo "the Go program did not build"; exit 1; }
build_go "$work/gotest-symbols" "" || { echo "the Go program did not build"; exit 1; }

echo "== the file system"
debs=${CORTEX_GO_TEST_DEBS:-$work/debs}
if ! ls "$debs"/bash_*_arm64.deb >/dev/null 2>&1; then
  mkdir -p "$debs/state/lists/partial" "$debs/cache/archives/partial"
  echo "deb [arch=arm64] http://ports.ubuntu.com/ubuntu-ports noble main" > "$debs/src.list"
  : > "$debs/status"
  apt_opts=(-o Dir::State="$debs/state" -o Dir::State::status="$debs/status" -o Dir::Cache="$debs/cache"
    -o Dir::Etc::SourceList="$debs/src.list" -o Dir::Etc::SourceParts=/dev/null -o APT::Architecture=arm64
    -o APT::Architectures=arm64 -o Debug::NoLocking=1 -o Acquire::Retries=3 -o APT::Sandbox::User=root)
  apt-get "${apt_opts[@]}" update -qq >/dev/null 2>&1 \
    && (cd "$debs" && apt-get "${apt_opts[@]}" download bash libtinfo6 >/dev/null 2>&1) \
    || skip "cannot download Ubuntu's arm64 packages"
fi
root=$work/rootfs
mkdir -p "$root/etc" "$root/tmp" "$root/home/user" "$root/usr/lib" "$root/lib/aarch64-linux-gnu"
for d in "$debs"/bash_*_arm64.deb "$debs"/libtinfo6_*_arm64.deb; do dpkg-deb -x "$d" "$root"; done
cross=/usr/aarch64-linux-gnu/lib
for f in libc.so.6 libm.so.6 libdl.so.2 libgcc_s.so.1; do cp -L "$cross/$f" "$root/lib/aarch64-linux-gnu/" 2>/dev/null; done
cp -L "$cross/ld-linux-aarch64.so.1" "$root/lib/ld-linux-aarch64.so.1"
ln -sfn usr/bin "$root/bin"
ln -sf bash "$root/usr/bin/sh"
cp "$hook" "$root/usr/lib/libcortex-hook.so"
echo 'PRETTY_NAME="Fake Ubuntu 24.04"' > "$root/etc/os-release"
printf 'nameserver 8.8.8.8\n' > "$root/etc/resolv.conf"
printf '127.0.0.1 localhost\n10.9.8.7 fake.example\n' > "$root/etc/hosts"
cat > "$work/hello.c" <<'EOF'
#include <stdio.h>
#include <stdlib.h>
int main(int argc, char **argv) {
    printf("hello argc=%d argv0=%s", argc, argv[0]);
    for (int i = 1; i < argc; i++) printf(" [%s]", argv[i]);
    printf(" root=%s", getenv("CORTEX_ROOT") ? "set" : "unset");
    FILE *f = fopen("/etc/os-release", "r");
    char line[128] = "?";
    if (f) { if (!fgets(line, sizeof line, f)) line[0] = 0; fclose(f); }
    for (char *p = line; *p; p++) if (*p == '\n') *p = 0;
    printf(" os=%s\n", line);
    return 3;
}
EOF
aarch64-linux-gnu-gcc -O2 -o "$root/usr/bin/hello" "$work/hello.c"

# A program "of Android's": started without the hook in its environment
if [ ! -e /system/bin/true ] && $SUDO mkdir -p /system/bin 2>/dev/null && $SUDO ln -sf "$(command -v true)" /system/bin/true 2>/dev/null; then
  made_host_program=1
fi
expected=$work/expected.txt
cp "$here/tests/go/expected.txt" "$expected"
host_program=""
if [ -e /system/bin/true ]; then host_program=/system/bin/true; else sed -i '/^exec-host=/d' "$expected"; fi

# A run takes a second or two. qemu-user sometimes hangs when a program with several threads (every
# Go program) forks, a fault of the emulator, so a run that times out is made again
raw_run() {
  (cd "$root/home/user" && env -i CORTEX_ROOT="$root" PATH=/usr/bin:/bin HOME=/home/user GOTEST_HOST_PROGRAM="$host_program" timeout 120 "$@" 2>&1)
}
raw_via_hook() { # bash with the hook loaded starts the program, as a session of Cortex does
  (cd "$root/home/user" && env -i CORTEX_ROOT="$root" PATH=/usr/bin:/bin HOME=/home/user GOTEST_HOST_PROGRAM="$host_program" \
    timeout 120 "$root/lib/ld-linux-aarch64.so.1" --preload "$root/usr/lib/libcortex-hook.so" \
    --library-path "$root/lib/aarch64-linux-gnu:$root/usr/lib/aarch64-linux-gnu" "$root/usr/bin/bash" -c "cd /home/user; $1" 2>&1)
}
retrying() {
  local i out rc
  for i in 1 2 3; do
    out=$("$@")
    rc=$?
    [ "$rc" != 124 ] && break
    echo "  (a run timed out, qemu-user hangs now and then: try $i of 3)" >&2
  done
  printf '%s\n' "$out"
}
run_in_root() { retrying raw_run "$@"; }
via_hook() { retrying raw_via_hook "$1" | grep -v "cannot be preloaded"; }
same_as_expected() { diff -u "$expected" - ; }
hash_of() { sha256sum "$1" | cut -d' ' -f1; }

echo "== the program without the patch"
run_in_root "$work/gotest" > "$work/plain.out"
check "the plain program does not see the file system (the control)" grep -q 'stat-hello=ERR' "$work/plain.out"

echo "== the patch"
cp "$work/gotest" "$work/patched"
before=$(hash_of "$work/patched")
mtime=$(stat -c %Y "$work/patched")
size=$(stat -c %s "$work/patched")
"$work/gopatch" "$root" "$work/patched" >/dev/null
check "the file changed" test "$(hash_of "$work/patched")" != "$before"
check "the file keeps its size" test "$(stat -c %s "$work/patched")" = "$size"
check "the file keeps its time" test "$(stat -c %Y "$work/patched")" = "$mtime"
check "the call site is a branch now" bash -c "aarch64-linux-gnu-objdump -d '$work/patched' 2>/dev/null | grep -B1 -E 'cmn\s+x0, #0xfff' | grep -q -E '\sb\s+0x'"
run_in_root "$work/patched" | same_as_expected > "$work/diff.out" 2>&1
check "the program sees the file system and starts its programs" test ! -s "$work/diff.out"
[ -s "$work/diff.out" ] && sed 's/^/        /' "$work/diff.out" | head -30

once=$(hash_of "$work/patched")
"$work/gopatch" "$root" "$work/patched" >/dev/null
check "a second patch changes nothing" test "$(hash_of "$work/patched")" = "$once"

# Ninety-six goroutines read two hundred files, each its own. The scratch memory for the rewritten
# paths was shared by sixteen slots in turn, and a thread that had not run for a moment found
# another call's path in its slot: `go build` opened other files than it asked for.
stress_out=$(run_in_root "$work/patched" stress | tail -n 1)
check "many calls at once each get the file they asked for" bash -c "[[ '$stress_out' == 'stress=ok'* ]]"
[[ "$stress_out" == 'stress=ok'* ]] || echo "        $stress_out"

# More goroutines than slots start a program at once: the slot of a path that a vfork child passed to
# a successful execve is given back when its goroutine goes on, so that all of them finish
exec_out=$(run_in_root "$work/patched" execstress | tail -n 1)
check "more programs started at once than there are slots all start" bash -c "[[ '$exec_out' == 'execstress=ok'* ]]"
[[ "$exec_out" == 'execstress=ok'* ]] || echo "        $exec_out"

# CORTEX_NO_GOSYS=1: the program as it was built, and the code comes back with the next start
CORTEX_NO_GOSYS=1 "$work/gopatch" "$root" "$work/patched" >/dev/null
check "CORTEX_NO_GOSYS takes the code out again, exactly" test "$(hash_of "$work/patched")" = "$before"
check "and the file keeps its size and time" bash -c "test \"\$(stat -c %s '$work/patched')\" = '$size' && test \"\$(stat -c %Y '$work/patched')\" = '$mtime'"
CORTEX_NO_GOSYS=1 "$work/gopatch" "$root" "$work/patched" >/dev/null
check "and with it set nothing is added" test "$(hash_of "$work/patched")" = "$before"
CORTEX_NO_GOSYS=0 "$work/gopatch" "$root" "$work/patched" >/dev/null
check "CORTEX_NO_GOSYS=0 does not switch it off" test "$(hash_of "$work/patched")" = "$once"

cp "$work/gotest-symbols" "$work/patched-symbols"
"$work/gopatch" "$root" "$work/patched-symbols" >/dev/null
run_in_root "$work/patched-symbols" | same_as_expected > "$work/diff.out" 2>&1
check "a program with its symbols works as well" test ! -s "$work/diff.out"

echo "== the hook patches a program when it starts it"
cp "$work/gotest" "$root/usr/bin/gotest"
before=$(hash_of "$root/usr/bin/gotest")
via_hook "/usr/bin/gotest" | same_as_expected > "$work/diff.out" 2>&1
check "bash starts the plain program and it sees the file system" test ! -s "$work/diff.out"
[ -s "$work/diff.out" ] && sed 's/^/        /' "$work/diff.out" | head -30
check "the hook patched the file" test "$(hash_of "$root/usr/bin/gotest")" != "$before"
once=$(hash_of "$root/usr/bin/gotest")
via_hook "/usr/bin/gotest" >/dev/null
check "starting it again does not change it" test "$(hash_of "$root/usr/bin/gotest")" = "$once"

echo "== a newer Cortex replaces the code of an older one"
cp "$work/gotest" "$work/upgraded"
"$work/gopatch-old" "$root" "$work/upgraded" >/dev/null
older=$(hash_of "$work/upgraded")
run_in_root "$work/upgraded" | same_as_expected > "$work/diff.out" 2>&1
check "a program patched with the older code works" test ! -s "$work/diff.out"
"$work/gopatch" "$root" "$work/upgraded" >/dev/null
check "the newer patcher changed it" test "$(hash_of "$work/upgraded")" != "$older"
fresh=$(hash_of "$work/patched")
check "and the result is exactly the one of a first patch" test "$(hash_of "$work/upgraded")" = "$fresh"
run_in_root "$work/upgraded" | same_as_expected > "$work/diff.out" 2>&1
check "and it works" test ! -s "$work/diff.out"

echo "== another root file system"
root2=$work/rootfs2
cp -a "$root" "$root2"
echo 'PRETTY_NAME="Other root"' > "$root2/etc/os-release"
cp "$work/patched" "$work/moved"
"$work/gopatch" "$root2" "$work/moved" >/dev/null
(cd "$root2/home/user" && env -i CORTEX_ROOT="$root2" PATH=/usr/bin:/bin HOME=/home/user timeout 300 "$work/moved" 2>&1) | grep -q '^os-release=PRETTY_NAME="Other root"'
check "a program moved to another rootfs is patched for it" test "${PIPESTATUS[1]}" = 0

echo "== root mode: a chroot of the rootfs, where the rootfs's own path does not exist"
cp "$work/patched" "$work/inchroot"
mv "$root" "$root.away"
mkdir -p "$work/emptyhome"
(cd "$work/emptyhome" && env -i CORTEX_ROOT="$root" PATH=/usr/bin:/bin HOME=/tmp timeout 120 "$work/inchroot" 2>&1) > "$work/chroot.out"
mv "$root.away" "$root"
check "the program does not map paths there (it sees the host's own)" bash -c "grep -q '^os-release=' '$work/chroot.out' && ! grep -q 'Fake Ubuntu' '$work/chroot.out' && grep -q '^stat-missing=true' '$work/chroot.out'"

echo "== what is left alone"
before=$(hash_of "$root/usr/bin/hello")
"$work/gopatch" "$root" "$root/usr/bin/hello" >/dev/null
check "a dynamic C program is not changed" test "$(hash_of "$root/usr/bin/hello")" = "$before"
printf 'int main(void){return 0;}\n' > "$work/static.c"
aarch64-linux-gnu-gcc -static -O2 -o "$work/static" "$work/static.c" 2>/dev/null
cp "$work/static" "$work/static.before"
"$work/gopatch" "$root" "$work/static" >/dev/null
check "a static C program gets no Go code" bash -c "! grep -q CXGOSYS1 '$work/static'"

echo "== Go versions"
for v in ${CORTEX_GO_TEST_VERSIONS:-}; do
  if build_go "$work/gotest-$v" "$v" -ldflags "-s -w" 2>/dev/null; then
    "$work/gopatch" "$root" "$work/gotest-$v" >/dev/null
    run_in_root "$work/gotest-$v" | same_as_expected > "$work/diff.out" 2>&1
    check "Go $v" test ! -s "$work/diff.out"
  else
    echo "  skip  Go $v (toolchain not available)"
  fi
done

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
