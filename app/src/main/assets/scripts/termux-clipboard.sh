#!/bin/sh
# termux-clipboard-get and termux-clipboard-set: the Android clipboard through cortex-api, by the
# names programs look for (the clipboard providers of Neovim and Helix know Termux's commands, so "+y
# and "+p reach Android's clipboard). Without TEXT, termux-clipboard-set takes standard input.
case "${0##*/}" in
    termux-clipboard-get) exec cortex-api clipboard-get ;;
    *) exec cortex-api clipboard-set "$@" ;;
esac
