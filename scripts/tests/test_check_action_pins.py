#!/usr/bin/env python3
"""Regression tests for scripts/check-action-pins.py.

The gate exists because a `uses:` tag is mutable. Every test here mutates a
workflow in a temporary directory and asserts the gate rejects it, except the
two that read the real workflows.

The pair-of-eyes tests matter more than usual for this gate, because its scope
is *derived* rather than listed. A regex that stopped matching `contents: write`
would leave zero workflows in scope and report success -- the gate would still
exit 0, the CI step would still be green, and nothing would be pinned. That is
the failure this repository has hit repeatedly, so
`test_the_real_workflows_in_scope` asserts the specific files that must be in
scope, and `test_a_write_workflow_that_is_not_pinned_is_rejected` asserts that
something in scope is actually being checked.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location("check_action_pins", SCRIPTS / "check-action-pins.py")
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1"


class TheRealWorkflowsArePinned(unittest.TestCase):
    """Reads the real workflows, so a scope regex that stops matching is caught."""

    def test_the_gate_passes_on_the_repository(self):
        self.assertEqual(checker.check(), [])

    def test_the_real_workflows_in_scope(self):
        # Named explicitly on purpose. Deriving this list in the test from the same
        # helper the gate uses would make the test pass while the gate checked
        # nothing. These three are in scope for three different declared reasons,
        # which is what makes the assertion worth making.
        reasons = {
            name: checker.holds_write_scope(text) for name, text in checker.read_workflows()
        }
        in_scope = {name for name, reason in reasons.items() if reason}
        self.assertIn("release.yml", in_scope)
        self.assertIn("site-content-dispatch.yml", in_scope)
        self.assertIn("codeql.yml", in_scope)

    def test_the_read_only_workflows_are_out_of_scope(self):
        # The acceptance says read-only workflows may keep tags. If that quietly
        # stopped being true, pins would be demanded in four more files and the
        # first reaction would be to weaken the gate rather than to check it.
        reasons = {
            name: checker.holds_write_scope(text) for name, text in checker.read_workflows()
        }
        for name in ("ci.yml", "docker.yml", "nightly.yml"):
            self.assertIsNone(reasons[name], f"{name} is read-only but was scoped in")

    def test_every_pinned_action_names_its_version(self):
        for name, text in checker.read_workflows():
            if not checker.holds_write_scope(text):
                continue
            for _, value, comment in checker.uses_lines(text):
                ref = value.rsplit("@", 1)[-1]
                self.assertTrue(checker.PINNED.match(ref), f"{name}: {value} is not a full SHA")
                self.assertTrue(comment, f"{name}: {value} has no version comment")


class AMovingRefIsRejected(unittest.TestCase):
    """Every failure mode is introduced into a temporary workflow and must be caught."""

    def gate(self, body, name="w.yml"):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / ".github" / "workflows"
            root.mkdir(parents=True)
            (root / name).write_text(body, encoding="utf-8")
            original = checker.WORKFLOWS
            checker.WORKFLOWS = root.relative_to(directory)
            try:
                cwd = pathlib.Path.cwd()
                import os

                os.chdir(directory)
                try:
                    return checker.check()
                finally:
                    os.chdir(cwd)
            finally:
                checker.WORKFLOWS = original

    def test_a_major_version_tag_is_rejected(self):
        problems = self.gate(
            "permissions:\n  contents: write\njobs:\n  a:\n    steps:\n"
            "      - uses: actions/checkout@v7\n"
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("mutable ref `v7`", problems[0])

    def test_a_pinned_action_without_a_version_comment_is_rejected(self):
        # A bare SHA cannot be reviewed: a reader cannot tell whether it is the
        # version they meant, or a typo, or an unrelated commit.
        problems = self.gate(
            f"permissions:\n  contents: write\njobs:\n  a:\n    steps:\n"
            f"      - uses: actions/checkout@{SHA}\n"
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("does not name the version", problems[0])

    def test_a_short_sha_is_rejected(self):
        # A short SHA is a prefix, not a commit: it can be ambiguous, and a
        # shortened pin is how a careful-looking change stops being a pin.
        problems = self.gate(
            "permissions:\n  contents: write\njobs:\n  a:\n    steps:\n"
            "      - uses: actions/checkout@3d3c42e5 # v7.0.1\n"
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("mutable ref `3d3c42e5`", problems[0])

    def test_a_secret_puts_a_workflow_in_scope(self):
        # `contents: read` plus a token is the shape that matters most: the job
        # token is narrow, the secret is not visible here, and assuming it is
        # narrow is the mistake.
        problems = self.gate(
            "permissions:\n  contents: read\njobs:\n  a:\n    steps:\n"
            "      - uses: actions/checkout@v7\n"
            "      - run: announce\n        env:\n"
            "          TOKEN: ${{ secrets.SITE_DISPATCH_TOKEN }}\n"
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("SITE_DISPATCH_TOKEN", problems[0])

    def test_id_token_write_puts_a_workflow_in_scope(self):
        problems = self.gate(
            "permissions:\n  id-token: write\njobs:\n  a:\n    steps:\n"
            "      - uses: sigstore/cosign-installer@v4.1.3\n"
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("id-token: write", problems[0])

    def test_a_read_only_workflow_may_keep_a_tag(self):
        # The negative control. Without this, a gate that rejected every tag would
        # pass every test above while contradicting the stated acceptance.
        self.assertEqual(
            self.gate(
                "permissions:\n  contents: read\njobs:\n  a:\n    steps:\n"
                "      - uses: actions/checkout@v7\n"
            ),
            [],
        )

    def test_a_commented_out_secret_does_not_scope_a_workflow(self):
        # A workflow that documents a secret without holding one is read-only.
        # Scoping on the mention alone would demand pins in files that hold nothing.
        self.assertEqual(
            self.gate(
                "permissions:\n  contents: read\n# needs secrets.SITE_DISPATCH_TOKEN to announce\n"
                "jobs:\n  a:\n    steps:\n      - uses: actions/checkout@v7\n"
            ),
            [],
        )

    def test_a_fully_pinned_write_workflow_passes(self):
        self.assertEqual(
            self.gate(
                f"permissions:\n  contents: write\njobs:\n  a:\n    steps:\n"
                f"      - uses: actions/checkout@{SHA} # v7.0.1\n"
            ),
            [],
        )


class TheScopeIsReported(unittest.TestCase):
    def test_the_summary_names_what_is_in_scope(self):
        # The gate's coverage has to be visible in CI output. A gate that reports
        # "ok" without saying how many files it looked at cannot be reviewed.
        summary = checker.summarise()
        for name in ("release.yml", "site-content-dispatch.yml", "codeql.yml"):
            self.assertIn(name, summary)


if __name__ == "__main__":
    unittest.main()
