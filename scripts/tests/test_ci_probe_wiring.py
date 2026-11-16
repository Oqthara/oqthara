#!/usr/bin/env python3
"""Regression tests for the A6.4 probe step in .github/workflows/ci.yml.

A6.4 says the probe harness runs in CI, so a regression in any inventory row fails the
build. The harnesses were fully written long before this step existed and passed when a
human remembered to run them, which is exactly the failure mode: a check nobody invokes
cannot fail a build, and its continued passing proves nothing.

These tests pin the wiring rather than the harness output, because the output is pinned by
`run.py`'s own expectations and by the three `probe-*.py` self-tests. The three failure
modes tested here are the ones that would leave the step green while probing nothing:

  * the step is deleted, or its `if:` narrows so it never runs;
  * the step runs before the jar it needs exists, so it fails for a reason unrelated to
    the probes, or silently reads a stale jar on a developer machine;
  * a harness path is renamed and the step keeps naming the old one.

The second test is the discriminating one for ordering: `run.py` takes the jar as an
argument and exits 2 when the path is absent, so a step that runs too early cannot pass.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CI = ROOT / ".github" / "workflows" / "ci.yml"

STEP_NAME = "Decision probes (A6.4)"
HARNESSES = [
    "docs/governance/alpha-5/java-bridge-probes/run.py",
    "scripts/probe-construct-leaders.py",
    "scripts/probe-non-finite.py",
    "scripts/probe-reserved-positions.py",
]


def steps():
    """Return the CI step blocks as (name, body) pairs, in file order.

    Read as text rather than through a YAML parser so the tests do not depend on PyYAML
    being installed on the machine running them.
    """
    text = CI.read_text(encoding="utf-8")
    found = []
    for match in re.finditer(r"^      - name: (.+)$", text, re.M):
        end = text.find("\n      - name:", match.end())
        body = text[match.end(): end if end != -1 else len(text)]
        found.append((match.group(1).strip(), body))
    return found


def step_body(name):
    for step_name, body in steps():
        if step_name == name:
            return body
    raise AssertionError(f"no CI step named {name!r}; steps are {[n for n, _ in steps()]}")


class ProbeStepExists(unittest.TestCase):
    def test_the_probe_step_is_present(self):
        self.assertGreaterEqual(len(steps()), 1)
        self.assertIn(STEP_NAME, [name for name, _ in steps()])

    def test_every_named_harness_exists_on_disk(self):
        body = step_body(STEP_NAME)
        for relative in HARNESSES:
            self.assertIn(relative, body, f"{relative} is not invoked by the {STEP_NAME} step")
            self.assertTrue((ROOT / relative).is_file(), f"{relative} is invoked but absent")

    def test_the_step_is_gated_the_same_way_as_the_conformance_step(self):
        # A probe step that ran on every leg would still be green, but it would triple the
        # runtime to prove nothing new: the jar is the same on all four.
        gates = [
            line.strip()
            for line in step_body(STEP_NAME).splitlines()
            if line.strip().startswith("if:")
        ]
        self.assertEqual(gates, ["if: matrix.os == 'ubuntu-latest' && matrix.java == '21'"])


class ProbeStepOrdering(unittest.TestCase):
    def test_the_jar_is_built_before_the_probes_run(self):
        names = [name for name, _ in steps()]
        conformance = next(i for i, n in enumerate(names) if n.startswith("Independent backend conformance"))
        probes = next(i for i, n in enumerate(names) if n == STEP_NAME)
        self.assertLess(conformance, probes, "the probes would read a jar nothing has built yet")

    def test_the_step_does_not_build_a_second_jar(self):
        # `standaloneJar` is already run one step earlier; repeating it here would make the
        # gate slower without changing what it measures.
        self.assertNotIn("gradlew", step_body(STEP_NAME))


class ProbeRunnerContract(unittest.TestCase):
    def test_the_runner_reads_the_jar_path_from_its_argument(self):
        # If run.py grew a hard-coded jar path it would still pass here and fail in CI, where
        # the build directory differs. It must take the path it is given.
        runner = (ROOT / HARNESSES[0]).read_text(encoding="utf-8")
        self.assertIn("sys.argv", runner)
        self.assertIn("no jar at", runner)

    def test_a_missing_jar_is_misuse_not_a_finding(self):
        # Exit 2 distinguishes "I could not run" from "a probe disagreed", so a CI log says
        # which happened. Collapsing them to 1 would make a broken invocation look like a
        # regression in the language.
        runner = (ROOT / HARNESSES[0]).read_text(encoding="utf-8")
        self.assertIn("return 2", runner)


if __name__ == "__main__":
    unittest.main()
