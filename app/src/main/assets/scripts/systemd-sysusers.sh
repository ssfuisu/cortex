#!/bin/sh
# Cortex POSIX-shell implementation of systemd-sysusers(8).
# Parses sysusers.d(5) config files (u, u!, g, m) and creates missing system users/groups
# in /etc/passwd, /etc/group, /etc/shadow, and /etc/gshadow so Ubuntu 26.04 package maintainer
# scripts (such as dbus-system-bus-common / dbus) can create their required system accounts.

ROOT=""
READ_STDIN=0
ARGS=""

while [ $# -gt 0 ]; do
    case "$1" in
        --root=*) ROOT="${1#--root=}"; shift ;;
        --root) ROOT="${2:-}"; shift; [ $# -gt 0 ] && shift ;;
        --replace=*|--image=*|--image-policy=*|--tldr|--cat-config|--inline|--no-pager|--quiet|-q|--dry-run)
            shift ;;
        --replace|--image|--image-policy)
            shift; [ $# -gt 0 ] && shift ;;
        -h|--help|--version)
            exit 0 ;;
        --)
            shift
            while [ $# -gt 0 ]; do
                ARGS="$ARGS $1"
                shift
            done
            break ;;
        -)
            READ_STDIN=1
            shift ;;
        -*)
            shift ;;
        *)
            ARGS="$ARGS $1"
            shift ;;
    esac
done

ROOT="${ROOT%/}"
PASSWD="$ROOT/etc/passwd"
GROUP="$ROOT/etc/group"
SHADOW="$ROOT/etc/shadow"
GSHADOW="$ROOT/etc/gshadow"

mkdir -p "$ROOT/etc" 2>/dev/null || true
[ -f "$PASSWD" ] || : >"$PASSWD" 2>/dev/null || true
[ -f "$GROUP" ] || : >"$GROUP" 2>/dev/null || true

next_free_gid() {
    awk -F: '
        { used[$3] = 1 }
        END {
            for (id = 100; id < 1000; id++) {
                if (!used[id]) { print id; exit }
            }
            for (id = 1001; id < 60000; id++) {
                if (!used[id]) { print id; exit }
            }
            print 999
        }
    ' "$GROUP" 2>/dev/null
}

next_free_uid() {
    awk -F: '
        { used[$3] = 1 }
        END {
            for (id = 100; id < 1000; id++) {
                if (!used[id]) { print id; exit }
            }
            for (id = 1001; id < 60000; id++) {
                if (!used[id]) { print id; exit }
            }
            print 999
        }
    ' "$PASSWD" 2>/dev/null
}

lookup_gid() {
    awk -F: -v n="$1" '$1 == n { print $3; exit }' "$GROUP" 2>/dev/null
}

gid_in_use() {
    awk -F: -v g="$1" '$3 == g { found=1; exit } END { exit (found ? 0 : 1) }' "$GROUP" 2>/dev/null
}

uid_in_use() {
    awk -F: -v u="$1" '$3 == u { found=1; exit } END { exit (found ? 0 : 1) }' "$PASSWD" 2>/dev/null
}

user_exists() {
    awk -F: -v n="$1" '$1 == n { found=1; exit } END { exit (found ? 0 : 1) }' "$PASSWD" 2>/dev/null
}

ensure_group() {
    local name="$1" req_gid="$2" gid
    [ -n "$name" ] || return 0
    gid="$(lookup_gid "$name")"
    if [ -n "$gid" ]; then
        printf '%s' "$gid"
        return 0
    fi
    case "$req_gid" in
        ""|-|*:*) gid="" ;;
        *[!0-9]*) gid="" ;;
        *)
            if ! gid_in_use "$req_gid"; then
                gid="$req_gid"
            else
                gid=""
            fi
            ;;
    esac
    [ -n "$gid" ] || gid="$(next_free_gid)"
    printf '%s:x:%s:\n' "$name" "$gid" >>"$GROUP" 2>/dev/null || true
    if [ -f "$GSHADOW" ] && ! awk -F: -v n="$name" '$1 == n { f=1; exit } END { exit (f ? 0 : 1) }' "$GSHADOW" 2>/dev/null; then
        printf '%s:!::\n' "$name" >>"$GSHADOW" 2>/dev/null || true
    fi
    printf '%s' "$gid"
}

ensure_user() {
    local name="$1" id_spec="$2" gecos="$3" home="$4" shell="$5"
    [ -n "$name" ] || return 0
    [ "$gecos" = "-" ] && gecos=""
    [ -z "$home" ] || [ "$home" = "-" ] && home="/"
    [ -z "$shell" ] || [ "$shell" = "-" ] && shell="/usr/sbin/nologin"

    local req_uid="" req_grp="" gid="" uid=""
    case "$id_spec" in
        *:*)
            req_uid="${id_spec%%:*}"
            req_grp="${id_spec#*:}"
            ;;
        *)
            req_uid="$id_spec"
            req_grp=""
            ;;
    esac

    if [ -n "$req_grp" ] && [ "$req_grp" != "-" ]; then
        case "$req_grp" in
            *[!0-9]*)
                gid="$(ensure_group "$req_grp" "-")"
                ;;
            *)
                gid="$req_grp"
                ;;
        esac
    else
        gid="$(ensure_group "$name" "$req_uid")"
    fi

    if user_exists "$name"; then
        return 0
    fi

    case "$req_uid" in
        ""|-)
            if [ -n "$gid" ] && ! uid_in_use "$gid"; then
                uid="$gid"
            else
                uid="$(next_free_uid)"
            fi
            ;;
        *[!0-9]*)
            uid="$(next_free_uid)"
            ;;
        *)
            if ! uid_in_use "$req_uid"; then
                uid="$req_uid"
            else
                uid="$(next_free_uid)"
            fi
            ;;
    esac

    [ -n "$gid" ] || gid="$uid"
    printf '%s:x:%s:%s:%s:%s:%s\n' "$name" "$uid" "$gid" "$gecos" "$home" "$shell" >>"$PASSWD" 2>/dev/null || true
    if [ -f "$SHADOW" ] && ! awk -F: -v n="$name" '$1 == n { f=1; exit } END { exit (f ? 0 : 1) }' "$SHADOW" 2>/dev/null; then
        printf '%s:*:19700:0:99999:7:::\n' "$name" >>"$SHADOW" 2>/dev/null || true
    fi
}

add_member() {
    local user="$1" group="$2"
    [ -n "$user" ] && [ -n "$group" ] || return 0
    ensure_group "$group" "-" >/dev/null
    local tmp="$GROUP.sysusers.$$"
    if awk -F: -v OFS=: -v u="$user" -v g="$group" '
        $1 == g {
            n = split($4, a, ",")
            found = 0
            for (i = 1; i <= n; i++) if (a[i] == u) found = 1
            if (!found) $4 = ($4 == "" ? u : $4 "," u)
        }
        { print }
    ' "$GROUP" >"$tmp" 2>/dev/null; then
        mv -f "$tmp" "$GROUP" 2>/dev/null || rm -f "$tmp"
    else
        rm -f "$tmp" 2>/dev/null || true
    fi
}

# Parse a sysusers.d line respecting double-quoted fields, outputting tab-separated fields.
process_stream() {
    awk '
        function emit(arr, n,   i) {
            if (n < 2) return
            for (i = 1; i <= 6; i++) {
                printf "%s%s", (i <= n ? arr[i] : ""), (i < 6 ? "\t" : "\n")
            }
        }
        {
            sub(/\r$/, "")
            sub(/^[ \t]+/, "")
            if ($0 == "" || substr($0, 1, 1) == "#") next
            n = 0
            s = $0
            while (length(s) > 0 && n < 6) {
                sub(/^[ \t]+/, "", s)
                if (s == "") break
                if (substr(s, 1, 1) == "\"") {
                    s = substr(s, 2)
                    tok = ""
                    while (length(s) > 0) {
                        c = substr(s, 1, 1)
                        s = substr(s, 2)
                        if (c == "\\" && length(s) > 0) {
                            tok = tok substr(s, 1, 1)
                            s = substr(s, 2)
                        } else if (c == "\"") {
                            break
                        } else {
                            tok = tok c
                        }
                    }
                    arr[++n] = tok
                } else {
                    match(s, /^[^ \t]+/)
                    arr[++n] = substr(s, 1, RLENGTH)
                    s = substr(s, RLENGTH + 1)
                }
            }
            emit(arr, n)
        }
    ' | while IFS="$(printf '\t')" read -r typ name id_spec gecos home shell; do
        case "$typ" in
            u|u!)
                ensure_user "$name" "$id_spec" "$gecos" "$home" "$shell"
                ;;
            g)
                ensure_group "$name" "$id_spec" >/dev/null
                ;;
            m)
                add_member "$name" "$id_spec"
                ;;
        esac
    done
}

find_conf() {
    local arg="$1" d
    case "$arg" in
        /*)
            [ -r "$arg" ] && { printf '%s' "$arg"; return 0; }
            [ -n "$ROOT" ] && [ -r "$ROOT$arg" ] && { printf '%s' "$ROOT$arg"; return 0; }
            return 1
            ;;
    esac
    for d in "$ROOT/etc/sysusers.d" "$ROOT/run/sysusers.d" "$ROOT/usr/local/lib/sysusers.d" "$ROOT/usr/lib/sysusers.d" "$ROOT/lib/sysusers.d"; do
        if [ -r "$d/$arg" ]; then
            printf '%s' "$d/$arg"
            return 0
        fi
    done
    return 1
}

if [ "$READ_STDIN" = 1 ]; then
    process_stream
fi

if [ -n "$ARGS" ]; then
    for arg in $ARGS; do
        conf="$(find_conf "$arg" || true)"
        if [ -n "$conf" ] && [ -r "$conf" ]; then
            process_stream <"$conf"
        fi
    done
elif [ "$READ_STDIN" = 0 ]; then
    SEEN=" "
    for d in "$ROOT/etc/sysusers.d" "$ROOT/run/sysusers.d" "$ROOT/usr/local/lib/sysusers.d" "$ROOT/usr/lib/sysusers.d" "$ROOT/lib/sysusers.d"; do
        [ -d "$d" ] || continue
        for f in "$d"/*.conf; do
            [ -r "$f" ] || continue
            base="$(basename "$f")"
            case "$SEEN" in
                *" $base "*) continue ;;
            esac
            SEEN="$SEEN$base "
            process_stream <"$f"
        done
    done
fi

exit 0
