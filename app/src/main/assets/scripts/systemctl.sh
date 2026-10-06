#!/bin/bash
# Cortex systemctl compatibility shim
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
