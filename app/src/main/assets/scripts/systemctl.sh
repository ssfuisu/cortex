#!/bin/bash
# Cortex systemctl compatibility shim
USER_MODE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --user) USER_MODE=1; shift ;;
        --system|--quiet|-q|--no-reload|--no-block|--now|--global|--runtime|--no-pager|--no-ask-password|--full|-l)
            shift ;;
        --root=*|--preset-mode=*|--kill-who=*|--signal=*|-s=*)
            shift ;;
        --root|--preset-mode|--kill-who|--signal|-s)
            shift; [ $# -gt 0 ] && shift ;;
        --) shift; break ;;
        -*) shift ;;
        *) break ;;
    esac
done

# There is no systemd --user daemon in Cortex; report failure so scripts (e.g. xfce4 xinitrc)
# do not attempt to use systemd user activation.
if [ "$USER_MODE" = 1 ]; then
    exit 1
fi

ACTION="${1:-}"
SERVICE="${2:-}"
SERVICE="${SERVICE%.service}"
shift 2 2>/dev/null || shift $# 2>/dev/null

AUTOSTART_DIR="${CORTEX_AUTOSTART_DIR:-/etc/cortex/autostart}"

validate_service_name() {
    if [ -z "$SERVICE" ] || [[ ! "$SERVICE" =~ ^[a-zA-Z0-9._-]+$ ]] || [ "$SERVICE" = "." ] || [ "$SERVICE" = ".." ]; then
        echo "systemctl: invalid service name '$SERVICE'" >&2
        exit 1
    fi
}

case "$ACTION" in
    ""|daemon-reload|daemon-reexec|reset-failed)
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
    try-restart|condrestart|try-reload-or-restart)
        [ -z "$SERVICE" ] && exit 0
        validate_service_name
        if service "$SERVICE" status >/dev/null 2>&1; then
            exec service "$SERVICE" restart "$@"
        fi
        exit 0
        ;;
    reload-or-restart)
        if [ -z "$SERVICE" ]; then
            echo "Usage: systemctl $ACTION <service>"
            exit 1
        fi
        validate_service_name
        if service "$SERVICE" status >/dev/null 2>&1; then
            exec service "$SERVICE" restart "$@"
        else
            exec service "$SERVICE" start "$@"
        fi
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
