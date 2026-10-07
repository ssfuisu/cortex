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
for script_name in reload.sh service.sh systemctl.sh su.sh xdg-open.sh; do
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
cp "$(command -v bash)" "$SU_TEST_DIR/bin/bash" 2>/dev/null || ln -s "$(command -v bash)" "$SU_TEST_DIR/bin/bash"

MOCK_SU="$TEST_TMP/mock_su"
cat > "$MOCK_SU" << 'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "-c" ]; then
    shift
    # Verify TERM does not contain literal single quotes
    case "${TERM:-}" in
        \'*\')
            echo "LITERAL_QUOTES_IN_TERM:$TERM" >&2
            exit 2
            ;;
    esac
    exec bash -c "$1"
fi
exec bash "$@"
EOF
chmod +x "$MOCK_SU"

PWN_SU_FILE="$TEST_TMP/su_injected"
SU_OUT="$(
    CORTEX_HOST_SU="$MOCK_SU" \
    CORTEX_ROOT="$SU_TEST_DIR" \
    CORTEX_URL_PORT="4715" \
    CORTEX_URL_TOKEN="tok_123" \
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
    CORTEX_URL_PORT="4715" \
    CORTEX_URL_TOKEN="tok'456" \
    bash "$SU_SH" -c 'printf "%s|%s" "$CORTEX_URL_TOKEN" "$1"' _ "val'with'quote" 2>&1
)"
if [ "$SU_C_OUT" = "tok'456|val'with'quote" ]; then
    pass "su.sh -c mode preserves CORTEX_URL_TOKEN and positional arguments with single quotes"
else
    fail "su.sh -c mode failed: got '$SU_C_OUT'"
fi

echo "=== 4. Testing xdg-open.sh ==="
XDG_OPEN_SH="$ASSETS_SCRIPTS/xdg-open.sh"

# 4a. Test URL command injection & auth token verification over local TCP server using python3
if command -v python3 >/dev/null 2>&1; then
    PWN_XDG_FILE="$TEST_TMP/xdg_pwned"
    REQ_LOG="$TEST_TMP/xdg_req.log"
    PORT_FILE="$TEST_TMP/xdg_port"

    python3 -c '
import socket, sys

port_file, req_log, mode = sys.argv[1], sys.argv[2], sys.argv[3]
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 0))
srv.listen(5)
with open(port_file, "w") as f:
    f.write(str(srv.getsockname()[1]))
srv.settimeout(5.0)
conn, _ = srv.accept()
data = conn.recv(4096).decode("utf-8", "replace")
with open(req_log, "w") as f:
    f.write(data)
if mode == "ok":
    conn.sendall(b"OK\n")
else:
    conn.sendall(b"ERR unauthorized\n")
conn.close()
srv.close()
' "$PORT_FILE" "$REQ_LOG" "ok" &
    SRV_PID=$!

    for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -s "$PORT_FILE" ] && break
        sleep 0.05
    done
    TEST_PORT="$(cat "$PORT_FILE" 2>/dev/null || echo 4715)"

    INJECT_URL="https://example.com/\$(touch \"$PWN_XDG_FILE\")\"'\$(id)'"
    if CORTEX_URL_PORT="$TEST_PORT" CORTEX_URL_TOKEN="secret_token_abc" sh "$XDG_OPEN_SH" "$INJECT_URL" >/dev/null 2>&1; then
        pass "xdg-open.sh succeeded on OK response from server"
    else
        fail "xdg-open.sh failed on OK response from server"
    fi
    wait "$SRV_PID" 2>/dev/null || true

    if [ -e "$PWN_XDG_FILE" ]; then
        fail "xdg-open.sh vulnerable to command injection in URL!"
    else
        pass "xdg-open.sh prevented command injection in URL"
    fi

    RECEIVED_LINE="$(tr -d '\r\n' < "$REQ_LOG" 2>/dev/null || true)"
    EXPECTED_LINE="OPEN secret_token_abc $INJECT_URL"
    if [ "$RECEIVED_LINE" = "$EXPECTED_LINE" ]; then
        pass "xdg-open.sh sent exact 'OPEN <token> <url>' payload"
    else
        fail "xdg-open.sh payload mismatch: got '$RECEIVED_LINE', expected '$EXPECTED_LINE'"
    fi

    # 4b. Test reading token fallback from $CORTEX_ROOT/../cortex_url_token
    rm -f "$PORT_FILE" "$REQ_LOG"
    FAKE_FILES_DIR="$TEST_TMP/app_files"
    mkdir -p "$FAKE_FILES_DIR/cortex"
    printf "file_fallback_token_999\n" > "$FAKE_FILES_DIR/cortex_url_token"

    python3 -c '
import socket, sys
port_file, req_log = sys.argv[1], sys.argv[2]
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 0))
srv.listen(5)
with open(port_file, "w") as f:
    f.write(str(srv.getsockname()[1]))
srv.settimeout(5.0)
conn, _ = srv.accept()
data = conn.recv(4096).decode("utf-8", "replace")
with open(req_log, "w") as f:
    f.write(data)
conn.sendall(b"OK\n")
conn.close()
srv.close()
' "$PORT_FILE" "$REQ_LOG" &
    SRV_PID=$!
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -s "$PORT_FILE" ] && break
        sleep 0.05
    done
    TEST_PORT="$(cat "$PORT_FILE" 2>/dev/null || echo 4715)"

    if CORTEX_URL_PORT="$TEST_PORT" CORTEX_URL_TOKEN="" CORTEX_ROOT="$FAKE_FILES_DIR/cortex" sh "$XDG_OPEN_SH" "https://example.com/fallback" >/dev/null 2>&1; then
        RECEIVED_FALLBACK="$(tr -d '\r\n' < "$REQ_LOG" 2>/dev/null || true)"
        if [ "$RECEIVED_FALLBACK" = "OPEN file_fallback_token_999 https://example.com/fallback" ]; then
            pass "xdg-open.sh reads fallback token from \$CORTEX_ROOT/../cortex_url_token"
        else
            fail "xdg-open.sh fallback token mismatch: got '$RECEIVED_FALLBACK'"
        fi
    else
        fail "xdg-open.sh failed when using fallback token file"
    fi
    wait "$SRV_PID" 2>/dev/null || true

    # 4c. Test ERR / HTTP 401 rejection across all transports
    rm -f "$PORT_FILE" "$REQ_LOG"
    python3 -c '
import socket, sys

port_file = sys.argv[1]
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 0))
srv.listen(10)
with open(port_file, "w") as f:
    f.write(str(srv.getsockname()[1]))
srv.settimeout(3.0)
for _ in range(6):
    try:
        conn, _ = srv.accept()
        req = conn.recv(4096).decode("utf-8", "replace")
        if req.startswith("GET "):
            body = b"ERR unauthorized\n"
            conn.sendall(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: " + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
        else:
            conn.sendall(b"ERR unauthorized\n")
        conn.close()
    except Exception:
        break
srv.close()
' "$PORT_FILE" &
    SRV_PID=$!

    for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -s "$PORT_FILE" ] && break
        sleep 0.05
    done
    TEST_PORT="$(cat "$PORT_FILE" 2>/dev/null || echo 4715)"

    if CORTEX_URL_PORT="$TEST_PORT" CORTEX_URL_TOKEN="bad_token" sh "$XDG_OPEN_SH" "https://example.com" >/dev/null 2>&1; then
        fail "xdg-open.sh exited 0 despite ERR / 401 Unauthorized from server"
    else
        pass "xdg-open.sh exits non-zero on ERR / 401 Unauthorized from server"
    fi
    kill "$SRV_PID" 2>/dev/null || true
    wait "$SRV_PID" 2>/dev/null || true
fi

echo ""
echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
exit 0
