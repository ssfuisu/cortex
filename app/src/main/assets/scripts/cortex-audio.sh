#!/usr/bin/env bash
# Manages the PulseAudio -> Android FIFO sound bridge so CLI tools (paplay, aplay, pygame, ffmpeg)
# and the X11 desktop share low-latency audio playback.

set -u

R="${CORTEX_AUDIO_ROOT:-}"
STATE="$R/tmp/.cortex-desktop"
LOG_DIR="${HOME:-/tmp}/.cortex"
FIFO="$STATE/audio.fifo"
SOCK="$STATE/pulse.sock"
PIDFILE="$STATE/pulse.pid"
SERVERFILE="$STATE/pulse.server"
THEIRS="module-native-protocol-unix socket=$SOCK"

usage() {
    echo "Usage: cortex-audio {setup|start|ensure|stop|status}"
}

pid_alive() {
    [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

pid_is_pulse() {
    local pid="${1:-}" cmd=""
    pid_alive "$pid" || return 1
    [ -r "/proc/$pid/cmdline" ] || return 0
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
    [[ "$cmd" == *pulseaudio* ]]
}

ensure_alsa_and_client_conf() {
    local pulse_so="" d
    for d in "$R/usr/lib/aarch64-linux-gnu/alsa-lib" "$R/usr/lib/arm-linux-gnueabihf/alsa-lib" "$R/usr/lib/alsa-lib"; do
        if [ -e "$d/libasound_module_pcm_pulse.so" ]; then
            pulse_so="$d/libasound_module_pcm_pulse.so"
            break
        fi
    done

    if [ -n "$pulse_so" ] && [ -d "$R/etc/alsa/conf.d" ]; then
        local conf="$R/etc/alsa/conf.d/99-cortex-pulse.conf"
        if [ ! -e "$conf" ] && { [ -w "$R/etc/alsa/conf.d" ] || [ "$(id -u)" -eq 0 ]; }; then
            cat >"$conf" <<'EOF' 2>/dev/null || true
pcm.!default {
    type pulse
    fallback "sysdefault"
}
ctl.!default {
    type pulse
    fallback "sysdefault"
}
EOF
        fi
    fi

    if [ -d "$R/etc/pulse" ] && { [ -w "$R/etc/pulse/client.conf" ] || [ -w "$R/etc/pulse" ]; }; then
        local cconf="$R/etc/pulse/client.conf"
        if [ ! -e "$cconf" ]; then
            printf 'default-server = unix:%s\nautospawn = no\n' "$SOCK" >"$cconf" 2>/dev/null || true
        elif ! grep -q "^default-server = unix:$SOCK\$" "$cconf" 2>/dev/null; then
            if grep -q '^[;# ]*default-server =' "$cconf" 2>/dev/null; then
                sed -i "s|^[;# ]*default-server =.*|default-server = unix:$SOCK|" "$cconf" 2>/dev/null || true
            else
                printf '\ndefault-server = unix:%s\nautospawn = no\n' "$SOCK" >>"$cconf" 2>/dev/null || true
            fi
        fi
    fi
}

stop_audio() {
    local pid i
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    pid_is_pulse "$pid" && kill "$pid" 2>/dev/null
    command -v pkill >/dev/null 2>&1 && pkill -f "$THEIRS" 2>/dev/null || true
    for i in $(seq 1 30); do
        if ! pid_is_pulse "$pid" && ! { command -v pgrep >/dev/null 2>&1 && pgrep -f "$THEIRS" >/dev/null 2>&1; }; then
            break
        fi
        sleep 0.1
    done
    if pid_is_pulse "$pid"; then kill -9 "$pid" 2>/dev/null || true; fi
    command -v pkill >/dev/null 2>&1 && pkill -9 -f "$THEIRS" 2>/dev/null || true
    rm -f "$PIDFILE" "$SERVERFILE" "$SOCK"
}

is_running() {
    local pid
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    [ -S "$SOCK" ] && pid_is_pulse "$pid"
}

start_audio() {
    local quiet="${1:-0}"
    if ! command -v pulseaudio >/dev/null 2>&1; then
        [ "$quiet" = 1 ] && return 1
        echo "pulseaudio is not installed. Run: cortex-audio setup" >&2
        return 1
    fi
    if is_running; then
        [ -r "$SERVERFILE" ] || echo "unix:/tmp/.cortex-desktop/pulse.sock" >"$SERVERFILE" 2>/dev/null || true
        ensure_alsa_and_client_conf
        [ "$quiet" = 1 ] || echo "Audio bridge is already running (PULSE_SERVER=$(cat "$SERVERFILE" 2>/dev/null))."
        return 0
    fi
    mkdir -p "$STATE" "$LOG_DIR" 2>/dev/null || return 1
    chmod 700 "$STATE" 2>/dev/null || true
    [ -p "$FIFO" ] || { rm -f "$FIFO"; mkfifo -m 600 "$FIFO" 2>/dev/null; } || return 1
    stop_audio
    ensure_alsa_and_client_conf

    setsid pulseaudio --daemonize=no --use-pid-file=no --exit-idle-time=-1 --disallow-exit --disable-shm=yes \
        --realtime=no --high-priority=no --log-target=file:"$LOG_DIR/pulseaudio.log" -n \
        -L "$THEIRS auth-anonymous=1" \
        -L "module-pipe-sink file=$FIFO format=s16le rate=44100 channels=2 sink_name=android" \
        -L "module-always-sink" </dev/null >/dev/null 2>&1 9>&- &
    local pid=$!
    echo "$pid" >"$PIDFILE"
    local i
    for i in $(seq 1 50); do
        [ -S "$SOCK" ] && break
        pid_alive "$pid" || break
        sleep 0.1
    done
    if [ -S "$SOCK" ]; then
        echo "unix:$SOCK" >"$SERVERFILE"
        [ "$quiet" = 1 ] || echo "Audio bridge started: export PULSE_SERVER=\"unix:$SOCK\""
        return 0
    fi
    stop_audio
    [ "$quiet" = 1 ] || echo "Failed to start PulseAudio (log: $LOG_DIR/pulseaudio.log)" >&2
    return 1
}

cmd_setup() {
    local sudo_cmd=()
    if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
        sudo_cmd=(sudo)
    fi
    "${sudo_cmd[@]}" apt-get update && "${sudo_cmd[@]}" apt-get install -y --no-install-recommends pulseaudio pulseaudio-utils libasound2-plugins || return 1
    if [ -d "$R/etc/alsa/conf.d" ]; then
        printf 'pcm.!default {\n    type pulse\n    fallback "sysdefault"\n}\nctl.!default {\n    type pulse\n    fallback "sysdefault"\n}\n' | "${sudo_cmd[@]}" tee "$R/etc/alsa/conf.d/99-cortex-pulse.conf" >/dev/null 2>&1 || true
    fi
    if [ -d "$R/etc/pulse" ]; then
        printf 'default-server = unix:%s\nautospawn = no\n' "$SOCK" | "${sudo_cmd[@]}" tee -a "$R/etc/pulse/client.conf" >/dev/null 2>&1 || true
    fi
    start_audio 0
}

case "${1:-status}" in
    setup)  cmd_setup ;;
    start)  start_audio 0 ;;
    ensure) start_audio 1 ;;
    stop)
        stop_audio
        echo "Audio bridge stopped."
        ;;
    status)
        if is_running; then
            echo "running (PULSE_SERVER=$(cat "$SERVERFILE" 2>/dev/null || echo "unix:/tmp/.cortex-desktop/pulse.sock"))"
            exit 0
        else
            echo "stopped"
            exit 3
        fi
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        usage >&2
        exit 1
        ;;
esac
