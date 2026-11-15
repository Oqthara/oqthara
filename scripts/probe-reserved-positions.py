#!/usr/bin/env python3
"""Probes one reserved word against every grammar position a name can occupy.

The claim ADR-0061 makes is a negative one: no program can contain a ``finally`` line and still
run. That is easy to assert and, without a harness, impossible for a reader to check. This probes
each position with the bare word and records what the compiler says, so the counts quoted in the
ADR are regenerated rather than transcribed.

A position that exits 0 is a hole: the word was accepted somewhere it must not be, and the
reservation failed there. Every other outcome is a refusal, and the report groups refusals by
message so drift in the wording is visible too.

Usage:
    python3 scripts/probe-reserved-positions.py [--word finally] [--verbose] [--check]

``--check`` exits 1 if any position accepts the word, which makes this usable as a gate.
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"

# One entry per grammar position a name can occupy. The word is substituted for NAME, so a
# position that does not appear to test the word is still testing it -- unless the language
# cannot reach the word there, which is what the comprehension rows below record.
POSITIONS: list[tuple[str, str]] = [
    # Reads and writes.
    ("statement-read", "NAME\n"),
    ("assign-target", "NAME = 1\n"),
    ("compound-target", "NAME += 1\n"),
    ("multi-assign-target", "a, NAME = [1, 2]\n"),
    ("const-name", "const NAME = 1\n"),
    # Declarations.
    ("function-name", "fun NAME()\nend\n"),
    ("first-parameter", "fun f(NAME)\nend\n"),
    ("second-parameter", "fun f(a, NAME)\nend\n"),
    ("data-name", "data NAME\nend\n"),
    ("data-field", "data P(NAME)\nend\n"),
    ("class-name", "class NAME\nend\n"),
    ("class-body-field", "class C()\n  NAME\nend\n"),
    ("contract-name", "contract NAME\nend\n"),
    ("contract-method-name", "contract NAME()\nend\n"),
    # Control flow.
    ("for-variable", "for NAME in 1 to 3\nend\n"),
    ("for-range-start", "for n in NAME to 3\nend\n"),
    ("for-range-end", "for n in 1 to NAME\nend\n"),
    ("if-condition", "if NAME\nend\n"),
    ("while-condition", "while NAME\nend\n"),
    ("return-value", "fun f()\n  return NAME\nend\n"),
    ("try-body", "try\n  NAME\ncatch e\nend\n"),
    ("catch-name", "try\n  1\ncatch NAME\nend\n"),
    # Expressions.
    ("call-target", "NAME()\n"),
    ("call-argument", "print NAME\n"),
    ("command-argument", "print NAME\n"),
    ("binary-right", "1 + NAME\n"),
    ("binary-left", "NAME + 1\n"),
    ("unary-operand", "x = not NAME\n"),
    ("index", "x[NAME]\n"),
    ("field-name", "x.NAME\n"),
    ("list-element", "[NAME]\n"),
    ("set-element", "{NAME}\n"),
    ("map-key", "{NAME: 1}\n"),
    ("map-value", "{1: NAME}\n"),
    ("nested-list-element", "[[NAME]]\n"),
    ("interpolation", 'x = "a {NAME} b"\n'),
    ("use-target", "use NAME\n"),
    # Comprehension positions. Comprehensions are unimplemented, so the list rule refuses these
    # before the word is examined; the rows are kept to record that, not to claim a reservation.
    ("comprehension-element", "[NAME for n in 1 to 3]\n"),
    ("comprehension-variable", "[n for NAME in 1 to 3]\n"),
    ("comprehension-range-start", "[n for n in NAME to 3]\n"),
    ("comprehension-range-end", "[n for n in 1 to NAME]\n"),
    ("comprehension-filter", "[n for n in 1 to 3 if NAME if NAME]\n"),
]


def run_probe(source: str) -> tuple[int, str]:
    """Compiles the program and returns its exit code and first error line."""
    with tempfile.TemporaryDirectory() as directory:
        program = pathlib.Path(directory) / "probe.oq"
        program.write_text(source, encoding="utf-8")
        completed = subprocess.run(
            ["java", "-jar", str(JAR), "check", str(program)],
            capture_output=True,
            text=True,
        )
    lines = [line.strip() for line in completed.stderr.splitlines() if line.strip()]
    message = next((line for line in lines if line.startswith("error:")), "")
    return completed.returncode, message


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--word", default="finally", help="reserved word to probe")
    parser.add_argument("--verbose", action="store_true", help="print every position")
    parser.add_argument(
        "--check", action="store_true", help="exit 1 if any position accepts the word"
    )
    arguments = parser.parse_args()

    if not JAR.exists():
        print(f"missing {JAR}; run: ./gradlew :oq-cli:standaloneJar", file=sys.stderr)
        return 2

    accepted: list[str] = []
    messages: collections.Counter[str] = collections.Counter()
    reserved_hits = 0

    for label, template in POSITIONS:
        source = template.replace("NAME", arguments.word)
        code, message = run_probe(source)
        if code == 0:
            accepted.append(label)
            messages["<accepted>"] += 1
        else:
            messages[message] += 1
            if message == f"error: {arguments.word} does not exist":
                reserved_hits += 1
        if arguments.verbose:
            outcome = "ACCEPTED" if code == 0 else message
            print(f"{label:32} {outcome}")

    total = len(POSITIONS)
    print(f"word: {arguments.word}")
    print(f"positions probed: {total}")
    print(f"accepted: {len(accepted)}")
    print(f"rejected: {total - len(accepted)}")
    print(f"refused by the reservation itself: {reserved_hits}")
    print(f"distinct messages: {len(messages)}")
    for message, count in messages.most_common():
        print(f"  {count:3}x {message}")

    if accepted:
        print()
        print("FAIL: the word was accepted as a name in these positions:")
        for label in accepted:
            print(f"  - {label}")
        return 1

    print()
    print(f"ok: no position accepts '{arguments.word}' as a name")
    return 0


if __name__ == "__main__":
    sys.exit(main())
