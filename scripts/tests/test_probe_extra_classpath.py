#!/usr/bin/env python3
"""Regression tests for the C11.1 probe's launch form.

C11.1 says a class on the user's own classpath is importable. For most of the audit that row was
covered by hand-gathered evidence only, and the reason was mechanical rather than deliberate:
`java -jar` ignores `CLASSPATH` and the working directory, so a probe launched that way cannot
reach a class that is not inside `oq.jar`. The probe would have failed for a reason unrelated to
the capability it claims to measure.

The failure modes these tests pin are the ones that leave the run green while proving nothing:

  * `EXTRA_CLASSPATH` is consulted but `launch` still emits `-jar`, so the fixture is unused and
    the probe only passes because the harness happens to see it some other way;
  * the `-cp` form leaks onto probes that did not ask for it, silently changing the launch form
    of output that was recorded under `-jar`;
  * the fixture jar is dropped from the tree, so the probe skips instead of failing;
  * the compiled half runs under `-jar`, which cannot see the fixture either.

`launch` is called directly rather than through `run`, so these are decisions about a command
line and need no JVM.
"""
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
RUNNER = ROOT / "docs" / "governance" / "alpha-5" / "java-bridge-probes" / "run.py"
PROBE = RUNNER.parent / "c11_user_classpath.oq"


def load_runner():
    spec = importlib.util.spec_from_file_location("probe_runner_classpath", RUNNER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LaunchFormIsDecidedByTheProbe(unittest.TestCase):
    def setUp(self):
        self.runner = load_runner()

    def test_the_probe_needing_a_classpath_gets_the_cp_form(self):
        fixture = RUNNER.parent / self.runner.EXTRA_CLASSPATH["c11_user_classpath"]
        argv = self.runner.launch(pathlib.Path("oq.jar"), "run", "p.oq", extra=fixture)
        self.assertIn("-cp", argv, "the probe needs `-cp`; `-jar` cannot reach the fixture")
        self.assertNotIn("-jar", argv)
        self.assertIn(self.runner.CLI_MAIN, argv)
        self.assertIn(str(fixture), " ".join(argv))

    def test_every_other_probe_keeps_the_jar_form(self):
        argv = self.runner.launch(pathlib.Path("oq.jar"), "run", "p.oq")
        self.assertIn("-jar", argv)
        self.assertNotIn("-cp", argv)
        self.assertNotIn(self.runner.CLI_MAIN, argv)

    def test_the_compiled_half_can_see_the_fixture_too(self):
        fixture = pathlib.Path("fixture.jar")
        # The compiled jar's manifest names `oq.generated.Program`, so under `-cp` that class has
        # to be spelled out or the JVM starts nothing at all.
        self.assertEqual(self.runner.COMPILED_MAIN, "oq.generated.Program")
        argv = self.runner.compiled_launch(pathlib.Path("x.jar"), extra=fixture)
        self.assertIn("-cp", argv)
        self.assertNotIn("-jar", argv)
        self.assertIn(self.runner.COMPILED_MAIN, argv)
        self.assertIn(str(fixture), " ".join(argv))

    def test_the_compiled_half_keeps_the_jar_form_without_a_fixture(self):
        argv = self.runner.compiled_launch(pathlib.Path("x.jar"))
        self.assertIn("-jar", argv)
        self.assertNotIn(self.runner.COMPILED_MAIN, argv)

    def test_the_separator_is_the_platform_one(self):
        # On POSIX `os.pathsep` is already `:`, so a hard-coded `:` passes here and breaks on a
        # Windows leg. Substituting a separator that appears in neither path is what makes the
        # difference observable on the machine running the test.
        separator = self.runner.os.pathsep
        self.runner.os.pathsep = ";;"
        self.addCleanup(setattr, self.runner.os, "pathsep", separator)
        argv = self.runner.launch(pathlib.Path("oq.jar"), extra=pathlib.Path("f.jar"))
        entry = argv[argv.index("-cp") + 1]
        self.assertEqual(entry.split(";;"), ["oq.jar", "f.jar"])


class TheFixtureIsReallyNeeded(unittest.TestCase):
    def setUp(self):
        self.runner = load_runner()

    def test_the_fixture_source_is_committed(self):
        source = RUNNER.parent / self.runner.EXTRA_CLASSPATH["c11_user_classpath"]
        self.assertTrue(
            source.is_file(),
            f"{source.name} is missing; the probe would fail as a missing-file error, not as a "
            "statement about the classpath",
        )
        self.assertGreater(source.stat().st_size, 0)

    def test_the_fixture_is_built_not_committed(self):
        # A jar in the tree would be invisible to review and would need regenerating by hand.
        # The build is in the harness and the source is reviewable.
        source = RUNNER.parent / self.runner.EXTRA_CLASSPATH["c11_user_classpath"]
        self.assertEqual(source.suffix, ".java")
        self.assertFalse((RUNNER.parent / "hello_classpath_fixture.jar").exists())

    def test_a_broken_fixture_fails_rather_than_skipping(self):
        scratch = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        broken = scratch / "Broken.java"
        broken.write_text("public final class Broken { this is not java }", encoding="utf-8")
        with self.assertRaises(RuntimeError) as caught:
            self.runner.fixture_jar(broken, scratch / "build")
        self.assertIn("javac failed", str(caught.exception))

    def test_a_missing_fixture_source_fails_rather_than_skipping(self):
        # Pointing the table at a file that is not there must record a failure. Returning
        # quietly would leave the probe uncounted, and an uncounted probe reads as a pass.
        original = self.runner.EXTRA_CLASSPATH["c11_user_classpath"]
        self.runner.EXTRA_CLASSPATH["c11_user_classpath"] = "does-not-exist.java"
        self.addCleanup(
            self.runner.EXTRA_CLASSPATH.__setitem__, "c11_user_classpath", original
        )
        failures, defects, tally = [], [], self.runner.Tally()
        self.runner.check_probe(
            "c11_user_classpath", ["hi world"], None,
            pathlib.Path("oq.jar"), False, tally, failures, defects,
        )
        self.assertEqual(1, len(failures))
        self.assertIn("missing", failures[0])

    def test_a_missing_compiler_fails_rather_than_skipping(self):
        # `javac` may be absent on a stripped-down runner. A probe that skipped itself would
        # report a pass for a check that never ran, so `check_probe` must record a failure.
        scratch = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        original = self.runner.fixture_jar
        self.runner.fixture_jar = lambda source, work: (_ for _ in ()).throw(
            FileNotFoundError("javac")
        )
        self.addCleanup(setattr, self.runner, "fixture_jar", original)
        failures, defects, tally = [], [], self.runner.Tally()
        self.runner.check_probe(
            "c11_user_classpath", ["hi world"], None,
            pathlib.Path("oq.jar"), False, tally, failures, defects,
        )
        self.assertEqual(1, len(failures))
        self.assertIn("c11_user_classpath", failures[0])

    def test_the_probe_imports_the_fixture_class(self):
        # If the probe stopped importing `Hello` it would pass under `-jar` as well, and the
        # `-cp` launch would become decoration.
        text = PROBE.read_text(encoding="utf-8")
        self.assertRegex(text, r"(?m)^use Hello$")
        self.assertNotRegex(
            text,
            r"use java\.lang\.Hello",
            "Hello must stay a plain name, not a JDK class, or the fixture proves nothing",
        )

    def test_only_the_classpath_probe_is_exempted(self):
        # EXTRA_CLASSPATH is the whole exemption list. A second entry would be a second probe
        # launched differently from the output recorded for it.
        self.assertEqual(sorted(self.runner.EXTRA_CLASSPATH), ["c11_user_classpath"])


if __name__ == "__main__":
    unittest.main()
