#!/usr/bin/env python3
"""Regression tests for scripts/check-requirement-matrix.py.

The Alpha 6 matrix is the file a reader consults to decide what Alpha 6 lacks.
Nothing read it, so a stale figure or a dead citation survived every review —
the same class of defect `check-report-claims.py` exists to prevent in the
Alpha 7 report.

As with that gate, there are two ways for this one to be useless, and both are
tested: it can miss drift, and it can over-fire on a document that is correct.
The catching tests mutate a copy of the real matrix and assert the checker
rejects it; the silence test asserts the unmutated matrix passes, so a gate that
rejected everything would fail here rather than looking thorough.
"""
import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_requirement_matrix", SCRIPTS / "check-requirement-matrix.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

MATRIX = SCRIPTS.parent / "docs" / "governance" / "alpha-6-requirement-matrix.md"


def problems_for(mutate) -> list[str]:
    """The problems reported for the real matrix with `mutate` applied."""
    text = mutate(MATRIX.read_text(encoding="utf-8"))
    live = checker.prose(text)
    found: list[str] = []
    checker.problems.clear()
    checker.states_are_in_the_legend(text)
    checker.cited_paths_are_real(live)
    checker.cited_conformance_are_real(live)
    checker.conformance_figures_agree(live)
    checker.probe_figures_agree(live)
    checker.refusal_count_matches_the_rows(live)
    checker.last_verified_pin_resolves(text)
    found.extend(checker.problems)
    checker.problems.clear()
    return found


def replaced(old: str, new: str):
    def mutate(text: str) -> str:
        assert old in text, f"the fixture {old!r} is no longer in the matrix"
        return text.replace(old, new, 1)

    return mutate


class TheMatrixPasses(unittest.TestCase):
    def test_the_unmutated_matrix_is_accepted(self):
        self.assertEqual([], problems_for(lambda text: text))

    def test_it_reads_the_rows_it_claims_to_read(self):
        rows = checker.rows(MATRIX.read_text(encoding="utf-8"))
        self.assertGreater(len(rows), 30)
        self.assertTrue(all(len(cells) == 4 for _, cells in rows))


class ItCatchesDrift(unittest.TestCase):
    def test_a_stale_probe_total(self):
        found = problems_for(replaced("ok: 28 probes match", "ok: 27 probes match"))
        self.assertTrue(any("27 probes" in problem for problem in found), found)

    def test_a_stale_executable_conformance_count(self):
        found = problems_for(replaced("224 of 224 executable", "223 of 224 executable"))
        self.assertTrue(any("223 executable" in problem for problem in found), found)

    def test_a_stale_conformance_program_total(self):
        found = problems_for(replaced("across 257 programs", "across 256 programs"))
        self.assertTrue(any("256 conformance programs" in problem for problem in found), found)

    def test_a_refusal_count_that_no_longer_matches_the_rows(self):
        found = problems_for(replaced("all 12 `NOT STARTED` rows exit 1", "all 13 `NOT STARTED` rows exit 1"))
        self.assertTrue(any("13 NOT STARTED rows" in problem for problem in found), found)

    def test_a_conformance_case_that_does_not_exist(self):
        found = problems_for(replaced("conformance `238`", "conformance `999`"))
        self.assertTrue(any("conformance/999" in problem for problem in found), found)

    def test_a_cited_script_that_does_not_exist(self):
        found = problems_for(
            replaced("scripts/probe-non-finite.py", "scripts/probe-does-not-exist.py")
        )
        self.assertTrue(any("does-not-exist" in problem for problem in found), found)

    def test_a_last_verified_pin_that_is_not_a_commit(self):
        found = problems_for(
            replaced(
                "**Last verified:** `d5f6797`",
                "**Last verified:** `0000000`",
            )
        )
        self.assertTrue(any("not a commit in this repository" in p for p in found), found)

    def test_a_last_verified_pin_that_names_no_commit_at_all(self):
        """The shape this file actually had: prose describing a pin instead of a pin."""
        def mutate(text: str) -> str:
            start = text.index("**Last verified:**")
            end = text.index("\n\n", start)
            return (
                text[:start]
                + "**Last verified:** the branch that adds this file, at the commit named in\n"
                + "the pull request that merged it."
                + text[end:]
            )

        found = problems_for(mutate)
        self.assertTrue(any("no reachable 'Last verified' pin" in p for p in found), found)

    def test_a_state_missing_from_the_legend(self):
        def mutate(text: str) -> str:
            legend_end = text.index("**How the capability rows were produced.**")
            return text[:legend_end].replace("`BLOCKED`", "`STUCK`") + text[legend_end:]

        found = problems_for(mutate)
        self.assertTrue(any("BLOCKED is used but absent" in problem for problem in found), found)


class ItDoesNotOverFire(unittest.TestCase):
    def test_a_fenced_transcript_quoting_an_older_measurement_is_not_a_claim(self):
        """A fenced block records what a command printed then; that is history."""
        found = problems_for(
            replaced(
                "## What this matrix does not cover",
                "## What this matrix does not cover\n\n"
                "```\n$ python3 docs/governance/alpha-5/java-bridge-probes/run.py \\\n"
                "ok: 19 probes compared compiled bytecode against the interpreter\n```\n",
            )
        )
        self.assertEqual([], found)

    def test_a_quoted_older_path_is_still_checked_in_prose(self):
        """Backticks are not an exemption here, because inline code carries live evidence."""
        found = problems_for(
            replaced(
                "## What this matrix does not cover",
                "## What this matrix does not cover\n\n"
                "An older pass ran `scripts/probe-gone.py` for this row.\n",
            )
        )
        self.assertTrue(any("probe-gone" in problem for problem in found), found)


if __name__ == "__main__":
    unittest.main()
