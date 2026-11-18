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
import contextlib
import io
import json
import os
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

PINNED = "deepseek-ai/deepseek-v4.1-flash-1017"

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
        self.bin = pathlib.Path(self._tmp.name) / "bin"
        self.bin.mkdir()
        # Both are global process state. Leaving them set let one test's stand-in `gh` answer for
        # every later test, so a run could pass while an individual test would not.
        self._saved_path = os.environ.get("PATH")
        self._saved_fake_gh = os.environ.get("FAKE_GH")
        self.stub_gh()
        self.root = pathlib.Path(self._tmp.name)
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "t@example.invalid")
        git(self.root, "config", "user.name", "Test")
        # A base commit, then the change on top of it. A fixture with a single commit has that
        # commit as its own merge base, so every citation in it is legitimately "the branch
        # point" and no record can pass -- the fixture would be testing the wrong thing.
        # A root commit, then the branch point, then the change. The extra commit gives the
        # branch point a parent, so a "commit older than the branch point" can be reachable
        # from HEAD and reach the age rule instead of being refused as unreachable.
        (self.root / "README.md").write_text("x\n")
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "root")
        (self.root / "README.md").write_text("x\nx\n")
        git(self.root, "commit", "-qam", "base")
        git(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")
        (self.root / "README.md").write_text("x\ny\n")
        git(self.root, "commit", "-qam", "the change under review")
        self.sha = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.root,
            capture_output=True, text=True,
        ).stdout.strip()
        (self.root / "docs" / "governance" / "reviews").mkdir(parents=True)
        checker.problems.clear()
        checker.checked.clear()

    def tearDown(self):
        self._restore("PATH", self._saved_path)
        self._restore("FAKE_GH", self._saved_fake_gh)
        self._tmp.cleanup()

    @staticmethod
    def _restore(name, value):
        if value is None:
            os.environ.pop(name, None)
        else:
            os.environ[name] = value

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

    # The gate asks `gh` who opened the pull request so an automated author can be exempted.
    # Tests must not depend on a network call or on the runner being authenticated, so a
    # stand-in `gh` is put first on PATH. FAKE_GH selects its answer: a login, or "error".
    def stub_gh(self, answer="some-human"):
        script = self.bin / "gh"
        script.write_text(
            "#!/bin/sh\n"
            f'if [ "$FAKE_GH" = "error" ]; then\n'
            '  echo "gh: authentication required" >&2; exit 1\n'
            "fi\n"
            'echo "$FAKE_GH"\n',
            encoding="utf-8",
        )
        script.chmod(0o755)
        os.environ["FAKE_GH"] = answer
        os.environ["PATH"] = f"{self.bin}{os.pathsep}{os.environ.get('PATH', '')}"


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
            f"fixed and pinned by a test in `{self.sha}` |\n"
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
        self.assertTrue(any("does not name any model" in p for p in problems))

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

    def test_a_deferred_high_finding_is_unresolved(self):
        # A non-empty resolution cell is not a resolution.
        for phrase in ("deferred to a later PR", "won't fix", "TBD", "still open"):
            with self.subTest(phrase=phrase):
                findings = f"| 1 | high | `a.kt:1` | data loss on write | {phrase} |\n"
                self.write(
                    "1",
                    body=GOOD_RECORD.format(number=1, sha=self.sha).replace(
                        "| functional correctness | pass | 0 |",
                        "| functional correctness | pass | 1 |"),
                    findings=findings,
                )
                problems = self.run_gate()
                self.assertTrue(any("unresolved" in p for p in problems), problems)

    def test_a_resolution_without_a_real_commit_is_unresolved(self):
        findings = "| 1 | high | `a.kt:1` | data loss on write | fixed in `deadbee` |\n"
        self.write(
            "1",
            body=GOOD_RECORD.format(number=1, sha=self.sha).replace(
                "| functional correctness | pass | 0 |",
                "| functional correctness | pass | 1 |"),
            findings=findings,
        )
        problems = self.run_gate()
        self.assertTrue(any("unresolved" in p for p in problems), problems)

    def test_a_negative_count_cannot_cancel_a_finding(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| functional correctness | pass | 0 |",
            "| functional correctness | pass | 1 |").replace(
            "| security and privacy | pass | 0 |",
            "| security and privacy | pass | -1 |")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("negative" in p for p in problems), problems)

    def test_a_bare_claim_of_independence_is_rejected(self):
        for claim in ("yes", "yes — trust me", "yes — deepseek-ai/deepseek-v4.1-flash-1017"):
            with self.subTest(claim=claim):
                body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
                    "no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the reviewer\n"
                    "shares the implementer's blind spots (ADR-0059).",
                    claim)
                self.write("1", body=body)
                problems = self.run_gate()
                self.assertTrue(problems, f"'{claim}' should not pass")

    def test_independence_claiming_the_shared_model_is_rejected(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the reviewer\n"
            "shares the implementer's blind spots (ADR-0059).",
            "yes — deepseek-ai/deepseek-v4.1-flash-1017 ran the review on a fresh context, "
            "so no state carried over from the implementation")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("not independent" in p or "every agent" in p for p in problems), problems)

    def test_independence_needs_a_mechanism_not_only_a_model(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the reviewer\n"
            "shares the implementer's blind spots (ADR-0059).",
            "yes — anthropic/claude-sonnet-4.5")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("mechanism" in p for p in problems), problems)

    def test_only_release_integrity_may_be_na(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            "| security and privacy | not applicable | 0 |")
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("only" in p and "release integrity" in p for p in problems))


class ItHandlesAShallowClone(Repository):
    """`--allow-shallow` exists for CI, and must not weaken a clone that has the history."""

    def write_unknown(self):
        self.write("1", body=GOOD_RECORD.format(number=1, sha="0" * 40))

    def gate(self, allow_shallow):
        checker.problems.clear()
        checker.checked.clear()
        checker.validate(self.root, "1", allow_shallow=allow_shallow)
        return list(checker.problems)

    def test_an_unresolvable_commit_is_rejected(self):
        self.write_unknown()
        problems = self.gate(False)
        self.assertTrue(any("not a commit in this repository" in p for p in problems), problems)

    def test_the_flag_does_not_weaken_a_complete_clone(self):
        # The flag is scoped to a shallow clone. If it were honoured everywhere, passing it
        # would turn off the check that the reviewed commit exists -- so this asserts it does
        # not, by using a repository that has its full history.
        self.write_unknown()
        problems = self.gate(True)
        self.assertTrue(any("not a commit in this repository" in p for p in problems), problems)

    def test_a_shallow_clone_cannot_confirm_a_citation_it_can_resolve(self):
        """A SHA the clone happens to contain is still not proof of ancestry in a shallow clone.

        `commit_exists` answers two questions at once. For a SHA that is present it says "yes",
        and for a clone that is shallow it says "but I cannot walk the history". Reading the
        first without the second let an off-branch citation through whenever the SHA happened to
        be the clone tip -- the one commit a depth-1 clone always has.
        """
        shallow = pathlib.Path(self._tmp.name) / "shallowtip"
        subprocess.run(["git", "clone", "-q", "--depth", "1", self.root.as_uri(), str(shallow)],
                       check=True, capture_output=True, text=True)
        self.assertEqual(
            0, subprocess.run(["git", "cat-file", "-e", self.sha], cwd=shallow,
                              capture_output=True).returncode,
            "the clone does not contain the cited commit, so this test proves nothing")
        record = shallow / "docs" / "governance" / "reviews" / "PR-1.md"
        record.parent.mkdir(parents=True)
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| functional correctness | pass | 0 |", "| functional correctness | pass | 1 |")
        record.write_text(body + f"| 1 | high | `a.kt:1` | an unhandled case | fixed in "
                                 f"`{self.sha}` |\n" + FINDINGS_HEADER_END, encoding="utf-8")

        checker.problems.clear()
        checker.checked.clear()
        checker.validate(shallow, "1")
        self.assertTrue(
            any("cannot be checked for ancestry in a shallow clone" in p
                for p in checker.problems), checker.problems)

    def test_a_shallow_clone_needs_the_flag(self):
        old_sha = self.sha
        (self.root / "later.txt").write_text("later\n", encoding="utf-8")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-qm", "later")

        shallow = pathlib.Path(self._tmp.name) / "shallow"
        subprocess.run(["git", "clone", "-q", "--depth", "1", self.root.as_uri(), str(shallow)],
                       check=True, capture_output=True, text=True)
        self.assertNotEqual(
            0, subprocess.run(["git", "cat-file", "-e", old_sha],
                              cwd=shallow, capture_output=True).returncode,
            "the clone is not shallow, so this test proves nothing")

        record = shallow / "docs" / "governance" / "reviews" / "PR-1.md"
        record.parent.mkdir(parents=True)
        record.write_text(GOOD_RECORD.format(number=1, sha=old_sha) + FINDINGS_HEADER_END,
                          encoding="utf-8")

        checker.problems.clear()
        checker.checked.clear()
        checker.validate(shallow, "1")
        self.assertTrue(any("cannot resolve" in p for p in checker.problems), checker.problems)

        checker.problems.clear()
        checker.checked.clear()
        checker.validate(shallow, "1", allow_shallow=True)
        self.assertEqual([], list(checker.problems))


class ItRejectsADeferralDressedAsAResolution(Repository):
    """A critical finding is resolved by a commit, not by a sentence claiming one."""

    def record(self, cell):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            "| security and privacy | pass | 1 |",
        )
        findings = (
            "| 1 | critical | `a.kt:3` | an unchecked redirect | " + cell + " |\n"
        )
        self.write("1", body=body, findings=findings)

    def assert_rejected(self, cell):
        self.record(cell)
        problems = self.run_gate()
        self.assertTrue(any("critical" in p for p in problems), (cell, problems))

    def test_plain_deferral(self):
        self.assert_rejected("deferred")

    def test_deferral_with_a_commit_that_merely_looks_like_evidence(self):
        # The first round's rule compared the whole cell against a set of deferral words, so a
        # cell that also contained a valid SHA passed. The word is what decides.
        self.assert_rejected(f"Deferred; tracking issue 12, investigation in `{self.sha}`")

    def test_deferral_in_other_words(self):
        self.assert_rejected("still open, see the follow-up")

    def test_an_empty_cell(self):
        self.assert_rejected("")

    def test_a_real_resolution_passes(self):
        self.record(f"fixed in `{self.sha}`")
        self.assertEqual([], self.run_gate())


class ItRejectsAUrlAsAModelName(Repository):
    """`MODEL_SLUG` matches `owner/name`, which a URL also looks like."""

    def test_a_url_is_not_a_model(self):
        # A `yes` claim names a model and a mechanism. If a URL counted as a model name, a
        # record could claim independence by pointing at a link, so the case that detects the
        # filter is a `yes` claim whose only `owner/name`-shaped token is a URL.
        original = (
            "**Independent:** no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the "
            "reviewer\nshares the implementer's blind spots (ADR-0059)."
        )
        replacement = (
            "**Independent:** yes — the reviewer ran https://example.com/run/123 which is a "
            "different model from the one the implementer pinned, and the review was conducted "
            "against a fixed diff with no access to the implementer transcript, so the blind "
            "spots were not inherited."
        )
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(original, replacement)
        self.assertNotEqual(GOOD_RECORD.format(number=1, sha=self.sha), body)
        self.write("1", body=body)
        problems = self.run_gate()
        self.assertTrue(any("names no model" in p for p in problems), problems)

    def test_a_bare_domain_is_not_a_model_identifier(self):
        # A scheme-prefixed URL is caught by the `://` check, so only a bare `host/path` token
        # exercises the domain guard. Without this case that guard could be deleted silently.
        self.assertEqual([], checker.models_named("the reviewer ran https://example.com/run"))
        self.assertEqual([], checker.models_named("the reviewer ran example.com/run"))
        self.assertEqual(["openai/gpt-5.1"], checker.models_named("the reviewer ran openai/gpt-5.1"))


class ItAcceptsAnHonestIndependenceClaim(Repository):
    """A `yes` needs a different model and a stated mechanism; both, not one."""

    MECHANISM = (
        "the reviewer was run on a frozen diff with no access to the implementer's summary "
        "or transcript, and was asked to enumerate defects before reading the test names"
    )

    def record(self, statement):
        original = (
            "**Independent:** no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the "
            "reviewer\nshares the implementer's blind spots (ADR-0059)."
        )
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            original, f"**Independent:** {statement}")
        self.assertNotEqual(GOOD_RECORD.format(number=1, sha=self.sha), body)
        self.write("1", body=body)
        return self.run_gate()

    def test_a_different_model_with_a_mechanism_passes(self):
        self.assertEqual([], self.record(f"yes — the reviewer ran anthropic/claude-sonnet-4.5 and {self.MECHANISM}"))

    def test_a_model_the_repository_has_never_configured_is_rejected(self):
        """A named model is only evidence if the repository can say it exists.

        Independence is claimed by naming what the reviewer ran. A name invented for the record
        satisfies every other rule in this file, so without this check the strongest possible
        claim of independence is also the easiest to fabricate.
        """
        problems = self.record(f"yes — ran fictional-vendor/nonexistent-9 and {self.MECHANISM}")
        self.assertTrue(any("has no record of" in p for p in problems), problems)

    def test_a_reviewer_marked_human_is_accepted(self):
        """`human:` is the one identity the gate cannot check, and must stay usable.

        A human reviewer is genuinely independent in a way no agent in this repository is, so
        the allowlist must not close the only honest way to record one.
        """
        problems = self.record(
            "yes — human:maintainer reviewed it, and the review ran against a separate "
            "checkout rather than the implementer's notes")
        self.assertEqual([], problems, problems)

    def test_a_different_model_without_a_mechanism_is_rejected(self):
        problems = self.record("yes — ran anthropic/claude-sonnet-4.5")
        self.assertTrue(any("mechanism" in p for p in problems), problems)

    def test_a_no_claim_may_not_name_a_file_path_as_the_model(self):
        """`no -- see scripts/check-ai-review.py` named a path, and the path passed.

        The `no` answer still has to say what the reviewer ran. Without this rule the same
        invented-name problem exists on the honest path, which is the path most records use.
        """
        problems = self.record("no \u2014 see scripts/check-ai-review.py for the parties involved")
        self.assertTrue(any("not a configured model" in p for p in problems), problems)

    def test_a_no_claim_naming_a_configured_model_is_accepted(self):
        problems = self.record(
            "no \u2014 both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the reviewer "
            "shares the implementer's blind spots")
        self.assertEqual([], problems, problems)

    def test_the_pinned_model_is_not_independence(self):
        problems = self.record(f"yes — ran deepseek-ai/deepseek-v4.1-flash-1017 and {self.MECHANISM}")
        self.assertTrue(any("not " in p and "independent" in p for p in problems), problems)


class ItExemptsAutomatedAuthors(Repository):
    """A bot cannot review its own pull request, so it is exempt -- but only when it is one."""

    def test_dependabot_needs_no_record(self):
        self.stub_gh("dependabot[bot]")
        problems = self.run_gate("5")
        self.assertEqual([], problems)
        self.assertTrue(any("automated" in note for note in checker.checked))

    def test_the_modernised_app_login_is_also_exempt(self):
        # GitHub reports an App-authored pull request as `app/dependabot`, not
        # `dependabot[bot]`. The gate originally listed only the bracketed spelling, so this test
        # passed while the real exemption never matched and every dependency pull request failed
        # a required context -- PR #251 stayed red for that reason alone. The spelling below is
        # the one `gh pr view --json author` actually returns.
        self.stub_gh("app/dependabot")
        problems = self.run_gate("5")
        self.assertEqual([], problems, problems)
        self.assertTrue(any("automated" in note for note in checker.checked))

    def test_renovate_modernised_login_is_also_exempt(self):
        self.stub_gh("app/renovate")
        problems = self.run_gate("5")
        self.assertEqual([], problems, problems)

    def test_an_unrecognised_app_is_not_exempt(self):
        # The exemption is for dependency updaters, not for every App. A workflow token can open
        # a pull request too, and exempting `app/*` wholesale would let such a change merge with
        # no review at all.
        self.stub_gh("app/some-other-automation")
        problems = self.run_gate("5")
        self.assertTrue(any("PR-5.md is missing" in p for p in problems), problems)

    def test_a_human_still_needs_a_record(self):
        self.stub_gh("a-human")
        problems = self.run_gate("5")
        self.assertTrue(any("PR-5.md is missing" in p for p in problems))

    def test_a_failed_lookup_is_reported_as_a_lookup_failure(self):
        # This is the case that made the exemption dead in CI: with contents: read and no
        # token, `gh` fails. The gate must still reject, but it must say why, or a maintainer
        # goes looking for a missing file instead of a missing permission.
        self.stub_gh("error")
        problems = self.run_gate("5")
        self.assertTrue(any("could not determine the author of PR #5" in p for p in problems),
                        problems)
        self.assertFalse(any("PR-5.md is missing" in p for p in problems), problems)


class TheCommandLine(Repository):
    # CI runs this suite on a pull_request event, where GITHUB_EVENT_PATH and GITHUB_REF both
    # name the pull request under test. Inheriting them makes a test that asserts "no number"
    # resolve a number instead, so the environment is scrubbed rather than trusted.
    SCRUBBED = ("GH_PR_NUMBER", "GITHUB_EVENT_PATH", "GITHUB_REF")

    def invoke(self, *args, extra_env=None):
        env = {k: v for k, v in os.environ.items() if k not in self.SCRUBBED}
        env.update(extra_env or {})
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "check-ai-review.py"), *args],
            capture_output=True, text=True, env=env,
        )

    def test_the_gate_reads_the_pull_request_number_from_the_event(self):
        self.write("7")
        event = self.root / "event.json"
        event.write_text('{"pull_request": {"number": 7}}', encoding="utf-8")
        result = self.invoke("--root", str(self.root), extra_env={"GITHUB_EVENT_PATH": str(event)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

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


class ItParsesTheRecordAsWritten(unittest.TestCase):
    """How a cell is split decides which cell the gate reads, so the parser is tested alone."""

    def test_an_escaped_pipe_stays_in_one_cell(self):
        row = "| 1 | high | `a.py` \\| `b.py` | a finding | fixed in `abc1234` |"
        self.assertEqual(
            ["1", "high", "`a.py` | `b.py`", "a finding", "fixed in `abc1234`"],
            checker.split_row(row),
        )

    def test_an_escaped_pipe_does_not_move_the_resolution_cell(self):
        """Without escape handling the resolution lands past the last column the gate reads."""
        markdown = (
            "| # | Severity | Location | Finding | Resolution |\n"
            "| --- | --- | --- | --- | --- |\n"
            "| 1 | high | `a.py` \\| `b.py` | a finding | fixed in `abc1234` |\n"
        )
        header, rows = checker.header_and_rows(checker.tables(markdown)[0])
        self.assertEqual(5, len(rows[0]))
        self.assertEqual("fixed in `abc1234`", rows[0][header.index("resolution")])

    def test_a_fenced_table_is_not_read_as_data(self):
        markdown = (
            "```markdown\n"
            "| Category | Verdict | Findings |\n"
            "| --- | --- | --- |\n"
            "| invented | fail | 99 |\n"
            "```\n"
        )
        self.assertEqual([], checker.tables(markdown))

    def test_a_real_table_after_a_fence_is_still_read(self):
        markdown = (
            "```markdown\n| Category | Verdict | Findings |\n```\n\n"
            "| Category | Verdict | Findings |\n"
            "| --- | --- | --- |\n"
            "| functional correctness | pass | 0 |\n"
        )
        self.assertEqual(1, len(checker.tables(markdown)))


class ItReadsFieldsAsMarkdown(unittest.TestCase):
    """A field is prose, not a fixed byte sequence."""

    def test_the_colon_may_follow_the_bold(self):
        self.assertEqual("#946", checker.check_field("**Pull request**: #946", "Pull request"))

    def test_the_label_is_matched_whatever_its_case(self):
        self.assertEqual("#946", checker.check_field("**PULL REQUEST:** #946", "Pull request"))

    def test_a_placeholder_names_nobody(self):
        for value in ("—", "-", "N/A", "tbd", "Reviewer", "<agent>", "same as above"):
            self.assertFalse(checker.names_a_party(value), value)

    def test_a_named_party_is_accepted(self):
        self.assertTrue(checker.names_a_party("oq-hive-review-escalation"))

    def test_the_first_sha_is_taken_from_surrounding_prose(self):
        for value, expected in (
            ("`ab5fe4f`", "ab5fe4f"),
            ("`ab5fe4f` (round two fixes)", "ab5fe4f"),
            ("`AB5FE4F`", "ab5fe4f"),
            ("reviewed after `0635cc2` and `518cac7`", "0635cc2"),
            ("`ab5fe4f`, `518cac7`", "ab5fe4f"),
        ):
            self.assertEqual(expected, checker.first_commit_sha(value), value)


class ItRecognisesADeferralInAnyWording(unittest.TestCase):
    """The policy no longer promises the list is exhaustive, but the obvious phrasings fail."""

    def test_ordinary_ways_of_saying_not_done_are_caught(self):
        for resolution in (
            "Postponed until the next release",
            "Shelved",
            "Out of scope for this PR",
            "Deferred; tracking issue 12",
            "Won't fix",
            "WILL NOT FIX",
            "Not addressed here",
            "Left for a follow-up",
            "Still open",
            "Will be handled in a later PR",
            "TODO",
            "tbd",
            "No action taken",
        ):
            self.assertTrue(checker.mentions_deferral(resolution), resolution)

    def test_denying_a_deferral_is_not_a_deferral(self):
        for resolution in (
            "This is not a deferral; fixed in `ab5fe4f`",
            "No follow-up needed; fixed in `ab5fe4f`",
            "Never deferred; closed in `ab5fe4f`",
        ):
            self.assertFalse(checker.mentions_deferral(resolution), resolution)

    def test_a_plain_resolution_is_not_treated_as_a_deferral(self):
        for resolution in (
            "fixed in `ab5fe4f`",
            "Removed the check and added a test in `518cac7`",
            "Documented as a known limit in `ab5fe4f`",
        ):
            self.assertFalse(checker.mentions_deferral(resolution), resolution)


class ItRejectsAFindingCitedWithNoEvidence(Repository):
    def test_a_commit_from_another_branch_does_not_resolve_a_finding(self):
        git(self.root, "checkout", "-q", "-b", "elsewhere")
        git(self.root, "commit", "-q", "--allow-empty", "-m", "elsewhere")
        other = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.root, capture_output=True, text=True,
        ).stdout.strip()
        git(self.root, "checkout", "-q", "-")
        self.write(
            10,
            findings=(
                "| 1 | high | `a.py` | a finding | fixed in `%s` |\n" % other[:12]
            ),
        )
        checker.validate(self.root, "10")
        self.assertTrue(any("leaves a high finding unresolved" in p for p in checker.problems))

    def test_a_placeholder_reviewer_is_rejected(self):
        body = GOOD_RECORD.format(number=11, sha=self.sha).replace(
            "**Reviewer:** oq-hive-review-escalation (deepseek-ai/deepseek-v4.1-flash-1017)",
            "**Reviewer:** —",
        )
        self.write(11, body=body)
        checker.validate(self.root, "11")
        self.assertTrue(any("does not name the reviewer" in p for p in checker.problems))

    def test_the_same_party_cannot_be_reviewer_and_implementer(self):
        body = GOOD_RECORD.format(number=12, sha=self.sha).replace(
            "**Reviewer:** oq-hive-review-escalation",
            "**Reviewer:** oq-hive-development",
        )
        self.write(12, body=body)
        checker.validate(self.root, "12")
        self.assertTrue(any("same party as Reviewer" in p for p in checker.problems))

    def test_a_record_with_no_findings_table_is_rejected(self):
        """A prose claim of "no findings" is not a record; the table has to exist."""
        body = GOOD_RECORD.format(number=13, sha=self.sha)
        body = body.split("## Findings")[0] + "## Findings\n\nNo findings.\n"
        self.write(13, body=body)
        checker.validate(self.root, "13")
        self.assertTrue(
            any("no findings table with a severity column" in p for p in checker.problems)
        )


class ItReadsTheRecordTheWayAMarkdownRendererDoes(Repository):
    """Fields and tables come from prose, not from code fences or legends.

    A record is read by humans in a rendered view and by the gate in a plain one. Every rule here
    exists because those two readings disagreed: a value inside a fence looked like a field, and
    a legend table looked like findings.
    """

    def test_a_field_inside_a_fence_does_not_supply_the_value(self):
        """A fenced example must not answer a question the record never answered.

        The policy tells reviewers to paste before/after snippets. If a copy of a correct field
        inside such a snippet satisfied the check, a record could pass without ever stating the
        field -- and the rendered document would show no such line at all.
        """
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "**Independent:** no — both runs pin deepseek-ai/deepseek-v4.1-flash-1017, so the "
            "reviewer\nshares the implementer's blind spots (ADR-0059).",
            "**Independent:**\n\n```markdown\n**Independent:** no — "
            "deepseek-ai/deepseek-v4.1-flash-1017\n```\n")
        self.write("1", body=body)
        self.assertTrue(any("Independent" in p for p in self.run_gate()))

    def test_a_table_inside_a_fence_does_not_supply_the_categories(self):
        """A fenced template is not a completed table."""
        body = GOOD_RECORD.format(number=1, sha=self.sha)
        categories = body[body.index("| Category |"):body.index("## Findings")]
        rows = "".join(
            line + "\n" for line in categories.splitlines() if line.startswith("|")
        )
        self.write("1", body=body.replace(categories, "```markdown\n" + rows + "```\n\n"))
        self.assertTrue(any("no '| Category |" in p for p in self.run_gate()))

    def test_a_findings_table_headed_rank_is_still_findings(self):
        """The header is not the contract; the severity column is.

        Keying on a column *named* `#` let a record rename one header cell and hide an open
        critical finding from the gate while the same table still read as findings to a human.
        """
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| # | Severity |", "| Rank | Severity |").replace(
            "| functional correctness | pass | 0 |",
            "| functional correctness | pass | 1 |")
        self.write("1", body=body,
                   findings="| 1 | critical | `a.kt:1` | unchecked redirect | \u2014 |\n")
        self.assertTrue(any("critical" in p for p in self.run_gate()))

    def test_a_legend_table_is_not_merged_into_the_findings(self):
        """A severity legend has no resolutions, and merging it invents findings.

        Two tables here share a `Severity` column. Treating every one of them as findings made a
        correct record fail on the legend's empty resolution cells.
        """
        body = GOOD_RECORD.format(number=1, sha=self.sha) + textwrap.dedent("""\

            | Severity | Meaning |
            | --- | --- |
            | critical | must be fixed before merge |
            """)
        self.write("1", body=body)
        # A legend is not findings. Merging its rows reported a malformed row and a count
        # mismatch against a record that is entirely correct, so a legitimate record failed.
        self.assertEqual([], self.run_gate())

    def test_a_legend_before_the_findings_does_not_displace_them(self):
        """Order must not decide which table is the findings.

        Taking the first table with a severity column let a legend placed above the real table
        become the findings table, hiding every finding the record actually contained.
        """
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            "| security and privacy | pass | 1 |")
        body = body.replace("## Findings", textwrap.dedent("""\
            | Severity | Meaning |
            | --- | --- |
            | critical | must be fixed before merge |

            ## Findings"""))
        self.write("1", body=body,
                   findings="| 1 | critical | `a.kt:3` | an unchecked redirect | \u2014 |\n")
        problems = self.run_gate()
        self.assertTrue(any("critical" in p for p in problems), problems)

    def test_row_numbers_must_be_present_and_distinct(self):
        """A findings table is numbered so a review can be discussed by finding."""
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| functional correctness | pass | 0 |",
            "| functional correctness | pass | 2 |")
        self.write("1", body=body,
                   findings="| 1 | low | `a.kt:1` | first | accepted |\n"
                            "| 1 | low | `a.kt:2` | second | accepted |\n")
        self.assertTrue(any("number" in p for p in self.run_gate()), self.run_gate())

    def test_a_roll_up_category_table_does_not_shadow_the_real_one(self):
        """A summary table that names categories differently is not a second vote.

        Merging every table whose first cell is `Category` made a correct record with a summary
        fail, and taking only the first match let a summary hide a `fail` verdict.
        """
        body = GOOD_RECORD.format(number=1, sha=self.sha) + textwrap.dedent("""\

            | Category group | Verdict |
            | --- | --- |
            | everything | pass |
            """).replace("Category group", "Category")
        self.write("1", body=body)
        self.assertEqual([], self.run_gate())

    def test_a_total_row_beside_the_categories_is_not_an_unknown_category(self):
        """`| Total | | 55 |` sums the table; it does not add a category to it.

        Rejecting it failed a correct record for stating its own total, which is the number a
        reader checks the table against.
        """
        body = GOOD_RECORD.format(number=1, sha=self.sha) + "| Total | | 0 |\n"
        self.write("1", body=body)
        self.assertEqual([], self.run_gate())


class ItRequiresBothPartiesAndACitedPlaceToStand(Repository):
    """The record has to name two parties and a commit that is actually on the change."""

    def setUp(self):
        super().setUp()
        git(self.root, "update-ref", "refs/remotes/origin/main", self.sha)
        (self.root / "a.kt").write_text("one\n")
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "two")
        self.head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.root,
            capture_output=True, text=True).stdout.strip()

    def test_the_same_party_cannot_be_both_reviewer_and_implementer(self):
        """Separation is the point; one name in both fields records the opposite.

        An earlier version compared the raw strings, so a parenthetical was enough to make one
        agent look like two.
        """
        body = GOOD_RECORD.format(number=1, sha=self.head).replace(
            "**Implementer:** oq-hive-development (deepseek-ai/deepseek-v4.1-flash-1017)",
            "**Implementer:** oq-hive-review-escalation (implementation pass of 17)")
        self.write("1", body=body)
        self.assertTrue(any("party" in p or "differ" in p for p in self.run_gate()))

    def test_the_merge_base_does_not_resolve_a_finding(self):
        """The branch point is not part of the change under review."""
        body = GOOD_RECORD.format(number=1, sha=self.head).replace(
            "| functional correctness | pass | 0 |",
            "| functional correctness | pass | 1 |")
        # `self.sha` is where the branch left main; the commit that made the change is `head`.
        self.write("1", body=body,
                   findings=f"| 1 | high | `a.kt:1` | an unhandled case | fixed in `{self.sha}` |\n")
        problems = self.run_gate()
        self.assertTrue(any("branch point" in p or "merge base" in p for p in problems),
                        problems)

    def test_a_commit_on_no_branch_is_not_evidence(self):
        """A SHA that exists but is unreachable on this branch cannot show this branch fixed it."""
        (self.root / "b.kt").write_text("x\n")
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "elsewhere")
        away = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.root,
                              capture_output=True, text=True).stdout.strip()
        git(self.root, "reset", "-q", "--hard", self.head)
        body = GOOD_RECORD.format(number=1, sha=self.head).replace(
            "| functional correctness | pass | 0 |",
            "| functional correctness | pass | 1 |")
        self.write("1", body=body,
                   findings=f"| 1 | high | `a.kt:1` | an unhandled case | fixed in `{away}` |\n")
        problems = self.run_gate()
        self.assertTrue(any("commit on this branch" in p for p in problems), problems)

    def test_a_review_at_the_merge_base_did_not_read_the_change(self):
        """`Reviewed at` must point inside the change, not at where it branched."""
        self.write("1", body=GOOD_RECORD.format(number=1, sha=self.sha))
        problems = self.run_gate()
        self.assertTrue(any("merge base" in p for p in problems), problems)


class ItRejectsTheOpenPhrasingsTheVocabularyCovers(Repository):
    """Every phrasing below states the finding is still open.

    The list is a vocabulary and the policy says so, so these are the phrasings it must cover --
    not a proof that no wording escapes it.
    """

    def assert_rejected(self, cell):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            "| security and privacy | pass | 1 |")
        self.write("1", body=body,
                   findings="| 1 | high | `a.kt:3` | an unchecked redirect | " + cell + " |\n")
        problems = self.run_gate()
        self.assertTrue(any("high" in p for p in problems), (cell, problems))

    def test_it_rejects_the_phrasings_that_remain_open(self):
        for cell in (
            "remains open",
            "will land in a later pr",
            "known limitation, documented",
            "left as is",
            "accepted risk",
            "handled separately",
            "this is not a deferral",
        ):
            with self.subTest(cell=cell):
                self.assert_rejected(cell)


class ItRejectsAVerdictThatOnlyLooksHarmless(Repository):
    """`fail` anywhere in the verdict cell means the change failed review.

    The exemption for `not applicable` was tested against the whole cell, so
    `fail - not applicable` -- a record stating in its own category table that the change failed
    -- exited 0. The word is what matters, not the exact string.
    """

    def assert_rejected(self, verdict):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            f"| security and privacy | {verdict} | 0 |")
        self.write(1, body=body)
        problems = self.run_gate()
        self.assertTrue(any("failing verdict" in p for p in problems), problems)

    def test_a_failing_verdict_annotated_as_not_applicable_is_rejected(self):
        self.assert_rejected("fail - not applicable")

    def test_a_not_applicable_verdict_annotated_with_fail_is_rejected(self):
        self.assert_rejected("not applicable (fail)")

    def test_a_bare_fail_is_rejected(self):
        self.assert_rejected("fail")

    def test_the_plain_not_applicable_verdict_is_still_accepted(self):
        # The finding count is zero, so "not applicable" is the honest answer for a category
        # this change did not touch, and it must not be caught by the fail scan.
        self.write(1)
        self.assertEqual([], self.run_gate())


class ItCountsFindingsWithDigitsAndNothingElse(Repository):
    """A count is a run of digits, not any Python integer literal.

    `int()` accepts `1_3`, `+13` and Arabic-Indic digits, so a cell that does not read as the
    number it counts was still summed as one.
    """

    def assert_rejected(self, count, expected):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |",
            f"| security and privacy | pass | {count} |")
        self.write(1, body=body)
        problems = self.run_gate()
        # The cell must be refused *as a count*. Asserting only "something was rejected" passed
        # with the guard removed: `int()` read the bad cell as a number, and the record then
        # failed on the unrelated mismatch between that number and the empty findings table.
        self.assertTrue(any(expected in p for p in problems), problems)

    def test_an_underscore_is_not_a_digit(self):
        self.assert_rejected("1_3", "which is not a number")

    def test_a_plus_sign_is_not_a_digit(self):
        self.assert_rejected("+13", "cannot be negative")

    def test_arabic_indic_digits_are_not_ascii_digits(self):
        self.assert_rejected("\u0661\u0663", "which is not a number")


class TheHeadingIsReadFromTheRenderedDocument(Repository):
    """A heading that exists only inside a fence is not the document's heading.

    The check read `raw`, so a record with a fenced example containing `# AI review -- PR #1`
    and no real heading passed -- and a reader saw a document with no title.
    """

    def test_a_heading_inside_a_fence_does_not_count(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "# AI review \u2014 PR #1\n",
            "```\n# AI review \u2014 PR #1\n```\n", 1)
        self.write(1, body=body)
        problems = self.run_gate()
        self.assertTrue(any("no '# AI review" in p for p in problems), problems)

    def test_a_heading_written_with_a_hyphen_is_accepted(self):
        # `# AI review - PR #1` is the same heading; only an em dash was accepted before.
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "# AI review \u2014 PR #1", "# AI review - PR #1", 1)
        self.write(1, body=body)
        self.assertEqual([], self.run_gate())


class ItChecksTheCitedCommitIsAheadOfTheBranchPoint(Repository):
    """A commit that predates the merge base cannot be a fix made on this branch.

    The check used to ask only whether the cited SHA differed from the base, so an ancestor of
    the base -- an older commit on main -- was accepted as evidence.
    """

    def test_a_commit_older_than_the_merge_base_is_rejected(self):
        # The parent of the branch point: it exists, it is an ancestor of HEAD, and it cannot
        # be a fix made on this branch.
        older = subprocess.run(
            ["git", "rev-parse", "origin/main^"], cwd=self.root,
            capture_output=True, text=True).stdout.strip()
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |", "| security and privacy | pass | 1 |")
        self.write(1, body=body, findings=(
            f"| 1 | high | `a.kt:3` | an unhandled empty list | fixed in `{older}` |\n"))
        problems = self.run_gate()
        self.assertTrue(any("merge base" in p for p in problems), problems)


class ItRefusesToCheckAncestryWithoutABase(Repository):
    """A checkout with no `origin/main` must not silently stop checking ancestry.

    `merge_base()` returning `None` meant every `if base:` guard vanished with it, so the whole
    ancestry rule switched itself off in exactly the checkouts -- a bare clone, a fixture, a
    branch built from a local `main` -- where it was most likely to be wrong.
    """

    def setUp(self):
        super().setUp()
        # `merge_base` falls back from `origin/main` to a local `main`, so both have to go.
        # Detaching first is what makes the local branch deletable at all.
        subprocess.run(["git", "checkout", "-q", "--detach"], cwd=self.root,
                       check=True, capture_output=True, text=True)
        for ref in ("refs/remotes/origin/main", "refs/heads/main", "refs/heads/master"):
            subprocess.run(["git", "update-ref", "-d", ref], cwd=self.root,
                           capture_output=True, text=True)
        # Detached HEAD has no branch to fall back to, so `merge_base` now finds nothing.
        self.assertEqual("", subprocess.run(
            ["git", "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes"],
            cwd=self.root, capture_output=True, text=True).stdout.strip())

    def test_a_missing_base_is_a_rejection_and_not_a_pass(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "| security and privacy | pass | 0 |", "| security and privacy | pass | 1 |")
        self.write(1, body=body, findings=(
            f"| 1 | high | `a.kt:3` | an unhandled empty list | fixed in `{self.sha}` |\n"))
        problems = self.run_gate()
        self.assertTrue(any("no `origin/main`" in p for p in problems), problems)


class ThePartiesAreComparedAsParties(Repository):
    """`review-agent the impartial one` is the same party as `review-agent`.

    Keeping the first three words meant one extra word defeated the comparison, so two passes
    of the same run could be presented as the reviewer and the implementer.
    """

    def test_the_second_pass_of_the_same_run_is_the_same_party(self):
        # The order forbids presenting one party as two. Keeping the first three words made
        # `review-agent review pass 17` and `review-agent implementation pass 18` compare as
        # different parties, which is exactly the pair this check exists to catch.
        self.assertEqual(
            checker.party_identity("review-agent review pass 17"),
            checker.party_identity("review-agent implementation pass 18"),
        )

    def test_two_genuinely_different_parties_stay_different(self):
        self.assertNotEqual(
            checker.party_identity("oq-hive-review-escalation (model)"),
            checker.party_identity("oq-hive-development (model)"),
        )

    def test_a_pass_description_does_not_create_a_second_party(self):
        body = GOOD_RECORD.format(number=1, sha=self.sha).replace(
            "**Reviewer:** oq-hive-review-escalation (deepseek-ai/deepseek-v4.1-flash-1017)",
            "**Reviewer:** oq-hive-development review pass 17 "
            "(deepseek-ai/deepseek-v4.1-flash-1017)", 1)
        self.assertIn("review pass 17", body)
        self.write(1, body=body)
        problems = self.run_gate()
        self.assertTrue(any("same party" in p for p in problems), problems)


class TheOverrideCannotValidateSomeoneElsesPullRequest(Repository):
    """Round five's critical finding: `--pr` and `GH_PR_NUMBER` redirected the whole gate.

    Every test above calls `validate` directly, so none of them exercised `main` -- the guard
    that stops the override existed and was unreachable from the suite. Adding `--pr 224` to
    the workflow step would have made every pull request validate #224's record, find it
    well-formed, and keep the required context green. Removing the guard changed nothing the
    tests could see, which is exactly why it went unnoticed.
    """

    def run_main(self, *args):
        """Runs `main` and returns (exit code, stderr) -- the refusal is printed, not collected."""
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = checker.main(["--root", str(self.root), *args])
        return code, err.getvalue()

    def event(self, number):
        path = self.root / "event.json"
        path.write_text(json.dumps({"pull_request": {"number": number}}), encoding="utf-8")
        os.environ["GITHUB_EVENT_PATH"] = str(path)

    def setUp(self):
        super().setUp()
        self._saved_event = os.environ.get("GITHUB_EVENT_PATH")
        self._saved_pr = os.environ.get("GH_PR_NUMBER")
        self.write("224")

    def tearDown(self):
        self._restore("GITHUB_EVENT_PATH", self._saved_event)
        self._restore("GH_PR_NUMBER", self._saved_pr)
        super().tearDown()

    def test_a_local_run_may_name_its_pull_request(self):
        # No event payload: this is the case the override exists for.
        os.environ.pop("GITHUB_EVENT_PATH", None)
        os.environ.pop("GH_PR_NUMBER", None)
        code, err = self.run_main("--pr", "224")
        self.assertEqual(0, code, err)

    def test_a_ci_run_may_not_validate_a_different_pull_request(self):
        self.event(999)
        code, err = self.run_main("--pr", "224")
        self.assertEqual(1, code, err)
        self.assertIn("this run is for pull request #999", err)
        self.assertIn("remove the override", err)

    def test_the_environment_override_is_refused_too(self):
        # The finding named both spellings; guarding one leaves the other wide open.
        self.event(999)
        os.environ["GH_PR_NUMBER"] = "224"
        code, err = self.run_main()
        self.assertEqual(1, code, err)
        self.assertIn("this run is for pull request #999", err)

    def test_agreeing_with_the_event_payload_is_allowed(self):
        # The guard must reject disagreement, not the override in general, or CI -- which
        # passes neither -- would be the only configuration that could ever work.
        self.event(224)
        code, err = self.run_main("--pr", "224")
        self.assertEqual(0, code, err)


if __name__ == "__main__":
    unittest.main()
