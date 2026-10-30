#!/bin/sh
# termux-clipboard-get and termux-clipboard-set: the Android clipboard through cortex-api, by the
# names programs look for (the clipboard providers of Neovim and Helix know Termux's commands, so "+y
# and "+p reach Android's clipboard). Without TEXT, termux-clipboard-set takes standard input.
#
# Programs call these as they start and wait for them (micro does before it draws anything), so a
# call that Cortex does not answer must end: it gets CORTEX_API_TIMEOUT seconds (2), then fails, and
# for the next ten seconds the calls fail at once instead of each waiting as long (#43).
name=${0##*/}
wait=${CORTEX_API_TIMEOUT:-2}
down=${TMPDIR:-/tmp}/.cortex-clipboard-down

if [ -s "$down" ]; then
    since=$(cat "$down" 2>/dev/null)
    case $since in '' | *[!0-9]*) since=0 ;; esac
    if [ $(($(date +%s) - since)) -lt 10 ]; then
        echo "$name: Cortex did not answer a moment ago (the app has to be on screen)" >&2
        exit 1
    fi
fi

case $name in
    termux-clipboard-get) set -- clipboard-get ;;
    *) set -- clipboard-set "$@" ;;
esac

if command -v timeout >/dev/null 2>&1; then
    # --foreground: the program stays in the terminal's foreground group (it is not stopped by SIGTTIN)
    timeout --foreground -k 1 "$wait" cortex-api "$@"
else
    cortex-api "$@"
fi
status=$?
if [ "$status" = 124 ] || [ "$status" = 137 ]; then
    date +%s > "$down" 2>/dev/null
    echo "$name: Cortex did not answer in $wait seconds (the app has to be on screen)" >&2
    status=1
fi
exit "$status"
