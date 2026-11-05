#!/usr/bin/env bash
# shellcheck disable=SC2329
# End-to-end tests for scripts/install.sh. No network access, no jq.
#
# Fixtures are served from a temp directory by `python3 -m http.server` on a
# dynamic 127.0.0.1 port. python3 is a test-only dependency. A tiny second
# server truncates a response body to exercise interrupted downloads.
set -uo pipefail
HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
SCRIPT="$HERE/../install.sh"

pass=0
failed=0
n=0

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/oq-installer-tests.XXXXXX")
WWW="$ROOT/www"
STUBS="$ROOT/stubs"
SERVER_PID=""
TRUNC_PID=""

# The fixture servers are the only test dependency. Fail with the reason instead of
# letting every case fail later with "could not start fixture server".
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required to run the installer tests (fixture HTTP server)" >&2
  exit 2
fi
mkdir -p "$WWW" "$STUBS"
printf 'a different file used to force a checksum mismatch\n' > "$WWW/other"

cleanup() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [ -n "$TRUNC_PID" ]; then
    kill "$TRUNC_PID" 2>/dev/null || true
    wait "$TRUNC_PID" 2>/dev/null || true
  fi
  rm -rf -- "$ROOT"
}
trap cleanup EXIT

free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

wait_for_http() {
  local url=$1 tries=0
  while [ "$tries" -lt 100 ]; do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then return 0; fi
    tries=$((tries + 1))
    sleep 0.05
  done
  return 1
}

start_servers() {
  PORT=$(free_port)
  python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WWW" >"$ROOT/http.log" 2>&1 &
  SERVER_PID=$!
  printf 'ok\n' > "$WWW/ping"
  wait_for_http "http://127.0.0.1:$PORT/ping" || { echo "could not start fixture server"; exit 1; }

  TRUNC_PORT=$(free_port)
  cat > "$ROOT/trunc.py" <<'PY'
import http.server
import socketserver
import sys


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/trunc"):
            self.send_response(200)
            self.send_header("Content-Length", "1048576")
            self.end_headers()
            self.wfile.write(b"x" * 64)
            self.wfile.flush()
            self.close_connection = True
            return
        self.send_error(404)

    def log_message(self, *args):
        pass


socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("127.0.0.1", int(sys.argv[1])), Handler) as server:
    server.serve_forever()
PY
  python3 "$ROOT/trunc.py" "$TRUNC_PORT" >"$ROOT/trunc.log" 2>&1 &
  TRUNC_PID=$!
  sleep 0.2
}

start_servers || exit 1
BASE="http://127.0.0.1:$PORT/releases/download"

# A fake java: reports 21 for -version, and only succeeds on -jar for a
# non-empty jar, printing the oq version line the installer expects.
cat > "$STUBS/java" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = "-version" ]; then
  printf 'openjdk version "21.0.9" 2025-01-01 LTS\n' >&2
  exit 0
fi
jar=""
prev=""
for arg in "$@"; do
  if [ "$prev" = "-jar" ]; then jar=$arg; fi
  prev=$arg
done
if [ ! -s "$jar" ]; then
  printf 'Error: Invalid or corrupt jarfile\n' >&2
  exit 1
fi
printf 'oq 0.1.0-alpha.2\n'
exit 0
SH
chmod +x "$STUBS/java"

cat > "$STUBS/java17" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = "-version" ]; then
  printf 'openjdk version "17.0.1" 2021-10-19\n' >&2
  exit 0
fi
exit 1
SH
chmod +x "$STUBS/java17"

cat > "$STUBS/java21" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = "-version" ]; then
  printf 'java version "21.0.9" 2025-01-01 LTS\n' >&2
  exit 0
fi
jar=""
prev=""
for arg in "$@"; do
  if [ "$prev" = "-jar" ]; then jar=$arg; fi
  prev=$arg
done
if [ ! -s "$jar" ]; then
  printf 'Error: Invalid or corrupt jarfile\n' >&2
  exit 1
fi
printf 'oq 0.11.0\n'
exit 0
SH
chmod +x "$STUBS/java21"

JAVA_STUB="$STUBS/java"

hex_of() {
  # Same rule the installer uses: macOS ships shasum, most Linux images ship
  # sha256sum. A GNU-only assumption here would fail the macOS CI job while the
  # installer it is testing worked correctly.
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | sed 's/[[:space:]].*//'
  else
    sha256sum "$1" | sed 's/[[:space:]].*//'
  fi
}

make_release() { # tag content sha-mode(good|star|bad|none)
  local tag=$1 content=$2 mode=${3:-good}
  local dir="$WWW/releases/download/$tag"
  mkdir -p "$dir"
  printf '%s' "$content" > "$dir/oq.jar"
  case "$mode" in
    good) printf '%s  oq.jar\n' "$(hex_of "$dir/oq.jar")" > "$dir/oq.jar.sha256" ;;
    star) printf '%s *oq.jar\n' "$(hex_of "$dir/oq.jar")" > "$dir/oq.jar.sha256" ;;
    bad)  printf '%s  oq.jar\n' "$(hex_of "$WWW/other")" > "$dir/oq.jar.sha256" ;;
    none) : ;;
  esac
}

make_api() { # name json -> prints url
  printf '%s' "$2" > "$WWW/api-$1.json"
  printf 'http://127.0.0.1:%s/api-%s.json\n' "$PORT" "$1"
}

install_to() { # dir api-url
  env OQ_RELEASE_API="$2" \
      OQ_RELEASE_TAG_BASE="$BASE" \
      OQ_JAVA="$JAVA_STUB" \
      OQ_INSTALL_DIR="$1" \
      bash "$SCRIPT"
}

no_temp_files() { # dir
  local dir=$1
  if find "$dir" -maxdepth 1 -name '.*.tmp.*' -print -quit | grep -q .; then
    echo "leftover temp files: $(find "$dir" -maxdepth 1 -name '.*.tmp.*' -print)"
    return 1
  fi
  return 0
}

run_case() {
  local name=$1
  shift
  n=$((n + 1))
  if "$@"; then
    printf 'ok %d - %s\n' "$n" "$name"
    pass=$((pass + 1))
  else
    printf 'not ok %d - %s\n' "$n" "$name"
    failed=$((failed + 1))
  fi
}

# ---------------------------------------------------------------------------
case_discovery_skips_draft() {
  local dir="$ROOT/c1" out
  make_release v0.1.0-alpha.2 "PRERELEASE JAR" good
  make_release v9.9.9-draft "DRAFT JAR" good
  local api
  api=$(make_api discovery '[
    {
      "tag_name": "v9.9.9-draft",
      "draft": true,
      "prerelease": true,
      "assets": []
    },
    {
      "tag_name": "v0.1.0-alpha.2",
      "draft": false,
      "prerelease": true,
      "assets": []
    }
  ]')
  out=$(install_to "$dir" "$api" 2>&1) || { echo "$out"; return 1; }
  grep -q 'oq.jar' <<<"$out" >/dev/null
  [ "$(cat "$dir/oq.jar")" = "PRERELEASE JAR" ] || { echo "wrong jar: $(cat "$dir/oq.jar")"; return 1; }
  grep -q '0\.1\.0-alpha\.2' <<<"$out" || { echo "$out"; return 1; }
  ! grep -q '9\.9\.9' <<<"$out"
}

case_pinned_without_v() {
  local dir="$ROOT/c2" out
  make_release v0.2.0 "PINNED NO V" good
  out=$(OQ_VERSION=0.2.0 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  [ "$(cat "$dir/oq.jar")" = "PINNED NO V" ]
}

case_pinned_with_v() {
  local dir="$ROOT/c3" out
  make_release v0.3.0 "PINNED WITH V" star
  out=$(OQ_VERSION=v0.3.0 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  [ "$(cat "$dir/oq.jar")" = "PINNED WITH V" ]
}

case_missing_sha() {
  local dir="$ROOT/c4" out
  make_release v0.4.0 "NO SHA JAR" none
  if out=$(OQ_VERSION=0.4.0 install_to "$dir" "$BASE" 2>&1); then
    echo "expected failure, got: $out"
    return 1
  fi
  [ ! -e "$dir/oq.jar" ] || { echo "jar was installed"; return 1; }
  grep -qi 'checksum' <<<"$out" || { echo "$out"; return 1; }
  no_temp_files "$dir"
}

case_corrupt_jar() {
  local dir="$ROOT/c5" out
  make_release v0.5.0 "" good
  if out=$(OQ_VERSION=0.5.0 install_to "$dir" "$BASE" 2>&1); then
    echo "expected failure, got: $out"
    return 1
  fi
  [ ! -e "$dir/oq.jar" ] || { echo "jar was installed"; return 1; }
  grep -q 'failed to run' <<<"$out" || { echo "$out"; return 1; }
  no_temp_files "$dir"
}

case_checksum_preserves_previous() {
  local dir="$ROOT/c6" out
  mkdir -p "$dir"
  printf 'PREVIOUS JAR BYTES' > "$dir/oq.jar"
  local before
  before=$(cat "$dir/oq.jar")
  make_release v0.6.0 "NEW JAR" bad
  if out=$(OQ_VERSION=0.6.0 install_to "$dir" "$BASE" 2>&1); then
    echo "expected failure, got: $out"
    return 1
  fi
  [ "$(cat "$dir/oq.jar")" = "$before" ] || { echo "previous jar was replaced"; return 1; }
  grep -q 'Checksum mismatch' <<<"$out" || { echo "$out"; return 1; }
  no_temp_files "$dir"
}

case_interrupted_download() {
  local dir="$ROOT/c7" out
  mkdir -p "$dir"
  printf 'PREVIOUS JAR BYTES' > "$dir/oq.jar"
  local trunc_base="http://127.0.0.1:$TRUNC_PORT/trunc"
  if out=$(env OQ_RELEASE_API="$BASE" OQ_RELEASE_TAG_BASE="$trunc_base" \
        OQ_JAVA="$JAVA_STUB" OQ_INSTALL_DIR="$dir" OQ_VERSION=0.7.0 \
        bash "$SCRIPT" 2>&1); then
    echo "expected failure, got: $out"
    return 1
  fi
  [ "$(cat "$dir/oq.jar")" = "PREVIOUS JAR BYTES" ] || { echo "previous jar was replaced"; return 1; }
  no_temp_files "$dir"
}

case_path_with_spaces() {
  local dir="$ROOT/c8 with spaces" out
  make_release v0.8.0 "SPACED JAR" good
  out=$(OQ_VERSION=0.8.0 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  [ "$(cat "$dir/oq.jar")" = "SPACED JAR" ] || { echo "jar missing in spaced dir"; return 1; }
  [ -x "$dir/oq" ] || { echo "launcher not executable"; return 1; }
  # Run the generated launcher against the stub JVM, exactly as the installer's own
  # smoke test does, so the result does not depend on which java is first on PATH.
  out=$(OQ_JAVA="$STUBS/java" "$dir/oq")
  grep -q '^oq ' <<<"$out"
}

case_repeated_install() {
  local dir="$ROOT/c9" out
  make_release v0.9.0 "FIRST JAR" good
  out=$(OQ_VERSION=0.9.0 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  make_release v0.9.1 "SECOND JAR" good
  out=$(OQ_VERSION=0.9.1 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  [ "$(cat "$dir/oq.jar")" = "SECOND JAR" ] || { echo "jar was not replaced"; return 1; }
  no_temp_files "$dir"
}

case_java17_rejected() {
  local dir="$ROOT/c10" out
  make_release v0.10.0 "JAVA 17 JAR" good
  if out=$(env OQ_RELEASE_API="$BASE" OQ_RELEASE_TAG_BASE="$BASE" \
        OQ_JAVA="$STUBS/java17" OQ_INSTALL_DIR="$dir" OQ_VERSION=0.10.0 \
        bash "$SCRIPT" 2>&1); then
    echo "expected failure, got: $out"
    return 1
  fi
  grep -q 'Java 21+' <<<"$out" || { echo "$out"; return 1; }
  grep -q '17.0.1' <<<"$out" || { echo "$out"; return 1; }
  [ ! -e "$dir/oq.jar" ] || { echo "jar installed despite old java"; return 1; }
}

case_java21_accepted() {
  local dir="$ROOT/c11" out
  make_release v0.11.0 "JAVA 21 JAR" good
  out=$(env OQ_RELEASE_API="$BASE" OQ_RELEASE_TAG_BASE="$BASE" \
        OQ_JAVA="$STUBS/java21" OQ_INSTALL_DIR="$dir" OQ_VERSION=0.11.0 \
        bash "$SCRIPT" 2>&1)
  local rc=$?
  [ "$rc" -eq 0 ] || { echo "$out"; return 1; }
  [ "$(cat "$dir/oq.jar")" = "JAVA 21 JAR" ] || { echo "jar not installed"; return 1; }
}

case_temp_cleanup() {
  local dir="$ROOT/c12" out
  make_release v0.12.0 "CLEAN JAR" good
  out=$(OQ_VERSION=0.12.0 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  no_temp_files "$dir" || return 1
  make_release v0.12.1 "CLEAN FAIL" bad
  out=$(OQ_VERSION=0.12.1 install_to "$dir" "$BASE" 2>&1) && { echo "expected failure"; return 1; }
  no_temp_files "$dir"
}

case_launcher_self_dir() {
  local dir="$ROOT/c13" out
  make_release v0.13.0 "LAUNCHER JAR" good
  out=$(OQ_VERSION=0.13.0 install_to "$dir" "$BASE" 2>&1) || { echo "$out"; return 1; }
  grep -q 'pwd -P' "$dir/oq" || { echo "launcher does not use pwd -P"; return 1; }
  if grep -q 'readlink -f' "$dir/oq"; then
    echo "launcher uses readlink -f"
    return 1
  fi
  out=$(OQ_JAVA="$STUBS/java" "$dir/oq")
  grep -q '^oq ' <<<"$out" || { echo "launcher output: $out"; return 1; }
  [ "$(cat "$dir/oq.jar")" = "LAUNCHER JAR" ]
}

run_case "API discovery skips drafts and selects the prerelease" case_discovery_skips_draft
run_case "pinned version without v" case_pinned_without_v
run_case "pinned version with v" case_pinned_with_v
run_case "missing .sha256 fails and leaves no installed jar" case_missing_sha
run_case "empty/corrupt jar fails" case_corrupt_jar
run_case "checksum mismatch preserves the previous oq.jar" case_checksum_preserves_previous
run_case "interrupted download preserves the previous jar" case_interrupted_download
run_case "install dir path containing spaces" case_path_with_spaces
run_case "repeated install replaces atomically" case_repeated_install
run_case "unsupported Java 17 is rejected before download" case_java17_rejected
run_case "Java 21 is accepted" case_java21_accepted
run_case "temp files are cleaned up on success and failure" case_temp_cleanup
run_case "launcher resolves its own directory without readlink -f" case_launcher_self_dir

printf '%d passing\n' "$pass"
if [ "$failed" -ne 0 ]; then
  printf '%d failing\n' "$failed"
  exit 1
fi
exit 0
