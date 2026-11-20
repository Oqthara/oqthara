#!/usr/bin/env python3
"""Regression tests for scripts/check-path-parsing.py.

The gate exists because the same path-parsing lines were written four times and only one
copy was guarded. Consolidating them did not stop a fifth copy, and R-032 says so.

A checker that has only ever passed is indistinguishable from one that cannot fail, so
most of these tests reintroduce the defect and assert it is caught, and the rest feed it
spellings that evade a naive line-at-a-time scan. Every evasion below is one that an
earlier draft of this gate actually missed.
"""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent

_spec = importlib.util.spec_from_file_location(
    "check_path_parsing", SCRIPTS / "check-path-parsing.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)


def found(text):
    return [line for line, _ in checker.unguarded(text)]


class TheRealTreeIsGuarded(unittest.TestCase):
    def test_it_passes(self):
        problems, scanned, allowed = checker.check(ROOT)
        self.assertEqual([], problems)
        self.assertGreaterEqual(scanned, 55)
        self.assertGreaterEqual(allowed, len(checker.ALLOWED))

    def test_every_allowed_site_really_parses_a_path(self):
        for name in checker.ALLOWED:
            with self.subTest(name):
                text = (ROOT / name).read_text(encoding="utf-8")
                self.assertTrue(
                    checker.all_parses(text),
                    f"{name} is exempted but parses nothing, so the entry is stale",
                )

    def test_no_file_outside_the_allowed_list_parses_a_path(self):
        for scope in checker.SCOPES:
            for source in sorted((ROOT / scope).rglob("*.java")):
                relative = str(source.relative_to(ROOT))
                if relative in checker.ALLOWED:
                    continue
                with self.subTest(relative):
                    text = source.read_text(encoding="utf-8")
                    self.assertEqual([], checker.unguarded(text))

    def test_the_gate_reads_core_and_runtime_not_only_the_stdlib(self):
        names = " ".join(str(scope) for scope in checker.SCOPES)
        self.assertIn("oq-stdlib", names)
        self.assertIn("oq-core", names)
        self.assertIn("oq-runtime", names)


class DefectsAreCaught(unittest.TestCase):
    def test_a_parse_on_user_text_is_unguarded(self):
        self.assertEqual([1], found("        Path file = Path.of(path);"))

    def test_paths_get_is_unguarded_too(self):
        self.assertEqual([1], found("        Path file = Paths.get(value);"))

    def test_a_third_copy_anywhere_in_a_scope_is_caught(self):
        with tempfile.TemporaryDirectory() as made:
            root = pathlib.Path(made)
            for scope in checker.SCOPES:
                (root / scope).mkdir(parents=True)
            (root / checker.SCOPES[0] / "Fifth.java").write_text(
                "class Fifth {\n    Path p = Path.of(text);\n}\n", encoding="utf-8"
            )
            problems, _, _ = checker.check(root)
        self.assertTrue(problems)
        self.assertIn("Fifth.java:2", problems[0])

    def test_a_missing_scope_is_a_problem_not_a_pass(self):
        with tempfile.TemporaryDirectory() as made:
            root = pathlib.Path(made)
            (root / checker.SCOPES[0]).mkdir(parents=True)
            problems, _, _ = checker.check(root)
        self.assertTrue(problems)
        self.assertIn("missing", problems[0])

    def test_a_stale_allowed_entry_is_a_problem(self):
        with tempfile.TemporaryDirectory() as made:
            root = pathlib.Path(made)
            for scope in checker.SCOPES:
                (root / scope).mkdir(parents=True)
            problems, _, _ = checker.check(root)
        stale = [p for p in problems if "stale" in p]
        self.assertEqual(len(checker.ALLOWED), len(stale))


class SpellingsThatEvadeANaiveScan(unittest.TestCase):
    def test_a_parse_after_a_one_line_block_comment(self):
        self.assertEqual([1], found("/* note */ Path.of(text);"))

    def test_a_parse_after_a_block_comment_closes(self):
        self.assertEqual([2], found("/*\n */ Path.of(text);"))

    def test_a_double_slash_inside_a_literal_does_not_hide_the_next_line(self):
        self.assertEqual([2], found('String url = "http://x";\nPath.of(text);'))

    def test_a_package_qualified_parse(self):
        self.assertEqual([1], found("java.nio.file.Path.of(text);"))

    def test_a_static_import_of_of(self):
        self.assertEqual([1], found("import static java.nio.file.Path.of;"))

    def test_a_static_import_of_paths_get(self):
        self.assertEqual([1], found("import static java.nio.file.Paths.get;"))

    def test_file_systems_get_default_get_path(self):
        self.assertEqual(
            [1], found("FileSystems.getDefault().getPath(text);")
        )

    def test_a_parse_split_across_two_lines(self):
        self.assertEqual([1], found("Path\n    .of(text);"))

    def test_a_parse_split_with_spaces_around_the_dots(self):
        self.assertEqual([1], found("Path . of (text);"))

    def test_a_parse_whose_argument_is_an_escaped_literal(self):
        self.assertEqual([1], found('Path.of("a\\u0000b");'))


class ConfusingLookalikesAreNotParses(unittest.TestCase):
    def test_a_string_literal_argument_is_a_constant(self):
        self.assertEqual([], found('Path.of("."),'))

    def test_the_working_directory_constant_is_a_constant(self):
        self.assertEqual([], found('if (base == null) base = Path.of("");'))

    def test_a_constant_plus_text_is_not_a_constant(self):
        self.assertEqual([1], found('Path jar = Path.of(stem + ".jar");'))

    def test_a_line_comment_is_ignored(self):
        self.assertEqual([], found("// Path.of(path) used to be here"))

    def test_a_block_comment_is_ignored(self):
        self.assertEqual([], found("/*\n * Path.of(path) escaped from here\n */\nclass X {}"))

    def test_a_javadoc_block_is_ignored(self):
        # A bare `*` line is not valid Java on its own, so the opener is part of the case.
        text = "/**\n * caught {@code Path.of(t)} here\n */\nclass X {}"
        self.assertEqual([], found(text))

    def test_a_literal_that_names_the_call_is_not_a_call(self):
        self.assertEqual([], found('String s = "Path.of(x)";'))

    def test_an_ordinary_path_getter_is_not_a_parse(self):
        self.assertEqual([], found("String p = uri.getPath();"))


class ExitCodesDistinguishPassFromRot(unittest.TestCase):
    def run_gate(self):
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "check-path-parsing.py")],
            capture_output=True,
            text=True,
            cwd=str(ROOT),
        )

    def test_the_real_tree_passes_with_zero(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("guarded sites", result.stdout)

    def test_a_defect_exits_one(self):
        with tempfile.TemporaryDirectory() as made:
            root = pathlib.Path(made)
            for scope in checker.SCOPES:
                (root / scope).mkdir(parents=True)
            (root / checker.SCOPES[0] / "Fifth.java").write_text(
                "class Fifth {\n    Path p = Path.of(t);\n}\n", encoding="utf-8"
            )
            problems, _, _ = checker.check(root)
        self.assertTrue(problems)
        self.assertIn("outside a guarded site", problems[0])


if __name__ == "__main__":
    unittest.main()
