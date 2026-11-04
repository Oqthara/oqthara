#!/usr/bin/env python3
"""Supervisor for supervised Oqthara engineering cycles.

Each `kilo run` invocation performs exactly one bounded cycle: it reads the master prompt
and the resume file, completes one task with evidence, writes a checkpoint, and exits. This
supervisor starts the next invocation, with backoff and an explicit stop mechanism.

Usage:
    python3 scripts/ai-watchdog.py                      # run until .oqthara-ai/STOP appears
    OQTHARA_MAX_CYCLES=1 python3 scripts/ai-watchdog.py # one cycle, for testing
    python3 scripts/ai-watchdog.py --dry-run            # print the resolved configuration
    python3 scripts/ai-watchdog.py --status             # lock, heartbeat, cycle, log path
    python3 scripts/ai-watchdog.py --install            # install a launchd/systemd user unit
    python3 scripts/ai-watchdog.py --uninstall          # unload and remove that unit
    python3 scripts/ai-watchdog.py --help               # this text

Options:
    -h, --help      show this help and exit
    -n, --dry-run   print binary, agent, state dir, stop file, heartbeat and limits; exit
        --status    print lock pid, liveness, heartbeat age, stop file, cycle count,
                    last checkpoint and the log path; exit
        --install   write and load the platform user service (macOS launchd,
                    Linux systemd --user) that keeps the supervisor alive; exit
        --uninstall unload and delete that user service; exit

Environment:
    OQTHARA_STATE_DIR       state directory (default: <repo>/.oqthara-ai)
    OQTHARA_MAX_CYCLES      number of cycles, 0 or unset means unlimited
    OQTHARA_CYCLE_TIMEOUT   seconds allowed per cycle (default 3600)
    OQTHARA_BACKOFF         base seconds between cycles (default 10)

Stop:
    touch .oqthara-ai/STOP

This file never removes STOP. Deleting it is a human decision.
"""

from __future__ import annotations

import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_state_override = os.environ.get("OQTHARA_STATE_DIR")
STATE_DIR = Path(_state_override).expanduser() if _state_override else ROOT / ".oqthara-ai"
STATE_FILE = STATE_DIR / "state.json"
LOG_FILE = STATE_DIR / "LOG.md"
STOP_FILE = STATE_DIR / "STOP"
LOCK_FILE = STATE_DIR / ".watchdog.lock"
HEARTBEAT_FILE = STATE_DIR / ".watchdog.heartbeat"

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
THROTTLE_SECONDS = 60

UNIT_MACOS = "dev.oqthara.supervisor.plist"
UNIT_LINUX = "oqthara-supervisor.service"

SHUTDOWN = False
LOCK_OWNED = False


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


def write_heartbeat() -> None:
    """Record the supervisor's liveness as epoch seconds, best effort."""
    try:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        HEARTBEAT_FILE.write_text(str(int(time.time())), encoding="utf-8")
    except OSError:
        pass


def heartbeat_age() -> int | None:
    """Seconds since the supervisor's own heartbeat, or None when it cannot be read.

    Cycle progress is a different signal: see `cycle_state_age`.
    """
    try:
        stamp = int(HEARTBEAT_FILE.read_text(encoding="utf-8").strip())
        return max(0, int(time.time()) - stamp)
    except (OSError, ValueError):
        pass
    state = read_state()
    stamp = state.get("heartbeat")
    if not stamp:
        return None
    try:
        then = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    return int((datetime.now(timezone.utc) - then).total_seconds())


def cycle_state_age() -> int | None:
    """Seconds since state.json's heartbeat, the signal a Kilo cycle actually advanced."""
    stamp = state_heartbeat()
    if not stamp:
        return None
    try:
        then = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    return int((datetime.now(timezone.utc) - then).total_seconds())


def state_heartbeat() -> str | None:
    """The raw heartbeat string from state.json, for exact before/after comparison."""
    stamp = read_state().get("heartbeat")
    return str(stamp) if stamp else None


def kilo_binary() -> str | None:
    found = shutil.which("kilo") or shutil.which("kilocode")
    if found:
        return found
    # launchd and systemd start jobs with a minimal PATH; look where installers put it.
    for candidate in (
        Path.home() / ".kilo" / "bin" / "kilo",
        Path.home() / ".local" / "bin" / "kilo",
        Path("/usr/local/bin/kilo"),
        Path("/opt/homebrew/bin/kilo"),
    ):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def service_path() -> str:
    """PATH for the supervised job: the directory holding kilo, then the usual places."""
    binary = kilo_binary()
    parts = []
    if binary:
        parts.append(str(Path(binary).parent))
    parts.extend(["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"])
    seen = []
    for part in parts:
        if part not in seen:
            seen.append(part)
    return os.pathsep.join(seen)


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


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def pid_is_watchdog(pid: int) -> bool:
    """True only for a live process whose command line runs ai-watchdog.py."""
    if not pid_alive(pid):
        return False
    if os.name == "nt":
        return True
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "command="],
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    command = result.stdout.strip()
    if not command:
        return False
    return "ai-watchdog.py" in command


def lock_pid() -> int | None:
    try:
        return int(LOCK_FILE.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None


def create_lock_file() -> bool:
    """Create the lock atomically with the current PID. False when it already exists.

    The PID is written while the descriptor is still open so the file is never left empty
    by a normal path; a concurrent reader that catches the empty window is handled by
    `acquire_lock`, which refuses rather than stealing a lock it cannot verify.
    """
    try:
        descriptor = os.open(str(LOCK_FILE), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
    except FileExistsError:
        return False
    try:
        os.write(descriptor, str(os.getpid()).encode("utf-8"))
    finally:
        os.close(descriptor)
    return True


def acquire_lock() -> bool:
    """Refuse to run two supervisors in the same checkout.

    A stale lock (dead PID, or a live PID that is not a watchdog) is removed once and the
    atomic create is retried exactly once. A lock that cannot be read yet is given a short
    grace period because its owner may still be writing the PID; if it stays unreadable the
    supervisor refuses to start instead of deleting a lock it cannot verify. The lock of a
    live watchdog is never removed.
    """
    global LOCK_OWNED
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    if create_lock_file():
        LOCK_OWNED = True
        return True

    pid = lock_pid()
    if pid is None:
        # Another supervisor may be inside the create-then-write window.
        for _ in range(20):
            time.sleep(0.05)
            pid = lock_pid()
            if pid is not None:
                break
    if pid_is_watchdog(pid or -1):
        log(f"LOCK live supervisor pid={pid}")
        return False
    if pid is None:
        log(f"LOCK unreadable at {LOCK_FILE}; refusing to steal it")
        return False

    reason = "not-a-watchdog" if pid_alive(pid) else "dead"
    log(f"STALE LOCK pid={pid} reason={reason}")
    try:
        LOCK_FILE.unlink()
    except FileNotFoundError:
        pass
    if create_lock_file():
        LOCK_OWNED = True
        return True
    return False


def release_lock() -> None:
    """Remove the lock only when this process owns it."""
    global LOCK_OWNED
    if not LOCK_OWNED:
        return
    LOCK_OWNED = False
    try:
        LOCK_FILE.unlink()
    except FileNotFoundError:
        pass


def stop_requested() -> bool:
    return STOP_FILE.exists()


def read_cycle_state() -> tuple[int, str, str]:
    """Returns (cycle, checkpoint, status) from state.json, or zeros when unreadable."""
    state = read_state()
    try:
        cycle = int(state.get("cycle") or 0)
    except (TypeError, ValueError):
        cycle = 0
    return cycle, str(state.get("last_checkpoint") or "?"), str(state.get("status") or "?")


def run_one_cycle(binary: str, agent: str, timeout: int) -> tuple[int, str, str]:
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
        return 124, "timeout", "unknown"
    except OSError as error:
        log(f"ERROR cannot start kilo: {error}")
        return 127, "error", "unknown"
    log(f"EXIT code={code}")
    cycle, checkpoint, status = read_cycle_state()
    log(f"STATE cycle={cycle} checkpoint={checkpoint} status={status}")
    print(
        f"CYCLE | cycle={cycle} | exit={code} | checkpoint={checkpoint} | status={status}",
        flush=True,
    )
    return code, checkpoint, status


def wait_for(seconds: int) -> bool:
    """Wait up to `seconds`, waking every second. True when shutdown or STOP was requested."""
    deadline = time.monotonic() + max(0, seconds)
    while time.monotonic() < deadline:
        if SHUTDOWN or stop_requested():
            return True
        time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))
    return SHUTDOWN or stop_requested()


def _handle_signal(signum: int, _frame) -> None:
    global SHUTDOWN
    SHUTDOWN = True
    try:
        name = signal.Signals(signum).name
    except ValueError:
        name = str(signum)
    release_lock()
    log(f"SHUTDOWN signal={name}")
    raise SystemExit(0)


def install_signal_handlers() -> None:
    for name in ("SIGTERM", "SIGINT"):
        number = getattr(signal, name, None)
        if number is None:
            continue
        try:
            signal.signal(number, _handle_signal)
        except (OSError, ValueError):
            pass


def script_path() -> Path:
    return Path(__file__).resolve()


def unit_path() -> Path | None:
    if sys.platform == "darwin":
        return Path.home() / "Library" / "LaunchAgents" / UNIT_MACOS
    if sys.platform.startswith("linux"):
        return Path.home() / ".config" / "systemd" / "user" / UNIT_LINUX
    return None


def supervisor_log() -> Path:
    return STATE_DIR / "supervisor.out.log"


def run_command(command: list[str], ignore_failure: bool = False) -> int:
    print(f"+ {shlex.join(command)}", flush=True)
    try:
        result = subprocess.run(command)
        code = result.returncode
    except OSError as error:
        print(f"! {shlex.join(command)}: {error}", file=sys.stderr)
        return 0 if ignore_failure else 127
    if code != 0 and not ignore_failure:
        print(f"! command exited {code}", file=sys.stderr)
    return code


def unit_payload() -> dict:
    """The launchd job. Restarts only after an unsuccessful exit.

    A successful exit means the supervisor honoured STOP or OQTHARA_MAX_CYCLES, so launchd
    must not start it again: KeepAlive is the SuccessfulExit form, not a bare True.
    """
    return {
        "Label": "dev.oqthara.supervisor",
        "ProgramArguments": [sys.executable, str(script_path())],
        "WorkingDirectory": str(ROOT),
        "RunAtLoad": True,
        "KeepAlive": {"SuccessfulExit": False},
        "ThrottleInterval": THROTTLE_SECONDS,
        "StandardOutPath": str(supervisor_log()),
        "StandardErrorPath": str(supervisor_log()),
        "EnvironmentVariables": {
            "OQTHARA_STATE_DIR": str(STATE_DIR),
            "PATH": service_path(),
        },
    }


def systemd_unit() -> str:
    """The Linux user unit. Restart=on-failure so a clean STOP exit is not respawned."""
    exec_start = f"{shlex.quote(sys.executable)} {shlex.quote(str(script_path()))}"
    lines = [
        "[Unit]",
        "Description=Oqthara supervisor",
        "After=default.target",
        "",
        "[Service]",
        "Type=simple",
        f"ExecStart={exec_start}",
        f"WorkingDirectory={shlex.quote(str(ROOT))}",
        "Restart=on-failure",
        f"RestartSec={THROTTLE_SECONDS}",
        f"Environment=OQTHARA_STATE_DIR={shlex.quote(str(STATE_DIR))}",
        f"Environment=PATH={service_path()}",
        f"StandardOutput=append:{supervisor_log()}",
        f"StandardError=append:{supervisor_log()}",
        "",
        "[Install]",
        "WantedBy=default.target",
        "",
    ]
    return "\n".join(lines)


def install() -> int:
    target = unit_path()
    if target is None:
        print(
            f"unsupported platform {sys.platform!r}: expected darwin or linux",
            file=sys.stderr,
        )
        return 1

    STATE_DIR.mkdir(parents=True, exist_ok=True)
    target.parent.mkdir(parents=True, exist_ok=True)

    if sys.platform == "darwin":
        import plistlib

        target.write_bytes(plistlib.dumps(unit_payload(), sort_keys=False))
        print(f"wrote {target}")
        run_command(["launchctl", "unload", str(target)], ignore_failure=True)
        return run_command(["launchctl", "load", str(target)])

    target.write_text(systemd_unit(), encoding="utf-8")
    print(f"wrote {target}")
    run_command(["systemctl", "--user", "daemon-reload"], ignore_failure=True)
    return run_command(["systemctl", "--user", "enable", "--now", UNIT_LINUX])


def uninstall() -> int:
    target = unit_path()
    if target is None:
        print(
            f"unsupported platform {sys.platform!r}: expected darwin or linux",
            file=sys.stderr,
        )
        return 1

    if sys.platform == "darwin":
        run_command(["launchctl", "unload", str(target)], ignore_failure=True)
    else:
        run_command(
            ["systemctl", "--user", "disable", "--now", UNIT_LINUX],
            ignore_failure=True,
        )

    try:
        target.unlink()
        print(f"removed {target}")
    except FileNotFoundError:
        print(f"no unit file at {target}")
    return 0


def print_status() -> int:
    pid = lock_pid()
    cycle, checkpoint, _status = read_cycle_state()
    age = heartbeat_age()
    print(f"state dir: {STATE_DIR}")
    print(f"lock: {'held' if LOCK_FILE.exists() else 'none'}")
    print(f"lock pid: {pid if pid is not None else '-'}")
    print(f"lock alive: {'yes' if pid is not None and pid_alive(pid) else 'no'}")
    print(f"heartbeat age: {age if age is not None else '-'}s")
    print(f"stop file: {'present' if stop_requested() else 'absent'}")
    print(f"cycle count: {cycle}")
    print(f"last checkpoint: {checkpoint}")
    print(f"log: {LOG_FILE}")
    return 0


def parse_args(argv: list[str]) -> dict:
    options = {
        "help": False,
        "dry_run": False,
        "status": False,
        "install": False,
        "uninstall": False,
    }
    for arg in argv:
        if arg in ("-h", "--help"):
            options["help"] = True
        elif arg in ("-n", "--dry-run"):
            options["dry_run"] = True
        elif arg == "--status":
            options["status"] = True
        elif arg == "--install":
            options["install"] = True
        elif arg == "--uninstall":
            options["uninstall"] = True
        else:
            print(f"unknown option: {arg}", file=sys.stderr)
            options["help"] = True
    return options


def print_help() -> None:
    print(__doc__.strip())


def persist_ok(before: str | None) -> bool:
    """A cycle counts as persisted when state.json's heartbeat changed and is fresh.

    Compares the raw string, so a whole-second truncation cannot fake an advance, and a
    cycle that legitimately ran but did not update the heartbeat is reported honestly.
    """
    after = state_heartbeat()
    if after is None:
        return False
    if before is not None and after == before:
        return False
    age = cycle_state_age()
    return age is not None and age < 900


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

    actions = [name for name in ("install", "uninstall", "status") if options[name]]
    if len(actions) > 1:
        print(f"options are mutually exclusive: {', '.join(actions)}", file=sys.stderr)
        return 1
    if options["install"]:
        return install()
    if options["uninstall"]:
        return uninstall()
    if options["status"]:
        return print_status()

    install_signal_handlers()

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
        write_heartbeat()
        agent = agent_name(binary)
        max_cycles = int(os.environ.get("OQTHARA_MAX_CYCLES", "0") or 0)
        timeout = int(os.environ.get("OQTHARA_CYCLE_TIMEOUT", str(DEFAULT_TIMEOUT)))
        base_backoff = int(os.environ.get("OQTHARA_BACKOFF", "10"))

        cycle = 0
        consecutive_failures = 0
        log(f"BOOT agent={agent} max_cycles={max_cycles or 'unlimited'} lock={os.getpid()}")

        while True:
            if SHUTDOWN:
                log("SHUTDOWN flag set; supervisor exiting")
                break
            if stop_requested():
                log("STOP file detected; supervisor exiting")
                break
            if max_cycles and cycle >= max_cycles:
                log(f"REACHED max_cycles={max_cycles}")
                break

            cycle += 1
            write_heartbeat()
            log(f"CYCLE {cycle} begin")
            stamp_before = state_heartbeat()
            code, _checkpoint, _status = run_one_cycle(binary, agent, timeout)
            write_heartbeat()
            if not persist_ok(stamp_before):
                log("WARNING state.json did not advance; a later cycle must recover")

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
            if wait_for(sleep_for):
                if stop_requested():
                    log("STOP file detected during backoff; exiting")
                else:
                    log("SHUTDOWN requested during backoff; exiting")
                break
    finally:
        release_lock()
        if not SHUTDOWN:
            log("SHUTDOWN")

    return 0


if __name__ == "__main__":
    sys.exit(main())
