#!/system/bin/sh
# Run as root on the emulator by run-emulator-test.sh when the terminal does not answer: lists the
# app's processes and what they wait for, and runs Ubuntu's bash directly, without and with Cortex's
# preloaded library and as a login shell, to tell where starting a session goes wrong.
PKG="$1"
DATA="/data/data/$PKG"
ROOT="$DATA/files/cortex"
uid=$(stat -c %u "$DATA")

echo "--- processes of uid $uid"
ps -A -o PID,PPID,UID,S,WCHAN,TIME,ARGS | awk -v u="$uid" 'NR == 1 || $3 == u'
for p in $(ps -A -o PID=,UID= | awk -v u="$uid" '$2 == u { print $1 }'); do
    echo "--- pid $p: $(tr '\0' ' ' < "/proc/$p/cmdline" | cut -c1-200)"
    echo "exe: $(readlink "/proc/$p/exe")  $(grep State "/proc/$p/status")  wchan: $(cat "/proc/$p/wchan")"
    # A zombie's exit status, as waitpid() reports it: the last field of stat (signal in the low 7 bits)
    status=$(awk '{ print $NF }' "/proc/$p/stat")
    echo "exit status (waitpid): $status, exit code $((status >> 8)), signal $((status & 127))"
    case "$(readlink "/proc/$p/exe")" in
        *app_process*) ;;
        *)
            echo "fds:"
            ls -l "/proc/$p/fd" 2>/dev/null | awk '{ print "  " $(NF-2), $(NF-1), $NF }'
            if command -v strace >/dev/null; then
                echo "system calls for 5 s:"
                timeout 5 strace -f -p "$p" 2>&1 | tail -n 15
            fi
            ;;
    esac
done

env_for_shell() {
    echo "HOME=$ROOT/home" "CORTEX_ROOT=$ROOT" "TMPDIR=$ROOT/tmp" "TERM=xterm-256color" \
        "PATH=$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/usr/sbin:/system/bin" \
        "LD_LIBRARY_PATH=$ROOT/usr/lib/aarch64-linux-gnu:$ROOT/lib/aarch64-linux-gnu:$ROOT/usr/lib:$ROOT/lib" \
        "GLIBC_TUNABLES=glibc.pthread.rseq=0" "LANG=C.UTF-8"
}

try() { # try NAME COMMAND...: runs COMMAND for at most 60 s
    name="$1"
    shift
    echo "--- $name"
    timeout 60 "$@" > /data/local/tmp/cortex-try.txt 2>&1
    s=$?
    tail -n 20 /data/local/tmp/cortex-try.txt
    echo "(status $s)"
}

cd "$ROOT/home" || exit 1
# Like Cortex does, through glibc's ld.so (Ubuntu's programs ask for /lib/ld-linux-aarch64.so.1,
# which is not where Android looks).
LDSO="$ROOT/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
HOOK="LD_PRELOAD=$ROOT/usr/lib/libcortex-hook.so"
try "bash, as root, without the hook" env -i $(env_for_shell) "$LDSO" "$ROOT/usr/bin/bash" -c 'echo bash-ok; uname -m'
try "bash, as root, with the hook" env -i $(env_for_shell) "$HOOK" "$LDSO" "$ROOT/usr/bin/bash" -c 'echo hook-ok; id; ls /'
try "login bash with the hook, as the app's user" su "$uid" env -i $(env_for_shell) "$HOOK" \
    "$LDSO" "$ROOT/usr/bin/bash" -l -c 'echo login-ok'
try "apt-get with the hook, as the app's user" su "$uid" env -i $(env_for_shell) "$HOOK" \
    "$LDSO" "$ROOT/usr/bin/bash" -c 'apt-get --version | head -n 1'
try "id without the hook" env -i $(env_for_shell) "$LDSO" "$ROOT/usr/bin/id"
try "apt-get without the hook" env -i $(env_for_shell) "$LDSO" "$ROOT/usr/bin/apt-get" --version
try "id with the hook, directly" env -i $(env_for_shell) "$HOOK" "$LDSO" "$ROOT/usr/bin/id"
if command -v strace >/dev/null; then
    echo "--- system calls of id with the hook (last 40)"
    # strace is an Android program: the variables go only to the traced program (-E).
    set --
    for kv in $(env_for_shell) "$HOOK"; do set -- "$@" -E "$kv"; done
    timeout 60 strace -f -o /data/local/tmp/cortex-strace.txt "$@" "$LDSO" "$ROOT/usr/bin/id" >/dev/null 2>&1
    tail -n 40 /data/local/tmp/cortex-strace.txt
    # id crashes as a child of bash (a forked process), not when it is started directly
    echo "--- system calls of the process that crashes in: bash -c 'id; echo after-id' (with the hook)"
    timeout 60 strace -f -o /data/local/tmp/cortex-strace2.txt "$@" "$LDSO" "$ROOT/usr/bin/bash" -c 'id; echo after-id' 2>&1
    crashed=$(grep -m 1 "+++ killed by" /data/local/tmp/cortex-strace2.txt | awk '{ print $1 }')
    if [ -n "$crashed" ]; then
        echo "process $crashed: first 40 and last 80 system calls (all of them: strace-fork.txt)"
        grep "^$crashed " /data/local/tmp/cortex-strace2.txt | head -n 40
        echo "..."
        grep "^$crashed " /data/local/tmp/cortex-strace2.txt | tail -n 80
    else
        tail -n 30 /data/local/tmp/cortex-strace2.txt
    fi
fi
echo "--- tombstones"
ls -lt /data/tombstones 2>/dev/null | head -n 5
