#!/bin/bash
# cortex-backup: back up and restore your Cortex home directory.
#
#   cortex-backup [create] [-o FILE] [--include-caches]
#   cortex-backup restore FILE [--yes] [--no-packages]
#   cortex-backup packages [--yes]
#   cortex-backup list [DIR]
#
# A backup is a .tar.gz with the paths "home/..." and "root/..." relative to the Cortex root, so it
# can be restored into a fresh install. The installed programs (/usr, /etc) are not part of it, but
# the list of the packages installed with apt is, with the apt sources of other vendors
# (home/.cortex/backup): restore installs the ones that are missing.
set -u

ROOT="${CORTEX_ROOT:-}"
# In root mode the rootfs is the chroot itself
[ -z "$ROOT" ] && [ -n "${CORTEX_CHROOT:-}" ] && ROOT=/
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
  cortex-backup restore FILE [--yes] [--no-packages]
      Put the files from FILE back. Existing files with the same name are overwritten. Then the
      packages that were installed with apt when the backup was made and are missing are installed
      again (after asking; --no-packages leaves them out).
  cortex-backup packages [--yes]
      Install the packages of the last restored backup that are missing.
  cortex-backup list [DIR]
      Show backups in DIR (default: $DEFAULT_DIR).
Environment: CORTEX_BACKUP_DIR changes the default directory.
EOF
}

die() {
    echo "cortex-backup: $*" >&2
    exit 1
}

# The packages installed with apt (not the ones they pulled in) and the apt sources of other vendors
# with their keys, kept in the home directory so that they are part of the backup
PKG_REL="home/.cortex/backup"
PKG_DIR="$ROOT/$PKG_REL"

ask() { # ask QUESTION DEFAULT(y|n): true for yes; $yes answers yes without asking
    local answer=""
    if [ "${yes:-0}" -eq 1 ]; then
        return 0
    fi
    if [ "$2" = y ]; then printf '%s [Y/n] ' "$1"; else printf '%s [y/N] ' "$1"; fi
    read -r answer || answer=""
    case "$answer" in
        y|Y|yes|YES) return 0 ;;
        "") [ "$2" = y ] ;;
        *) return 1 ;;
    esac
}

save_package_lists() {
    command -v apt-mark >/dev/null 2>&1 || return 0
    mkdir -p "$PKG_DIR" 2>/dev/null || return 0
    if ! apt-mark showmanual > "$PKG_DIR/packages.list.new" 2>/dev/null; then
        rm -f "$PKG_DIR/packages.list.new"
        return 0
    fi
    mv "$PKG_DIR/packages.list.new" "$PKG_DIR/packages.list"
    rm -rf "$PKG_DIR/apt"
    local f sub
    for f in "$ROOT"/etc/apt/sources.list.d/* "$ROOT"/etc/apt/keyrings/*; do
        [ -f "$f" ] || continue
        case "${f##*/}" in ubuntu.sources*|*.save|*.distUpgrade|.*) continue ;; esac # Cortex's own, apt's copies
        sub="${f#"$ROOT"/etc/apt/}"
        mkdir -p "$PKG_DIR/apt/${sub%/*}" && cp -p "$f" "$PKG_DIR/apt/$sub"
    done
    echo "Package list: $(wc -l < "$PKG_DIR/packages.list") packages installed with apt"
}

# Package names as apt-mark writes them (an architecture may follow); nothing that apt would take as an option
valid_packages() {
    grep -E '^[a-z0-9][a-z0-9+.-]*(:[a-z0-9-]+)?$' "$1"
}

# The packages of the backup that are not installed
missing_packages() {
    local native installed
    native="$(dpkg --print-architecture 2>/dev/null)"
    installed="$(dpkg-query -W -f='${Package} ${Architecture} ${db:Status-Abbrev}\n' 2>/dev/null |
        awk -v n="$native" '$3 ~ /^.i/ { print $1 ":" $2; if ($2 == n || $2 == "all") print $1 }')"
    valid_packages "$PKG_DIR/packages.list" | grep -Fxv -f <(printf '%s\n' "$installed") || true
}

# Puts back the apt sources of other vendors that are not there (after asking: they decide what apt installs)
restore_apt_sources() {
    local f sub new=()
    for f in "$PKG_DIR"/apt/sources.list.d/* "$PKG_DIR"/apt/keyrings/*; do
        [ -f "$f" ] && [ ! -L "$f" ] || continue
        sub="${f#"$PKG_DIR"/apt/}"
        case "${sub##*/}" in ""|.*|*/*) continue ;; esac
        [ -e "$ROOT/etc/apt/$sub" ] || new+=("$sub")
    done
    [ ${#new[@]} -gt 0 ] || return 0
    echo "The backup has apt sources and keys of other vendors that are not set up here:"
    printf '  /etc/apt/%s\n' "${new[@]}"
    if ask "Add them (apt then installs packages from them)?" n; then
        for sub in "${new[@]}"; do
            mkdir -p "$ROOT/etc/apt/${sub%/*}" && cp "$PKG_DIR/apt/$sub" "$ROOT/etc/apt/$sub" && chmod 644 "$ROOT/etc/apt/$sub"
        done
    fi
}

# Installs the packages of the backup that are missing: all at once, else one by one, so that one
# that is gone from the archive does not stop the others
restore_packages() {
    if [ ! -f "$PKG_DIR/packages.list" ]; then
        echo "No package list in $PKG_DIR (backups from Cortex 1.26.38 on have one)."
        return 0
    fi
    if ! command -v apt-get >/dev/null 2>&1 || ! command -v dpkg-query >/dev/null 2>&1; then
        echo "apt is not available here; the packages of the backup are listed in $PKG_DIR/packages.list"
        return 0
    fi
    restore_apt_sources
    local missing=()
    mapfile -t missing < <(missing_packages)
    if [ ${#missing[@]} -eq 0 ]; then
        echo "The packages of the backup are installed."
        return 0
    fi
    echo "${#missing[@]} packages of the backup are not installed: ${missing[*]}"
    if ! ask "Install them now?" y; then
        echo "Install them later with: cortex-backup packages"
        return 0
    fi
    apt-get update || echo "cortex-backup: apt-get update failed; trying with the package lists there are" >&2
    if apt-get install -y "${missing[@]}"; then
        echo "Installed the ${#missing[@]} packages of the backup."
        return 0
    fi
    echo "Installing them one by one..."
    local p failed=()
    for p in "${missing[@]}"; do
        apt-get install -y "$p" || failed+=("$p")
    done
    if [ ${#failed[@]} -gt 0 ]; then
        echo "cortex-backup: these packages could not be installed: ${failed[*]}" >&2
        return 1
    fi
    echo "Installed the ${#missing[@]} packages of the backup."
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

    save_package_lists
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
    local file="" packages=1
    yes=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --yes|-y) yes=1; shift ;;
            --no-packages) packages=0; shift ;;
            -h|--help) usage; exit 0 ;;
            -*) die "unknown option: $1" ;;
            *) [ -z "$file" ] || die "only one backup file can be restored at a time"; file="$1"; shift ;;
        esac
    done
    [ -n "$file" ] || die "restore needs a backup file"
    [ -f "$file" ] || die "$file does not exist"
    tar -tzf "$file" >/dev/null 2>&1 || die "$file is not a valid .tar.gz archive"
    check_archive_paths "$file" || die "$file is not a Cortex backup (or is unsafe), nothing was restored"

    printf 'This overwrites files in %s/home and %s/root with the ones from the backup.\n' "$ROOT" "$ROOT"
    if ! ask "Continue?" n; then
        echo "Cancelled."
        exit 1
    fi

    tar -C "$ROOT" -xzpf "$file" --no-same-owner || die "extraction failed (the backup may be incomplete)"
    echo "Restored $file"
    # The packages, when this backup has their list
    if [ "$packages" -eq 1 ] && tar -tzf "$file" 2>/dev/null | grep -qx "$PKG_REL/packages.list"; then
        restore_packages || echo "cortex-backup: try again with: cortex-backup packages" >&2
    fi
    echo "Run 'reload' or open a new session to pick up restored shell settings."
}

do_packages() {
    yes=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --yes|-y) yes=1; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option: $1" ;;
        esac
    done
    restore_packages
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
    packages) shift; do_packages "$@" ;;
    list) shift; do_list "$@" ;;
    -h|--help|help) usage ;;
    -*) do_create "$@" ;;
    *) die "unknown command: $cmd (try --help)" ;;
esac
