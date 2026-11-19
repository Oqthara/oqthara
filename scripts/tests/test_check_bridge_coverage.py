#!/usr/bin/env python3
"""Regression tests for scripts/check-bridge-coverage.py.

The Java Bridge inventory's Evidence column argues each classification with a
transcript of a call and its result. Before this gate, nothing tied a transcript
to a file: a probe could be deleted or renamed and the row would keep asserting
a result no test produced. Worse, C13.7 and C13.8 had been sitting in the table
with only four cells — their argument folded into the Class column — and no gate
noticed, because no gate read the table's shape.

Two ways for this gate to be useless, both tested here. It can miss drift, so
one class runs it against a small synthetic tree that is wrong in exactly one
way. It can over-fire, so another asserts the committed tree passes: a gate that
rejected everything would fail that rather than look thorough.

The synthetic tree is not a convenience. Every row in the real inventory is
classified `Complete` since Alpha 6 fixed the six defects, so rule 6 — a row
asserting absence cannot cite a passing probe — has nothing to bite on there and
would otherwise go untested until the day it mattered.
"""
import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_bridge_coverage", SCRIPTS / "check-bridge-coverage.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

REPO = SCRIPTS.parent
INVENTORY = REPO / "docs" / "governance" / "alpha-5-java-bridge-capability-inventory.md"

HARNESS = """
PROBES = [
    ("c1_pass", ["ok"], None),
    ("c1_refuse", "cannot do the thing", None),
    ("c2_pass", ["ok"], None),
]
"""

# One row per shape the gate must distinguish. Both directions reach a probe of
# their own, so the fixture is clean before a test breaks it.
CLEAN = """
## Direction 1 — Construction

| ID | Capability | Class | Evidence | `probe` |
|----|------------|-------|----------|---------|
| C1.1 | Construct | Complete | `ArrayList()` gives `[]` | `c1_pass` |
| C1.2 | Refused | Incorrect | `X()` gives `cannot do the thing` | `c1_refuse` |

## Direction 2 — Instances

| ID | Capability | Class | Evidence | `probe` |
|----|------------|-------|----------|---------|
| C2.1 | Instance member | Complete | `size` gives `0` | `c2_pass` |
"""


def problems(mutate, harness=None) -> list[str]:
    """The gate's problems for the clean fixture with `mutate` applied."""
    root = pathlib.Path(tempfile.mkdtemp())
    try:
        (root / checker.INVENTORY).parent.mkdir(parents=True, exist_ok=True)
        (root / checker.INVENTORY).write_text(mutate(CLEAN), encoding="utf-8")
        probes = root / checker.PROBE_DIR
        probes.mkdir(parents=True, exist_ok=True)
        (probes / "run.py").write_text(harness(HARNESS) if harness else HARNESS, encoding="utf-8")
        for name in ("c1_pass", "c1_refuse", "c2_pass"):
            (probes / f"{name}.oq").write_text("print 1\n", encoding="utf-8")
        return checker.problems_for(root)
    finally:
        shutil.rmtree(root)


def replaced(old: str, new: str):
    def mutate(text: str) -> str:
        assert old in text, f"the fixture {old!r} is no longer in the clean tree"
        return text.replace(old, new, 1)

    return mutate


class TheCommittedInventoryIsBacked(unittest.TestCase):
    def test_the_committed_tree_passes(self):
        self.assertEqual([], checker.problems_for(REPO))

    def test_it_reads_every_capability_row(self):
        rows = checker.parse_rows(INVENTORY.read_text(encoding="utf-8"))
        self.assertEqual(55, len(rows))
        self.assertTrue(all(row.probes for row in rows), "every row carries a marker")

    def test_every_registered_probe_is_claimed(self):
        harness = checker.load_harness(REPO)
        rows = checker.parse_rows(INVENTORY.read_text(encoding="utf-8"))
        cited = {probe for row in rows for probe in row.probes}
        self.assertEqual({name for name, _, _ in harness.PROBES}, cited)

    def test_rule_six_is_exercised_only_by_the_fixture(self):
        # Not a tautology: it records why rule 6 needs a synthetic tree, and
        # fails if a future defect reopens a Missing row, at which point the
        # fixture assumptions above deserve a second look.
        rows = checker.parse_rows(INVENTORY.read_text(encoding="utf-8"))
        absent = [r.cid for r in rows if "missing" in r.classification]
        self.assertEqual([], absent)

    def test_the_two_rows_that_lost_a_column_are_five_cells(self):
        # C13.7 and C13.8 carried their argument in the Class cell, so the probe
        # citation had nowhere to go. A four-cell row is the shape that hid it.
        for line in INVENTORY.read_text(encoding="utf-8").splitlines():
            if line.startswith(("| C13.7 ", "| C13.8 ")):
                self.assertEqual(5, len(checker.CELL_SPLIT.split(line.strip().strip("|"))))


class ItCatchesDrift(unittest.TestCase):
    def test_a_row_with_no_probe_marker(self):
        found = problems(replaced("| `c1_pass` |", "| |"))
        self.assertTrue(any("C1.1 has no probe marker" in p for p in found), found)

    def test_a_cited_probe_that_does_not_exist(self):
        found = problems(replaced("`c1_pass`", "`c1_gone`"))
        self.assertTrue(any("c1_gone`, which is not a probe file" in p for p in found), found)

    def test_a_probe_file_the_harness_never_runs(self):
        # On disk but absent from PROBES: correct contents, no execution.
        found = problems(
            lambda text: text,
            harness=lambda h: h.replace('    ("c1_refuse", "cannot do the thing", None),\n', ""),
        )
        self.assertTrue(any("not registered in run.py" in p for p in found), found)

    def test_an_orphan_probe_no_row_claims(self):
        found = problems(replaced("| `c2_pass` |", "| `c1_pass` |"))
        self.assertTrue(any("c2_pass` is registered but no" in p for p in found), found)

    def test_a_marker_copied_from_another_direction(self):
        # Every other rule still holds; only the per-direction rule notices.
        found = problems(lambda text: text.replace("| `c2_pass` |", "| `c1_pass` |"))
        self.assertTrue(any("direction 2" in p for p in found), found)

    def test_an_absence_claim_backed_by_a_passing_probe(self):
        found = problems(replaced("cannot do the thing` | `c1_refuse`", "cannot do the thing` | `c1_pass`"))
        self.assertTrue(any("expected output is a pass" in p for p in found), found)


class ItDoesNotOverFire(unittest.TestCase):
    def test_the_clean_fixture_passes(self):
        self.assertEqual([], problems(lambda text: text))

    def test_a_refusal_probe_backs_an_absence_claim(self):
        # A diagnostic string is how a refusal is asserted, so this must not fire.
        found = problems(replaced("| C1.2 | Refused | Incorrect |", "| C1.2 | Refused | Unverified |"))
        self.assertEqual([], found)

    def test_a_partial_row_may_cite_a_passing_probe(self):
        # The half that works is proven by a green probe; the classification is
        # about the half that does not.
        found = problems(replaced("| C1.1 | Construct | Complete |", "| C1.1 | Construct | Partial |"))
        self.assertEqual([], found)


class TheShapesAreRead(unittest.TestCase):
    def test_absence_classifications(self):
        self.assertEqual(("missing", "incorrect", "unverified"), checker.ASSERTS_ABSENCE)

    def test_the_fifth_cell_holds_the_marker(self):
        row = checker.parse_rows(CLEAN)[0]
        self.assertEqual(["c1_pass"], row.probes)

    def test_backticks_in_the_evidence_cell_are_not_markers(self):
        # The Evidence column is full of backticked transcripts; reading them as
        # citations would invent probes named `ArrayList`.
        row = checker.parse_rows(CLEAN)[0]
        self.assertNotIn("arraylist", row.probes)

    def test_a_short_row_does_not_crash_the_parser(self):
        row = checker.parse_rows("## Direction 1 — x\n\n| C1.1 | a | b |\n")[0]
        self.assertEqual([], row.probes)

    def test_a_row_before_any_direction_heading_is_ignored(self):
        self.assertEqual([], checker.parse_rows("| C1.1 | a | b | c | `d` |\n"))


if __name__ == "__main__":
    unittest.main()
