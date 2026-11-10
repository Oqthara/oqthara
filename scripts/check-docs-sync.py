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
        for name in re.findall(r"\b([a-z][a-z_0-9]*_[a-z_0-9]+)\b", text):
            cited.setdefault(name, page)
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


CHECKS = (
    stdlib_reference_is_current,
    keyword_budget_is_honest,
    documented_commands_exist,
    catalog_is_current,
    backend_counts_are_measured,
    version_is_consistent,
    cited_builtins_exist,
    contract_register_is_complete,
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
