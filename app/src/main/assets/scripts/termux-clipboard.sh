#!/bin/sh
# termux-clipboard-get and termux-clipboard-set: the Android clipboard through cortex-api, by the
# names programs look for (the clipboard providers of Neovim and Helix know Termux's commands, so "+y
# and "+p reach Android's clipboard). Without TEXT, termux-clipboard-set takes standard input.
#
# Programs call these as they start and wait for them (micro does before it draws anything), so a
# call that Cortex does not answer must end: it gets CORTEX_API_TIMEOUT seconds (2), then fails, and
# for the next ten seconds the calls fail at once instead of each waiting as long (#43). cortex-api
# keeps the time; this script only starts it (with exec: no process of its own to wait for it).
CORTEX_API_DEADLINE=${CORTEX_API_TIMEOUT:-2}
CORTEX_API_DOWN=${TMPDIR:-/tmp}/.cortex-clipboard-down
export CORTEX_API_DEADLINE CORTEX_API_DOWN
case "${0##*/}" in
    termux-clipboard-get) exec cortex-api clipboard-get ;;
    *) exec cortex-api clipboard-set "$@" ;;
esac
