#!/bin/sh
# Wrapper invoking su.sh while preserving the called name (`sudo`, `su`, `tsu`, `android-su`, `root`).
export CORTEX_INVOKED_AS="${0##*/}"
SELF_DIR="${0%/*}"
if [ "$SELF_DIR" != "$0" ] && [ -x "$SELF_DIR/su" ]; then
    exec "$SELF_DIR/su" "$@"
fi
exec /usr/local/bin/su "$@"
