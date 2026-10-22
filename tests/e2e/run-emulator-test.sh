#!/bin/bash
# End-to-end test of Cortex on an Android emulator, as a user would use it: installs the APK,
# starts the app, waits for Ubuntu to be unpacked, types a command into the terminal that runs
# tests/e2e/in-terminal.sh, and collects its results, logcat and screenshots.
#
#     run-emulator-test.sh APK_DIR OUT_DIR
#
# Needs adb and a running emulator with adb root (a "google_apis" image). The app's arm64 Ubuntu
# runs on an x86_64 emulator through the image's ARM translation, which must also run ARM programs
# started from a shell (binfmt_misc); the test sets that up when the image has not.
set -u

APK_DIR="$1"
OUT="$2"
DEVICE_OUT=/sdcard/Download/cortex-e2e
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
}
sh_() { adb shell "$@" | tr -d '\r'; }  # the output of a command on the device
run_() { adb shell "$@" >/dev/null 2>&1; } # only whether it succeeded
shot() { adb exec-out screencap -p > "$OUT/screens/$1.png" 2>/dev/null || rm -f "$OUT/screens/$1.png"; }
tap_center() { sh_ input tap $(($1 / 2)) $(($2 / 2)); }

# Types TEXT into the focused view, as from a hardware keyboard, then Enter. (adb runs the command
# through the device's shell, so the text is quoted for it; "input text" takes %s for a space.)
type_line() {
    local q
    q=$(printf '%s' "$1" | sed -e 's/ /%s/g' -e "s/'/'\\\\''/g")
    adb shell "input text '$q'"
    adb shell input keyevent 66
}

# Taps the view with this resource id (of the app), found in the window's accessibility dump.
tap_id() {
    local bounds
    sh_ uiautomator dump /sdcard/window.xml >/dev/null
    bounds=$(sh_ cat /sdcard/window.xml | grep -o "resource-id=\"[^\"]*:id/$1\"[^>]*bounds=\"[^\"]*\"" |
        sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/' | head -n 1)
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

log "Emulator"
adb wait-for-device
sh_ getprop | grep -E 'ro.product.cpu.abilist|native.bridge|ro.build.version.(sdk|release)|ro.product.model' | tee "$OUT/device.txt"
adb root >/dev/null 2>&1
sleep 3
adb wait-for-device
[ "$(sh_ id -u)" = 0 ] || die "adb root is not available on this image"

sh_ getprop ro.product.cpu.abilist | grep -q arm64-v8a ||
    die "this image cannot run arm64 apps (no ARM translation): $(sh_ getprop ro.product.cpu.abilist)"

# ARM programs started with exec() (bash, apt, ...) go through binfmt_misc to the translator.
binfmt=/proc/sys/fs/binfmt_misc
if ! sh_ ls "$binfmt" 2>/dev/null | grep -q arm64; then
    log "Registering ARM programs with binfmt_misc"
    sh_ ls -l /system/bin | grep -iE 'ndk_translation|houdini' || true
    sh_ ls -l /system/etc/binfmt_misc 2>/dev/null || true
    sh_ setprop ro.enable.native.bridge.exec 1 2>/dev/null || true
    sh_ setprop ro.enable.native.bridge.exec64 1 2>/dev/null || true
    sleep 2
    if ! sh_ ls "$binfmt" 2>/dev/null | grep -q arm64; then
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
APK=$(ls "$APK_DIR"/*arm64-v8a*.apk 2>/dev/null | head -n 1)
[ -n "$APK" ] || die "no arm64-v8a APK in $APK_DIR"
adb install -r -g "$APK" || die "the APK did not install"
PKG=$(sh_ pm list packages org.cortex.terminal | sed 's/^package://' | head -n 1)
[ -n "$PKG" ] || die "the app is not installed"
echo "package: $PKG"
DATA=/data/data/$PKG
# Android 11+ asks for "all files access" at start; the test grants it beforehand.
sh_ appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
sh_ rm -rf "$DEVICE_OUT"
adb push tests/e2e/in-terminal.sh /sdcard/Download/cortex-e2e.sh >/dev/null || die "cannot copy the test script"

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null' EXIT

log "Starting Cortex"
STARTED=$SECONDS
sh_ am start -W -n "$PKG/org.cortex.terminal.MainActivity"
sleep 20
shot "01-first-start"
wait_for 1200 run_ test -f "$DATA/files/cortex/.cortex_version" || die "Ubuntu was not unpacked within 20 minutes"
log "Ubuntu unpacked after $((SECONDS - STARTED)) s"
sleep 15
shot "02-terminal"

log "Running the tests in the terminal"
size=$(sh_ wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -n 1)
WIDTH=${size% *}
HEIGHT=${size#* }
tap_center "$WIDTH" "$HEIGHT"
sleep 2
type_line "bash /sdcard/Download/cortex-e2e.sh"
if ! wait_for 90 run_ test -d "$DEVICE_OUT/started"; then
    shot "03-not-started"
    diagnose
    log "The test did not start; typing the command again"
    tap_center "$WIDTH" "$HEIGHT"
    sleep 2
    type_line "bash /sdcard/Download/cortex-e2e.sh"
    wait_for 90 run_ test -d "$DEVICE_OUT/started" || die "the command typed into the terminal did not run"
fi
i=0
until run_ test -f "$DEVICE_OUT/done"; do
    [ $((SECONDS - STARTED)) -lt 3000 ] || {
        adb pull "$DEVICE_OUT" "$OUT/" >/dev/null 2>&1
        die "the tests did not finish in time"
    }
    sleep 30
    i=$((i + 1))
    [ $((i % 4)) = 0 ] && shot "04-progress"
done
sleep 2
shot "05-test-results"
adb pull "$DEVICE_OUT" "$OUT/" >/dev/null || die "cannot read the results"
log "Results"
cat "$OUT/cortex-e2e/results.txt"
cat "$OUT/cortex-e2e/done"

status=0
grep -q '^FAIL' "$OUT/cortex-e2e/results.txt" && status=1
if sh_ dumpsys notification --noredact | grep -q "Cortex e2e"; then
    echo "PASS the cortex-api notification is shown"
else
    echo "FAIL the cortex-api notification is not shown"
    status=1
fi

log "Screenshots"
# The terminal with cortex-api in use, as in the README.
type_line "clear"
type_line "cortex-api battery"
type_line "echo 'Hello from Ubuntu' | cortex-api clipboard-set"
type_line "cortex-api clipboard-get"
type_line "notify-send 'Build finished' 'make: all targets are up to date'"
sleep 5
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
sh_ input swipe 2 $((HEIGHT / 2)) $((WIDTH * 2 / 3)) $((HEIGHT / 2)) 300
sleep 2
shot "12-sessions"
if tap_id btnDrawerSettings; then
    sleep 3
    shot "13-settings"
    sh_ input swipe $((WIDTH / 2)) $((HEIGHT * 3 / 4)) $((WIDTH / 2)) $((HEIGHT / 4)) 500
    sleep 2
    shot "14-settings-more"
else
    log "no settings button found"
fi

log "Took $((SECONDS - STARTED)) s"
exit $status
