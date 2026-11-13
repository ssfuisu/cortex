#!/bin/bash
# Same tests, any Ubuntu or Debian on a phone: run it in Cortex, then in proot-distro (or anything
# else) on the same phone, and compare the two tables. Needs only bash and coreutils; python3, gcc
# and apt-cache are used when they are there.
#
#     bash compare.sh            # prints a Markdown table
#     curl -fsSL https://raw.githubusercontent.com/ssfuisu/cortex/main/tests/bench/compare.sh | bash
#
# Every number is the median of RUNS runs (default 5). Lower is better except for MB/s.
set -u
RUNS="${RUNS:-5}"
W="$(mktemp -d "${TMPDIR:-/tmp}/cortex-compare.XXXXXX")" || exit 1
trap 'rm -rf "$W"' EXIT
LC_ALL=C
export LC_ALL

now_ns() { date +%s%N; }

median() {
    sort -n | awk '{ v[NR] = $1 } END { if (NR == 0) print "-"; else if (NR % 2) print v[(NR + 1) / 2]; else print int((v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

# measure NAME DIVISOR UNIT COMMAND: runs COMMAND RUNS times, prints the median time divided by DIVISOR
measure() {
    local name="$1" div="$2" unit="$3" cmd="$4" i t0 t1 samples=""
    for ((i = 0; i < RUNS; i++)); do
        t0=$(now_ns)
        if ! bash -c "$cmd" > /dev/null 2>&1; then
            echo "| $name | failed |"
            return
        fi
        t1=$(now_ns)
        samples+="$(((t1 - t0) / div))"$'\n'
    done
    echo "| $name | $(printf '%s' "$samples" | median) $unit |"
}

# What this is running in
env_name="unknown"
tracer=$(awk '/^TracerPid:/ { print $2 }' /proc/self/status 2>/dev/null)
if [ -n "${CORTEX_ROOT:-}" ] || [[ "${LD_PRELOAD:-}" == *cortex* ]]; then
    env_name="Cortex (LD_PRELOAD library)"
elif [ -n "$tracer" ] && [ "$tracer" != 0 ]; then
    env_name="traced by $(tr -d '\0' < "/proc/$tracer/comm" 2>/dev/null || echo "pid $tracer") (ptrace, PRoot-style)"
elif [ -f /.dockerenv ] || grep -q container= /proc/1/environ 2>/dev/null; then
    env_name="container"
else
    env_name="no tracer, no Cortex library (plain Linux, chroot or VM)"
fi

echo "### $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Linux}") on $(uname -m), kernel $(uname -r)"
echo
echo "Running in: $env_name. $(nproc) CPUs. Median of $RUNS runs."
echo
echo "| Test | Result |"
echo "|---|---|"

# Starting programs: fork + exec + the dynamic loader, the cost every shell script pays
measure "Start a program (\`/bin/true\`, per start)" $((200 * 1000)) "µs" 'for i in $(seq 200); do /bin/true; done'

# File system calls that take a path: what a build, git or apt does all the time
mkdir -p "$W/f"
# A new directory each run, so every run creates files (and does not overwrite the last run's)
measure "Create 1000 small files" 1000000 "ms" "d=\$(mktemp -d '$W/c.XXXXXX') && cd \"\$d\" && for i in \$(seq 1000); do echo \$i > f\$i; done"
for i in $(seq 1000); do echo "$i" > "$W/f/f$i"; done
measure "\`stat\` 1000 files (\`ls -l\`)" 1000000 "ms" "ls -l '$W/f'"
measure "Read 1000 files (\`cat\`)" 1000000 "ms" "cat '$W'/f/* > /dev/null"
measure "Archive and unpack them (\`tar\`)" 1000000 "ms" "cd '$W' && tar cf a.tar f && mkdir -p u && tar xf a.tar -C u && rm -rf u a.tar"
measure "Find files (\`find\` over /usr/share)" 1000000 "ms" "find /usr/share -name '*.txt' > /dev/null"

# Pure computation: no system calls in the hot loop, should be the same everywhere
measure "Shell loop, 200000 rounds (CPU only)" 1000000 "ms" 'i=0; while [ $i -lt 200000 ]; do i=$((i + 1)); done'
mbps() {
    local i t0 t1
    for ((i = 0; i < RUNS; i++)); do
        t0=$(now_ns)
        head -c 268435456 /dev/zero | sha256sum > /dev/null
        t1=$(now_ns)
        echo $((256 * 1000000000 / (t1 - t0)))
    done | median
}
echo "| \`sha256sum\` of 256 MB (CPU and pipes) | $(mbps) MB/s |"

if command -v python3 > /dev/null; then
    measure "Start Python (\`python3 -c pass\`)" 1000000 "ms" 'python3 -c pass'
fi
if command -v gcc > /dev/null; then
    printf '#include <stdio.h>\nint main(void){puts("hi");return 0;}\n' > "$W/h.c"
    measure "Compile hello.c (\`gcc\`)" 1000000 "ms" "gcc -O2 -o '$W/h' '$W/h.c'"
fi
if command -v apt-cache > /dev/null; then
    measure "Search the package lists (\`apt-cache search python\`)" 1000000 "ms" 'apt-cache search python'
fi

# Memory a shell takes, and the tracer's own (PRoot keeps one process that follows all of them)
rss=$(awk '/^VmRSS:/ { print $2 }' "/proc/$$/status")
echo "| Memory of one shell process | $((rss / 1024)) MB |"
if [ -n "$tracer" ] && [ "$tracer" != 0 ]; then
    trss=$(awk '/^VmRSS:/ { print $2 }' "/proc/$tracer/status" 2>/dev/null)
    [ -n "$trss" ] && echo "| Memory of the tracer process | $((trss / 1024)) MB |"
fi
