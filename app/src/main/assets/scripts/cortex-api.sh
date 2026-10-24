#!/bin/bash
# cortex-api: Android features from Cortex's shell (notifications, clipboard, opening links, ...).
#
# The app listens on 127.0.0.1 while it runs; the port and a secret token are in /run/cortex-api,
# which only the app's user and root can read. Works in normal and in root mode.
set -u

CONF="${CORTEX_API_CONF:-/run/cortex-api}"

usage() {
    cat <<'EOF'
Usage: cortex-api COMMAND [OPTIONS]

  battery                        battery level and state (JSON)
  clipboard-get                  print the clipboard
  clipboard-set [TEXT]           set the clipboard to TEXT (or stdin)
  info                           device and app information (JSON)
  mirror [show|auto|official]    the Ubuntu mirror apt uses; auto measures them and takes
                                 the fastest current one (security updates stay official)
  network                        the phone's addresses (interface and address per line,
                                 Wi-Fi first)
  notification [-t TITLE] [-c TEXT] [-i ID]
                                 show a notification (TEXT from stdin without -c);
                                 prints its ID; showing the same ID again replaces it
  notification-remove ID         remove a notification
  open URL|FILE                  open a link or a file in an Android app
  share [-t TITLE] [-f FILE | TEXT]
                                 share text (or stdin) or a file with an Android app
  toast [-l] [TEXT]              show a short message (TEXT or stdin; -l: longer)
  tts [TEXT]                     speak TEXT (or stdin)
  vibrate [-d MS]                vibrate for MS milliseconds (default 300)
  wakelock on|off|status         keep the CPU awake while Cortex runs

Opening links and files and reading the clipboard only work while Cortex is on screen.
EOF
}

die() {
    echo "cortex-api: $*" >&2
    exit 1
}

if [ "$#" -eq 0 ]; then
    usage >&2
    exit 1
fi
case "$1" in
    -h|--help|help) usage; exit 0 ;;
esac
cmd="$1"
shift

# Files are sent as absolute paths; the app maps them to Android's view of the rootfs.
abspath() {
    case "$1" in
        /*) printf '%s' "$1" ;;
        *) printf '%s/%s' "$PWD" "$1" ;;
    esac
}

is_uri() {
    [[ "$1" =~ ^[a-zA-Z][a-zA-Z0-9+.-]*:// ]] && return 0
    case "$1" in mailto:*|tel:*|sms:*|geo:*|market:*|intent:*) return 0 ;; esac
    return 1
}

args=()
has_text=0      # whether the text is given as an argument (else it can come from stdin)
case "$cmd" in
    open)
        for a in "$@"; do
            if is_uri "$a"; then args+=("$a"); else args+=("$(abspath "$a")"); fi
        done
        ;;
    share)
        while [ "$#" -gt 0 ]; do
            case "$1" in
                -f|--file) [ "$#" -ge 2 ] || die "$1 needs a file"; args+=(-f "$(abspath "$2")"); has_text=1; shift 2 ;;
                -t|--title) [ "$#" -ge 2 ] || die "$1 needs a value"; args+=("$1" "$2"); shift 2 ;;
                *) args+=("$1"); has_text=1; shift ;;
            esac
        done
        ;;
    notification)
        while [ "$#" -gt 0 ]; do
            case "$1" in
                -c|--content) [ "$#" -ge 2 ] || die "$1 needs a value"; args+=("$1" "$2"); has_text=1; shift 2 ;;
                --content=*) args+=("$1"); has_text=1; shift ;;
                -t|--title|-i|--id) [ "$#" -ge 2 ] || die "$1 needs a value"; args+=("$1" "$2"); shift 2 ;;
                *) args+=("$1"); shift ;;
            esac
        done
        ;;
    clipboard-set|toast|tts)
        for a in "$@"; do
            case "$a" in -l|--long|-s|--short) ;; *) has_text=1 ;; esac
            args+=("$a")
        done
        ;;
    *)
        args=("$@")
        has_text=1
        ;;
esac

send_stdin=0
stdin_text=""
case "$cmd" in
    clipboard-set|toast|tts|share|notification)
        if [ "$has_text" = 0 ] && [ ! -t 0 ]; then
            IFS= read -r -d '' stdin_text || true
            send_stdin=1
        fi
        ;;
esac

[ -r "$CONF" ] || die "Cortex is not running (or is older than this tool): open the Cortex app"
read -r port token < "$CONF" || die "cannot read $CONF"
case "$port" in ''|*[!0-9]*) die "$CONF is damaged: restart the Cortex app" ;; esac

# Writes the byte length of the UTF-8 text (the app reads exactly that many bytes), a newline and
# the text. No subshell: the app gives the whole request 10 seconds once connected.
send_block() {
    local LC_ALL=C
    printf '%s\n%s' "${#1}" "$1"
}

# (The braces keep 2>/dev/null to the connection attempt: on a bare exec it would stay.) On a free
# descriptor that bash picks, not a fixed one: an open one would be closed, and the ARM translator
# of x86 devices keeps its own memory file at 3 (it aborts when it needs it next).
{ exec {api}<>"/dev/tcp/127.0.0.1/$port"; } 2>/dev/null || die "Cortex does not answer: open the Cortex app"
{
    printf 'CORTEX-API 1\n%s\n%s\n%s\n' "$token" "$cmd" "${#args[@]}"
    for a in ${args[@]+"${args[@]}"}; do
        send_block "$a"
    done
    if [ "$send_stdin" = 1 ]; then
        send_block "$stdin_text"
    else
        printf -- '-1\n'
    fi
} >&"$api"

IFS= read -r status <&"$api" || die "no answer from Cortex"
if [ "$status" = 0 ]; then
    cat <&"$api"
else
    cat <&"$api" >&2
fi
exec {api}<&-
case "$status" in ''|*[!0-9]*) exit 1 ;; esac
exit "$status"
