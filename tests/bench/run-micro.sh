#!/usr/bin/env bash
# Measures what libcortex-hook.so adds to common calls, against the same call without the hook.
#
#   tests/bench/run-micro.sh [-r REPS] [-t TEST,TEST,...] [-c CPU] [NAME=HOOK.so ...]
#
# Run it inside Cortex (it needs a compiler: apt install gcc). Without arguments it compares the
# installed hook with no hook at all; give NAME=path pairs to compare other builds of the hook, for
# example one built from an older commit:
#
#   gcc -std=gnu11 -shared -fPIC -O3 -o /tmp/hook-old.so old/cortex-hook.c -ldl
#   tests/bench/run-micro.sh old=/tmp/hook-old.so new=$CORTEX_ROOT/usr/lib/libcortex-hook.so
#
# Every test runs REPS times per configuration (default 7), interleaved so that drift in the phone's
# clock speed or load hits all of them alike; the table shows the median time per call in ns (us for
# starting a program) and, in brackets, the change against the first configuration. Results depend on
# the phone: compare numbers measured on the same device in the same session.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPS=7
ONLY=""
CPU=""

usage() {
    sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while getopts "r:t:c:h" opt; do
    case "$opt" in
        r) REPS="$OPTARG" ;;
        t) ONLY="$OPTARG" ;;
        c) CPU="$OPTARG" ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done
shift $((OPTIND - 1))

if [ -z "${CORTEX_ROOT:-}" ]; then
    echo "run-micro.sh: CORTEX_ROOT is not set; run this inside a Cortex session" >&2
    exit 64
fi
CC="${CC:-gcc}"
if ! command -v "$CC" >/dev/null 2>&1; then
    echo "run-micro.sh: no C compiler ($CC); install one with: apt install gcc" >&2
    exit 64
fi

# The CPU to pin to: the highest-numbered online one is a big core on most phones.
if [ -z "$CPU" ]; then
    CPU="$(nproc --all 2>/dev/null || echo 1)"
    CPU=$((CPU - 1))
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
case "$WORK" in
    "$CORTEX_ROOT"/*) REAL_WORK="$WORK" ;;
    *) REAL_WORK="$CORTEX_ROOT$WORK" ;;
esac

"$CC" -std=gnu11 -O2 -Wall -o "$WORK/micro" "$HERE/micro.c" || exit 1

# ld.so is started by hand, so that the program runs with exactly the hook we ask for (or none)
LD_SO=""
for candidate in "$CORTEX_ROOT"/lib/*/ld-linux-*.so.* "$CORTEX_ROOT"/lib/ld-linux-*.so.*; do
    if [ -e "$candidate" ]; then LD_SO="$candidate"; break; fi
done
if [ -z "$LD_SO" ]; then
    echo "run-micro.sh: no dynamic linker found under $CORTEX_ROOT/lib" >&2
    exit 1
fi
LD_DIR="$(dirname "$LD_SO")"
LIBPATH="$LD_DIR:$CORTEX_ROOT/usr/lib/$(basename "$LD_DIR")"

# ld.so is started by hand and opens LD_PRELOAD itself, so it needs the real path of a library:
# inside Cortex a path such as /tmp/hook.so is really $CORTEX_ROOT/tmp/hook.so.
real_path() {
    local p
    p="$(readlink -f "$1")"
    case "$p" in
        "$CORTEX_ROOT"/*) ;;
        /*) if [ -e "$CORTEX_ROOT$p" ]; then p="$CORTEX_ROOT$p"; fi ;;
    esac
    printf '%s\n' "$p"
}

cfg_names=(native)
cfg_hooks=(none)
if [ "$#" -eq 0 ]; then
    set -- "installed=$CORTEX_ROOT/usr/lib/libcortex-hook.so"
fi
for pair in "$@"; do
    name="${pair%%=*}"
    path="${pair#*=}"
    if [ "$name" = "$pair" ] || [ ! -f "$path" ]; then
        echo "run-micro.sh: expected NAME=path-to-hook.so, got '$pair'" >&2
        exit 64
    fi
    cfg_names+=("$name")
    cfg_hooks+=("$(real_path "$path")")
done
ncfg=${#cfg_names[@]}
declare -a failed

# Tests that cannot run without the hook: a plain Ubuntu program or script cannot be started by the
# kernel here, Android programs refuse the library path of Ubuntu's libraries, and Android's seccomp
# filter kills glibc's realpath().
needs_hook() {
    case "$1" in realpath | spawn_true | spawn_script | spawn_host) return 0 ;; esac
    return 1
}

# seconds for one run of test $2 with $3 iterations under configuration $1
run_one() {
    local hook="${cfg_hooks[$1]}" root="" out
    if [ "$hook" = none ]; then
        root="$CORTEX_ROOT"
        hook=""
    fi
    out="$(env LD_PRELOAD="$hook" BENCH_LDSO="$LD_SO" BENCH_CPU="$CPU" \
        "$LD_SO" --library-path "$LIBPATH" "$REAL_WORK/micro" "$2" "$root" "$3" 2>&1)" || {
        echo "run-micro.sh: ${cfg_names[$1]} / $2 failed: $(printf '%s' "$out" | head -n 2 | tr '\n' ' ' | cut -c1-200)" >&2
        return 1
    }
    echo "${out%% *}"
}

median() {
    sort -g | awk '{ a[NR] = $1 } END { if (NR % 2) print a[(NR + 1) / 2]; else print (a[NR / 2] + a[NR / 2 + 1]) / 2 }'
}

if [ -n "$ONLY" ]; then
    tests="$(printf '%s' "$ONLY" | tr ',' ' ')"
else
    tests="$(env LD_PRELOAD= "$LD_SO" --library-path "$LIBPATH" "$REAL_WORK/micro" --list | tr '\n' ' ')"
fi

printf '%-18s' "per call"
for ((i = 0; i < ncfg; i++)); do printf ' | %-22s' "${cfg_names[$i]}"; done
printf '\n'

for t in $tests; do
    if [ "$t" = spawn_host ] && [ ! -x /system/bin/sh ]; then continue; fi

    first=0
    if needs_hook "$t"; then first=1; fi
    if [ "$first" -ge "$ncfg" ]; then continue; fi

    # iterations for about 0.2 s per run, from a short pilot (the first configuration that can run it)
    pilot=""
    for ((i = first; i < ncfg; i++)); do
        if pilot="$(run_one "$i" "$t" 200 2>/dev/null)"; then break; fi
        pilot=""
    done
    if [ -z "$pilot" ]; then
        printf '%-18s | every configuration fails this test\n' "$t"
        continue
    fi
    n="$(awk -v s="$pilot" 'BEGIN { per = s / 200; if (per < 1e-9) per = 1e-9; n = int(0.2 / per); if (n < 20) n = 20; if (n > 5000000) n = 5000000; print n }')"
    case "$t" in spawn_*) if [ "$n" -gt 300 ]; then n=300; fi ;; esac

    # a configuration that fails (for example an old hook that cannot start scripts) is reported, not fatal
    for ((i = 0; i < ncfg; i++)); do : >"$WORK/s.$i"; failed[i]=0; done
    for ((rep = 0; rep < REPS; rep++)); do
        for ((k = 0; k < ncfg; k++)); do
            i=$(((k + rep) % ncfg))
            if [ "$i" -lt "$first" ] || [ "${failed[$i]}" -eq 1 ]; then continue; fi
            if secs="$(run_one "$i" "$t" "$n")"; then
                echo "$secs" >>"$WORK/s.$i"
            else
                failed[i]=1
            fi
        done
    done

    printf '%-18s' "$t"
    base=""
    for ((i = 0; i < ncfg; i++)); do
        if [ "$i" -lt "$first" ]; then
            printf ' | %-22s' "n/a"
            continue
        fi
        if [ "${failed[$i]}" -eq 1 ]; then
            printf ' | %-22s' "fails"
            continue
        fi
        med="$(median <"$WORK/s.$i")"
        cell="$(awk -v s="$med" -v n="$n" -v base="$base" 'BEGIN {
            ns = s / n * 1e9; unit = "ns"
            if (ns >= 20000) { ns /= 1000; unit = "us" }
            if (base == "") printf "%.1f %s", ns, unit
            else printf "%.1f %s (%+.0f%%)", ns, unit, 100 * (s / base - 1)
        }')"
        if [ -z "$base" ]; then base="$med"; fi
        printf ' | %-22s' "$cell"
    done
    printf '\n'
done
