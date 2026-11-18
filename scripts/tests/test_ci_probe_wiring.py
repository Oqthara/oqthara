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

    def with_fallback(self, name, reason):
        """Run `body` with one synthetic `FALLBACK` entry installed, then restore the map.

        `FALLBACK` is empty now that the last gap has closed, so a test that loops over it
        asserts nothing and reports green. Each of the tests below therefore needs an entry to
        exist, and this is how it gets one. Deleting the restore would leak between tests.
        """
        self.runner.FALLBACK[name] = reason
        self.addCleanup(self.runner.FALLBACK.pop, name)
        return reason

    def test_a_recorded_fallback_passes_verbatim(self):
        reason = "codepath: interpreter launcher (recorded verbatim)"
        name = self.runner.PROBES[0][0]
        self.with_fallback(name, reason)
        self.assertIsNone(self.check(name, reason.split("codepath: ", 1)[1]))

    def test_a_recorded_fallback_that_closed_fails(self):
        # Fixing the gap must fail too, so the entry cannot outlive it.
        name = self.runner.PROBES[0][0]
        self.with_fallback(name, "codepath: interpreter launcher (recorded)")
        refused = self.check(name, "compiled bytecode")
        self.assertIsNotNone(refused, name)
        self.assertIn("recorded fallback is gone", refused)

    def test_a_changed_fallback_reason_fails_as_a_changed_reason(self):
        # The entry is an exact line, not a prefix: a different reason is a different state.
        # The message must say the reason changed, not that the gap closed -- only compiling
        # again closes the gap, and conflating the two sends a reader to delete a live entry.
        name = self.runner.PROBES[0][0]
        self.with_fallback(name, "codepath: interpreter launcher (the recorded reason)")
        refused = self.check(name, "interpreter launcher (something else entirely)")
        self.assertIsNotNone(refused, name)
        self.assertIn("recorded fallback reason changed", refused)
        self.assertNotIn("gap it recorded has closed", refused)

    def test_the_fallback_tests_are_not_vacuous(self):
        # A loop over an empty FALLBACK passes without asserting anything, which is how three
        # of these tests silently stopped testing. This pins that each one runs a body.
        self.assertEqual(self.runner.FALLBACK, {}, "expected no live fallback; see run.py")
        name = self.runner.PROBES[0][0]
        self.with_fallback(name, "codepath: interpreter launcher (guard)")
        self.assertEqual(len(self.runner.FALLBACK), 1)

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


class TheGateIsWiredNotJustDefined(unittest.TestCase):
    """A6.3: calling `main` must run the codepath check.

    The tests above exercise `codepath_failure` in isolation, and that is not enough: a
    correct rule that `main` never calls is indistinguishable from no rule at all, and every
    one of those tests would still pass with the gate deleted. These drive the real `main`
    with the three subprocess calls stubbed, so deleting the check from the runner fails here.
    """

    def setUp(self):
        spec = importlib.util.spec_from_file_location("probe_runner_main", RUNNER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.runner = module
        self.jar = pathlib.Path(tempfile.mkdtemp()) / "q.jar"
        self.jar.write_bytes(b"")

    def drive(self, codepath_for):
        """Run `main --both` with the builds simulated; `codepath_for` returns a codepath line.

        Every probe is given output that satisfies its own expectation, whatever shape that
        expectation has, so the only thing that can fail the run is the codepath check.
        """
        by_name = {name: (expected, defect) for name, expected, defect in self.runner.PROBES}

        def output_for(name):
            expected, defect = by_name[name]
            if defect is not None:
                # A recorded defect expects its recorded (wrong) lines verbatim.
                return [line for line in defect[1]], 0
            if isinstance(expected, str):
                # A refusal probe: the diagnostic must be present and the exit non-zero.
                return [expected], 1
            return list(expected), 0

        def fake_run(jar, program, extra=None):
            lines, code = output_for(program.stem)
            return "\n".join(lines) + "\n", code

        def fake_build(jar, program, extra=None):
            target = jar.parent / f"{program.stem}.jar"
            target.write_bytes(b"")
            output = (f"compiled {program.name}\nbackend=classfile\n"
                      f"{codepath_for(program.stem)}\n")
            return target, output, 0

        def fake_compiled(target, extra=None):
            lines, code = output_for(target.stem)
            return "\n".join(lines) + "\n", code

        self.runner.run = fake_run
        self.runner.compile_program = fake_build
        self.runner.run_compiled = fake_compiled
        argv, self_argv = self.runner.sys.argv, sys.argv
        self.runner.sys.argv = ["run.py", str(self.jar), "--both"]
        sys.argv = self.runner.sys.argv
        try:
            import io
            from contextlib import redirect_stdout
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = self.runner.main()
            return code, buffer.getvalue()
        finally:
            self.runner.sys.argv = argv
            sys.argv = self_argv

    def declare(self, name):
        """The codepath this probe should report: compiled, unless it is a declared fallback."""
        return self.runner.FALLBACK.get(name, "codepath: compiled bytecode")

    def synthetically_declare(self, reason=None):
        """Temporarily declare a real probe as a fallback, returning its name.

        `FALLBACK` is empty by design now that the last gap has closed, but both directions of
        the gate still need covering. Injecting a synthetic entry exercises the same code path
        the real entry used to, without depending on a gap existing.
        """
        name = self.runner.PROBES[0][0]
        reason = reason or "codepath: interpreter launcher (synthetic, for the gate test)"
        original = dict(self.runner.FALLBACK)
        self.runner.FALLBACK[name] = reason
        self.addCleanup(lambda: (self.runner.FALLBACK.clear(),
                                 self.runner.FALLBACK.update(original)))
        return name

    def test_a_compiled_run_passes_and_says_what_it_compared(self):
        code, out = self.drive(self.declare)
        self.assertEqual(code, 0, out)
        compiled = len(self.runner.PROBES) - len(self.runner.FALLBACK)
        self.assertIn(
            f"ok: {compiled} probes compared compiled bytecode against the interpreter", out
        )

    def test_a_declared_fallback_that_now_compiles_fails_the_run(self):
        # Through `main`, not just through the decision function: closing the gap has to fail
        # the build so the entry is deleted rather than left describing a fixed bug.
        closed = self.synthetically_declare()
        code, out = self.drive(
            lambda name: ("codepath: compiled bytecode" if name == closed else self.declare(name))
        )
        self.assertEqual(code, 1, out)
        self.assertIn("recorded fallback is gone", out)

    def test_main_fails_when_a_build_silently_falls_back(self):
        # The exact regression: the build exits 0, writes a jar, and the jar runs -- but the
        # jar is the interpreter launcher. Only the codepath check can see that.
        target = self.runner.PROBES[0][0]

        def codepath_for(name):
            return ("codepath: interpreter launcher" if name == target
                    else "codepath: compiled bytecode")

        code, out = self.drive(codepath_for)
        self.assertEqual(code, 1, out)
        self.assertIn(target, out)
        self.assertIn("expected 'codepath: compiled bytecode'", out)

    def test_the_fallback_note_is_printed_when_a_probe_does_not_compile(self):
        # Declaring the fallback keeps the run green, but the summary must not then claim the
        # probe's output was compared across two backends.
        self.synthetically_declare()
        code, out = self.drive(self.declare)
        self.assertEqual(code, 0, out)
        self.assertIn("not across two backends", out)


if __name__ == "__main__":
    unittest.main()
