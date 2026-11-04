#!/usr/bin/env bash
# Keeps an Oqthara agent running in a clean session. Run from the repo root.
set -uo pipefail

BIN=$(command -v kilo || command -v kilocode || true)
if [ -z "$BIN" ]; then
  echo "error: neither 'kilo' nor 'kilocode' is on PATH" >&2
  exit 1
fi

STATE_DIR=".oqthara-ai"
LOG="$STATE_DIR/LOG.md"
PROMPT="Read .oqthara-ai/MASTER_PROMPT.md and .oqthara-ai/RESUME.md and continue the loop from where it stopped. Never stop. Never ask questions."

mkdir -p "$STATE_DIR"
stamp() { date -u +%FT%TZ; }

heartbeat_age() {
  [ -f "$STATE_DIR/state.json" ] || { echo 999999; return; }
  local hb
  hb=$(python3 -c "import json,sys;print(json.load(open('$STATE_DIR/state.json')).get('heartbeat',''))" 2>/dev/null || echo "")
  [ -z "$hb" ] && { echo 999999; return; }
  local then now
  then=$(date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$hb" +%s 2>/dev/null || date -u -d "$hb" +%s 2>/dev/null || echo 0)
  now=$(date -u +%s)
  echo $(( now - then ))
}

while true; do
  age=$(heartbeat_age)
  if [ "$age" -gt 1800 ]; then
    echo "$(stamp) STALLED heartbeat_age=${age}s" >> "$LOG"
  fi

  echo "$(stamp) START kilo" >> "$LOG"
  "$BIN" run "$PROMPT" --auto 2>>"$LOG"
  code=$?
  echo "$(stamp) exit=$code" >> "$LOG"

  if [ "$code" -eq 0 ]; then
    sleep 10
  else
    sleep 30
  fi
done
