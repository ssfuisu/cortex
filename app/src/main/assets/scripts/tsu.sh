#!/bin/sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# su tells tsu, sudo and root apart by this name (they differ in root mode)
CORTEX_INVOKED_AS="$(basename "$0")"
export CORTEX_INVOKED_AS
if [ -x "$SCRIPT_DIR/su" ]; then
    exec "$SCRIPT_DIR/su" "$@"
elif [ -x /usr/local/bin/su ]; then
    exec /usr/local/bin/su "$@"
else
    exec su "$@"
fi
