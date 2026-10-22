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
for script_name in reload.sh service.sh systemctl.sh su.sh cortex-shell.sh cortex-backup.sh cortex-api.sh notify-send.sh; do
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

# Root mode (CORTEX_CHROOT): sudo runs the command itself, su is Ubuntu's own su
ROOTMODE_STUBS="$TEST_TMP/rootmode-stubs"
mkdir -p "$ROOTMODE_STUBS"
printf '#!%s\necho "runuser $*"\n' "$BASH_EXEC" > "$ROOTMODE_STUBS/runuser"
printf '#!%s\necho "real-su $*"\n' "$BASH_EXEC" > "$ROOTMODE_STUBS/real-su"
chmod +x "$ROOTMODE_STUBS/runuser" "$ROOTMODE_STUBS/real-su"
rootmode() { env CORTEX_CHROOT=1 PATH="$ROOTMODE_STUBS:$PATH" CORTEX_REAL_SU="$ROOTMODE_STUBS/real-su" "$@"; }

OUT="$(rootmode CORTEX_INVOKED_AS=sudo bash "$SU_SH" printf '%s|%s' "arg with spaces" "arg'quote" 2>&1)"
[ "$OUT" = "arg with spaces|arg'quote" ] && pass "root mode: sudo runs the command with its arguments" || fail "root mode: sudo command: got '$OUT'"
OUT="$(rootmode CORTEX_INVOKED_AS=sudo bash "$SU_SH" -E -H -n -- printf ok 2>&1)"
[ "$OUT" = "ok" ] && pass "root mode: sudo ignores -E -H -n and --" || fail "root mode: sudo options: got '$OUT'"
OUT="$(rootmode CORTEX_INVOKED_AS=sudo bash "$SU_SH" -u postgres psql -l 2>&1)"
[ "$OUT" = "runuser -u postgres -- psql -l" ] && pass "root mode: sudo -u USER uses runuser" || fail "root mode: sudo -u: got '$OUT'"
OUT="$(rootmode CORTEX_INVOKED_AS=sudo bash "$SU_SH" --user=www-data -i 2>&1)"
[ "$OUT" = "runuser -l www-data" ] && pass "root mode: sudo -i --user=USER starts USER's login shell" || fail "root mode: sudo -i --user: got '$OUT'"
OUT="$(rootmode CORTEX_INVOKED_AS=sudo bash "$SU_SH" -u root printf ok 2>&1)"
[ "$OUT" = "ok" ] && pass "root mode: sudo -u root runs the command directly" || fail "root mode: sudo -u root: got '$OUT'"
OUT="$(rootmode CORTEX_INVOKED_AS=su bash "$SU_SH" - postgres 2>&1)"
[ "$OUT" = "real-su - postgres" ] && pass "root mode: su is the real su" || fail "root mode: su: got '$OUT'"
OUT="$(rootmode CORTEX_INVOKED_AS=sudo bash "$SU_SH" bash -c 'echo "${CORTEX_INVOKED_AS:-unset}"' 2>&1)"
[ "$OUT" = "unset" ] && pass "root mode: the invoked name is not passed on" || fail "root mode: CORTEX_INVOKED_AS leaked: got '$OUT'"
if [ ! -d /system/bin ]; then
    OUT="$(env -u CORTEX_ROOT -u CORTEX_CHROOT CORTEX_INVOKED_AS=sudo bash "$SU_SH" printf ok 2>&1)"
    [ "$OUT" = "ok" ] && pass "root mode is recognised without CORTEX_CHROOT (no CORTEX_ROOT, no /system)" || fail "root mode detection: got '$OUT'"
fi

TSU_DIR="$TEST_TMP/tsu-dir"
mkdir -p "$TSU_DIR"
printf '#!%s\necho "as=${CORTEX_INVOKED_AS:-none} $*"\n' "$BASH_EXEC" > "$TSU_DIR/su"
cp "$ASSETS_SCRIPTS/tsu.sh" "$TSU_DIR/sudo"
chmod +x "$TSU_DIR/su" "$TSU_DIR/sudo"
OUT="$("$TSU_DIR/sudo" apt update 2>&1)"
[ "$OUT" = "as=sudo apt update" ] && pass "tsu.sh passes the name it was started as to su" || fail "tsu.sh name: got '$OUT'"

echo "=== 3b. Testing cortex-api and notify-send against a stand-in server ==="
API_SH="$ASSETS_SCRIPTS/cortex-api.sh"
API_DIR="$TEST_TMP/cortex api dir"
mkdir -p "$API_DIR/bin" "$API_DIR/work"
API_CONF="$API_DIR/conf"
API_LOG="$API_DIR/requests.jsonl"
API_TOKEN="tok$$secret"
python3 -I "$REPO_ROOT/tests/scripts/cortex_api_mock.py" "$API_CONF" "$API_LOG" "$API_TOKEN" &
API_PID=$!
for _ in $(seq 1 50); do [ -s "$API_CONF" ] && break; sleep 0.1; done
api() { CORTEX_API_CONF="$API_CONF" bash "$API_SH" "$@"; }
# FIELD of the last request (JSON), printed as JSON
last() { python3 -I -c 'import json,sys; print(json.dumps(json.loads(open(sys.argv[1]).read().splitlines()[-1])[sys.argv[2]], ensure_ascii=False))' "$API_LOG" "$1"; }

OUT="$(api battery </dev/null)"; STATUS=$?
[ "$STATUS" = 0 ] && [ "$OUT" = "ok battery" ] && pass "cortex-api prints the answer and exits 0" || fail "cortex-api battery: status $STATUS, output '$OUT'"
[ "$(last token)" = "\"$API_TOKEN\"" ] && [ "$(last args)" = "[]" ] && [ "$(last stdin)" = "null" ] \
    && pass "cortex-api sends the token, no arguments and no stdin" || fail "cortex-api request: token $(last token) args $(last args) stdin $(last stdin)"

echo "ignored" | api notification -t "Başlık ✓ \"x\"" -c "iki kelime" -i build >/dev/null
[ "$(last args)" = '["-t", "Başlık ✓ \"x\"", "-c", "iki kelime", "-i", "build"]' ] && [ "$(last stdin)" = "null" ] \
    && pass "notification arguments keep UTF-8, quotes and spaces; stdin is not read when -c is given" || fail "notification: args $(last args) stdin $(last stdin)"

printf 'satır 1\nsatır 2\n' | api notification -t T >/dev/null
[ "$(last stdin)" = '"satır 1\nsatır 2\n"' ] && pass "notification reads the text from stdin without -c" || fail "notification stdin: $(last stdin)"

api clipboard-set "metin" </dev/null >/dev/null
[ "$(last args)" = '["metin"]' ] && [ "$(last stdin)" = "null" ] && pass "clipboard-set TEXT sends no stdin" || fail "clipboard-set TEXT: $(last args) $(last stdin)"
printf 'from a pipe' | api clipboard-set >/dev/null
[ "$(last stdin)" = '"from a pipe"' ] && pass "clipboard-set reads stdin without TEXT" || fail "clipboard-set stdin: $(last stdin)"
printf 'x' | api toast -l >/dev/null
[ "$(last args)" = '["-l"]' ] && [ "$(last stdin)" = '"x"' ] && pass "toast options do not count as text" || fail "toast -l: $(last args) $(last stdin)"

(cd "$API_DIR/work" && api open "report one.pdf" </dev/null >/dev/null)
[ "$(last args)" = "[\"$API_DIR/work/report one.pdf\"]" ] && pass "open sends a relative file as an absolute path" || fail "open file: $(last args)"
api open "https://example.com/a?b=c d" </dev/null >/dev/null
[ "$(last args)" = '["https://example.com/a?b=c d"]' ] && pass "open sends a link as it is" || fail "open link: $(last args)"
(cd "$API_DIR/work" && api share -t Paylaş -f notes.txt </dev/null >/dev/null)
[ "$(last args)" = "[\"-t\", \"Paylaş\", \"-f\", \"$API_DIR/work/notes.txt\"]" ] && pass "share -f sends an absolute path" || fail "share -f: $(last args)"

ERR="$(api fail 2>&1 >/dev/null </dev/null)"; STATUS=$?
OUT="$(api fail 2>/dev/null </dev/null)"
[ "$STATUS" = 3 ] && [ "$ERR" = "cortex-api: olmadı" ] && [ -z "$OUT" ] && pass "errors go to stderr with the server's exit status" || fail "error: status $STATUS stderr '$ERR' stdout '$OUT'"

printf '%s\n' "$(cut -d' ' -f1 "$API_CONF") wrongtoken" > "$API_DIR/badconf"
CORTEX_API_CONF="$API_DIR/badconf" bash "$API_SH" battery </dev/null >/dev/null 2>&1
[ "$?" = 1 ] && pass "a wrong token is refused" || fail "a wrong token was not refused"
ERR="$(CORTEX_API_CONF="$API_DIR/missing" bash "$API_SH" battery 2>&1 </dev/null)"; STATUS=$?
[ "$STATUS" = 1 ] && echo "$ERR" | grep -q "Cortex is not running" && pass "a missing /run/cortex-api says Cortex is not running" || fail "missing conf: $STATUS '$ERR'"
api --help | grep -q "notification \[-t TITLE\]" && pass "cortex-api --help works without the app" || fail "cortex-api --help"

cp "$API_SH" "$API_DIR/bin/cortex-api" && chmod +x "$API_DIR/bin/cortex-api"
CORTEX_API_CONF="$API_CONF" PATH="$API_DIR/bin:$PATH" bash "$ASSETS_SCRIPTS/notify-send.sh" -u critical -t 5000 -i dialog-information "Derleme bitti" "0 hata" </dev/null
[ "$?" = 0 ] && [ "$(last args)" = '["-t", "Derleme bitti", "-c", "0 hata"]' ] && pass "notify-send shows SUMMARY and BODY through cortex-api" || fail "notify-send: $(last args)"
CORTEX_API_CONF="$API_CONF" PATH="$API_DIR/bin:$PATH" bash "$ASSETS_SCRIPTS/notify-send.sh" </dev/null 2>/dev/null
[ "$?" = 1 ] && pass "notify-send without a summary fails" || fail "notify-send without a summary did not fail"
kill "$API_PID" 2>/dev/null; wait "$API_PID" 2>/dev/null

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

echo "=== 5. Testing cortex-shell.sh (/etc/profile.d interactive helpers) ==="
CORTEX_SHELL_SH="$ASSETS_SCRIPTS/cortex-shell.sh"
RC_HOME="$TEST_TMP/rc_home"
RC_TMP="$TEST_TMP/rc_tmp"
mkdir -p "$RC_HOME" "$RC_TMP"
printf '# user bashrc\n' > "$RC_HOME/.bashrc"

# Interactive bash fed from a pipe: PROMPT_COMMAND runs before every line it reads.
run_interactive() {
    HOME="$RC_HOME" TMPDIR="$RC_TMP" CORTEX_SHELL_SH="$CORTEX_SHELL_SH" \
        CORTEX_TEST_SESSION="$TEST_TMP/rc_session.sh" \
        timeout 30 bash --norc --noprofile -i 2>&1
}

# 5a. Non-interactive shells must not be touched (no PROMPT_COMMAND, no functions)
NONINT_OUT="$(HOME="$RC_HOME" TMPDIR="$RC_TMP" bash -c '. "$1"; printf "%s|%s" "${PROMPT_COMMAND-none}" "$(type -t reload || echo nofn)"' _ "$CORTEX_SHELL_SH" 2>&1)"
if [ "$NONINT_OUT" = "none|nofn" ]; then
    pass "cortex-shell.sh does nothing in non-interactive shells"
else
    fail "cortex-shell.sh leaked into a non-interactive shell: '$NONINT_OUT'"
fi

# 5b. An installer appending to ~/.bashrc is picked up at the next prompt (nvm / opencode scenario)
cat > "$TEST_TMP/rc_session.sh" << 'EOF'
. "$CORTEX_SHELL_SH"
sleep 1.1
printf 'export CORTEX_TEST_FROM_INSTALLER=yes\nnvm() { echo nvm-ready; }\n' >> "$HOME/.bashrc"
EOF
RC_OUT="$(printf 'source "$CORTEX_TEST_SESSION"\necho "VAR=${CORTEX_TEST_FROM_INSTALLER-unset}"\necho "FN=$(type -t nvm || echo none)"\n' | run_interactive)"
if [[ "$RC_OUT" == *"VAR=yes"* ]] && [[ "$RC_OUT" == *"FN=function"* ]]; then
    pass "cortex-shell.sh re-sources ~/.bashrc when an installer changes it"
else
    fail "cortex-shell.sh did not auto-reload ~/.bashrc: output='$RC_OUT'"
fi

# 5c. CORTEX_AUTO_RELOAD=0 turns the automatic reload off
printf '# user bashrc\n' > "$RC_HOME/.bashrc"
RC_OFF_OUT="$(printf 'source "$CORTEX_TEST_SESSION"\necho "VAR=${CORTEX_TEST_FROM_INSTALLER-unset}"\n' | CORTEX_AUTO_RELOAD=0 run_interactive)"
if [[ "$RC_OFF_OUT" == *"VAR=unset"* ]]; then
    pass "CORTEX_AUTO_RELOAD=0 disables the automatic reload"
else
    fail "CORTEX_AUTO_RELOAD=0 did not disable the automatic reload: output='$RC_OFF_OUT'"
fi

# 5d. A stamp file removed behind the shell's back (tmp cleanup at app start) must not trigger a reload
RC_STAMP_OUT="$(printf 'source "$CORTEX_SHELL_SH"\nrm -f "$TMPDIR"/.cortex-rcwatch.*\necho one\necho two\n' | run_interactive)"
if [[ "$RC_STAMP_OUT" != *"reloading"* ]] && [[ "$RC_STAMP_OUT" == *"two"* ]]; then
    pass "cortex-shell.sh recovers silently when its stamp file is removed"
else
    fail "cortex-shell.sh reloaded after its stamp file vanished: output='$RC_STAMP_OUT'"
fi

# 5e. A user's PROMPT_COMMAND is preserved and sourcing the helpers twice does not stack the hook
PC_OUT="$(HOME="$RC_HOME" TMPDIR="$RC_TMP" PROMPT_COMMAND='echo user-hook' bash --norc --noprofile -i -c '. "$1"; . "$1"; printf "%s" "$PROMPT_COMMAND"' _ "$CORTEX_SHELL_SH" 2>/dev/null)"
PC_HOOKS="$(printf '%s' "$PC_OUT" | grep -o '__cortex_precmd;' | wc -l)"
if [[ "$PC_OUT" == *"echo user-hook" ]] && [ "$PC_HOOKS" -eq 1 ]; then
    pass "cortex-shell.sh keeps the user's PROMPT_COMMAND and installs its hook once"
else
    fail "cortex-shell.sh mangled PROMPT_COMMAND (hooks=$PC_HOOKS): '$PC_OUT'"
fi

# 5f. OSC 133 marks: C before a command runs, D;<status> at the next prompt
printf '# user bashrc\n' > "$RC_HOME/.bashrc"
MARK_OUT="$(printf 'source "$CORTEX_SHELL_SH"\ntrue\nfalse\n(exit 7)\n' | run_interactive)"
if [[ "$MARK_OUT" == *$'\033]133;C\007'* ]] && [[ "$MARK_OUT" == *$'\033]133;D;0\007'* ]] &&
   [[ "$MARK_OUT" == *$'\033]133;D;1\007'* ]] && [[ "$MARK_OUT" == *$'\033]133;D;7\007'* ]]; then
    pass "cortex-shell.sh emits OSC 133 command marks with exit statuses"
else
    fail "cortex-shell.sh did not emit the expected OSC 133 marks: $(printf '%s' "$MARK_OUT" | od -c | head -5)"
fi

# 5g. Later PROMPT_COMMAND hooks (starship, direnv, custom prompts) still see the user's $?
STATUS_OUT="$(printf 'source "$CORTEX_SHELL_SH"\nfalse\n' | PROMPT_COMMAND='echo "seen-status=$?"' run_interactive)"
if [[ "$STATUS_OUT" == *"seen-status=1"* ]]; then
    pass "cortex-shell.sh hands the last exit status on to other PROMPT_COMMAND hooks"
else
    fail "cortex-shell.sh hid the exit status from other hooks: output='$STATUS_OUT'"
fi

# 5h. CORTEX_COMMAND_MARKS=0 switches the marks off
OFF_MARKS="$(printf 'source "$CORTEX_SHELL_SH"\ntrue\n' | CORTEX_COMMAND_MARKS=0 run_interactive)"
if [[ "$OFF_MARKS" != *$'\033]133;'* ]]; then
    pass "CORTEX_COMMAND_MARKS=0 disables the OSC 133 marks"
else
    fail "CORTEX_COMMAND_MARKS=0 did not disable the marks"
fi

echo "=== 6. Testing cortex-backup.sh ==="
BACKUP_SH="$ASSETS_SCRIPTS/cortex-backup.sh"
BK_ROOT="$TEST_TMP/bk_root"
BK_OUT="$TEST_TMP/bk_out"
mkdir -p "$BK_ROOT/home/proj" "$BK_ROOT/home/.cache" "$BK_ROOT/root" "$BK_OUT"
echo "hello" > "$BK_ROOT/home/proj/a.txt"
echo "secret" > "$BK_ROOT/root/r.txt"
echo "junk" > "$BK_ROOT/home/.cache/big"
ln -s /nonexistent-sdcard "$BK_ROOT/home/storage"

if CORTEX_ROOT="$BK_ROOT" bash -n "$BACKUP_SH" 2>/dev/null; then
    pass "cortex-backup.sh passes bash -n syntax check"
else
    fail "cortex-backup.sh failed bash -n syntax check"
fi

CORTEX_ROOT="$BK_ROOT" CORTEX_BACKUP_DIR="$BK_OUT" bash "$BACKUP_SH" create >/dev/null 2>&1
BK_FILE="$(ls "$BK_OUT"/cortex-backup-*.tar.gz 2>/dev/null | head -n 1)"
if [ -n "$BK_FILE" ] && tar -tzf "$BK_FILE" | grep -q '^home/proj/a.txt$' && tar -tzf "$BK_FILE" | grep -q '^root/r.txt$'; then
    pass "cortex-backup creates an archive with home/ and root/"
else
    fail "cortex-backup did not create the expected archive"
fi
if [ -n "$BK_FILE" ] && ! tar -tzf "$BK_FILE" | grep -q -e '\.cache' -e 'storage'; then
    pass "cortex-backup leaves caches and the storage link out"
else
    fail "cortex-backup included caches or the storage link"
fi

rm -rf "$BK_ROOT/home/proj"
echo "stale" > "$BK_ROOT/root/r.txt"
if CORTEX_ROOT="$BK_ROOT" bash "$BACKUP_SH" restore "$BK_FILE" --yes >/dev/null 2>&1 &&
   [ "$(cat "$BK_ROOT/home/proj/a.txt" 2>/dev/null)" = "hello" ] && [ "$(cat "$BK_ROOT/root/r.txt")" = "secret" ]; then
    pass "cortex-backup restore brings deleted and changed files back"
else
    fail "cortex-backup restore did not restore the files"
fi

# An archive that is not a Cortex backup (or tries to leave home/ and root/) must be refused untouched.
EVIL_DIR="$TEST_TMP/evil"
mkdir -p "$EVIL_DIR/x"
echo pwn > "$EVIL_DIR/x/evil"
tar -C "$EVIL_DIR" -czf "$TEST_TMP/foreign.tar.gz" x
python3 - "$TEST_TMP/traversal.tar.gz" << 'EOF'
import io, sys, tarfile
t = tarfile.open(sys.argv[1], 'w:gz')
info = tarfile.TarInfo('home/../../escape')
info.size = 1
t.addfile(info, io.BytesIO(b'x'))
t.close()
EOF
if ! CORTEX_ROOT="$BK_ROOT" bash "$BACKUP_SH" restore "$TEST_TMP/foreign.tar.gz" --yes >/dev/null 2>&1 &&
   ! CORTEX_ROOT="$BK_ROOT" bash "$BACKUP_SH" restore "$TEST_TMP/traversal.tar.gz" --yes >/dev/null 2>&1 &&
   [ ! -e "$BK_ROOT/x" ] && [ ! -e "$TEST_TMP/escape" ]; then
    pass "cortex-backup restore refuses foreign and path-traversal archives"
else
    fail "cortex-backup restore accepted an unsafe archive"
fi

if ! CORTEX_ROOT="$BK_ROOT" bash "$BACKUP_SH" create -o "$BK_FILE" >/dev/null 2>&1; then
    pass "cortex-backup never overwrites an existing backup"
else
    fail "cortex-backup overwrote an existing backup"
fi

echo "=== 7. Checking translations ==="
if command -v python3 >/dev/null 2>&1; then
    TR_OUT="$(python3 "$REPO_ROOT/scripts/check-translations.py" 2>&1)"
    if [ $? -eq 0 ]; then
        pass "translations are complete and consistent ($(printf '%s' "$TR_OUT" | head -n 1))"
    else
        fail "translation check failed: $TR_OUT"
    fi
else
    echo "  [SKIP] python3 not available"
fi

echo "=== 8. Checking the library search path definitions ==="
if command -v python3 >/dev/null 2>&1; then
    LP_OUT="$(python3 "$REPO_ROOT/scripts/check-library-path.py" 2>&1)"
    if [ $? -eq 0 ]; then
        pass "all library search paths agree ($(printf '%s' "$LP_OUT" | head -n 1))"
    else
        fail "library path check failed: $LP_OUT"
    fi
else
    echo "  [SKIP] python3 not available"
fi

echo "=== 9b. Checking the emulator test scripts ==="
for e2e_script in run-emulator-test.sh in-terminal.sh diagnose.sh; do
    if bash -n "$REPO_ROOT/tests/e2e/$e2e_script" 2>/dev/null; then
        pass "$e2e_script passes bash -n syntax check"
    else
        fail "$e2e_script failed bash -n syntax check"
    fi
done

echo "=== 9. Checking the benchmark runner ==="
if bash -n "$REPO_ROOT/tests/bench/run-micro.sh" 2>/dev/null; then
    pass "run-micro.sh passes bash -n syntax check"
else
    fail "run-micro.sh failed bash -n syntax check"
fi
if LC_ALL=C grep -q $'\r' "$REPO_ROOT/tests/bench/run-micro.sh"; then
    fail "run-micro.sh contains CRLF line endings"
else
    pass "run-micro.sh uses clean LF line endings"
fi
if bash "$REPO_ROOT/tests/bench/run-micro.sh" -h 2>&1 | grep -q "run-micro.sh \[-r REPS\]"; then
    pass "run-micro.sh -h prints its usage"
else
    fail "run-micro.sh -h did not print its usage"
fi

echo ""
echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
exit 0
