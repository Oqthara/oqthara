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
            any("commit reachable from HEAD" in item for item in checker.checked),
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
        self.assertIn("not an ancestor of HEAD", message)

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
        self.assertIn("squash-merged", message)
        self.assertIn("pin the merge commit instead", message)

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


if __name__ == "__main__":
    unittest.main()
