#!/usr/bin/env python3
"""Regression tests for the conformance verifier's pass/fail logic.

These tests never build a jar and never run a program. They pin the decision rules that let
`verify-conformance.py` pass on a healthy corpus and fail loudly when the corpus or the
bytecode backend regresses, so the gate cannot rot into a step that always succeeds.
"""
import pathlib
import sys
import tempfile
import threading
import unittest
import unittest.mock

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

    def test_tallies_must_fit_inside_the_executable_set(self):
        # compiled and fallback are disjoint halves of the executable corpus. Before this
        # was enforced, four .err cases were counted as fallback and the summary reported
        # 203 + 13 = 216 over an executable set of 212.
        problems = verify.evaluate(
            observe(executable=212, compiled=203) | {"fallback": 13}, [])
        self.assertEqual(1, len(problems))
        self.assertIn("exceed", problems[0])

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


class IsolationTests(unittest.TestCase):
    """Pins that cases cannot observe each other, which is what makes running them
    concurrently safe. A regression here would let a stray sibling file or a shared
    working directory silently change another program's result."""

    def test_each_case_gets_its_own_working_and_build_directories(self):
        seen = []
        lock = threading.Lock()

        def fake_check(program, cases, jar, case_root):
            with lock:
                seen.append(case_root)
            return 0, 0, []

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            programs = [root / f"{n}.oq" for n in ("a", "b", "c", "d")]
            for program in programs:
                program.write_text("print 1\n")

            with unittest.mock.patch.object(verify, "check_program", fake_check):
                verify.collect(programs, root, root / "oq.jar", root, jobs=4)

            self.assertEqual(4, len(set(seen)))
            self.assertEqual({"case-a", "case-b", "case-c", "case-d"},
                             {p.name for p in seen})

    def test_worker_raising_is_reported_as_a_divergence(self):
        def exploding(program, cases, jar, case_root):
            raise RuntimeError("boom")

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            program = root / "a.oq"
            program.write_text("print 1\n")

            with unittest.mock.patch.object(verify, "check_program", exploding):
                results = verify.collect([program], root, root / "oq.jar", root, jobs=1)

        self.assertIn("a.oq", results)
        _, _, divergences = results["a.oq"]
        self.assertEqual(1, len(divergences))
        self.assertIn("verifier raised", divergences[0])
        self.assertIn("boom", divergences[0])


class DeterminismTests(unittest.TestCase):
    """Pins that the verdict does not depend on scheduling. These call the real aggregate()
    so a change that folds results in completion order fails here."""

    @staticmethod
    def _programs(names):
        return [pathlib.Path(n) for n in names]

    def test_aggregation_follows_corpus_order_not_completion_order(self):
        names = [f"{i:03d}-case.oq" for i in range(6)]
        completed = {name: (1, 0, [f"{name}: synthetic divergence"]) for name in names}

        def as_result(order):
            return {name: completed[name] for name in order}

        forward = verify.aggregate(self._programs(names), as_result(names))
        backward = verify.aggregate(self._programs(names), as_result(list(reversed(names))))
        interleaved = verify.aggregate(
            self._programs(names),
            as_result([names[3], names[0], names[5], names[1], names[4], names[2]]))

        self.assertEqual(forward, backward)
        self.assertEqual(forward, interleaved)
        self.assertEqual(6, forward[0])
        self.assertEqual(names, [d.split(":")[0] for d in forward[2]])

    def test_missing_result_is_a_divergence_not_a_silent_pass(self):
        programs = self._programs(["a.oq", "b.oq"])
        compiled, fallback, divergences = verify.aggregate(programs, {"a.oq": (1, 0, [])})

        self.assertEqual(1, compiled)
        self.assertEqual(1, len(divergences))
        self.assertIn("b.oq", divergences[0])
        self.assertIn("no result recorded", divergences[0])

    def test_totals_sum_every_program(self):
        programs = self._programs(["a.oq", "b.oq", "c.oq"])
        results = {"a.oq": (1, 0, []), "b.oq": (0, 1, []), "c.oq": (0, 1, [])}

        compiled, fallback, divergences = verify.aggregate(programs, results)

        self.assertEqual((1, 2, []), (compiled, fallback, divergences))


class DefaultJobsTests(unittest.TestCase):
    def test_default_is_bounded(self):
        # Concurrency must stay modest: each case runs up to three JVMs in sequence.
        self.assertGreaterEqual(verify.default_jobs(), 1)
        self.assertLessEqual(verify.default_jobs(), 4)


if __name__ == "__main__":
    unittest.main()
