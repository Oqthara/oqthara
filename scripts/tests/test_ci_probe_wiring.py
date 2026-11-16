#!/usr/bin/env python3
"""Regression tests for the A6.4 probe step in .github/workflows/ci.yml.

A6.4 says the probe harness runs in CI, so a regression in a probed inventory row fails the
build. The harnesses were fully written long before this step existed and passed when a human
remembered to run them, which is exactly the failure mode: a check nobody invokes cannot fail a
build, and its continued passing proves nothing.

These tests pin the wiring rather than the harness output, because the output is pinned by
`run.py`'s own expectations and by the three `probe-*.py` self-tests. The failure modes tested
here are the ones that leave the step green while probing nothing:

  * the step is deleted, moved to another job, or its `if:` narrows so it never runs;
  * a command in the step is commented out, so the step exits 0 having run fewer harnesses;
  * the step runs before the jar it needs exists;
  * `run.py` stops honouring the jar path it is given, or reports a missing jar as a probe
    failure rather than as misuse.

The YAML is read with PyYAML rather than by scanning for `- name:` lines, because a regex
cannot tell which job a step belongs to -- and the `installers` job has no `matrix.java`, so a
step moved there would be skipped while still looking present.
"""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CI = ROOT / ".github" / "workflows" / "ci.yml"
RUNNER = ROOT / "docs" / "governance" / "alpha-5" / "java-bridge-probes" / "run.py"

STEP_NAME = "Decision probes (A6.4)"
CONFORMANCE_STEP = "Independent backend conformance (interpreter vs compiled jar)"
HARNESSES = [
    "docs/governance/alpha-5/java-bridge-probes/run.py",
    "scripts/probe-construct-leaders.py",
    "scripts/probe-non-finite.py",
    "scripts/probe-reserved-positions.py",
]
# The leg the jar is built on. Every probe step must live on this same leg.
LEG = {"os": "ubuntu-latest", "java": "21"}


def workflow():
    return yaml.safe_load(CI.read_text(encoding="utf-8"))


def job_steps(job):
    return workflow()["jobs"][job]["steps"]


def find_step(name):
    """Return (job_name, step) for the one step with this name, or raise."""
    matches = [
        (job, step)
        for job in workflow()["jobs"]
        for step in job_steps(job)
        if step.get("name") == name
    ]
    assert len(matches) == 1, f"expected exactly one {name!r} step, found {len(matches)}"
    return matches[0]


def live_commands(run_block):
    """Return the commands a `run:` block actually executes, with continuations joined.

    A commented-out command is not live, which is the point: prefixing a line with `#` must
    show up as one fewer command rather than as an unchanged step.
    """
    joined, buffer = [], ""
    for raw in run_block.splitlines():
        line = raw.strip()
        if line.startswith("#") or not line:
            continue
        if line.endswith("\\"):
            buffer += line[:-1].rstrip() + " "
            continue
        joined.append((buffer + line).strip())
        buffer = ""
    if buffer.strip():
        joined.append(buffer.strip())
    return joined


class ProbeStepIsWired(unittest.TestCase):
    def test_the_step_exists_in_the_test_job(self):
        job, step = find_step(STEP_NAME)
        self.assertEqual(job, "test", "the probes must run in the job that builds the jar")
        self.assertIn("run", step)

    def test_the_step_runs_on_the_leg_that_builds_the_jar(self):
        _, step = find_step(STEP_NAME)
        for key, value in LEG.items():
            self.assertIn(f"matrix.{key} == '{value}'", step["if"])

    def test_the_step_is_gated_exactly_like_the_conformance_step(self):
        # Both steps need the same jar, so a divergence in the gate means one of them now runs
        # somewhere the other does not -- usually because one was edited and not the other.
        _, probes = find_step(STEP_NAME)
        _, conformance = find_step(CONFORMANCE_STEP)
        self.assertEqual(probes["if"], conformance["if"])

    def test_every_harness_is_invoked_by_a_live_command(self):
        _, step = find_step(STEP_NAME)
        live = "\n".join(live_commands(step["run"]))
        for relative in HARNESSES:
            self.assertIn(relative, live, f"{relative} is not run by any live command")
            self.assertTrue((ROOT / relative).is_file(), f"{relative} is invoked but absent")

    def test_no_harness_command_is_commented_out(self):
        _, step = find_step(STEP_NAME)
        commented = [
            line.strip()
            for line in step["run"].splitlines()
            if line.strip().startswith("#") and "probe" in line.lower()
        ]
        self.assertEqual(commented, [], "a harness line is commented out but still looks wired")

    def test_the_step_runs_all_four_harnesses_and_nothing_else(self):
        _, step = find_step(STEP_NAME)
        invocations = [c for c in live_commands(step["run"]) if "python3" in c]
        self.assertEqual(len(invocations), len(HARNESSES), invocations)


class ProbeStepOrdering(unittest.TestCase):
    def test_the_jar_is_built_before_the_probes_run(self):
        _, probes = find_step(STEP_NAME)
        job, conformance = find_step(CONFORMANCE_STEP)
        order = [s.get("name") for s in job_steps(job)]
        self.assertLess(order.index(conformance["name"]), order.index(probes["name"]))

    def test_the_step_does_not_build_a_second_jar(self):
        # `standaloneJar` is already run one step earlier; repeating it here would make the gate
        # slower without changing what it measures.
        _, step = find_step(STEP_NAME)
        self.assertNotIn("gradlew", step["run"])


class RunnerHonoursItsArgument(unittest.TestCase):
    """Behavioural tests: run the runner instead of reading its source."""

    def test_a_missing_jar_is_reported_at_the_path_it_was_given(self):
        # A hard-coded default would either run 18 probes or name the default path in its
        # message. Neither can produce the path used here, which exists nowhere.
        with tempfile.TemporaryDirectory() as scratch:
            jar = pathlib.Path(scratch) / "definitely-absent" / "oq.jar"
            done = subprocess.run(
                [sys.executable, str(RUNNER), str(jar)],
                capture_output=True, text=True, timeout=60, cwd=ROOT,
            )
        self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
        self.assertIn("no jar at", done.stdout)
        self.assertIn("definitely-absent", done.stdout, "the argument was not honoured")

    def test_no_arguments_is_misuse_rather_than_a_probe_failure(self):
        done = subprocess.run(
            [sys.executable, str(RUNNER)],
            capture_output=True, text=True, timeout=60, cwd=ROOT,
        )
        self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
        self.assertIn("Usage", done.stdout + done.stderr)


class CodepathIsCheckedNotAssumed(unittest.TestCase):
    """A6.3: `--both` must prove the jar was compiled, not merely that a jar exists.

    Before this check, `--both` compared output and nothing else. An interpreter launcher is
    still a jar and its output matches the interpreter byte for byte, because the interpreter
    produced it — so every probe could have regressed to the fallback and the run would have
    stayed green. That is the whole claim A6.3 makes, and it was untested.

    These tests call the decision function the runner uses, so they fail if the rule changes
    rather than if a copy of the rule here disagrees with it.
    """

    def setUp(self):
        spec = importlib.util.spec_from_file_location("probe_runner", RUNNER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.runner = module

    def check(self, name, codepath):
        build = f"compiled {name}.oq\nbackend=classfile\ncodepath: {codepath}\n"
        return self.runner.codepath_failure(name, build)

    def test_a_compiled_probe_passes(self):
        self.assertIsNone(self.check("d2_class_instance", "compiled bytecode"))

    def test_an_undeclared_fallback_fails(self):
        # The regression A6.3 exists to catch: the build falls back and nothing records it.
        refused = self.check("d2_class_instance", "interpreter launcher (classes are not compiled)")
        self.assertIsNotNone(refused)
        self.assertIn("expected 'codepath: compiled bytecode'", refused)

    def test_a_recorded_fallback_passes_verbatim(self):
        for name, line in self.runner.FALLBACK.items():
            self.assertIsNone(self.check(name, line.split("codepath: ", 1)[1]))

    def test_a_recorded_fallback_that_closed_fails(self):
        # Fixing the gap must fail too, so the entry cannot outlive it.
        for name in self.runner.FALLBACK:
            refused = self.check(name, "compiled bytecode")
            self.assertIsNotNone(refused, name)
            self.assertIn("recorded fallback is gone", refused)

    def test_a_changed_fallback_reason_fails(self):
        # The entry is an exact line, not a prefix: a different reason is a different state.
        for name in self.runner.FALLBACK:
            refused = self.check(name, "interpreter launcher (something else entirely)")
            self.assertIsNotNone(refused, name)

    def test_a_build_with_no_codepath_line_fails(self):
        # `oq build` could stop reporting the path. Silence must not read as "compiled".
        refused = self.runner.codepath_failure("d2_class_instance", "backend=classfile\n")
        self.assertIsNotNone(refused)
        self.assertIn("no codepath line", refused)

    def test_the_declared_compiled_line_matches_the_cli(self):
        # The runner's constant and the CLI's printed text must be the same string, or every
        # probe fails for a reason that has nothing to do with the backend.
        source = (ROOT / "oq-cli" / "src" / "main" / "java" / "dev" / "oqthara" / "cli"
                  / "Main.java")
        self.assertIn(self.runner.COMPILED, source.read_text(encoding="utf-8"))

    def test_every_fallback_entry_names_a_real_probe(self):
        names = {name for name, _, _ in self.runner.PROBES}
        self.assertEqual(set(self.runner.FALLBACK) - names, set())


if __name__ == "__main__":
    unittest.main()
