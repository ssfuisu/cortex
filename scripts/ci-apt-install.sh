#!/bin/bash
# ci-apt-install.sh PACKAGE...: installs packages on a CI runner. The runner's own mirror
# (azure.archive.ubuntu.com) did not resolve for the whole of two runs in a row, archive.ubuntu.com
# for a third, and apt then tried every file for twenty minutes before giving up. When a mirror
# cannot be reached, or the install from it fails, the next one is used: Ubuntu's archive, then
# kernel.org's mirror of it (apt checks the signatures of what it gets from any of them).
set -u

MIRRORS="archive.ubuntu.com/ubuntu mirrors.edge.kernel.org/ubuntu"

use_mirror() { # host/path
    echo "ci-apt-install: using $1"
    if [ -f /etc/apt/apt-mirrors.txt ]; then
        printf 'http://%s/\tpriority:1\n' "$1" | sudo tee /etc/apt/apt-mirrors.txt >/dev/null
    fi
    sudo sed -i -E "s#^URIs: https?://[^ ]*ubuntu[^ ]*#URIs: http://$1/#; s#https?://[a-z.]*archive\.ubuntu\.com/ubuntu/?#http://$1/#g; s#https?://security\.ubuntu\.com/ubuntu/?#http://$1/#g" \
        /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null
    return 0
}

resolves() {
    getent hosts "${1%%/*}" >/dev/null 2>&1
}

install() {
    sudo apt-get update -o Acquire::Retries=2 -qq &&
        sudo apt-get install -y --no-install-recommends -o Acquire::Retries=2 "$@"
}

# the mirror the runner is set up with, unless its name does not resolve now
if ! grep -qs azure.archive.ubuntu.com /etc/apt/apt-mirrors.txt /etc/apt/sources.list.d/*.sources /etc/apt/sources.list ||
    resolves azure.archive.ubuntu.com; then
    install "$@" && exit 0
fi
for mirror in $MIRRORS; do
    resolves "$mirror" || { echo "ci-apt-install: ${mirror%%/*} does not resolve"; continue; }
    use_mirror "$mirror"
    install "$@" && exit 0
done
echo "ci-apt-install: no mirror worked" >&2
exit 1
