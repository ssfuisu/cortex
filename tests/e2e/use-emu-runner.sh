#!/system/bin/sh
# Run as root on the emulator by run-emulator-test.sh after the app is installed: makes ARM
# programs start through cortex-emu-runner (emu-runner.c), which keeps Cortex's LD_PRELOAD and
# LD_LIBRARY_PATH away from the emulator's ARM translator.
#
#     use-emu-runner.sh PACKAGE BUILT_RUNNER [arm64|arm]
PKG="$1"
NAME="${3:-arm64}"
DATA="/data/data/$PKG"
RUNNER="$DATA/cortex-emu-runner"
BINFMT=/proc/sys/fs/binfmt_misc
set -e

# In the app's own directory, with its owner and SELinux label, so that its processes may run it.
uid=$(stat -c %u "$DATA")
label=$(ls -Zd "$DATA" | awk '{ print $1 }')
cp "$2" "$RUNNER"
chown "$uid:$uid" "$RUNNER"
chmod 755 "$RUNNER"
chcon "$label" "$RUNNER"

# ELF class (64 or 32-bit) and machine (EM_AARCH64 or EM_ARM) of the image's own entries
case "$NAME" in
    arm64) class=02 machine=b7 ;;
    arm) class=01 machine=28 ;;
    *) echo "unknown kind $NAME" >&2; exit 2 ;;
esac
for kind in exe dyn; do
    case "$kind" in exe) type=02 ;; dyn) type=03 ;; esac
    [ -e "$BINFMT/${NAME}_$kind" ] && echo -1 > "$BINFMT/${NAME}_$kind"
    # ELF, little-endian, version 1, ET_EXEC or ET_DYN
    printf '%s' ":${NAME}_$kind:M::\\x7fELF\\x$class\\x01\\x01\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x$type\\x00\\x$machine::$RUNNER:P" > "$BINFMT/register"
    echo "--- ${NAME}_$kind"
    cat "$BINFMT/${NAME}_$kind"
done
