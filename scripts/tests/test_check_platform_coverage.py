#!/usr/bin/env python3
"""Regression tests for scripts/check-platform-coverage.py.

Finding 20 was a Windows-only defect that passed on the two platforms a developer
had. The policy in `docs/governance/platform-validation-policy.md` writes down which
platform validates which test; this gate checks the writing against the code.

Two ways for it to be useless, both tested here. It can miss drift, so a class runs
it against a small synthetic tree that is wrong in exactly one way and asserts the
gate names that way. It can over-fire, so another asserts the committed tree passes:
a gate that rejected everything would look thorough and enforce nothing.

The fixture also pins the three shapes that a naive implementation gets wrong, each
of which the real repository contains:

- `assumeTrue(false, ...)` inside a `catch` is a capability probe after a real
  attempt, which is legitimate; the same call at the top of a test body is a test
  that skips everywhere, which is not. Only the position distinguishes them.
- a suite that defines a `WINDOWS` constant but guards every dependent test names
  no suite row, because the per-test rows already carry the declaration. Requiring
  both would be bookkeeping, not evidence.
- a platform word in a comment is not a dependency. The audit that produced the
  policy matched six javadoc sentences before comments were stripped.
"""
import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_platform_coverage", SCRIPTS / "check-platform-coverage.py"
)
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

REPO = SCRIPTS.parent

POLICY = """
## Required merge contexts

| Context | Workflow | Proves |
| --- | --- | --- |
| `ubuntu-latest / JDK 21` | `ci.yml` | the full suite on Linux |
| `analyze` | `codeql.yml` | the security analysis |
| `ci/circleci: jazzer-fuzz` | `.circleci/config.yml` | the fuzz targets |

### Not required, and therefore not evidence

| Job | What it proves | Why this is a gap |
| --- | --- | --- |
| `Display-backed Swing` | a frame is realized | no merge-blocking job proves it |

## Declared environment guards

| Test | File | Runs on | Skips on | Why |
| --- | --- | --- | --- | --- |
| `aFifoTest` | `Guarded` | linux, macos | windows | no FIFO on Windows |
| `aSymlinkTest` | `Guarded` | linux, macos, windows | — | skips only without symlinks |
| `aPosixProbe` | `Guarded` | linux, macos | windows | needs POSIX attributes |

## Platform-branching suites

| Suite | Runs on | What differs |
| --- | --- | --- |
| `Branching` | linux, macos, windows | a batch file on Windows, a shell script elsewhere |
"""

# One test per shape the gate must distinguish.
GUARDED = """
import java.nio.file.*;

class Guarded {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");

    @Test
    void aFifoTest() throws Exception {
        assumeFalse(WINDOWS, "a FIFO cannot be created reliably on Windows");
        assertEquals(1, 1);
    }

    @Test
    void aSymlinkTest() throws Exception {
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, dir.resolve("payload"));
        } catch (FileSystemException unsupported) {
            assumeTrue(false, "symbolic links unavailable: " + unsupported.getMessage());
            return;
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void aPosixProbe() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        assertTrue(true);
    }
}
"""

# PASSES EVERYWHERE WHILE PROVING ONE BRANCH: the shape the policy is about.
BRANCHING = """
class Branching {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");

    @Test
    void aBranchingTest() throws Exception {
        String script = WINDOWS ? "run.bat" : "run.sh";
        assertTrue(script.endsWith(WINDOWS ? ".bat" : ".sh"));
    }
}
"""

WORKFLOW = """
name: CI
jobs:
  test:
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest]
        java: ['21', '25']
    name: ${{ matrix.os }} / JDK ${{ matrix.java }}
    runs-on: ${{ matrix.os }}
    steps:
      - run: echo hi
  analyze:
    runs-on: ubuntu-latest
    steps:
      - uses: github/codeql-action/analyze@v4
"""

CIRCLECI = """
version: 2.1
workflows:
  fuzzing:
    jobs:
      - jazzer-fuzz
  macos:
    jobs:
      - test-macos:
          name: macOS / JDK 21
"""


def tree(policy: str = POLICY, guarded: str = GUARDED, branching: str = BRANCHING,
         workflow: str = WORKFLOW) -> pathlib.Path:
    """A small repository that is correct in every way the gate can check."""
    root = pathlib.Path(tempfile.mkdtemp())
    (root / checker.POLICY).parent.mkdir(parents=True, exist_ok=True)
    (root / checker.POLICY).write_text(policy, encoding="utf-8")
    tests = root / "oq-stdlib" / "src" / "test" / "java" / "dev" / "oqthara" / "stdlib"
    tests.mkdir(parents=True)
    (tests / "Guarded.java").write_text(guarded, encoding="utf-8")
    (tests / "Branching.java").write_text(branching, encoding="utf-8")
    (root / ".github" / "workflows").mkdir(parents=True)
    (root / ".github" / "workflows" / "ci.yml").write_text(workflow, encoding="utf-8")
    (root / ".circleci").mkdir()
    (root / ".circleci" / "config.yml").write_text(CIRCLECI, encoding="utf-8")
    return root


def problems(**overrides) -> list[str]:
    root = tree(**overrides)
    try:
        return checker.problems_for(root)
    finally:
        shutil.rmtree(root)


def replaced(text: str, old: str, new: str) -> str:
    assert old in text, f"the fixture {old!r} is no longer in the clean tree"
    return text.replace(old, new, 1)


class TheCommittedTreePasses(unittest.TestCase):
    def test_the_committed_tree_passes(self):
        # Over-firing guard: a gate that rejected the real tree would look thorough
        # and block every merge.
        self.assertEqual([], checker.problems_for(REPO))

    def test_it_reads_the_real_policy(self):
        policy = (REPO / checker.POLICY).read_text(encoding="utf-8")
        self.assertTrue(checker.read_guard_rows(policy), "the guard table parsed empty")
        self.assertTrue(checker.read_suite_rows(policy), "the suite table parsed empty")
        self.assertTrue(checker.read_required_contexts(policy), "the context table parsed empty")

    def test_the_not_required_table_is_not_read_as_required(self):
        # `Display-backed Swing` is recorded as a gap. Reading it as a requirement
        # would make the gate demand a context nobody claims to require.
        policy = (REPO / checker.POLICY).read_text(encoding="utf-8")
        self.assertNotIn("Display-backed Swing", checker.read_required_contexts(policy))

    def test_the_comment_words_are_not_dependencies(self):
        # The audit that produced the policy matched six javadoc sentences before
        # comments were stripped. `SafeArchiveTest` is the live example.
        source = (REPO / "oq-cli/src/test/java/dev/oqthara/cli/SafeArchiveTest.java")
        code = checker.strip_comments(source.read_text(encoding="utf-8"))
        self.assertNotIn("WINDOWS", code.replace("WINDOWS", ""), "comments were not stripped")
        self.assertNotIn("POSIX", code.replace("POSIX", ""))


class ItCatchesDrift(unittest.TestCase):
    def test_an_undeclared_guard(self):
        found = problems(policy=replaced(POLICY, "| `aFifoTest` | `Guarded` | linux, macos | "
                                                 "windows | no FIFO on Windows |\n", ""))
        self.assertTrue(any("Guarded.aFifoTest carries a platform guard" in p for p in found),
                        found)

    def test_an_undeclared_branching_suite(self):
        found = problems(policy=replaced(POLICY, "| `Branching` | linux, macos, windows | a "
                                                 "batch file on Windows, a shell script "
                                                 "elsewhere |\n", ""))
        self.assertTrue(any("Branching branches on the operating system" in p for p in found),
                        found)

    def test_a_stale_guard_row(self):
        found = problems(policy=replaced(POLICY, "`aPosixProbe`", "`aDeletedTest`"))
        self.assertTrue(any("no such guarded test exists" in p for p in found), found)

    def test_a_stale_suite_row(self):
        found = problems(policy=replaced(POLICY, "| `Branching` |", "| `Deleted` |"))
        self.assertTrue(any("no such test class exists" in p for p in found), found)

    def test_a_guard_whose_direction_contradicts_its_row(self):
        # The row claims the FIFO test runs on Windows; the body skips there.
        found = problems(policy=replaced(POLICY, "| linux, macos | windows | no FIFO",
                                        "| linux, macos, windows | — | no FIFO"))
        self.assertTrue(any("claims coverage the run did not produce" in p for p in found), found)

    def test_a_blanket_refusal_outside_a_catch(self):
        guarded = replaced(GUARDED, "        assumeFalse(WINDOWS, ",
                           "        assumeTrue(false, \"not today\");\n        assumeFalse(WINDOWS, ")
        found = problems(guarded=guarded)
        self.assertTrue(any("a test that never runs is not coverage" in p for p in found), found)

    def test_a_required_context_no_job_produces(self):
        found = problems(policy=replaced(POLICY, "| `analyze` |", "| `analysis` |"))
        self.assertTrue(any("unsatisfiable requirement blocks every merge" in p for p in found),
                        found)

    def test_a_matrix_context_needs_the_axes_expanded(self):
        # Reading only literal `name:` strings would miss both legs and report a
        # requirement no job satisfies.
        found = problems(policy=replaced(POLICY, "| `analyze` | `codeql.yml` | the security "
                                                 "analysis |",
                                        "| `ubuntu-latest JDK 21` | `ci.yml` | misspelled |"))
        self.assertTrue(any("`ubuntu-latest JDK 21`" in p for p in found), found)


class ItAcceptsTheLegitimateShapes(unittest.TestCase):
    def test_a_capability_probe_inside_a_catch_is_not_a_refusal(self):
        # The symlink test attempts the operation and reports what the host lacks.
        found = problems()
        self.assertEqual([], found)

    def test_a_suite_that_guards_every_dependent_test_needs_no_suite_row(self):
        # FileOperationsTest's shape: the constant is defined, every dependent test
        # carries its own row, so a suite row would repeat the declarations.
        self.assertNotIn("FileOperationsTest", checker.read_suite_rows(
            (REPO / checker.POLICY).read_text(encoding="utf-8")))

    def test_a_platform_word_in_a_comment_creates_no_dependency(self):
        commented = BRANCHING.replace(
            "class Branching {",
            "/**\n * On WINDOWS this differs; on POSIX it does not. os.name decides.\n */\n"
            "class Plain {\n    @Test\n    void nothing() {\n        assertTrue(true);\n    }\n}\n"
            "class Branching {")
        found = problems(branching=commented)
        self.assertEqual([], found)


if __name__ == "__main__":
    unittest.main()
