#!/bin/bash
# notify-send for Cortex: shows the notification on Android through cortex-api (there is no desktop
# notification daemon to talk to). Accepts the usual options and ignores the ones Android has no
# use for.
set -u

while [ "$#" -gt 0 ]; do
    case "$1" in
        -u|--urgency|-t|--expire-time|-i|--icon|-a|--app-name|-c|--category|-h|--hint|-r|--replace-id|-A|--action)
            shift
            [ "$#" -gt 0 ] && shift
            ;;
        --*=*|-e|--transient|-p|--print-id|-w|--wait) shift ;;
        -\?|--help)
            echo "Usage: notify-send [OPTION...] SUMMARY [BODY]"
            exit 0
            ;;
        --) shift; break ;;
        -*) shift ;;
        *) break ;;
    esac
done

if [ "$#" -eq 0 ] || [ -z "$1" ]; then
    echo "notify-send: no summary specified" >&2
    exit 1
fi

exec cortex-api notification -t "$1" -c "${2:-}" >/dev/null
