#!/bin/bash
# The package compatibility run (compat-nightly.yml): runs inside a Cortex terminal session on the
# emulator, as in-terminal.sh does. Each package is installed on its own and gets a short test of
# what it is used for; $OUT/results.txt has "PASS name" or "FAIL name: ..." per package, which
# compat-summary.sh makes the table of the job summary. A failure here is a finding, not a broken run.
OUT="${CORTEX_E2E_OUT:-/sdcard/Download/cortex-e2e}"
mkdir -p "$OUT"
mkdir "$OUT/started" 2>/dev/null || exit 0 # the command was typed twice: run once
RESULTS="$OUT/results.txt"
: > "$RESULTS"
export DEBIAN_FRONTEND=noninteractive

# As in in-terminal.sh: the emulator's translator does not run every instruction the CPU features it
# reports promise; the crypto libraries use their plain code there
if [ -n "${CORTEX_E2E_EMULATOR:-}" ]; then
    export OPENSSL_armcap=0 GNUTLS_CPUID_OVERRIDE=0x1 NETTLE_FAT_OVERRIDE=none
    mkdir -p /etc/gcrypt
    printf '%s\n' arm-neon arm-aes arm-sha1 arm-sha2 arm-pmull arm-sha3 arm-sm3 arm-sm4 arm-sha512 \
        arm-sve arm-sve2 arm-sveaes arm-svepmull arm-svesha3 arm-svesm4 > /etc/gcrypt/hwf.deny
fi

# CORTEX_COMPAT_ONLY="redis nginx": only those packages (names as in the table), to look closer at some
ONLY=" ${CORTEX_COMPAT_ONLY:-} "
ONLY="${ONLY//,/ }"

# What the system's users and groups look like to the tools that ask (a package's scripts do)
{
    echo "--- id: $(id)"
    echo "--- passwd: $(wc -l < /etc/passwd) lines, group: $(wc -l < /etc/group) lines"
    for u in man www-data nobody _apt redis; do echo "getent passwd $u: $(getent passwd $u || echo none)"; done
    for g in adm tty shadow staff nogroup; do echo "getent group $g: $(getent group $g || echo none)"; done
    echo "--- setpriv --reuid=man: $(setpriv --reuid=man id -u 2>&1)"
    ls -la /etc/passwd* /etc/group* 2>&1
} > "$OUT/diag-users.txt" 2>&1

W="${CORTEX_COMPAT_DIR:-/tmp/cortex-compat}"
rm -rf "$W"
mkdir -p "$W"
cd "$W" || exit 1

# package NAME "APT PACKAGES" EXPECTED COMMAND: installs the packages, runs COMMAND (bash) and
# records whether it succeeded with EXPECTED in its output, and how long the whole took
package() {
    local name="$1" packages="$2" expected="$3" command="$4" out s started=$SECONDS
    if [ "$ONLY" != "  " ] && [[ "$ONLY" != *" $name "* ]]; then return; fi
    printf '\033[36m…\033[0m %s\n' "$name"
    (
        echo "\$ apt-get install -y $packages"
        # timeout puts the command in a process group of its own, which is in the background of the
        # terminal: apt-get changes the terminal's settings at its end and is stopped (SIGTTOU) for good.
        # So: --foreground, and no terminal as input.
        # shellcheck disable=SC2086 # the package list
        timeout --foreground 900 apt-get install -y --no-install-recommends $packages < /dev/null 2>&1 | tail -n 250
        s=${PIPESTATUS[0]}
        [ "$s" = 0 ] || { echo "apt-get failed ($s)"; exit 10; }
        echo "\$ $command"
        out=$(cd "$W" && timeout --foreground 300 bash -c "$command" < /dev/null 2>&1)
        s=$?
        printf '%s\n' "$out"
        [ "$s" = 0 ] || { echo "exit status $s"; exit 11; }
        [[ "$out" == *"$expected"* ]] || { echo "the output lacks \"$expected\""; exit 12; }
    ) > "$OUT/log-$name.txt" 2>&1
    s=$?
    local took=$((SECONDS - started))
    if [ "$s" = 0 ]; then
        echo "PASS $name (${took} s)" >> "$RESULTS"
        printf '\033[32m✓\033[0m %s\n' "$name"
    else
        local why="the test failed"
        [ "$s" = 10 ] && why="apt-get install failed"
        echo "FAIL $name (${took} s): $why: $(tail -n 3 "$OUT/log-$name.txt" | tr '\n' ' ' | cut -c1-300)" >> "$RESULTS"
        printf '\033[31m✗\033[0m %s\n' "$name"
    fi
}

apt-get update > "$OUT/apt-update.txt" 2>&1

# Languages
package python3 "python3" "ok" 'python3 -c "import ssl, sqlite3, json, hashlib, zlib; print(\"ok\")"'
package python3-venv-pip "python3-venv python3-pip" "Successfully installed" \
    'python3 -m venv v && v/bin/pip install --disable-pip-version-check --no-cache-dir six'
package nodejs-npm "nodejs npm" "left-pad ok" \
    'mkdir -p npm && cd npm && npm init -y >/dev/null && npm install --no-audit --no-fund left-pad >/dev/null && node -e "require(\"left-pad\"); console.log(\"left-pad ok\")"'
package c-gcc "build-essential" "hello from C" \
    'printf "#include <stdio.h>\nint main(void) { puts(\"hello from C\"); return 0; }\n" > h.c && gcc -O2 h.c -o h && ./h'
package cpp-g++ "g++" "hello from C++" \
    'printf "#include <iostream>\nint main() { std::cout << \"hello from C++\" << std::endl; }\n" > h.cc && g++ h.cc -o hcc && ./hcc'
package cmake "cmake make" "hello from cmake" \
    'mkdir -p cm && cd cm && printf "#include <stdio.h>\nint main(void) { puts(\"hello from cmake\"); return 0; }\n" > h.c && printf "cmake_minimum_required(VERSION 3.10)\nproject(h C)\nadd_executable(h h.c)\n" > CMakeLists.txt && cmake -S . -B b >/dev/null && cmake --build b >/dev/null && ./b/h'
package rust "rustc cargo" "hello from Rust" \
    'printf "fn main() { println!(\"hello from Rust\"); }\n" > h.rs && rustc h.rs -o hrs && ./hrs'
package go "golang-go" "hello from Go" \
    'printf "package main\nimport \"fmt\"\nfunc main() { fmt.Println(\"hello from Go\") }\n" > h.go && go run h.go'
package java "openjdk-17-jdk-headless" "hello from Java" \
    'printf "class H { public static void main(String[] a) { System.out.println(\"hello from Java\"); } }\n" > H.java && javac H.java && java H'
package php "php-cli" "2" 'php -r "echo 1 + 1, PHP_EOL;"'
package ruby "ruby" "2" 'ruby -e "puts 1 + 1"'
package perl "perl" "2" 'perl -MJSON::PP -e "print JSON::PP->new->decode(q([1,1]))->[0] + 1, qq(\n)"'
package lua "lua5.4" "2" 'lua5.4 -e "print(1 + 1)"'

# Tools
package git "git" "1 file changed" \
    'git init -q g && cd g && echo x > f && git add f && git -c user.name=t -c user.email=t@t commit -q -m x && git show --stat | tail -n 1'
package curl-https "curl ca-certificates" "HTTP/" 'curl -sSI https://ubuntu.com | head -n 1'
package wget "wget" "saved" 'wget -O index.html https://ubuntu.com 2>&1 | tail -n 2'
package jq "jq" "3" "echo '{\"a\": [1, 2]}' | jq '.a | add'"
package sqlite3 "sqlite3" "2" 'sqlite3 :memory: "select 1 + 1;"'
package zip-unzip "zip unzip" "zipped-ok" 'echo zipped-ok > z.txt && zip -q z.zip z.txt && rm z.txt && unzip -q z.zip && cat z.txt'
package xz-zstd "xz-utils zstd" "ok" 'head -c 100000 /dev/urandom > r && xz -k r && zstd -q r && xz -dc r.xz | cmp - r && zstd -dc r.zst | cmp - r && echo ok'
package rsync "rsync" "f" 'mkdir -p a && echo f > a/f && rsync -a a/ b/ && cat b/f'
package openssh-client "openssh-client" "OpenSSH" 'ssh -V'
package htop "htop" "htop" 'htop --version'
package ripgrep "ripgrep" "needle" 'echo needle > n.txt && rg needle n.txt'

# Editors and shells
package vim "vim" "VIM" 'vim --version | head -n 1'
package neovim "neovim" "ok" 'nvim --headless "+lua io.write(\"ok\")" +q'
package tmux "tmux" "tmux" 'tmux -V'
package zsh "zsh" "5." 'zsh -c "echo \$ZSH_VERSION"'
package fish "fish" "hi" 'fish -c "echo hi"'

# Servers
package redis "redis-server" "PONG" \
    'redis-server --port 6390 --daemonize yes --save "" >/dev/null && sleep 2 && redis-cli -p 6390 ping; redis-cli -p 6390 shutdown nosave >/dev/null 2>&1'
package nginx "nginx-light" "hello from nginx" \
    'mkdir -p ng && echo "hello from nginx" > ng/index.html && printf "daemon on; pid %s/ng/pid; error_log %s/ng/err; events {} http { access_log off; server { listen 8088; root %s/ng; } }\n" "$PWD" "$PWD" "$PWD" > ng/conf && nginx -c "$PWD/ng/conf" && sleep 1 && curl -s http://127.0.0.1:8088/; nginx -c "$PWD/ng/conf" -s stop'
# As on Ubuntu: the package's cluster, started as a service, and psql as the postgres user
package postgresql "postgresql" "1" \
    'service postgresql start && sleep 5 && su postgres -s /bin/sh -c "psql -tAc \"select 1\""; s=$?; service postgresql stop >/dev/null 2>&1; exit $s'

passed=$(grep -c '^PASS' "$RESULTS")
failed=$(grep -c '^FAIL' "$RESULTS")
echo "passed=$passed failed=$failed" > "$OUT/done"
echo
echo "$passed packages work, $failed do not"
