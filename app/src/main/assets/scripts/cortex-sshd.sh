#!/bin/bash
# cortex-sshd: an SSH server in Cortex (Ubuntu's OpenSSH), to use the phone from a computer.
#
#   cortex-sshd [start] [-p PORT]   installs openssh-server the first time, starts it, says how to connect
#   cortex-sshd stop | restart | status
#   cortex-sshd enable | disable    start it (or not) whenever Cortex starts
#   cortex-sshd key [KEY]           lets a public key (or the keys on stdin) log in
#
# It listens on port 8022: ports below 1024 need root on Android. It runs while Cortex runs; with
# "cortex-api wakelock on" Android also lets it run with the screen off.
set -u

# A directory in front of the system's paths, for the tests
R="${CORTEX_SSHD_ROOT:-}"
SSHD="${CORTEX_SSHD_BIN:-$R/usr/sbin/sshd}"
CONF="$R/etc/ssh/sshd_config.d/cortex.conf"
PIDFILE="$R/run/cortex-sshd.pid"
AUTOSTART="$R/etc/cortex/autostart/cortex-sshd"
LOG="${HOME:-/root}/.cortex/sshd.log"
DEFAULT_PORT=8022

die() {
    echo "cortex-sshd: $*" >&2
    exit 1
}

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

running_pid() {
    local pid
    pid="$(cat "$PIDFILE" 2>/dev/null)" || return 1
    [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null && echo "$pid"
}

configured_port() {
    local port
    port="$(sed -n 's/^Port \([0-9]\{1,5\}\)$/\1/p' "$CONF" 2>/dev/null | head -n 1)"
    echo "${port:-$DEFAULT_PORT}"
}

login_user() {
    id -un 2>/dev/null || echo root
}

# Who can log in: the keys in ~/.ssh/authorized_keys, or a password in /etc/shadow
show_access() {
    local user keys=0 hash
    user="$(login_user)"
    [ -f "$HOME/.ssh/authorized_keys" ] && keys="$(grep -cE '^(ssh-|ecdsa-|sk-)' "$HOME/.ssh/authorized_keys" 2>/dev/null)"
    hash="$(awk -F: -v u="$user" '$1 == u { print $2 }' "$R/etc/shadow" 2>/dev/null)"
    if [ "${keys:-0}" -gt 0 ]; then
        echo "Log in with one of the ${keys} keys in ~/.ssh/authorized_keys."
    fi
    case "$hash" in
        ""|"*"|"!"*) [ "${keys:-0}" -gt 0 ] || {
            echo "Nobody can log in yet. Either let your computer's key in:"
            echo "    cortex-sshd key 'ssh-ed25519 AAAA... you@computer'   (the line of ~/.ssh/id_ed25519.pub there)"
            echo "or set a password for $user:"
            echo "    passwd"
        } ;;
        *) echo "Log in with the password of $user (passwd changes it)." ;;
    esac
}

show_addresses() {
    local port="$1" user addresses
    user="$(login_user)"
    addresses="$(cortex-api network 2>/dev/null | awk '{ print $2 }' | grep -v '^fe80' | grep -v '^$')"
    [ -n "$addresses" ] || addresses="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -v '^$')"
    echo "Connect from a computer in the same network:"
    if [ -n "$addresses" ]; then
        local a
        while IFS= read -r a; do
            case "$a" in
                *:*) echo "    ssh -p $port $user@$a   (IPv6)" ;;
                *) echo "    ssh -p $port $user@$a" ;;
            esac
        done <<< "$addresses"
    else
        echo "    ssh -p $port $user@<the phone's address in Settings > Wi-Fi>"
    fi
}

write_config() {
    local port="$1"
    mkdir -p "${CONF%/*}" || die "cannot create ${CONF%/*}"
    cat > "$CONF.new" <<EOF
# Written by cortex-sshd ("cortex-sshd start -p PORT" changes the port). Settings of your own go in
# another file of this directory: this one comes first, so its values count.
Port $port
PermitRootLogin yes
# Android sees the files of the home directory as the app's
StrictModes no
# No PAM login sessions on Android: passwords are checked against /etc/shadow
UsePAM no
KbdInteractiveAuthentication no
PrintMotd no
PidFile ${PIDFILE#"$R"}
EOF
    mv "$CONF.new" "$CONF" || die "cannot write $CONF"
}

ensure_installed() {
    [ -x "$SSHD" ] && return 0
    echo "Installing openssh-server (once)..."
    # Without what it recommends: systemd's login and resolver (which would make /etc/resolv.conf a
    # link to one that does not run), python3 for ssh-import-id, xauth
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server ||
        die "openssh-server could not be installed"
    [ -x "$SSHD" ] || die "$SSHD is missing after installing openssh-server"
}

do_start() {
    local port=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -p|--port)
                [ $# -ge 2 ] || die "$1 needs a port"
                port="$2"
                shift 2
                ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option: $1" ;;
        esac
    done
    if [ -n "$port" ]; then
        [[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] ||
            die "the port must be a number from 1024 to 65535 (lower ones need root on Android)"
    fi
    local pid
    if pid="$(running_pid)"; then
        if [ -z "$port" ] || [ "$port" = "$(configured_port)" ]; then
            echo "The SSH server runs on port $(configured_port) (process $pid)."
            show_addresses "$(configured_port)"
            show_access
            return 0
        fi
        do_stop >/dev/null
    fi
    ensure_installed
    write_config "${port:-$(configured_port)}"
    port="$(configured_port)"
    # Host keys, made once; the directory where sshd's unprivileged part runs
    ssh-keygen -A >/dev/null 2>&1 || true
    mkdir -p "$R/run/sshd" && chmod 755 "$R/run/sshd"
    mkdir -p "${LOG%/*}"
    "$SSHD" -t || die "sshd does not accept its configuration (see above)"
    "$SSHD" -E "$LOG" || die "sshd did not start (see $LOG)"
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        pid="$(running_pid)" && break
        sleep 0.3
    done
    [ -n "${pid:-}" ] || die "sshd did not keep running (see $LOG)"
    echo "The SSH server runs on port $port."
    show_addresses "$port"
    show_access
    [ -f "$AUTOSTART" ] || echo "To start it with Cortex every time: cortex-sshd enable"
}

do_stop() {
    local pid i
    if ! pid="$(running_pid)"; then
        echo "The SSH server is not running."
        return 0
    fi
    kill "$pid" 2>/dev/null
    for i in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.3
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    rm -f "$PIDFILE"
    echo "The SSH server is stopped."
}

do_status() {
    local pid
    if pid="$(running_pid)"; then
        echo "The SSH server runs on port $(configured_port) (process $pid)."
        show_addresses "$(configured_port)"
        show_access
    else
        echo "The SSH server is not running (cortex-sshd starts it)."
        return 3
    fi
}

# Adds public keys (the argument, or the lines on stdin) to ~/.ssh/authorized_keys, once each
do_key() {
    local keys added=0 line
    if [ $# -gt 0 ]; then
        keys="$*"
    elif [ ! -t 0 ]; then
        keys="$(cat)"
    else
        die "key needs the public key: cortex-sshd key 'ssh-ed25519 AAAA... you@computer'"
    fi
    mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
    touch "$HOME/.ssh/authorized_keys" && chmod 600 "$HOME/.ssh/authorized_keys"
    while IFS= read -r line; do
        line="${line%$'\r'}"
        [ -n "$line" ] || continue
        [[ "$line" =~ ^(ssh-[a-z0-9-]+|ecdsa-sha2-[a-z0-9-]+|sk-[a-z0-9@.-]+)\ [A-Za-z0-9+/=]+(\ .*)?$ ]] ||
            die "this is not a public key line (they start with ssh-ed25519, ssh-rsa or ecdsa-sha2-...): $line"
        if grep -qxF "$line" "$HOME/.ssh/authorized_keys"; then
            continue
        fi
        printf '%s\n' "$line" >> "$HOME/.ssh/authorized_keys"
        added=$((added + 1))
    done <<< "$keys"
    echo "Added $added key(s) to ~/.ssh/authorized_keys."
}

cmd="${1:-start}"
[ $# -gt 0 ] && shift
case "$cmd" in
    start) do_start "$@" ;;
    -p|--port) do_start "$cmd" "$@" ;;
    stop) do_stop ;;
    restart) do_stop >/dev/null; do_start "$@" ;;
    status) do_status ;;
    enable)
        mkdir -p "${AUTOSTART%/*}" && touch "$AUTOSTART" || die "cannot write $AUTOSTART"
        echo "The SSH server starts with Cortex from now on."
        ;;
    disable)
        rm -f "$AUTOSTART"
        echo "The SSH server no longer starts with Cortex."
        ;;
    key) do_key "$@" ;;
    -h|--help|help) usage ;;
    *) die "unknown command: $cmd (try --help)" ;;
esac
