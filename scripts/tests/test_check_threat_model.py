#!/usr/bin/env python3
"""Regression tests for scripts/check-threat-model.py.

The gate exists because the threat model had no gate at all. Its review cadence
promises it is "revisited at least once per Alpha" and that "a threat is
admitted with an ID, a status, and at least one cited path"; nothing checked
either. When it was finally re-read at the Alpha 7 boundary the pin was three
Alphas old, six facts were stale, and TH-15's stated mitigation — "the compiler
refuses `use`" — had stopped existing when ADR-0066 made `use` compile.

Each test reintroduces one failure mode into a copy of the document and asserts
the gate rejects it. Two of them read the *real* document rather than a rebuilt
fixture, because the failure this codebase keeps repeating is a test that
constructs its input from the same constant the checker uses: the two agree by
construction and the test can never fail. `TheRealDocumentIsReadCorrectly`
exists to be that pair of eyes.
"""
import importlib.util
import pathlib
import re
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_threat_model", SCRIPTS / "check-threat-model.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

MODEL = SCRIPTS.parent / "docs" / "governance" / "alpha-4-threat-model.md"


def bucket_line(text, status):
    """The summary table row for `status`, e.g. `| **Mitigated** | 15 | TH-17, ...`.

    Tests mutate the row they find rather than one they spell out. Spelling the
    row out is how the first version of these tests broke: moving one threat
    between statuses changed `Mitigated | 14` to `Mitigated | 15` and four tests
    failed on a document that was correct. A test that hardcodes a count is a
    second copy of the document, and the two drift.
    """
    for line in text.splitlines():
        if line.startswith(f"| **{status}** |") and re.match(
            r"\| \*\*[^*]+\*\* \| \d+ \| TH-", line
        ):
            return line
    raise AssertionError(f"no summary row for {status}")


class TheRealDocumentPasses(unittest.TestCase):
    def test_it_passes(self):
        text = MODEL.read_text(encoding="utf-8")
        self.assertEqual([], checker.check(text))

    def test_it_finds_every_threat_row(self):
        text = MODEL.read_text(encoding="utf-8")
        threats = checker.parse_threats(text)
        self.assertGreaterEqual(len(threats), 50)

    def test_no_threat_id_has_two_rows(self):
        text = MODEL.read_text(encoding="utf-8")
        ids = [t["id"] for t in checker.parse_threats(text)]
        self.assertEqual(len(ids), len(set(ids)), "a TH- id appears in two tables")

    def test_every_status_in_the_legend_is_used(self):
        text = MODEL.read_text(encoding="utf-8")
        used = {t["status"] for t in checker.parse_threats(text)}
        self.assertEqual(set(checker.STATUSES), used)


class TheRealDocumentIsReadCorrectly(unittest.TestCase):
    """Reads the real file for the structures the checker depends on.

    These do not rebuild the document from `checker` constants. If the summary
    heading is renamed or the exemption title reworded, these fail — which is the
    whole point, because a checker that silently parses nothing reports success.
    """

    def test_the_summary_section_is_found_by_its_real_heading(self):
        text = MODEL.read_text(encoding="utf-8")
        listed, stated, total, error = checker.parse_summary(text)
        self.assertTrue(listed, "the summary table was not found in the real document")
        self.assertEqual(len(checker.STATUSES), len(listed))
        self.assertIsNotNone(total, "the 'Fifty-five threats.' sentence was not parsed")
        self.assertIsNone(error, error)

    def test_the_exemption_table_is_found_in_the_real_document(self):
        text = MODEL.read_text(encoding="utf-8")
        register_check = checker._load("check_risk_register", "check-risk-register.py")
        exemptions = register_check.parse_exemptions(text, checker.EXEMPT_TITLE)
        self.assertIn("gradle/verification-metadata.xml", exemptions)
        self.assertIn("site/versions.json", exemptions)
        for path, reason in exemptions.items():
            self.assertTrue(reason.strip(), f"{path} is exempted with no reason")

    def test_no_exempted_path_actually_exists(self):
        text = MODEL.read_text(encoding="utf-8")
        register_check = checker._load("check_risk_register", "check-risk-register.py")
        for path in register_check.parse_exemptions(text, checker.EXEMPT_TITLE):
            self.assertIsNone(
                register_check.resolve_citation(path),
                f"{path} is exempted as absent but resolves",
            )


class ASummaryCountMustMatchItsIds(unittest.TestCase):
    def test_a_wrong_count_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        row = bucket_line(text, "Mitigated")
        count = int(re.search(r"\| (\d+) \|", row).group(1))
        lied = row.replace(f"| {count} |", f"| {count + 1} |", 1)
        broken = text.replace(row, lied, 1)
        self.assertNotEqual(text, broken)
        problems = checker.check(broken)
        self.assertTrue(
            any(f"states {count + 1} but lists {count}" in p for p in problems), problems
        )

    def test_a_dropped_id_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        row = bucket_line(text, "Mitigated")
        listed = re.search(r"TH-\d+(?:, TH-\d+)*", row).group(0).split(", ")
        dropped = listed[0]
        broken = text.replace(row, row.replace(f"{dropped}, ", "", 1), 1)
        self.assertNotEqual(text, broken)
        problems = checker.check(broken)
        self.assertTrue(
            any(f"{dropped} has a threat row but is in no summary bucket" in p for p in problems),
            problems,
        )


class TheBucketsMustPartitionTheThreats(unittest.TestCase):
    def test_an_id_in_two_buckets_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        open_row = bucket_line(text, "Not mitigated")
        first = re.search(r"TH-\d+", open_row).group(0)
        duped = re.search(r"TH-\d+", bucket_line(text, "Mitigated")).group(0)
        inserted = open_row.replace(first, f"{duped}, {first}", 1)
        broken = text.replace(open_row, inserted, 1)
        self.assertNotEqual(text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("also under another status" in p for p in problems), problems)

    def test_an_id_in_no_bucket_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        open_row = bucket_line(text, "Not mitigated")
        dropped = re.search(r"TH-\d+", open_row).group(0)
        broken = text.replace(open_row, open_row.replace(f"{dropped}, ", "", 1), 1)
        self.assertNotEqual(text, broken)
        problems = checker.check(broken)
        self.assertTrue(any(dropped in p and "in no summary bucket" in p for p in problems), problems)

    def test_a_total_that_disagrees_with_the_rows_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        broken = text.replace("Fifty-five threats.", "Fifty-four threats.", 1)
        self.assertNotEqual(text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("states 54 threats" in p for p in problems), problems)


class AnUnreadableFactIsAnError(unittest.TestCase):
    """The gate must not treat "I cannot parse this" as "nothing to report".

    These three cases share one root cause, found by reading the gate back rather
    than by running it. Each made a real check silently stop checking while every
    gate stayed green -- the same shape as the `EXEMPT_HEADING` bug that the
    register's gate shipped with, and the same shape as return `None`.
    """

    def test_an_unreadable_total_is_rejected_rather_than_skipped(self):
        text = MODEL.read_text(encoding="utf-8")
        for wrong in ("Ninety-five threats.", "Fifty threats.", "fifty-five threats."):
            with self.subTest(stated=wrong):
                broken = text.replace("Fifty-five threats.", wrong, 1)
                self.assertNotEqual(text, broken)
                problems = checker.check(broken)
                self.assertTrue(problems, f"{wrong!r} disabled the total check silently")

    def test_a_missing_total_sentence_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        broken = text.replace("Fifty-five threats.", "Some threats.", 1)
        self.assertNotEqual(text, broken)
        problems = checker.check(broken)
        self.assertTrue(any("stated total" in p for p in problems), problems)

    def test_an_invented_status_is_named_as_the_status_problem(self):
        text = MODEL.read_text(encoding="utf-8")
        broken = text.replace(
            "| TH-17 | An unbound placeholder silently writes `NULL` | Tampering | **Mitigated** |",
            "| TH-17 | An unbound placeholder silently writes `NULL` | Tampering | **Basically fine** |",
            1,
        )
        self.assertNotEqual(text, broken, "the fixture no longer contains the TH-17 row")
        problems = checker.check(broken)
        self.assertTrue(
            any("TH-17 has status '**Basically fine**'" in p for p in problems),
            f"the row was dropped without naming the bad status: {problems}",
        )

    def test_the_real_document_has_no_row_with_an_unknown_status(self):
        text = MODEL.read_text(encoding="utf-8")
        self.assertEqual([], checker.rows_without_a_known_status(text))


class ACitationMustResolve(unittest.TestCase):
    def test_an_unresolvable_citation_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        broken = text.replace("`Builtins.java`", "`Ghosts.java`", 1)
        self.assertNotEqual(text, broken, "the fixture no longer contains the citation it mutates")
        problems = checker.check(broken)
        self.assertTrue(any("Ghosts.java" in p for p in problems), problems)

    def test_an_exempted_path_that_appears_is_rejected(self):
        text = MODEL.read_text(encoding="utf-8")
        # `site/versions.json` is cited by TH-53 because ADR-0011 removed site/.
        # Creating it is the error the reverse check exists for, and it is
        # simulated here by making the resolver answer for that one path rather
        # than by touching the tree, so the test cannot leave debris behind.
        register_check = checker._load("check_risk_register", "check-risk-register.py")
        real_resolve = register_check.resolve_citation
        try:
            register_check.resolve_citation = lambda cited: (
                register_check.ROOT / "site" / "versions.json"
                if cited == "site/versions.json"
                else real_resolve(cited)
            )
            problems = checker.check(text)
        finally:
            register_check.resolve_citation = real_resolve
        self.assertTrue(
            any("now exists" in p and "site/versions.json" in p for p in problems), problems
        )


if __name__ == "__main__":
    unittest.main()
