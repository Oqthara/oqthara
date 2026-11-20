#!/usr/bin/env python3
"""Fails when a path parse appears outside the one place that guards it.

`OqPaths` exists because the same nine lines were written four times and only one copy
caught `InvalidPathException`. A path holding a NUL is reachable from a program through
`url_decode("%00")`, and an unguarded copy does not fail at the call site: the exception
escapes the interpreter, which prints "This is a bug in Oqthara" and exits 70 for a
program that merely passed bad text.

Consolidating the four copies fixed the defect. It did not stop a fifth. R-032 says so in
its own mitigation column — "nothing in the build fails when a new copy appears" — and a
convention nobody enforces is exactly how the first four copies happened. This is the gate
that column was missing.

The rule is narrow on purpose. In `oq-stdlib`'s main sources, `Path.of` and `Paths.get` may
appear only in `OqPaths.java`. Every parse there takes text a program supplied.

Two exemptions are recorded rather than granted silently, because a rule with invisible
exceptions is a rule somebody argues with and nobody can check:

* `Builtins.java` builds a default interpreter over `Path.of(".")`. A constant, not text.
* `OqPaths` itself parses `Path.of("")` when no working directory is set. Also a constant.

Other modules are out of scope and deliberately so:

* `oq-core`, `oq-cli`, `oq-compiler`, `oq-lsp` parse command-line arguments and URIs, which
  a program cannot supply. `JavaBridge` coerces an Oqthara string to `Path`, but the bridge
  converts the resulting exception into an `OqError` — verified, not assumed.
* `oq-runtime` only ever parses `"."` as an empty working directory.

Exit codes: 0 every parse is guarded, 1 an unguarded parse was found, 2 the tree could not
be read.
"""

from __future__ import annotations

import pathlib
import re
import sys

GUARDED = pathlib.Path("oq-stdlib/src/main/java/dev/oqthara/stdlib")
GUARD = GUARDED / "OqPaths.java"
SOURCES = pathlib.Path("oq-stdlib/src/main/java")

PARSE = re.compile(r"\b(?:Path\.of|Paths\.get)\s*\(")

CONSTANT = re.compile(r"""\b(?:Path\.of|Paths\.get)\s*\(\s*"[^"]*"\s*\)""")


def unguarded(text: str) -> list[tuple[int, str]]:
    """Line numbers and text of parse calls on anything but a string literal."""
    found = []
    in_block = False
    for number, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if in_block:
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*"):
            in_block = "*/" not in stripped
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        code = line.split("//")[0]
        for match in PARSE.finditer(code):
            if CONSTANT.match(code, match.start()):
                continue
            found.append((number, stripped))
            break
    return found


def check(root: pathlib.Path) -> list[str]:
    guard = root / GUARD
    sources = root / SOURCES
    if not guard.exists():
        return [f"{GUARD} is missing, so nothing guards a path parse any more"]

    problems = []
    for source in sorted(sources.rglob("*.java")):
        if source.resolve() == guard.resolve():
            continue
        for number, line in unguarded(source.read_text(encoding="utf-8")):
            relative = source.relative_to(root)
            problems.append(
                f"{relative}:{number} parses a path outside OqPaths: {line[:100]}\n"
                f"    Route it through OqPaths.resolve(...) or OqPaths.lexical(...), "
                f"which refuse bad text as an OqError instead of letting "
                f"InvalidPathException escape."
            )
    return problems


def main() -> int:
    root = pathlib.Path(".")
    if not SOURCES.is_dir():
        print(f"cannot read {SOURCES}", file=sys.stderr)
        return 2

    problems = check(root)
    if problems:
        print(f"unguarded path parse: {len(problems)} site(s)")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    checked = len(list(SOURCES.rglob("*.java")))
    guarded = len(unguarded(GUARD.read_text(encoding="utf-8")))
    print(
        f"path parsing: {guarded} parse(s) in OqPaths, none in the other "
        f"{checked - 1} stdlib sources"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
