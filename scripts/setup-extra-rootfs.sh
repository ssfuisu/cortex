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

chmod 0755 extra-rootfs/usr/local/bin/*
echo "extra-rootfs service and browser tools prepared successfully."
