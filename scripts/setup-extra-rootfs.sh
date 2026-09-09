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

cat << 'EOFAUDIO' > extra-rootfs/usr/local/bin/play-audio
#!/bin/bash
ACTION="$1"
if [ -z "$ACTION" ]; then
  echo "Usage: play-audio <file.mp3|wav|ogg|flac|aac|m4a>"
  echo "       play-audio --stop"
  echo "       play-audio --pause"
  echo "       play-audio --resume"
  echo "       play-audio --status"
  echo "       play-audio --beep [frequency_hz]"
  exit 1
fi
case "$ACTION" in
  --stop|-s) CMD="STOP" ;;
  --pause|-p) CMD="PAUSE" ;;
  --resume|-r) CMD="RESUME" ;;
  --status) CMD="STATUS" ;;
  --beep|-b)
    FREQ="${2:-440}"
    CMD="BEEP $FREQ"
    ;;
  *)
    TARGET="$1"
    if [ ! -e "$TARGET" ]; then
      echo "play-audio: file not found: $TARGET"
      exit 1
    fi
    REAL_PATH="$(realpath "$TARGET" 2>/dev/null || readlink -f "$TARGET" 2>/dev/null || echo "$TARGET")"
    CMD="PLAY $REAL_PATH"
    ;;
esac
if (exec 3<>/dev/tcp/127.0.0.1/4712) 2>/dev/null; then
  echo "$CMD" >&3
  cat <&3
  exec 3<&-
  exec 3>&-
else
  echo "play-audio: Cortex AudioServer is not running on port 4712."
  exit 1
fi
EOFAUDIO

printf '#!/bin/sh\nexec service "$@"\n' > extra-rootfs/usr/local/bin/cortex-service
printf '#!/bin/sh\nexec play-audio "$@"\n' > extra-rootfs/usr/local/bin/cortex-play
printf '#!/bin/sh\nexec play-audio "$@"\n' > extra-rootfs/usr/local/bin/paplay
printf '#!/bin/bash\necho "Playing 440Hz test tone on device speaker..."\nplay-audio --beep 440\n' > extra-rootfs/usr/local/bin/speaker-test
printf '#!/bin/bash\nif [ -n "$1" ]; then\n  exec play-audio "$@"\nelse\n  if (exec 3<>/dev/tcp/127.0.0.1/4712) 2>/dev/null; then\n    echo "STREAM" >&3\n    read -r _ <&3\n    cat >&3\n    exec 3<&-\n    exec 3>&-\n  else\n    echo "aplay: AudioServer not available"\n    exit 1\n  fi\nfi\n' > extra-rootfs/usr/local/bin/aplay

chmod 0755 extra-rootfs/usr/local/bin/*
echo "extra-rootfs service and audio tools prepared successfully."
