#!/usr/bin/env python3
"""Checks that every platform-dependent test declares the platforms that validate it.

Finding 20 of `reviews/PR-272.md` was a Windows-only defect — `Path.resolve("<t>")`
throws `InvalidPathException` on Windows and quietly finds nothing on macOS and Linux
— that survived a green local suite, nine green gates and a green conformance corpus.
Every check that reported success was correct about the machine it ran on. No check
was answering "has this been run where the answer differs".

`docs/governance/platform-validation-policy.md` (ADR-0074) records the answer per
test. This gate checks that the record still matches the code, because a policy
document nothing verifies is a description of what someone intended once.

What is checked:

1. Every test that carries an `assumeTrue` / `assumeFalse` guard appears in the
   policy's guard table. An undeclared guard is a platform dependency nobody wrote
   down, and leaving the table empty is the cheap way out.

2. Every test class that branches on the operating system, and whose dependency is not
   already declared test by test, appears in the suite table. A suite that branches and
   passes on every branch is the most deceptive shape here: the green tick does not say
   which branch ran.

3. A guard's direction agrees with its row. A test declaring `Runs on` Windows whose
   body starts with `assumeFalse(WINDOWS, ...)` claims coverage the run never produced.
   `assumeTrue(false, ...)` reached without first attempting the operation is refused
   too: it skips everywhere, proving nothing while looking guarded. Reached from a
   `catch` after the operation was genuinely tried, it is a capability probe, which is
   legitimate and is what the symbolic-link tests do.

4. Every test named in either table exists. A row naming a deleted test protects
   nothing while reading as coverage.

5. Every required context named in the policy is a job the workflows produce. The
   contexts themselves live in repository settings no gate can read — that half is a
   recorded observation with a date. This half is checkable: a renamed job would
   otherwise become a silently unsatisfiable requirement that blocks every merge.

Comments are stripped before matching. A platform word in prose is not a dependency,
and during the audit that produced this policy a naive grep matched six javadoc
sentences, which is exactly how a table fills up with fiction.

Exit codes: 0 every platform dependency is declared and agrees, 1 a dependency is
undeclared or a declaration contradicts the code, 2 the policy or sources cannot be
read.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

POLICY = Path("docs/governance/platform-validation-policy.md")

# The environments the support list names. `macos` and `linux` cover both JDK legs:
# a guard is about the operating system, and no test in this tree branches on the JDK.
PLATFORMS = ("linux", "macos", "windows")

# Any assumption call makes a test environment-dependent, because it can turn an
# assertion into a skip. A guard whose condition is a bare name carries a direction
# check 3 can read; a capability probe such as
# `assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"))`
# can only be checked for being declared, which is still the point.
GUARD = re.compile(r"assume(True|False)\s*\(")
CONSTANT_GUARD = re.compile(r"assume(True|False)\(\s*([A-Za-z_][\w.]*)\s*(?:,|\))")

# What makes a test class platform-dependent rather than merely platform-aware.
BRANCH = re.compile(r'os\.name|\bWINDOWS\b|\bPOSIX\b|\bisWindows\b|\bisMac\b')


def strip_comments(source: str) -> str:
    """Removes comments so a platform word in prose is not read as a dependency."""
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//[^\n]*", "", source)


def balanced(code: str, open_index: int) -> int:
    """The index of the brace closing the one at `open_index`."""
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] == "{":
            depth += 1
        elif code[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    return len(code)


def method_bodies(code: str) -> list[tuple[str, str]]:
    """Every `void name(...) { ... }` in a source file, with its balanced body."""
    found = []
    for match in re.finditer(r"void\s+(\w+)\s*\([^)]*\)[^{;]*\{", code):
        start = match.end() - 1
        found.append((match.group(1), code[start:balanced(code, start) + 1]))
    return found


def trapped(body: str, position: int) -> bool:
    """Whether a statement at `position` sits inside a `catch` block.

    A guard reached from `catch` follows an attempt that failed, so it reports a
    capability the host lacks rather than a decision not to try.
    """
    for match in re.finditer(r"catch\s*\([^)]*\)\s*\{", body):
        start = match.end() - 1
        if start <= position <= balanced(body, start):
            return True
    return False


def parse_platforms(cell: str) -> list[str]:
    """The platform names in a `Runs on` or `Skips on` cell, in declaration order."""
    text = cell.replace("`", "").strip().lower()
    if text in ("", "—", "-", "none"):
        return []
    return [name for name in PLATFORMS if re.search(rf"\b{name}\b", text)]


def table_rows(policy: str, heading: str) -> list[list[str]]:
    """The body rows of the table under `heading`, split into cells.

    The table ends at the next heading of any level, so the `### Not required` table
    that follows the required contexts is never read as required.
    """
    rows, inside = [], False
    for line in policy.splitlines():
        if line.lstrip().startswith("#"):
            inside = line.startswith(heading)
            continue
        if not inside or not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if not cells or set(cells[0]) <= set("-") or cells[0] in ("Test", "Suite", "Context"):
            continue
        rows.append(cells)
    return rows


def read_guard_rows(policy: str) -> dict[str, tuple[str, list[str], list[str]]]:
    """The guard table: test name -> (file, runs on, skips on)."""
    rows: dict[str, tuple[str, list[str], list[str]]] = {}
    for cells in table_rows(policy, "## Declared environment guards"):
        if len(cells) < 4:
            continue
        rows[cells[0].strip("`")] = (
            cells[1].strip("`"), parse_platforms(cells[2]), parse_platforms(cells[3]))
    return rows


def read_suite_rows(policy: str) -> dict[str, list[str]]:
    """The branching-suite table: suite name -> environments it must run in."""
    rows: dict[str, list[str]] = {}
    for cells in table_rows(policy, "## Platform-branching suites"):
        if len(cells) < 2:
            continue
        platforms = parse_platforms(cells[1])
        if platforms:
            rows[cells[0].strip("`")] = platforms
    return rows


def read_required_contexts(policy: str) -> list[str]:
    """Every context named in the policy's required-merge-context table."""
    return [cells[0].strip("`") for cells in table_rows(policy, "## Required merge contexts")]


def job_contexts(cwd: Path) -> set[str]:
    """Every check context the workflows can report.

    A GitHub context is a job's `name:` when it has one and its `jobs:` key otherwise,
    which is why reading only `name:` misses `analyze`. A matrix job's context is
    assembled from its own axes. CircleCI reports `ci/circleci: <job name from the
    workflow>`, which is not a GitHub job at all.
    """
    import yaml

    contexts: set[str] = set()
    for workflow in sorted((cwd / ".github/workflows").glob("*.yml")):
        document = yaml.safe_load(workflow.read_text(encoding="utf-8")) or {}
        jobs = document.get("jobs") or {}
        if not isinstance(jobs, dict):
            continue
        for key, job in jobs.items():
            contexts.add(key)
            if not isinstance(job, dict):
                continue
            axes: dict[str, list[str]] = {}
            _collect_axes(job, axes)
            for name in _collect_names(job):
                if "${{" not in name:
                    contexts.add(name)
                    continue
                # `${{ matrix.os }} / JDK ${{ matrix.java }}` expands over the axes the
                # job itself declares. A template that still contains a placeholder
                # after expansion is not a context, so it is dropped rather than
                # guessed at.
                for os_name in axes.get("os", [""]):
                    for java in axes.get("java", [""]):
                        expanded = (name.replace("${{ matrix.os }}", os_name)
                                    .replace("${{ matrix.java }}", java))
                        if "${{" not in expanded:
                            contexts.add(" ".join(expanded.split()))

    circleci = cwd / ".circleci/config.yml"
    if circleci.is_file():
        document = yaml.safe_load(circleci.read_text(encoding="utf-8")) or {}
        for workflow in (document.get("workflows") or {}).values():
            for job in (workflow or {}).get("jobs") or []:
                if isinstance(job, str):
                    contexts.add(f"ci/circleci: {job}")
                elif isinstance(job, dict):
                    for key, settings in job.items():
                        label = settings.get("name", key) if isinstance(settings, dict) else key
                        contexts.add(f"ci/circleci: {label}")
    return contexts


def _collect_axes(node: object, axes: dict[str, list[str]]) -> None:
    """Collects the `os` and `java` matrix axes a job declares."""
    if isinstance(node, list):
        for item in node:
            _collect_axes(item, axes)
    elif isinstance(node, dict):
        for key in ("os", "java"):
            value = node.get(key)
            if isinstance(value, list):
                axes[key] = [str(entry) for entry in value]
        for value in node.values():
            _collect_axes(value, axes)


def _collect_names(node: object) -> set[str]:
    """Every `name:` string inside a job definition."""
    if isinstance(node, list):
        return {name for item in node for name in _collect_names(item)}
    if isinstance(node, dict):
        found = {node["name"]} if isinstance(node.get("name"), str) else set()
        return found | {name for value in node.values() for name in _collect_names(value)}
    return set()


def sources(cwd: Path) -> list[Path]:
    return sorted(cwd.glob("oq-*/src/test/java/**/*.java"))


def problems_for(cwd: Path) -> list[str]:
    policy_path = cwd / POLICY
    if not policy_path.is_file():
        raise OSError(f"{POLICY} is missing")
    policy = policy_path.read_text(encoding="utf-8")
    guards = read_guard_rows(policy)
    suites = read_suite_rows(policy)
    problems: list[str] = []

    bodies = {path.stem: strip_comments(path.read_text(encoding="utf-8"))
              for path in sources(cwd)}
    guarded: dict[str, tuple[str, str]] = {}
    for suite, code in bodies.items():
        for name, body in method_bodies(code):
            if GUARD.search(body):
                guarded[name] = (suite, body)

    # 1. Every guarded test is declared.
    for name, (suite, _) in sorted(guarded.items()):
        if name not in guards:
            problems.append(
                f"{suite}.{name} carries a platform guard but has no row in "
                f"{POLICY.as_posix()}; declare the environments it runs and skips in"
            )

    # 4a. Every declared guard row names a test that exists.
    for name, (suite, _, _) in sorted(guards.items()):
        if name not in guarded:
            problems.append(
                f"{POLICY.as_posix()} declares `{name}` in {suite} but no such guarded test "
                f"exists; a stale row protects nothing while reading as coverage"
            )

    # 2. Every suite that branches without per-test declarations is declared.
    declared_suites = {suite for suite, _ in guarded.values()}
    for suite, code in sorted(bodies.items()):
        if not BRANCH.search(code) or suite in declared_suites:
            continue
        if suite not in suites:
            problems.append(
                f"{suite} branches on the operating system but has no row in "
                f"{POLICY.as_posix()}; a suite green on every branch proves one branch"
            )

    # 4b. Every declared suite exists.
    for suite in sorted(suites):
        if suite not in bodies:
            problems.append(
                f"{POLICY.as_posix()} declares suite `{suite}` but no such test class exists"
            )

    # 3. A guard's direction agrees with its row, and a refusal is not a guard.
    for name, (suite, body) in sorted(guarded.items()):
        row = guards.get(name)
        for match in CONSTANT_GUARD.finditer(body):
            positive, target = match.group(1) == "True", match.group(2)
            if positive and target == "false":
                if not trapped(body, match.start()):
                    problems.append(
                        f"{suite}.{name} refuses with `assumeTrue(false, ...)` without first "
                        f"attempting the operation, so it skips everywhere; a test that never "
                        f"runs is not coverage"
                    )
                continue
            if row is None:
                continue
            _, runs, skips = row
            platform = target.lower()
            if platform not in PLATFORMS:
                continue
            agrees = platform in runs if positive else platform in skips
            if not agrees:
                problems.append(
                    f"{suite}.{name} guards with "
                    f"`assume{'True' if positive else 'False'}({target})` but its policy row "
                    f"says runs on {runs or 'nothing'} / skips on {skips or 'nothing'}; the row "
                    f"claims coverage the run did not produce"
                )

    # 5. Every required context is a job the workflows produce.
    produced = job_contexts(cwd)
    for context in read_required_contexts(policy):
        if context not in produced:
            problems.append(
                f"{POLICY.as_posix()} lists required context `{context}` but no workflow "
                f"produces a job by that name; an unsatisfiable requirement blocks every merge"
            )

    return problems


def main(cwd: str = ".") -> int:
    cwd = Path(cwd)
    try:
        problems = problems_for(cwd)
    except OSError as error:
        print(f"cannot read the platform validation policy or test sources: {error}",
              file=sys.stderr)
        return 2
    except ImportError:
        print("PyYAML is unavailable, so the workflow contexts cannot be verified; a check "
              "that silently degrades is the weakness it is meant to prevent", file=sys.stderr)
        return 2

    if problems:
        print(f"Platform validation gate failed ({len(problems)} problem(s)):")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    policy = (cwd / POLICY).read_text(encoding="utf-8")
    print(
        f"Every platform dependency is declared and agrees "
        f"({len(read_guard_rows(policy))} guarded tests, "
        f"{len(read_suite_rows(policy))} branching suites, "
        f"{len(read_required_contexts(policy))} required contexts, "
        f"{len(sources(cwd))} test files)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
