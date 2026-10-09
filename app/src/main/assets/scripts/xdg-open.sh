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
    TOKEN_FILE="${CORTEX_ROOT:-/data/user/0/org.cortex.terminal/files/cortex}/../cortex_url_token"
    if [ -r "$TOKEN_FILE" ]; then
        IFS= read -r TOKEN < "$TOKEN_FILE" 2>/dev/null || true
        TOKEN="${TOKEN%%[[:space:]]*}"
    fi
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
    if "$BASH_BIN" -c '
        port="$1"
        token="$2"
        target="$3"
        exec 3<>"/dev/tcp/127.0.0.1/$port" || exit 1
        if [ -n "$token" ]; then
            printf "OPEN %s %s\n" "$token" "$target" >&3 || exit 1
        else
            printf "OPEN %s\n" "$target" >&3 || exit 1
        fi
        IFS= read -r resp <&3 || true
        exec 3<&-
        exec 3>&-
        case "$resp" in
            OK*) exit 0 ;;
            *) exit 1 ;;
        esac
    ' _ "$PORT" "$TOKEN" "$TARGET" 2>/dev/null; then
        exit 0
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
    esac
fi

# 3. Try curl (HTTP GET /open?url=...)
if command -v curl >/dev/null 2>&1; then
    if [ -n "$TOKEN" ]; then
        if curl -fsS -m 2 -G "http://127.0.0.1:${PORT}/open" \
            -H "Authorization: Bearer $TOKEN" \
            --data-urlencode "url=$TARGET" >/dev/null 2>&1; then
            exit 0
        fi
    else
        if curl -fsS -m 2 -G "http://127.0.0.1:${PORT}/open" \
            --data-urlencode "url=$TARGET" >/dev/null 2>&1; then
            exit 0
        fi
    fi
fi

# 4. Try python3
if command -v python3 >/dev/null 2>&1; then
    if python3 -c '
import sys, socket
port = int(sys.argv[1])
token = sys.argv[2]
target = sys.argv[3]
payload = ("OPEN " + token + " " + target + "\n") if token else ("OPEN " + target + "\n")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(2.0)
s.connect(("127.0.0.1", port))
s.sendall(payload.encode("utf-8"))
resp = s.recv(1024).decode("utf-8", "replace")
s.close()
sys.exit(0 if resp.startswith("OK") else 1)
' "$PORT" "$TOKEN" "$TARGET" 2>/dev/null; then
        exit 0
    fi
fi

# 5. Try python (if python2 or aliased)
if command -v python >/dev/null 2>&1; then
    if python -c '
import sys, socket
port = int(sys.argv[1])
token = sys.argv[2]
target = sys.argv[3]
payload = ("OPEN " + token + " " + target + "\n") if token else ("OPEN " + target + "\n")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(2.0)
s.connect(("127.0.0.1", port))
s.sendall(payload.encode("utf-8"))
resp = s.recv(1024).decode("utf-8", "replace")
s.close()
sys.exit(0 if resp.startswith("OK") else 1)
' "$PORT" "$TOKEN" "$TARGET" 2>/dev/null; then
        exit 0
    fi
fi

# 6. Fallback to Android am command
for am_path in /system/bin/am /system/xbin/am; do
    if [ -x "$am_path" ]; then
        if env -u LD_PRELOAD -u LD_LIBRARY_PATH "$am_path" start -a android.intent.action.VIEW -d "$TARGET" >/dev/null 2>&1; then
            exit 0
        fi
    fi
done

echo "xdg-open: Unable to open browser for: $TARGET" >&2
exit 1
