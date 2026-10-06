#!/bin/bash
# Cortex Service Manager for Ubuntu on Android
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
