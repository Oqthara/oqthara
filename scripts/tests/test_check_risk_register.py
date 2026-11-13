#!/usr/bin/env python3
"""Regression tests for scripts/check-risk-register.py.

The checker exists because the register has already disagreed with itself twice:
R-014's row said Low while the summary counted it Medium, and the commit that
fixed the counts claimed a script performed the check that no script performed.

Each test reintroduces one of those defects into a copy of the register and
asserts the checker rejects it. A checker that has only ever passed is
indistinguishable from one that cannot fail, so the failing cases are the point.
"""
import pathlib
import re
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "check_risk_register", SCRIPTS / "check-risk-register.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

REGISTER = SCRIPTS.parent / "docs" / "governance" / "alpha-4-risk-register.md"


class TheRealRegisterIsConsistent(unittest.TestCase):
    def test_it_passes(self):
        text = REGISTER.read_text(encoding="utf-8")
        self.assertEqual([], checker.check(text))

    def test_the_checker_knows_every_exposure_the_rows_use(self):
        text = REGISTER.read_text(encoding="utf-8")
        used = {row["exposure"] for row in checker.parse_rows(text)}
        self.assertTrue(used.issubset(set(checker.EXPOSURES)))


class DefectsAreCaught(unittest.TestCase):
    def setUp(self):
        self.text = REGISTER.read_text(encoding="utf-8")

    def test_a_row_whose_exposure_contradicts_the_matrix_is_caught(self):
        # The historical R-014 defect, reproduced exactly.
        broken = re.sub(
            r"^(\| R-014 \|.*\| Medium \| Low \|) Low (\|)",
            r"\1 Medium \2",
            self.text,
            count=1,
            flags=re.MULTILINE,
        )
        self.assertNotEqual(self.text, broken, "the R-014 row was not found to mutate")
        problems = checker.check(broken)
        self.assertTrue(
            any("R-014" in p and "matrix resolves to Low" in p for p in problems), problems
        )

    def test_a_count_that_disagrees_with_its_rows_is_caught(self):
        # Editing the count alone leaves the ID list intact, so only an explicit
        # count comparison notices. This is the defect the summary had.
        broken = self.text.replace(
            "| Medium | 11 | R-004", "| Medium | 12 | R-004", 1)
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("states 12 but 11 rows" in p for p in problems), problems)

    def test_a_total_that_disagrees_with_the_row_count_is_caught(self):
        broken = re.sub(r"^\| \*\*Total\*\* \| \*\*\d+\*\*", "| **Total** | **27**", self.text,
                        count=1, flags=re.MULTILINE)
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("total is 27 but there are 28 rows" in p for p in problems), problems)

    def test_a_duplicate_id_is_caught(self):
        broken = re.sub(r"^\| R-028 \|", "| R-027 |", self.text, count=1, flags=re.MULTILINE)
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("more than once" in p for p in problems), problems)

    def test_an_id_missing_from_the_summary_is_caught(self):
        broken = self.text.replace("R-026, R-028", "R-026", 1)
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("R-028" in p and "missing from the summary" in p for p in problems),
                        problems)

    def test_an_altered_exposure_matrix_is_caught(self):
        # If someone weakens the matrix to match a wrong row, the checker must
        # object rather than adopt the weakened table as its own standard.
        broken = self.text.replace("| **Low** | Low | Low | Medium |",
                                   "| **Low** | Low | Medium | Medium |", 1)
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("no longer matches the checker" in p for p in problems), problems)


class TheCommandLineBehaves(unittest.TestCase):
    def test_it_exits_zero_on_the_real_register(self):
        import subprocess
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "check-risk-register.py")],
            cwd=SCRIPTS.parent, capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("matrix and summary agree", result.stdout)

    def test_it_exits_two_when_the_register_is_absent(self):
        import subprocess
        with tempfile.TemporaryDirectory() as empty:
            result = subprocess.run(
                [sys.executable, str(SCRIPTS / "check-risk-register.py")],
                cwd=empty, capture_output=True, text=True,
            )
        self.assertEqual(2, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
