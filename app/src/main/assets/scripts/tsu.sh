#!/bin/sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$SCRIPT_DIR/su" ]; then
    exec "$SCRIPT_DIR/su" "$@"
elif [ -x /usr/local/bin/su ]; then
    exec /usr/local/bin/su "$@"
else
    exec su "$@"
fi
