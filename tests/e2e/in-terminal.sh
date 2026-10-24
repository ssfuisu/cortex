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
    echo "page size: $(getconf PAGESIZE)"
    df -h / 2>/dev/null
    LD_SHOW_AUXV=1 /bin/true | grep -E "AT_HWCAP|AT_PLATFORM"
    grep -m 1 -i features /proc/cpuinfo
} > "$OUT/system.txt" 2>&1

echo "Cortex end-to-end test"

# The system
check os-release grep -q 'VERSION_ID="24.04"' /etc/os-release
expect dpkg-arch "${CORTEX_E2E_ARCH:-arm64}" dpkg --print-architecture
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
# Hard links are copies that show the link count and inode of a real link: link() and stat() in one
# perl, then ln and stat, separate processes that share the table of emulated links.
hard_links() {
    local d
    d=$(mktemp -d) && cd "$d" && echo one > a &&
        perl -e 'link("a", "b") or die "link: $!\n"; my @a = stat("a"); my @b = stat("b");
                 print "links=$a[3] ", ($a[1] == $b[1] ? "same" : "different"), " inode\n"' &&
        [ "$(cat b)" = one ] && cd / && rm -rf "$d"
}
export -f hard_links
expect hard-links "links=2 same inode" hard_links
# (what stat and perl see is printed, so that a failure shows which part differs)
hard_links_between_processes() {
    local d linked removed same=different
    d=$(mktemp -d) && cd "$d" && echo one > a && ln a b || return 1
    stat -c '%n: links %h, inode %i, device %d, modified %y' a b
    perl -e 'for (@ARGV) { my @s = stat($_); print "$_ (perl): links $s[3], inode $s[1], device $s[0], modified $s[9]\n" }' a b
    ls -l /var/lib/cortex/hardlinks
    linked=$(stat -c %h a)
    [ "$(stat -c %i a)" = "$(stat -c %i b)" ] && same=same
    rm b && removed=$(stat -c %h a)
    cd / && rm -rf "$d"
    echo "links $linked then $removed, $same inode"
}
export -f hard_links_between_processes
expect hard-links-shared "links 2 then 1, same inode" hard_links_between_processes
# The files Cortex makes are root's, the user programs run as (test -O compares the owner with geteuid())
check own-files bash -c 'f=$(mktemp) && [ -O "$f" ] && [ -G "$f" ] && [ "$(stat -c %u:%g "$f")" = 0:0 ]; s=$?; rm -f "$f"; exit $s'
check sha256sum bash -c 'echo hi | sha256sum | grep -q ^98ea6e4f216f2fb4b69fff9b3a44842c38686ca685f3f55dc48c5d3fb1107be4'
check shared-storage bash -c 'echo hi > "$0/written-by-ubuntu.txt" && [ "$(cat "$0/written-by-ubuntu.txt")" = hi ]' "$OUT"

# skip NAME WHY: a check left out
skip() {
    echo "SKIP $1: $2" >> "$RESULTS"
    printf '\033[33m-\033[0m %s\n' "$1"
}
# Without packages (E2E_NO_APT, the 16 KB image: its translator spins forever in apt's signature
# check of the package lists)
NO_APT="${CORTEX_E2E_NO_APT:-}"

# Packages from Ubuntu's archive. The app comes without package lists: the first install runs
# "apt-get update" by itself (/usr/local/bin/apt-get).
if [ -z "$NO_APT" ]; then
    expect apt-install "package lists are empty" apt-get install -y --no-install-recommends hello python3-minimal
    check apt-update apt-get update
    expect hello "Hello, world!" hello
    expect python3 "4" python3 -c 'print(2 + 2)'
else
    for name in apt-install apt-update hello python3; do skip "$name" "no packages on this emulator"; done
fi

# cortex-api
expect api-info '"app"' cortex-api info
expect api-battery '"percentage"' cortex-api battery
check api-notification cortex-api notification -i e2e -t "Cortex e2e notification" -c "A notification from the terminal"
check notify-send notify-send "Cortex e2e notify-send" "A notification from notify-send"
expect api-clipboard e2e-clip bash -c 'echo e2e-clip | cortex-api clipboard-set && cortex-api clipboard-get'
check api-toast cortex-api toast "Hello from Ubuntu"
expect api-wakelock on bash -c 'cortex-api wakelock on >/dev/null && cortex-api wakelock status; s=$?; cortex-api wakelock off >/dev/null; exit $s'
expect api-bad-command "unknown command" bash -c 'cortex-api no-such-command; [ $? = 1 ]'
expect api-mirror "https://" cortex-api mirror show
expect api-network " " cortex-api network

# Ubuntu's OpenSSH server (cortex-sshd installs it the first time), and a login with a key
ssh_login() {
    cortex-sshd start || return 1
    rm -f ~/.ssh/e2e ~/.ssh/e2e.pub
    ssh-keygen -q -t ed25519 -N '' -f ~/.ssh/e2e || return 1
    cortex-sshd key < ~/.ssh/e2e.pub || return 1
    local out s
    out="$(timeout 60 ssh -p 8022 -i ~/.ssh/e2e -o BatchMode=yes -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null root@127.0.0.1 'echo ssh-login-ok as $(id -un) in $PWD' 2>&1)"
    s=$?
    echo "$out"
    cortex-sshd stop
    [ "$s" = 0 ] || { echo "--- sshd log"; tail -n 40 ~/.cortex/sshd.log; return 1; }
}
export -f ssh_login
if [ -z "$NO_APT" ]; then
    expect ssh-server "ssh-login-ok as root" ssh_login
else
    skip ssh-server "openssh-server is a package"
fi

# A web server for the notification about it, which run-emulator-test.sh looks for (perl-base is
# part of every Ubuntu system); it answers for three minutes
perl -MIO::Socket::INET -e '
    my $s = IO::Socket::INET->new(LocalAddr => "127.0.0.1", LocalPort => 8642, Listen => 5, ReuseAddr => 1) or die "$!";
    $SIG{ALRM} = sub { exit 0 };
    alarm 180;
    while (my $c = $s->accept) { <$c>; print $c "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\ncortex e2e\n"; close $c }
' > "$OUT/web-server.txt" 2>&1 &

echo "passed=$passed failed=$failed" > "$OUT/done"
echo
echo "$passed passed, $failed failed"
