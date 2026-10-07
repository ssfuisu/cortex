#!/bin/bash
# Cortex systemctl compatibility shim
ACTION="$1"
SERVICE="${2%.service}"
shift 2 2>/dev/null

AUTOSTART_DIR="${CORTEX_AUTOSTART_DIR:-/etc/cortex/autostart}"

validate_service_name() {
    if [ -z "$SERVICE" ] || [[ ! "$SERVICE" =~ ^[a-zA-Z0-9._-]+$ ]] || [ "$SERVICE" = "." ] || [ "$SERVICE" = ".." ]; then
        echo "systemctl: invalid service name '$SERVICE'" >&2
        exit 1
    fi
}

case "$ACTION" in
    daemon-reload|reset-failed)
        exit 0
        ;;
    is-system-running)
        echo "running"
        exit 0
        ;;
    is-active)
        validate_service_name
        if service "$SERVICE" status >/dev/null 2>&1; then
            echo "active"
            exit 0
        else
            echo "inactive"
            exit 3
        fi
        ;;
    is-enabled)
        validate_service_name
        if [ -f "$AUTOSTART_DIR/$SERVICE" ]; then
            echo "enabled"
            exit 0
        else
            echo "disabled"
            exit 1
        fi
        ;;
    enable)
        validate_service_name
        mkdir -p "$AUTOSTART_DIR"
        touch "$AUTOSTART_DIR/$SERVICE"
        echo "Enabled $SERVICE for automatic startup."
        exit 0
        ;;
    disable)
        validate_service_name
        rm -f "$AUTOSTART_DIR/$SERVICE"
        echo "Disabled $SERVICE from automatic startup."
        exit 0
        ;;
    start|stop|restart|status|reload|force-reload)
        if [ -z "$SERVICE" ]; then
            echo "Usage: systemctl $ACTION <service>"
            exit 1
        fi
        validate_service_name
        exec service "$SERVICE" "$ACTION" "$@"
        ;;
    list-units|list-unit-files)
        exec service --status-all
        ;;
    *)
        if [ -n "$SERVICE" ]; then
            validate_service_name
            exec service "$SERVICE" "$ACTION" "$@"
        fi
        exit 0
        ;;
esac
