#!/bin/sh
# Cortex URL & Browser Opener for Android Chrome / Default Browser
if [ -z "$1" ]; then
    echo "Usage: xdg-open <url>" >&2
    exit 1
fi

TARGET=""
for arg in "$@"; do
    case "$arg" in
        http://*|https://*|ftp://*|file://*)
            TARGET="$arg"
            break
            ;;
        --*|-*)
            ;;
        *)
            if [ -z "$TARGET" ]; then
                TARGET="$arg"
            fi
            ;;
    esac
done

if [ -z "$TARGET" ]; then
    TARGET="$1"
fi

PORT="${CORTEX_URL_PORT:-4715}"
case "$PORT" in
    ''|*[!0-9]*)
        PORT=4715
        ;;
esac

TOKEN="${CORTEX_URL_TOKEN:-}"
if [ -z "$TOKEN" ]; then
    for cand in \
        /etc/cortex_url_token \
        "${CORTEX_ROOT:-}/etc/cortex_url_token" \
        "${CORTEX_ROOT:-/data/user/0/org.cortex.terminal/files/cortex}/../cortex_url_token" \
        /data/data/org.cortex.terminal/files/cortex_url_token \
        /data/user/0/org.cortex.terminal/files/cortex_url_token; do
        if [ -r "$cand" ]; then
            IFS= read -r TOKEN < "$cand" 2>/dev/null || true
            TOKEN="${TOKEN%%[[:space:]]*}"
            [ -n "$TOKEN" ] && break
        fi
    done
fi

# 1. Try bash /dev/tcp if bash is available
BASH_BIN=""
if [ -x /bin/bash ]; then
    BASH_BIN=/bin/bash
elif [ -x /usr/bin/bash ]; then
    BASH_BIN=/usr/bin/bash
elif command -v bash >/dev/null 2>&1; then
    BASH_BIN="$(command -v bash)"
fi
if [ -n "$BASH_BIN" ]; then
    "$BASH_BIN" -c '
        port="$1"
        token="$2"
        target="$3"
        exec 3<>"/dev/tcp/127.0.0.1/$port" || exit 3
        if [ -n "$token" ]; then
            printf "OPEN %s %s\n" "$token" "$target" >&3 || exit 3
        else
            printf "OPEN %s\n" "$target" >&3 || exit 3
        fi
        IFS= read -r resp <&3 || true
        exec 3<&-
        exec 3>&-
        case "$resp" in
            OK*) exit 0 ;;
            ERR*) exit 2 ;;
            *) exit 3 ;;
        esac
    ' _ "$PORT" "$TOKEN" "$TARGET" 2>/dev/null
    STATUS=$?
    if [ "$STATUS" -eq 0 ]; then
        exit 0
    elif [ "$STATUS" -eq 2 ]; then
        exit 1
    fi
fi

# 2. Try netcat (nc)
if command -v nc >/dev/null 2>&1; then
    if [ -n "$TOKEN" ]; then
        NC_RESP="$(printf 'OPEN %s %s\n' "$TOKEN" "$TARGET" | nc -w 2 127.0.0.1 "$PORT" 2>/dev/null || true)"
    else
        NC_RESP="$(printf 'OPEN %s\n' "$TARGET" | nc -w 2 127.0.0.1 "$PORT" 2>/dev/null || true)"
    fi
    case "$NC_RESP" in
        OK*) exit 0 ;;
        ERR*) exit 1 ;;
    esac
fi

# 3. Try curl (HTTP GET /open?url=...)
if command -v curl >/dev/null 2>&1; then
    if [ -n "$TOKEN" ]; then
        CURL_RESP="$(curl -s -m 2 -G "http://127.0.0.1:${PORT}/open" \
            -H "Authorization: Bearer $TOKEN" \
            --data-urlencode "url=$TARGET" 2>/dev/null || true)"
    else
        CURL_RESP="$(curl -s -m 2 -G "http://127.0.0.1:${PORT}/open" \
            --data-urlencode "url=$TARGET" 2>/dev/null || true)"
    fi
    case "$CURL_RESP" in
        OK*) exit 0 ;;
        ERR*) exit 1 ;;
    esac
fi

# 4. Try python3
if command -v python3 >/dev/null 2>&1; then
    python3 -c '
import sys, socket
port = int(sys.argv[1])
token = sys.argv[2]
target = sys.argv[3]
payload = ("OPEN " + token + " " + target + "\n") if token else ("OPEN " + target + "\n")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(2.0)
try:
    s.connect(("127.0.0.1", port))
except Exception:
    sys.exit(3)
s.sendall(payload.encode("utf-8"))
resp = s.recv(1024).decode("utf-8", "replace")
s.close()
if resp.startswith("OK"):
    sys.exit(0)
elif resp.startswith("ERR"):
    sys.exit(2)
else:
    sys.exit(3)
' "$PORT" "$TOKEN" "$TARGET" 2>/dev/null
    STATUS=$?
    if [ "$STATUS" -eq 0 ]; then
        exit 0
    elif [ "$STATUS" -eq 2 ]; then
        exit 1
    fi
fi

# 5. Try python (if python2 or aliased)
if command -v python >/dev/null 2>&1; then
    python -c '
import sys, socket
port = int(sys.argv[1])
token = sys.argv[2]
target = sys.argv[3]
payload = ("OPEN " + token + " " + target + "\n") if token else ("OPEN " + target + "\n")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(2.0)
try:
    s.connect(("127.0.0.1", port))
except Exception:
    sys.exit(3)
s.sendall(payload.encode("utf-8"))
resp = s.recv(1024).decode("utf-8", "replace")
s.close()
if resp.startswith("OK"):
    sys.exit(0)
elif resp.startswith("ERR"):
    sys.exit(2)
else:
    sys.exit(3)
' "$PORT" "$TOKEN" "$TARGET" 2>/dev/null
    STATUS=$?
    if [ "$STATUS" -eq 0 ]; then
        exit 0
    elif [ "$STATUS" -eq 2 ]; then
        exit 1
    fi
fi

# 6. Fallback to Android am / cmd commands and Termux receiver
for am_path in /system/bin/am /system/xbin/am; do
    if [ -x "$am_path" ]; then
        if env -u LD_PRELOAD -u LD_LIBRARY_PATH "$am_path" start -a android.intent.action.VIEW -d "$TARGET" >/dev/null 2>&1; then
            exit 0
        fi
        if env -u LD_PRELOAD -u LD_LIBRARY_PATH "$am_path" start --user 0 -a android.intent.action.VIEW -d "$TARGET" >/dev/null 2>&1; then
            exit 0
        fi
        if env -u LD_PRELOAD -u LD_LIBRARY_PATH "$am_path" broadcast --user 0 -a android.intent.action.VIEW -n "com.termux/com.termux.app.TermuxOpenReceiver" -d "$TARGET" >/dev/null 2>&1; then
            exit 0
        fi
    fi
done

if [ -x /system/bin/cmd ]; then
    if env -u LD_PRELOAD -u LD_LIBRARY_PATH /system/bin/cmd activity start --user 0 -a android.intent.action.VIEW -d "$TARGET" >/dev/null 2>&1; then
        exit 0
    fi
fi

echo "xdg-open: Unable to open browser for: $TARGET" >&2
exit 1
