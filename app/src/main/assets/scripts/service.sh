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

INIT_DIR="${CORTEX_INIT_DIR:-/etc/init.d}"
RUN_DIR="${CORTEX_RUN_DIR:-/run}"
SERVICE_PATH="${CORTEX_SERVICE_PATH:-/usr/sbin:/usr/bin}"

if [ "$SERVICE" = "--status-all" ]; then
    echo " [ + ] Running services"
    echo " [ - ] Stopped services"
    for initscript in "$INIT_DIR"/*; do
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

if [[ ! "$SERVICE" =~ ^[a-zA-Z0-9._-]+$ ]] || [ "$SERVICE" = "." ] || [ "$SERVICE" = ".." ]; then
    echo "service: invalid service name '$SERVICE'" >&2
    exit 1
fi

PIDFILE="$RUN_DIR/$SERVICE.pid"
mkdir -p "$RUN_DIR" /var/run 2>/dev/null || true

if [ -x "$INIT_DIR/$SERVICE" ]; then
    exec "$INIT_DIR/$SERVICE" "$ACTION" "$@"
fi

DAEMON=""
OLD_IFS="$IFS"
IFS=":"
for dir in $SERVICE_PATH; do
    if [ -n "$dir" ] && [ -x "$dir/$SERVICE" ]; then
        DAEMON="$dir/$SERVICE"
        break
    fi
done
IFS="$OLD_IFS"

is_service_process() {
    local pid="$1"
    local svc="$2"
    local daemon="$3"

    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    [ "$pid" != "$$" ] || return 1
    [ "$pid" != "${BASHPID:-$$}" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1

    if [ -r "/proc/$pid/status" ]; then
        local state_line
        state_line="$(grep '^State:' "/proc/$pid/status" 2>/dev/null || true)"
        case "$state_line" in
            *Z*) return 1 ;;
        esac
    fi

    local daemon_base=""
    if [ -n "$daemon" ]; then
        daemon_base="$(basename "$daemon")"
    fi

    if [ -L "/proc/$pid/exe" ]; then
        local exe_path exe_base
        exe_path="$(readlink -f "/proc/$pid/exe" 2>/dev/null || readlink "/proc/$pid/exe" 2>/dev/null || true)"
        exe_path="${exe_path% (deleted)}"
        if [ -n "$exe_path" ]; then
            exe_base="$(basename "$exe_path" 2>/dev/null)"
            if [ "$exe_base" = "$svc" ] || { [ -n "$daemon_base" ] && [ "$exe_base" = "$daemon_base" ]; }; then
                return 0
            fi
        fi
    fi

    if [ -r "/proc/$pid/comm" ]; then
        local comm_val=""
        IFS= read -r comm_val < "/proc/$pid/comm" 2>/dev/null || true
        if [ "$comm_val" = "$svc" ] || { [ -n "$daemon_base" ] && [ "$comm_val" = "$daemon_base" ]; }; then
            return 0
        fi
    fi

    if [ -r "/proc/$pid/cmdline" ]; then
        local arg0="" arg1="" token="" idx=0
        while IFS= read -r -d '' token || [ -n "$token" ]; do
            if [ "$idx" -eq 0 ]; then
                arg0="$token"
            elif [ "$idx" -eq 1 ]; then
                arg1="$token"
                break
            fi
            idx=$((idx + 1))
            token=""
        done < "/proc/$pid/cmdline"

        if [ -n "$arg0" ]; then
            local arg0_clean="${arg0#-}"
            local arg0_base
            arg0_base="$(basename "${arg0_clean%% *}" 2>/dev/null)"
            if [ "$arg0_base" = "$svc" ] || { [ -n "$daemon_base" ] && [ "$arg0_base" = "$daemon_base" ]; }; then
                return 0
            fi

            case "$arg0_base" in
                sh|bash|dash|zsh|ksh|python|python2*|python3*|perl|ruby|node|nodejs|php)
                    if [ -n "$arg1" ]; then
                        if [ "$arg1" = "$INIT_DIR/$SERVICE" ] || [ "$arg1" = "/etc/init.d/$svc" ] || \
                           { [ -n "$daemon" ] && [ "$arg1" = "$daemon" ]; } || \
                           [ "$arg1" = "/usr/sbin/$svc" ] || [ "$arg1" = "/usr/bin/$svc" ]; then
                            return 0
                        fi
                    fi
                    ;;
            esac
        fi
    fi

    return 1
}

find_service_pids() {
    local svc="$1"
    local daemon="$2"
    local proc_dir pid
    for proc_dir in /proc/[0-9]*; do
        [ -d "$proc_dir" ] || continue
        pid="${proc_dir#/proc/}"
        if is_service_process "$pid" "$svc" "$daemon"; then
            printf '%s\n' "$pid"
        fi
    done
}

proc_start_ticks() {
    local pid="$1"
    local stat_line="" rest=""
    [ -r "/proc/$pid/stat" ] || return 1
    IFS= read -r stat_line < "/proc/$pid/stat" 2>/dev/null || return 1
    rest="${stat_line##*) }"
    set -- $rest
    printf '%s\n' "${20:-}"
}

case "$ACTION" in
    start)
        if [ -f "$PIDFILE" ]; then
            EXISTING_PID="$(cat "$PIDFILE" 2>/dev/null)"
            if is_service_process "$EXISTING_PID" "$SERVICE" "$DAEMON"; then
                echo "Service $SERVICE is already running (PID $EXISTING_PID)."
                exit 0
            fi
            rm -f "$PIDFILE"
        fi
        if [ -n "$DAEMON" ]; then
            echo "Starting $SERVICE..."
            PRE_PIDS=" $(find_service_pids "$SERVICE" "$DAEMON" | tr '\n' ' ') "
            LAUNCH_TICKS="$(proc_start_ticks "${BASHPID:-$$}" 2>/dev/null || true)"
            "$DAEMON" "$@" &
            DAEMON_PID=$!
            sleep 0.1
            if ! is_service_process "$DAEMON_PID" "$SERVICE" "$DAEMON"; then
                FORKED_PID=""
                for cand_pid in $(find_service_pids "$SERVICE" "$DAEMON"); do
                    case "$PRE_PIDS" in
                        *" $cand_pid "*) continue ;;
                    esac
                    cand_ticks="$(proc_start_ticks "$cand_pid" 2>/dev/null || true)"
                    if [ -z "$LAUNCH_TICKS" ] || [ -z "$cand_ticks" ] || [ "$cand_ticks" -ge "$LAUNCH_TICKS" ] 2>/dev/null; then
                        FORKED_PID="$cand_pid"
                        break
                    fi
                done
                if [ -n "$FORKED_PID" ]; then
                    DAEMON_PID="$FORKED_PID"
                else
                    wait "$DAEMON_PID" 2>/dev/null || true
                    rm -f "$PIDFILE"
                    echo "service: failed to start $SERVICE (daemon exited immediately)" >&2
                    exit 1
                fi
            fi
            echo "$DAEMON_PID" > "$PIDFILE"
            echo "$SERVICE started with PID $DAEMON_PID"
        else
            echo "service: unrecognized service $SERVICE" >&2
            exit 1
        fi
        ;;
    stop)
        STOPPED=0
        TARGET_PIDS=""
        if [ -f "$PIDFILE" ]; then
            PID="$(cat "$PIDFILE" 2>/dev/null)"
            if is_service_process "$PID" "$SERVICE" "$DAEMON"; then
                TARGET_PIDS="$PID"
            fi
            rm -f "$PIDFILE"
        fi
        if [ -z "$TARGET_PIDS" ]; then
            TARGET_PIDS="$(find_service_pids "$SERVICE" "$DAEMON")"
        fi
        if [ -n "$TARGET_PIDS" ]; then
            for pid in $TARGET_PIDS; do
                if is_service_process "$pid" "$SERVICE" "$DAEMON"; then
                    echo "Stopping $SERVICE (PID $pid)..."
                    if kill "$pid" 2>/dev/null; then
                        STOPPED=1
                    fi
                fi
            done
            sleep 0.1
            for pid in $TARGET_PIDS; do
                if is_service_process "$pid" "$SERVICE" "$DAEMON"; then
                    kill -9 "$pid" 2>/dev/null || true
                fi
            done
            rm -f "$PIDFILE"
            if [ "$STOPPED" -eq 1 ]; then
                echo "$SERVICE stopped."
                exit 0
            fi
        fi
        rm -f "$PIDFILE"
        echo "$SERVICE is not running."
        exit 0
        ;;
    status)
        if [ -f "$PIDFILE" ]; then
            PID="$(cat "$PIDFILE" 2>/dev/null)"
            if is_service_process "$PID" "$SERVICE" "$DAEMON"; then
                echo "* $SERVICE is running (PID $PID)"
                exit 0
            fi
            rm -f "$PIDFILE"
        fi
        LIVE_PID="$(find_service_pids "$SERVICE" "$DAEMON" | head -n 1)"
        if [ -n "$LIVE_PID" ]; then
            echo "* $SERVICE is running (PID $LIVE_PID)"
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
