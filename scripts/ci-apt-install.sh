#!/bin/bash
# ci-apt-install.sh PACKAGE...: installs packages on a CI runner. The runner's own mirror
# (azure.archive.ubuntu.com) did not resolve for the whole of two runs in a row, and apt then tried
# every file for twenty minutes before giving up: when the mirror cannot be reached, or the install
# fails, Ubuntu's archive is used instead.
set -u

use_ubuntu_archive() {
    echo "ci-apt-install: using archive.ubuntu.com instead of the runner's mirror"
    if [ -f /etc/apt/apt-mirrors.txt ]; then
        printf 'http://archive.ubuntu.com/ubuntu/\tpriority:1\n' | sudo tee /etc/apt/apt-mirrors.txt >/dev/null
    fi
    sudo sed -i 's|^URIs: .*azure\.archive\.ubuntu\.com.*|URIs: http://archive.ubuntu.com/ubuntu/|; s|http://azure\.archive\.ubuntu\.com/ubuntu|http://archive.ubuntu.com/ubuntu|g' \
        /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null
    return 0
}

install() {
    sudo apt-get update -o Acquire::Retries=3 -qq &&
        sudo apt-get install -y --no-install-recommends -o Acquire::Retries=3 "$@"
}

if grep -qs azure.archive.ubuntu.com /etc/apt/apt-mirrors.txt /etc/apt/sources.list.d/*.sources /etc/apt/sources.list &&
    ! getent hosts azure.archive.ubuntu.com >/dev/null 2>&1; then
    use_ubuntu_archive
fi
install "$@" && exit 0
use_ubuntu_archive
install "$@"
