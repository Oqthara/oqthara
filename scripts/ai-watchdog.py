#!/usr/bin/env python3
"""Supervisor for supervised Oqthara engineering cycles.

Each `kilo run` invocation performs exactly one bounded cycle: it reads the master prompt
and the resume file, completes one task with evidence, writes a checkpoint, and exits. This
supervisor starts the next invocation, with backoff and an explicit stop mechanism.

Usage:
    python3 scripts/ai-watchdog.py                 # run until .oqthara-ai/STOP appears
    OQTHARA_MAX_CYCLES=1 python3 scripts/ai-watchdog.py   # one cycle, for testing

Stop:
    touch .oqthara-ai/STOP

This file never removes STOP. Deleting it is a human decision.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = ROOT / ".oqthara-ai"
STATE_FILE = STATE_DIR / "state.json"
LOG_FILE = STATE_DIR / "LOG.md"
STOP_FILE = STATE_DIR / "STOP"
LOCK_FILE = STATE_DIR / ".watchdog.lock"

PROMPT = (
    "Read .oqthara-ai/MASTER_PROMPT.md, .oqthara-ai/RESUME.md, state.json, STATUS.md, "
    "BACKLOG.md, BLOCKERS.md and the latest checkpoint. Execute exactly ONE bounded cycle "
    "as described in section 7 of the master prompt: pick one task with a verifiable "
    "acceptance criterion, write an intention checkpoint, delegate with the task tool, run "
    "the tests, get an independent review, commit, push and open a PR when GitHub access is "
    "available, update state and write the final checkpoint. Then stop this invocation. "
    "Do not ask questions. If the build or CI is red, that is the cycle."
)

DEFAULT_TIMEOUT = 3600
MAX_BACKOFF = 900
HEARTBEAT_STALE_SECONDS = 1800


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def log(line: str) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    with LOG_FILE.open("a", encoding="utf-8") as handle:
        handle.write(f"- {now()} SUPERVISOR {line}\n")
    print(f"[{now()}] {line}", flush=True)


def read_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def heartbeat_age() -> int | None:
    """Seconds since state.json's heartbeat, or None when it cannot be read."""
    state = read_state()
    stamp = state.get("heartbeat")
    if not stamp:
        return None
    try:
        then = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    return int((datetime.now(timezone.utc) - then).total_seconds())


def kilo_binary() -> str | None:
    return shutil.which("kilo") or shutil.which("kilocode")


def agent_name(binary: str) -> str:
    """Prefer the custom orchestrator, fall back to a stock primary agent."""
    try:
        result = subprocess.run(
            [binary, "agent", "list"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (subprocess.SubprocessError, OSError):
        return "code"
    if "oq-orchestrator" in result.stdout:
        return "oq-orchestrator"
    return "code"


def acquire_lock() -> bool:
    """Refuse to run two supervisors in the same checkout."""
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    if LOCK_FILE.exists():
        try:
            pid = int(LOCK_FILE.read_text().strip())
        except (ValueError, OSError):
            pid = -1
        if pid > 0:
            try:
                os.kill(pid, 0)
            except OSError:
                pass  # stale lock from a dead process
            else:
                return False
    LOCK_FILE.write_text(str(os.getpid()), encoding="utf-8")
    return True


def release_lock() -> None:
    try:
        LOCK_FILE.unlink()
    except FileNotFoundError:
        pass


def stop_requested() -> bool:
    return STOP_FILE.exists()


def run_one_cycle(binary: str, agent: str, timeout: int) -> int:
    age = heartbeat_age()
    if age is not None and age > HEARTBEAT_STALE_SECONDS:
        log(f"STALLED heartbeat_age={age}s before start")

    command = [binary, "run", "--agent", agent, "--auto", PROMPT]
    log(f"START agent={agent} timeout={timeout}s")
    try:
        completed = subprocess.run(command, cwd=ROOT, timeout=timeout)
        code = completed.returncode
    except subprocess.TimeoutExpired:
        log(f"TIMEOUT after {timeout}s; the process was terminated")
        return 124
    except OSError as error:
        log(f"ERROR cannot start kilo: {error}")
        return 127
    log(f"EXIT code={code}")
    return code


def parse_args(argv: list[str]) -> dict:
    options = {"help": False, "dry_run": False}
    for arg in argv:
        if arg in ("-h", "--help"):
            options["help"] = True
        elif arg in ("-n", "--dry-run"):
            options["dry_run"] = True
        else:
            print(f"unknown option: {arg}", file=sys.stderr)
            options["help"] = True
    return options


def print_help() -> None:
    print(__doc__.strip())


def main() -> int:
    options = parse_args(sys.argv[1:])
    if options["help"]:
        print_help()
        return 0
    if options["dry_run"]:
        binary = kilo_binary()
        agent = agent_name(binary) if binary else "code"
        print(f"binary: {binary or '(not found)'}")
        print(f"agent: {agent}")
        print(f"state dir: {STATE_DIR}")
        print(f"stop file present: {stop_requested()}")
        print(f"heartbeat age: {heartbeat_age()}s")
        print(f"max cycles: {os.environ.get('OQTHARA_MAX_CYCLES', '0') or 'unlimited'}")
        print(f"timeout: {os.environ.get('OQTHARA_CYCLE_TIMEOUT', str(DEFAULT_TIMEOUT))}s")
        print(f"command: {binary or 'kilo'} run --agent {agent} --auto \"<one-cycle prompt>\"")
        return 0

    if stop_requested():
        print(f"STOP file present at {STOP_FILE}; doing nothing.", file=sys.stderr)
        return 0

    binary = kilo_binary()
    if not binary:
        log("ERROR neither 'kilo' nor 'kilocode' is on PATH")
        return 1

    if not acquire_lock():
        print("Another supervisor is already running in this checkout.", file=sys.stderr)
        return 1

    try:
        agent = agent_name(binary)
        max_cycles = int(os.environ.get("OQTHARA_MAX_CYCLES", "0") or 0)
        timeout = int(os.environ.get("OQTHARA_CYCLE_TIMEOUT", str(DEFAULT_TIMEOUT)))
        base_backoff = int(os.environ.get("OQTHARA_BACKOFF", "10"))

        cycle = 0
        consecutive_failures = 0
        log(f"BOOT agent={agent} max_cycles={max_cycles or 'unlimited'} lock={os.getpid()}")

        while True:
            if stop_requested():
                log("STOP file detected; supervisor exiting")
                break
            if max_cycles and cycle >= max_cycles:
                log(f"REACHED max_cycles={max_cycles}")
                break

            cycle += 1
            log(f"CYCLE {cycle} begin")
            code = run_one_cycle(binary, agent, timeout)

            if code == 0:
                consecutive_failures = 0
                sleep_for = base_backoff
            else:
                consecutive_failures += 1
                sleep_for = min(MAX_BACKOFF, base_backoff * (2 ** consecutive_failures))
                log(f"FAILURE consecutive={consecutive_failures} backoff={sleep_for}s")

            if stop_requested():
                log("STOP file detected after the cycle; exiting")
                break
            if max_cycles and cycle >= max_cycles:
                break

            log(f"SLEEP {sleep_for}s before the next cycle")
            time.sleep(sleep_for)
    finally:
        release_lock()
        log("SHUTDOWN")

    return 0


if __name__ == "__main__":
    sys.exit(main())
