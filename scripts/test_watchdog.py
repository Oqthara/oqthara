#!/usr/bin/env python3
"""Subprocess tests for scripts/ai-watchdog.py.

Stdlib only. Every test drives the script as a subprocess with OQTHARA_STATE_DIR pointed at
a temporary directory, so nothing here touches the real .oqthara-ai/ state.

Run:
    python3 scripts/test_watchdog.py -v
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "ai-watchdog.py"
SLEEP = shutil.which("sleep") or "/bin/sleep"

# Imports the watchdog as a module and dumps the launchd payload, without loading any unit.
UNIT_PROBE = (
    "import importlib.util, json, sys\n"
    "from pathlib import Path\n"
    f"spec = importlib.util.spec_from_file_location('wd', {str(SCRIPT)!r})\n"
    "module = importlib.util.module_from_spec(spec)\n"
    "spec.loader.exec_module(module)\n"
    "print(json.dumps(module.unit_payload()))\n"
)

# Same idea for the systemd unit: render the text install() would write, without writing it.
SYSTEMD_PROBE = (
    "import importlib.util, sys\n"
    f"spec = importlib.util.spec_from_file_location('wd', {str(SCRIPT)!r})\n"
    "module = importlib.util.module_from_spec(spec)\n"
    "spec.loader.exec_module(module)\n"
    "print(module.systemd_unit())\n"
)

# Exercises persist_ok and release_lock in-process against a temporary state dir.
LOGIC_PROBE = (
    "import importlib.util, json, sys\n"
    "from datetime import datetime, timedelta, timezone\n"
    f"spec = importlib.util.spec_from_file_location('wd', {str(SCRIPT)!r})\n"
    "module = importlib.util.module_from_spec(spec)\n"
    "spec.loader.exec_module(module)\n"
    "module.STATE_DIR.mkdir(parents=True, exist_ok=True)\n"
    "state = module.STATE_FILE\n"
    "def at(delta):\n"
    "    return (datetime.now(timezone.utc) - delta).strftime('%Y-%m-%dT%H:%M:%SZ')\n"
    "results = {}\n"
    "first = at(timedelta(minutes=5))\n"
    "second = at(timedelta(minutes=1))\n"
    "state.write_text(json.dumps({'heartbeat': first}))\n"
    "results['same'] = module.persist_ok(first)\n"
    "state.write_text(json.dumps({'heartbeat': second}))\n"
    "results['advanced'] = module.persist_ok(first)\n"
    "state.write_text(json.dumps({'heartbeat': at(timedelta(days=30))}))\n"
    "results['stale'] = module.persist_ok(None)\n"
    "state.write_text(json.dumps({}))\n"
    "results['missing'] = module.persist_ok(None)\n"
    "module.LOCK_FILE.write_text('424242')\n"
    "module.release_lock()\n"
    "results['lock_kept_when_not_owned'] = module.LOCK_FILE.exists()\n"
    "module.LOCK_OWNED = True\n"
    "module.release_lock()\n"
    "results['lock_removed_when_owned'] = not module.LOCK_FILE.exists()\n"
    "print(json.dumps(results))\n"
)

ORDER = {
    "test_dry_run": (1, "dry-run exits 0 and prints agent"),
    "test_status_fresh": (2, "status on a fresh state dir reports no lock"),
    "test_stale_dead_pid": (3, "stale lock with a dead pid is reclaimed"),
    "test_live_non_watchdog": (4, "live non-watchdog pid is treated as stale"),
    "test_stop_respected": (5, "STOP file prevents any cycle"),
    "test_live_watchdog_refused": (6, "live watchdog lock is refused"),
    "test_unit_restarts_only_on_failure": (7, "unit restarts on failure, not after a clean exit"),
    "test_heartbeat_is_independent": (8, "supervisor heartbeat is separate from state.json"),
    "test_unit_carries_a_usable_path": (9, "unit PATH contains the kilo binary directory"),
    "test_systemd_unit_is_well_formed": (10, "systemd unit declares Restart and PATH"),
    "test_unreadable_lock_is_not_stolen": (11, "an empty lock is never deleted by a rival"),
    "test_signal_during_backoff_releases_own_lock": (12, "SIGTERM during backoff drops its own lock"),
    "test_heartbeat_advance_is_exact": (13, "persist_ok compares the raw heartbeat string"),
}


class Result(unittest.TextTestResult):
    def addSuccess(self, test: unittest.TestCase) -> None:
        super().addSuccess(test)
        number, label = ORDER[test._testMethodName]
        print(f"OK {number} - {label}", flush=True)


class WatchdogTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.state = self.tmp / "state"
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.kilo = self.bin / "kilo"
        self.kilo.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        self.kilo.chmod(0o755)
        self.log = self.state / "LOG.md"
        self.lock = self.state / ".watchdog.lock"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def env(self, **extra: str) -> dict:
        env = dict(os.environ)
        env["PATH"] = f"{self.bin}{os.pathsep}{env.get('PATH', '')}"
        env["OQTHARA_STATE_DIR"] = str(self.state)
        env.pop("OQTHARA_MAX_CYCLES", None)
        env.pop("OQTHARA_BACKOFF", None)
        env.update(extra)
        return env

    def run_script(self, args: list[str], **env: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            cwd=ROOT,
            env=self.env(**env),
            capture_output=True,
            text=True,
            timeout=120,
        )

    def start_script(self, args: list[str], **env: str) -> subprocess.Popen:
        return subprocess.Popen(
            [sys.executable, str(SCRIPT), *args],
            cwd=ROOT,
            env=self.env(**env),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def wait_for_log(self, needle: str, timeout: float = 30.0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if needle in self.log.read_text(encoding="utf-8"):
                    return True
            except OSError:
                pass
            time.sleep(0.05)
        return False

    def log_text(self) -> str:
        try:
            return self.log.read_text(encoding="utf-8")
        except OSError:
            return ""

    # 1
    def test_dry_run(self) -> None:
        done = self.run_script(["--dry-run"])
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("agent:", done.stdout)

    # 2
    def test_status_fresh(self) -> None:
        done = self.run_script(["--status"])
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("lock: none", done.stdout)
        self.assertIn("lock pid: -", done.stdout)

    # 11
    def test_unreadable_lock_is_not_stolen(self) -> None:
        """An empty lock may be mid-write by its owner; a rival must refuse, not delete it."""
        self.state.mkdir(parents=True)
        self.lock.write_text("", encoding="utf-8")
        done = self.run_script([], OQTHARA_MAX_CYCLES="1")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("unreadable", self.log_text())
        self.assertTrue(self.lock.exists(), "a rival deleted a lock it could not verify")
        self.assertEqual(self.lock.read_text(encoding="utf-8"), "")

    # 12
    def test_signal_during_backoff_releases_own_lock(self) -> None:
        """SIGTERM while waiting between cycles must exit 0 and drop the lock this process owns."""
        self.state.mkdir(parents=True)
        proc = self.start_script([], OQTHARA_MAX_CYCLES="0", OQTHARA_BACKOFF="60")
        try:
            self.assertTrue(self.wait_for_log("SLEEP 60s"), "supervisor never reached backoff")
            proc.terminate()
            _out, err = proc.communicate(timeout=60)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.communicate()
        self.assertEqual(proc.returncode, 0, err)
        self.assertFalse(self.lock.exists(), "the owner must release its own lock on SIGTERM")
        self.assertIn("SHUTDOWN signal=SIGTERM", self.log_text())

    # 13
    def test_heartbeat_advance_is_exact(self) -> None:
        done = subprocess.run(
            [sys.executable, "-c", LOGIC_PROBE],
            cwd=ROOT,
            env=self.env(),
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        results = json.loads(done.stdout)
        self.assertFalse(results["same"], "an unchanged heartbeat must not count as progress")
        self.assertTrue(results["advanced"], "a changed heartbeat must count as progress")
        self.assertFalse(results["stale"], "an old heartbeat must not count as progress")
        self.assertFalse(results["missing"], "a missing heartbeat must not count as progress")
        self.assertTrue(results["lock_kept_when_not_owned"])
        self.assertTrue(results["lock_removed_when_owned"])

    # 3
    def test_stale_dead_pid(self) -> None:
        self.state.mkdir(parents=True)
        self.lock.write_text("999999", encoding="utf-8")
        proc = self.start_script([], OQTHARA_MAX_CYCLES="0", OQTHARA_BACKOFF="30")
        try:
            self.assertTrue(self.wait_for_log("BOOT"), "supervisor never booted")
            self.assertEqual(self.lock.read_text(encoding="utf-8").strip(), str(proc.pid))
            (self.state / "STOP").write_text("", encoding="utf-8")
            _out, err = proc.communicate(timeout=60)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.communicate()
        self.assertEqual(proc.returncode, 0, err)
        text = self.log_text()
        self.assertIn("STALE LOCK", text)
        self.assertIn("BOOT", text)

    # 4
    def test_live_non_watchdog(self) -> None:
        """A live PID that is not a watchdog must be reported by its real reason."""
        self.state.mkdir(parents=True)
        other = subprocess.Popen([SLEEP, "30"])
        proc = None
        try:
            self.lock.write_text(str(other.pid), encoding="utf-8")
            proc = self.start_script([], OQTHARA_MAX_CYCLES="1")
            _out, err = proc.communicate(timeout=120)
        finally:
            other.kill()
            other.wait()
            if proc is not None and proc.poll() is None:
                proc.kill()
                proc.communicate()
        self.assertEqual(proc.returncode, 0, err)
        text = self.log_text()
        self.assertIn("STALE LOCK", text)
        self.assertIn("reason=not-a-watchdog", text)
        self.assertNotIn("reason=dead", text)
        self.assertIn("BOOT", text)
        self.assertNotIn(f"LOCK live supervisor pid={other.pid}", text)

    # 5
    def test_stop_respected(self) -> None:
        self.state.mkdir(parents=True)
        (self.state / "STOP").write_text("", encoding="utf-8")
        done = self.run_script([], OQTHARA_MAX_CYCLES="1")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertNotIn("START agent=", self.log_text())

    # 6
    def test_live_watchdog_refused(self) -> None:
        self.state.mkdir(parents=True)
        link = self.tmp / "ai-watchdog.py"
        link.symlink_to(SLEEP)
        fake = subprocess.Popen([str(link), "60"])
        try:
            self.lock.write_text(str(fake.pid), encoding="utf-8")
            done = self.run_script([], OQTHARA_MAX_CYCLES="1")
        finally:
            fake.kill()
            fake.wait()
        self.assertEqual(done.returncode, 1, done.stderr)
        self.assertIn("Another supervisor", done.stdout + done.stderr)
        self.assertEqual(self.lock.read_text(encoding="utf-8").strip(), str(fake.pid))

    # 7
    def test_unit_restarts_only_on_failure(self) -> None:
        """A bare KeepAlive=True would respawn the supervisor forever after STOP."""
        done = subprocess.run(
            [sys.executable, "-c", UNIT_PROBE],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        payload = json.loads(done.stdout)
        self.assertEqual(payload["KeepAlive"], {"SuccessfulExit": False})
        self.assertGreaterEqual(payload["ThrottleInterval"], 10)
        self.assertEqual(payload["Label"], "dev.oqthara.supervisor")
        self.assertTrue(payload["RunAtLoad"])
        self.assertEqual(len(payload["ProgramArguments"]), 2)
        self.assertTrue(payload["ProgramArguments"][1].endswith("ai-watchdog.py"))

    # 9
    def test_unit_carries_a_usable_path(self) -> None:
        """launchd starts jobs with a minimal PATH; a missing kilo would kill every boot."""
        done = subprocess.run(
            [sys.executable, "-c", UNIT_PROBE],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        env = json.loads(done.stdout)["EnvironmentVariables"]
        parts = env["PATH"].split(os.pathsep)
        self.assertIn("/usr/bin", parts)
        self.assertIn("/bin", parts)
        probe = subprocess.run(
            ["/bin/sh", "-c", f'PATH={env["PATH"]} command -v kilo'],
            capture_output=True,
            text=True,
        )
        self.assertEqual(probe.returncode, 0, "unit PATH cannot find kilo")
        self.assertTrue(probe.stdout.strip().endswith("kilo"), probe.stdout)

    # 10
    def test_systemd_unit_is_well_formed(self) -> None:
        done = subprocess.run(
            [sys.executable, "-c", SYSTEMD_PROBE],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        unit = done.stdout
        self.assertIn("Restart=on-failure", unit)
        self.assertIn("RestartSec=", unit)
        self.assertIn("Environment=PATH=", unit)
        self.assertIn("WantedBy=default.target", unit)
        self.assertIn("WorkingDirectory=", unit)
        self.assertNotIn("Restart=always", unit)
        for line in unit.splitlines():
            if line and not line.startswith("["):
                self.assertIn("=", line, f"malformed unit line: {line!r}")

    # 8
    def test_heartbeat_is_independent(self) -> None:
        """A stale state.json heartbeat must not count as a fresh supervisor heartbeat."""
        self.state.mkdir(parents=True)
        self.state.joinpath("state.json").write_text(
            json.dumps({"heartbeat": "2020-01-01T00:00:00Z", "cycle": 3}),
            encoding="utf-8",
        )
        proc = self.start_script([], OQTHARA_MAX_CYCLES="0", OQTHARA_BACKOFF="30")
        try:
            self.assertTrue(self.wait_for_log("BOOT"), "supervisor never booted")
            beat = int((self.state / ".watchdog.heartbeat").read_text().strip())
            self.assertLess(abs(time.time() - beat), 120)
            (self.state / "STOP").write_text("", encoding="utf-8")
            _out, err = proc.communicate(timeout=60)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.communicate()
        self.assertEqual(proc.returncode, 0, err)
        self.assertIn("WARNING state.json did not advance", self.log_text())


if __name__ == "__main__":
    runner = unittest.TextTestRunner(verbosity=2, resultclass=Result)
    sys.exit(0 if runner.run(unittest.defaultTestLoader.loadTestsFromTestCase(WatchdogTest)) else 1)
