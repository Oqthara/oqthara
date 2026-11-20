#!/usr/bin/env python3
"""Regression tests for scripts/check-path-parsing.py.

The gate exists because the same nine lines were written four times and one copy was
guarded. The consolidation removed the four copies; it did not stop a fifth, and R-032
says so. A checker that has only ever passed is indistinguishable from one that cannot
fail, so most of these tests reintroduce the defect and assert it is caught.
"""
import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_path_parsing", SCRIPTS / "check-path-parsing.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)


class TheRealTreeIsGuarded(unittest.TestCase):
    def test_it_passes(self):
        self.assertEqual([], checker.check(pathlib.Path(".")))

    def test_no_stdlib_source_but_the_guard_parses_a_path(self):
        for source in sorted(checker.SOURCES.rglob("*.java")):
            if source.resolve() == checker.GUARD.resolve():
                continue
            text = source.read_text(encoding="utf-8")
            self.assertEqual(
                [], checker.unguarded(text), f"{source} parses a path outside OqPaths"
            )

    def test_the_guard_is_the_file_that_does_parse(self):
        self.assertTrue(checker.GUARD.exists())
        self.assertTrue(
            checker.unguarded(checker.GUARD.read_text(encoding="utf-8")),
            "OqPaths no longer parses anything, so it probably stopped guarding",
        )


class DefectsAreCaught(unittest.TestCase):
    def test_a_parse_on_user_text_is_unguarded(self):
        found = checker.unguarded("        Path file = Path.of(path);")
        self.assertEqual(1, len(found))

    def test_paths_get_is_unguarded_too(self):
        found = checker.unguarded("        Path file = Paths.get(value);")
        self.assertEqual(1, len(found))

    def test_a_third_copy_anywhere_in_the_stdlib_is_caught(self):
        with tempfile.TemporaryDirectory() as made:
            root = pathlib.Path(made)
            (root / checker.GUARD).parent.mkdir(parents=True)
            (root / checker.GUARD).write_text("class OqPaths {}", encoding="utf-8")
            (root / checker.GUARDED / "Fifth.java").write_text(
                "class Fifth {\n    Path p = Path.of(text);\n}\n", encoding="utf-8"
            )
            problems = checker.check(root)
        self.assertEqual(1, len(problems))
        self.assertIn("Fifth.java:2", problems[0])

    def test_a_missing_guard_is_a_problem_not_a_pass(self):
        with tempfile.TemporaryDirectory() as made:
            root = pathlib.Path(made)
            (root / checker.SOURCES).mkdir(parents=True)
            problems = checker.check(root)
        self.assertEqual(1, len(problems))
        self.assertIn("missing", problems[0])


class ConstantsAndCommentsAreNotParses(unittest.TestCase):
    def test_a_string_literal_constant_is_exempt(self):
        self.assertEqual([], checker.unguarded('Path.of("."),'))

    def test_the_working_directory_constant_is_exempt(self):
        self.assertEqual([], checker.unguarded('if (base == null) base = Path.of("");'))

    def test_a_constant_plus_text_is_not_exempt(self):
        found = checker.unguarded('Path jar = Path.of(stem + ".jar");')
        self.assertEqual(1, len(found))

    def test_a_line_comment_is_ignored(self):
        self.assertEqual([], checker.unguarded("// Path.of(path) used to be here"))

    def test_a_block_comment_is_ignored(self):
        text = "/*\n * Path.of(path) escaped from here\n */\nclass X {}"
        self.assertEqual([], checker.unguarded(text))

    def test_a_javadoc_reference_is_ignored(self):
        self.assertEqual([], checker.unguarded("     * caught {@link InvalidPathException}."))


if __name__ == "__main__":
    unittest.main()
