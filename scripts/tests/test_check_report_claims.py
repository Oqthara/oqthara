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
import re
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
        self.assertEqual(28, VALUES["probes"])
        self.assertEqual(55, VALUES["capability_rows"])
        self.assertEqual(13, VALUES["directions"])
        self.assertEqual(14, VALUES["fuzz_targets"])
        self.assertEqual(22, VALUES["keywords"])
        self.assertEqual(6, VALUES["defects"])
        self.assertEqual(31, VALUES["risk_total"])
        self.assertEqual(5, VALUES["Open"])
        # §4's scope record states this one, and nothing read it while it was
        # being written: mutating "twelve" to "thirteen" passed every gate.
        self.assertEqual(12, VALUES["refusals"])
        self.assertEqual(13, VALUES["probed_groups"])


class DriftIsCaught(unittest.TestCase):
    """One test per class of defect a real review record has found."""

    def test_a_mutated_total_is_caught(self):
        problems = problems_after("**55** capability rows", "**41** capability rows")
        self.assertTrue(problems)
        self.assertIn("41 capability rows", problems[0])

    def test_a_mutated_spelled_out_total_is_caught(self):
        problems = problems_after("**thirteen** directions", "**nine** directions")
        self.assertTrue(problems)

    def test_a_mutated_coverage_range_is_caught(self):
        # PR-263 finding 5. A grep for the count 19 does not see this one,
        # because the stale thing is the range, not the count.
        problems = problems_after("covers C1–C13 in 28", "covers C1–C8 in 28")
        self.assertTrue(problems)
        self.assertIn("C8", problems[0])

    def test_a_mutated_probe_total_with_no_unit_word_is_caught(self):
        # The report writes "covers C1–C13 in 26" without the word "probes".
        # Requiring the unit word let this class through entirely.
        problems = problems_after("C1–C13 in 28", "C1–C13 in 19")
        self.assertTrue(problems)

    def test_a_figure_after_a_currency_verb_is_caught(self):
        # PR-264 finding 22. §9c said "so the command prints 26" — a figure with
        # no unit word, attached to a verb. No noun rule could see it and the
        # CLAIM_SHAPE catch-all could not either, so it passed unread while the
        # tree held 27. The repaired sentence names its unit; this restates the
        # shape that defeated the gate.
        problems = problems_after(
            "so the command counts 29 probe files where it counted 19",
            "so the command prints 26",
        )
        self.assertTrue(problems)
        self.assertIn("prints 26", problems[0])

    def test_a_backticked_figure_tuple_after_a_currency_verb_is_caught(self):
        # The same defect's harder half. Backticks are masked to spaces before
        # any noun rule runs, so a tuple of four units-dropped figures was
        # invisible twice over: no unit word, and the digits themselves blanked.
        match = re.search(r"Measured now: \d+ directions, \d+ capability rows, "
                          r"\d+ probe files, \d+ ADRs\.", REPORT_TEXT)
        self.assertIsNotNone(match, "the report no longer states its measured-now totals")
        problems = problems_after(
            match.group(0),
            "Re-running it today gives `13`, `52`, `26`, `71`.",
        )
        self.assertTrue(problems)
        self.assertIn("gives", problems[0])

    def test_the_new_probe_file_quantity_is_derived_not_guessed(self):
        # A sentence may now say "29 probe files", so the number behind it must
        # come from the tree rather than from a second copy of the total.
        self.assertEqual(
            len(list((SCRIPTS.parent / checker.PROBE_DIR).glob("*.oq"))),
            VALUES["probe_files"],
        )

    def test_a_mutated_probe_file_total_is_caught(self):
        problems = problems_after("29 probe files", "26 probe files")
        self.assertTrue(problems)
        self.assertIn("26 probe files", problems[0])

    def test_measured_now_does_not_hide_the_figures_before_the_last_one(self):
        # "Measured now: 13 directions, 55 capability rows, 28 probe files, 69
        # ADRs" is four current totals, not a before-and-after. Reading bare
        # "now" as a pairing marker checked only the trailing figure, and a
        # stale "27 probe files" sat in this sentence through the pass that
        # added the 28th probe. Every figure in the list must be read.
        # Read the sentence out of the report rather than pinning it: the
        # totals in it move whenever a probe file or an ADR is added, and a
        # test that breaks on a correct update teaches the next person to edit
        # the number instead of reading it.
        match = re.search(r"Measured now: \d+ directions, \d+ capability rows, "
                          r"\d+ probe files, \d+ ADRs\.", REPORT_TEXT)
        self.assertIsNotNone(match, "the report no longer states its measured-now totals")
        sentence = match.group(0)
        live = re.search(r"(\d+) probe files", sentence).group(1)
        stale = str(int(live) - 2)
        problems = problems_after(sentence, sentence.replace(f"{live} probe files",
                                                             f"{stale} probe files"))
        self.assertTrue(problems)
        self.assertIn(f"{stale} probe files", problems[0])

    def test_a_mutated_bare_target_count_is_caught(self):
        # The repaired line 664. Written in the present tense, so a dating
        # exemption does not apply and the count must match the build file.
        problems = problems_after(
            "claims fourteen targets exist", "claims eight targets exist"
        )
        self.assertTrue(problems)

    def test_a_mutated_status_cell_is_caught(self):
        for name, held in (("Open", "5"), ("Mitigated", "12"), ("Accepted", "14")):
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
            "**R-012, R-021, R-022, R-023, R-027**",
            "**R-012, R-021, R-022, R-023, R-028**",
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

    def test_deleting_the_scope_section_is_caught(self):
        # The scope record is the thing this change exists to add, so its
        # absence has to fail. Deleting Item 6b outright passed all nine gates
        # before this rule: every other check searches for what is present, and
        # none requires anything to be.
        start = REPORT_TEXT.index("## Item 6b — §4's scope, recorded")
        end = REPORT_TEXT.index("## Item 7 — Type-system decisions")
        problems = checker.check_required_sections(REPORT_TEXT[:start] + REPORT_TEXT[end:])
        self.assertTrue(problems)
        self.assertIn("no longer has", problems[0])

    def test_deleting_one_exclusion_row_is_caught(self):
        # Deleting a row silently widens §4, which is the only way a scope
        # record can fail without becoming obviously wrong.
        problems = checker.check_required_sections(
            REPORT_TEXT.replace("| Grafeno and OQSS | Outside this repository's Alpha 7 work |\n", "")
        )
        self.assertTrue(problems)
        self.assertIn("Grafeno", problems[0])

    def test_weakening_the_refusal_rule_is_caught(self):
        # "The twelve ADR-0062 refusals remain refusals" is the boundary that
        # stops §4 reviving a decided question. Rewording it to something
        # weaker is not a copy-edit.
        problems = checker.check_required_sections(
            REPORT_TEXT.replace(
                "**The twelve ADR-0062 refusals remain refusals**",
                "**These remain refusals**",
            )
        )
        self.assertTrue(problems)
        self.assertIn("twelve ADR-0062 refusals remain refusals", problems[0])

    def test_a_mutated_refusal_count_is_caught(self):
        # The refusals live in the Alpha 6 matrix, which this gate did not read.
        # §4's scope record is the first place to state the figure in prose, so
        # it is derived from that matrix rather than trusted.
        problems = problems_after(
            "**The twelve ADR-0062 refusals remain refusals**",
            "**The eleven ADR-0062 refusals remain refusals**",
        )
        self.assertTrue(problems)
        # The message quotes the sentence as written, which is the word form.
        self.assertIn("says eleven refusals", problems[0])
        self.assertIn("has 12", problems[0])

    def test_the_derivation_reads_the_matrix_not_the_report(self):
        # The control for the test above: a figure read out of the sentence it
        # is checking would agree with itself no matter what was written.
        self.assertEqual(12, checker.derive()["refusals"])

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


class TheCapabilityMatrixCountsItsOwnRows(unittest.TestCase):
    """The matrix states its arithmetic; the gate derives it from the table.

    Three hand-counts of the same document gave 29, 49 and 50 before these
    rules existed, and the closing paragraph restates every verdict a second
    time, so an edit to one usually misses the other.
    """

    MATRIX = SCRIPTS.parent / "docs" / "governance" / "alpha-7-language-core-capability-matrix.md"
    MATRIX_TEXT = MATRIX.read_text(encoding="utf-8")

    def matrix_problems_after(self, old, new):
        assert old in self.MATRIX_TEXT, f"the matrix no longer contains {old!r}"
        return checker.check_capability_matrix(
            self.MATRIX_TEXT.replace(old, new, 1)
        )

    def test_the_real_matrix_is_consistent(self):
        self.assertEqual([], checker.check_capability_matrix())

    def test_a_mutated_verdict_count_is_caught(self):
        # The fixture reads the stated count out of the document rather than
        # naming it. Pinning a literal here is how two tests in this file broke
        # when L9.3 was closed and Complete legitimately moved 50 -> 51: the
        # gate was right and the fixture was stale, which is the failure the
        # gate exists to catch, reproduced in the gate's own tests.
        stated = re.search(r"^\| Complete \| (\d+) \|", self.MATRIX_TEXT, re.M)
        self.assertIsNotNone(stated, "the matrix no longer states a Complete count")
        current = int(stated.group(1))
        problems = self.matrix_problems_after(
            f"| Complete | {current} |", f"| Complete | {current - 1} |"
        )
        self.assertTrue(problems)
        self.assertIn(f"{current - 1} 'Complete'", problems[0])
        self.assertIn(f"holds {current}", problems[0])

    def test_a_mutated_row_total_is_caught(self):
        problems = self.matrix_problems_after("57 rows, counted", "58 rows, counted")
        self.assertTrue(problems)
        self.assertIn("says 58 rows", problems[0])

    def test_a_mutated_prose_count_is_caught(self):
        # The second copy of the same quantity. Editing one and not the other
        # is the exact error the matrix was written to document. The words are
        # read from the document for the same reason as the test above.
        phrasing = re.search(r"\*\*(\d+) rows behave as", self.MATRIX_TEXT)
        self.assertIsNotNone(phrasing, "the matrix no longer states its Complete total in prose")
        stated = int(phrasing.group(1))
        problems = self.matrix_problems_after(
            f"**{stated} rows behave as", f"**{stated + 1} rows behave as"
        )
        self.assertTrue(problems)
        self.assertIn(f"{stated + 1} 'Complete'", problems[0])

    def test_a_mutated_refusal_verdict_is_caught(self):
        problems = self.matrix_problems_after("| Intentionally refused | 1 |", "| Intentionally refused | 2 |")
        self.assertTrue(problems)
        self.assertIn("'Intentionally refused'", problems[0])

    def test_a_zeroed_verdict_counter_is_still_checked(self):
        # Recounting only the verdicts that appear leaves a counter unguarded the
        # moment one falls to zero. With "| Missing | 0 |" and no Missing row, a
        # stray "| Missing | 1 |" passed this gate until the loop was closed over
        # the six defined verdicts.
        stated = re.search(r"^\| Missing \| (\d+) \|", self.MATRIX_TEXT, re.M)
        self.assertIsNotNone(stated, "the matrix no longer counts Missing rows")
        current = int(stated.group(1))
        problems = self.matrix_problems_after(
            f"| Missing | {current} |", f"| Missing | {current + 1} |"
        )
        self.assertTrue(problems)
        self.assertIn(f"{current + 1} 'Missing'", problems[0])
        self.assertIn("holds 0", problems[0])

    def test_a_verdict_outside_the_defined_six_is_caught(self):
        # A new verdict word would otherwise be counted and then silently skipped
        # by the closed loop, so the table could grow a sixth-plus category with
        # no stated arithmetic behind it.
        row = next(
            line for line in self.MATRIX_TEXT.splitlines() if line.startswith("| L1.1 ")
        )
        mutated = row.replace("| Complete |", "| Probably |", 1)
        self.assertNotEqual(row, mutated, "L1.1 is no longer a Complete row")
        problems = checker.check_capability_matrix(
            self.MATRIX_TEXT.replace(row, mutated, 1)
        )
        self.assertIn(
            "the matrix uses the verdict 'Probably', which is not one of the six it defines",
            problems,
        )

    def test_the_defined_verdicts_are_the_six_the_matrix_names(self):
        self.assertEqual(6, len(checker.VERDICTS))
        self.assertEqual(len(set(checker.VERDICTS)), len(checker.VERDICTS))
        for verdict in checker.VERDICTS:
            with self.subTest(verdict=verdict):
                self.assertIn(
                    verdict.replace("*", ""),
                    self.MATRIX_TEXT.replace("*", ""),
                    f"the matrix never names the verdict {verdict!r}",
                )

    def test_an_emptied_matrix_is_caught(self):
        # Deleting every row leaves the counts and the prose agreeing at zero,
        # which would otherwise read as success.
        stripped = "\n".join(
            line for line in self.MATRIX_TEXT.splitlines() if not line.startswith("| L")
        )
        problems = checker.check_capability_matrix(stripped)
        self.assertTrue(problems)
        self.assertIn("no capability rows", problems[0])

    def test_a_stray_clause_is_not_read_as_a_total(self):
        # "L3.11 is missing" appears in the prose and matches the Missing clause
        # shape, reading "L3" as the count. The closing paragraph is read on its
        # own for exactly this reason.
        self.assertEqual([], checker.check_capability_matrix())


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

    def test_a_figure_that_keeps_its_unit_after_a_currency_verb_is_silent(self):
        # "reads 22" is the shape a currency-verb rule fires on, but here the
        # unit follows the figure, so the sentence is unambiguous and refusing
        # it would be noise. This is the pairing for the catching test above:
        # the rule fires on a dropped unit, not on the verb.
        self.assertEqual(
            [],
            problems_after(
                "and the agent file reads 22 words",
                "and the agent file reads 30 words",
            ),
        )

    def test_a_rating_after_a_currency_verb_is_silent(self):
        # "returns to 27/27 and exit 0" states a ratio, not a count of anything.
        # Reading it as one figure with no unit would flag a correct sentence —
        # the score is the whole claim. This is why the numeral pattern accepts
        # a slash before a currency verb is allowed to reject the shape.
        self.assertEqual(
            [],
            problems_after(
                "the run returns to 27/27 and exit 0",
                "the run returns to 26/26 and exit 0",
            ),
        )

    def test_a_percentage_after_a_currency_verb_is_silent(self):
        # `%` is a unit that happens to be a symbol. Found by probing the rule
        # after writing it: "coverage gives 92.3%" was being read as a figure
        # that dropped its unit, because the guard only looked for a trailing
        # letter. The figure is unambiguous and must stay silent.
        problems = checker.check(
            "The gate yields 92.3% on this tree and the other one gives 80.5%.",
            VALUES,
        )
        self.assertEqual([], problems)

    def test_a_decimal_after_a_currency_verb_is_read_whole(self):
        # The same probe found the rule truncating decimals: "yields 3.14"
        # matched as "yields 3" and the next character was a dot, which the
        # guard did not look for. A partial numeral in a report is worse than
        # no numeral, so the pattern reads the decimal or it refuses.
        matches = checker.CURRENCY_FIGURE.findall("the build yields 3.14")
        self.assertEqual(["3.14"], matches)


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
