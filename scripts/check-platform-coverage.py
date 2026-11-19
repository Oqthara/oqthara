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

2. Every test class that branches on the operating system appears in the suite table,
   whether or not its dependent tests carry their own guards. A suite that branches and
   passes on every branch is the most deceptive shape here: the green tick does not say
   which branch ran.

   An earlier revision exempted a suite from this whenever any one of its tests had a
   guard row. That is the hole this check exists to close, re-opened: the four suites
   that define a `WINDOWS` constant were exempt wholesale, so a *new, unguarded* branch
   added to any of them would pass. One declared test is not a declaration for the file
   around it.

3. A guard's direction agrees with its row. A test declaring `Runs on` Windows whose
   body starts with `assumeFalse(WINDOWS, ...)` claims coverage the run never produced.
   `assumeTrue(false, ...)` reached without first attempting the operation is refused
   too: it skips everywhere, proving nothing while looking guarded. Reached from a
   `catch` after the operation was genuinely tried, it is a capability probe, which is
   legitimate and is what the symbolic-link tests do.

   `POSIX` is read as `linux, macos`, because `File.separatorChar` and `unix:mode` are
   properties of the POSIX family rather than of one operating system. Without that
   expansion a `POSIX` guard matched no platform and check 3 skipped the comparison
   entirely, so a row contradicting it passed.

4. Every test named in either table exists, and its `File` cell names the class that
   actually declares it. A row naming a deleted test protects nothing while reading as
   coverage; a row naming the wrong class is the same defect one field over.

5. Every required context named in the policy is a job the workflows produce, by the
   path the policy attributes it to. The contexts themselves live in repository settings
   no gate can read — that half is a recorded observation with a date. This half is
   checkable: a renamed job would otherwise become a silently unsatisfiable requirement
   that blocks every merge, and a context attributed to the wrong workflow would pass
   while pointing a reader at a file that never reports it.

   Only a job's own `name` and its `jobs:` key are contexts. A `steps[*].name` such as
   `Check out` is not a check context, and an earlier revision collected every `name:`
   anywhere under a job, so a step name or an artifact name satisfied this check.

6. A test whose row says `run` here did run here. The policy requires `skipped=0` for a
   `run` disposition, and nothing read that number: the sentence asserted an enforcement
   that did not exist, which is the same failure as a declared platform with no run.
   Gradle records each suspended case by name in its JUnit XML, so the check reads them
   and points at the row that claimed the coverage. A row that already declares `skips
   on` this host is not a contradiction. With no results present the check reports
   nothing, because an unbuilt tree is not evidence of zero skips.

Comments are stripped before matching. A platform word in prose is not a dependency,
and during the audit that produced this policy a naive grep matched six javadoc
sentences, which is exactly how a table fills up with fiction.

Exit codes: 0 every platform dependency is declared and agrees, 1 a dependency is
undeclared or a declaration contradicts the code, 2 the policy, sources or test results
cannot be read.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

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
CONSTANT_GUARD = re.compile(
    r"assume(True|False)\(\s*([A-Za-z_][\w.]*)\s*(?:,|\))")

# A guard of `POSIX` (or any name containing it) covers the whole POSIX family. Every
# constant whose meaning is "this host is not a Windows host" is spelled a different way
# in each suite — `POSIX`, `EXECUTABLE`, `File.separatorChar == '/'` — so the guard is
# resolved by the name it is given rather than by the platform it names.
POSIX_GUARD = re.compile(r"(?i)posix")

# What makes a test class platform-dependent rather than merely platform-aware.
#
# Every alternative reads the *host*, not a platform name used as data. That distinction
# matters: `PlatformSupportTest` and `RuntimeProvenanceTest` construct
# `new Platform("linux", "x86_64")` to exercise the resolver, and a bare `\blinux\b`
# alternative flagged both as branching suites. Naming a platform is not branching on one.
BRANCH = re.compile(
    r'os\.name|\bWINDOWS\b|\bPOSIX\b|\bisWindows\b|\bisMac\b|'
    r'File\.separatorChar|@EnabledOnOs|@DisabledOnOs|'
    r'"unix:|\.exe\b|\.bat\b')

# A test that gives up on a platform without an assumption call. This is the shape that
# made finding 20 possible to miss: `Files.getAttribute(p, "unix:mode")` throws on
# Windows, the test returns from the catch before asserting anything, and JUnit records
# the test as a *pass*, not a skip. Every piece of tooling that counts skips — Gradle's
# report, `events("failed", "skipped")`, and the `skipped=0` disposition in the policy —
# reads it as coverage that exists.
#
# Only a `return` inside a `catch` counts. A `return` anywhere else is ordinary control
# flow, and a method that already carries an `assume*` is handled by check 1.
SILENT_RETURN = re.compile(r"\breturn\b")

# Gradle writes one `TEST-<class>.xml` per test class under each module's
# `build/test-results/test`. Its `<testsuite>` element carries `tests` and `skipped`, which
# is the only place the number check 6 makes a claim about actually appears.
RESULTS = "*/build/test-results/test/TEST-*.xml"

# The apply script encodes the same required set as the ruleset, so a context added to
# the policy and not to the script is reverted the next time anyone re-applies protection.
# It is read with its own pattern rather than by parsing JSON out of bash.
SCRIPT_CONTEXT = re.compile(r'\{"context":\s*"([^"]+)"\}')



def strip_comments(source: str, literals: bool = True) -> str:
    """Removes comments, and by default blanks the contents of string and char literals.

    Comments go so a platform word in prose is not read as a dependency: the audit that
    produced this policy matched six javadoc sentences before that was done.

    Literals go for a subtler reason, and only when `literals` is true. `balanced`,
    `method_bodies` and `trapped` count braces, and a brace inside a literal is not
    structure. `ServerTest` writes the frame `"{}"` and the malformed payload
    `"{not json"`; the second one unbalanced the count, so `balanced` ran to the end of
    the file and swallowed every method that followed — including their `catch` blocks,
    which then read as silent platform returns belonging to a test that has neither.

    Branch *detection* is the opposite case and passes `literals=False`: `"java.exe"` and
    `"windows"` are genuine platform references, so blanking them would hide the
    dependency the check exists to find.

    Lengths are preserved either way, so an offset into the result is the same offset
    into the original and `trapped(body, match.start())` keeps working.
    """
    out: list[str] = []
    index, size = 0, len(source)
    while index < size:
        char = source[index]
        if char == "/" and index + 1 < size and source[index + 1] == "/":
            while index < size and source[index] != "\n":
                out.append(" ")
                index += 1
        elif char == "/" and index + 1 < size and source[index + 1] == "*":
            while index < size and not source.startswith("*/", index):
                out.append("\n" if source[index] == "\n" else " ")
                index += 1
            for _ in range(min(2, size - index)):
                out.append(" ")
                index += 1
        elif source.startswith('"""', index):
            out.append('"""')
            index += 3
            while index < size and not source.startswith('"""', index):
                out.append("\n" if source[index] == "\n" else
                           (" " if literals else source[index]))
                index += 1
            if index < size:
                out.append('"""')
                index += 3
        elif char in ('"', "'"):
            quote = char
            out.append(quote)
            index += 1
            while index < size and source[index] != quote:
                if source[index] == "\\" and index + 1 < size:
                    if literals:
                        out.extend((" ", " "))
                    else:
                        out.extend((source[index], source[index + 1]))
                    index += 2
                    continue
                out.append("\n" if source[index] == "\n" else
                           (" " if literals else source[index]))
                index += 1
            if index < size:
                out.append(quote)
                index += 1
        else:
            out.append(char)
            index += 1
    return "".join(out)


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
    """The platform names in a `Runs on` or `Skips on` cell, in declaration order.

    `posix` is expanded to `linux, macos` so a row written in the vocabulary the code
    uses is comparable to a guard written the same way.
    """
    text = cell.replace("`", "").strip().lower()
    if text in ("", "—", "-", "none"):
        return []
    names: list[str] = []
    if re.search(r"\bposix\b", text):
        names.extend(["linux", "macos"])
    for name in PLATFORMS:
        if re.search(rf"\b{name}\b", text) and name not in names:
            names.append(name)
    return [name for name in PLATFORMS if name in names]


def guard_platforms(target: str) -> list[str]:
    """The platforms a guard's constant actually covers.

    `POSIX` names the family rather than one operating system, and every suite spells
    its own equivalent — `POSIX`, `EXECUTABLE`. Resolving by the name keeps check 3
    comparing rather than silently skipping, which is what it used to do: a target that
    was not literally `linux`, `macos` or `windows` hit `continue` and no contradiction
    was ever reported.
    """
    lowered = target.lower()
    if POSIX_GUARD.search(lowered):
        return ["linux", "macos"]
    if lowered in PLATFORMS:
        return [lowered]
    return []


def silent_returns(body: str) -> list[int]:
    """Offsets of every `return` that leaves the test method, inside a `catch`.

    A test that catches an unsupported operation and returns asserts nothing on the
    platform that raised it, while the runner records a pass. See `SILENT_RETURN`.

    Two things are excluded, and both are false positives this function produced before
    they were:

    - **A `return` in a lambda.** `FileOperationsTest` submits a task that answers
      `return true` / `return false`, and those leave the lambda rather than the test; a
      test that asserts two lines further down is not skipping anything. Lambdas are
      found by their `-> {` bodies, so a lambda's own `catch` is excluded with it.
    - **A `return` in a nested block.** A return has the same brace depth as the `catch`
      that contains it, so comparing depth to the catch's own depth is what separates
      "leaves the method" from "leaves a loop inside the catch".
    """
    lambda_ranges = []
    for match in re.finditer(r"->\s*\{", body):
        start = match.end() - 1
        lambda_ranges.append((start, balanced(body, start)))

    catch_ranges = []
    for match in re.finditer(r"catch\s*\([^)]*\)\s*\{", body):
        start = match.end() - 1
        if any(begin < start < end for begin, end in lambda_ranges):
            continue
        catch_ranges.append(start)

    offsets = []
    depth = 0
    catch_depths: dict[int, int] = {}
    index = 0
    while index < len(body):
        char = body[index]
        if char == "{":
            depth += 1
            if index in catch_ranges:
                catch_depths[index] = depth
        elif char == "}":
            depth -= 1
        elif char == "r" and SILENT_RETURN.match(body, index):
            for start, catch_depth in catch_depths.items():
                if start < index and depth == catch_depth:
                    offsets.append(index)
                    break
        index += 1
    return offsets


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
        if not cells or set(cells[0]) <= set("-") or cells[0] in ("Test", "Suite", "Context", "Job"):
            continue
        rows.append(cells)
    return rows


def read_guard_rows(policy: str) -> dict[str, tuple[str, list[str], list[str]]]:
    """Both declaration tables: test name -> (file, runs on, skips on).

    A silent return and an `assume*` guard are the same declaration with the same
    consequence — an environment this test does not prove anything on — so they share one
    lookup and differ only in the shape the gate found in the code.
    """
    rows: dict[str, tuple[str, list[str], list[str]]] = {}
    for heading in ("## Declared environment guards", "## Declared silent returns"):
        for cells in table_rows(policy, heading):
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


def read_required_contexts(policy: str) -> dict[str, str]:
    """The required-context table: context -> the workflow the policy attributes it to."""
    contexts: dict[str, str] = {}
    for cells in table_rows(policy, "## Required merge contexts"):
        if len(cells) < 2:
            continue
        contexts[cells[0].strip("`")] = cells[1].strip("`")
    return contexts


def job_contexts(cwd: Path) -> dict[str, set[str]]:
    """Every check context the workflows can report, attributed to the file reporting it.

    A GitHub context is a job's `name:` when it has one and its `jobs:` key otherwise,
    which is why reading only `name:` misses `analyze`. A matrix job's context is
    assembled from its own axes. CircleCI reports `ci/circleci: <job name from the
    workflow>`, which is not a GitHub job at all.

    Only *job-level* names count. An earlier revision recursed for every `name:` under a
    job and so collected every step name and artifact name — `Check out`, `Set up JDK
    21` — which meant a required-context row naming a checkout step satisfied the check
    while naming nothing that ever reports a status.
    """
    import yaml

    contexts: dict[str, set[str]] = {}
    workflow_files = sorted(
        [* (cwd / ".github/workflows").glob("*.yml"),
         * (cwd / ".github/workflows").glob("*.yaml")])
    for workflow in workflow_files:
        document = yaml.safe_load(workflow.read_text(encoding="utf-8")) or {}
        jobs = document.get("jobs") or {}
        if not isinstance(jobs, dict):
            continue
        produced = contexts.setdefault(workflow.name, set())
        for key, job in jobs.items():
            produced.add(key)
            if not isinstance(job, dict):
                continue
            axes: dict[str, list[str]] = {}
            _collect_axes(job, axes)
            names = {job["name"]} if isinstance(job.get("name"), str) else {key}
            for name in names:
                if "${{" not in name:
                    produced.add(" ".join(name.split()))
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
                            produced.add(" ".join(expanded.split()))

    circleci = cwd / ".circleci/config.yml"
    if circleci.is_file():
        produced = contexts.setdefault(str(Path(".circleci/config.yml")), set())
        document = yaml.safe_load(circleci.read_text(encoding="utf-8")) or {}
        for workflow in (document.get("workflows") or {}).values():
            for job in (workflow or {}).get("jobs") or []:
                if isinstance(job, str):
                    produced.add(f"ci/circleci: {job}")
                elif isinstance(job, dict):
                    for key, settings in job.items():
                        label = settings.get("name", key) if isinstance(settings, dict) else key
                        produced.add(f"ci/circleci: {label}")
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


def host_platform() -> str:
    """The platform this run is happening on, in the policy's vocabulary."""
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def read_results(cwd: Path) -> dict[str, tuple[int, int, set[str]]] | None:
    """Test class -> (tests, skipped, skipped names) from the last run, or None.

    Returning None rather than an empty dict matters: "no results" and "results with no
    skips" are different facts, and only the second is evidence. A gate that treated a
    missing build directory as a clean run would report coverage it never saw.
    """
    found = sorted(cwd.glob(RESULTS))
    if not found:
        return None
    results: dict[str, tuple[int, int, set[str]]] = {}
    for path in found:
        root = ElementTree.parse(path).getroot()
        suite = root.find("testsuite") if root.tag != "testsuite" else root
        if suite is None:
            continue
        name = (suite.get("name") or "").rsplit(".", 1)[-1]
        tests, skipped, names = results.get(name, (0, 0, set()))
        for case in suite.iter("testcase"):
            if case.find("skipped") is None:
                continue
            skipped += 1
            # Gradle writes the declaration form, `name()`, not the name.
            names.add((case.get("name") or "").split("(")[0])
        tests += int(suite.get("tests") or 0)
        results[name] = (tests, skipped, names)
    return results


def sources(cwd: Path) -> list[Path]:
    """Every Java test source in the tree, including modules outside `oq-*`."""
    found: set[Path] = set()
    for module in ("oq-*", "bench-jmh"):
        found.update(cwd.glob(f"{module}/src/test/java/**/*.java"))
    return sorted(found)


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
    # Branch detection reads literals; structure analysis blanks them.
    literal_text = {path.stem: strip_comments(path.read_text(encoding="utf-8"), literals=False)
                    for path in sources(cwd)}
    guarded: dict[str, tuple[str, str]] = {}
    silent: dict[str, tuple[str, str]] = {}
    for suite, code in bodies.items():
        for name, body in method_bodies(code):
            if GUARD.search(body):
                guarded[name] = (suite, body)
            elif silent_returns(body):
                silent[name] = (suite, body)

    # 1. Every guarded test is declared.
    for name, (suite, _) in sorted(guarded.items()):
        if name not in guards:
            problems.append(
                f"{suite}.{name} carries a platform guard but has no row in "
                f"{POLICY.as_posix()}; declare the environments it runs and skips in"
            )

    # 1b. Every test that gives up inside a `catch` is declared too. It asserts nothing
    # on the platform that raised, and the runner records a pass rather than a skip, so
    # no skip count can catch it.
    for name, (suite, _) in sorted(silent.items()):
        if name not in guards:
            problems.append(
                f"{suite}.{name} returns from a `catch` before asserting, so it proves nothing "
                f"on the platform that raised, yet JUnit records a pass; give it a row in "
                f"{POLICY.as_posix()} or use an `assume*` so the skip is visible"
            )

    # 4a. Every declared guard row names a test that exists, in the suite it names.
    all_bodies = {suite: dict(method_bodies(code)) for suite, code in bodies.items()}
    for name, (suite, _, _) in sorted(guards.items()):
        if name not in guarded and name not in silent:
            problems.append(
                f"{POLICY.as_posix()} declares `{name}` in {suite} but no such platform-dependent "
                f"test exists; a stale row protects nothing while reading as coverage"
            )
        elif suite in all_bodies and name not in all_bodies[suite]:
            holders = sorted(other for other, methods in all_bodies.items() if name in methods)
            problems.append(
                f"{POLICY.as_posix()} attributes `{name}` to {suite} but the test is declared in "
                f"{', '.join(holders) or 'no class'}; a row pointing at the wrong class sends a "
                f"reader to a file that does not carry the guard"
            )

    # 2. Every suite that branches on the operating system is declared. A per-test guard
    # is not a declaration for the file around it, so there is no exemption here.
    for suite, code in sorted(bodies.items()):
        if not BRANCH.search(literal_text[suite]) or suite in suites:
            continue
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
            if positive and target.lower() == "false":
                if not trapped(body, match.start()):
                    problems.append(
                        f"{suite}.{name} refuses with `assumeTrue(false, ...)` without first "
                        f"attempting the operation, so it skips everywhere; a test that never "
                        f"runs is not coverage"
                    )
                continue
            covered = guard_platforms(target)
            if row is None or not covered:
                continue
            _, runs, skips = row
            declared = runs if positive else skips
            missing = [platform for platform in covered if platform not in declared]
            if missing:
                problems.append(
                    f"{suite}.{name} guards with "
                    f"`assume{'True' if positive else 'False'}({target})`, which covers "
                    f"{', '.join(covered)}, but its policy row says runs on "
                    f"{runs or 'nothing'} / skips on {skips or 'nothing'}; the row claims "
                    f"coverage the run did not produce"
                )

    # 5. Every required context is a job the workflows produce, by the path it names.
    produced = job_contexts(cwd)
    for context, workflow in sorted(read_required_contexts(policy).items()):
        if context in produced.get(workflow, set()):
            continue
        elsewhere = sorted(path for path, names in produced.items() if context in names)
        if elsewhere:
            problems.append(
                f"{POLICY.as_posix()} attributes required context `{context}` to {workflow}, but "
                f"{', '.join(elsewhere)} produces it; a reader is sent to a file that never "
                f"reports that status"
            )
        else:
            problems.append(
                f"{POLICY.as_posix()} lists required context `{context}` but no workflow "
                f"produces a job by that name; an unsatisfiable requirement blocks every merge"
            )

    # 6. A test declared to run on this platform did run on it. The policy's disposition
    # says `skipped=0` is required for a `run` row; nothing read the number, so the claim
    # rested on a reviewer noticing a skip count. Gradle records it per class, and a `run`
    # row whose test is named among that class's skips is claiming coverage this run did
    # not produce.
    #
    # The count is a class total, so it is used as a filter rather than as the claim: the
    # skipped case names themselves decide which row is contradicted. Absent results the
    # check reports nothing. An unbuilt tree has no evidence either way, and inventing a
    # verdict from a missing file is the failure this whole gate exists to catch.
    results = read_results(cwd)
    if results is not None:
        here = host_platform()
        for name, (holdings, runs, skips) in sorted(guards.items()):
            observed = results.get(holdings)
            if observed is None or observed[1] == 0 or here in skips:
                continue
            if name not in observed[2]:
                continue
            problems.append(
                f"{holdings}.{name} is declared to run on {here} and the last run skipped "
                f"it; a skipped test on a `run` row is not coverage, and the row claims "
                f"coverage the run did not produce"
            )

    return problems


def main(cwd: str = ".") -> int:
    cwd = Path(cwd)
    try:
        problems = problems_for(cwd)
    except (OSError, ElementTree.ParseError) as error:
        print(f"cannot read the platform validation policy, test sources or results: "
              f"{error}", file=sys.stderr)
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
    observed = read_results(cwd)
    # Which of the two modes ran is printed rather than left to the reader: a green tick
    # that silently fell back to "no results" would look exactly like one that verified
    # `skipped=0`, and that ambiguity is the defect this gate was written to remove.
    evidence = (f"{len(observed)} test classes from the last run" if observed is not None
                else "no test results present, so `skipped=0` was not verified")
    print(
        f"Every platform dependency is declared and agrees "
        f"({len(read_guard_rows(policy))} guarded tests, "
        f"{len(read_suite_rows(policy))} branching suites, "
        f"{len(read_required_contexts(policy))} required contexts, "
        f"{len(sources(cwd))} test files; {evidence})."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
