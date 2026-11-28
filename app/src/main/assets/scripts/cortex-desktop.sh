#!/bin/bash
# cortex-desktop: a graphical Linux desktop (XFCE + Firefox) in Cortex, shown in the app's Desktop screen.
#
#   cortex-desktop install                    installs the XFCE desktop and Firefox
#   cortex-desktop start [--size WxH]         starts it (the app opens its screen)
#   cortex-desktop stop | restart | status
#   cortex-desktop remove                     removes what install added
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

XFCE_PACKAGES="tigervnc-standalone-server tigervnc-tools dbus-x11 xauth x11-xserver-utils x11-utils
xfonts-base fonts-dejavu-core xfce4 xfce4-terminal thunar mousepad
adwaita-icon-theme-full librsvg2-common pulseaudio pulseaudio-utils xdg-utils curl ca-certificates"

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
    as_root mkdir -p "$(dirname "$CONF")"
    as_root touch "$CONF"
    if grep -q "^$1=" "$CONF" 2>/dev/null; then
        as_root sed -i "s|^$1=.*|$1=$2|" "$CONF"
    else
        echo "$1=$2" | as_root tee -a "$CONF" >/dev/null
    fi
}

as_root() {
    if [ -n "$R" ] || [ "$(id -u 2>/dev/null || echo 1000)" = 0 ]; then "$@"; else sudo "$@"; fi
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
SESSION_NAMES='dbus-launch|xfce4-session|startxfce4|setsid'

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

# The real program behind one that Cortex does not run when packages are installed (its stand-in
# does nothing, and the hook answers for a program of that name itself)
real_tool() {
    if [ -x "$R/usr/bin/$1.distrib" ]; then
        echo "$R/usr/bin/$1.distrib"
    elif [ -x "$R/usr/bin/$1" ] && mkdir -p "$STATE" && ln -sf "$R/usr/bin/$1" "$STATE/$1.real" 2>/dev/null; then
        echo "$STATE/$1.real"
    else
        return 1
    fi
}

# The databases of file types (/usr/share/mime) and of the programs that open them. Cortex leaves
# them out when packages are installed: they take time and a terminal has no use for them. But GTK
# cannot load a single icon without the first (XFCE's panel ended at once, its desktop stayed
# black), and a file manager does not know what opens a file without the second. They are built
# here: the first (a few seconds) when it is missing or a package added to or took from it (the
# directory of its sources changed: a file dpkg unpacks keeps the date it has in the package, so the
# files' own dates say nothing), the second (a moment) every time.
refresh_databases() {
    local dir real
    dir="$R/usr/share/mime"
    if [ -d "$dir/packages" ] && { [ ! -f "$dir/mime.cache" ] || [ -n "$(find "$dir/packages" -newer "$dir/mime.cache" -print -quit 2>/dev/null)" ]; }; then
        if real="$(real_tool update-mime-database)"; then
            as_root "$real" "$dir" >/dev/null 2>&1 || echo "cortex-desktop: update-mime-database failed" >&2
        fi
    fi
    dir="$R/usr/share/applications"
    if [ -d "$dir" ] && real="$(real_tool update-desktop-database)"; then
        as_root "$real" "$dir" >/dev/null 2>&1 || echo "cortex-desktop: update-desktop-database failed" >&2
    fi
}

installed_kind() {
    local kind
    kind="$(conf_value DESKTOP)"
    [ "$kind" = "xfce" ] && command -v Xvnc >/dev/null 2>&1 && echo "$kind"
}

cmd_install() {
    local kind="xfce" firefox=1
    for arg in "$@"; do
        case "$arg" in
            xfce|xfce4|--firefox) ;;
            --no-firefox) firefox=0 ;;
            *) die "unknown option: $arg" ;;
        esac
    done
    local packages="$XFCE_PACKAGES"
    # What was there before stays when the desktop is removed: only the packages installed here are listed
    as_root mkdir -p "$(dirname "$CONF")"
    local p pkg_list=""
    for p in $packages; do
        dpkg-query -W -f='${Status}\n' "$p" 2>/dev/null | grep -q '^install ok installed' || pkg_list="$pkg_list$p"$'\n'
    done
    if [ -n "$pkg_list" ]; then
        printf '%s' "$pkg_list" | as_root tee -a "$R/etc/cortex/desktop.packages" >/dev/null
    fi
    as_root sort -u -o "$R/etc/cortex/desktop.packages" "$R/etc/cortex/desktop.packages" 2>/dev/null || true
    # The links Cortex made to its Android opener step aside for the real ones (update-alternatives)
    for p in x-www-browser xdg-open; do
        [ "$(readlink "/usr/bin/$p" 2>/dev/null)" = /usr/local/bin/xdg-open ] && as_root rm -f "/usr/bin/$p"
    done
    echo "Installing the XFCE desktop and Firefox. This downloads packages from Ubuntu's archive."
    as_root apt-get update || die "apt-get update failed"
    # shellcheck disable=SC2086
    as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $packages ||
        die "the packages could not be installed"
    if [ "$firefox" = 1 ]; then install_firefox || echo "cortex-desktop: Firefox was not installed (see above)." >&2; fi
    refresh_databases
    set_conf DESKTOP "$kind"
    echo "The desktop is installed. Open it from the app's menu (Desktop), or run: cortex-desktop start"
}

# Ubuntu's firefox package is only a pointer to the snap, which needs snapd: Mozilla's own apt
# repository has the real program.
install_firefox() {
    echo "Installing Firefox from Mozilla's apt repository (packages.mozilla.org)."
    if ! dpkg-query -W -f='${Status}\n' firefox 2>/dev/null | grep -q '^install ok installed'; then
        echo firefox | as_root tee -a "$R/etc/cortex/desktop.packages" >/dev/null
    fi
    as_root mkdir -p /etc/apt/keyrings || return 1
    local tmpkey
    tmpkey="$(mktemp)" || return 1
    if ! curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg -o "$tmpkey" || [ ! -s "$tmpkey" ]; then
        rm -f "$tmpkey"
        return 1
    fi
    as_root install -m 0644 "$tmpkey" /etc/apt/keyrings/packages.mozilla.org.asc || { rm -f "$tmpkey"; return 1; }
    rm -f "$tmpkey"
    local list_file="/etc/apt/sources.list.d/mozilla.list"
    local pin_file="/etc/apt/preferences.d/mozilla"
    local list_bak="" pin_bak=""
    if [ -f "$list_file" ]; then
        list_bak="$(mktemp)" && cp -p "$list_file" "$list_bak" || list_bak=""
    fi
    if [ -f "$pin_file" ]; then
        pin_bak="$(mktemp)" && cp -p "$pin_file" "$pin_bak" || pin_bak=""
    fi
    restore_mozilla_apt() {
        if [ -n "$list_bak" ] && [ -f "$list_bak" ]; then
            as_root install -m 0644 "$list_bak" "$list_file" >/dev/null 2>&1 || true
        else
            as_root rm -f "$list_file"
        fi
        if [ -n "$pin_bak" ] && [ -f "$pin_bak" ]; then
            as_root install -m 0644 "$pin_bak" "$pin_file" >/dev/null 2>&1 || true
        else
            as_root rm -f "$pin_file"
        fi
        rm -f "$list_bak" "$pin_bak"
    }
    if ! echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" |
        as_root tee "$list_file" >/dev/null; then
        restore_mozilla_apt
        return 1
    fi
    if ! printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' |
        as_root tee "$pin_file" >/dev/null; then
        restore_mozilla_apt
        return 1
    fi
    if ! { as_root apt-get update && as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends firefox; }; then
        restore_mozilla_apt
        return 1
    fi
    rm -f "$list_bak" "$pin_bak"
}

cmd_remove() {
    cmd_stop >/dev/null 2>&1
    # Recover cleanly if a previous removal left dbus/systemd packages half-configured (missing messagebus)
    if command -v systemd-sysusers >/dev/null 2>&1; then
        as_root systemd-sysusers >/dev/null 2>&1 || true
    fi
    as_root env DEBIAN_FRONTEND=noninteractive dpkg --configure -a >/dev/null 2>&1 || true
    # Only what the desktop installed (packages that were there before it stay)
    local packages="" p
    for p in $(cat "$R/etc/cortex/desktop.packages" 2>/dev/null); do
        dpkg-query -W -f='${Status}\n' "$p" 2>/dev/null | grep -q '^install ok installed' && packages="$packages $p"
    done
    if [ -n "$packages" ]; then
        # --auto-remove + --no-install-recommends stops apt from pulling in dbus-user-session/systemd
        # to satisfy a soon-to-be-autoremoved dependency (such as dconf-service) when removing dbus-x11.
        # shellcheck disable=SC2086
        as_root env DEBIAN_FRONTEND=noninteractive apt-get remove --auto-remove -y --no-install-recommends $packages ||
            die "the packages could not be removed (see above); the desktop stays installed"
        as_root env DEBIAN_FRONTEND=noninteractive apt-get autoremove -y --no-install-recommends || true
    fi
    as_root rm -f /etc/apt/sources.list.d/mozilla.list /etc/apt/preferences.d/mozilla "$R/etc/cortex/desktop.packages"
    set_conf DESKTOP ""
    echo "The desktop was removed."
}

session_command() {
    echo "startxfce4"
}

# Ends the desktop's PulseAudio. "pulseaudio --kill" does not find it here: it looks for a process
# named pulseaudio, and Cortex's programs run as ld.so with their name as an argument. So it was
# never stopped, and every start of the desktop left one more behind, the new one without its socket
# (the old one still held it). Ours are known by the pid kept at their start and by their socket.
stop_audio() {
    local pid i theirs="module-native-protocol-unix socket=$STATE/pulse.sock"
    pid="$(cat "$STATE/pulse.pid" 2>/dev/null)"
    pid_is "$pid" pulseaudio && kill "$pid" 2>/dev/null
    command -v pkill >/dev/null 2>&1 && pkill -f "$theirs" 2>/dev/null
    # all of them gone before the socket is taken away: one that ends later removes it as it goes,
    # which would be the next one's
    for i in $(seq 1 30); do
        if ! pid_is "$pid" pulseaudio && ! { command -v pgrep >/dev/null 2>&1 && pgrep -f "$theirs" >/dev/null 2>&1; }; then break; fi
        sleep 0.1
    done
    if pid_is "$pid" pulseaudio; then kill -9 "$pid" 2>/dev/null; fi
    command -v pkill >/dev/null 2>&1 && pkill -9 -f "$theirs" 2>/dev/null
    rm -f "$STATE/pulse.pid" "$STATE/pulse.server" "$STATE/pulse.sock"
}

start_audio() {
    command -v pulseaudio >/dev/null 2>&1 || return 0
    local fifo="$STATE/audio.fifo" pid i
    [ -p "$fifo" ] || { rm -f "$fifo"; mkfifo -m 600 "$fifo" 2>/dev/null; } || return 0
    stop_audio
    # No shared memory between PulseAudio and its programs (they talk over the socket), no realtime
    # priority (Android does not give it), and one output: the pipe the app reads and plays. Not as
    # its own daemon and without its pid file (see stop_audio): in a session of its own, like the X server.
    setsid pulseaudio --daemonize=no --use-pid-file=no --exit-idle-time=-1 --disallow-exit --disable-shm=yes \
        --realtime=no --high-priority=no --log-target=file:"$LOG_DIR/pulseaudio.log" -n \
        -L "module-native-protocol-unix socket=$STATE/pulse.sock auth-anonymous=1" \
        -L "module-pipe-sink file=$fifo format=s16le rate=44100 channels=2 sink_name=android" \
        -L "module-always-sink" </dev/null >/dev/null 2>&1 9>&- &
    pid=$!
    echo "$pid" >"$STATE/pulse.pid"
    # its socket is there within a moment; a desktop without sound is still a desktop
    for i in $(seq 1 50); do
        [ -S "$STATE/pulse.sock" ] && break
        pid_alive "$pid" || break
        sleep 0.1
    done
    if [ -S "$STATE/pulse.sock" ]; then
        echo "unix:$STATE/pulse.sock" >"$STATE/pulse.server"
        if [ -d "$R/etc/alsa/conf.d" ] && [ -w "$R/etc/alsa/conf.d" ] && [ ! -e "$R/etc/alsa/conf.d/99-cortex-pulse.conf" ]; then
            for d in "$R/usr/lib/aarch64-linux-gnu/alsa-lib" "$R/usr/lib/arm-linux-gnueabihf/alsa-lib" "$R/usr/lib/alsa-lib"; do
                if [ -e "$d/libasound_module_pcm_pulse.so" ]; then
                    printf 'pcm.!default {\n    type pulse\n    fallback "sysdefault"\n}\nctl.!default {\n    type pulse\n    fallback "sysdefault"\n}\n' >"$R/etc/alsa/conf.d/99-cortex-pulse.conf" 2>/dev/null || true
                    break
                fi
            done
        fi
    else
        # not one that runs on without anyone knowing its socket
        stop_audio
    fi
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
    mkdir -p "$STATE" "$STATE/runtime" "$LOG_DIR"
    chmod 700 "$STATE" "$STATE/runtime"
    # Pre-create X11 and ICE socket directories owned by root with sticky bit 1777 so non-root
    # xfce4-session/Xvnc do not stall on "_IceTransmkdir: ERROR: euid != 0".
    as_root mkdir -p -m 1777 "$R/tmp/.X11-unix" "$R/tmp/.ICE-unix" 2>/dev/null || mkdir -p "$R/tmp/.X11-unix" "$R/tmp/.ICE-unix"
    as_root chmod 1777 "$R/tmp" "$R/tmp/.X11-unix" "$R/tmp/.ICE-unix" 2>/dev/null || true
    local display
    display="$(free_display)" || die "no free X display (:1 to :9 are taken)"
    [ -n "$size" ] || size="$(conf_value SIZE)"
    [ -n "$size" ] || size="$DEFAULT_SIZE"
    rm -f "$SOCKET"
    : >"$LOG"
    # -rfbport -1: no TCP port, the Unix socket only (mode 0600: this app's user alone). setsid: a
    # session of its own, so that the end of the terminal that started it (the app runs this command
    # on a pseudo-terminal of its own) sends it no SIGHUP, which makes an X server reset.
    # -SendPrimary=0: only what is copied (the CLIPBOARD) goes to Android's clipboard. Text that is
    # only selected (the PRIMARY) replaced what had been copied on the phone, at every selection.
    setsid Xvnc ":$display" -geometry "$size" -depth 24 -rfbunixpath "$SOCKET" -rfbunixmode 0600 -rfbport -1 \
        -SecurityTypes None -nolisten tcp -AlwaysShared -AcceptSetDesktopSize -dpi 96 -SendPrimary=0 \
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
        stop_audio
        die "the desktop did not start (log: $LOG)"
    fi
    echo "The desktop is running: DISPLAY=:$display (programs started from the terminal open on it)."
}

# XFCE's window manager blends the windows together itself (shadows, transparency) unless told
# otherwise, and without a GPU the CPU does that for every change on the screen. Also point
# xfdesktop at an installed XFCE wallpaper instead of Ubuntu's missing xubuntu-wallpaper.png
# (which otherwise left the entire desktop background pitch black). Both files are only created
# when missing so user changes are preserved.
ensure_xfce_defaults() {
    local dir="${HOME}/.config/xfce4/xfconf/xfce-perchannel-xml"
    mkdir -p "$dir" || return 0
    if [ ! -e "$dir/xfwm4.xml" ]; then
        cat >"$dir/xfwm4.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>

<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
EOF
    fi
    if [ ! -e "$dir/xfce4-desktop.xml" ]; then
        local wp=""
        for cand in \
            /usr/share/backgrounds/xfce/xfce-blue.jpg \
            /usr/share/backgrounds/xfce/xfce-shapes.svg \
            /usr/share/backgrounds/xfce/xfce-teal.svg; do
            if [ -f "$R$cand" ]; then
                wp="$cand"
                break
            fi
        done
        local img_style=0
        [ -n "$wp" ] && img_style=5
        cat >"$dir/xfce4-desktop.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>

<channel name="xfce4-desktop" version="1.0">
  <property name="backdrop" type="empty">
    <property name="screen0" type="empty">
      <property name="monitorVNC-0" type="empty">
        <property name="workspace0" type="empty">
          <property name="color-style" type="int" value="0"/>
          <property name="image-style" type="int" value="$img_style"/>
          <property name="last-image" type="string" value="$wp"/>
          <property name="rgba1" type="array">
            <value type="double" value="0.117647"/>
            <value type="double" value="0.160784"/>
            <value type="double" value="0.231373"/>
            <value type="double" value="1.000000"/>
          </property>
        </property>
      </property>
      <property name="monitor0" type="empty">
        <property name="workspace0" type="empty">
          <property name="color-style" type="int" value="0"/>
          <property name="image-style" type="int" value="$img_style"/>
          <property name="last-image" type="string" value="$wp"/>
          <property name="rgba1" type="array">
            <value type="double" value="0.117647"/>
            <value type="double" value="0.160784"/>
            <value type="double" value="0.231373"/>
            <value type="double" value="1.000000"/>
          </property>
        </property>
      </property>
    </property>
  </property>
</channel>
EOF
    fi
}

# The desktop's session on the X server of display $1; fails when it ends at once
start_session() {
    local display="$1" pulse=""
    refresh_databases
    ensure_xfce_defaults
    mkdir -p "$STATE/runtime" 2>/dev/null && chmod 700 "$STATE/runtime" 2>/dev/null
    [ -f "$STATE/pulse.server" ] && pulse="$(cat "$STATE/pulse.server")"
    (
        cd "$HOME" || exit 1
        unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
        # NO_AT_BRIDGE: there is no accessibility bus here, and every GTK program waited for it and
        # wrote a warning
        export DISPLAY=":$display" XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=XFCE XDG_RUNTIME_DIR="$STATE/runtime" NO_AT_BRIDGE=1
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
    stop_audio
    rm -f "$SOCKET"
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
