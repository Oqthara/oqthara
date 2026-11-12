#!/usr/bin/env python3
"""Regression tests for the release gate's pass/fail decision rules.

These tests never touch the network. They pin two properties that the gate must
keep or it stops being a gate:

  * macOS evidence may arrive from either provider, because GitHub Actions is
    primary and CircleCI is the fallback (docs/adr/0049-macos-ci-on-github-actions.md);
  * a failure from either provider is fatal, so a green run on one provider can
    never mask a red run on the other.

The second property is the one worth protecting: the obvious implementation of
"accept either" is ``any(success)``, which would silently pass a release whose
CircleCI macOS job failed while the GitHub one succeeded.
"""
import importlib.util
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

spec = importlib.util.spec_from_file_location(
    "check_circleci",
    pathlib.Path(__file__).resolve().parent.parent / "check-circleci.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

JAZZER = "ci/circleci: jazzer-fuzz"
GH_21 = "macos-latest / JDK 21"
GH_25 = "macos-latest / JDK 25"
CI_21 = "ci/circleci: macOS / JDK 21"
CI_25 = "ci/circleci: macOS / JDK 25"


def statuses(**states):
    return {
        context: {"context": context, "state": state, "target_url": "https://example.test"}
        for context, state in states.items()
    }


def satisfied(states):
    """The minimal all-green map, with macOS taken from GitHub Actions."""
    base = {JAZZER: "success", GH_21: "success", GH_25: "success"}
    base.update(states)
    return statuses(**base)


class GroupStateTests(unittest.TestCase):
    def test_github_actions_satisfies_the_macos_requirement(self):
        state, context = gate._group_state(statuses(**{GH_21: "success"}), (GH_21, CI_21))
        self.assertEqual("success", state)
        self.assertEqual(GH_21, context)

    def test_circleci_still_satisfies_the_macos_requirement(self):
        # The fallback path must keep working: if the GitHub macOS runner is
        # unavailable, CircleCI is the only source of that evidence.
        state, context = gate._group_state(statuses(**{CI_21: "success"}), (GH_21, CI_21))
        self.assertEqual("success", state)
        self.assertEqual(CI_21, context)

    def test_either_provider_failing_fails_the_group(self):
        # This is the anti-masking rule.
        state, _ = gate._group_state(
            statuses(**{GH_21: "success", CI_21: "failure"}), (GH_21, CI_21))
        self.assertEqual("failed", state)

    def test_a_failure_wins_even_when_it_is_the_second_member(self):
        state, _ = gate._group_state(
            statuses(**{CI_21: "error", GH_21: "success"}), (GH_21, CI_21))
        self.assertEqual("failed", state)

    def test_absent_evidence_is_waiting_not_success(self):
        state, _ = gate._group_state(statuses(), (GH_21, CI_21))
        self.assertEqual("waiting", state)

    def test_one_provider_pending_leaves_the_group_waiting(self):
        state, _ = gate._group_state(statuses(**{GH_21: "pending"}), (GH_21, CI_21))
        self.assertEqual("waiting", state)


class EvaluateTests(unittest.TestCase):
    def test_all_requirements_green_on_github_passes(self):
        self.assertEqual("success", gate.evaluate(satisfied({})))

    def test_circleci_only_macos_fallback_passes(self):
        self.assertEqual(
            "success", gate.evaluate(statuses(**{JAZZER: "success", CI_21: "success", CI_25: "success"})))

    def test_missing_jazzer_blocks(self):
        self.assertEqual("waiting", gate.evaluate(statuses(**{GH_21: "success", GH_25: "success"})))

    def test_a_red_circleci_macos_job_fails_despite_green_github(self):
        # End-to-end form of the anti-masking rule, at the level main() consults.
        self.assertEqual("failed", gate.evaluate(satisfied({CI_21: "failure"})))

    def test_pending_is_not_success(self):
        self.assertEqual("waiting", gate.evaluate(satisfied({GH_25: "pending"})))


class CheckRunStateTests(unittest.TestCase):
    """`_check_run_state` translates Actions conclusions into gate vocabulary."""

    def test_completed_success(self):
        self.assertEqual(
            "success", gate._check_run_state({"status": "completed", "conclusion": "success"}))

    def test_in_progress_is_pending(self):
        self.assertEqual(
            "pending", gate._check_run_state({"status": "in_progress", "conclusion": None}))

    def test_skipped_is_not_evidence_of_success(self):
        # A skipped job proves nothing about the platform it was meant to cover.
        self.assertEqual(
            "pending", gate._check_run_state({"status": "completed", "conclusion": "skipped"}))

    def test_neutral_is_not_evidence_of_success(self):
        self.assertEqual(
            "pending", gate._check_run_state({"status": "completed", "conclusion": "neutral"}))

    def test_timed_out_is_failure(self):
        self.assertEqual(
            "failure", gate._check_run_state({"status": "completed", "conclusion": "timed_out"}))

    def test_cancelled_is_failure(self):
        self.assertEqual(
            "failure", gate._check_run_state({"status": "completed", "conclusion": "cancelled"}))


class DescribeTests(unittest.TestCase):
    def test_describe_names_both_providers_for_grouped_requirements(self):
        lines = "\n".join(gate.describe(statuses()))
        self.assertIn("or", lines)
        self.assertIn(GH_21, lines)
        self.assertIn(CI_21, lines)

    def test_describe_reports_the_context_that_satisfied_the_group(self):
        lines = gate.describe(statuses(**{GH_21: "success", JAZZER: "success", GH_25: "success"}))
        joined = "\n".join(lines)
        self.assertIn("(via {context})".format(context=GH_21), joined)


class RequirementShapeTests(unittest.TestCase):
    def test_every_requirement_group_is_non_empty(self):
        for group in gate.REQUIRED_CONTEXTS:
            self.assertTrue(group, "a requirement group must name at least one context")

    def test_jazzer_is_required_exactly_once(self):
        flat = [context for group in gate.REQUIRED_CONTEXTS for context in group]
        self.assertEqual(1, flat.count(JAZZER))


if __name__ == "__main__":
    unittest.main()
