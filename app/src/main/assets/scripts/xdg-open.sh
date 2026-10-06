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

# 1. Try bash /dev/tcp if bash is available
if [ -x /bin/bash ] || [ -x /usr/bin/bash ]; then
    BASH_BIN="$([ -x /bin/bash ] && echo /bin/bash || echo /usr/bin/bash)"
    if "$BASH_BIN" -c "exec 3<>/dev/tcp/127.0.0.1/4715 && printf 'OPEN %s\n' \"$1\" >&3 && exec 3<&- && exec 3>&-" _ "$TARGET" 2>/dev/null; then
        exit 0
    fi
fi

# 2. Try netcat (nc)
if command -v nc >/dev/null 2>&1; then
    if printf "OPEN %s\n" "$TARGET" | nc -w 2 127.0.0.1 4715 >/dev/null 2>&1; then
        exit 0
    fi
fi

# 3. Try curl (HTTP GET /open?url=...)
if command -v curl >/dev/null 2>&1; then
    if curl -s -m 2 -G "http://127.0.0.1/4715/open" --data-urlencode "url=$TARGET" >/dev/null 2>&1; then
        exit 0
    fi
fi

# 4. Try python3
if command -v python3 >/dev/null 2>&1; then
    if python3 -c '
import sys, socket
s = socket.socket()
s.settimeout(2.0)
s.connect(("127.0.0.1", 4715))
s.sendall(f"OPEN {sys.argv[1]}\n".encode("utf-8"))
s.close()
' "$TARGET" 2>/dev/null; then
        exit 0
    fi
fi

# 5. Try python (if python2 or aliased)
if command -v python >/dev/null 2>&1; then
    if python -c '
import sys, socket
s = socket.socket()
s.settimeout(2.0)
s.connect(("127.0.0.1", 4715))
s.sendall(b"OPEN " + sys.argv[1].encode("utf-8") + b"\n")
s.close()
' "$TARGET" 2>/dev/null; then
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
