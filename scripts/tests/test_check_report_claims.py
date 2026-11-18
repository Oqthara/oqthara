#!/usr/bin/env python3
"""Regression tests for scripts/check-report-claims.py.

Four review records in a row — PR-260 through PR-263 — found the same defect:
a number in `docs/governance/alpha-7-first-report.md` that was true when written
and had quietly stopped being true. Every pass repaired the sentences by hand.
Nothing re-derived them, so the next pass found new ones.

The gate has two ways to be useless, and both are tested here.

It can **miss** drift, which is what it was built for. Each test below
reintroduces one real class of drift into a copy of the report and asserts the
checker rejects it: a mutated total, a mutated coverage range, a mutated status
cell, a mutated scoped subset, and a claim-shaped sentence the rules do not
recognise.

It can also **over-fire**. A gate that rejects the dated snapshots the record
depends on — §9c quoting what a command printed then, the before/after halves of
a change — is worse than no gate, because the first person to hit it deletes it
from CI. So each catching test is paired with a silence test over the same
sentence class. A checker that has only ever passed is indistinguishable from one
that cannot fail; a checker that fires on everything is indistinguishable from
one that reports nothing.
"""
import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_report_claims", SCRIPTS / "check-report-claims.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

REPORT = SCRIPTS.parent / "docs" / "governance" / "alpha-7-first-report.md"

# The values are derived once from the tree rather than hardcoded, for the same
# reason the gate itself derives them: a second copy of a total is the defect.
VALUES = checker.derive(SCRIPTS.parent)
REPORT_TEXT = REPORT.read_text(encoding="utf-8")


def problems_after(old: str, new: str) -> list[str]:
    """The problems the checker reports once `old` is replaced by `new`."""
    assert old in REPORT_TEXT, f"the report no longer contains {old!r}"
    return checker.check(REPORT_TEXT.replace(old, new, 1), VALUES)


class TheRealReportIsConsistent(unittest.TestCase):
    def test_it_passes(self):
        self.assertEqual([], checker.check(REPORT_TEXT, VALUES))

    def test_the_derived_values_are_the_real_ones(self):
        # If a deriver silently returned 0 the gate would pass on any figure
        # zero, so pin the derivation itself, not just the report that uses it.
        self.assertEqual(27, VALUES["probes"])
        self.assertEqual(54, VALUES["capability_rows"])
        self.assertEqual(13, VALUES["directions"])
        self.assertEqual(14, VALUES["fuzz_targets"])
        self.assertEqual(22, VALUES["keywords"])
        self.assertEqual(6, VALUES["defects"])
        self.assertEqual(31, VALUES["risk_total"])
        self.assertEqual(6, VALUES["Open"])
        self.assertEqual(13, VALUES["probed_groups"])


class DriftIsCaught(unittest.TestCase):
    """One test per class of defect a real review record has found."""

    def test_a_mutated_total_is_caught(self):
        problems = problems_after("**54** capability rows", "**41** capability rows")
        self.assertTrue(problems)
        self.assertIn("41 capability rows", problems[0])

    def test_a_mutated_spelled_out_total_is_caught(self):
        problems = problems_after("**thirteen** directions", "**nine** directions")
        self.assertTrue(problems)

    def test_a_mutated_coverage_range_is_caught(self):
        # PR-263 finding 5. A grep for the count 19 does not see this one,
        # because the stale thing is the range, not the count.
        problems = problems_after("covers C1–C13 in 27", "covers C1–C8 in 27")
        self.assertTrue(problems)
        self.assertIn("C8", problems[0])

    def test_a_mutated_probe_total_with_no_unit_word_is_caught(self):
        # The report writes "covers C1–C13 in 26" without the word "probes".
        # Requiring the unit word let this class through entirely.
        problems = problems_after("C1–C13 in 27", "C1–C13 in 19")
        self.assertTrue(problems)

    def test_a_mutated_bare_target_count_is_caught(self):
        # The repaired line 664. Written in the present tense, so a dating
        # exemption does not apply and the count must match the build file.
        problems = problems_after(
            "claims fourteen targets exist", "claims eight targets exist"
        )
        self.assertTrue(problems)

    def test_a_mutated_status_cell_is_caught(self):
        for name, held in (("Open", "6"), ("Mitigated", "12"), ("Accepted", "13")):
            with self.subTest(status=name):
                other = str(int(held) - 1)
                problems = problems_after(f"| {name} | {held} |", f"| {name} | {other} |")
                self.assertTrue(problems)

    def test_a_mutated_table_total_is_caught(self):
        problems = problems_after("| **Total** | **31** |", "| **Total** | **30** |")
        self.assertTrue(problems)

    def test_a_wrong_open_id_is_caught(self):
        # A count alone would miss this: the register still has six Open
        # entries, but the sentence now names one that is not among them.
        problems = problems_after(
            "**R-012, R-014, R-021, R-022, R-023, R-027**",
            "**R-012, R-014, R-021, R-022, R-023, R-028**",
        )
        self.assertTrue(problems)

    def test_a_mutated_defect_range_is_caught(self):
        problems = problems_after("**D1–D6**", "**D1–D4**")
        self.assertTrue(problems)

    def test_a_mutated_scoped_subset_is_caught(self):
        # "the five d1_* probes" is a claim about one group, not the whole
        # harness. It needs the group's own count, not the total.
        problems = problems_after("the five `d1_*` probes", "the four `d1_*` probes")
        self.assertTrue(problems)

    def test_a_bare_risk_total_is_caught(self):
        # The risk total appears in the Item 2 table, so it looked covered. It
        # is also written bare in prose, and "risks" was absent from NOUNS, so
        # a sentence stating the wrong total passed unread. Found in review of
        # this gate, not of the report.
        problems = problems_after(
            "**Item 8 held up completely.**",
            "**Item 8 held up completely.** The register holds 30 risks.",
        )
        self.assertTrue(problems)
        self.assertIn("30 risks", problems[0])

    def test_a_noun_before_the_number_is_caught(self):
        # The mirror shape. Every rule above reads "41 capability rows"; a
        # heading or summary line writing "capability rows: 41" matched nothing
        # and was silently skipped, which is the failure mode the next test
        # guards against in general.
        problems = problems_after(
            "**Item 8 held up completely.**",
            "**Item 8 held up completely.** Capability rows: 41.",
        )
        self.assertTrue(problems)

    def test_a_case_insensitive_noun_is_checked_not_crashed(self):
        # `NOUN_KEY` was built from the noun as written and then read with
        # `.lower()`, so "ADRs" was unreachable and any live "69 ADRs" raised
        # KeyError out of classify. No test saw it because the only occurrences
        # of that phrase were inside fenced blocks, which are exempt. A crash is
        # not a pass, and a gate that dies takes the CI step with it.
        problems = problems_after(
            "**Item 8 held up completely.**",
            "**Item 8 held up completely.** There are 68 ADRs in the tree.",
        )
        self.assertTrue(problems)
        self.assertIn("68 ADRs", problems[0])

    def test_the_correct_adr_total_is_silent(self):
        # The positive control for the test above: it must reject the wrong
        # number by comparing, not by raising on every number.
        self.assertEqual(
            [],
            problems_after(
                "**Item 8 held up completely.**",
                f"**Item 8 held up completely.** There are {VALUES['adrs']} ADRs in the tree.",
            ),
        )

    def test_an_unclassifiable_claim_is_caught(self):
        # The failure this gate exists to prevent in itself: silently skipping
        # what it cannot parse and reporting success.
        problems = problems_after(
            "**Item 8 held up completely.**",
            "**Item 8 held up completely.** There are 88 capability rows here.",
        )
        self.assertTrue(problems)
        self.assertIn("88", problems[0])


class HistoricalFiguresStaySilent(unittest.TestCase):
    """The dated snapshots the record depends on must not be reported."""

    def test_a_dated_snapshot_is_silent(self):
        # §9c quotes what a command printed then, on purpose.
        self.assertEqual(
            [],
            problems_after(
                "covered C1–C8 in 19 probes when this table was written",
                "covered C1–C6 in 5 probes when this table was written",
            ),
        )

    def test_a_dated_snapshot_with_a_trailing_predicate_is_silent(self):
        # The dating clause here governs a whole list and sits after it. A
        # rule that only looked at the clause holding each figure would fire.
        self.assertEqual(
            [],
            problems_after(
                "Thirteen directions, 52 capability rows",
                "Twelve directions, 50 capability rows",
            ),
        )

    def test_a_quoted_figure_is_silent(self):
        # The report quotes a gate message to illustrate bad input. Markdown
        # wraps the quoted span across two lines, so this also proves the
        # reader works on sentences rather than lines.
        self.assertEqual(
            [],
            problems_after(
                "`run.py` covering\nC1–C8 with 19 probes",
                "`run.py` covering\nC1–C5 with 9 probes",
            ),
        )


class TheExemptionListIsSmall(unittest.TestCase):
    def test_every_exemption_states_a_reason(self):
        # An exemption without a written reason is how a gate starts lying.
        self.assertTrue(checker.EXEMPT, "the exemption list is empty")
        for pattern, reason in checker.EXEMPT:
            with self.subTest(pattern=pattern.pattern):
                self.assertTrue(reason.strip(), "exemption has no reason")

    def test_the_dating_markers_do_not_include_bare_past_tense(self):
        # Bare "was"/"were"/"had" appear in live present-tense prose and, as
        # whole-sentence exemptions, switch the gate off for those sentences.
        for word in ("was", "were", "had", "then"):
            with self.subTest(word=word):
                self.assertIsNone(
                    checker.DATED.fullmatch(word),
                    f"bare {word!r} must not be a dating marker on its own",
                )


if __name__ == "__main__":
    unittest.main()
