#!/bin/bash
# Runs inside a Cortex terminal session during the emulator test: run-emulator-test.sh types the
# command that starts it, as a user would. Every check is recorded in $OUT/results.txt as
# "PASS name" or "FAIL name: last lines of its output"; $OUT/done marks the end. $OUT is in shared
# storage, where adb can read it.
OUT="${CORTEX_E2E_OUT:-/sdcard/Download/cortex-e2e}"
mkdir -p "$OUT"
mkdir "$OUT/started" 2>/dev/null || exit 0 # the command was typed twice: run once
RESULTS="$OUT/results.txt"
: > "$RESULTS"
export DEBIAN_FRONTEND=noninteractive

# On the x86_64 test emulator Ubuntu runs through Android's ARM translator, which does not run every
# instruction that the CPU features it reports promise (apt's https method died of SIGILL): the
# crypto libraries are told to use their plain code there. Phones do not need this.
if [ -n "${CORTEX_E2E_EMULATOR:-}" ]; then
    export OPENSSL_armcap=0 GNUTLS_CPUID_OVERRIDE=0x1 NETTLE_FAT_OVERRIDE=none
    mkdir -p /etc/gcrypt
    printf '%s\n' arm-neon arm-aes arm-sha1 arm-sha2 arm-pmull arm-sha3 arm-sm3 arm-sm4 arm-sha512 \
        arm-sve arm-sve2 arm-sveaes arm-svepmull arm-svesha3 arm-svesm4 > /etc/gcrypt/hwf.deny
fi

passed=0
failed=0

# check NAME COMMAND...: runs COMMAND and records whether it succeeded, with its output.
check() {
    local name="$1" out
    shift
    if out=$("$@" 2>&1); then
        echo "PASS $name" >> "$RESULTS"
        passed=$((passed + 1))
        printf '\033[32m✓\033[0m %s\n' "$name"
    else
        echo "FAIL $name: $(printf '%s' "$out" | tail -n 5 | tr '\n' ' ')" >> "$RESULTS"
        failed=$((failed + 1))
        printf '\033[31m✗\033[0m %s\n' "$name"
    fi
    printf '%s\n' "$out" > "$OUT/log-$name.txt"
}

# expect NAME EXPECTED COMMAND...: like check, and the output must contain EXPECTED.
expect() {
    local name="$1" expected="$2"
    shift 2
    check "$name" bash -c 'out=$("$@" 2>&1); s=$?; printf "%s\n" "$out"; [ "$s" = 0 ] && [[ "$out" == *"$0"* ]]' "$expected" "$@"
}

{
    uname -a
    cat /etc/os-release
    id
    dpkg --print-architecture
    df -h / 2>/dev/null
    LD_SHOW_AUXV=1 /bin/true | grep -E "AT_HWCAP|AT_PLATFORM"
    grep -m 1 -i features /proc/cpuinfo
} > "$OUT/system.txt" 2>&1

echo "Cortex end-to-end test"

# The system
check os-release grep -q 'VERSION_ID="24.04"' /etc/os-release
expect dpkg-arch arm64 dpkg --print-architecture
expect id-root "uid=0(root)" id
expect bash-arrays ok bash -c 'declare -A m=([a]=ok); echo "${m[a]}"'
expect pipes 100000 bash -c 'seq 1 100000 | sort -rn | head -n 1'
expect perl ok perl -e 'print "ok\n"'

# Files
# (set -x: the log shows the step that failed)
check files bash -xc '
    d=$(mktemp -d) && cd "$d" &&
    echo one > a && mv a b && ln -s b s && [ "$(readlink s)" = b ] && [ "$(cat s)" = one ] &&
    mkfifo p && [ -p p ] && rm -rf "$d"'
# Hard links are copies that show the link count and inode of a real link to the process that made
# them (so ln and a later stat, two processes, do not see it): link() and stat() in one perl.
hard_links() {
    local d
    d=$(mktemp -d) && cd "$d" && echo one > a &&
        perl -e 'link("a", "b") or die "link: $!\n"; my @a = stat("a"); my @b = stat("b");
                 print "links=$a[3] ", ($a[1] == $b[1] ? "same" : "different"), " inode\n"' &&
        [ "$(cat b)" = one ] && cd / && rm -rf "$d"
}
export -f hard_links
expect hard-links "links=2 same inode" hard_links
check sha256sum bash -c 'echo hi | sha256sum | grep -q ^98ea6e4f216f2fb4b69fff9b3a44842c38686ca685f3f55dc48c5d3fb1107be4'
check shared-storage bash -c 'echo hi > "$0/written-by-ubuntu.txt" && [ "$(cat "$0/written-by-ubuntu.txt")" = hi ]' "$OUT"

# Packages from Ubuntu's archive
check apt-update apt-get update
check apt-install apt-get install -y --no-install-recommends hello python3-minimal
expect hello "Hello, world!" hello
expect python3 "4" python3 -c 'print(2 + 2)'

# cortex-api
expect api-info '"app"' cortex-api info
expect api-battery '"percentage"' cortex-api battery
check api-notification cortex-api notification -i e2e -t "Cortex e2e notification" -c "A notification from the terminal"
check notify-send notify-send "Cortex e2e notify-send" "A notification from notify-send"
expect api-clipboard e2e-clip bash -c 'echo e2e-clip | cortex-api clipboard-set && cortex-api clipboard-get'
check api-toast cortex-api toast "Hello from Ubuntu"
expect api-wakelock on bash -c 'cortex-api wakelock on && cortex-api wakelock status; cortex-api wakelock off >/dev/null'
expect api-bad-command "unknown command" bash -c 'cortex-api no-such-command; [ $? = 1 ]'

echo "passed=$passed failed=$failed" > "$OUT/done"
echo
echo "$passed passed, $failed failed"
