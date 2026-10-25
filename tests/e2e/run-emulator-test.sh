#!/bin/bash
# End-to-end test of Cortex on an Android emulator, as a user would use it: installs the APK,
# starts the app, waits for Ubuntu to be unpacked, types a command into the terminal that runs
# tests/e2e/in-terminal.sh, and collects its results, logcat and screenshots.
#
#     run-emulator-test.sh APK_DIR OUT_DIR [ABI]
#
# ABI is the APK to test: arm64-v8a (the default) or armeabi-v7a. Needs adb, gcc and a running
# emulator with adb root (a "google_apis" image).
#
# Environment:
#   E2E_SCRIPT=FILE        the script typed into the terminal (default tests/e2e/in-terminal.sh)
#   E2E_MODE=compat        only that script's results (the package compatibility run, compat.sh):
#                          no screenshots, restart or update, and failed checks are its results
#   E2E_TIMEOUT=SECONDS    how long the script may take (default 3000)
#   E2E_PAGE_SIZE=BYTES    the page size the emulator must have (the 16 KB image)
#   E2E_SKIP_WITHOUT_ABI=1 an image that cannot run the ABI's apps is a skip, not a failure
#   E2E_NO_APT=1           the checks that install packages (apt, the SSH server) are left out
#   E2E_GO_PROGRAM=FILE    the static arm64 Go program of tests/go/gotest, for the checks of Go programs
#
# The app's ARM Ubuntu runs on an x86_64 emulator through the image's ARM translation, which must also run ARM programs started from a shell
# (binfmt_misc). They are started through emu-runner.c, which keeps Cortex's LD_PRELOAD and
# LD_LIBRARY_PATH away from the translator (see there).
set -u

APK_DIR="$1"
OUT="$2"
ABI="${3:-arm64-v8a}"
# RUN_UBUNTU: whether the emulator's translator runs Ubuntu's programs. Android 11's translator of
# 32-bit ARM code (the only one there is) stops every program of Ubuntu's armhf glibc that sets a
# signal handler, bash first of all ("Unknown Thumb sa_restorer in guest sigaction!": glibc's signal
# return code is Thumb code it does not know). Phones run the code themselves. On the emulator the
# 32-bit APK is tested as far as an installed and set-up Ubuntu (check_unpacked_system).
case "$ABI" in
    arm64-v8a) BINFMT_NAME=arm64 DPKG_ARCH=arm64 RUN_UBUNTU=1 ;;
    armeabi-v7a) BINFMT_NAME=arm DPKG_ARCH=armhf RUN_UBUNTU=0 ;;
    *) echo "unknown ABI $ABI" >&2; exit 2 ;;
esac
DEVICE_OUT=/sdcard/Download/cortex-e2e
E2E_SCRIPT="${E2E_SCRIPT:-tests/e2e/in-terminal.sh}"
E2E_MODE="${E2E_MODE:-full}"
E2E_TIMEOUT="${E2E_TIMEOUT:-3000}"
mkdir -p "$OUT/screens"

log() { echo "== $*"; }
die() {
    echo "::error::$*"
    shot "failure"
    diagnose
    exit 1
}
diagnosed=0
diagnose() { # once: the app's processes, and Ubuntu's bash started directly (tests/e2e/diagnose.sh)
    [ "$diagnosed" = 0 ] && [ -n "${PKG:-}" ] || return 0
    diagnosed=1
    log "Diagnostics"
    adb push tests/e2e/diagnose.sh /data/local/tmp/cortex-diagnose.sh >/dev/null &&
        sh_ sh /data/local/tmp/cortex-diagnose.sh "$PKG" 2>&1 | tee "$OUT/diagnose.txt"
    timeout 120 adb pull /data/local/tmp/cortex-strace.txt "$OUT/strace-direct.txt" >/dev/null 2>&1
    timeout 120 adb pull /data/local/tmp/cortex-strace2.txt "$OUT/strace-fork.txt" >/dev/null 2>&1
}
# Each adb command has a time limit: one that hangs (a slow emulator, adbd stuck) would otherwise
# hold the waiting loops, and E2E_TIMEOUT, forever
sh_() { timeout 300 adb shell "$@" | tr -d '\r'; }  # the output of a command on the device
run_() { timeout 60 adb shell "$@" >/dev/null 2>&1; } # only whether it succeeded
shot() { timeout 60 adb exec-out screencap -p > "$OUT/screens/$1.png" 2>/dev/null || rm -f "$OUT/screens/$1.png"; }
tap_center() { sh_ input tap $(($1 / 2)) $(($2 / 2)); }

# Types TEXT into the focused view, as from a hardware keyboard, then Enter. (adb runs the command
# through the device's shell, so the text is quoted for it; "input text" takes %s for a space.)
type_line() {
    local q
    q=$(printf '%s' "$1" | sed -e 's/ /%s/g' -e "s/'/'\\\\''/g")
    timeout 60 adb shell "input text '$q'"
    timeout 60 adb shell input keyevent 66
}

# Taps the view with this resource id (of the app), found in the window's accessibility dump. The
# dump fails now and then (it waits for the screen to be idle, and the cursor blinks): three tries.
tap_id() {
    local bounds try
    for try in 1 2 3; do
        sh_ rm -f /sdcard/window.xml
        sh_ uiautomator dump /sdcard/window.xml >/dev/null
        bounds=$(sh_ cat /sdcard/window.xml 2>/dev/null | grep -o "resource-id=\"[^\"]*:id/$1\"[^>]*bounds=\"[^\"]*\"" |
            sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/' | head -n 1)
        [ -n "$bounds" ] && break
        sleep 2
    done
    [ -n "$bounds" ] || return 1
    set -- $bounds
    sh_ input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}

wait_for() { # wait_for SECONDS COMMAND...: retries COMMAND every 5 s until it succeeds
    local deadline=$((SECONDS + $1))
    shift
    until "$@"; do
        [ "$SECONDS" -lt "$deadline" ] || return 1
        sleep 5
    done
}

pass_or_fail() { # pass_or_fail DESCRIPTION COMMAND...: a PASS or FAIL line; a failure sets status
    local what="$1"
    shift
    if "$@"; then
        echo "PASS $what"
    else
        echo "FAIL $what"
        status=1
    fi
}
version_is() { [ "$(sh_ cat "$DATA/files/cortex/.cortex_version")" = "$1" ]; }
app_runs() { [ -n "$(sh_ pidof "$PKG")" ]; }
app_is_32bit() { sh_ "readlink /proc/\$(pidof -s $PKG)/exe" | grep -q app_process32; }

# ELF_FILE ELF_CLASS MACHINE: the file is an ELF file of that class (01: 32-bit) and machine (28: ARM)
elf_is() {
    set -- "$1" "$2" "$3" $(sh_ "od -An -tx1 -N20 $1" | tr -s ' \n' ' ')
    [ "$4$5$6$7" = 7f454c46 ] && [ "$8" = "$2" ] && [ "${22}" = "$3" ]
}

# The 32-bit APK, where the emulator does not run Ubuntu's programs (see RUN_UBUNTU): the app's
# process is a 32-bit one, and the unpacked and set-up system is the armhf one.
check_unpacked_system() {
    local root="$DATA/files/cortex" f
    pass_or_fail "the app runs as a 32-bit process" app_is_32bit
    pass_or_fail "bash is a 32-bit ARM program" elf_is "$root/usr/bin/bash" 01 28
    pass_or_fail "the hook library is the 32-bit ARM one" elf_is "$root/usr/lib/libcortex-hook.so" 01 28
    for f in usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3 usr/lib/arm-linux-gnueabihf/libc.so.6 \
        usr/local/bin/apt etc/resolv.conf etc/passwd var/lib/dpkg/status home/.bashrc; do
        pass_or_fail "/$f is set up" run_ test -e "$root/$f"
    done
    pass_or_fail "the packages are armhf ones" run_ "grep -q '^Architecture: armhf' $root/var/lib/dpkg/status"
}

log "Emulator"
adb wait-for-device
sh_ getprop | grep -E 'ro.product.cpu.abilist|native.bridge|ro.build.version.(sdk|release)|ro.product.model' | tee "$OUT/device.txt"
adb root >/dev/null 2>&1
sleep 3
adb wait-for-device
[ "$(sh_ id -u)" = 0 ] || die "adb root is not available on this image"
# Buttons instead of gestures, so that a swipe from the left edge opens Cortex's drawer instead of
# going back; and no on-screen keyboard (the test types as a hardware keyboard), for screenshots.
sh_ cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
sh_ settings put secure show_ime_with_hard_keyboard 0 || true

page_size=$(sh_ getconf PAGESIZE)
echo "page size: $page_size"
if [ -n "${E2E_PAGE_SIZE:-}" ] && [ "$page_size" != "$E2E_PAGE_SIZE" ]; then
    die "the emulator has pages of $page_size bytes, not $E2E_PAGE_SIZE"
fi

if ! sh_ getprop ro.product.cpu.abilist | tr ',' '\n' | grep -qx "$ABI"; then
    if [ "${E2E_SKIP_WITHOUT_ABI:-0}" = 1 ]; then
        echo "::notice::SKIP: this image cannot run $ABI apps (no ARM translation): $(sh_ getprop ro.product.cpu.abilist)"
        [ -n "${GITHUB_STEP_SUMMARY:-}" ] &&
            echo "Skipped: this emulator image cannot run $ABI apps (it has no ARM translation)." >> "$GITHUB_STEP_SUMMARY"
        exit 0
    fi
    die "this image cannot run $ABI apps (no ARM translation): $(sh_ getprop ro.product.cpu.abilist)"
fi

# ARM programs started with exec() (bash, apt, ...) go through binfmt_misc to the translator.
binfmt=/proc/sys/fs/binfmt_misc
if ! sh_ ls "$binfmt" 2>/dev/null | grep -qx "${BINFMT_NAME}_dyn"; then
    log "Registering ARM programs with binfmt_misc"
    sh_ ls -l /system/bin | grep -iE 'ndk_translation|houdini' || true
    sh_ ls -l /system/etc/binfmt_misc 2>/dev/null || true
    sh_ setprop ro.enable.native.bridge.exec 1 2>/dev/null || true
    sh_ setprop ro.enable.native.bridge.exec64 1 2>/dev/null || true
    sleep 2
    if ! sh_ ls "$binfmt" 2>/dev/null | grep -qx "${BINFMT_NAME}_dyn"; then
        sh_ "mountpoint -q $binfmt || mount -t binfmt_misc binfmt_misc $binfmt" || true
        for f in $(sh_ ls /system/etc/binfmt_misc 2>/dev/null); do
            sh_ "cat /system/etc/binfmt_misc/$f > $binfmt/register" || true
        done
    fi
fi
sh_ ls -l "$binfmt" | tee "$OUT/binfmt_misc.txt"
for f in $(sh_ ls "$binfmt" | grep -E '^arm'); do
    echo "--- $f"
    sh_ cat "$binfmt/$f"
done | tee -a "$OUT/binfmt_misc.txt"

log "Installing the app"
APK=$(ls "$APK_DIR"/*"$ABI"*.apk 2>/dev/null | head -n 1)
[ -n "$APK" ] || die "no $ABI APK in $APK_DIR"
adb install -r -g "$APK" || die "the APK did not install"
PKG=$(sh_ pm list packages org.cortex.terminal | sed 's/^package://' | head -n 1)
[ -n "$PKG" ] || die "the app is not installed"
echo "package: $PKG"
DATA=/data/data/$PKG
# Android 11+ asks for "all files access" at start; the test grants it beforehand.
sh_ appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

log "Starting $ABI programs through cortex-emu-runner"
# The image's own runner, which cortex-emu-runner hands the programs to
translator=$(sh_ cat "/proc/sys/fs/binfmt_misc/${BINFMT_NAME}_dyn" | sed -n 's/^interpreter //p')
[ -n "$translator" ] || die "the image registers no ARM translator with binfmt_misc"
echo "translator: $translator"
# Its segments aligned for 16 KB pages too: a kernel with them refuses the 4 KB ones (EINVAL)
gcc -static -nostdlib -ffreestanding -fno-builtin -fno-tree-loop-distribute-patterns -fno-stack-protector \
    -fno-pie -no-pie -O2 -Wall -Wextra -Wl,-z,max-page-size=16384 -DRUNNER="\"$translator\"" \
    -o "$OUT/cortex-emu-runner" tests/e2e/emu-runner.c ||
    die "cannot build cortex-emu-runner"
adb push "$OUT/cortex-emu-runner" /data/local/tmp/cortex-emu-runner >/dev/null &&
    adb push tests/e2e/use-emu-runner.sh /data/local/tmp/use-emu-runner.sh >/dev/null ||
    die "cannot copy cortex-emu-runner"
sh_ sh /data/local/tmp/use-emu-runner.sh "$PKG" /data/local/tmp/cortex-emu-runner "$BINFMT_NAME" | tee -a "$OUT/binfmt_misc.txt"
sh_ cat "/proc/sys/fs/binfmt_misc/${BINFMT_NAME}_dyn" | grep -q cortex-emu-runner || die "cortex-emu-runner is not registered"
sh_ rm -rf "$DEVICE_OUT"
adb push "$E2E_SCRIPT" /sdcard/Download/cortex-e2e.sh >/dev/null || die "cannot copy the test script"
if [ -n "${E2E_GO_PROGRAM:-}" ] && [ -f "$E2E_GO_PROGRAM" ]; then
    adb push "$E2E_GO_PROGRAM" /sdcard/Download/cortex-e2e-gotest >/dev/null || log "cannot copy the Go program"
fi

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null' EXIT

log "Starting Cortex"
STARTED=$SECONDS
sh_ am start -W -n "$PKG/org.cortex.terminal.MainActivity" | tee "$OUT/first-start.txt"
sleep 20
shot "01-first-start"
wait_for 1200 run_ test -f "$DATA/files/cortex/.cortex_version" || die "Ubuntu was not unpacked within 20 minutes"
log "Ubuntu unpacked after $((SECONDS - STARTED)) s"
sleep 15
shot "02-terminal"

size=$(sh_ wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -n 1)
WIDTH=${size% *}
HEIGHT=${size#* }
status=0

if [ "$RUN_UBUNTU" = 0 ]; then
    log "The installed system (the emulator does not run $ABI programs of Ubuntu)"
    check_unpacked_system
else
log "Running the tests in the terminal"
tap_center "$WIDTH" "$HEIGHT"
sleep 2
TEST_COMMAND="CORTEX_E2E_EMULATOR=1 CORTEX_E2E_ARCH=$DPKG_ARCH bash /sdcard/Download/cortex-e2e.sh"
[ "${E2E_NO_APT:-0}" = 1 ] && TEST_COMMAND="CORTEX_E2E_NO_APT=1 $TEST_COMMAND"
type_line "$TEST_COMMAND"
if ! wait_for 90 run_ test -d "$DEVICE_OUT/started"; then
    shot "03-not-started"
    diagnose
    log "The test did not start; typing the command again"
    tap_center "$WIDTH" "$HEIGHT"
    sleep 2
    type_line "$TEST_COMMAND"
    wait_for 90 run_ test -d "$DEVICE_OUT/started" || die "the command typed into the terminal did not run"
fi
i=0
until run_ test -f "$DEVICE_OUT/done"; do
    [ $((SECONDS - STARTED)) -lt "$E2E_TIMEOUT" ] || {
        timeout 120 adb pull "$DEVICE_OUT" "$OUT/" >/dev/null 2>&1
        die "the tests did not finish in time"
    }
    sleep 30
    i=$((i + 1))
    [ $((i % 4)) = 0 ] && shot "04-progress"
done
sleep 2
shot "05-test-results"
timeout 120 adb pull "$DEVICE_OUT" "$OUT/" >/dev/null || die "cannot read the results"
log "Results"
cat "$OUT/cortex-e2e/results.txt"
cat "$OUT/cortex-e2e/done"

if [ "$E2E_MODE" = compat ]; then
    # Which packages work is the result: a failed one is not a failure of the run
    log "Took $((SECONDS - STARTED)) s"
    exit 0
fi
if grep -q '^FAIL' "$OUT/cortex-e2e/results.txt"; then
    status=1
    diagnose
fi
# in-terminal.sh's notifications, and the one about the web server it left running (the app looks
# for servers every few seconds)
sleep 8
notifications=$(sh_ dumpsys notification --noredact)
for title in "Cortex e2e notification" "Cortex e2e notify-send" "serves web pages on port 8642"; do
    if printf '%s\n' "$notifications" | grep -F -q "$title"; then
        echo "PASS the notification \"$title\" is shown"
    else
        echo "FAIL the notification \"$title\" is not shown"
        status=1
    fi
done

log "Screenshots"
# The terminal with cortex-api in use, as in the README. Each command gets time to finish, so that
# the next one is not typed into its output. The test's notifications go first (notify-send's is
# the app's first one without an ID).
run_line() {
    type_line "$1"
    sleep "${2:-4}"
}
run_line "cortex-api notification-remove e2e; cortex-api notification-remove 1; clear" 5
run_line "cortex-api battery" 5
run_line "echo 'Hello from Ubuntu' | cortex-api clipboard-set"
run_line "cortex-api clipboard-get"
run_line "notify-send 'Build finished' 'make: all targets are up to date'" 5
shot "10-cortex-api"
sh_ cmd statusbar expand-notifications
sleep 3
shot "11-notifications"
sh_ cmd statusbar collapse
sleep 2
# A second session, then the session list in the drawer
sh_ input swipe 2 $((HEIGHT / 2)) $((WIDTH * 2 / 3)) $((HEIGHT / 2)) 300
sleep 2
tap_id btnDrawerNewSession || log "no new-session button found"
sleep 8
# A directory for this session, which it is to be in again after the restart
run_line "mkdir -p ~/restore-test && cd ~/restore-test && clear"
sh_ input swipe 2 $((HEIGHT / 2)) $((WIDTH * 2 / 3)) $((HEIGHT / 2)) 300
sleep 2
shot "12-sessions"
if tap_id btnDrawerSettings; then
    sleep 3
    shot "13-settings"
    sh_ input swipe $((WIDTH / 2)) $((HEIGHT * 3 / 4)) $((WIDTH / 2)) $((HEIGHT / 4)) 500
    sleep 2
    shot "14-settings-more"
    sh_ input swipe $((WIDTH / 2)) $((HEIGHT * 3 / 4)) $((WIDTH / 2)) $((HEIGHT / 4)) 500
    sleep 2
    shot "15-settings-root-mode"
else
    log "no settings button found"
fi
fi # RUN_UBUNTU

log "Restart"
# Cortex again in a new process, as after Android stopped it: the normal start of an installed system,
# whose checks run in the background while the first session starts.
sh_ input keyevent 4
sleep 1
run_ am force-stop "$PKG"
sleep 3
sh_ am start -W -n "$PKG/org.cortex.terminal.MainActivity" | tee "$OUT/restart.txt"
sleep 10
if [ "$RUN_UBUNTU" = 1 ]; then
    tap_center "$WIDTH" "$HEIGHT"
    sleep 2
    type_line "id -u > $DEVICE_OUT/after-restart.txt; pwd > $DEVICE_OUT/after-restart-pwd.txt"
    pass_or_fail "a session works after a restart" wait_for 90 run_ test -s "$DEVICE_OUT/after-restart.txt"
    # Kept when Cortex went to the background (the settings), brought back by this start
    pass_or_fail "the sessions were kept" run_ test -s "$DATA/files/sessions"
    pass_or_fail "the session came back in its directory" wait_for 30 run_ grep -q "restore-test" "$DEVICE_OUT/after-restart-pwd.txt"
else
    pass_or_fail "the app runs after a restart" app_runs
fi
shot "16-after-restart"
sleep 20 # the background checks of the start

log "Update"
# As after installing a newer version of the app over this one: the installed system is marked as
# set up by an older version, which the next start updates in the background before the first session.
current_version=$(sh_ cat "$DATA/files/cortex/.cortex_version")
sh_ "echo 12600 > $DATA/files/cortex/.cortex_version"
run_ am force-stop "$PKG"
sleep 3
sh_ am start -W -n "$PKG/org.cortex.terminal.MainActivity" | tee "$OUT/update-start.txt"
sleep 15
if [ "$RUN_UBUNTU" = 1 ]; then
    tap_center "$WIDTH" "$HEIGHT"
    sleep 2
    type_line "id -u > $DEVICE_OUT/after-update.txt"
    pass_or_fail "a session works after an update" wait_for 120 run_ test -s "$DEVICE_OUT/after-update.txt"
fi
if wait_for 120 version_is "$current_version"; then
    echo "PASS the update marked the system as current ($current_version)"
else
    echo "FAIL the system is still marked as $(sh_ cat "$DATA/files/cortex/.cortex_version")"
    status=1
fi
shot "17-after-update"
sleep 20

log "The app's log"
# Messages of mistakes that slowed down or broke the first start once
if grep -E "Tar-Slip|Native extraction failed|home/storage/|Failed to setup which" "$OUT/logcat.txt" > "$OUT/app-errors.txt"; then
    echo "FAIL the app logged errors at start (app-errors.txt):"
    head -n 20 "$OUT/app-errors.txt"
    status=1
else
    echo "PASS no start-up errors in the app's log"
fi
if grep -A 3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PKG"; then
    echo "FAIL the app crashed:"
    grep -A 15 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 40
    status=1
else
    echo "PASS the app did not crash"
fi
{
    echo "first start:"
    grep -E "TotalTime|WaitTime" "$OUT/first-start.txt"
    echo "start of the installed app:"
    grep -E "TotalTime|WaitTime" "$OUT/restart.txt"
    echo "start after an update:"
    grep -E "TotalTime|WaitTime" "$OUT/update-start.txt"
    grep -E "Displayed $PKG|Base system (installed|updated)|Start-up maintenance took|Session process [0-9]+ started|ensureCaCertificates|Skipped [0-9]+ frames" "$OUT/logcat.txt" |
        grep -E "$PKG|BootstrapManager|MainActivity|CortexPty|Choreographer" | sed 's/^/  /'
} | tee "$OUT/timings.txt"

# The same figures for timing-trend.sh, one KEY=VALUE per line (empty when missing)
first_number() { grep -oE "$1" "$2" 2>/dev/null | head -n 1 | grep -oE '[0-9]+' | head -n 1; }
{
    echo "first_start_ms=$(first_number 'TotalTime: [0-9]+' "$OUT/first-start.txt")"
    echo "extract_ms=$(first_number 'Extracted the base system in [0-9]+' "$OUT/logcat.txt")"
    echo "install_ms=$(first_number 'Base system installed in [0-9]+' "$OUT/logcat.txt")"
    echo "restart_ms=$(first_number 'TotalTime: [0-9]+' "$OUT/restart.txt")"
    echo "maintenance_ms=$(first_number 'Start-up maintenance took [0-9]+' "$OUT/logcat.txt")"
    echo "update_start_ms=$(first_number 'TotalTime: [0-9]+' "$OUT/update-start.txt")"
    echo "update_ms=$(first_number 'Base system updated in [0-9]+' "$OUT/logcat.txt")"
    echo "session_start_us=$(grep -oE 'Session process [0-9]+ started in [0-9]+' "$OUT/logcat.txt" | head -n 1 | grep -oE '[0-9]+$')"
} > "$OUT/timings.env"
cat "$OUT/timings.env"

log "Took $((SECONDS - STARTED)) s"
exit $status
