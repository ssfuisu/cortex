#!/bin/bash
# Compares the start-up figures of an emulator run (timings.env, written by run-emulator-test.sh)
# with the runs before it and adds them to the history:
#
#     timing-trend.sh TIMINGS_ENV HISTORY_CSV [LABEL]
#
# Prints a Markdown table: this run, the median of the last 10 runs and the change. A figure more
# than 50% above that median gets a GitHub warning. The emulator's speed differs a lot from runner
# to runner (twice as slow is common), so a slow run is a hint to look, not a failure: exit 0.
set -u
env_file="$1"
history="$2"
label="${3:-$(date -u +%Y-%m-%dT%H:%MZ)}"
keys="first_start_ms extract_ms install_ms restart_ms maintenance_ms update_start_ms update_ms session_start_us"

value_of() { sed -n "s/^$1=\\([0-9]*\\)$/\\1/p" "$env_file" | head -n 1; }

if [ ! -s "$history" ]; then
    mkdir -p "$(dirname "$history")"
    echo "label,$(echo $keys | tr ' ' ',')" > "$history"
fi

echo "| figure | this run | median of the last runs | change |"
echo "|---|---|---|---|"
col=2
for key in $keys; do
    now="$(value_of "$key")"
    median="$(tail -n +2 "$history" | cut -d, -f"$col" | grep -E '^[0-9]+$' | tail -n 10 | sort -n |
        awk '{ v[NR] = $1 } END { if (NR == 0) exit; if (NR % 2) print v[(NR + 1) / 2]; else print int((v[NR / 2] + v[NR / 2 + 1]) / 2) }')"
    change=""
    if [ -n "$now" ] && [ -n "$median" ] && [ "$median" -gt 0 ]; then
        change="$(awk -v n="$now" -v m="$median" 'BEGIN { printf "%+.0f%%", 100 * (n / m - 1) }')"
        if [ "$((now * 2))" -gt "$((median * 3))" ]; then
            echo "::warning::$key is $now, more than 50% above the median of the last runs ($median)" >&2
        fi
    fi
    echo "| $key | ${now:--} | ${median:--} | ${change:--} |"
    col=$((col + 1))
done

row="$label"
for key in $keys; do row="$row,$(value_of "$key")"; done
echo "$row" >> "$history"
