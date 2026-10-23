#!/bin/bash
# Installed by Cortex as /usr/local/bin/apt and /usr/local/bin/apt-get (ahead of /usr/bin in PATH).
#
# Cortex's Ubuntu comes without package lists: the ones of the build would be weeks old and made the
# app twice as big. The first command that needs them ("apt install ...") runs "apt-get update"
# first, as a user would have to; with lists in place this only starts the real program.

real_dir="${CORTEX_APT_BIN:-/usr/bin}"
lists="${CORTEX_APT_LISTS:-/var/lib/apt/lists}"
name="${0##*/}"
case "$name" in
    apt | apt-get) ;;
    *) name=apt-get ;;
esac

needs_lists=0
for arg in "$@"; do
    case "$arg" in
        install | reinstall | upgrade | full-upgrade | dist-upgrade | build-dep | source | download | \
            search | showsrc | satisfy | changelog)
            needs_lists=1
            break
            ;;
    esac
done

if [ "$needs_lists" = 1 ] && ! compgen -G "$lists/*_Packages*" >/dev/null; then
    echo "Cortex: the package lists are empty, running \"apt-get update\" first." >&2
    "$real_dir/apt-get" update || exit $?
fi
exec "$real_dir/$name" "$@"
