#!/usr/bin/env bash
# Tests of cortex-chroot (root mode) with a small rootfs at an Android-like path. Needs root, since
# it mounts and changes root for real: CI runs it with sudo. Without root it is skipped, unless
# CORTEX_REQUIRE_ROOT_TESTS=1 (then it fails).
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

PASS_COUNT=0
FAIL_COUNT=0
pass() { PASS_COUNT=$((PASS_COUNT + 1)); printf "  [PASS] %s\n" "$1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); printf "  [FAIL] %s\n" "$1" >&2; }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$3', got '$2')"; fi; }

if [ "$(id -u)" -ne 0 ]; then
    if [ "${CORTEX_REQUIRE_ROOT_TESTS:-0}" = "1" ]; then
        echo "cortex-chroot tests need root" >&2
        exit 1
    fi
    echo "Skipping cortex-chroot tests: they need root (run with sudo)."
    exit 0
fi

PKG="org.cortex.terminal.chroottest"
PKG_DIR="/data/user/0/$PKG"
PKG_B="$PKG.b"
ROOT="$PKG_DIR/files/cortex"
APP_ID=10123
WORK="$(mktemp -d)"
HELPER="$WORK/cortex-chroot"

cleanup() {
    grep " $ROOT/" /proc/self/mounts | awk '{print $2}' | sort -r | while read -r m; do umount -l "$m" 2>/dev/null; done
    rm -rf "$PKG_DIR" "$WORK" "/data/data/$PKG_B" "/data/user/0/$PKG_B"
    # Only the directories this test created, and only while they are empty.
    rmdir /data/data /data/user/0 /data/user /data 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Building cortex-chroot ==="
if gcc -std=gnu11 -static -O2 -Wall -Wextra -Werror -o "$HELPER" "$REPO_ROOT/app/src/main/cpp/root/cortex-chroot.c"; then
    pass "cortex-chroot builds statically without warnings"
else
    fail "cortex-chroot does not build"
    exit 1
fi

echo "=== Preparing a rootfs at $ROOT ==="
rm -rf "$PKG_DIR"
mkdir -p "$ROOT/usr/bin" "$ROOT/usr/lib" "$ROOT/usr/lib64" "$ROOT/etc" "$ROOT/home" "$ROOT/tmp"
ln -s usr/bin "$ROOT/bin"
ln -s usr/lib "$ROOT/lib"
ln -s usr/lib64 "$ROOT/lib64"
for b in bash ls id cat hostname readlink stat touch grep env sleep wc; do
    p="$(command -v "$b")"
    cp -L "$p" "$ROOT/usr/bin/$b"
    for lib in $(ldd "$p" 2>/dev/null | grep -o '/[^ ]*'); do
        mkdir -p "$ROOT$(dirname "$lib")"
        cp -L "$lib" "$ROOT$lib" 2>/dev/null || true
    done
done
ln -s bash "$ROOT/usr/bin/sh"
echo "root:x:0:0:root:$ROOT/home:/bin/bash" > "$ROOT/etc/passwd"
echo 'CORTEX_TEST_PROFILE=1' > "$ROOT/etc/profile"
chmod 0700 "$ROOT"
chmod 0700 "$ROOT/tmp"

echo "=== check ==="
"$HELPER" check "$ROOT"
check "check succeeds in a working rootfs" "$?" "0"
check "check leaves no mount behind" "$(grep -c " $ROOT" /proc/self/mounts)" "0"

echo "=== enter ==="
OUT="$("$HELPER" enter "$ROOT" --term xterm-test --colorterm truecolor --lang C.UTF-8 --tz Europe/Istanbul -- /bin/bash -c '
echo "uid=$(id -u)"
echo "host=$(hostname)"
echo "home=$HOME pwd=$PWD"
echo "chroot=$CORTEX_CHROOT term=$TERM tz=$TZ"
echo "preload=${LD_PRELOAD:-none} libpath=${LD_LIBRARY_PATH:-none} cortexroot=${CORTEX_ROOT:-none}"
echo "alias=$(readlink -f '"$ROOT"'/etc)"
echo "alias2=$(readlink -f /data/data/'"$PKG"'/files/cortex/home)"
test -c /dev/null && echo x > /dev/null && echo "devnull=ok"
test -c /dev/ptmx && echo "ptmx=ok"
echo "fd=$(readlink /dev/fd)"
echo shared > /dev/shm/cortex-test && echo "shm=written"
echo "mounts=$(grep -c . /proc/self/mounts)"
')"
check "enter runs as root" "$(echo "$OUT" | grep '^uid=')" "uid=0"
check "the session has its own hostname" "$(echo "$OUT" | grep '^host=')" "host=cortex-android"
check "HOME is the home directory normal mode uses" "$(echo "$OUT" | grep '^home=')" "home=/home pwd=/home"
check "the environment is passed and marked" "$(echo "$OUT" | grep '^chroot=')" "chroot=1 term=xterm-test tz=Europe/Istanbul"
check "the hook is not in the environment" "$(echo "$OUT" | grep '^preload=')" "preload=none libpath=none cortexroot=none"
check "ROOT's Android path resolves to / in the chroot" "$(echo "$OUT" | grep '^alias=')" "alias=/etc"
check "/data/data resolves too" "$(echo "$OUT" | grep '^alias2=')" "alias2=/home"
check "/dev/null works" "$(echo "$OUT" | grep '^devnull=')" "devnull=ok"
check "/dev/ptmx exists" "$(echo "$OUT" | grep '^ptmx=')" "ptmx=ok"
check "/dev/fd links to /proc/self/fd" "$(echo "$OUT" | grep '^fd=')" "fd=/proc/self/fd"
check "/dev/shm can be written" "$(echo "$OUT" | grep '^shm=')" "shm=written"
check "/dev/shm is ROOT/tmp/shm, shared with normal mode" "$(cat "$ROOT/tmp/shm/cortex-test" 2>/dev/null)" "shared"
check "the chroot sees only its own mounts (proc sys dev pts shm data)" "$(echo "$OUT" | grep '^mounts=')" "mounts=6"
check "/ and /tmp are open to other users in the chroot" "$(stat -c %a "$ROOT" "$ROOT/tmp" | tr '\n' ' ')" "755 1777 "
check "nothing is mounted outside the session" "$(grep -c " $ROOT" /proc/self/mounts)" "0"

LOGIN="$(echo 'echo "$0 $CORTEX_TEST_PROFILE"; exit 3' | "$HELPER" enter "$ROOT")"
STATUS=$?
check "without a command enter starts a login shell" "$LOGIN" "-bash 1"
check "the shell's exit status is returned" "$STATUS" "3"

"$HELPER" enter "$ROOT" -- /nonexistent 2>/dev/null
check "a missing command fails with status 6" "$?" "6"

echo "=== a phone where /data/user/0/<package> links to /data/data/<package> ==="
mkdir -p "/data/data/$PKG_B/files"
cp -a "$ROOT" "/data/data/$PKG_B/files/cortex"
ln -s "../../data/$PKG_B" "/data/user/0/$PKG_B"
OUT_B="$("$HELPER" enter "/data/user/0/$PKG_B/files/cortex" -- /bin/bash -c '
echo "user0=$(readlink -f /data/user/0/'"$PKG_B"'/files/cortex/etc)"
echo "data=$(readlink -f /data/data/'"$PKG_B"'/files/cortex/home)"
')"
check "the app's /data/user/0 path resolves when it is a link" "$(echo "$OUT_B" | grep '^user0=')" "user0=/etc"
check "the canonical /data/data path resolves too" "$(echo "$OUT_B" | grep '^data=')" "data=/home"
rm -rf "/data/data/$PKG_B" "/data/user/0/$PKG_B"

echo "=== argument checks ==="
"$HELPER" enter /tmp 2>/dev/null
check "a directory that is not a Cortex rootfs is refused" "$?" "2"
"$HELPER" enter "$ROOT" --term 2>/dev/null
check "an option without a value is refused" "$?" "2"
if command -v setpriv >/dev/null 2>&1; then
    setpriv --reuid=65534 --regid=65534 --clear-groups "$HELPER" check "$ROOT" 2>/dev/null
    check "it refuses to run without root" "$?" "2"
fi

echo "=== restore ==="
"$HELPER" restore / "$APP_ID" "$APP_ID" 2>/dev/null
check "restore refuses /" "$?" "2"
"$HELPER" restore "$ROOT/.." "$APP_ID" "$APP_ID" 2>/dev/null
check "restore refuses the directory above the rootfs" "$?" "2"
"$HELPER" restore "$ROOT" 0 0 2>/dev/null
check "restore refuses uid 0" "$?" "2"
"$HELPER" restore "$ROOT" "$APP_ID" abc 2>/dev/null
check "restore refuses a gid that is not a number" "$?" "2"

touch "$ROOT/home/made-by-root"
mkdir -p "$ROOT/var/lib/pkg" && touch "$ROOT/var/lib/pkg/state"
cp "$ROOT/usr/bin/id" "$ROOT/usr/bin/setuid-tool" && chmod 4755 "$ROOT/usr/bin/setuid-tool"
HOST_FILE="$WORK/host-file"
touch "$HOST_FILE"
ln -s "$HOST_FILE" "$ROOT/home/link-to-host"
mkdir -p "$ROOT/mnt/other" && mount -t tmpfs other "$ROOT/mnt/other" && touch "$ROOT/mnt/other/inside"
LABEL_TEST=0
if python3 -I -c 'import os,sys; os.setxattr(sys.argv[1], "security.selinux", b"u:object_r:app_data_file:s0:c123,c256\0", follow_symlinks=False); os.setxattr(sys.argv[2], "security.selinux", b"u:object_r:app_data_file:s0\0", follow_symlinks=False)' "$ROOT" "$ROOT/home/made-by-root" 2>/dev/null; then
    LABEL_TEST=1
fi

"$HELPER" restore "$ROOT" "$APP_ID" "$APP_ID" >/dev/null
check "restore succeeds" "$?" "0"
check "every file of the rootfs belongs to the app" \
    "$(find "$ROOT" -xdev \( ! -uid "$APP_ID" -o ! -gid "$APP_ID" \) | grep -v "^$ROOT/mnt/other" | wc -l)" "0"
check "the set-user-ID bit is kept" "$(stat -c %a "$ROOT/usr/bin/setuid-tool")" "4755"
check "links are not followed" "$(stat -c %u "$HOST_FILE")" "0"
check "other file systems are not entered" "$(stat -c %u "$ROOT/mnt/other/inside")" "0"
if [ "$LABEL_TEST" = 1 ]; then
    check "SELinux labels are set to the rootfs's label" \
        "$(python3 -I -c 'import os,sys; print(os.getxattr(sys.argv[1], "security.selinux", follow_symlinks=False))' "$ROOT/home/made-by-root")" \
        "b'u:object_r:app_data_file:s0:c123,c256\\x00'"
else
    echo "  (security.selinux cannot be set here: label test skipped)"
fi
umount "$ROOT/mnt/other"

echo ""
echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
[ "$FAIL_COUNT" -eq 0 ]
