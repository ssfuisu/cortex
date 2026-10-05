#!/bin/bash
# Cortex Root Switcher (su / tsu / sudo)

find_host_su() {
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
    host_which=$(env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c 'command -v su 2>/dev/null || which su 2>/dev/null' 2>/dev/null)
    if [ -n "$host_which" ]; then
        echo "$host_which"
        return 0
    fi

    for cand in /system/bin/su /system/xbin/su /sbin/su /data/adb/ap/bin/su /data/adb/ksu/bin/su /data/adb/magisk/su; do
        if env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data $cand -v >/dev/null 2>&1 || \
           env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c "$cand -v" >/dev/null 2>&1; then
            echo "$cand"
            return 0
        fi
    done

    for p in /system/bin /system/xbin /sbin /vendor/bin /system_ext/bin /product/bin; do
        if [ -x "$p/su" ] || [ -f "$p/su" ] || [ -L "$p/su" ]; then
            echo "$p/su"
            return 0
        fi
    done

    if [ -x "/data/adb/magisk/magisk" ] || [ -f "/data/adb/magisk/magisk" ]; then
        echo "/data/adb/magisk/magisk su"
        return 0
    fi

    echo "su"
    return 0
}

HOST_SU=$(find_host_su)

if [ -z "$HOST_SU" ]; then
    echo "root not found" >&2
    exit 1
fi

RUN_ANDROID_SU() {
    if [ -x "$HOST_SU" ] || [ -f "$HOST_SU" ]; then
        env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES \
          PATH=/system/bin:/system/xbin:/sbin:/vendor/bin \
          ANDROID_ROOT=/system \
          ANDROID_DATA=/data \
          TERM="${TERM:-xterm-256color}" \
          COLORTERM="${COLORTERM:-truecolor}" \
          $HOST_SU "$@"
    else
        env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES \
          PATH=/system/bin:/system/xbin:/sbin:/vendor/bin \
          ANDROID_ROOT=/system \
          ANDROID_DATA=/data \
          TERM="${TERM:-xterm-256color}" \
          COLORTERM="${COLORTERM:-truecolor}" \
          /system/bin/sh -c "exec $HOST_SU \"\$@\"" _ "$@"
    fi
}

CHECK_ROOT() {
    local uid
    uid=$(RUN_ANDROID_SU -c 'id -u 2>/dev/null || /system/bin/id -u 2>/dev/null || /system/xbin/id -u 2>/dev/null || /system/bin/toybox id -u 2>/dev/null || echo "$UID" || echo "$USER_ID"' 2>/dev/null)
    if [ -n "$uid" ] && [ "$uid" -eq 0 ] 2>/dev/null; then
        return 0
    fi

    uid=$(env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c "$HOST_SU -c 'id -u 2>/dev/null || /system/bin/id -u 2>/dev/null || /system/xbin/id -u 2>/dev/null || /system/bin/toybox id -u 2>/dev/null || echo \$UID || echo \$USER_ID'" 2>/dev/null)
    if [ -n "$uid" ] && [ "$uid" -eq 0 ] 2>/dev/null; then
        return 0
    fi

    if RUN_ANDROID_SU -c 'true' 2>/dev/null; then
        return 0
    fi

    if env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c "$HOST_SU -c 'true'" 2>/dev/null; then
        return 0
    fi

    return 1
}

if ! CHECK_ROOT; then
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
CORTEX_LD="$CORTEX_ROOT/lib:$CORTEX_ROOT/usr/lib:$CORTEX_ROOT/lib/aarch64-linux-gnu:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:$CORTEX_ROOT/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:$CORTEX_ROOT/usr/local/lib:$CORTEX_ROOT/usr/lib/systemd:$CORTEX_ROOT/lib/systemd:$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/lib/aarch64-linux-gnu/systemd:$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/systemd:$CORTEX_ROOT/lib/arm-linux-gnueabihf/systemd"
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
    "$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/ld-2.39.so" \
    "$CORTEX_ROOT/lib/ld-linux-aarch64.so.1" \
    "$CORTEX_ROOT/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "$CORTEX_ROOT/usr/lib/ld-linux-aarch64.so.1" \
    "$CORTEX_ROOT/usr/lib64/ld-linux-aarch64.so.1" \
    "$CORTEX_ROOT/lib64/ld-linux-aarch64.so.1" \
    "$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" \
    "$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-2.39.so" \
    "$CORTEX_ROOT/lib/ld-linux-armhf.so.3" \
    "$CORTEX_ROOT/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" \
    "$CORTEX_ROOT/usr/lib/ld-linux-armhf.so.3"; do
    if [ -f "$cand_ld" ] || [ -x "$cand_ld" ] || [ -L "$cand_ld" ]; then
        CORTEX_LD_SO="$cand_ld"
        break
    fi
done

if [ -n "$CORTEX_LD_SO" ]; then
    LAUNCH_SHELL="'$CORTEX_LD_SO' --library-path '$CORTEX_LD' '$CORTEX_SHELL'"
else
    LAUNCH_SHELL="'$CORTEX_SHELL'"
fi

if [ -x "$HOST_SU" ] || [ -f "$HOST_SU" ]; then
    RUN_SU="env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data TERM='${TERM:-xterm-256color}' COLORTERM='${COLORTERM:-truecolor}' $HOST_SU"
else
    RUN_SU="env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data TERM='${TERM:-xterm-256color}' COLORTERM='${COLORTERM:-truecolor}' /system/bin/sh -c \"exec $HOST_SU \\\"\\\$@\\\"\" _"
fi

if [ "$1" = "-c" ]; then
    shift
    exec $RUN_SU -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null || cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -c \"\$@\"" _ "$@"
elif [ "$1" = "-" ] || [ "$1" = "-l" ] || [ "$1" = "--login" ]; then
    exec $RUN_SU -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -l -i"
elif [ "$1" = "root" ]; then
    shift
    if [ "$#" -eq 0 ]; then
        exec $RUN_SU -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null || cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -i"
    else
        exec $RUN_SU -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null || cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -c \"\$*\"" _ "$@"
    fi
elif [ "$#" -eq 0 ]; then
    exec $RUN_SU -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null || cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -i"
else
    exec $RUN_SU -c "$ENV_SETUP export HOME='$ROOT_HOME'; cd '$CURRENT_DIR' 2>/dev/null || cd '$ROOT_HOME' 2>/dev/null; exec $LAUNCH_SHELL -c \"\$*\"" _ "$@"
fi
