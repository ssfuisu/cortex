#!/bin/bash
# cortex-desktop: a graphical Linux desktop (X11) in Cortex, shown in the app's Desktop screen.
#
#   cortex-desktop install [light|xfce] [--firefox]   installs the desktop (light: Openbox, the default)
#   cortex-desktop start [--size WxH]                  starts it (the app opens its screen)
#   cortex-desktop stop | restart | status
#   cortex-desktop remove                              removes what install added
#
# The X server is Ubuntu's Xvnc. It listens on no network port: only on a Unix socket that the app
# alone can open (/tmp/.cortex-desktop/vnc.sock), and X programs reach it as DISPLAY=:1 through
# /tmp/.X11-unix. Sound goes from PulseAudio through a pipe to Android's speaker. There is no GPU:
# everything is drawn by the CPU, which is fine for editors, file managers, office programs and
# browsers, and slow for games and video.
set -u

R="${CORTEX_DESKTOP_ROOT:-}"
STATE="$R/tmp/.cortex-desktop"
SOCKET="$STATE/vnc.sock"
CONF="$R/etc/cortex/desktop.conf"
LOG_DIR="${HOME:-/root}/.cortex"
LOG="$LOG_DIR/desktop.log"
DEFAULT_SIZE="1280x720"

LIGHT_PACKAGES="tigervnc-standalone-server tigervnc-tools dbus-x11 xauth x11-xserver-utils x11-utils
xfonts-base fonts-dejavu-core openbox obconf python3-xdg tint2 pcmanfm lxterminal mousepad lxappearance
adwaita-icon-theme-full pulseaudio pulseaudio-utils xdg-utils"
XFCE_PACKAGES="tigervnc-standalone-server tigervnc-tools dbus-x11 xauth x11-xserver-utils x11-utils
xfonts-base fonts-dejavu-core xfce4 xfce4-terminal thunar mousepad
adwaita-icon-theme-full pulseaudio pulseaudio-utils xdg-utils"

die() {
    echo "cortex-desktop: $*" >&2
    exit 1
}

usage() {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
}

conf_value() {
    sed -n "s/^$1=//p" "$CONF" 2>/dev/null | tail -n 1
}

set_conf() {
    mkdir -p "$(dirname "$CONF")"
    touch "$CONF"
    if grep -q "^$1=" "$CONF" 2>/dev/null; then
        sed -i "s|^$1=.*|$1=$2|" "$CONF"
    else
        echo "$1=$2" >>"$CONF"
    fi
}

as_root() {
    if [ "$(id -u)" = 0 ]; then "$@"; else sudo "$@"; fi
}

# A process that runs (one that ended but was not reaped yet, a zombie, does not)
pid_alive() {
    [[ "${1:-}" =~ ^[0-9]+$ ]] && kill -0 "$1" 2>/dev/null && ! grep -q '^State:[[:space:]]*Z' "/proc/$1/status" 2>/dev/null
}

# Whether pid runs the program named (a pid of an ended process can be another's later)
pid_is() {
    pid_alive "$1" && tr '\0' ' ' 2>/dev/null </proc/"$1"/cmdline | grep -Eq "$2"
}

XVNC_NAMES='X(tig)?vnc'
SESSION_NAMES='dbus-launch|openbox|xfce4-session|startxfce4|setsid'

running_pid() {
    local pid
    pid="$(cat "$STATE/xvnc.pid" 2>/dev/null)" || return 1
    pid_is "$pid" "$XVNC_NAMES" && echo "$pid"
}

session_running() {
    pid_is "$(cat "$STATE/session.pid" 2>/dev/null)" "$SESSION_NAMES"
}

# One start or stop at a time (the app and a terminal can ask together)
take_lock() {
    mkdir -p "$STATE" 2>/dev/null && chmod 700 "$STATE"
    exec 9>"$STATE/lock" || return 0
    command -v flock >/dev/null 2>&1 && flock -w 60 9
}

# A display number that no X server holds: :1 unless a live one has it (its lock file names its pid)
free_display() {
    local n pid
    for n in 1 2 3 4 5 6 7 8 9; do
        pid="$(tr -dc '0-9' 2>/dev/null <"$R/tmp/.X$n-lock")"
        if pid_alive "$pid"; then continue; fi
        rm -f "$R/tmp/.X$n-lock" "$R/tmp/.X11-unix/X$n"
        echo "$n"
        return 0
    done
    return 1
}

installed_kind() {
    local kind
    kind="$(conf_value DESKTOP)"
    [ -n "$kind" ] && command -v Xvnc >/dev/null 2>&1 && echo "$kind"
}

cmd_install() {
    local kind="light" firefox=0
    for arg in "$@"; do
        case "$arg" in
            light|openbox) kind="light" ;;
            xfce|xfce4) kind="xfce" ;;
            --firefox) firefox=1 ;;
            *) die "unknown option: $arg" ;;
        esac
    done
    local packages="$LIGHT_PACKAGES"
    [ "$kind" = xfce ] && packages="$XFCE_PACKAGES"
    # What was there before stays when the desktop is removed: only the packages installed here are listed
    mkdir -p "$(dirname "$CONF")"
    local p
    for p in $packages; do
        dpkg-query -W -f='${Status}\n' "$p" 2>/dev/null | grep -q '^install ok installed' || echo "$p"
    done >>"$R/etc/cortex/desktop.packages"
    sort -u -o "$R/etc/cortex/desktop.packages" "$R/etc/cortex/desktop.packages" 2>/dev/null
    # The links Cortex made to its Android opener step aside for the real ones (update-alternatives)
    for p in x-www-browser xdg-open; do
        [ "$(readlink "/usr/bin/$p" 2>/dev/null)" = /usr/local/bin/xdg-open ] && as_root rm -f "/usr/bin/$p"
    done
    echo "Installing the $kind desktop. This downloads a few hundred MB from Ubuntu's archive."
    as_root apt-get update || die "apt-get update failed"
    # shellcheck disable=SC2086
    as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $packages ||
        die "the packages could not be installed"
    if [ "$firefox" = 1 ]; then install_firefox || echo "cortex-desktop: Firefox was not installed (see above)." >&2; fi
    set_conf DESKTOP "$kind"
    echo "The desktop is installed. Open it from the app's menu (Desktop), or run: cortex-desktop start"
}

# Ubuntu's firefox package is only a pointer to the snap, which needs snapd: Mozilla's own apt
# repository has the real program.
install_firefox() {
    echo "Installing Firefox from Mozilla's apt repository (packages.mozilla.org)."
    dpkg-query -W -f='${Status}\n' firefox 2>/dev/null | grep -q '^install ok installed' ||
        echo firefox >>"$R/etc/cortex/desktop.packages"
    as_root mkdir -p /etc/apt/keyrings || return 1
    curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg | as_root tee /etc/apt/keyrings/packages.mozilla.org.asc >/dev/null || return 1
    echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" |
        as_root tee /etc/apt/sources.list.d/mozilla.list >/dev/null || return 1
    printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' |
        as_root tee /etc/apt/preferences.d/mozilla >/dev/null || return 1
    as_root apt-get update && as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y firefox
}

cmd_remove() {
    cmd_stop >/dev/null 2>&1
    # Only what the desktop installed (packages that were there before it stay)
    local packages="" p
    for p in $(cat "$R/etc/cortex/desktop.packages" 2>/dev/null); do
        dpkg-query -W -f='${Status}\n' "$p" 2>/dev/null | grep -q '^install ok installed' && packages="$packages $p"
    done
    if [ -n "$packages" ]; then
        # shellcheck disable=SC2086
        as_root env DEBIAN_FRONTEND=noninteractive apt-get remove -y $packages ||
            die "the packages could not be removed (see above); the desktop stays installed"
        as_root env DEBIAN_FRONTEND=noninteractive apt-get autoremove -y || true
    fi
    as_root rm -f /etc/apt/sources.list.d/mozilla.list /etc/apt/preferences.d/mozilla
    rm -f "$R/etc/cortex/desktop.packages"
    set_conf DESKTOP ""
    echo "The desktop was removed."
}

session_command() {
    case "$(conf_value DESKTOP)" in
        xfce) echo "startxfce4" ;;
        *) echo "openbox-session" ;;
    esac
}

# Openbox alone is an empty screen: a panel (launchers, windows, clock) and a desktop background come
# with it, in the app's dark colors. Only written when missing: they are the user's to change.
ensure_openbox_autostart() {
    local dir="${HOME}/.config"
    mkdir -p "$dir/openbox" "$dir/tint2" "$dir/pcmanfm/default"
    [ -f "$dir/openbox/autostart" ] || cat >"$dir/openbox/autostart" <<'EOF'
# Started with the Openbox desktop of Cortex; edit freely.
tint2 &
pcmanfm --desktop &
EOF
    [ -f "$dir/tint2/tint2rc" ] || cat >"$dir/tint2/tint2rc" <<'EOF'
# The panel of Cortex's desktop (tint2); edit freely, or run tint2conf.
# Background 1: the panel
rounded = 0
border_width = 0
background_color = #1e1e2e 100
border_color = #45475a 100
# Background 2: the active window, a launcher under the finger
rounded = 6
border_width = 0
background_color = #45475a 100
border_color = #45475a 100
background_color_hover = #585b70 100
border_color_hover = #585b70 100

panel_items = LTSC
panel_size = 100% 44
panel_padding = 6 4 6
panel_background_id = 1
panel_position = bottom center horizontal
panel_layer = top
wm_menu = 1
strut_policy = follow_size

taskbar_mode = single_desktop
taskbar_padding = 0 0 4
taskbar_name = 0
task_text = 1
task_icon = 1
task_maximum_size = 220 36
task_padding = 6 4 6
task_font = DejaVu Sans 10
task_font_color = #cdd6f4 100
task_active_background_id = 2
task_active_font_color = #ffffff 100

launcher_padding = 4 4 6
launcher_icon_size = 30
launcher_icon_background_id = 2
launcher_item_app = lxterminal.desktop
launcher_item_app = pcmanfm.desktop
launcher_item_app = org.xfce.mousepad.desktop
launcher_item_app = firefox.desktop

systray_padding = 4 2 4
systray_icon_size = 22

time1_format = %H:%M
time1_font = DejaVu Sans Bold 11
clock_font_color = #cdd6f4 100
clock_padding = 6 0
EOF
    [ -f "$dir/pcmanfm/default/desktop-items-0.conf" ] || cat >"$dir/pcmanfm/default/desktop-items-0.conf" <<'EOF'
[*]
wallpaper_mode=color
desktop_bg=#1e1e2e
desktop_fg=#cdd6f4
desktop_shadow=#11111b
desktop_font=DejaVu Sans 11
show_wm_menu=0
show_documents=1
show_trash=1
show_mounts=0
EOF
}

start_audio() {
    command -v pulseaudio >/dev/null 2>&1 || return 0
    local fifo="$STATE/audio.fifo"
    [ -p "$fifo" ] || { rm -f "$fifo"; mkfifo -m 600 "$fifo" 2>/dev/null; } || return 0
    pulseaudio --kill >/dev/null 2>&1
    # No shared memory between PulseAudio and its programs (they talk over the socket), no realtime
    # priority (Android does not give it), and one output: the pipe the app reads and plays
    pulseaudio --daemonize=yes --exit-idle-time=-1 --disallow-exit --disable-shm=yes \
        --realtime=no --high-priority=no --log-target=file:"$LOG_DIR/pulseaudio.log" -n \
        -L "module-native-protocol-unix socket=$STATE/pulse.sock auth-anonymous=1" \
        -L "module-pipe-sink file=$fifo format=s16le rate=44100 channels=2 sink_name=android" \
        -L "module-always-sink" >/dev/null 2>&1 || return 0
    echo "unix:$STATE/pulse.sock" >"$STATE/pulse.server"
}

cmd_start() {
    local size="" pid
    while [ $# -gt 0 ]; do
        case "$1" in
            --size) [ "$#" -ge 2 ] || die "--size needs a value, such as 1280x720"; size="$2"; shift 2 ;;
            --size=*) size="${1#--size=}"; shift ;;
            *) die "unknown option: $1" ;;
        esac
    done
    [[ -z "$size" || "$size" =~ ^[0-9]{3,4}x[0-9]{3,4}$ ]] || die "the size is WIDTHxHEIGHT, such as 1280x720"
    [ -n "$(installed_kind)" ] || die "the desktop is not installed: cortex-desktop install"
    take_lock
    if pid="$(running_pid)"; then
        if ! session_running; then
            # the X server outlived its desktop (logged out, or it crashed): a new one on it
            start_session "$(cat "$STATE/display" 2>/dev/null || echo 1)" || die "the desktop did not start (log: $LOG)"
        fi
        echo "The desktop is running (DISPLAY=:$(cat "$STATE/display" 2>/dev/null || echo 1))."
        return 0
    fi
    mkdir -p "$STATE" "$LOG_DIR" "$R/tmp/.X11-unix"
    chmod 700 "$STATE"
    chmod 1777 "$R/tmp/.X11-unix" 2>/dev/null
    local display
    display="$(free_display)" || die "no free X display (:1 to :9 are taken)"
    [ -n "$size" ] || size="$(conf_value SIZE)"
    [ -n "$size" ] || size="$DEFAULT_SIZE"
    rm -f "$SOCKET"
    : >"$LOG"
    # -rfbport -1: no TCP port, the Unix socket only (mode 0600: this app's user alone). setsid: a
    # session of its own, so that the end of the terminal that started it (the app runs this command
    # on a pseudo-terminal of its own) sends it no SIGHUP, which makes an X server reset.
    setsid Xvnc ":$display" -geometry "$size" -depth 24 -rfbunixpath "$SOCKET" -rfbunixmode 0600 -rfbport -1 \
        -SecurityTypes None -nolisten tcp -AlwaysShared -AcceptSetDesktopSize -dpi 120 \
        </dev/null >>"$LOG" 2>&1 9>&- &
    pid=$!
    echo "$pid" >"$STATE/xvnc.pid"
    echo "$display" >"$STATE/display"
    local waited=0
    while [ ! -S "$SOCKET" ] || [ ! -S "$R/tmp/.X11-unix/X$display" ]; do
        if ! pid_alive "$pid"; then
            rm -f "$STATE/xvnc.pid"
            tail -n 15 "$LOG" >&2
            die "the X server did not start (log: $LOG)"
        fi
        sleep 0.5
        waited=$((waited + 1))
        if [ "$waited" -ge 120 ]; then
            kill "$pid" 2>/dev/null
            rm -f "$STATE/xvnc.pid" "$SOCKET"
            die "the X server did not start within a minute (log: $LOG)"
        fi
    done
    start_audio
    if ! start_session "$display"; then
        stop_processes
        die "the desktop did not start (log: $LOG)"
    fi
    echo "The desktop is running: DISPLAY=:$display (programs started from the terminal open on it)."
}

# The desktop's session on the X server of display $1; fails when it ends at once
start_session() {
    local display="$1" pulse="" current="openbox"
    [ "$(conf_value DESKTOP)" = xfce ] || ensure_openbox_autostart
    [ -f "$STATE/pulse.server" ] && pulse="$(cat "$STATE/pulse.server")"
    [ "$(conf_value DESKTOP)" = xfce ] && current="XFCE"
    (
        cd "$HOME" || exit 1
        unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
        export DISPLAY=":$display" XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP="$current"
        [ -n "$pulse" ] && export PULSE_SERVER="$pulse"
        # shellcheck disable=SC2046
        exec setsid dbus-launch --exit-with-session $(session_command)
    ) </dev/null >>"$LOG" 2>&1 9>&- &
    local session=$!
    echo "$session" >"$STATE/session.pid"
    sleep 1
    if ! pid_alive "$session"; then
        tail -n 15 "$LOG" >&2
        rm -f "$STATE/session.pid"
        return 1
    fi
}

# Ends the session and the X server (only processes that are still theirs), and waits for them
stop_processes() {
    local f pid names stopped=1 i
    for f in session.pid xvnc.pid; do
        names="$SESSION_NAMES"
        [ "$f" = xvnc.pid ] && names="$XVNC_NAMES"
        pid="$(cat "$STATE/$f" 2>/dev/null)"
        if pid_is "$pid" "$names"; then
            kill "$pid" 2>/dev/null && stopped=0
            # the old server removes its socket as it ends: not after a new one made it
            for i in $(seq 1 50); do pid_alive "$pid" || break; sleep 0.1; done
        fi
        rm -f "$STATE/$f"
    done
    return $stopped
}

cmd_stop() {
    take_lock
    local stopped=0
    stop_processes && stopped=1
    command -v pulseaudio >/dev/null 2>&1 && pulseaudio --kill >/dev/null 2>&1
    rm -f "$STATE/pulse.server" "$SOCKET"
    if [ "$stopped" = 1 ]; then echo "The desktop was stopped."; else echo "The desktop was not running."; fi
}

cmd_status() {
    local kind
    kind="$(installed_kind)"
    if [ -z "$kind" ]; then
        echo "not installed"
        return 3
    fi
    if running_pid >/dev/null; then
        echo "running ($kind, DISPLAY=:$(cat "$STATE/display" 2>/dev/null || echo 1))"
        return 0
    fi
    echo "stopped ($kind)"
    return 1
}

case "${1:-start}" in
    install) shift; cmd_install "$@" ;;
    remove|uninstall) cmd_remove ;;
    start) shift; cmd_start "$@" ;;
    stop) cmd_stop ;;
    restart) shift; cmd_stop >/dev/null; cmd_start "$@" ;;
    status) cmd_status ;;
    -h|--help|help) usage ;;
    *) usage >&2; exit 2 ;;
esac
