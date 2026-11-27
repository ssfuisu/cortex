#!/bin/bash
# Cortex Ubuntu privilege switcher (`su` & `sudo`) and Android host root bridge (`tsu` / `android-su`).
#
# - `sudo`: strictly Ubuntu local privilege escalation using /etc/sudoers (/etc/sudoers.d/*,
#   supporting `cortex ALL=(ALL:ALL) NOPASSWD: ALL`). Never invokes phone Android su.
# - `su`:   strictly Ubuntu user switching (`su`, `su -`, `su postgres`, ...). Use `su --android`
#   (or `tsu` / `android-su`) when phone Android root (Magisk/KernelSU/APatch) is wanted.
# - `tsu` / `android-su`: launches phone Android root cleanly with host `su`, stripping
#   Cortex userland IDs so it never collides with Ubuntu `sudo`/`su`.

sq() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

quote_args() {
    local out="" arg
    for arg in "$@"; do
        if [ -n "$out" ]; then
            out="$out $(sq "$arg")"
        else
            out="$(sq "$arg")"
        fi
    done
    printf '%s' "$out"
}

INVOKED_AS="${CORTEX_INVOKED_AS:-$(basename "$0")}"
unset CORTEX_INVOKED_AS

# Locate rootfs prefix when checking /etc/sudoers (allows unit tests to point at a temp dir).
sudoers_root() {
    if [ -n "${CORTEX_SUDOERS_ROOT:-}" ]; then
        printf '%s' "$CORTEX_SUDOERS_ROOT"
    elif [ -f /etc/sudoers ] || [ -d /etc/sudoers.d ]; then
        printf ''
    elif [ -n "${CORTEX_ROOT:-}" ]; then
        printf '%s' "$CORTEX_ROOT"
    fi
}

# Checks whether caller_user (or any of caller_groups) has an active NOPASSWD / ALL rule in
# /etc/sudoers or included /etc/sudoers.d/* files. Returns the matched privilege summary on stdout.
sudoers_rule_for_user() {
    local r="$1" caller="$2" groups_str="$3"
    local main="$r/etc/sudoers"
    local files=()
    [ -f "$main" ] && files+=("$main")

    # Gather included files matching @includedir / #includedir, or standard /etc/sudoers.d fallback.
    local inc_dirs=("$r/etc/sudoers.d")
    if [ -f "$main" ]; then
        while IFS= read -r line; do
            case "$line" in
                '@includedir '*|'#includedir '*)
                    local d="${line#* }"
                    d="${d#"${d%%[![:space:]]*}"}"
                    d="${d%%[[:space:]]*}"
                    [ "${d#/}" != "$d" ] && inc_dirs+=("$r$d")
                    ;;
            esac
        done <"$main"
    fi

    local d f
    for d in "${inc_dirs[@]}"; do
        [ -d "$d" ] || continue
        for f in "$d"/*; do
            [ -f "$f" ] || continue
            case "$(basename "$f")" in
                *~|*.*) continue ;;
            esac
            files+=("$f")
        done
    done

    # Minimal clean environment without /etc/sudoers defaults to fallback cleanly only when no sudoers file exists.
    if [ "${#files[@]}" -eq 0 ]; then
        if [ "$caller" = "root" ] || [ "$caller" = "cortex" ]; then
            printf '(ALL : ALL) NOPASSWD: ALL\n'
            return 0
        fi
        return 1
    fi

    local line who rest matched=0 nopasswd=0
    for f in "${files[@]}"; do
        [ -r "$f" ] || continue
        while IFS= read -r line || [ -n "$line" ]; do
            line="${line%%#*}"
            line="${line#"${line%%[![:space:]]*}"}"
            line="${line%"${line##*[![:space:]]}"}"
            [ -n "$line" ] || continue
            case "$line" in
                @*|Defaults*|User_Alias*|Runas_Alias*|Host_Alias*|Cmnd_Alias*) continue ;;
            esac
            who="${line%%[[:space:]]*}"
            rest="${line#"$who"}"
            rest="${rest#"${rest%%[![:space:]]*}"}"
            local who_ok=0
            if [ "$who" = "$caller" ]; then
                who_ok=1
            elif [ "${who#%}" != "$who" ]; then
                local g="${who#%}"
                case " $groups_str " in
                    *" $g "*) who_ok=1 ;;
                esac
            fi
            [ "$who_ok" = 1 ] || continue
            case "$rest" in
                *NOPASSWD*:*ALL*|*NOPASSWD*ALL)
                    printf '(ALL : ALL) NOPASSWD: ALL\n'
                    return 0
                    ;;
                ALL=*' ALL'|ALL=*ALL)
                    matched=1
                    ;;
            esac
        done <"$f"
    done
    if [ "$matched" = 1 ] && [ "$nopasswd" = 0 ]; then
        printf '(ALL : ALL) ALL\n'
        return 0
    fi
    return 1
}

sudo_usage() {
    cat <<'EOF'
usage: sudo [-h | -V]
usage: sudo [-v | -k | -l]
usage: sudo [-EHnPS] [-u user] [-i | -s] [command [arg ...]]
EOF
}

sudo_direct() {
    local target_user="" login=0 use_shell=0 list=0 validate=0 preserve_env=0 set_home=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            -h|--help) sudo_usage; exit 0 ;;
            -V|--version) echo "Cortex sudo (Ubuntu local privilege elevation)"; exit 0 ;;
            -l|--list) list=1; shift ;;
            -v|--validate) validate=1; shift ;;
            -k|--reset-timestamp|-K|--remove-timestamp) shift ;;
            -i|--login) login=1; set_home=1; shift ;;
            -s|--shell) use_shell=1; shift ;;
            -E|--preserve-env) preserve_env=1; shift ;;
            -H|--set-home) set_home=1; shift ;;
            -u|--user) target_user="${2:-}"; shift; [ "$#" -gt 0 ] && shift ;;
            -u*) target_user="${1#-u}"; shift ;;
            --user=*) target_user="${1#--user=}"; shift ;;
            -g|--group|-p|--prompt|-C|--close-from|-D|--chdir|-h|--host|-r|--role|-t|--type|-T|--command-timeout)
                shift; [ "$#" -gt 0 ] && shift ;;
            --) shift; break ;;
            -*)
                # Support bundled flags like -HE or -ni
                local opts="${1#-}" c
                while [ -n "$opts" ]; do
                    c="${opts%"${opts#?}"}"
                    opts="${opts#?}"
                    case "$c" in
                        i) login=1; set_home=1 ;;
                        s) use_shell=1 ;;
                        E) preserve_env=1 ;;
                        H) set_home=1 ;;
                        l) list=1 ;;
                        v) validate=1 ;;
                    esac
                done
                shift
                ;;
            *) break ;;
        esac
    done

    local caller_uid caller_user caller_groups rule
    caller_uid="$(id -u 2>/dev/null || echo 1000)"
    caller_user="$(id -un 2>/dev/null || echo "${USER:-cortex}")"
    caller_groups="$(id -Gn 2>/dev/null || echo "$caller_user")"

    if [ "$caller_uid" -ne 0 ]; then
        if ! rule="$(sudoers_rule_for_user "$(sudoers_root)" "$caller_user" "$caller_groups")"; then
            echo "$caller_user is not in the sudoers file." >&2
            exit 1
        fi
    else
        rule="(ALL : ALL) NOPASSWD: ALL"
    fi

    if [ "$validate" = 1 ]; then
        exit 0
    fi
    if [ "$list" = 1 ] && [ "$#" -eq 0 ]; then
        local host
        host="$(hostname 2>/dev/null || echo localhost)"
        printf 'User %s may run the following commands on %s:\n    %s\n' "$caller_user" "$host" "$rule"
        exit 0
    fi

    export CORTEX_BECOME=root
    if [ -n "$target_user" ] && [ "$target_user" != "root" ] && [ "$target_user" != "0" ]; then
        if [ "$login" = 1 ]; then
            [ "$#" -eq 0 ] && exec runuser -l "$target_user"
            exec runuser -l "$target_user" -c "$(quote_args "$@")"
        fi
        [ "$#" -eq 0 ] && exec runuser -u "$target_user" -- "${SHELL:-/bin/bash}"
        exec runuser -u "$target_user" -- "$@"
    fi

    if [ "$preserve_env" = 0 ]; then
        export USER=root LOGNAME=root
        if [ "$set_home" = 1 ]; then
            local root_home="/root"
            [ -n "${CORTEX_ROOT:-}" ] && [ -d "$CORTEX_ROOT/root" ] && root_home="$CORTEX_ROOT/root"
            export HOME="$root_home"
            [ "$login" = 1 ] && { cd "$HOME" 2>/dev/null || true; }
        fi
    fi

    if [ "$#" -eq 0 ]; then
        if [ "$login" = 1 ]; then
            exec /bin/bash -l
        elif [ "$use_shell" = 1 ]; then
            exec "${SHELL:-/bin/bash}"
        else
            sudo_usage >&2
            exit 1
        fi
    fi
    if [ "$use_shell" = 1 ] || [ "$login" = 1 ]; then
        local login_flag=()
        [ "$login" = 1 ] && login_flag=(-l)
        exec "${SHELL:-/bin/bash}" "${login_flag[@]}" -c "$(quote_args "$@")"
    fi
    exec "$@"
}

su_direct() {
    local login=0 command="" have_command=0 target_user="" shell_path="" preserve_env=0
    local shell_args=()
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --android|-A)
                shift
                android_su_direct "$@"
                exit $?
                ;;
            -|-l|--login) login=1; shift ;;
            -c|--command) have_command=1; command="${2:-}"; shift; [ "$#" -gt 0 ] && shift ;;
            --command=*) have_command=1; command="${1#--command=}"; shift ;;
            -s|--shell) shell_path="${2:-}"; shift; [ "$#" -gt 0 ] && shift ;;
            --shell=*) shell_path="${1#--shell=}"; shift ;;
            -p|-m|--preserve-environment) preserve_env=1; shift ;;
            -g|--group|-G|--supp-group|-w|--whitelist-environment) shift; [ "$#" -gt 0 ] && shift ;;
            --) shift; break ;;
            -*) shift ;;
            *)
                if [ -z "$target_user" ]; then
                    # Allow `su -c '...' _ arg1 arg2` sh-style placeholder without treating `_` as a username.
                    if [ "$have_command" = 1 ] && [ "$1" = "_" ]; then
                        shell_args+=("$@")
                        break
                    fi
                    target_user="$1"
                else
                    shell_args+=("$1")
                fi
                shift ;;
        esac
    done
    shell_args+=("$@")

    export CORTEX_BECOME=root
    case "$target_user" in
        ""|root|0)
            target_user=""
            if [ "$preserve_env" = 0 ]; then
                export USER=root LOGNAME=root
                local root_home="/root"
                [ -n "${CORTEX_ROOT:-}" ] && [ -d "$CORTEX_ROOT/root" ] && root_home="$CORTEX_ROOT/root"
                export HOME="$root_home"
                [ "$login" = 1 ] && { cd "$HOME" 2>/dev/null || true; }
            fi
            ;;
        *)
            local shell_cmd="$shell_path"
            if [ -z "$shell_cmd" ]; then
                shell_cmd="$(getent passwd "$target_user" 2>/dev/null | cut -d: -f7)"
                [ -n "$shell_cmd" ] || shell_cmd=/bin/sh
            fi
            if [ "$login" = 1 ]; then
                [ "$have_command" = 1 ] && exec runuser -l "$target_user" -c "$command"
                exec runuser -l "$target_user"
            fi
            [ "$have_command" = 1 ] && exec runuser -u "$target_user" -- "$shell_cmd" -c "$command" "${shell_args[@]}"
            exec runuser -u "$target_user" -- "$shell_cmd" "${shell_args[@]}"
            ;;
    esac

    local shell_cmd="${shell_path:-${SHELL:-/bin/bash}}"
    local login_flag=()
    [ "$login" = 1 ] && login_flag=(-l)
    [ "$have_command" = 1 ] && exec "$shell_cmd" "${login_flag[@]}" -c "$command" "${shell_args[@]}"
    exec "$shell_cmd" "${login_flag[@]}" "${shell_args[@]}"
}

# ---- Android Host Root (`tsu` / `android-su` / `su --android`) --------------------------------

find_host_su() {
    if [ -n "${CORTEX_HOST_SU:-}" ]; then
        echo "$CORTEX_HOST_SU"
        return 0
    fi

    for cand in \
        /system/bin/su \
        /system/xbin/su \
        /sbin/su \
        /data/adb/ksu/bin/su \
        /data/adb/ap/bin/su \
        /data/adb/ap/su \
        /data/adb/magisk/su \
        /vendor/bin/su \
        /system_ext/bin/su \
        /product/bin/su \
        /apex/com.android.runtime/bin/su; do
        if [ -f "$cand" ] || [ -x "$cand" ] || [ -L "$cand" ]; then
            echo "$cand"
            return 0
        fi
    done

    local host_which
    host_which=$(env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES -u CORTEX_IDS -u CORTEX_BECOME \
        PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data \
        /system/bin/sh -c 'command -v su 2>/dev/null || which su 2>/dev/null' 2>/dev/null)
    if [ -n "$host_which" ]; then
        echo "$host_which"
        return 0
    fi

    if [ -x "/data/adb/magisk/magisk" ] || [ -f "/data/adb/magisk/magisk" ]; then
        echo "/data/adb/magisk/magisk su"
        return 0
    fi

    return 1
}

android_su_direct() {
    local HOST_SU
    HOST_SU="$(find_host_su || true)"
    if [ -z "$HOST_SU" ]; then
        echo "$INVOKED_AS: Android root binary (su) was not found. Use 'sudo' or 'su' for Ubuntu root." >&2
        exit 1
    fi

    local RUN_SU=()
    if [ -x "$HOST_SU" ] || [ -f "$HOST_SU" ]; then
        RUN_SU=(
            env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES -u CORTEX_IDS -u CORTEX_BECOME
            PATH="/system/bin:/system/xbin:/sbin:/vendor/bin:/usr/bin:/bin${PATH:+:$PATH}"
            ANDROID_ROOT=/system
            ANDROID_DATA=/data
            "TERM=${TERM:-xterm-256color}"
            "COLORTERM=${COLORTERM:-truecolor}"
            "$HOST_SU"
        )
    else
        RUN_SU=(
            env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES -u CORTEX_IDS -u CORTEX_BECOME
            PATH="/system/bin:/system/xbin:/sbin:/vendor/bin:/usr/bin:/bin${PATH:+:$PATH}"
            ANDROID_ROOT=/system
            ANDROID_DATA=/data
            "TERM=${TERM:-xterm-256color}"
            "COLORTERM=${COLORTERM:-truecolor}"
            /system/bin/sh -c 'exec "$@"' _ $HOST_SU
        )
    fi

    local uid
    uid=$("${RUN_SU[@]}" -c 'id -u 2>/dev/null || /system/bin/id -u 2>/dev/null || /system/xbin/id -u 2>/dev/null || /system/bin/toybox id -u 2>/dev/null || echo "$UID" || echo "$USER_ID"' 2>/dev/null || true)
    if ! { [ -n "$uid" ] && [ "$uid" -eq 0 ] 2>/dev/null; } && ! "${RUN_SU[@]}" -c 'true' 2>/dev/null; then
        echo "$INVOKED_AS: Android root access denied or unavailable (Magisk/KernelSU/APatch). Use 'sudo' for Ubuntu root." >&2
        exit 1
    fi

    if [ "${1:-}" = "--host" ]; then
        shift
        if [ "$#" -eq 0 ]; then
            exec "${RUN_SU[@]}"
        fi
        exec "${RUN_SU[@]}" -c "$(quote_args "$@")"
    fi

    if [ -z "${CORTEX_ROOT:-}" ]; then
        if [ -d "/data/user/0/org.cortex.terminal/files/cortex" ]; then
            CORTEX_ROOT="/data/user/0/org.cortex.terminal/files/cortex"
        elif [ -d "/data/data/org.cortex.terminal/files/cortex" ]; then
            CORTEX_ROOT="/data/data/org.cortex.terminal/files/cortex"
        fi
    fi

    local ROOT_HOME="$CORTEX_ROOT/root"
    if [ ! -d "$ROOT_HOME" ]; then
        mkdir -p "$ROOT_HOME" 2>/dev/null || ROOT_HOME="$CORTEX_ROOT/home"
    fi

    local CORTEX_SHELL="" s
    for s in "$CORTEX_ROOT/bin/bash" "$CORTEX_ROOT/usr/bin/bash" "$CORTEX_ROOT/bin/sh" "$CORTEX_ROOT/usr/bin/sh"; do
        if [ -x "$s" ]; then
            CORTEX_SHELL="$s"
            break
        fi
    done
    [ -z "$CORTEX_SHELL" ] && CORTEX_SHELL="/system/bin/sh"

    local CORTEX_PATH="$CORTEX_ROOT/usr/local/sbin:$CORTEX_ROOT/usr/sbin:$CORTEX_ROOT/sbin:$CORTEX_ROOT/usr/local/bin:$CORTEX_ROOT/bin:$CORTEX_ROOT/usr/bin:/system/bin:/system/xbin"
    local CORTEX_LD="$CORTEX_ROOT/lib/aarch64-linux-gnu:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:$CORTEX_ROOT/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:$CORTEX_ROOT/lib:$CORTEX_ROOT/usr/lib:$CORTEX_ROOT/usr/local/lib:$CORTEX_ROOT/usr/lib/systemd:$CORTEX_ROOT/lib/systemd:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/systemd:$CORTEX_ROOT/lib/arm-linux-gnueabihf/systemd"

    local HOOK_LIB="${HOOK_LIB:-}"
    if [ -z "$HOOK_LIB" ]; then
        if [ -f "$CORTEX_ROOT/usr/lib/libcortex-hook.so" ]; then
            HOOK_LIB="$CORTEX_ROOT/usr/lib/libcortex-hook.so"
        elif [ -f "$CORTEX_ROOT/lib/libcortex-hook.so" ]; then
            HOOK_LIB="$CORTEX_ROOT/lib/libcortex-hook.so"
        fi
    fi

    local CERT_FILE="$CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt"
    local CERT_DIR="$CORTEX_ROOT/etc/ssl/certs:/system/etc/security/cacerts"
    local CURRENT_DIR="$PWD"

    local ENV_SETUP="unset CORTEX_IDS CORTEX_BECOME; \
export CORTEX_ROOT=$(sq "$CORTEX_ROOT"); \
export PATH=$(sq "$CORTEX_PATH"); \
export LD_LIBRARY_PATH=$(sq "$CORTEX_LD"); \
export GLIBC_TUNABLES='glibc.pthread.rseq=0'; \
export LANG='C.UTF-8'; \
export LC_ALL='C.UTF-8'; \
export LOCPATH=$(sq "$CORTEX_ROOT/usr/lib/locale"); \
export USER='root'; \
export LOGNAME='root'; \
export HOSTNAME='localhost'; \
export TERM=$(sq "${TERM:-xterm-256color}"); \
export COLORTERM=$(sq "${COLORTERM:-truecolor}"); \
export SSL_CERT_FILE=$(sq "$CERT_FILE"); \
export SSL_CERT_DIR=$(sq "$CERT_DIR"); \
export CURL_CA_BUNDLE=$(sq "$CERT_FILE"); \
export NODE_EXTRA_CA_CERTS=$(sq "$CERT_FILE"); \
export REQUESTS_CA_BUNDLE=$(sq "$CERT_FILE"); \
export TZDIR=$(sq "$CORTEX_ROOT/usr/share/zoneinfo"); \
export TERMINFO=$(sq "$CORTEX_ROOT/usr/share/terminfo"); \
export TERMINFO_DIRS=$(sq "$CORTEX_ROOT/usr/share/terminfo:$CORTEX_ROOT/lib/terminfo:$CORTEX_ROOT/etc/terminfo:/usr/share/terminfo"); \
export GODEBUG='netdns=cgo'; \
export BROWSER='/usr/local/bin/xdg-open'; \
export PS1='\[\033[01;31m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]# ';"

    if [ -n "$HOOK_LIB" ]; then
        ENV_SETUP="$ENV_SETUP export LD_PRELOAD=$(sq "$HOOK_LIB");"
    fi

    local GLIBC_LDSO="${GLIBC_LDSO:-}" cand_ld
    if [ -z "$GLIBC_LDSO" ]; then
        for cand_ld in \
            "$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
            "$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/ld-2.43.so" \
            "$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/ld-2.39.so" \
            "$CORTEX_ROOT/lib/ld-linux-aarch64.so.1" \
            "$CORTEX_ROOT/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
            "$CORTEX_ROOT/usr/lib/ld-linux-aarch64.so.1" \
            "$CORTEX_ROOT/usr/lib64/ld-linux-aarch64.so.1" \
            "$CORTEX_ROOT/lib64/ld-linux-aarch64.so.1" \
            "$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" \
            "$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-2.43.so" \
            "$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-2.39.so" \
            "$CORTEX_ROOT/lib/ld-linux-armhf.so.3" \
            "$CORTEX_ROOT/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" \
            "$CORTEX_ROOT/usr/lib/ld-linux-armhf.so.3"; do
            if [ -f "$cand_ld" ] || [ -x "$cand_ld" ] || [ -L "$cand_ld" ]; then
                GLIBC_LDSO="$cand_ld"
                break
            fi
        done
    fi

    local LAUNCH_SHELL
    if [ -n "$GLIBC_LDSO" ]; then
        LAUNCH_SHELL="$(sq "$GLIBC_LDSO") --library-path $(sq "$CORTEX_LD") $(sq "$CORTEX_SHELL")"
    else
        LAUNCH_SHELL="$(sq "$CORTEX_SHELL")"
    fi

    local BASE_INIT="$ENV_SETUP export HOME=$(sq "$ROOT_HOME"); cd $(sq "$CURRENT_DIR") 2>/dev/null || cd $(sq "$ROOT_HOME") 2>/dev/null;"

    if [ "${1:-}" = "-c" ]; then
        shift
        if [ "$#" -eq 0 ]; then
            exec "${RUN_SU[@]}" -c "$BASE_INIT exec $LAUNCH_SHELL -i"
        elif [ "$#" -eq 1 ]; then
            exec "${RUN_SU[@]}" -c "$BASE_INIT exec $LAUNCH_SHELL -c $(sq "$1")"
        else
            exec "${RUN_SU[@]}" -c "$BASE_INIT exec $LAUNCH_SHELL -c $(sq "$1") $(quote_args "${@:2}")"
        fi
    elif [ "${1:-}" = "-" ] || [ "${1:-}" = "-l" ] || [ "${1:-}" = "--login" ]; then
        exec "${RUN_SU[@]}" -c "$ENV_SETUP export HOME=$(sq "$ROOT_HOME"); cd $(sq "$ROOT_HOME") 2>/dev/null; exec $LAUNCH_SHELL -l -i"
    elif [ "$#" -eq 0 ]; then
        exec "${RUN_SU[@]}" -c "$BASE_INIT exec $LAUNCH_SHELL -i"
    else
        local USER_CMD
        USER_CMD="$(quote_args "$@")"
        exec "${RUN_SU[@]}" -c "$BASE_INIT exec $LAUNCH_SHELL -c $(sq "$USER_CMD")"
    fi
}

case "$INVOKED_AS" in
    sudo)
        sudo_direct "$@"
        ;;
    su)
        su_direct "$@"
        ;;
    root)
        su_direct - "$@"
        ;;
    tsu|android-su)
        android_su_direct "$@"
        ;;
    *)
        su_direct "$@"
        ;;
esac
