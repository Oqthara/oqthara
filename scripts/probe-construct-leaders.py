#!/usr/bin/env python3
"""Probes the construct leaders against both halves of the ADR-0062 claim.

The diagnostic change has two obligations, and they pull in opposite directions:

* Before an operand, a leader word must name the missing construct instead of blaming the
  token that follows it.
* Everywhere a name can occur, the same word must still be accepted, because ``match``,
  ``from`` and ``await`` are ordinary names in shipped examples and hard-reserving them
  would be a regression dressed as a fix.

A single test can satisfy either half by breaking the other, so this probes both and fails if
either regresses. The counts quoted in ADR-0062 are produced here rather than transcribed.

Usage:
    python3 scripts/probe-construct-leaders.py [--verbose] [--check]

``--check`` exits 1 if a leader fails to name itself before an operand, if a diagnostic
carries no hint, or if any leader stops being usable as a name.
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"

EXPECTED = "is not a construct in Oqthara"

# Each leader with an operand of the shape the real construct would take, and the fragment its
# hint must contain. The operand matters: the refusal must be triggered by the word being *used
# as syntax*, not by the word alone. The hint fragment matters just as much, because the parser
# already emits a generic hint at this position -- "Statements end at the end of the line." -- so
# a bare "has a hint" check passes before the change and proves nothing.
OPERANDS: list[tuple[str, str, str]] = [
    ("match", "match x\n", "no pattern matching"),
    ("case", "case 1:\n", "no pattern matching"),
    ("switch", "switch x\n", "no pattern matching"),
    ("yield", "yield 1\n", "no generators"),
    ("def", "def f(x)\nend\n", "'fun' declares a function"),
    ("async", "async fun g()\nend\n", "no asynchronous functions"),
    ("await", "await f()\n", "no asynchronous functions"),
    ("from", "from x import y\n", "'use' is the only import"),
    ("import", "import foo\n", "'use' is the only import"),
    ("throw", 'throw "boom"\n', "Raise an error"),
]

# The same leaders in the two value positions a statement can put them in. These are separate
# rows because the first version of the rule ran only on the bare-expression path, so `x = yield 1`
# still blamed the `1` while `yield 1` named the construct. A probe that only tested the bare form
# would have reported success.
VALUE_POSITIONS: list[tuple[str, str]] = [
    ("assign", "x = {source}\n"),
    ("compound-assign", "x += {source}\n"),
]

# One entry per grammar position a name can occupy. All of these must keep parsing; a refusal
# here means the debuggability fix silently became the reservation it was written to avoid.
NAME_POSITIONS: list[tuple[str, str]] = [
    ("assign-target", "NAME = 1\n"),
    ("command-argument", "print NAME\n"),
    ("for-variable", "for NAME in [1]\nend\n"),
    ("parameter", "fun f(NAME)\nend\n"),
    ("function-name", "fun NAME()\nend\n"),
    ("bare-read", "NAME\n"),
]

COMPREHENSIONS: list[tuple[str, str, str]] = [
    ("list", "[n * 2 for n in 1 to 3]\n", "list comprehensions are not implemented"),
    ("map", "{n: n for n in 1 to 3}\n", "map comprehensions are not implemented"),
    ("set", "{n for n in 1 to 3}\n", "set comprehensions are not implemented"),
    ("generator", "(n for n in 1 to 3)\n", "generator comprehensions are not implemented"),
]


def run_check(source: str) -> tuple[int, list[str]]:
    """Parses the program and returns its exit code and the non-blank stderr lines."""
    with tempfile.TemporaryDirectory() as directory:
        program = pathlib.Path(directory) / "probe.oq"
        program.write_text(source, encoding="utf-8")
        completed = subprocess.run(
            ["java", "-jar", str(JAR), "check", str(program)],
            capture_output=True,
            text=True,
        )
    return completed.returncode, [line.strip() for line in completed.stderr.splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="print every position")
    parser.add_argument(
        "--check", action="store_true", help="exit 1 if either half of the claim fails"
    )
    arguments = parser.parse_args()

    if not JAR.exists():
        print(f"missing {JAR}; run: ./gradlew :oq-cli:standaloneJar", file=sys.stderr)
        return 2

    failures: list[str] = []

    named = 0
    hinted = 0
    for leader, source, hint_fragment in OPERANDS:
        code, lines = run_check(source)
        message = next((line for line in lines if line.startswith("error:")), "")
        hint = next((line for line in lines if line.startswith("help:")), "")
        names_itself = code != 0 and message == f"error: {leader} {EXPECTED}"
        if names_itself:
            named += 1
        else:
            failures.append(f"{leader}: expected 'error: {leader} {EXPECTED}', got {message or '<accepted>'}")
        hint_text = hint[len("help:"):].strip() if hint else ""
        if hint_fragment in hint_text:
            hinted += 1
        else:
            failures.append(
                f"{leader}: the hint does not contain {hint_fragment!r} (got {hint_text!r})"
            )
        if arguments.verbose:
            print(f"operand {leader:8} {message or 'ACCEPTED'}")
            if hint:
                print(f"{'':16} {hint}")

    named_values = 0
    for leader, _, _ in OPERANDS:
        for label, template in VALUE_POSITIONS:
            source = template.replace("{source}", leader + " y")
            code, lines = run_check(source)
            message = next((line for line in lines if line.startswith("error:")), "")
            if code != 0 and message == f"error: {leader} {EXPECTED}":
                named_values += 1
            else:
                failures.append(
                    f"{leader} in {label}: expected 'error: {leader} {EXPECTED}', "
                    f"got {message or '<accepted>'}"
                )
            if arguments.verbose:
                print(f"value   {leader:8} {label:24} {message or 'ACCEPTED'}")

    accepted = 0
    for leader, _, _ in OPERANDS:
        for label, template in NAME_POSITIONS:
            source = template.replace("NAME", leader)
            code, lines = run_check(source)
            if code == 0:
                accepted += 1
            else:
                message = next((line for line in lines if line.startswith("error:")), "")
                failures.append(f"{leader} as {label}: refused ({message})")
            if arguments.verbose:
                outcome = "ok" if code == 0 else "REFUSED"
                print(f"name    {leader:8} {label:24} {outcome}")

    shapes_named = 0
    for shape, source, expected in COMPREHENSIONS:
        code, lines = run_check(source)
        message = next((line for line in lines if line.startswith("error:")), "")
        if code != 0 and message == f"error: {expected}":
            shapes_named += 1
        else:
            failures.append(f"{shape} comprehension: expected 'error: {expected}', got {message or '<accepted>'}")
        if arguments.verbose:
            print(f"comprehension {shape:10} {message or 'ACCEPTED'}")

    positions = len(OPERANDS) * len(NAME_POSITIONS)
    print(f"leaders probed: {len(OPERANDS)}")
    print(f"operand diagnostics naming the construct: {named} of {len(OPERANDS)}")
    print(f"diagnostics carrying a construct-specific hint: {hinted} of {len(OPERANDS)}")
    value_slots = len(OPERANDS) * len(VALUE_POSITIONS)
    print(f"assignment values naming the construct: {named_values} of {value_slots}")
    print(f"name positions per leader: {len(NAME_POSITIONS)}")
    print(f"name positions still accepted: {accepted} of {positions}")
    print(f"comprehension shapes naming themselves: {shapes_named} of {len(COMPREHENSIONS)}")

    if failures:
        print()
        print("FAIL:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print()
    print("ok: every construct leader names itself before an operand and stays usable as a name")
    return 0


if __name__ == "__main__":
    sys.exit(main())
