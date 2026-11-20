#!/usr/bin/env python3
"""Fails when a path parse appears outside a place that guards it.

`OqPaths` exists because the same nine lines were written four times and only one copy
caught `InvalidPathException`. A path holding a NUL is reachable from a program through
`url_decode("%00")`, and an unguarded copy does not fail at the call site: the exception
escapes the interpreter, which prints "This is a bug in Oqthara" and exits 70 for a
program that merely passed bad text.

Consolidating the copies fixed the defect. It did not stop a fifth. R-032's *Evidence*
bullet ends with an admission that nothing in the build fails when a new copy appears -- the
mitigation column makes the same point in the register's own words; a convention nobody
enforces is exactly how the first four copies happened.

Scope is every module a program can reach with its own text — `oq-stdlib`, `oq-core` and
`oq-runtime`. An earlier version of this gate covered `oq-stdlib` alone and argued the
other two out of scope. That argument was wrong twice: `JavaBridge` coerced an Oqthara
string to a `Path` without a guard, and `Modules.resolveModule` did the same for a quoted
`use` name. Both let an `InvalidPathException` escape the interpreter. Those guards now
exist, and this gate covers them, which is why there is more than one allowed site.

Each site is named with the reason it may parse, so adding one is a visible edit here and a
reviewer sees the justification. A stale entry — one that no longer parses, or a file that
vanished — is a failure too, because an exemption nobody rechecks is how a gate rots.

Exit codes: 0 every parse is guarded, 1 an unguarded parse was found, 2 the tree could not
be read or the gate swept less than it should have.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

# Modules a compiled program can reach with its own text.
SCOPES = [
    pathlib.Path("oq-stdlib/src/main/java"),
    pathlib.Path("oq-core/src/main/java"),
    pathlib.Path("oq-runtime/src/main/java"),
]

# Every file permitted to parse a path, with the reason it is allowed to.
ALLOWED = {
    "oq-stdlib/src/main/java/dev/oqthara/stdlib/OqPaths.java":
        "parses program text; turns InvalidPathException into an OqError",
    "oq-core/src/main/java/dev/oqthara/core/JavaBridge.java":
        "bridgePath parses program text; turns InvalidPathException into an OqError",
    "oq-core/src/main/java/dev/oqthara/core/Modules.java":
        "resolveModule parses a use name; turns InvalidPathException into an OqError",
    "oq-stdlib/src/main/java/dev/oqthara/stdlib/Builtins.java":
        "parses the literal '.' for a default interpreter",
    "oq-core/src/main/java/dev/oqthara/core/Interpreter.java":
        "parses the literal '.' for a default working directory",
    "oq-runtime/src/main/java/dev/oqthara/runtime/Methods.java":
        "parses the literal '.' for a default working directory",
    "oq-runtime/src/main/java/dev/oqthara/runtime/Callables.java":
        "parses the literal '.' for a default working directory",
}

# Spellings that reach a path parse. `getPath` is included because
# `FileSystems.getDefault().getPath(text)` parses exactly as `Path.of` does. `\s*` between
# the parts covers `Path . of (x)`; the optional package covers `java.nio.file.Path.of(x)`.
PARSE = (r"\b(?:java\.nio\.file\.)?(?:Path|Paths)\s*\.\s*(?:of|get)\s*\("
         r"|\bFileSystems\s*\.\s*getDefault\s*\(\s*\)\s*\.\s*getPath\s*\(")
PARSE_RE = re.compile(PARSE)

# A parse whose argument is a double-quoted literal with no escape is a constant. Escapes are
# excluded on purpose: `Path.of("a\u0000b")` is a literal too, and it is the text this gate
# exists to catch.
CONSTANT_RE = re.compile(r"^(?:" + PARSE + r")\s*\"[^\"\\\n\r]*\"\s*\)")

OPENERS = (("/*", "  ", "block"), ("//", "  ", "line_comment"))


def code_only(text: str) -> list[tuple[str, str]]:
    """Per line: code with comments and literals blanked, and code with literals kept.

    A line-by-line split is what let the previous version miss `/* c */ code() Path.of(t);`
    and `"http://x"; Path.of(t)`, so this walks characters and keeps a small state machine.
    Blanked regions become spaces, which keeps column offsets and stops two tokens either
    side of a comment from fusing into a different token.

    Two views are returned because the two questions need different ones. Finding a parse
    must not see inside a literal, or `"Path.of(x)"` reads as a call. Deciding whether a
    parse's argument is a literal must see them, or every literal looks like text and every
    constant looks like a violation. The second view blanks comments only.
    """
    code_lines: list[str] = []
    literal_lines: list[str] = []
    code: list[str] = []
    literal: list[str] = []
    i, n = 0, len(text)
    state = "code"

    def flush() -> None:
        code_lines.append("".join(code))
        literal_lines.append("".join(literal))
        code.clear()
        literal.clear()

    while i < n:
        ch = text[i]
        if ch == "\n":
            flush()
            i += 1
            if state in ("line_comment", "string", "char"):
                state = "code"
            continue
        if state == "code":
            if text.startswith('"""', i):
                code.append("   ")
                literal.append('"""')
                i += 3
                state = "text_block"
                continue
            if text.startswith("/*", i):
                code.append("  ")
                literal.append("  ")
                i += 2
                state = "block"
                continue
            if text.startswith("//", i):
                code.append("  ")
                literal.append("  ")
                i += 2
                state = "line_comment"
                continue
            if ch in "\"'":
                code.append(" ")
                literal.append(ch)
                i += 1
                state = "string" if ch == '"' else "char"
                continue
            code.append(ch)
            literal.append(ch)
            i += 1
            continue
        if state == "line_comment":
            code.append(" ")
            literal.append(" ")
            i += 1
            continue
        if state == "block":
            if text.startswith("*/", i):
                code.append("  ")
                literal.append("  ")
                i += 2
                state = "code"
                continue
            code.append(" ")
            literal.append(" ")
            i += 1
            continue
        if state == "text_block":
            if text.startswith('"""', i):
                code.append("   ")
                literal.append('"""')
                i += 3
                state = "code"
                continue
            code.append(" ")
            literal.append(ch)
            i += 1
            continue
        # string or char
        if ch == "\\":
            code.append("  ")
            literal.append(text[i:i + 2])
            i += 2
            continue
        if ch == ('"' if state == "string" else "'"):
            code.append(" ")
            literal.append(ch)
            i += 1
            state = "code"
            continue
        code.append(" ")
        literal.append(ch)
        i += 1
    flush()
    return list(zip(code_lines, literal_lines))


def all_parses(text: str) -> list[int]:
    """Line numbers of every parse call, constants included."""
    found = []
    for index, (code, _) in enumerate(code_only(text)):
        if PARSE_RE.search(code):
            found.append(index + 1)
    return found


# A parse can be split across lines: `Path` and `.of(t)` on separate lines is legal Java and
# reads past a line-at-a-time scan. The joined view is scanned as well, and only the line the
# match starts on is reported.
STATIC_IMPORT_RE = re.compile(
    r"^\s*import\s+static\s+(?:java\.nio\.file\.)?(?:Path|Paths|FileSystems)\s*\.\s*"
    r"(?:of|get|getPath)\s*;")


def unguarded(text: str) -> list[tuple[int, str]]:
    """Line numbers and source text of parse calls on anything but a plain literal."""
    found: list[tuple[int, str]] = []
    original = text.splitlines()
    pairs = code_only(text)
    seen: set[int] = set()
    for index, (code, literal) in enumerate(pairs):
        source = original[index].strip() if index < len(original) else code.strip()
        for match in PARSE_RE.finditer(code):
            if CONSTANT_RE.match(literal[match.start():]):
                continue
            found.append((index + 1, source))
            seen.add(index + 1)
            break
        if STATIC_IMPORT_RE.match(code):
            if index + 1 not in seen:
                found.append((index + 1, source))
                seen.add(index + 1)
    # The joined view: two lines at a time, so `Path` newline `.of(x)` is one string.
    for index in range(len(pairs) - 1):
        joined = pairs[index][0] + pairs[index + 1][0]
        boundary = len(pairs[index][0])
        for match in PARSE_RE.finditer(joined):
            if index + 1 in seen:
                break
            if not (match.start() < boundary < match.end()):
                continue
            if CONSTANT_RE.match(joined[match.start():]):
                continue
            source = original[index].strip() if index < len(original) else ""
            found.append((index + 1, source))
            seen.add(index + 1)
            break
    return sorted(found)


def check(root: pathlib.Path) -> tuple[list[str], int, int]:
    """Problems, number of files read, number of allowed parse sites actually found."""
    problems: list[str] = []
    scanned = 0
    allowed_seen = 0
    covered: set[str] = set()
    for scope in SCOPES:
        directory = root / scope
        if not directory.is_dir():
            problems.append(f"{scope} is missing, so the gate would pass on a tree it never read")
            continue
        for source in sorted(directory.rglob("*.java")):
            scanned += 1
            relative = str(source.relative_to(root))
            text = source.read_text(encoding="utf-8")
            hits = unguarded(text)
            if relative in ALLOWED:
                covered.add(relative)
                parses = len(all_parses(text))
                if not parses:
                    problems.append(
                        f"{relative} is listed as guarded but parses nothing, so the "
                        f"exemption now covers nothing and should be removed"
                    )
                allowed_seen += parses
                continue
            for number, line in hits:
                problems.append(
                    f"{relative}:{number} parses a path outside a guarded site: {line[:100]}"
                )
    for name in ALLOWED:
        if name not in covered:
            problems.append(f"{name} is listed as guarded but was not found, so the entry is stale")
    return problems, scanned, allowed_seen


def main() -> int:
    problems, scanned, allowed_seen = check(ROOT)
    if problems:
        print(f"unguarded path parsing: {len(problems)} problem(s)")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    # A sweep that reads nothing reports success, which is how a check quietly stops
    # checking. Both figures are floors: the three modules, and the sites named above.
    if scanned < 55:
        print(f"path parsing: read only {scanned} sources, expected the whole of the three modules",
              file=sys.stderr)
        return 2
    if allowed_seen < len(ALLOWED):
        print(f"path parsing: found {allowed_seen} guard site(s) with a parse, expected "
              f"{len(ALLOWED)}", file=sys.stderr)
        return 2
    print(
        f"path parsing: {allowed_seen} parse(s) across {len(ALLOWED)} guarded sites, "
        f"none elsewhere in {scanned} sources"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
