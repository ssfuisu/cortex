#!/bin/bash
# cortex-backup: back up and restore your Cortex home directory.
#
#   cortex-backup [create] [-o FILE] [--include-caches]
#   cortex-backup restore FILE [--yes]
#   cortex-backup list [DIR]
#
# A backup is a .tar.gz with the paths "home/..." and "root/..." relative to the Cortex root, so it
# can be restored into a fresh install. Installed packages (/usr, /etc) are not part of it: after
# restoring, reinstall them with apt.
set -u

ROOT="${CORTEX_ROOT:-}"
if [ -z "$ROOT" ] || [ ! -d "$ROOT" ]; then
    echo "cortex-backup: CORTEX_ROOT is not set or not a directory (run this inside Cortex)." >&2
    exit 1
fi

DEFAULT_DIR="${CORTEX_BACKUP_DIR:-}"
if [ -z "$DEFAULT_DIR" ]; then
    if [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then
        DEFAULT_DIR=/sdcard/Download
    else
        DEFAULT_DIR="$ROOT/home/backups"
    fi
fi

usage() {
    cat <<EOF
Usage:
  cortex-backup [create] [-o FILE] [--include-caches]
      Save your home directory (and /root) to FILE.
      Default: $DEFAULT_DIR/cortex-backup-<date>.tar.gz
      Caches (~/.cache, ~/.npm/_cacache) and the ~/storage link are left out unless --include-caches.
  cortex-backup restore FILE [--yes]
      Put the files from FILE back. Existing files with the same name are overwritten.
  cortex-backup list [DIR]
      Show backups in DIR (default: $DEFAULT_DIR).
Environment: CORTEX_BACKUP_DIR changes the default directory.
EOF
}

die() {
    echo "cortex-backup: $*" >&2
    exit 1
}

do_create() {
    local out="" include_caches=0
    while [ $# -gt 0 ]; do
        case "$1" in
            -o|--output)
                [ $# -ge 2 ] || die "$1 needs a file name"
                out="$2"
                shift 2
                ;;
            --include-caches) include_caches=1; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option: $1" ;;
        esac
    done

    if [ -z "$out" ]; then
        mkdir -p "$DEFAULT_DIR" 2>/dev/null || die "cannot create $DEFAULT_DIR"
        out="$DEFAULT_DIR/cortex-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
    fi
    case "$out" in
        /*) ;;
        *) out="$PWD/$out" ;;
    esac
    [ -e "$out" ] && die "$out already exists"
    mkdir -p "$(dirname "$out")" 2>/dev/null || die "cannot create the folder for $out"

    local paths=()
    [ -d "$ROOT/home" ] && paths+=(home)
    [ -d "$ROOT/root" ] && paths+=(root)
    [ ${#paths[@]} -gt 0 ] || die "nothing to back up"

    local excludes=(--exclude="home/backups")
    if [ "$include_caches" -eq 0 ]; then
        excludes+=(--exclude="home/.cache" --exclude="home/.npm/_cacache" --exclude="home/storage" --exclude="root/.cache")
    fi

    echo "Backing up $ROOT/{${paths[*]}} ..."
    local partial="$out.partial"
    if ! tar -C "$ROOT" -czf "$partial" "${excludes[@]}" "${paths[@]}"; then
        rm -f "$partial"
        die "tar failed, no backup was written"
    fi
    if ! tar -tzf "$partial" >/dev/null 2>&1; then
        rm -f "$partial"
        die "the archive failed its integrity check, no backup was written"
    fi
    mv "$partial" "$out" || die "could not move the archive to $out"
    echo "Backup written: $out ($(du -h "$out" 2>/dev/null | cut -f1))"
}

# Every entry must be home/... or root/... without ".." - an archive from elsewhere must not be able
# to write outside the two directories it is meant to restore.
check_archive_paths() {
    local file="$1" entry bad=0
    while IFS= read -r entry; do
        case "$entry" in
            home|home/*|root|root/*) ;;
            *) echo "cortex-backup: refusing entry outside home/ and root/: $entry" >&2; bad=1 ;;
        esac
        case "/$entry/" in
            */../*) echo "cortex-backup: refusing entry with '..': $entry" >&2; bad=1 ;;
        esac
    done < <(tar -tzf "$file" 2>/dev/null)
    return $bad
}

do_restore() {
    local file="" yes=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --yes|-y) yes=1; shift ;;
            -h|--help) usage; exit 0 ;;
            -*) die "unknown option: $1" ;;
            *) [ -z "$file" ] || die "only one backup file can be restored at a time"; file="$1"; shift ;;
        esac
    done
    [ -n "$file" ] || die "restore needs a backup file"
    [ -f "$file" ] || die "$file does not exist"
    tar -tzf "$file" >/dev/null 2>&1 || die "$file is not a valid .tar.gz archive"
    check_archive_paths "$file" || die "$file is not a Cortex backup (or is unsafe), nothing was restored"

    if [ "$yes" -eq 0 ]; then
        printf 'This overwrites files in %s/home and %s/root with the ones from the backup.\nContinue? [y/N] ' "$ROOT" "$ROOT"
        local answer=""
        read -r answer
        case "$answer" in
            y|Y|yes|YES) ;;
            *) echo "Cancelled."; exit 1 ;;
        esac
    fi

    tar -C "$ROOT" -xzpf "$file" --no-same-owner || die "extraction failed (the backup may be incomplete)"
    echo "Restored $file"
    echo "Run 'reload' or open a new session to pick up restored shell settings."
}

do_list() {
    local dir="${1:-$DEFAULT_DIR}"
    [ -d "$dir" ] || die "$dir does not exist"
    local found=0 f
    for f in "$dir"/cortex-backup-*.tar.gz; do
        [ -e "$f" ] || continue
        found=1
        ls -lh "$f" 2>/dev/null | awk '{print $5, $6, $7, $8, $9}'
    done
    [ "$found" -eq 1 ] || echo "No backups in $dir"
}

cmd="${1:-create}"
case "$cmd" in
    create) shift; do_create "$@" ;;
    restore) shift; do_restore "$@" ;;
    list) shift; do_list "$@" ;;
    -h|--help|help) usage ;;
    -*) do_create "$@" ;;
    *) die "unknown command: $cmd (try --help)" ;;
esac
