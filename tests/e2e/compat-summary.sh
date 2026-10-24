#!/bin/bash
# The table of the package compatibility run (compat.sh's results.txt) for the job summary.
#
#     compat-summary.sh RESULTS [VERSION]
set -u
RESULTS="$1"
VERSION="${2:-}"
[ -f "$RESULTS" ] || { echo "No results ($RESULTS is missing)."; exit 0; }

passed=$(grep -c '^PASS' "$RESULTS")
failed=$(grep -c '^FAIL' "$RESULTS")
echo "### Packages on Cortex${VERSION:+ $VERSION} (API 34 emulator, arm64-v8a)"
echo
echo "$passed of $((passed + failed)) work."
echo
echo "| Package | Works | Time | What went wrong |"
echo "|---|---|---|---|"
while IFS= read -r line; do
    case "$line" in
        PASS\ *) state="✅"; rest="${line#PASS }"; why="" ;;
        FAIL\ *) state="❌"; rest="${line#FAIL }"; why="${rest#*: }"; rest="${rest%%: *}" ;;
        *) continue ;;
    esac
    name="${rest%% (*}"
    took="${rest#* (}"
    took="${took%)*}"
    # Table cells cannot hold | or line breaks
    why="$(printf '%s' "$why" | tr '|\r\n' '/  ' | cut -c1-200)"
    echo "| $name | $state | $took | $why |"
done < "$RESULTS"
