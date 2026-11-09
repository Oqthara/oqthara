#!/usr/bin/env python3
"""Regression tests for the conformance verifier's pass/fail logic.

These tests never build a jar and never run a program. They pin the decision rules that let
`verify-conformance.py` pass on a healthy corpus and fail loudly when the corpus or the
bytecode backend regresses, so the gate cannot rot into a step that always succeeds.
"""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import importlib.util

spec = importlib.util.spec_from_file_location(
    "verify_conformance",
    pathlib.Path(__file__).resolve().parent.parent / "verify-conformance.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def observe(executable=None, compiled=None):
    return {
        "executable": verify.BASELINE["executable"] if executable is None else executable,
        "compiled": verify.BASELINE["compiled"] if compiled is None else compiled,
    }


class EvaluateTests(unittest.TestCase):
    def test_healthy_corpus_passes(self):
        self.assertEqual([], verify.evaluate(observe(), []))

    def test_compiled_regression_fails(self):
        problems = verify.evaluate(observe(compiled=verify.BASELINE["compiled"] - 1), [])
        self.assertEqual(1, len(problems))
        self.assertIn("compiled conformance regressed", problems[0])

    def test_executable_count_drift_fails_in_both_directions(self):
        for drift in (-1, 1):
            with self.subTest(drift=drift):
                problems = verify.evaluate(
                    observe(executable=verify.BASELINE["executable"] + drift), [])
                self.assertEqual(1, len(problems))
                self.assertIn("executable corpus changed", problems[0])

    def test_divergence_fails_even_when_counts_match(self):
        problems = verify.evaluate(observe(), ["a.oq: stdout differs"])
        self.assertEqual(1, len(problems))
        self.assertIn("divergence", problems[0])

    def test_every_problem_is_reported_not_just_the_first(self):
        problems = verify.evaluate(
            observe(executable=1, compiled=0), ["x: differs"])
        self.assertEqual(3, len(problems))

    def test_compiled_above_baseline_is_allowed(self):
        # Newly compiled programs are an improvement; the executable pin is what catches
        # corpus changes, so the compiled number is a floor rather than an equality.
        self.assertEqual([], verify.evaluate(observe(compiled=verify.BASELINE["compiled"] + 5), []))


class MeasureCorpusTests(unittest.TestCase):
    def test_err_sibling_is_classified_as_non_executable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "runs.oq").write_text("print 1\n")
            (root / "fails.oq").write_text("boom\n")
            (root / "fails.err").write_text("some error\n")

            programs, executable = verify.measure_corpus(root)

            self.assertEqual(["fails.oq", "runs.oq"], [p.name for p in programs])
            self.assertEqual(["runs.oq"], [p.name for p in executable])

    def test_empty_corpus_yields_no_programs(self):
        with tempfile.TemporaryDirectory() as tmp:
            programs, executable = verify.measure_corpus(pathlib.Path(tmp))
            self.assertEqual([], programs)
            self.assertEqual([], executable)


if __name__ == "__main__":
    unittest.main()
