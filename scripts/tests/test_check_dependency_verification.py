#!/usr/bin/env python3
"""Regression tests for scripts/check-dependency-verification.py.

The gate exists because Gradle's dependency verification is on if its metadata file
is present and silently off if it is not. Deleting `gradle/verification-metadata.xml`
leaves every build succeeding and nothing verified, so the failure this gate exists
to catch is a control that has quietly gone away. `--write-verification-metadata`
regenerates the file on demand, which makes "regenerate it and move on" the tempting
fix for any failure here; that is safe only if the regeneration is compared against
the pins the build actually declares.

Each test reintroduces one failure mode into a copy of the file and asserts the gate
rejects it. The failure modes are taken from what the control can actually lose:
the file, metadata verification, a component, a checksum, and agreement with the pin.
"""
import importlib.util
import pathlib
import re
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_dependency_verification", SCRIPTS / "check-dependency-verification.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

METADATA = checker.METADATA
REAL = (checker.ROOT / METADATA).read_text(encoding="utf-8")


def without_component(text, group, name):
    """Returns `text` with the whole component block for one coordinate removed."""
    pattern = re.compile(
        r"\s*<component group=\"%s\" name=\"%s\".*?</component>\n"
        % (re.escape(group), re.escape(name)),
        re.S,
    )
    stripped, count = pattern.subn("", text)
    assert count == 1, f"expected one component for {group}:{name}, removed {count}"
    return stripped


class TheControlIsPresent(unittest.TestCase):
    def test_the_real_file_passes(self):
        # The pair of eyes: the committed file, read as-is, must satisfy the gate.
        # Without this every other test could pass against a fixture while the real
        # file was the thing that had drifted.
        self.assertEqual(checker.check(REAL), [])

    def test_metadata_verification_is_enabled_and_reported(self):
        # Turning metadata verification off leaves artifact checksums in place but
        # stops Gradle checking `.module` files, which is where a swapped dependency
        # would most easily hide. The gate must not treat that as fine.
        broken = REAL.replace(
            "<verify-metadata>true</verify-metadata>",
            "<verify-metadata>false</verify-metadata>",
        )
        self.assertNotEqual(broken, REAL)
        problems = checker.check(broken)
        self.assertEqual(len(problems), 1)
        self.assertIn("metadata is not enabled", problems[0])


class EveryPinIsCovered(unittest.TestCase):
    def test_removing_a_component_is_caught(self):
        problems = checker.check(
            without_component(REAL, "org.xerial", "sqlite-jdbc")
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("org.xerial:sqlite-jdbc:3.54.1.0", problems[0])
        self.assertIn("has no component", problems[0])

    def test_removing_the_runtime_only_component_is_caught(self):
        # slf4j-nop is `runtimeOnly`, so it does not resolve during `classes` and its
        # absence is invisible to most builds. That is why it needs its own test.
        problems = checker.check(without_component(REAL, "org.slf4j", "slf4j-nop"))
        self.assertEqual(len(problems), 1)
        self.assertIn("org.slf4j:slf4j-nop:2.0.20", problems[0])

    def test_component_under_a_different_version_is_not_coverage(self):
        # The realistic drift: the pin in the build file moves and the metadata is
        # not regenerated. A component for the old version must not count.
        stale = REAL.replace(
            'name="sqlite-jdbc" version="3.54.1.0"',
            'name="sqlite-jdbc" version="3.50.0.0"',
        )
        self.assertNotEqual(stale, REAL)
        problems = checker.check(stale)
        self.assertEqual(len(problems), 1)
        self.assertIn("has no component", problems[0])

    def test_a_pin_that_disappears_from_the_build_file_is_caught(self):
        # PINS names a build file. If the dependency moves to another file the gate's
        # own assumption is wrong, and that must fail loudly rather than pass because
        # the metadata still has a component for a dependency nothing declares.
        original = checker.PINS
        checker.PINS = [(g, n, "oq-core/build.gradle.kts") for g, n, _ in original]
        try:
            problems = checker.check(REAL)
        finally:
            checker.PINS = original
        self.assertTrue(problems)
        self.assertTrue(all("is not pinned in" in p for p in problems))


class ChecksumsAreReal(unittest.TestCase):
    def test_a_placeholder_checksum_is_caught(self):
        # A row of zeros is well formed everywhere and only fails when that artifact
        # is resolved, which for a runtime-only dependency can be never.
        zeros = "0" * 64
        broken = re.sub(
            r'(<component group="org\.slf4j" name="slf4j-nop".*?<sha256 value=")[0-9a-f]{64}(")',
            r"\g<1>" + zeros + r"\g<2>",
            REAL,
            count=1,
            flags=re.S,
        )
        self.assertNotEqual(broken, REAL)
        problems = checker.check(broken)
        self.assertEqual(len(problems), 1)
        self.assertIn("placeholder checksum", problems[0])

    def test_a_component_with_no_checksum_at_all_is_caught(self):
        # Not the same as a placeholder. A component with no `<sha256>` children gives
        # the gate nothing to compare, so it must be rejected rather than treated as
        # "no checksums to disagree with".
        broken = re.sub(
            r'(<component group="org\.xerial" name="sqlite-jdbc" version="3\.54\.1\.0">)'
            r".*?(</component>)",
            r"\1\2",
            REAL,
            count=1,
            flags=re.S,
        )
        self.assertNotEqual(broken, REAL)
        problems = checker.check(broken)
        self.assertEqual(len(problems), 1)
        self.assertIn("no sha256 checksum", problems[0])


class TheFileBeingGoneIsNotASilentPass(unittest.TestCase):
    def test_a_missing_file_exits_one_and_says_what_was_lost(self):
        # The gate's main reason to exist. A merged-away file must read as "the
        # control is gone", which is exit 1, not exit 2 (checker broke) and not 0.
        original = checker.METADATA
        checker.METADATA = pathlib.Path("gradle/does-not-exist.xml")
        try:
            code = checker.main()
        finally:
            checker.METADATA = original
        self.assertEqual(code, 1)

    def test_reading_a_pin_from_a_missing_build_file_returns_none(self):
        self.assertIsNone(
            checker.read_pin_version(
                pathlib.Path("/tmp/does-not-exist.gradle.kts"), "org.slf4j", "slf4j-nop"
            )
        )


if __name__ == "__main__":
    unittest.main()
