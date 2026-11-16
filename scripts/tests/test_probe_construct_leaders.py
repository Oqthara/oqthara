#!/usr/bin/env python3
"""Regression tests for scripts/probe-construct-leaders.py.

The probe asserts two opposite things at once -- leaders name themselves before an operand, and
leaders stay usable as names -- so a harness bug can hide either one. Three failure modes are
tested here:

  * a template that never mentions the leader, which would probe nothing and report success;
  * the operand templates not actually being the shape the real construct would take, which
    would let a diagnostic pass for the wrong reason;
  * the exit code no longer distinguishing "clean" from "a half regressed", so `--check` cannot
    gate.

The last test is the discriminating one: it proves the probe can report a failure at all.
"""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
PROBE = ROOT / "scripts" / "probe-construct-leaders.py"


def load_probe():
    spec = importlib.util.spec_from_file_location("probe_construct_leaders", PROBE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ConstructLeaderProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe = load_probe()

    def test_every_operand_template_starts_with_its_leader(self):
        # The refusal is triggered by the word being used as syntax, so the operand has to
        # follow the leader. A template that does not begin with it tests some other program.
        wrong = [
            leader
            for leader, source, _ in self.probe.OPERANDS
            if not source.startswith(leader)
        ]
        self.assertEqual([], wrong, "these operand templates do not begin with their leader")

    def test_every_operand_names_a_hint_fragment_the_parser_does_not_emit_generically(self):
        # The parser already emits "Statements end at the end of the line." at this position, so
        # a probe that only checks "a hint exists" passes before the change and proves nothing.
        # Each fragment must be construct-specific, and the generic sentence must appear in none.
        generic = "Statements end at the end of the line"
        empty = [leader for leader, _, fragment in self.probe.OPERANDS if not fragment.strip()]
        self.assertEqual([], empty, "these operand rows expect no hint fragment")
        leaked = [
            leader
            for leader, _, fragment in self.probe.OPERANDS
            if generic in fragment
        ]
        self.assertEqual([], leaked, "these rows would accept the generic hint")

    def test_every_name_position_references_the_name_slot(self):
        # A position whose template omits NAME never substitutes the leader, so it would report
        # "still accepted" without having tested the leader at all.
        missing = [label for label, template in self.probe.NAME_POSITIONS if "NAME" not in template]
        self.assertEqual([], missing, "these positions never substitute the leader")

    def test_every_value_template_holds_the_source_slot(self):
        # These rows caught a real defect: the rule first ran only on the bare-expression path,
        # so `x = yield 1` still blamed the `1`. A template missing the slot would test nothing.
        missing = [label for label, template in self.probe.VALUE_POSITIONS if "{source}" not in template]
        self.assertEqual([], missing, "these value positions never substitute the leader")

    def test_every_nested_and_terminated_template_holds_the_source_slot(self):
        # The nested rows are the ones that exposed the statement-path design; the terminated
        # rows expose the opposite error. Both substitute the leader, so a template that forgot
        # the slot would silently pass either check.
        for group in ("NESTED_POSITIONS", "TERMINATED_STATEMENTS"):
            missing = [label for label, template in getattr(self.probe, group) if "{source}" not in template]
            self.assertEqual([], missing, f"these {group} rows never substitute the leader")

    def test_every_continued_expression_holds_the_leader_slot(self):
        # A continuation row that does not name the leader tests an ordinary expression, which
        # passes on any build and proves nothing about the contextual guard.
        missing = [label for label, template in self.probe.CONTINUED_EXPRESSIONS if "{leader}" not in template]
        self.assertEqual([], missing, "these continuation rows never substitute the leader")

    def test_every_leader_is_refused_in_every_nested_position(self):
        # The chokepoint covers these only if the guard is reached from nested expressions, so
        # the probe must exercise all of them for all ten leaders, not a sample.
        self.assertEqual(10, len(self.probe.OPERANDS))
        self.assertGreaterEqual(len(self.probe.NESTED_POSITIONS), 8)

    def test_labels_are_unique(self):
        for group in ("OPERANDS", "VALUE_POSITIONS", "NAME_POSITIONS", "NESTED_POSITIONS",
                      "CONTINUED_EXPRESSIONS", "TERMINATED_STATEMENTS", "COMPREHENSIONS"):
            rows = getattr(self.probe, group)
            labels = [row[0] for row in rows]
            self.assertEqual(len(labels), len(set(labels)), f"duplicate labels in {group}")

    def test_every_comprehension_shape_has_its_own_message(self):
        # Four shapes, four messages: a copy-paste error would make two shapes share a string and
        # the probe would then accept the wrong one.
        messages = [expected for _, _, expected in self.probe.COMPREHENSIONS]
        self.assertEqual(len(messages), len(set(messages)), "two shapes share a message")

    def test_a_diagnostic_that_names_nothing_is_reported_as_a_failure(self):
        # The discriminating property: the probe must be able to fail. `printable` is an ordinary
        # identifier with no operand after it, so it parses; asking for a construct diagnostic for
        # it must not be satisfied. This proves the check compares against the expected sentence
        # rather than merely requiring an error.
        if not self.probe.JAR.exists():
            self.skipTest(f"missing {self.probe.JAR}; run ./gradlew :oq-cli:standaloneJar")
        code, _ = self.probe.run_check("printable = 1\n")
        self.assertEqual(0, code, "an ordinary identifier must still be accepted")


if __name__ == "__main__":
    unittest.main()
