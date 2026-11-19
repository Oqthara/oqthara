#!/usr/bin/env python3
"""Regression tests for scripts/check-platform-coverage.py.

Finding 20 was a Windows-only defect that passed on the two platforms a developer
had. The policy in `docs/governance/platform-validation-policy.md` writes down which
platform validates which test; this gate checks the writing against the code.

Two ways for it to be useless, both tested here. It can miss drift, so a class runs
it against a small synthetic tree that is wrong in exactly one way and asserts the
gate names that way. It can over-fire, so another asserts the committed tree passes:
a gate that rejected everything would look thorough and enforce nothing.

The fixture also pins the shapes that a naive implementation gets wrong, each of which
the real repository contains:

- `assumeTrue(false, ...)` inside a `catch` is a capability probe after a real
  attempt, which is legitimate; the same call at the top of a test body is a test
  that skips everywhere, which is not. Only the position distinguishes them.
- a `return` from a `catch` is a silent platform refusal: the test asserts nothing on
  the host that raised, and JUnit records a *pass* rather than a skip, so no skip count
  sees it. A `return` inside a lambda leaves the lambda rather than the test.
- a suite that defines a `WINDOWS` constant needs a suite row even when its dependent
  tests carry their own guard rows. One declared test is not a declaration for the file
  around it.
- a platform word in a comment is not a dependency. The audit that produced the
  policy matched six javadoc sentences before comments were stripped.
- a brace inside a string literal is not structure. `"{}"` and `"{not json"` unbalanced
  the count and attributed every later method's `catch` to one test.
- a step name is not a check context. Only a job's own `name` and its `jobs:` key are.
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
| `Guarded` | linux, macos, windows | defines a `WINDOWS` constant consumed by its per-test guards |
"""

# One test per shape the gate must distinguish.
GUARDED = """
import java.nio.file.*;

class Guarded {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");
    private static final boolean POSIX = java.io.File.separatorChar == '/';

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
        assumeTrue(POSIX, "needs a POSIX shell");
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
      - name: Check out
        uses: actions/checkout@v4
      - run: echo hi
"""

CODEQL = """
name: CodeQL
jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - name: Initialize
        uses: github/codeql-action/init@v4
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


RESULT = """<testsuite name="{cls}" tests="{tests}" skipped="{skipped}" failures="0"
        errors="0">{cases}</testsuite>"""

SKIPPED_CASE = '<testcase name="{name}()" classname="{cls}"/>'


def results(cwd: pathlib.Path, cls: str, tests: int, skipped: tuple[str, ...] = ()) -> None:
    """Writes the JUnit XML Gradle would leave behind for one test class."""
    cases = "".join(SKIPPED_CASE.format(name=name, cls=cls) for name in skipped)
    cased = cases.replace("/>", "><skipped/></testcase>")
    plain = "".join(f'<testcase name="passing{i}()" classname="{cls}"/>'
                    for i in range(tests - len(skipped)))
    path = cwd / "oq-stdlib" / "build" / "test-results" / "test"
    path.mkdir(parents=True, exist_ok=True)
    (path / f"TEST-{cls}.xml").write_text(
        RESULT.format(cls=cls, tests=tests, skipped=len(skipped), cases=plain + cased),
        encoding="utf-8")


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
    (root / ".github" / "workflows" / "codeql.yml").write_text(CODEQL, encoding="utf-8")
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
        # The gate must read requirements from the required table only. A context that
        # is merely *mentioned* under "Not required" must not become one, or the gate
        # would demand a context the policy records as a gap.
        #
        # Only the gap table's first cells count. The prose around it names contexts that
        # have moved between the tables, and matching that prose would convict the policy
        # for explaining itself.
        policy = (REPO / checker.POLICY).read_text(encoding="utf-8")
        required = checker.read_required_contexts(policy)
        gap_section = policy.split("### Not required", 1)[1].split("\n## ", 1)[0]
        gap_rows = [
            line.split("|")[1].strip().strip("`")
            for line in gap_section.splitlines()
            if line.startswith("|") and not line.startswith("| ---")
        ]
        for row in gap_rows:
            named = [name for name in required if name in row]
            self.assertEqual([], named, f"row {row!r} also requires {named}")

    def test_the_comment_words_are_not_dependencies(self):
        # The audit that produced the policy matched six javadoc sentences before
        # comments were stripped. `SafeArchiveTest` is the live example: its prose
        # discusses WINDOWS and POSIX while its body has no guard at all.
        source = (REPO / "oq-cli/src/test/java/dev/oqthara/cli/SafeArchiveTest.java")
        original = source.read_text(encoding="utf-8")
        code = checker.strip_comments(original)
        self.assertIn("POSIX", original, "the fixture lost the word this test is about")
        self.assertNotIn("POSIX", code, "comments were not stripped")
        self.assertNotIn("windows", code, "comments were not stripped")

    def test_a_brace_in_a_literal_does_not_unbalance_the_file(self):
        # `ServerTest` writes the frame `"{}"` and the payload `"{not json"`. Counting
        # braces in the raw text read the second as structure, ran to end of file, and
        # attributed every following method's `catch` to the test above it.
        source = (REPO / "oq-lsp/src/test/java/dev/oqthara/lsp/ServerTest.java")
        code = checker.strip_comments(source.read_text(encoding="utf-8"))
        self.assertEqual(code.count("{"), code.count("}"),
                         "a literal unbalanced the brace count, so method bodies are wrong")

    def test_every_suite_that_branches_has_a_row(self):
        # The exemption this used to grant — one guard row covers the whole file — was the
        # hole the suite table exists to close. This pins the *shape* (set difference is
        # empty), never a total, so adding a branching suite needs a row and adding a
        # non-branching one needs nothing.
        policy = (REPO / checker.POLICY).read_text(encoding="utf-8")
        rows = set(checker.read_suite_rows(policy))
        branching = {path.stem for path in checker.sources(REPO)
                     if checker.BRANCH.search(
                         checker.strip_comments(path.read_text(encoding="utf-8"),
                                                literals=False))}
        self.assertEqual(set(), branching - rows,
                         "a suite branches on the platform without a policy row")


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
        self.assertTrue(any("no such platform-dependent test exists" in p for p in found), found)

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

    def test_a_suite_with_guarded_tests_still_needs_a_suite_row(self):
        # The shape that used to be exempt: a suite constant consumed by per-test guards.
        # The synthetic `Guarded` suite has three guard rows of its own, so an exemption
        # based on per-test rows would hide it. Removing its suite row must fail.
        found = problems(policy=replaced(POLICY, "| `Guarded` | linux, macos, windows | "
                                                 "defines a `WINDOWS` constant consumed by "
                                                 "its per-test guards |\n", ""))
        self.assertTrue(any("Guarded branches on the operating system" in p for p in found),
                        found)

    def test_a_return_inside_a_lambda_is_not_a_silent_platform_return(self):
        # FileOperationsTest submits a task that answers `return true` / `return false`
        # and then asserts two lines further down. Those leave the lambda, not the test.
        lambdas = GUARDED.replace(
            "    @Test\n    void aPosixProbe() throws Exception {",
            "    @Test\n    void aLambdaTest() {\n"
            "        pool.submit(() -> {\n"
            "            try {\n"
            "                call();\n"
            "                return true;\n"
            "            } catch (OqError refused) {\n"
            "                return false;\n"
            "            }\n"
            "        });\n"
            "        assertEquals(1, 1);\n"
            "    }\n\n"
            "    @Test\n    void aPosixProbe() throws Exception {")
        found = problems(guarded=lambdas)
        self.assertFalse(any("aLambdaTest" in p for p in found), found)

    def test_a_silent_return_is_declared(self):
        # A test that returns from a `catch` asserts nothing there and is recorded as a
        # pass, so no skip count sees it. It needs a row just as a guard does.
        silent = GUARDED.replace(
            "    @Test\n    void aPosixProbe() throws Exception {",
            "    @Test\n    void aSilentTest() throws Exception {\n"
            "        try {\n"
            "            Files.getAttribute(dir, \"unix:mode\");\n"
            "        } catch (UnsupportedOperationException e) {\n"
            "            return;\n"
            "        }\n"
            "        assertEquals(1, 1);\n"
            "    }\n\n"
            "    @Test\n    void aPosixProbe() throws Exception {")
        found = problems(guarded=silent)
        self.assertTrue(any("aSilentTest returns from a `catch`" in p for p in found), found)

    def test_a_posix_guard_is_compared_rather_than_skipped(self):
        # `POSIX` matched no name in PLATFORMS, so check 3 hit `continue` and never
        # compared it to the row. A row claiming the test runs only on Windows passed,
        # because the guard covered a family the check could not name.
        policy = replaced(POLICY, "| `aPosixProbe` | `Guarded` | linux, macos | windows | "
                                  "needs POSIX attributes |",
                          "| `aPosixProbe` | `Guarded` | windows | linux, macos | "
                          "needs POSIX attributes |")
        found = problems(policy=policy)
        self.assertTrue(any("claims coverage the run did not produce" in p for p in found),
                        found)

    def test_a_required_context_attributed_to_the_wrong_workflow(self):
        found = problems(policy=replaced(POLICY, "| `analyze` | `codeql.yml` |",
                                         "| `analyze` | `ci.yml` |"))
        self.assertTrue(any("a reader is sent to a file that never reports" in p for p in found),
                        found)

    def test_a_step_name_is_not_a_required_context(self):
        # `_collect_names` recursed for every `name:` under a job, so a step name or an
        # artifact name satisfied check 5 while naming nothing that reports a status.
        found = problems(policy=replaced(POLICY, "| `analyze` | `codeql.yml` |",
                                         "| `Check out` | `ci.yml` |"))
        self.assertTrue(any("unsatisfiable requirement blocks every merge" in p for p in found),
                        found)

    def test_the_policy_and_the_apply_script_name_the_same_contexts(self):
        # `apply-branch-protection.sh` re-applies the ruleset, so a context added to
        # the policy but not the script is silently reverted the next time it runs.
        # This asserts the two lists agree, without pinning how many there are.
        policy = (REPO / checker.POLICY).read_text(encoding="utf-8")
        documented = set(checker.read_required_contexts(policy))
        script = (REPO / "scripts/apply-branch-protection.sh").read_text(encoding="utf-8")
        scripted = set(checker.SCRIPT_CONTEXT.findall(script))
        self.assertTrue(scripted, "the apply script's context list parsed empty")
        self.assertEqual(sorted(documented), sorted(scripted))

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

class ItReadsTheLastRun(unittest.TestCase):
    """Check 6, which turns the policy's `skipped=0` sentence into something enforced."""

    def test_a_skipped_test_on_a_run_row_is_a_contradiction(self):
        # Assumed silently, `skipped=0` was prose. Gradle records the suspended case by
        # name, so the row that claimed coverage can be pointed at the test that did not.
        root = tree()
        try:
            results(root, "Guarded", 3, ("aPosixProbe",))
            found = checker.problems_for(root)
        finally:
            shutil.rmtree(root)
        self.assertTrue(any("aPosixProbe" in p and "skipped it" in p for p in found), found)

    def test_a_row_that_declares_a_skip_here_is_not_blamed(self):
        # `aFifoTest` declares `skips on windows`. On Windows its skip is the policy
        # working, not a contradiction; on macOS the same skip is a failure to produce the
        # coverage the row claims. Only the host decides which, so the check reads it.
        root = tree()
        try:
            results(root, "Guarded", 3, ("aFifoTest",))
            original = checker.host_platform
            checker.host_platform = lambda: "windows"
            try:
                found = checker.problems_for(root)
            finally:
                checker.host_platform = original
        finally:
            shutil.rmtree(root)
        self.assertFalse([p for p in found if "aFifoTest" in p], found)

    def test_no_results_means_no_verdict(self):
        # An unbuilt tree is not evidence of zero skips. A gate that read a missing
        # directory as a clean run would report coverage it never saw.
        self.assertEqual(checker.read_results(tree()), None)

    def test_the_class_total_alone_does_not_convict_a_row(self):
        # The count is a filter; the skipped names decide. A skip in the class that is not
        # one of the declared tests must not be blamed on every row in it.
        root = tree()
        try:
            results(root, "Guarded", 4, ("someOtherTest",))
            found = checker.problems_for(root)
        finally:
            shutil.rmtree(root)
        self.assertEqual(found, [])
