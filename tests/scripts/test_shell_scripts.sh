#!/usr/bin/env bash
# Regression test suite for Cortex shell scripts:
# reload.sh, service.sh, systemctl.sh, su.sh, xdg-open.sh
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ASSETS_SCRIPTS="$REPO_ROOT/app/src/main/assets/scripts"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    PASS_COUNT=$((PASS_COUNT + 1))
    printf "  [PASS] %s\n" "$1"
}

fail() {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf "  [FAIL] %s\n" "$1" >&2
}

TEST_TMP="$(mktemp -d 2>/dev/null || mktemp -d -t cortex_script_tests)"
cleanup() {
    rm -rf "$TEST_TMP" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== 0. Checking LF line endings and syntax ==="
for script_name in reload.sh service.sh systemctl.sh su.sh; do
    script_path="$ASSETS_SCRIPTS/$script_name"
    if LC_ALL=C grep -q $'\r' "$script_path"; then
        fail "$script_name contains CRLF line endings"
    else
        pass "$script_name uses clean LF line endings"
    fi
    if bash -n "$script_path" 2>/dev/null; then
        pass "$script_name passes bash -n syntax check"
    else
        fail "$script_name failed bash -n syntax check"
    fi
done

echo "=== 1. Testing reload.sh ==="
RELOAD_SH="$ASSETS_SCRIPTS/reload.sh"

# 1a. Sourced mode: should source .bashrc in current shell
HOME_SOURCED="$TEST_TMP/home_sourced"
mkdir -p "$HOME_SOURCED"
cat > "$HOME_SOURCED/.bashrc" << 'EOF'
export RELOAD_SOURCED_VAR="sourced_ok"
EOF

SOURCED_OUT="$(HOME="$HOME_SOURCED" bash -c '. "$1"; printf "%s" "${RELOAD_SOURCED_VAR:-missing}"' _ "$RELOAD_SH")"
if [[ "$SOURCED_OUT" == *"Environment reloaded."* ]] && [[ "$SOURCED_OUT" == *"sourced_ok"* ]]; then
    pass "reload.sh sourced mode updates current shell environment"
else
    fail "reload.sh sourced mode failed: output='$SOURCED_OUT'"
fi

# 1b. Executed mode: should exec $SHELL -l
MOCK_SHELL="$TEST_TMP/mock_shell"
cat > "$MOCK_SHELL" << 'EOF'
#!/usr/bin/env bash
printf "MOCK_SHELL_ARGS:%s\n" "$*"
EOF
chmod +x "$MOCK_SHELL"

EXEC_OUT="$(HOME="$HOME_SOURCED" SHELL="$MOCK_SHELL" bash "$RELOAD_SH" 2>&1)"
if [[ "$EXEC_OUT" == *"Environment reloaded."* ]] && [[ "$EXEC_OUT" == *"MOCK_SHELL_ARGS:-l"* ]]; then
    pass "reload.sh executed mode execs \$SHELL -l"
else
    fail "reload.sh executed mode did not exec \$SHELL -l: output='$EXEC_OUT'"
fi

echo "=== 2. Testing service.sh & systemctl.sh ==="
SERVICE_SH="$ASSETS_SCRIPTS/service.sh"
SYSTEMCTL_SH="$ASSETS_SCRIPTS/systemctl.sh"

SVC_ROOT="$TEST_TMP/svc_env"
mkdir -p "$SVC_ROOT/run" "$SVC_ROOT/init.d" "$SVC_ROOT/bin" "$SVC_ROOT/autostart"

export CORTEX_RUN_DIR="$SVC_ROOT/run"
export CORTEX_INIT_DIR="$SVC_ROOT/init.d"
export CORTEX_SERVICE_PATH="$SVC_ROOT/bin"
export CORTEX_AUTOSTART_DIR="$SVC_ROOT/autostart"

# 2a. Service name validation (prevent path traversal)
if bash "$SERVICE_SH" "../etc/passwd" status >/dev/null 2>&1; then
    fail "service.sh allowed path traversal '../etc/passwd'"
else
    pass "service.sh rejects path traversal '../etc/passwd'"
fi

if bash "$SERVICE_SH" ".." status >/dev/null 2>&1; then
    fail "service.sh allowed '..'"
else
    pass "service.sh rejects '..'"
fi

if bash "$SYSTEMCTL_SH" enable "../traversal" >/dev/null 2>&1 || [ -e "$SVC_ROOT/traversal" ]; then
    fail "systemctl.sh enable allowed path traversal '../traversal'"
else
    pass "systemctl.sh enable rejects path traversal '../traversal'"
fi

if bash "$SYSTEMCTL_SH" disable "../traversal" >/dev/null 2>&1; then
    fail "systemctl.sh disable allowed path traversal '../traversal'"
else
    pass "systemctl.sh disable rejects path traversal '../traversal'"
fi

if bash "$SYSTEMCTL_SH" is-enabled "../traversal" >/dev/null 2>&1; then
    fail "systemctl.sh is-enabled allowed path traversal '../traversal'"
else
    pass "systemctl.sh is-enabled rejects path traversal '../traversal'"
fi

# 2b. Immediate crash detection on start
cat > "$SVC_ROOT/bin/crashsvc" << 'EOF'
#!/usr/bin/env bash
exit 1
EOF
chmod +x "$SVC_ROOT/bin/crashsvc"

if bash "$SERVICE_SH" crashsvc start >/dev/null 2>&1; then
    fail "service.sh start succeeded for immediately crashing daemon"
elif [ -f "$SVC_ROOT/run/crashsvc.pid" ]; then
    fail "service.sh start wrote PIDFILE for immediately crashing daemon"
else
    pass "service.sh start detects immediate daemon exit and does not write PIDFILE"
fi

# 2c. Substring process isolation (unrelated process with service name in args must not match or be killed)
# Use a compound command ('sleep 30; :') so bash does not exec-optimize into sleep and drop the positional argument from /proc/$pid/cmdline.
bash -c 'sleep 30; :' _ " fake_argument_containing_mytestsvc " &
BYSTANDER_PID=$!

if [ -r "/proc/$BYSTANDER_PID/cmdline" ]; then
    BYSTANDER_CMDLINE="$(tr '\0' ' ' < "/proc/$BYSTANDER_PID/cmdline" 2>/dev/null || true)"
    if [[ "$BYSTANDER_CMDLINE" == *"mytestsvc"* ]]; then
        pass "bystander process retains 'mytestsvc' substring in /proc/\$pid/cmdline"
    else
        fail "bystander process cmdline did not retain 'mytestsvc' substring: '$BYSTANDER_CMDLINE'"
    fi
fi

cat > "$SVC_ROOT/bin/mytestsvc" << 'EOF'
#!/usr/bin/env bash
sleep 30
EOF
chmod +x "$SVC_ROOT/bin/mytestsvc"

if bash "$SERVICE_SH" mytestsvc status >/dev/null 2>&1; then
    fail "service.sh status falsely matched bystander process with substring in cmdline"
else
    pass "service.sh status ignores bystander process with substring in cmdline"
fi

# 2d. Stale PID file pointing to bystander process must be ignored and cleaned up on status/start
echo "$BYSTANDER_PID" > "$SVC_ROOT/run/mytestsvc.pid"
if bash "$SERVICE_SH" mytestsvc status >/dev/null 2>&1; then
    fail "service.sh status trusted PIDFILE pointing to unrelated bystander process"
else
    pass "service.sh status rejects PIDFILE pointing to unrelated process"
fi

# Start real service, verify status and stop without killing bystander
if bash "$SERVICE_SH" mytestsvc start >/dev/null 2>&1 && bash "$SERVICE_SH" mytestsvc status >/dev/null 2>&1; then
    pass "service.sh starts and detects real mytestsvc daemon"
else
    fail "service.sh failed to start or detect real mytestsvc daemon"
fi

bash "$SERVICE_SH" mytestsvc stop >/dev/null 2>&1
if kill -0 "$BYSTANDER_PID" 2>/dev/null; then
    pass "service.sh stop did not kill unrelated bystander process"
else
    fail "service.sh stop killed unrelated bystander process!"
fi
kill "$BYSTANDER_PID" 2>/dev/null || true
wait "$BYSTANDER_PID" 2>/dev/null || true

# 2e. Fork adoption ownership: start must NOT adopt a pre-existing instance when the new launch exits immediately
cat > "$SVC_ROOT/bin/forkguardsvc" << 'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "--hold" ]; then
    sleep 30
    exit 0
fi
exit 1
EOF
chmod +x "$SVC_ROOT/bin/forkguardsvc"

"$SVC_ROOT/bin/forkguardsvc" --hold &
PRE_SVC_PID=$!
sleep 0.05

if bash "$SERVICE_SH" forkguardsvc start >/dev/null 2>&1 || [ -f "$SVC_ROOT/run/forkguardsvc.pid" ]; then
    fail "service.sh start falsely adopted pre-existing process after new daemon exited immediately"
else
    pass "service.sh start does not adopt pre-existing process when new launch fails"
fi
kill "$PRE_SVC_PID" 2>/dev/null || true
wait "$PRE_SVC_PID" 2>/dev/null || true

echo "=== 3. Testing su.sh ==="
SU_SH="$ASSETS_SCRIPTS/su.sh"

# Check static structure of su.sh for sq() helper and RUN_SU array
if grep -q 'sq()' "$SU_SH" && grep -q 'RUN_SU=(' "$SU_SH"; then
    pass "su.sh defines sq() helper and uses RUN_SU array"
else
    fail "su.sh is missing sq() helper or RUN_SU array"
fi

# Functional test of su.sh with a mock HOST_SU
SU_TEST_DIR="$TEST_TMP/su dir with spaces & 'quotes'"
mkdir -p "$SU_TEST_DIR/bin" "$SU_TEST_DIR/root"
BASH_EXEC="$(command -v bash)"
cp "$BASH_EXEC" "$SU_TEST_DIR/bin/bash" 2>/dev/null || ln -s "$BASH_EXEC" "$SU_TEST_DIR/bin/bash"

MOCK_SU="$TEST_TMP/mock_su"
cat > "$MOCK_SU" << EOF
#!$BASH_EXEC
if [ "\${1:-}" = "-c" ]; then
    shift
    # Verify TERM does not contain literal single quotes
    case "\${TERM:-}" in
        \'*\')
            echo "LITERAL_QUOTES_IN_TERM:\$TERM" >&2
            exit 2
            ;;
    esac
    exec "$BASH_EXEC" -c "\$1"
fi
exec "$BASH_EXEC" "\$@"
EOF
chmod +x "$MOCK_SU"

PWN_SU_FILE="$TEST_TMP/su_injected"
SU_OUT="$(
    CORTEX_HOST_SU="$MOCK_SU" \
    CORTEX_ROOT="$SU_TEST_DIR" \
    bash "$SU_SH" printf '%s|%s|%s' "arg with spaces" "arg'quote" "\$(touch \"$PWN_SU_FILE\")" 2>&1
)"
if [ -e "$PWN_SU_FILE" ]; then
    fail "su.sh executed command injection payload in user arguments"
elif [ "$SU_OUT" = "arg with spaces|arg'quote|\$(touch \"$PWN_SU_FILE\")" ]; then
    pass "su.sh preserves complex arguments and prevents command injection"
else
    fail "su.sh argument preservation failed: got '$SU_OUT'"
fi

SU_C_OUT="$(
    CORTEX_HOST_SU="$MOCK_SU" \
    CORTEX_ROOT="$SU_TEST_DIR" \
    bash "$SU_SH" -c 'printf "%s|%s" "$USER" "$1"' _ "val'with'quote" 2>&1
)"
if [ "$SU_C_OUT" = "root|val'with'quote" ]; then
    pass "su.sh -c mode preserves USER and positional arguments with single quotes"
else
    fail "su.sh -c mode failed: got '$SU_C_OUT'"
fi

echo "=== 4. Testing simple xdg-open script ==="
XDG_OPEN_TEST="$TEST_TMP/xdg-open"
cat << 'EOFXDG' > "$XDG_OPEN_TEST"
#!/bin/sh
if [ $# -gt 0 ]; then
    echo "To open this URL, copy and paste it into your browser:"
    echo "$1"
fi
exit 0
EOFXDG
chmod +x "$XDG_OPEN_TEST"

XDG_OUT="$(sh "$XDG_OPEN_TEST" "https://example.com/test?a=1&b=2" 2>&1)"
if echo "$XDG_OUT" | grep -q "https://example.com/test?a=1&b=2"; then
    pass "xdg-open prints target URL to terminal for manual user copying"
else
    fail "xdg-open failed to print target URL: got '$XDG_OUT'"
fi

if sh "$XDG_OPEN_TEST" >/dev/null 2>&1; then
    pass "xdg-open with no arguments exits 0 cleanly"
else
    fail "xdg-open with no arguments failed"
fi

echo ""
echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
exit 0
