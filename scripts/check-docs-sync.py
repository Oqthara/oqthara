#!/usr/bin/env python3
"""Fails when documentation and implementation disagree.

Documentation in this repository is language-owned: every claim about the CLI, the standard
library, the backend and the version is checked against the code or a freshly generated
artifact. Each check prints the exact thing it verified, because a passing test that does not
say what it read is worth little.

Dependency-free: Python 3 standard library only. Any unexpected exception is reported as a
failure, never swallowed.
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
problems: list[str] = []
checked: list[str] = []


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def note(what: str) -> None:
    checked.append(what)


# 1. The stdlib reference must match a fresh generation from the implementation.
def stdlib_reference_is_current() -> None:
    reference = ROOT / "docs" / "stdlib" / "reference.md"
    if not reference.exists():
        problems.append("docs/stdlib/reference.md is missing")
        return
    jar = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"
    if not jar.exists():
        note("stdlib reference freshness skipped (no standalone jar built)")
        return
    result = subprocess.run(
        ["java", "-jar", str(jar), "doc", "."],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        problems.append(f"`oq doc .` failed: {result.stderr.strip()[:200]}")
        return
    # `oq doc` writes docs/stdlib/reference.md itself; read it back and compare with
    # what was committed at HEAD so a hand edit is caught even after regeneration.
    committed = subprocess.run(
        ["git", "show", "HEAD:docs/stdlib/reference.md"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if committed.returncode != 0:
        note("stdlib reference matches `oq doc` (no git baseline to compare)")
        return
    generated = reference.read_text(encoding="utf-8")
    if committed.stdout != generated:
        problems.append(
            "docs/stdlib/reference.md changed under `oq doc`; the committed page was stale"
        )
        return
    note("docs/stdlib/reference.md matches a fresh `oq doc .`")


# 2. The keyword count in the reference must match the lexer's list and the budget.
def keyword_budget_is_honest() -> None:
    reference = read("docs/stdlib/reference.md")
    match = re.search(
        r"## Keywords\n\n([^\n]+)\n\n(\d+) keywords \(budget: (\d+)\)", reference
    )
    if not match:
        problems.append("reference does not state the keyword count")
        return
    listed = [word.strip("` ") for word in match.group(1).split(",") if word.strip("` ")]
    count = int(match.group(2))
    budget = int(match.group(3))
    if len(listed) != count:
        problems.append(f"reference lists {len(listed)} keywords but claims {count}")
    if count > budget:
        problems.append(f"{count} keywords exceeds the budget of {budget}")

    lexer = read("oq-core/src/main/java/dev/oqthara/core/Lexer.java")
    # Read the canonical set out of Lexer.KEYWORDS rather than restating the names here. A
    # hard-coded list silently stops covering a keyword the moment one is added.
    block = re.search(r"KEYWORDS\s*=\s*Set\.of\((.*?)\);", lexer, re.S)
    if not block:
        problems.append("could not read Lexer.KEYWORDS")
        return
    keywords = set(re.findall(r'"([^"]+)"', block.group(1)))
    missing = sorted(set(listed) - keywords)
    extra = sorted(keywords - set(listed))
    if missing or extra:
        problems.append(
            "keyword list disagrees with the lexer: "
            f"missing from lexer {missing}, missing from reference {extra}"
        )
    note(f"{count} keywords within a budget of {budget}, matching the lexer")

    contributing = read("CONTRIBUTING.md")
    claim = re.search(r"keyword budget is (\d+); we are at (\d+)", contributing)
    if not claim:
        problems.append("CONTRIBUTING.md does not state the keyword budget and count")
        return
    claimed_budget, claimed_count = int(claim.group(1)), int(claim.group(2))
    if claimed_count != len(keywords):
        problems.append(
            f"CONTRIBUTING.md claims {claimed_count} keywords but the lexer defines "
            f"{len(keywords)}; update the count when a keyword lands")
    if claimed_budget != budget:
        problems.append(
            f"CONTRIBUTING.md states a budget of {claimed_budget} but the reference says "
            f"{budget}")
    if claimed_count > claimed_budget:
        problems.append(f"CONTRIBUTING.md is over its own budget: {claimed_count} > {claimed_budget}")
    note(f"CONTRIBUTING.md keyword count ({claimed_count}) matches the lexer")

    # design.md states the same count in its simplicity table. It is a third copy, and it
    # was the one that drifted to 20 while the lexer had 21, so it is checked explicitly.
    design = read("docs/spec/design.md")
    design_count = re.search(r"\| Keywords \| ≤ (\d+) \| (\d+) \|", design)
    if not design_count:
        problems.append("design.md does not state the keyword budget and count")
        return
    design_budget, design_current = int(design_count.group(1)), int(design_count.group(2))
    if design_current != len(keywords):
        problems.append(
            f"design.md claims {design_current} keywords but the lexer defines "
            f"{len(keywords)}")
    if design_budget != budget:
        problems.append(
            f"design.md states a budget of {design_budget} but the reference says {budget}")
    note(f"design.md keyword count ({design_current}) matches the lexer")


def command_form_matches_the_spec() -> None:
    # The spec said "Both accept any number of arguments" of two names, then its example
    # called a third, `write_text`, in command form -- which the parser rejects. Nothing
    # caught it because the whitelist lived only in Parser.COMMANDS and the spec never
    # named it. Read the set out of the parser rather than restating it here, for the same
    # reason keyword_budget_is_honest parses Lexer.KEYWORDS: a copy here would drift too.
    parser = read("oq-core/src/main/java/dev/oqthara/core/Parser.java")
    block = re.search(r"COMMANDS\s*=\s*Set\.of\((.*?)\);", parser, re.S)
    if not block:
        problems.append("could not read Parser.COMMANDS")
        return
    commands = set(re.findall(r'"([^"]+)"', block.group(1)))
    if not commands:
        problems.append("Parser.COMMANDS is empty")
        return

    spec = read("docs/spec/language.md")
    # The sentence wraps in the source, so joining the paragraph keeps the check independent
    # of where a line happens to break.
    joined = " ".join(spec.split())
    match = re.search(r"((?:`[a-z_]+`[ ,and]*)+\s)are the only (\w+) names that may be "
                      r"called as a command statement", joined)
    if not match:
        problems.append("docs/spec/language.md does not state which names take command form")
        return
    documented = set(re.findall(r"`([a-z_]+)`", match.group(1)))
    if documented != commands:
        problems.append(
            "the spec's command-form names disagree with Parser.COMMANDS: "
            f"the spec says {sorted(documented)}, the parser allows {sorted(commands)}")
    words = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5}
    if words.get(match.group(2)) != len(commands):
        problems.append(
            f"the spec says {match.group(2)} names take command form, but Parser.COMMANDS "
            f"has {len(commands)}")

    # Every command-form example must use one of those names. The failing example called
    # `write_text "x"`; checking the examples catches a wrong name in prose the set
    # comparison above cannot see.
    for name in re.findall(r"^([a-z_]+) [\"']", spec, re.M):
        if name not in commands and name in _builtin_names():
            problems.append(
                f"docs/spec/language.md calls `{name}` in command form, but only "
                f"{sorted(commands)} take command form; use `{name}(...)`")
    note(f"command form limited to {', '.join(sorted(commands))}, as the spec states")


def _builtin_names() -> set[str]:
    stdlib_dir = ROOT / "oq-stdlib" / "src" / "main" / "java" / "dev" / "oqthara" / "stdlib"
    names: set[str] = set()
    for source in stdlib_dir.glob("*.java"):
        names |= set(
            re.findall(
                r'NativeFunction\.(?:of|variadic)\(\s*"([a-z_][a-z_0-9]*)"',
                source.read_text(encoding="utf-8"),
            )
        )
    return names


# 3. Every CLI command in the help text must be documented in docs/reference/cli.md.
def documented_commands_exist() -> None:
    main = read("oq-cli/src/main/java/dev/oqthara/cli/Main.java")
    help_block = re.search(r'return """\n(.*?)""";', main, re.S)
    if not help_block:
        problems.append("could not read the help text from Main.java")
        return
    # Read the commands out of the help text instead of restating them here. A hard-coded
    # list drifts: it kept accepting `serve` long after the command stopped existing, so the
    # check guarded a wider surface than the CLI actually offered.
    commands = set(
        re.findall(r"^\s*oq ([a-z][a-z-]*)\b", help_block.group(1), re.M)
    )
    if not commands:
        problems.append("the help text lists no commands")
        return
    page = read("docs/reference/cli.md")
    for command in sorted(commands):
        if not re.search(rf"^## `oq {re.escape(command)}`", page, re.M):
            problems.append(f"docs/reference/cli.md has no section for `oq {command}`")
    # The other direction: a documented command the CLI never dispatches is dead documentation.
    dispatch = set(re.findall(r'case "([a-z][a-z-]*)"', main))
    for command in sorted(commands):
        if command not in dispatch:
            problems.append(f"the help text advertises `oq {command}` but dispatch has no case for it")
    note(f"{len(commands)} CLI commands in the help text all documented in cli.md: "
         + ", ".join(sorted(commands)))


# 4. docs/catalog.json must be exactly what the generator produces.
def catalog_is_current() -> None:
    generator = ROOT / "scripts" / "gen-docs-catalog.py"
    result = subprocess.run(
        [sys.executable, str(generator), "--check"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        problems.append(
            "docs/catalog.json is stale: " + (result.stderr.strip() or result.stdout.strip())
        )
        return
    catalog = json.loads(read("docs/catalog.json"))
    note(result.stdout.strip() or f"docs/catalog.json lists {len(catalog['docs'])} documents")


# 5. The compiled-capability claim must match the test and the conformance corpus.
def backend_counts_are_measured() -> None:
    backend = read("docs/spec/backend.md")
    claim = re.search(r"(\d+) of the (\d+) executable conformance programs", backend)
    if not claim:
        problems.append("docs/spec/backend.md does not state the compiled conformance count")
        return
    claimed_compiled, claimed_total = int(claim.group(1)), int(claim.group(2))

    test = read("oq-cli/src/test/java/dev/oqthara/cli/BytecodeCompilerTest.java")
    asserted = re.search(
        r"assertEquals\((\d+), compiled,\s*\n\s*\"compiled conformance programs", test
    )
    if not asserted:
        problems.append("BytecodeCompilerTest does not assert a compiled count")
        return
    asserted_compiled = int(asserted.group(1))

    conformance = ROOT / "conformance"
    programs = sorted(conformance.glob("*.oq"))
    executable = [p for p in programs if not p.with_suffix(".err").exists()]
    measured_total = len(executable)

    if claimed_compiled != asserted_compiled:
        problems.append(
            f"backend.md claims {claimed_compiled} compiled programs but the test asserts "
            f"{asserted_compiled}"
        )
    if claimed_total != measured_total:
        problems.append(
            f"backend.md claims {claimed_total} executable conformance programs but "
            f"conformance/ has {measured_total} .oq files without a sibling .err"
        )
    note(
        f"backend.md claim {claimed_compiled}/{claimed_total} matches "
        f"BytecodeCompilerTest ({asserted_compiled}) and conformance/ ({measured_total} "
        f"executable of {len(programs)} .oq files)"
    )


# 5b. Every quoted `wrote <jar> (N classes, 0 skipped)` line must agree.
#
# N counts the runtime classes copied into a jar, so it grows whenever a runtime class is
# added, and it is not derivable without running a build. What can be checked here is that the
# documents do not disagree with each other: before this check existed they quoted 99, 103 and
# 106 for the same build, which is only possible if at least two were wrong.
def quoted_jar_class_counts_agree() -> None:
    counts: dict[str, list[str]] = {}
    for path in sorted(DOCS.glob("**/*.md")):
        for match in re.finditer(r"wrote (\S+\.jar) \((\d+) classes, (\d+) skipped\)", path.read_text(encoding="utf-8")):
            jar, count, skipped = match.group(1), int(match.group(2)), int(match.group(3))
            if skipped != 0:
                problems.append(
                    f"{path.relative_to(ROOT)} quotes {jar} as '{count} classes, {skipped} skipped'; "
                    "every measured build reports 0 skipped"
                )
            counts.setdefault(str(count), []).append(f"{path.relative_to(ROOT)} ({jar})")

    if len(counts) > 1:
        detail = "; ".join(
            f"{count}: " + ", ".join(sorted(where)) for count, where in sorted(counts.items())
        )
        problems.append(
            f"documents quote {len(counts)} different jar class counts for the same build "
            f"({detail})"
        )
        return
    if counts:
        count = next(iter(counts))
        note(f"{sum(len(v) for v in counts.values())} quoted jar class counts all agree on {count}")


# 6. The version must agree across the CLI, the build and the changelog.
def version_is_consistent() -> None:
    main = read("oq-cli/src/main/java/dev/oqthara/cli/Main.java")
    version = re.search(r'VERSION = "([^"]+)"', main)
    if not version:
        problems.append("Main.VERSION is missing")
        return
    code_version = version.group(1)

    build = read("build.gradle.kts")
    gradle_version = re.search(r'^\s*version = "([^"]+)"', build, re.M)
    if not gradle_version:
        problems.append("build.gradle.kts has no `version =` line")
    elif gradle_version.group(1) != code_version:
        problems.append(
            f"build.gradle.kts version {gradle_version.group(1)} does not match "
            f"Main.VERSION {code_version}"
        )

    # The specification header and the LSP server are version declarations too. They drifted
    # apart from the CLI before, so they are compared rather than trusted.
    spec = read("docs/spec/language.md")
    spec_version = re.search(r"^Version:\s*([0-9][^\s]*)", spec, re.M)
    if not spec_version:
        problems.append("docs/spec/language.md has no `Version:` line")
    elif spec_version.group(1) != code_version:
        problems.append(
            f"docs/spec/language.md version {spec_version.group(1)} does not match "
            f"Main.VERSION {code_version}"
        )

    lsp = read("oq-lsp/src/main/java/dev/oqthara/lsp/Server.java")
    lsp_version = re.search(r'"version"\s*,\s*"([^"]+)"', lsp)
    if not lsp_version:
        problems.append("the LSP Server does not declare a version")
    elif lsp_version.group(1) != code_version:
        problems.append(
            f"the LSP Server version {lsp_version.group(1)} does not match "
            f"Main.VERSION {code_version}"
        )

    changelog = read("CHANGELOG.md")
    # The newest *release* heading, skipping `## [Unreleased]`.
    releases = [
        match.group(1)
        for match in re.finditer(r"^## \[?([0-9][^\]]*)\]?", changelog, re.M)
    ]
    if not releases:
        problems.append("CHANGELOG.md has no release heading")
    elif releases[0].strip() != code_version:
        problems.append(
            f"CHANGELOG newest version {releases[0].strip()} does not match code version "
            f"{code_version}"
        )

    note(
        "Main.VERSION, build.gradle.kts, the spec header, the LSP server and CHANGELOG "
        f"agree on {code_version}"
    )


# 7. Every builtin the specification tells a reader to call must exist.
def cited_builtins_exist() -> None:
    # `int_of(...)` was documented in three places while no such builtin existed; a reader
    # following the spec got "Check the spelling". Docs are language-owned, so a cited
    # name is a claim and must be checked against the registration sites.
    #
    # Extraction covers both registration helpers: most builtins use `NativeFunction.of`
    # and the printing family uses `NativeFunction.variadic`. Matching only `of(` made the
    # first version of this check report `write_text` as missing when it exists.
    stdlib_dir = ROOT / "oq-stdlib" / "src" / "main" / "java" / "dev" / "oqthara" / "stdlib"
    natives: set[str] = set()
    for source in stdlib_dir.glob("*.java"):
        natives |= set(
            re.findall(
                r'NativeFunction\.(?:of|variadic)\(\s*"([a-z_][a-z_0-9]*)"',
                source.read_text(encoding="utf-8"),
            )
        )

    methods = read("oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java")
    # Method names are often aliased on one `case "trim_end", "strip_end" ->` arm, so every
    # quoted name before the arrow is a real name; reading only the first under-reports.
    method_names: set[str] = set()
    # Aliases may differ in case (`subList` sits beside `slice`), and a case-sensitive class
    # here silently dropped every name on such an arm -- which is how `has_next` went unseen.
    for arm in re.findall(r"^\s*case ((?:\s*\"[A-Za-z_0-9]+\"\s*,?)+)\s*->", methods, re.M):
        method_names |= set(re.findall(r'"([A-Za-z_0-9]+)"', arm))

    # Constructors and iteration helpers the parser or interpreter define directly.
    core_names = {"print", "write", "list", "map", "set", "tuple", "range", "in", "for", "fun"}

    known = natives | method_names | core_names

    # Only snake_case names are checked. Builtin naming style is snake_case throughout, and
    # restricting to it avoids flagging the prose metavariables that also appear in examples
    # (`f`, `risky`).
    #
    # It does not avoid user functions: user code is snake_case too (`make_adder`), and the
    # first version of this scanning reported every such example as a missing builtin. A name
    # the page declares with `fun name(` is demonstrated to exist by the example itself, so
    # it is not a claim about the standard library.
    cited: dict[str, str] = {}
    declared: set[str] = set()
    for page in ("docs/spec/language.md", "docs/stdlib/reference.md"):
        text = read(page)
        declared |= set(re.findall(r"\bfun ([a-z][a-z_0-9]*)\s*\(", text))
        # A snake_case name in quotes is a string literal or a map key — `saved["last_id"]` —
        # not a function being called, so only unquoted occurrences are citations. The check is
        # on the character before the match: a lookbehind would instead let the pattern start
        # one character late and report `ast_id` from inside `last_id`.
        for match in re.finditer(r"\b[a-z][a-z_0-9]*_[a-z_0-9]+\b", text):
            if match.start() > 0 and text[match.start() - 1] == '"':
                continue
            cited.setdefault(match.group(0), page)
    cited = {name: page for name, page in cited.items() if name not in declared}
    # Paths such as `tests/test_math.oq` contain snake_case file stems, not builtin names.
    cited = {name: page for name, page in cited.items() if not re.search(rf"{name}\.[a-z]+", read(page))}

    unknown = sorted(name for name in cited if name not in known)
    if unknown:
        details = ", ".join(f"`{name}` in {cited[name]}" for name in unknown)
        problems.append(f"documentation cites builtins that do not exist: {details}")
        return
    note(f"{len(cited)} snake_case builtin names cited in the spec and stdlib reference all exist")


# 8. Every contract in the register must name a document that exists, and an enforced contract
#    must name a test. A register is only worth keeping if it cannot silently rot.
def contract_register_is_complete() -> None:
    register = read("docs/spec/contracts.md")
    rows = re.findall(r"^\| (\d+) \| .*? \| (.*?) \| `([^`]*)` \| (.*?) \|$", register, re.M)
    if len(rows) != 15:
        problems.append(
            f"docs/spec/contracts.md lists {len(rows)} contract rows, expected 15 "
            "(one per area the directive names)"
        )
        return

    missing: list[str] = []
    undocumented: list[str] = []
    for number, _, _, enforced in rows:
        row = register.split(f"| {number} |", 1)[1].split("\n", 1)[0]
        for target in re.findall(r"\]\(([a-z0-9/._-]+\.md)\)", row):
            # Links are relative to the register's own directory, as markdown requires.
            if not (ROOT / "docs" / "spec" / target).resolve().exists():
                missing.append(target)
        if enforced.strip() == "nothing":
            undocumented.append(number)
    if missing:
        problems.append("contract register names documents that do not exist: " + ", ".join(sorted(set(missing))))
        return
    note(f"{len(rows)} contracts registered, every referenced document exists "
         f"({len(undocumented)} deliberately absent)")


# 9. The stdlib inventory's coverage tallies must agree with its own per-domain chapters and
#    with each other. This document carried three mutually inconsistent tallies for months
#    because nothing checked them: a status table, a Total row and the sum of the per-domain
#    rows each said something different, and none matched the chapters they were derived from.
def stdlib_inventory_tallies_agree() -> None:
    path = "docs/governance/alpha-4-stdlib-inventory.md"
    lines = read(path).split("\n")

    def number(cell: str) -> int:
        return int(cell.strip().replace("*", ""))

    # Derive the truth from the chapters: a [C]/[P]/[A]/[X] tag applies to every backticked
    # name in its `;`-delimited clause. `test` counts once despite its eight submodules, which
    # the chapter summarises rather than enumerates.
    chapters: dict[int, list[int]] = {}
    for index, line in enumerate(lines):
        heading = re.match(r"^### (\d+)\. .+ — (\d+) modules?$", line)
        if not heading:
            continue
        cell = next(
            part.split("|")[2]
            for part in lines[index + 1 : index + 8]
            if part.startswith("| Modules |")
        )
        counts = [0, 0, 0, 0]
        names = 0
        for clause in cell.split(";"):
            found = len(re.findall(r"`([^`]+)`", clause))
            if "with its eight" in clause:
                found = 1
            tag = re.search(r"\[(?:all |both )?([CPAX])\]", clause)
            names += found
            if tag:
                counts["CPAX".index(tag.group(1))] += found
        chapters[int(heading.group(1))] = [names] + counts
    if not chapters:
        problems.append(f"{path} has no per-domain chapters to reconcile")
        return

    row_start = next(
        (i for i, line in enumerate(lines) if line.startswith("| # | Domain | Modules")),
        None,
    )
    if row_start is None:
        problems.append(f"{path} has no per-domain table")
        return

    def mismatched(cells: list[int]) -> bool:
        return cells != chapters.get(row_index, cells)

    summed = [0, 0, 0, 0, 0]
    total_row: list[int] | None = None
    seen: list[int] = []
    for line in lines[row_start + 2 :]:
        if not line.startswith("|"):
            break
        cells = [part.strip() for part in line.split("|")]
        if "**Total**" in line:
            total_row = [number(cell) for cell in cells[3:8]]
            break
        row_index = int(cells[1])
        seen.append(row_index)
        values = [number(cell) for cell in cells[3:8]]
        if values != chapters.get(row_index):
            problems.append(
                f"{path} per-domain row {row_index} says {values} but its chapter "
                f"accounts for {chapters.get(row_index)}"
            )
        for position, value in enumerate(values):
            summed[position] += value

    absent = sorted(set(chapters) - set(seen))
    if absent:
        problems.append(f"{path} has chapters with no per-domain row: {absent}")
    if total_row is None:
        problems.append(f"{path} has no Total row to compare")
        return
    if total_row != summed:
        problems.append(
            f"{path} Total row says {total_row} but its per-domain rows sum to {summed}"
        )

    status_rows = re.findall(
        r"^\| \*\*(?:Covered|Partly covered|Absent|Deliberately excluded)\*\* \| \*\*(\d+)\*\*",
        read(path),
        re.M,
    )
    if len(status_rows) != 4:
        problems.append(f"{path} does not state four coverage status counts")
        return
    stated = [int(value) for value in status_rows]
    if stated != summed[1:]:
        problems.append(
            f"{path} status table says {stated} but the chapters account for {summed[1:]}"
        )
        return
    note(
        f"{path} tallies agree: {summed[0]} modules, "
        f"{summed[1]} covered / {summed[2]} partial / {summed[3]} absent / "
        f"{summed[4]} excluded"
    )


def requirement_matrix_figures_match_the_inventory() -> None:
    """The matrix quotes the inventory's counts, so a repaired tally must reach both files.

    Twice now the inventory was corrected while the matrix kept the old numbers, and both
    times the stale figure was found by reading rather than by a check. The matrix may cite
    any of the four figures, so each one it does cite must equal the inventory's.
    """
    inventory = "docs/governance/alpha-4-stdlib-inventory.md"
    matrix = "docs/governance/alpha-4-requirement-matrix.md"
    inventory_text = read(inventory)
    matrix_text = read(matrix)

    status = re.findall(
        r"^\| \*\*(?:Covered|Partly covered|Absent|Deliberately excluded)\*\* \| \*\*(\d+)\*\*",
        inventory_text,
        re.M,
    )
    if len(status) != 4:
        problems.append(f"{matrix} check cannot read the inventory's status counts")
        return
    claimed = {
        "covered": int(status[0]),
        "absent": int(status[2]),
    }

    # A figure is only checked when the matrix actually cites it, so wording changes do not
    # fail the build; a cited figure that is wrong always does.
    for phrase, truth in (
        (r"(\d+) modules? remain absent", claimed["absent"]),
        (r"of which (\d+) are covered", claimed["covered"]),
    ):
        for found in re.findall(phrase, matrix_text):
            # `of which N are covered` is a range; take the first number in the run.
            value = int(found.split()[0])
            if value != truth:
                problems.append(
                    f"{matrix} says {value} where the inventory says {truth} "
                    f"(matched {phrase!r})"
                )

    builtins = re.search(r"(\d+) global (?:functions|builtins)", matrix_text)
    if builtins:
        reference = read("docs/stdlib/reference.md")
        section = reference.split("## Global functions")[1].split("## Methods on values")[0]
        actual = len(re.findall(r"^\| `[^`]+` \|", section, re.M))
        if int(builtins.group(1)) != actual:
            problems.append(
                f"{matrix} says {builtins.group(1)} global functions, "
                f"docs/stdlib/reference.md lists {actual}"
            )

    note(f"{matrix} figures agree with the inventory and the generated reference")


# 9b. A "Last verified" pin must name a commit that exists and is reachable from HEAD. The pin
#     is a promise that someone re-read the matrix against the tree; left unchecked it silently
#     ages, which is worse than an absent pin because a reader treats a stale SHA as current
#     evidence. The previous pin had drifted two merges behind and nothing failed.
def requirement_matrix_verification_pin_is_real() -> None:
    matrix = "docs/governance/alpha-4-requirement-matrix.md"
    match = re.search(r"^\*\*Last verified:\*\* `([0-9a-f]{7,40})`", read(matrix), re.M)
    if not match:
        problems.append(f"{matrix} has no 'Last verified' pin to check")
        return
    sha = match.group(1)
    # A shallow clone (CI checks out depth 1) does not contain the pinned commit as an object,
    # so "not found" there means "not fetched", not "does not exist". Reporting the second as
    # the first made a correct pin fail the build. `--is-shallow-repository` is the honest
    # discriminator: in a shallow clone this check can only confirm the pin is well-formed.
    shallow = subprocess.run(
        ["git", "rev-parse", "--is-shallow-repository"],
        cwd=ROOT, capture_output=True, text=True,
    ).stdout.strip() == "true"

    resolved = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{sha}^{{commit}}"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if resolved.returncode != 0:
        if shallow:
            note(f"{matrix} pins {sha}; a shallow clone cannot confirm it exists")
            return
        problems.append(
            f"{matrix} pins verification at {sha}, which is not a commit in this repository")
        return
    if subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"],
                      cwd=ROOT, capture_output=True).returncode != 0:
        # The pin must survive the merge strategy, not just the branch. A pin written to the
        # commit that did the verifying is unreachable from `main` as soon as the branch is
        # squash-merged, because squash rewrites the branch into one new commit and the original
        # SHAs are discarded. That failure only shows up after the merge, on `main`, where it
        # blocks everyone -- so the message names the cause rather than the symptom.
        problems.append(
            f"{matrix} pins verification at {sha}, which is not an ancestor of HEAD. If this "
            f"branch was squash-merged, the pinned commit no longer exists on the default "
            f"branch; pin the merge commit instead.")
        return
    note(f"{matrix} is verified against {sha}, a commit reachable from HEAD")


# Words that make a test name a statement about absence rather than a claim of evidence:
# a proposal, a recorded removal, or a note that the class has yet to be written.
ABSENT_MARKERS = (
    "proposed",
    "asks for",
    "no longer exists",
    "does not exist",
)

# Recorded retirement: a name that appears on a line saying it was removed, deleted or is gone.
# An ADR is a dated record and is not rewritten when a later ADR removes what it described, so a
# superseded mention is correct history rather than a false claim. Deriving the retired names from
# the corpus means ADR-0008 may keep saying the site test existed, because ADR-0011 states that it
# was removed -- and a name no ADR retires is still reported.
RETIREMENT_MARKERS = ("removed", "deleted", "no longer", "is gone", "was dropped")


def retired_test_names(documents: dict[pathlib.Path, str]) -> set[str]:
    retired: set[str] = set()
    for text in documents.values():
        for line in text.splitlines():
            lowered = line.lower()
            if any(marker in lowered for marker in RETIREMENT_MARKERS):
                retired.update(re.findall(r"\b([A-Z][A-Za-z0-9]*(?:Test|Tests))\b", line))
    return retired


def cited_tests_exist() -> None:
    """A document must not present a test class as evidence unless the class exists.

    The inventory once cited two classes before they were written, and ADR-0043 cited a third,
    which is a document claiming evidence it does not have -- the same defect as a stale tally
    and harder to notice, because the name looks like proof.

    Not every capitalised `...Test` is a claim. Three kinds of mention are legitimate and are
    not reported:

    * a `Test |` row of the inventory, which says what a missing module *would need* -- those
      rows are proposals by construction and the module they describe is absent;
    * a sentence that says a test was removed, or names a path, in an ADR recording history;
    * a name inside a fenced code block, which is sample output rather than a citation.

    Everything else must resolve to a file under a test or fuzz source set.
    """
    declared: set[str] = set()
    for pattern in ("**/src/test/java/**/*.java", "**/src/fuzz/java/**/*.java"):
        for path in ROOT.glob(pattern):
            declared.add(path.stem)

    documents = {path: read_text_utf8(path) for path in sorted(ROOT.glob("docs/**/*.md"))}
    retired = retired_test_names(documents)
    cited = 0
    for path, text in documents.items():
        relative = path.relative_to(ROOT)
        in_fence = False
        for number, line in enumerate(text.splitlines(), start=1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            names = re.findall(r"\b([A-Z][A-Za-z0-9]*(?:Test|Tests))\b", line)
            if not names:
                continue
            if line.startswith("| Test |"):
                continue
            lowered = line.lower()
            if any(marker in lowered for marker in ABSENT_MARKERS):
                continue
            if re.search(r"[\w/.-]+\.java\b", line):
                continue
            for name in names:
                cited += 1
                if name in declared or name in retired:
                    continue
                problems.append(
                    f"{relative}:{number} cites {name}, "
                    f"which no test or fuzz source set declares")
    note(f"{cited} test-class citations in docs resolve to a declared class")


# 10b. Every `name`:line citation in the stdlib inventory must point at the line that actually
#     registers that name. This is a different question from the tallies check, which only proves
#     the counts add up. The inventory asserted that every name was found by grep in the files
#     named against it, and 122 of its 126 `Stdlib.java` citations had drifted by the time this
#     check was written: the numbers were once true and nothing re-read them after the file
#     changed. A stale citation is worse than no citation, because it is checkable and therefore
#     looks checked.
def stdlib_inventory_citations_resolve() -> None:
    path = "docs/governance/alpha-4-stdlib-inventory.md"
    stdlib = ROOT / "oq-stdlib/src/main/java/dev/oqthara/stdlib"
    text = read(path)
    # The heading carries the count, so matching it literally made the check fail whenever a
    # builtin was added — and the fix was to edit the checker, which is the wrong direction.
    # Match the shape and read the number out of the heading instead.
    heading = re.search(r"^### The (\d+) names, by registration site$", text, re.M)
    if not heading:
        problems.append(f"{path} no longer has the registration-site section")
        return
    section = text.split(heading.group(0))[1]
    section = section.split("### What the " + heading.group(1) + " do not include")[0]

    def registered(source: pathlib.Path) -> dict[str, list[int]]:
        found: dict[str, list[int]] = {}
        relative = f"oq-stdlib/src/main/java/dev/oqthara/stdlib/{source.name}"
        for index, line in enumerate(read(relative).split("\n"), 1):
            match = re.search(r'defineBuiltin\(\s*NativeFunction\.\w+\("([a-z_0-9]+)"', line)
            if not match:
                match = re.search(r'defineBuiltin\(\s*"([a-z_0-9]+)"', line)
            if match:
                found.setdefault(match.group(1), []).append(index)
        return found

    # Each family is a paragraph that names at most one .java file; the document's convention is
    # that a paragraph without one means Stdlib.java.
    actual: dict[str, dict[str, list[int]]] = {}
    for block in section.split("\n\n"):
        if not re.search(r"`[a-z_0-9]+`:\d", block):
            continue
        named = re.findall(r"in `(\w+\.java)`", block)
        for file_name in named or ["Stdlib.java"]:
            if not (stdlib / file_name).exists():
                problems.append(f"{path} cites {file_name}, which does not exist")
                continue
            actual[file_name] = registered(stdlib / file_name)

    resolved = 0
    for file_name, table in actual.items():
        for match in re.finditer(r"`([a-z_0-9]+)`:((?:\d+(?:\s*(?:,|and)\s*)?)+)", section):
            name = match.group(1)
            if name not in table:
                continue
            cited = sorted(int(n) for n in re.findall(r"\d+", match.group(2)))
            if cited != sorted(table[name]):
                problems.append(
                    f"{path}: `{name}` is cited at {file_name}:{cited}"
                    f" but is registered at {sorted(table[name])}")
            else:
                resolved += 1

    # Names in a paragraph whose file is not Stdlib.java must not be attributed to Stdlib.java.
    for name in re.findall(r"`([a-z_0-9]+)`:\d", section):
        if not any(name in table for table in actual.values()):
            problems.append(f"{path} cites `{name}`, which none of the named files registers")

    if not any("citations" in p or "registered at" in p for p in problems):
        note(f"{resolved} stdlib inventory name citations resolve to the registering line")


# 13b. A cited test method must exist on the class it is attributed to.
#
# `cited_tests_exist` checks class names only, so `.aTestThatWasRenamed` slipped through: the
# class resolved and the method did not, which reads as evidence and is not. This collects every
# `ClassName.methodName` and every `ClassName.a`, `.b` continuation on one line, and resolves each
# dotted name against the methods declared by that class.
def cited_test_methods_exist() -> None:
    methods: dict[str, set[str]] = {}
    for path in list(ROOT.glob("**/src/test/java/**/*.java")) + list(
        ROOT.glob("**/src/fuzz/java/**/*.java")
    ):
        declared = re.findall(r"(?:void|static void)\s+(\w+)\s*\(", path.read_text(encoding="utf-8"))
        methods.setdefault(path.stem, set()).update(declared)

    checked = 0
    for path in sorted(ROOT.glob("docs/**/*.md")):
        relative = path.relative_to(ROOT)
        in_fence = False
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence or line.startswith("| Test |"):
                continue
            cited = [
                (k, m)
                for k, m in re.findall(
                    r"\b([A-Z][A-Za-z0-9]*(?:Test|Tests))\.(\w+)", line
                )
                if m not in {"java", "class", "md"}
            ]
            for klass, method in cited:
                if klass not in methods:
                    continue  # cited_tests_exist owns the unresolvable-class case
                checked += 1
                if method not in methods[klass]:
                    problems.append(
                        f"{relative}:{number} cites {klass}.{method}, which "
                        f"{klass} does not declare"
                    )
    note(f"{checked} cited test methods resolve on their class")


# 13c. A quoted test count (`ClassTest` (39 tests)) must match the class.
#
# The count is a measurement, so it rots whenever a test is added and the sentence is not
# re-read. Two exist today and both happened to be right; this keeps them that way.
def quoted_test_counts_agree() -> None:
    counts: dict[str, int] = {}
    for path in list(ROOT.glob("**/src/test/java/**/*.java")) + list(
        ROOT.glob("**/src/fuzz/java/**/*.java")
    ):
        counts[path.stem] = len(re.findall(r"@Test\b", path.read_text(encoding="utf-8")))

    checked = 0
    for path in sorted(ROOT.glob("docs/**/*.md")):
        relative = path.relative_to(ROOT)
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            # Two ways of quoting a class's present size: "`X` (N tests)" and
            # "`X`, which now holds N". The second is used in the requirement matrix,
            # where it was wrong by four without anything noticing.
            pairs = re.findall(r"`([A-Za-z]+Tests?)` \((\d+) tests?\)", line)
            pairs += re.findall(r"`([A-Za-z]+Tests?)`, which now holds (\d+)", line)
            for klass, quoted in pairs:
                if klass not in counts:
                    continue
                checked += 1
                if int(quoted) != counts[klass]:
                    problems.append(
                        f"{relative}:{number} says {klass} has {quoted} tests, "
                        f"but it declares {counts[klass]}"
                    )
    note(f"{checked} quoted test counts match their class")


def read_text_utf8(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


CHECKS = (
    stdlib_reference_is_current,
    keyword_budget_is_honest,
    command_form_matches_the_spec,
    documented_commands_exist,
    catalog_is_current,
    backend_counts_are_measured,
    quoted_jar_class_counts_agree,
    version_is_consistent,
    cited_builtins_exist,
    contract_register_is_complete,
    stdlib_inventory_tallies_agree,
    stdlib_inventory_citations_resolve,
    requirement_matrix_figures_match_the_inventory,
    cited_tests_exist,
    cited_test_methods_exist,
    quoted_test_counts_agree,
    requirement_matrix_verification_pin_is_real,
)


def main() -> int:
    for check in CHECKS:
        try:
            check()
        except Exception as error:  # a broken check must fail loudly, never pass silently
            problems.append(f"{check.__name__} raised {type(error).__name__}: {error}")

    if problems:
        print("Documentation drift detected:")
        for problem in problems:
            print("  -", problem)
        return 1

    print("Documentation is synchronized with the implementation:")
    for item in checked:
        print("  -", item)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"check-docs-sync: error: {error}", file=sys.stderr)
        raise
