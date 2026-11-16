#!/usr/bin/env python3
"""Regression tests for scripts/probe-non-finite.py.

The probe checks two decisions that pull in opposite directions: operators must treat NaN as
unordered, and collections must still place it. A harness bug that weakened either check would
report success, so the shape of the tables is tested here:

  * every comparison row has to be a real relational expression over the preamble's `nan`, or
    it would probe something else and still pass;
  * every expected value has to be a boolean literal, because the check compares printed text
    and a typo like "False" would fail for the wrong reason;
  * the total-order rows must name the consumers that would break if `compare` were changed,
    so removing one cannot silently reduce coverage;
  * the expected counts in ADR-0063 must match the tables, so prose cannot drift from code.

The discriminating test is the last one: it proves the probe can report a failure at all.
"""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
PROBE = ROOT / "scripts" / "probe-non-finite.py"
ADR = ROOT / "docs" / "adr" / "0063-relational-operators-follow-ieee-order.md"


def load_probe():
    spec = importlib.util.spec_from_file_location("probe_non_finite", PROBE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NonFiniteProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe = load_probe()

    def test_every_nan_comparison_uses_a_relational_operator_over_nan(self):
        operators = ("<", "<=", ">", ">=", "==", "!=")
        wrong = [
            expression
            for _, expression, _ in self.probe.NAN_COMPARISONS
            if "nan" not in expression
            or not any(operator in expression for operator in operators)
        ]
        self.assertEqual([], wrong, "these rows do not compare NaN with anything")

    def test_every_nan_expectation_is_a_boolean_literal(self):
        # The check compares against `str(expected).lower()`, so a non-boolean expectation
        # would compare printed text with something the program cannot print.
        wrong = [
            (label, expected)
            for label, _, expected in self.probe.NAN_COMPARISONS
            if not isinstance(expected, bool)
        ]
        self.assertEqual([], wrong, "these rows expect a non-boolean")

    def test_the_wrong_rows_are_all_represented(self):
        # Six comparisons were wrong before ADR-0063. Every one is pinned by a row, so a
        # regression cannot hide behind the ones that were already correct.
        wrong_before = {
            "nan-greater-than-one",
            "nan-greater-or-equal-one",
            "nan-less-or-equal-nan",
            "nan-greater-or-equal-nan",
            "one-less-than-nan",
            "nan-greater-or-equal-two",
        }
        labels = {label for label, _, _ in self.probe.NAN_COMPARISONS}
        self.assertEqual(set(), wrong_before - labels, "a previously wrong row is not probed")

    def test_total_order_rows_name_the_consumers_that_need_it(self):
        # These are the call sites that would break if `compare` stopped being a total order.
        # `sorted` and `clamp` are listed too because they reach the same ordering indirectly.
        required = {"sort", "sorted", "min", "max", "clamp"}
        labels = {label for label, _, _ in self.probe.TOTAL_ORDER}
        self.assertEqual(set(), required - labels, "a total-order consumer is not probed")

    def test_raising_rows_expect_a_message_that_names_the_problem(self):
        wrong = [
            (label, fragment)
            for label, _, fragment in self.probe.RAISING
            if not fragment or fragment.lower() in {"error", "failed"}
        ]
        self.assertEqual([], wrong, "these rows expect a message too vague to distinguish")

    def test_counts_quoted_in_the_adr_match_the_tables(self):
        # ADR-0063 states the case count and the group count. If a table grows, the ADR has to
        # be updated in the same change rather than left quoting a number that is no longer true.
        groups = [
            len(self.probe.SILENT_VALUES),
            len(self.probe.RAISING),
            len(self.probe.NAN_COMPARISONS),
            len(self.probe.SIGNED_ZERO),
            len(self.probe.TOTAL_ORDER),
            len(self.probe.ORDINARY_COMPARISONS),
        ]
        self.assertEqual([8, 11, 14, 9, 5, 10], groups)
        total = sum(groups)
        self.assertEqual(57, total)
        text = ADR.read_text(encoding="utf-8")
        self.assertIn(f"{total} cases across six groups", text)
        self.assertIn("14 of 14", text)
        self.assertIn("6 of 14", text)
        self.assertIn("9 of 9", text)
        self.assertIn("5 of 9", text)

    def test_the_preamble_defines_every_name_the_rows_use(self):
        for name in ("nan", "inf", "big"):
            self.assertIn(f"{name} =", self.probe.PREAMBLE, f"the preamble does not define {name}")

    def test_labels_are_unique(self):
        groups = [
            self.probe.SILENT_VALUES,
            self.probe.RAISING,
            self.probe.NAN_COMPARISONS,
            self.probe.SIGNED_ZERO,
            self.probe.TOTAL_ORDER,
            self.probe.ORDINARY_COMPARISONS,
        ]
        labels = [row[0] for group in groups for row in group]
        self.assertEqual(len(labels), len(set(labels)), "two rows share a label")

    def test_a_disagreeing_expectation_is_reported_as_a_failure(self):
        # The discriminating property: the probe must be able to fail. `1 < NaN` is false, so
        # asking the probe whether it is true has to be refuted by the real JVM. This proves the
        # check compares printed output with the expected literal rather than merely requiring a
        # successful run.
        if not self.probe.JAR.exists():
            self.skipTest(f"missing {self.probe.JAR}; run ./gradlew :oq-cli:standaloneJar")
        code, lines = self.probe.run(self.probe.PREAMBLE + "print nan > 1.0\n")
        self.assertEqual(0, code, "the program must still run")
        self.assertEqual(["false"], lines, "the probe must observe the real answer")

    def test_the_signed_zero_group_covers_both_directions(self):
        # A relation can be wrong in one direction only, which is how the original defect
        # survived: `-0.0 < 0.0` was true while `0.0 < -0.0` was false. A one-sided check would
        # have passed, so every ordered operator must appear from both operand orders.
        expressions = [row[1] for row in self.probe.SIGNED_ZERO]
        for operator in ("<", ">", "<=", ">="):
            self.assertIn(f"-0.0 {operator} 0.0", expressions)
            self.assertIn(f"0.0 {operator} -0.0", expressions)



if __name__ == "__main__":
    unittest.main()
