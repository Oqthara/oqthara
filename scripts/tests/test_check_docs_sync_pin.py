#!/usr/bin/env python3
"""Regression tests for the "Last verified" pin check in scripts/check-docs-sync.py.

The pin in the requirement matrix is a promise that someone re-read the matrix
against the tree at that commit. Nothing enforced it, so it silently drifted two
merges behind: it still named `27cac46` while `main` was at `2dc3890`. A stale SHA
is worse than no SHA, because a reader treats it as current evidence.

The check has two failure modes and both are tested here, because a check that has
only ever passed cannot be told apart from one that never runs:

  * a SHA no object in this repository matches (a typo, or a commit from a
    rewritten history);
  * a SHA that exists but is not an ancestor of HEAD (a commit on an abandoned or
    never-merged branch -- it exists, so `rev-parse` succeeds, and only the
    ancestry test rejects it).
"""
import importlib.util
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location("check_docs_sync", SCRIPTS / "check-docs-sync.py")
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

MATRIX = SCRIPTS.parent / "docs" / "governance" / "alpha-4-requirement-matrix.md"
PIN = re.compile(r"^\*\*Last verified:\*\* `([0-9a-f]{7,40})`", re.M)


class TheRealMatrixPinsARealCommit(unittest.TestCase):
    def test_it_passes(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.requirement_matrix_verification_pin_is_real()
        self.assertEqual([], checker.problems)

    def test_it_says_what_it_read(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.requirement_matrix_verification_pin_is_real()
        self.assertTrue(
            any("commit reachable from refs/remotes/origin/main" in item
                or "commit reachable from HEAD" in item for item in checker.checked),
            "a passing check must name the commit it verified",
        )


class BadPinsAreRejected(unittest.TestCase):
    def setUp(self):
        self.original = MATRIX.read_text(encoding="utf-8")
        self.sha = PIN.search(self.original).group(1)
        checker.problems.clear()
        checker.checked.clear()

    def tearDown(self):
        MATRIX.write_text(self.original, encoding="utf-8")

    def _pin_to(self, sha):
        MATRIX.write_text(
            PIN.sub(f"**Last verified:** `{sha}`", self.original, count=1), encoding="utf-8"
        )
        checker.problems.clear()
        checker.checked.clear()
        checker.requirement_matrix_verification_pin_is_real()
        return " ".join(checker.problems)

    def test_a_sha_that_matches_no_commit_is_rejected(self):
        message = self._pin_to("deadbee1")
        self.assertIn("not a commit in this repository", message)

    def test_a_commit_that_is_not_an_ancestor_is_rejected(self):
        # A real object that is not on this branch, so `rev-parse` succeeds and only the
        # ancestry test can reject it. Skipped where the object is absent, because then the
        # first failure mode applies and this test would silently prove nothing.
        probe = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", "HEAD~1"],
            cwd=SCRIPTS.parent, capture_output=True, text=True,
        )
        if probe.returncode != 0:
            self.skipTest("no parent commit to use as an unreachable probe")
        orphan = subprocess.run(
            ["git", "commit-tree", probe.stdout.strip() + "^{tree}", "-m", "probe"],
            cwd=SCRIPTS.parent, capture_output=True, text=True,
        )
        if orphan.returncode != 0:
            self.skipTest("cannot create a probe commit tree here")
        message = self._pin_to(orphan.stdout.strip())
        self.assertIn("not an ancestor of", message)
        self.assertIn("default branch", message)

    def test_the_message_names_squash_merge_as_the_likely_cause(self):
        """The pin can break *after* the branch is merged, on `main`, where it blocks everyone.

        A pin written to the commit that did the verifying is unreachable from the default
        branch as soon as the branch is squash-merged, because squash rewrites the branch into
        one new commit and discards the original SHAs. That happened to this repository: PR #205
        passed every check and then main's documentation gate failed on the merge commit. The
        ancestry failure is the symptom; squash-merge is the cause, and a message that only says
        "not an ancestor" sends the reader looking for a typo. This test pins the diagnosis.
        """
        probe = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", "HEAD~1"],
            cwd=SCRIPTS.parent, capture_output=True, text=True,
        )
        if probe.returncode != 0:
            self.skipTest("no parent commit to use as an unreachable probe")
        orphan = subprocess.run(
            ["git", "commit-tree", probe.stdout.strip() + "^{tree}", "-m", "probe"],
            cwd=SCRIPTS.parent, capture_output=True, text=True,
        )
        if orphan.returncode != 0:
            self.skipTest("cannot create a probe commit tree here")
        message = self._pin_to(orphan.stdout.strip())
        self.assertIn("squash-merge", message)
        self.assertIn("Pin the merge commit instead", message)

    def test_a_missing_pin_is_rejected(self):
        MATRIX.write_text(PIN.sub("**Last verified:** none", self.original, count=1),
                          encoding="utf-8")
        checker.problems.clear()
        checker.requirement_matrix_verification_pin_is_real()
        self.assertIn("no 'Last verified' pin", " ".join(checker.problems))

    def test_the_real_pin_still_passes_after_the_round_trip(self):
        self._pin_to(self.sha)
        self.assertEqual([], checker.problems)


class AShallowCloneDoesNotCondemnAGoodPin(unittest.TestCase):
    """CI checks out depth 1, where the pinned commit is not an object.

    The check used to report "not a commit in this repository" there, which turned a
    correct pin into a red build -- it did exactly that on the first CI run. The
    distinction that matters is "not fetched" versus "does not exist", and only
    `git rev-parse --is-shallow-repository` can tell them apart.
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        clone = subprocess.run(
            ["git", "clone", "--quiet", "--depth", "1",
             pathlib.Path(checker.ROOT).as_uri(), self.tmp + "/shallow"],
            capture_output=True, text=True,
        )
        if clone.returncode != 0:
            shutil.rmtree(self.tmp, ignore_errors=True)
            self.skipTest(f"cannot clone here: {clone.stderr.strip()}")
        self.repo = pathlib.Path(self.tmp) / "shallow"
        shallow = subprocess.run(
            ["git", "rev-parse", "--is-shallow-repository"],
            cwd=self.repo, capture_output=True, text=True,
        ).stdout.strip()
        if shallow != "true":
            shutil.rmtree(self.tmp, ignore_errors=True)
            self.skipTest("clone turned out not to be shallow")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_a_pin_the_clone_lacks_is_reported_as_unfetched(self):
        original_root = checker.ROOT
        checker.ROOT = self.repo
        try:
            matrix = self.repo / "docs" / "governance" / "alpha-4-requirement-matrix.md"
            text = matrix.read_text(encoding="utf-8")
            # A commit that cannot exist in a depth-1 clone, but is well formed.
            matrix.write_text(PIN.sub("**Last verified:** `0badc0de`", text, count=1),
                              encoding="utf-8")
            checker.problems.clear()
            checker.checked.clear()
            checker.requirement_matrix_verification_pin_is_real()
        finally:
            checker.ROOT = original_root

        self.assertEqual([], checker.problems,
                         "a shallow clone must not condemn a pin it simply has not fetched")
        self.assertTrue(
            any("shallow clone cannot confirm" in item for item in checker.checked),
            "the check must say plainly that it could not confirm the pin",
        )


class TheMatrixCitationsAreChecked(unittest.TestCase):
    """A `DONE` row must cite evidence a reader can open, in either citation style."""

    def setUp(self):
        self.original = MATRIX.read_text(encoding="utf-8")
        checker.problems.clear()
        checker.checked.clear()

    def tearDown(self):
        MATRIX.write_text(self.original, encoding="utf-8")

    def run_check(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.requirement_matrix_evidence_exists()
        return " ".join(checker.problems)

    def test_a_dead_backticked_citation_is_rejected(self):
        # Every §4.2 citation is backticked, and a check that read only markdown links would
        # have passed all of them without looking.
        broken = self.original.replace("`scripts/check-ai-review.py`",
                                       "`scripts/check-ai-review-renamed.py`", 1)
        self.assertNotEqual(self.original, broken)
        MATRIX.write_text(broken, encoding="utf-8")
        self.assertIn("check-ai-review-renamed.py", self.run_check())

    def test_a_dead_linked_citation_is_rejected(self):
        broken = self.original.replace("(../adr/0059-ai-review-as-the-review-mechanism.md)",
                                       "(../adr/0059-missing.md)", 1)
        self.assertNotEqual(self.original, broken)
        MATRIX.write_text(broken, encoding="utf-8")
        self.assertIn("0059-missing.md", self.run_check())

    def test_a_fragment_does_not_hide_the_file(self):
        # `file.md#section` names a file that exists; the fragment must be stripped rather
        # than treated as part of the path.
        self.assertNotIn("does not exist", self.run_check())

    def test_a_conformance_case_is_resolved_by_number(self):
        # `conformance/228` is a test number, stored as `conformance/228-<slug>.oq`.
        self.assertNotIn("conformance/228", self.run_check())

    def test_evidence_missing_at_the_pin_is_rejected(self):
        # This was a `note` until PR #225: a pin could name a commit where a DONE row's
        # evidence is absent and the check still exited 0, while the matrix prose claimed the
        # pin was "the first commit at which every row's evidence is present". If the prose
        # says it, the check must enforce it.
        #
        # An earlier version of this test pinned to the pin's parent and skipped when that
        # happened to carry the same citations, so deleting the guard left it green. The
        # repository's root commit is used instead: it predates ADR-0059, the gate and every
        # other citation a DONE row makes, and it is guaranteed to exist in any full clone.
        root = subprocess.run(["git", "rev-list", "--max-parents=0", "HEAD"],
                              cwd=SCRIPTS.parent, capture_output=True, text=True
                              ).stdout.split()
        if not root:
            self.skipTest("not a git checkout")
        before = MATRIX.read_text(encoding="utf-8")
        MATRIX.write_text(PIN.sub(f"**Last verified:** `{root[-1]}`", before, count=1),
                          encoding="utf-8")
        problems = self.run_check()
        self.assertIn("does not exist at the pin", problems)
        self.assertIn("0059-ai-review-as-the-review-mechanism.md", problems)

    def test_evidence_declared_in_flight_is_reported_but_allowed(self):
        # The escape hatch must be a named list of files, not a general exemption, or it
        # becomes the silent pass this check was just fixed to stop being.
        self.assertEqual(frozenset(), checker.PENDING_EVIDENCE,
                         "an entry here ships a matrix that is knowingly wrong at its own pin")


class QuotedTestCountsAreChecked(unittest.TestCase):
    """Every way a document states a test class's size must agree with the class.

    Three phrasings were in use and only two were validated, so the requirement matrix's
    "N tests in `X`" style drifted three times at once -- `RuntimeCompatibilityTest` said
    nine against eleven, `RuntimeInstallerTest` fourteen against sixteen, and
    `SafeArchiveTarTest` thirteen against fifteen. The unvalidated phrasing was the one
    the matrix actually used.
    """

    def setUp(self):
        self.original = MATRIX.read_text(encoding="utf-8")
        checker.problems.clear()
        checker.checked.clear()

    def tearDown(self):
        MATRIX.write_text(self.original, encoding="utf-8")

    def run_check(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.quoted_test_counts_agree()
        return " ".join(checker.problems)

    def test_the_real_tree_agrees(self):
        self.assertEqual("", self.run_check())

    def test_a_wrong_count_in_the_matrix_phrasing_is_rejected(self):
        # The phrasing the matrix uses, and the one that was unguarded.
        MATRIX.write_text(self.original + "\n99 tests in `RuntimeCompatibilityTest`.\n",
                          encoding="utf-8")
        self.assertIn("RuntimeCompatibilityTest has 99 tests", self.run_check())

    def test_a_wrong_count_in_the_parenthesised_phrasing_is_rejected(self):
        MATRIX.write_text(self.original + "\n`RuntimeCompatibilityTest` (99 tests).\n",
                          encoding="utf-8")
        self.assertIn("RuntimeCompatibilityTest has 99 tests", self.run_check())

    def test_a_wrong_count_in_the_now_holds_phrasing_is_rejected(self):
        MATRIX.write_text(self.original
                          + "\n`RuntimeCompatibilityTest`, which now holds 99.\n",
                          encoding="utf-8")
        self.assertIn("RuntimeCompatibilityTest has 99 tests", self.run_check())

    def test_a_count_for_a_class_that_does_not_exist_is_rejected(self):
        # Otherwise the count is vacuous and the check reports success over a typo.
        MATRIX.write_text(self.original + "\n11 tests in `RuntimeCompatabilityTest`.\n",
                          encoding="utf-8")
        self.assertIn("RuntimeCompatabilityTest", self.run_check())

    def test_a_subset_citation_is_not_read_as_a_total(self):
        # "the 4 tests in `X` that drive the interpreter" selects a subset. The definite
        # article is what distinguishes it from a claim about the class's size, and treating
        # it as a wrong total would make the check impossible to satisfy honestly.
        MATRIX.write_text(
            self.original
            + "\nThe 4 tests in `RuntimeCompatibilityTest` that run it are the guard.\n",
            encoding="utf-8")
        self.assertEqual("", self.run_check())


class APinMustBeOnTheDefaultBranch(unittest.TestCase):
    """A pin to a commit that exists only on the branch proposing it is not a resolvable pin.

    Ancestry was tested against `HEAD`, which on a pull request is the branch under review.
    A pin to one of that branch's own commits therefore passed, and the guard only fired after
    the squash-merge, on `main` -- by which point the pin could no longer be corrected in the
    pull request that wrote it. PR #225 found this by pinning to its own commit and watching
    the check exit 0.
    """

    def setUp(self):
        self.original = MATRIX.read_text(encoding="utf-8")
        checker.problems.clear()
        checker.checked.clear()

    def tearDown(self):
        MATRIX.write_text(self.original, encoding="utf-8")

    def test_a_branch_only_commit_is_not_an_acceptable_pin(self):
        own = subprocess.run(["git", "rev-parse", "HEAD"], cwd=SCRIPTS.parent,
                             capture_output=True, text=True).stdout.strip()
        if not own:
            self.skipTest("not a git checkout")
        branch = subprocess.run(["git", "merge-base", "--is-ancestor", own, "origin/main"],
                                cwd=SCRIPTS.parent, capture_output=True).returncode == 0
        if branch:
            self.skipTest("HEAD is already on origin/main; nothing branch-only to pin to")
        MATRIX.write_text(PIN.sub(f"**Last verified:** `{own}`", self.original, count=1),
                          encoding="utf-8")
        checker.problems.clear()
        checker.requirement_matrix_verification_pin_is_real()
        self.assertTrue(
            any("not an ancestor of" in p for p in checker.problems), checker.problems)
        self.assertIn("default branch", " ".join(checker.problems))


class ReviewRecordsAreNotInTheDocsCatalog(unittest.TestCase):
    """Review records change with every pull request and would make the catalog stale."""

    def test_the_catalog_guard_reads_the_documents(self):
        catalog = checker.json.loads((SCRIPTS.parent / "docs" / "catalog.json").read_text())
        self.assertIn("docs", catalog, "the guard reads catalog['docs']; the key must exist")
        self.assertTrue(catalog["docs"], "an empty list would make the guard a no-op")
        checker.problems.clear()
        checker.checked.clear()
        checker.review_records_are_not_in_the_docs_catalog()
        self.assertEqual([], checker.problems)

    def test_the_guard_fails_when_a_review_record_is_catalogued(self):
        """The previous version of this test passed with the guard neutered.

        It asserted only that the key existed, so returning early from the guard still passed.
        This one edits the catalog to contain a review record and requires a problem.
        """
        path = SCRIPTS.parent / "docs" / "catalog.json"
        original = path.read_text(encoding="utf-8")
        try:
            catalog = checker.json.loads(original)
            catalog["docs"].append(
                {"path": "docs/governance/reviews/PR-224.md", "title": "PR 224 review"}
            )
            path.write_text(checker.json.dumps(catalog), encoding="utf-8")
            checker.problems.clear()
            checker.checked.clear()
            checker.review_records_are_not_in_the_docs_catalog()
            self.assertTrue(
                checker.problems,
                "a catalogued review record must be reported; a guard that returns early "
                "would let a record churn the catalog on every review",
            )
        finally:
            path.write_text(original, encoding="utf-8")


class ReviewCategoriesMatchTheValidator(unittest.TestCase):
    def test_a_renamed_category_is_rejected(self):
        policy = SCRIPTS.parent / "docs" / "governance" / "ai-review-policy.md"
        original = policy.read_text(encoding="utf-8")
        try:
            renamed = original.replace("| functional correctness |",
                                       "| functional correctness (behaviour) |", 1)
            self.assertNotEqual(original, renamed)
            policy.write_text(renamed, encoding="utf-8")
            checker.problems.clear()
            checker.checked.clear()
            checker.ai_review_categories_match_the_validator()
            self.assertTrue(any("functional correctness" in p for p in checker.problems),
                            checker.problems)
        finally:
            policy.write_text(original, encoding="utf-8")

    def test_the_policy_and_template_agree_with_the_validator(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.ai_review_categories_match_the_validator()
        self.assertEqual([], checker.problems)


class TheGateStepCannotBeMadeInert(unittest.TestCase):
    """A step that is present but inert satisfies every substring test.

    `continue-on-error: true` and `if: false` leave both the script name and the required context
    in the file while the gate cannot fail the build, so the required status stays green with no
    review recorded. These mutate the real workflow and restore it.
    """

    CI = SCRIPTS.parent / ".github" / "workflows" / "ci.yml"

    def setUp(self):
        self.original = self.CI.read_text(encoding="utf-8")
        checker.problems.clear()
        checker.checked.clear()
        self.assertEqual("", self._run(), "the unmodified workflow must be accepted")

    def tearDown(self):
        self.CI.write_text(self.original, encoding="utf-8")

    def _run(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.ai_review_gate_is_wired_into_ci()
        return "\n".join(checker.problems)

    def test_continue_on_error_is_rejected(self):
        # Parsed as YAML this is the boolean True, not the string "true", and an allowlist of
        # strings let it through.
        broken = self.original.replace(
            "      - name: Check the pull request has an AI review record",
            "      - name: Check the pull request has an AI review record\n        continue-on-error: true", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("continue-on-error", self._run())

    def test_a_constant_false_condition_is_rejected(self):
        # The step already carries a matrix condition, which is legitimate and is how it binds to
        # the required context. Appending a second `if:` would let YAML's last-key-wins rule keep
        # the real one, so the existing condition is replaced instead.
        broken = self.original.replace(
            "        if: matrix.os == 'ubuntu-latest' && matrix.java == '21'\n"
            "        # ADR-0059 makes AI review",
            "        if: false\n        # ADR-0059 makes AI review", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("if:", self._run())

    def test_removing_the_step_is_rejected(self):
        broken = self.original.replace("scripts/check-ai-review.py", "scripts/missing.py")
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("check-ai-review.py", self._run())

    def test_a_redirected_pull_request_number_is_rejected(self):
        # `--pr 224` outranks the event payload, so one line makes every future pull request
        # validate PR #224's record.
        broken = self.original.replace(
            "          python3 scripts/check-ai-review.py\n",
            "          python3 scripts/check-ai-review.py --pr 224\n", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("--pr", self._run())

    def test_a_literal_pull_request_number_is_rejected(self):
        broken = self.original.replace(
            "GH_PR_NUMBER: ${{ github.event.pull_request.number }}",
            "GH_PR_NUMBER: 224", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("GH_PR_NUMBER", self._run())

    def test_a_swallowed_exit_status_is_rejected(self):
        broken = self.original.replace(
            "          python3 scripts/check-ai-review.py\n",
            "          python3 scripts/check-ai-review.py || echo ok\n", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("||", self._run())

    def test_echoing_the_gate_is_rejected(self):
        # The script name is still in the file, on a line of its own, and executed by nothing.
        broken = self.original.replace(
            "          python3 scripts/check-ai-review.py",
            "          echo python3 scripts/check-ai-review.py", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("check-ai-review.py", self._run())

    def test_the_gate_moved_out_of_the_required_job_is_rejected(self):
        # A string search cannot see this: every required literal stays, and the gate runs in a
        # job the ruleset does not require. The step is *moved* rather than copied -- copying it
        # leaves one running in the required job, which is not a regression.
        lines = self.original.splitlines(keepends=True)
        start = next(i for i, line in enumerate(lines)
                     if line.strip() == "- name: Check the pull request has an AI review record")
        end = start + 1
        while end < len(lines) and not lines[end].strip().startswith("- name:"):
            end += 1
        moved = lines[start:end]
        remainder = lines[:start] + lines[end:]
        broken = "".join(remainder).replace(
            "jobs:\n", "jobs:\n  spare:\n    runs-on: ubuntu-latest\n    steps:\n", 1)
        marker = broken.index("    steps:\n", broken.index("  spare:\n")) + len("    steps:\n")
        broken = broken[:marker] + "".join(moved) + broken[marker:]
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("no job reporting", self._run())

    def test_a_job_level_continue_on_error_is_rejected(self):
        broken = self.original.replace(
            "  test:\n", "  test:\n    continue-on-error: true\n", 1)
        self.assertNotEqual(self.original, broken)
        self.CI.write_text(broken, encoding="utf-8")
        self.assertIn("continue-on-error", self._run())


if __name__ == "__main__":
    unittest.main()
