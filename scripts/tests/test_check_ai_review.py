#!/usr/bin/env python3
"""Regression tests for scripts/check-ai-review.py.

ADR-0059 makes AI review the review mechanism for this repository and this script is the only
thing enforcing it. A review gate that has only ever passed cannot be told apart from one that
never runs, so each rejection path is exercised here against a throwaway repository:

  * no record at all;
  * a record that is a heading and nothing else;
  * a record that skips one of the seven required categories;
  * a record that marks a category `fail`;
  * a critical finding left unresolved;
  * a finding count that disagrees with the findings table;
  * a missing independence statement, and one that says `no` without naming the model;
  * a reviewed-at commit that does not exist;
  * a record filed under the wrong pull request number.

The passing case is tested too, because a validator that rejects everything is not a gate.
"""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location("check_ai_review", SCRIPTS / "check-ai-review.py")
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

GOOD_RECORD = """\
# AI review — PR #{number}

**Pull request:** #{number}
**Reviewer:** oq-hive-review-escalation (deepseek-ai/deepseek-v4.1-flash-1017)
**Implementer:** oq-hive-development (deepseek-ai/deepseek-v4.1-flash-1017)
**Reviewed at:** `{sha}`
**Independent:** no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the reviewer
shares the implementer's blind spots (ADR-0059).

## Categories

| Category | Verdict | Findings |
| --- | --- | --- |
| functional correctness | pass | 0 |
| architecture and maintainability | pass | 0 |
| security and privacy | pass | 0 |
| regression and compatibility | pass | 0 |
| test coverage and validation | pass | 0 |
| documentation accuracy | pass | 0 |
| release integrity | not applicable | 0 |

## Findings

| # | Severity | Location | Finding | Resolution |
| --- | --- | --- | --- | --- |
"""

FINDINGS_HEADER_END = """\

## What was examined

`git diff`, the test run, and `scripts/check-docs-sync.py`.
"""


def git(root, *args):
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True)


class Repository(unittest.TestCase):
    """A throwaway repository with one commit, so 'Reviewed at' can name a real SHA."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "t@example.invalid")
        git(self.root, "config", "user.name", "Test")
        (self.root / "README.md").write_text("x\n")
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "one")
        self.sha = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.root,
            capture_output=True, text=True,
        ).stdout.strip()
        (self.root / "docs" / "governance" / "reviews").mkdir(parents=True)
        checker.problems.clear()
        checker.checked.clear()

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, number, body=None, findings="", filename=None):
        text = (body if body is not None else GOOD_RECORD.format(number=number, sha=self.sha))
        text += findings + FINDINGS_HEADER_END
        path = self.root / "docs" / "governance" / "reviews" / (filename or f"PR-{number}.md")
        path.write_text(text)
        return path

    def run_gate(self, number="1"):
        checker.problems.clear()
        checker.checked.clear()
        checker.validate(self.root, number)
        return checker.problems


class ItPassesOnAWellFormedRecord(Repository):
    def test_no_findings(self):
        self.write("1")
        self.assertEqual([], self.run_gate())

    def test_it_says_what_it_read(self):
        self.write("1")
        self.run_gate()
        self.assertTrue(any("well-formed review record" in note for note in checker.checked))

    def test_resolved_high_finding(self):
        findings = (
            "| 1 | high | `a.kt:3` | an unhandled empty list | "
            "fixed and pinned by a test in `abc1234` |\n"
        )
        self.write(
            "1",
            body=GOOD_RECORD.format(number=1, sha=self.sha).replace(
                "| functional correctness | pass | 0 |",
                "| functional correctness | pass | 1 |",
            ),
            findings=findings,
        )
        self.assertEqual([], self.run_gate())


class ItRejects(Repository):
    def test_missing_record(self):
        problems = self.run_gate("7")
        self.assertTrue(any("PR-7.md is missing" in p for p in problems))

    def test_heading_only(self):
        self.write("1", body="# AI review — PR #1\n")
        self.assertTrue(self.run_gate())

    def test_too_short_record_cannot_pass(self):
        # The failure mode this gate exists to catch: a record that exists but says nothing.
        self.write("1", body=textwrap.dedent("""\
            # AI review — PR #1

            **Pull request:** #1
            **Reviewer:** someone
            **Implementer:** someone
            **Reviewed at:** `%s`
            **Independent:** no — deepseek-ai/deepseek-v4.1-flash-1017
        """) % self.sha)
        self.assertTrue(self.run_gate())

    def test_missing_category(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |\n", "")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("security and privacy" in p for p in problems))

    def test_failing_verdict(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| documentation accuracy | pass | 0 |",
            "| documentation accuracy | fail | 0 |")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("failing verdict" in p for p in problems))

    def test_unresolved_critical(self):
        findings = "| 1 | critical | `a.kt:1` | data loss on write | — |\n"
        self.write(
            "1",
            body=GOOD_RECORD.format(number=1, sha=self.sha).replace(
                "| functional correctness | pass | 0 |",
                "| functional correctness | pass | 1 |"),
            findings=findings,
        )
        problems = self.run_gate()
        self.assertTrue(any("unresolved" in p for p in problems))

    def test_count_disagrees_with_table(self):
        findings = "| 1 | low | `a.kt:1` | a nit | accepted |\n"
        self.write("1", findings=findings)  # category table says 0 findings
        problems = self.run_gate()
        self.assertTrue(any("lists 1" in p for p in problems))

    def test_missing_independence(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha)
        body = "\n".join(
            line for line in body.splitlines()
            if not line.startswith("**Independent:**") and "shares the implementer" not in line
        ) + "\n"
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("Independent" in p for p in problems))

    def test_independence_without_model(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the reviewer\n"
            "shares the implementer's blind spots (ADR-0059).",
            "no")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("does not name the shared model" in p for p in problems))

    def test_unknown_reviewed_commit(self):
        body = GOOD_RECORD.format(number=1, sha="0" * 40)
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("not a commit in this repository" in p for p in problems))

    def test_wrong_pull_request_number(self):
        self.write("1")
        problems = self.run_gate("2")
        self.assertTrue(any("PR-2.md is missing" in p for p in problems))

    def test_heading_disagrees_with_filename(self):
        self.write("2", filename="PR-2.md", body=GOOD_RECORD.format(number=9, sha=self.sha))
        problems = self.run_gate("2")
        self.assertTrue(any("heading says PR #9" in p for p in problems))

    def test_only_release_integrity_may_be_na(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            "| security and privacy | not applicable | 0 |")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("only" in p and "release integrity" in p for p in problems))


class TheCommandLine(Repository):
    def invoke(self, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "check-ai-review.py"), *args],
            capture_output=True, text=True,
        )

    def test_exit_zero_when_the_record_is_good(self):
        self.write("1")
        result = self.invoke("--pr", "1", "--root", str(self.root))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_exit_one_when_the_record_is_missing(self):
        result = self.invoke("--pr", "5", "--root", str(self.root))
        self.assertEqual(1, result.returncode)
        self.assertIn("PR-5.md is missing", result.stdout)

    def test_exit_two_without_a_pull_request_number(self):
        result = self.invoke("--root", str(self.root))
        self.assertEqual(2, result.returncode)
        self.assertIn("no pull request number", result.stderr)

    def test_exit_two_for_a_non_numeric_number(self):
        result = self.invoke("--pr", "main", "--root", str(self.root))
        self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
