#!/bin/sh
# Cortex POSIX-shell implementation of systemd-sysusers(8).
# Parses sysusers.d(5) config files (u, u!, g, m) and creates missing system users/groups
# in /etc/passwd, /etc/group, /etc/shadow, and /etc/gshadow so Ubuntu 26.04 package maintainer
# scripts (such as dbus-system-bus-common / dbus) can create their required system accounts.

ROOT=""
READ_STDIN=0
INLINE=0
DRY_RUN=0
RC=0

# Parse options while keeping positional arguments intact in "$@" (preserves whitespace/quotes).
COUNT=$#
POS_COUNT=0
END_OPTS=0
i=0
while [ "$i" -lt "$COUNT" ]; do
    i=$((i + 1))
    arg="$1"
    shift
    if [ "$END_OPTS" = 1 ]; then
        set -- "$@" "$arg"
        POS_COUNT=$((POS_COUNT + 1))
        continue
    fi
    case "$arg" in
        --root=*)
            ROOT="${arg#--root=}"
            ;;
        --root)
            if [ "$i" -lt "$COUNT" ]; then
                i=$((i + 1))
                ROOT="$1"
                shift
            fi
            ;;
        --inline)
            INLINE=1
            ;;
        --dry-run)
            DRY_RUN=1
            ;;
        --replace=*|--image=*|--image-policy=*|--tldr|--cat-config|--no-pager|--quiet|-q)
            ;;
        --replace|--image|--image-policy)
            if [ "$i" -lt "$COUNT" ]; then
                i=$((i + 1))
                shift
            fi
            ;;
        -h|--help|--version)
            exit 0
            ;;
        --)
            END_OPTS=1
            ;;
        -)
            READ_STDIN=1
            ;;
        -*)
            ;;
        *)
            set -- "$@" "$arg"
            POS_COUNT=$((POS_COUNT + 1))
            ;;
    esac
done

# Dry run must not create directories or modify account databases.
if [ "$DRY_RUN" = 1 ]; then
    exit 0
fi

ROOT="${ROOT%/}"
PASSWD="$ROOT/etc/passwd"
GROUP="$ROOT/etc/group"
SHADOW="$ROOT/etc/shadow"
GSHADOW="$ROOT/etc/gshadow"

mkdir -p "$ROOT/etc" || exit 1
[ -f "$PASSWD" ] || : >"$PASSWD" || exit 1
[ -f "$GROUP" ] || : >"$GROUP" || exit 1

SEP="$(printf '\037')"

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
    printf '%s:x:%s:\n' "$name" "$gid" >>"$GROUP" || return 1
    if [ -f "$GSHADOW" ] && ! awk -F: -v n="$name" '$1 == n { f=1; exit } END { exit (f ? 0 : 1) }' "$GSHADOW" 2>/dev/null; then
        printf '%s:!::\n' "$name" >>"$GSHADOW" || return 1
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
                gid="$(ensure_group "$req_grp" "-")" || return 1
                ;;
            *)
                gid="$req_grp"
                ;;
        esac
    else
        gid="$(ensure_group "$name" "$req_uid")" || return 1
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
    printf '%s:x:%s:%s:%s:%s:%s\n' "$name" "$uid" "$gid" "$gecos" "$home" "$shell" >>"$PASSWD" || return 1
    if [ -f "$SHADOW" ] && ! awk -F: -v n="$name" '$1 == n { f=1; exit } END { exit (f ? 0 : 1) }' "$SHADOW" 2>/dev/null; then
        printf '%s:*:19700:0:99999:7:::\n' "$name" >>"$SHADOW" || return 1
    fi
}

add_member() {
    local user="$1" group="$2"
    [ -n "$user" ] && [ -n "$group" ] || return 0
    if ! user_exists "$user"; then
        ensure_user "$user" "-" "" "" "" || return 1
    fi
    ensure_group "$group" "-" >/dev/null || return 1
    local tmp="$GROUP.sysusers.$$"
    if awk -F: -v OFS=: -v u="$user" -v g="$group" '
        $1 == g {
            n = split($4, a, ",")
            found = 0
            for (i = 1; i <= n; i++) if (a[i] == u) found = 1
            if (!found) $4 = ($4 == "" ? u : $4 "," u)
        }
        { print }
    ' "$GROUP" >"$tmp"; then
        mv -f "$tmp" "$GROUP" || { rm -f "$tmp"; return 1; }
    else
        rm -f "$tmp" 2>/dev/null || true
        return 1
    fi
}

# Parse sysusers.d lines respecting double-quoted fields, outputting non-whitespace \037-separated
# fields (so empty fields like "" are never collapsed by POSIX read). Emits g/u/u! before m so
# explicit user definitions are honored before membership records implicitly create missing users.
process_stream() {
    local parsed_tmp
    parsed_tmp="$(mktemp 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}/sysusers.parsed.$$")"
    awk -v sep="$SEP" '
        function format_rec(arr, n,   i, line) {
            line = ""
            for (i = 1; i <= 6; i++) {
                line = line (i <= n ? arr[i] : "") (i < 6 ? sep : "")
            }
            return line
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
            if (n < 2) next
            rec = format_rec(arr, n)
            if (arr[1] == "m") {
                deferred[++m_cnt] = rec
            } else {
                print rec
            }
        }
        END {
            for (i = 1; i <= m_cnt; i++) {
                print deferred[i]
            }
        }
    ' >"$parsed_tmp" || { rm -f "$parsed_tmp"; return 1; }

    local status=0
    while IFS="$SEP" read -r typ name id_spec gecos home shell; do
        case "$typ" in
            u|u!)
                ensure_user "$name" "$id_spec" "$gecos" "$home" "$shell" || status=1
                ;;
            g)
                ensure_group "$name" "$id_spec" >/dev/null || status=1
                ;;
            m)
                add_member "$name" "$id_spec" || status=1
                ;;
        esac
    done <"$parsed_tmp"
    rm -f "$parsed_tmp"
    return "$status"
}

find_conf() {
    local arg="$1" d
    case "$arg" in
        /*)
            if [ -n "$ROOT" ]; then
                [ -r "$ROOT$arg" ] && { printf '%s' "$ROOT$arg"; return 0; }
            else
                [ -r "$arg" ] && { printf '%s' "$arg"; return 0; }
            fi
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

# Collect all input streams into one stream so explicit u/g definitions across all files run before m.
collect_all_inputs() {
    if [ "$READ_STDIN" = 1 ]; then
        cat
        printf '\n'
    fi
    if [ "$POS_COUNT" -gt 0 ]; then
        for arg in "$@"; do
            if [ "$INLINE" = 1 ]; then
                printf '%s\n' "$arg"
            else
                conf="$(find_conf "$arg" || true)"
                if [ -n "$conf" ] && [ -r "$conf" ]; then
                    cat "$conf"
                    printf '\n'
                fi
            fi
        done
    elif [ "$READ_STDIN" = 0 ]; then
        # Select highest-priority file per basename, then sort globally by basename (sysusers.d(5)).
        local seen=" " list_tmp d f base
        list_tmp="$(mktemp 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}/sysusers.list.$$")"
        : >"$list_tmp"
        for d in "$ROOT/etc/sysusers.d" "$ROOT/run/sysusers.d" "$ROOT/usr/local/lib/sysusers.d" "$ROOT/usr/lib/sysusers.d" "$ROOT/lib/sysusers.d"; do
            [ -d "$d" ] || continue
            for f in "$d"/*.conf; do
                [ -r "$f" ] || continue
                base="$(basename "$f")"
                case "$seen" in
                    *" $base "*) continue ;;
                esac
                seen="$seen$base "
                printf '%s\t%s\n' "$base" "$f" >>"$list_tmp"
            done
        done
        LC_ALL=C sort -t "$(printf '\t')" -k1,1 "$list_tmp" | while IFS="$(printf '\t')" read -r _base path; do
            [ -r "$path" ] && cat "$path"
            printf '\n'
        done
        rm -f "$list_tmp"
    fi
}

INPUT_TMP="$(mktemp 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}/sysusers.in.$$")"
collect_all_inputs "$@" >"$INPUT_TMP"
if [ -s "$INPUT_TMP" ]; then
    process_stream <"$INPUT_TMP" || RC=1
fi
rm -f "$INPUT_TMP"

exit "$RC"
