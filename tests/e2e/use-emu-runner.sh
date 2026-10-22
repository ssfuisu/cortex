#!/system/bin/sh
# Run as root on the emulator by run-emulator-test.sh after the app is installed: makes arm64
# programs start through cortex-emu-runner (emu-runner.c), which keeps Cortex's LD_PRELOAD and
# LD_LIBRARY_PATH away from the emulator's ARM translator.
#
#     use-emu-runner.sh PACKAGE BUILT_RUNNER
PKG="$1"
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

for kind in exe dyn; do
    case "$kind" in exe) type=02 ;; dyn) type=03 ;; esac
    [ -e "$BINFMT/arm64_$kind" ] && echo -1 > "$BINFMT/arm64_$kind"
    # ELF, 64-bit, little-endian, version 1, ET_EXEC or ET_DYN, EM_AARCH64 (the image's own magic)
    printf '%s' ":arm64_$kind:M::\\x7fELF\\x02\\x01\\x01\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x$type\\x00\\xb7::$RUNNER:P" > "$BINFMT/register"
    echo "--- arm64_$kind"
    cat "$BINFMT/arm64_$kind"
done
