#!/bin/bash
set -e

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
#!/bin/bash
# Cortex URL & Browser Opener for Android Chrome / Default Browser
if [ -z "$1" ]; then
  echo "Usage: xdg-open <url>" >&2
  exit 1
fi

TARGET=""
for arg in "$@"; do
  case "$arg" in
    http://*|https://*|ftp://*|file://*)
      TARGET="$arg"
      break
      ;;
    --*|-*)
      ;;
    *)
      if [ -z "$TARGET" ]; then
        TARGET="$arg"
      fi
      ;;
  esac
done

if [ -z "$TARGET" ]; then
  TARGET="$1"
fi

# Send OPEN command to Cortex UrlOpenerServer on 127.0.0.1:4715
if (exec 3<>/dev/tcp/127.0.0.1/4715) 2>/dev/null; then
  echo "OPEN $TARGET" >&3
  read -r RESPONSE <&3 2>/dev/null
  exec 3<&-
  exec 3>&-
  if [ "$RESPONSE" = "OK" ]; then
    exit 0
  fi
fi

# Fallback to Android am command if available
if command -v am >/dev/null 2>&1; then
  am start -a android.intent.action.VIEW -d "$TARGET" >/dev/null 2>&1 && exit 0
fi

echo "xdg-open: Unable to open browser for: $TARGET" >&2
exit 1
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

cat << 'EOFSU' > extra-rootfs/usr/local/bin/su
#!/bin/bash
# Cortex Root Switcher (su / tsu / sudo)

find_host_su() {
  for cand in \
    /system/bin/su \
    /system/xbin/su \
    /sbin/su \
    /data/adb/ksu/bin/su \
    /data/adb/ap/bin/su \
    /data/adb/magisk/su \
    /vendor/bin/su; do
    if [ -f "$cand" ] && [ -x "$cand" ]; then
      echo "$cand"
      return 0
    fi
  done

  for p in $(echo "$PATH" | tr ':' ' '); do
    case "$p" in
      */usr/local/bin*|*/local/bin*) continue ;;
      *)
        if [ -x "$p/su" ]; then
          echo "$p/su"
          return 0
        fi
        ;;
    esac
  done

  return 1
}

HOST_SU=$(find_host_su)

if [ -z "$HOST_SU" ]; then
  echo "root not found" >&2
  exit 1
fi

if [ "$("$HOST_SU" -c 'id -u' 2>/dev/null)" != "0" ]; then
  echo "root not found" >&2
  exit 1
fi

if [ -z "$CORTEX_ROOT" ]; then
  if [ -d "/data/user/0/org.cortex.terminal/files/cortex" ]; then
    CORTEX_ROOT="/data/user/0/org.cortex.terminal/files/cortex"
  elif [ -d "/data/data/org.cortex.terminal/files/cortex" ]; then
    CORTEX_ROOT="/data/data/org.cortex.terminal/files/cortex"
  fi
fi

ROOT_HOME="$CORTEX_ROOT/root"
if [ ! -d "$ROOT_HOME" ]; then
  mkdir -p "$ROOT_HOME" 2>/dev/null || ROOT_HOME="$CORTEX_ROOT/home"
fi

CORTEX_SHELL=""
for s in "$CORTEX_ROOT/bin/bash" "$CORTEX_ROOT/usr/bin/bash" "$CORTEX_ROOT/bin/sh" "$CORTEX_ROOT/usr/bin/sh"; do
  if [ -x "$s" ]; then
    CORTEX_SHELL="$s"
    break
  fi
done
[ -z "$CORTEX_SHELL" ] && CORTEX_SHELL="/system/bin/sh"

CORTEX_PATH="$CORTEX_ROOT/usr/local/sbin:$CORTEX_ROOT/usr/sbin:$CORTEX_ROOT/sbin:$CORTEX_ROOT/usr/local/bin:$CORTEX_ROOT/bin:$CORTEX_ROOT/usr/bin:/system/bin:/system/xbin"
CORTEX_LD="$CORTEX_ROOT/lib:$CORTEX_ROOT/usr/lib:$CORTEX_ROOT/lib/aarch64-linux-gnu:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:$CORTEX_ROOT/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/local/lib"
CORTEX_PRELOAD=""
if [ -f "$CORTEX_ROOT/usr/lib/libcortex-hook.so" ]; then
  CORTEX_PRELOAD="$CORTEX_ROOT/usr/lib/libcortex-hook.so"
elif [ -f "$CORTEX_ROOT/lib/libcortex-hook.so" ]; then
  CORTEX_PRELOAD="$CORTEX_ROOT/lib/libcortex-hook.so"
fi

CERT_FILE="$CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt"
CERT_DIR="$CORTEX_ROOT/etc/ssl/certs:/system/etc/security/cacerts"
CURRENT_DIR="$PWD"

ENV_SETUP="export CORTEX_ROOT='$CORTEX_ROOT'; \
export PATH='$CORTEX_PATH'; \
export LD_LIBRARY_PATH='$CORTEX_LD'; \
export GLIBC_TUNABLES='glibc.pthread.rseq=0'; \
export LANG='C.UTF-8'; \
export LC_ALL='C.UTF-8'; \
export LOCPATH='$CORTEX_ROOT/usr/lib/locale'; \
export USER='root'; \
export LOGNAME='root'; \
export HOSTNAME='cortex-android'; \
export TERM='${TERM:-xterm-256color}'; \
export COLORTERM='${COLORTERM:-truecolor}'; \
export SSL_CERT_FILE='$CERT_FILE'; \
export SSL_CERT_DIR='$CERT_DIR'; \
export CURL_CA_BUNDLE='$CERT_FILE'; \
export NODE_EXTRA_CA_CERTS='$CERT_FILE'; \
export REQUESTS_CA_BUNDLE='$CERT_FILE'; \
export TZDIR='$CORTEX_ROOT/usr/share/zoneinfo'; \
export TERMINFO='$CORTEX_ROOT/usr/share/terminfo'; \
export TERMINFO_DIRS='$CORTEX_ROOT/usr/share/terminfo:$CORTEX_ROOT/lib/terminfo:$CORTEX_ROOT/etc/terminfo:/usr/share/terminfo'; \
export GODEBUG='netdns=cgo'; \
export BROWSER='/usr/local/bin/xdg-open'; \
export PS1='\[\033[01;31m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]# ';"

if [ -n "$CORTEX_PRELOAD" ]; then
  ENV_SETUP="$ENV_SETUP export LD_PRELOAD='$CORTEX_PRELOAD';"
fi

CORTEX_LD_SO=""
for cand_ld in \
  "$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
  "$CORTEX_ROOT/lib/ld-linux-aarch64.so.1" \
  "$CORTEX_ROOT/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
  "$CORTEX_ROOT/usr/lib/ld-linux-aarch64.so.1" \
  "$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" \
  "$CORTEX_ROOT/lib/ld-linux-armhf.so.3" \
  "$CORTEX_ROOT/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3"; do
  if [ -f "$cand_ld" ] && [ -x "$cand_ld" ]; then
    CORTEX_LD_SO="$cand_ld"
    break
  fi
done

if [ -n "$CORTEX_LD_SO" ]; then
  LAUNCH_SHELL="'$CORTEX_LD_SO' --library-path '$CORTEX_LD' '$CORTEX_SHELL'"
else
  LAUNCH_SHELL="'$CORTEX_SHELL'"
fi

if [ "$1" = "-c" ]; then
  shift
  CMD="$*"
  exec "$HOST_SU" -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null; exec $LAUNCH_SHELL -c \"$CMD\""
elif [ "$1" = "-" ] || [ "$1" = "-l" ] || [ "$1" = "--login" ]; then
  exec "$HOST_SU" -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -l -i"
elif [ "$1" = "root" ]; then
  shift
  if [ $# -eq 0 ]; then
    exec "$HOST_SU" -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null; exec $LAUNCH_SHELL -i"
  else
    CMD="$*"
    exec "$HOST_SU" -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null; exec $LAUNCH_SHELL -c \"$CMD\""
  fi
elif [ $# -eq 0 ]; then
  exec "$HOST_SU" -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null; exec $LAUNCH_SHELL -i"
else
  CMD="$*"
  exec "$HOST_SU" -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null; exec $LAUNCH_SHELL -c \"$CMD\""
fi
EOFSU

printf '#!/bin/sh\nSCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"\nif [ -x "$SCRIPT_DIR/su" ]; then exec "$SCRIPT_DIR/su" "$@"; elif [ -x /usr/local/bin/su ]; then exec /usr/local/bin/su "$@"; else exec su "$@"; fi\n' > extra-rootfs/usr/local/bin/tsu
printf '#!/bin/sh\nSCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"\nif [ -x "$SCRIPT_DIR/su" ]; then exec "$SCRIPT_DIR/su" "$@"; elif [ -x /usr/local/bin/su ]; then exec /usr/local/bin/su "$@"; else exec su "$@"; fi\n' > extra-rootfs/usr/local/bin/sudo

chmod 0755 extra-rootfs/usr/local/bin/*
echo "extra-rootfs service, browser, and root tools prepared successfully."
