#!/usr/bin/env python3
"""Regression tests for scripts/probe-reserved-positions.py.

The probe exists to make ADR-0061's numbers reproducible instead of transcribed. Two things can
go wrong with a harness like that, and both are tested here:

  * it finds an acceptance that is not there, or misses one that is -- a probe whose templates are
    malformed reports a refusal for the wrong reason, which is how an earlier draft listed a
    comprehension position as a reserved-word site when the list rule refuses it first;
  * the exit code stops distinguishing "clean" from "a hole was found", so `--check` cannot gate.
"""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
PROBE = ROOT / "scripts" / "probe-reserved-positions.py"


def load_probe():
    spec = importlib.util.spec_from_file_location("probe_reserved_positions", PROBE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProbePositionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe = load_probe()

    def test_every_template_references_the_word_slot(self):
        # A position whose template omits NAME does not probe the word at all.
        missing = [label for label, template in self.probe.POSITIONS if "NAME" not in template]
        self.assertEqual([], missing, "these positions never substitute the word")

    def test_position_labels_are_unique(self):
        labels = [label for label, _ in self.probe.POSITIONS]
        self.assertEqual(len(labels), len(set(labels)), "duplicate position labels")

    def test_the_comprehension_positions_are_present_and_documented(self):
        # They are kept deliberately, as a record that the list rule refuses them first.
        labels = {label for label, _ in self.probe.POSITIONS}
        self.assertIn("comprehension-element", labels)
        self.assertIn("comprehension-variable", labels)

    def test_a_word_that_is_not_reserved_is_accepted_so_the_probe_can_fail(self):
        # The discriminating property: the probe must be able to report a hole. `printable` is an
        # ordinary identifier, so binding it must succeed. If this ever fails, the probe reports
        # "nothing accepted" for every input and can no longer tell a clean run from a missed one.
        if not self.probe.JAR.exists():
            self.skipTest(f"missing {self.probe.JAR}; run ./gradlew :oq-cli:standaloneJar")
        exit_code, _ = self.probe.run_probe("printable = 1\n")
        self.assertEqual(0, exit_code, "an ordinary identifier must still be bindable")


if __name__ == "__main__":
    unittest.main()
