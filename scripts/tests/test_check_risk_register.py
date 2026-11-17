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
        # The stated count is read from the register rather than hard-coded: it
        # moved from 11 to 12 when R-029 was added, and a test that pins the
        # number turns every legitimate row addition into a test failure that
        # says nothing about the checker.
        match = re.search(r"^\| Medium \| (\d+) \| R-004", self.text, re.MULTILINE)
        self.assertIsNotNone(match, "the Medium summary row was not found")
        stated = int(match.group(1))
        broken = self.text.replace(match.group(0), f"| Medium | {stated + 1} | R-004", 1)
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(
            any(f"states {stated + 1} but {stated} rows" in p for p in problems), problems
        )

    def test_a_total_that_disagrees_with_the_row_count_is_caught(self):
        rows = len(checker.parse_rows(self.text))
        wrong = rows + 1
        broken = re.sub(
            r"^\| \*\*Total\*\* \| \*\*\d+\*\*",
            f"| **Total** | **{wrong}**",
            self.text,
            count=1,
            flags=re.MULTILINE,
        )
        self.assertNotEqual(self.text, broken)
        problems = checker.check(broken)
        self.assertTrue(
            any(f"total is {wrong} but there are {rows} rows" in p for p in problems), problems
        )

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


class ACitedPathMustResolve(unittest.TestCase):
    """The register's own stated blind spot: nothing resolved its citations.

    It said its paths were checked "by hand, once" and that "nothing will notice
    if an entry starts citing a path that has since moved". Three stale numbers
    survived there. These tests are the notice.
    """

    def setUp(self):
        self.text = REGISTER.read_text(encoding="utf-8")

    def test_the_real_register_cites_only_paths_that_resolve(self):
        self.assertEqual([], checker.check_citations(self.text))

    def test_a_citation_that_resolves_nowhere_is_caught(self):
        broken = self.text + "\nSee `oq-core/src/main/java/dev/oqthara/core/Ghost.java`.\n"
        problems = checker.check_citations(broken)
        self.assertTrue(any("Ghost.java" in p for p in problems), problems)

    def test_a_citation_that_resolves_nowhere_at_the_end_of_the_file_is_caught(self):
        # The exemption table is a table, so only its two columns may be skipped.
        # Slicing the document at its heading would let a late entry slide past,
        # and a late entry is where an unchecked citation actually lands.
        broken = self.text + "\n\n" + "x " * 200 + "`scripts/gone-script.py`\n"
        problems = checker.check_citations(broken)
        self.assertTrue(any("gone-script.py" in p for p in problems), problems)

    def test_a_renamed_citation_is_caught(self):
        broken = self.text.replace(
            "`oq-runtime/src/main/java/dev/oqthara/runtime/WaitedBudget.java`",
            "`oq-runtime/src/main/java/dev/oqthara/runtime/OldBudget.java`", 1)
        self.assertNotEqual(self.text, broken)
        problems = checker.check_citations(broken)
        self.assertTrue(any("OldBudget.java" in p for p in problems), problems)

    def test_an_exempted_path_that_starts_to_exist_is_caught(self):
        # R-008 and R-022 are true only while their paths are absent, so a
        # created file must fail the gate rather than quietly validate them.
        # The document is built from the real heading, found by search, so this
        # cannot pass against a heading the checker fails to recognise.
        with tempfile.TemporaryDirectory() as workspace:
            root = pathlib.Path(workspace)
            (root / "no" / "such").mkdir(parents=True)
            (root / "no" / "such" / "file.txt").write_text("", encoding="utf-8")
            original = checker.ROOT
            checker.ROOT = root
            try:
                problems = checker.check_citations(
                    self.text.split("### Citations", 1)[0]
                    + "\n" + self.heading(self.text) + "\n"
                    + "\n| Path | Why |\n| --- | --- |\n"
                    + "| `no/such/file.txt` | absent on purpose |\n"
                    + "\n`no/such/file.txt` is cited.\n"
                )
            finally:
                checker.ROOT = original
        self.assertTrue(any("no/such/file.txt" in p and "now exists" in p for p in problems),
                        problems)

    def test_an_exemption_without_a_reason_is_caught(self):
        broken = (self.text.split("### Citations", 1)[0]
                  + "\n" + self.heading(self.text) + "\n"
                  + "\n| Path | Why |\n| --- | --- |\n| `gone.txt` |   |\n")
        problems = checker.check_citations(broken)
        self.assertTrue(any("without a stated reason" in p for p in problems), problems)

    @staticmethod
    def heading(text):
        """The exemption heading as the document actually writes it."""
        match = checker.EXEMPT_HEADING.search(text)
        assert match is not None, "the real register has no exemption heading"
        return match.group(0)

    def test_the_exemption_heading_is_read_from_the_real_document(self):
        # The register moved this section under a new subsection, from `##` to
        # `###`. The checker matched a literal `## ` prefix, so it recognised
        # nothing; both the skip and the reverse check went dead. Every test still
        # passed because each one rebuilt the document from the same wrong
        # constant. This test reads the document, not the constant.
        match = checker.EXEMPT_HEADING.search(self.text)
        self.assertIsNotNone(match, "the exemption section heading was not recognised")
        self.assertTrue(match.group(0).endswith(checker.EXEMPT_TITLE), match.group(0))
        self.assertEqual(3, len(match.group(0)) - len(match.group(0).lstrip("#")),
                         f"expected the document's `###` heading, got {match.group(0)!r}")

    def test_the_exemption_table_is_found_in_the_real_document(self):
        exemptions = checker.parse_exemptions(self.text)
        self.assertIn("gradle/verification-metadata.xml", exemptions)
        self.assertIn("site/versions.json", exemptions)
        self.assertIn("conformance/", exemptions)
        for path, reason in exemptions.items():
            self.assertTrue(reason.strip(), f"{path} has no stated reason")

    def test_a_citation_after_the_exemption_table_is_still_checked(self):
        # The skip must end at the table, not at the next `##` heading. This is
        # the mutation that found the original off-by-one-level bug.
        broken = self.text + "\n`scripts/deleted-elsewhere.py`\n"
        problems = checker.check_citations(broken)
        self.assertTrue(any("deleted-elsewhere.py" in p for p in problems), problems)


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
