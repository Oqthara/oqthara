#!/usr/bin/env python3
"""Fails when documentation and implementation disagree.

The website, the language reference and the standard-library reference are all derived from
the implementation, so a feature can never be documented as present while missing, or removed
while still advertised. Each check names the exact file it read, because a passing test that
does not say what it verified is worth little.
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
problems = []
checked = []


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def note(what):
    checked.append(what)


# 1. The stdlib reference must match a fresh generation from the implementation.
def stdlib_reference_is_current():
    reference = ROOT / "docs" / "stdlib" / "reference.md"
    if not reference.exists():
        problems.append("docs/stdlib/reference.md is missing")
        return
    jar = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"
    if not jar.exists():
        note("stdlib reference freshness skipped (no standalone jar built)")
        return
    before = reference.read_text(encoding="utf-8")
    result = subprocess.run(
        ["java", "-jar", str(jar), "doc", "."], cwd=ROOT, capture_output=True, text=True
    )
    if result.returncode != 0:
        problems.append(f"`oq doc .` failed: {result.stderr.strip()[:200]}")
        return
    after = reference.read_text(encoding="utf-8")
    if before != after:
        problems.append("docs/stdlib/reference.md is stale; run `oq doc .`")
    note("stdlib reference matches the implementation")


# 2. The keyword count in the reference must match the lexer.
def keyword_budget_is_honest():
    reference = read("docs/stdlib/reference.md")
    match = re.search(r"## Keywords\n\n([^\n]+)\n\n(\d+) keywords \(budget: (\d+)\)", reference)
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
    note(f"{count} keywords within a budget of {budget}")


# 3. Every CLI command in the help text must be documented on the docs page.
def documented_commands_exist():
    main = read("oq-cli/src/main/java/dev/oqthara/cli/Main.java")
    help_block = re.search(r'return """\n(.*?)""";', main, re.S)
    if not help_block:
        problems.append("could not read the help text from Main.java")
        return
    commands = set(re.findall(r"oq (run|check|repl|fmt|test|new|doc|build|version|help)\b",
                              help_block.group(1)))
    page = read("site/src/pages/DocsPage.jsx")
    for command in sorted(commands):
        if f"oq {command}" not in page:
            problems.append(f"docs page does not document `oq {command}`")
    note(f"{len(commands)} CLI commands appear in both the help and the docs page")


# 4. Features the site claims must be real, and features that do not exist must not be claimed.
def website_claims_match_reality():
    status = read("site/src/pages/StatusPage.jsx")
    # The status page must be honest about the backend's boundaries.
    if "compiled" not in status.lower():
        problems.append("status page says nothing about the compiled backend")
    # Anything listed as a gap must actually be a gap. The compiler either refuses every
    # function or it emits real function methods; the status page must agree with whichever
    # is true, in both directions.
    compiler = read("oq-compiler/src/main/java/dev/oqthara/compiler/BytecodeCompiler.java")
    if "functions are not compiled yet" in compiler:
        if "Bytecode for functions" not in status:
            problems.append("the compiler refuses functions but the status page hides that gap")
    else:
        if "functionDescriptor" not in compiler:
            problems.append("the compiler does not refuse functions but has no function descriptor")
        if "Calls, closures and user functions fall back" in status:
            problems.append("the status page still says user functions fall back, but they compile")
        if "real bytecode for functions" not in status:
            problems.append("the status page does not say functions compile to real bytecode")
    note("status page claims match the implemented backend")


# 5. Every example shown on the website must exist in the repository's examples directory.
# Titles are prose ("Binary search tree"), so each program declares the file it came from.
def website_examples_are_real():
    page = read("site/src/pages/ExamplesPage.jsx")
    files = re.findall(r'file: "([^"]+\.oq)"', page)
    if not files:
        problems.append("the examples page does not name any source files")
        return
    for name in files:
        if not (ROOT / "examples" / name).exists():
            problems.append(f"example shown on the site has no file: examples/{name}")
    programs = page.split("const PROGRAMS = [", 1)[-1].split("\n];", 1)[0]
    titles = re.findall(r'title: "([^"]+)"', programs)
    if len(titles) != len(files):
        problems.append(
            f"examples page has {len(titles)} titles but {len(files)} source files")
    note(f"{len(files)} website examples map to files in examples/")


# 6. Documentation must not describe a version other than the code's.
def version_is_consistent():
    main = read("oq-cli/src/main/java/dev/oqthara/cli/Main.java")
    version = re.search(r'VERSION = "([^"]+)"', main)
    if not version:
        problems.append("Main.VERSION is missing")
        return
    changelog = read("CHANGELOG.md")
    heading = re.search(r"## \[?([0-9][^\]]*)\]?", changelog)
    if heading and heading.group(1).strip() != version.group(1):
        problems.append(
            f"CHANGELOG newest version {heading.group(1)} does not match code version {version.group(1)}"
        )
    note(f"code and changelog agree on version {version.group(1)}")


for check in (stdlib_reference_is_current, keyword_budget_is_honest,
              documented_commands_exist, website_claims_match_reality,
              website_examples_are_real, version_is_consistent):
    try:
        check()
    except Exception as error:  # a broken check must fail loudly, not silently pass
        problems.append(f"{check.__name__} raised {type(error).__name__}: {error}")

if problems:
    print("Documentation drift detected:")
    for problem in problems:
        print("  -", problem)
    sys.exit(1)

print("Documentation is synchronized with the implementation:")
for item in checked:
    print("  -", item)
