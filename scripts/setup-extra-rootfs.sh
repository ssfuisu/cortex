#!/bin/bash
set -euo pipefail

mkdir -p extra-rootfs/usr/local/bin extra-rootfs/etc/cortex/autostart extra-rootfs/run

cat << 'EOFSERVICE' > extra-rootfs/usr/local/bin/service
#!/bin/bash
SERVICE="$1"
ACTION="$2"
shift 2 2>/dev/null
if [ -z "$SERVICE" ]; then
  echo "Usage: service <service-name> {start|stop|restart|status}"
  echo "       service --status-all"
  exit 1
fi
if [ "$SERVICE" = "--status-all" ]; then
  echo " [ + ] Running services"
  echo " [ - ] Stopped services"
  for initscript in /etc/init.d/*; do
    if [ -f "$initscript" ] && [ -x "$initscript" ]; then
      sname="$(basename "$initscript")"
      if [ "$sname" != "skeleton" ] && [ "$sname" != "rc" ]; then
        if "$initscript" status >/dev/null 2>&1; then
          echo " [ + ]  $sname"
        else
          echo " [ - ]  $sname"
        fi
      fi
    fi
  done
  exit 0
fi
PIDFILE="/run/$SERVICE.pid"
mkdir -p /run /var/run
if [ -x "/etc/init.d/$SERVICE" ]; then
  exec "/etc/init.d/$SERVICE" "$ACTION" "$@"
fi
case "$ACTION" in
  start)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "Service $SERVICE is already running (PID $(cat "$PIDFILE"))."
      exit 0
    fi
    DAEMON=""
    for p in "/usr/sbin/$SERVICE" "/usr/bin/$SERVICE"; do
      if [ -x "$p" ]; then DAEMON="$p"; break; fi
    done
    if [ -n "$DAEMON" ]; then
      echo "Starting $SERVICE..."
      "$DAEMON" "$@" &
      echo $! > "$PIDFILE"
      echo "$SERVICE started with PID $!"
    else
      echo "service: unrecognized service $SERVICE"
      exit 1
    fi
    ;;
  stop)
    if [ -f "$PIDFILE" ]; then
      PID=$(cat "$PIDFILE")
      if kill -0 "$PID" 2>/dev/null; then
        echo "Stopping $SERVICE (PID $PID)..."
        kill "$PID" 2>/dev/null
        rm -f "$PIDFILE"
        echo "$SERVICE stopped."
      else
        rm -f "$PIDFILE"
      fi
    else
      pkill -f "$SERVICE" 2>/dev/null && echo "Stopped $SERVICE." || echo "$SERVICE is not running."
    fi
    ;;
  status)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "* $SERVICE is running (PID $(cat "$PIDFILE"))"
      exit 0
    elif pgrep -f "$SERVICE" >/dev/null 2>&1; then
      echo "* $SERVICE is running"
      exit 0
    else
      echo "* $SERVICE is not running"
      exit 3
    fi
    ;;
  restart)
    "$0" "$SERVICE" stop
    sleep 1
    "$0" "$SERVICE" start "$@"
    ;;
  *)
    echo "Usage: service $SERVICE {start|stop|restart|status}"
    exit 1
    ;;
esac
EOFSERVICE

cat << 'EOFSYSTEMCTL' > extra-rootfs/usr/local/bin/systemctl
#!/bin/bash
ACTION="$1"
SERVICE="${2%.service}"
shift 2 2>/dev/null
case "$ACTION" in
  daemon-reload|reset-failed)
    exit 0
    ;;
  is-system-running)
    echo "running"
    exit 0
    ;;
  is-active)
    if [ -z "$SERVICE" ]; then exit 1; fi
    if service "$SERVICE" status >/dev/null 2>&1; then
      echo "active"
      exit 0
    else
      echo "inactive"
      exit 3
    fi
    ;;
  is-enabled)
    if [ -f "/etc/cortex/autostart/$SERVICE" ]; then
      echo "enabled"
      exit 0
    else
      echo "disabled"
      exit 1
    fi
    ;;
  enable)
    mkdir -p /etc/cortex/autostart
    touch "/etc/cortex/autostart/$SERVICE"
    echo "Enabled $SERVICE for automatic startup."
    exit 0
    ;;
  disable)
    rm -f "/etc/cortex/autostart/$SERVICE"
    echo "Disabled $SERVICE from automatic startup."
    exit 0
    ;;
  start|stop|restart|status|reload|force-reload)
    if [ -z "$SERVICE" ]; then
      echo "Usage: systemctl $ACTION <service>"
      exit 1
    fi
    exec service "$SERVICE" "$ACTION" "$@"
    ;;
  list-units|list-unit-files)
    exec service --status-all
    ;;
  *)
    if [ -n "$SERVICE" ]; then
      exec service "$SERVICE" "$ACTION" "$@"
    fi
    exit 0
    ;;
esac
EOFSYSTEMCTL

cat << 'EOFXDG' > extra-rootfs/usr/local/bin/xdg-open
#!/bin/sh
if [ $# -gt 0 ]; then
  echo "To open this URL, copy and paste it into your browser:"
  echo "$1"
fi
exit 0
EOFXDG

printf '#!/bin/sh\nexec service "$@"\n' > extra-rootfs/usr/local/bin/cortex-service
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/sensible-browser
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/x-www-browser
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/google-chrome
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/google-chrome-stable
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/chromium
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/chromium-browser
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/firefox
printf '#!/bin/sh\nexec /usr/local/bin/xdg-open "$@"\n' > extra-rootfs/usr/local/bin/open

SCRIPT_ASSETS="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/scripts"
if [ -f "$SCRIPT_ASSETS/su.sh" ]; then
  cp "$SCRIPT_ASSETS/su.sh" extra-rootfs/usr/local/bin/su
  chmod 0755 extra-rootfs/usr/local/bin/su
else
cat << 'EOFSU' > extra-rootfs/usr/local/bin/su
#!/bin/bash
# Cortex Root Switcher (su / tsu / sudo fallback when assets/scripts is unavailable)
find_host_su() {
  for cand in /system/bin/su /system/xbin/su /sbin/su /data/adb/ksu/bin/su /data/adb/ap/bin/su /data/adb/magisk/su; do
    if [ -f "$cand" ] || [ -x "$cand" ] || [ -L "$cand" ]; then
      echo "$cand"
      return 0
    fi
  done
  return 1
}
CORTEX_LD="$CORTEX_ROOT/lib/aarch64-linux-gnu:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:$CORTEX_ROOT/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:$CORTEX_ROOT/lib:$CORTEX_ROOT/usr/lib:$CORTEX_ROOT/usr/local/lib:$CORTEX_ROOT/usr/lib/systemd:$CORTEX_ROOT/lib/systemd:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/systemd:$CORTEX_ROOT/lib/arm-linux-gnueabihf/systemd"
HOST_SU="$(find_host_su || true)"
[ -n "$HOST_SU" ] || { echo "su: Android root not found" >&2; exit 1; }
exec env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES -u CORTEX_IDS -u CORTEX_BECOME \
  PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data HOSTNAME=localhost "$HOST_SU" "$@"
EOFSU
fi

if [ -f "$SCRIPT_ASSETS/tsu.sh" ]; then
  for name in tsu android-su sudo root; do
    cp "$SCRIPT_ASSETS/tsu.sh" "extra-rootfs/usr/local/bin/$name"
    chmod 0755 "extra-rootfs/usr/local/bin/$name"
  done
else
  for name in tsu android-su sudo root; do
    printf '#!/bin/sh\nexport CORTEX_INVOKED_AS="${0##*/}"\nSELF_DIR="${0%%/*}"\nif [ "$SELF_DIR" != "$0" ] && [ -x "$SELF_DIR/su" ]; then exec "$SELF_DIR/su" "$@"; fi\nexec /usr/local/bin/su "$@"\n' > "extra-rootfs/usr/local/bin/$name"
    chmod 0755 "extra-rootfs/usr/local/bin/$name"
  done
fi

if [ -f "$SCRIPT_ASSETS/cortex-audio.sh" ]; then
  cp "$SCRIPT_ASSETS/cortex-audio.sh" extra-rootfs/usr/local/bin/cortex-audio
  chmod 0755 extra-rootfs/usr/local/bin/cortex-audio
fi

mkdir -p extra-rootfs/etc/sudoers.d
printf 'localhost\n' > extra-rootfs/etc/hostname
chmod 0644 extra-rootfs/etc/hostname
printf 'Defaults\tenv_reset\nDefaults\tmail_badpass\nDefaults\tsecure_path="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"\nroot\tALL=(ALL:ALL) ALL\n%%sudo\tALL=(ALL:ALL) ALL\n@includedir /etc/sudoers.d\n' > extra-rootfs/etc/sudoers
chmod 0440 extra-rootfs/etc/sudoers
printf 'cortex ALL=(ALL:ALL) NOPASSWD: ALL\n' > extra-rootfs/etc/sudoers.d/cortex
chmod 0440 extra-rootfs/etc/sudoers.d/cortex

mkdir -p extra-rootfs/usr/share/nano
cat << 'EOFNANO' > extra-rootfs/usr/share/nano/default.nanorc
## Default syntax highlighting
syntax "default"
EOFNANO
chmod 0644 extra-rootfs/usr/share/nano/default.nanorc
cat << 'EOFMP' > extra-rootfs/usr/local/bin/mountpoint
#!/bin/sh
for arg in "$@"; do
    case "$arg" in
        /proc|/sys|/dev|/dev/pts|/proc/|/sys/|/dev/|/dev/shm) exit 0 ;;
    esac
done
for cand in /bin/mountpoint.orig /usr/bin/mountpoint.orig /bin/mountpoint /usr/bin/mountpoint; do
    if [ -x "$cand" ] && [ "$cand" != "$0" ]; then
        exec "$cand" "$@"
    fi
done
exit 0
EOFMP
chmod 0755 extra-rootfs/usr/local/bin/mountpoint

rm -rf extra-rootfs/dev 2>/dev/null || true
mkdir -p extra-rootfs/tmp/shm
chmod 1777 extra-rootfs/tmp/shm 2>/dev/null || true

mkdir -p extra-rootfs/etc/ssl/certs/java extra-rootfs/var/lib/ca-certificates-java extra-rootfs/etc/.java/.systemPrefs
mkdir -p extra-rootfs/etc/gnupg extra-rootfs/home/.gnupg extra-rootfs/root/.gnupg

mkdir -p extra-rootfs/etc/profile.d
cat << 'EOFENV' > extra-rootfs/etc/profile.d/00-env.sh
if [ -z "$CORTEX_ROOT" ]; then
    if [ -d "$HOME/../etc" ]; then
        export CORTEX_ROOT="$(cd "$HOME/.." && pwd)"
    fi
fi
if [ -n "$CORTEX_ROOT" ]; then
    export LD_LIBRARY_PATH="$CORTEX_ROOT/lib/aarch64-linux-gnu:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:$CORTEX_ROOT/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:$CORTEX_ROOT/lib:$CORTEX_ROOT/usr/lib:$CORTEX_ROOT/usr/local/lib:$CORTEX_ROOT/usr/lib/systemd:$CORTEX_ROOT/lib/systemd:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/systemd:$CORTEX_ROOT/lib/arm-linux-gnueabihf/systemd"
    export LD_PRELOAD="$CORTEX_ROOT/usr/lib/libcortex-hook.so"
fi
export HOSTNAME="${HOSTNAME:-localhost}"
export PATH="/home/.local/bin:$HOME/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
if [ -z "${PULSE_SERVER:-}" ]; then
    export PULSE_SERVER="unix:/tmp/.cortex-desktop/pulse.sock"
fi
if [ -x /usr/local/bin/cortex-audio ]; then
    /usr/local/bin/cortex-audio ensure >/dev/null 2>&1 || true
fi
EOFENV
chmod 0644 extra-rootfs/etc/profile.d/00-env.sh

mkdir -p extra-rootfs/etc extra-rootfs/var/lib/dbus
: > extra-rootfs/etc/machine-id
chmod 0644 extra-rootfs/etc/machine-id
ln -sf /etc/machine-id extra-rootfs/var/lib/dbus/machine-id

rm -rf extra-rootfs/bin extra-rootfs/sbin 2>/dev/null || true
MAINT_TOOLS="systemd-machine-id-setup systemd-sysusers systemd-tmpfiles mandb update-mime-database update-desktop-database install-info install-sgmlcatalog"
for p in extra-rootfs/usr/sbin extra-rootfs/usr/bin; do
    mkdir -p "$p"
    for tool in $MAINT_TOOLS; do
        printf '#!/bin/sh\nexit 0\n' > "$p/$tool"
        chmod 0755 "$p/$tool"
    done
done

# Pre-seed man-db auto-update disabled to prevent background re-indexing
mkdir -p extra-rootfs/var/lib/man-db extra-rootfs/var/cache/man
rm -f extra-rootfs/var/lib/man-db/auto-update

mkdir -p extra-rootfs/var/lib/dpkg
if [ ! -f extra-rootfs/var/lib/dpkg/diversions ]; then
    touch extra-rootfs/var/lib/dpkg/diversions
fi
for tool in $MAINT_TOOLS; do
    for base in /usr/bin /bin /usr/sbin /sbin; do
        div_path="$base/$tool"
        if ! grep -q "^$div_path$" extra-rootfs/var/lib/dpkg/diversions 2>/dev/null; then
            printf '%s\n%s.distrib\n:\n' "$div_path" "$div_path" >> extra-rootfs/var/lib/dpkg/diversions
        fi
    done
done
chmod 0644 extra-rootfs/var/lib/dpkg/diversions

# Enforce HTTPS for APT repositories and disallow unauthenticated packages
if [ -d extra-rootfs/etc/apt ]; then
    find extra-rootfs/etc/apt -type f \( -name "*.sources" -o -name "*.list" \) -exec sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g; s|http://archive.ubuntu.com|https://archive.ubuntu.com|g' {} + 2>/dev/null || true
    if [ -d extra-rootfs/etc/apt/apt.conf.d ]; then
        find extra-rootfs/etc/apt/apt.conf.d -type f -exec sed -i '/AllowUnauthenticated/d; /AllowInsecureRepositories/d' {} + 2>/dev/null || true
    fi
fi

chmod 0755 extra-rootfs/usr/local/bin/*
echo "extra-rootfs service, browser, and root tools prepared successfully."

