#!/usr/bin/env python3
"""Probes the non-finite policy of ADR-0051 and the ordering rule of ADR-0063.

Two decisions meet in this area and they are easy to confuse:

* ADR-0051 decides *which* non-finite values may exist. A domain error raises; overflow and
  underflow are silent; a pole of a fractional power is an infinity rather than an error.
* ADR-0063 decides *how* those values compare. ``NaN`` is unordered, so every relational
  operator answers false for it, while ``sort``, ``min``, ``max``, ``clamp`` and the heap keep
  using a total order in which ``NaN`` sorts above every number.

The second decision only exists because the first makes ``NaN`` reachable, so the two are
probed together: a run that raised on overflow could not reach the comparison cases at all.
The counts quoted in ADR-0063 are produced here rather than transcribed.

Usage:
    python3 scripts/probe-non-finite.py [--verbose] [--check]

``--check`` exits 1 if any value, message, or comparison result differs from what the ADRs say.
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"

# Overflow and underflow are silent, and a pole of a fractional power is an infinity rather
# than a domain error, because a pole has a real answer on both sides. Each row is the whole
# program so the value can be checked without depending on how a list prints.
SILENT_VALUES: list[tuple[str, str, str]] = [
    ("overflow", "print 1.0e308 * 10\n", "infinity"),
    ("overflow-negative", "print -1.0e308 * 10\n", "-infinity"),
    ("underflow", "print 1.0e-308 / 1.0e20\n", "0.0"),
    ("subnormal", "print 5.0e-324 / 2.0\n", "0.0"),
    ("pole-power", "print pow(0.0, -1)\n", "infinity"),
    ("pole-operator", "print 0.0 ** -1\n", "infinity"),
    ("zero-to-the-zero", "print 0.0 ** 0\n", "1.0"),
    ("infinity-minus-infinity", "big = 1.0e308 * 10\nprint big - big\n", "NaN"),
]

# Every one of these must raise, because the input is outside the domain rather than merely
# large or small. The message fragment is checked too: a silent NaN would be the failure, and
# so would an infinity, which would claim a real answer exists.
RAISING: list[tuple[str, str, str]] = [
    ("divide-integer", "print 1 / 0\n", "division by zero"),
    ("divide-decimal-by-integer", "print 1.0 / 0\n", "division by zero"),
    ("divide-integer-by-decimal", "print 1 / 0.0\n", "division by zero"),
    ("divide-zero-by-zero", "print 0.0 / 0.0\n", "division by zero"),
    ("modulo-integer", "print 1 % 0\n", "division by zero"),
    ("modulo-decimal", "print 1.0 % 0.0\n", "division by zero"),
    ("sqrt-of-negative", "print sqrt(-1)\n", "no real result"),
    ("log-of-zero", "print ln(0)\n", "needs a positive number"),
    ("asin-out-of-range", "print asin(2)\n", "no real result"),
    ("acos-out-of-range", "print acos(2)\n", "no real result"),
    ("fractional-root-of-negative", "print pow(-8, 0.5)\n", "no real result"),
]

# NaN is unordered: all twelve relational outcomes must be the IEEE ones. The old defect made
# six of them wrong, and it made them wrong in a way that contradicted `==`, which is why every
# row names the operator and the expected literal rather than just "true" or "false".
NAN_COMPARISONS: list[tuple[str, str, bool]] = [
    ("nan-equals-nan", "nan == nan", False),
    ("nan-not-equals-nan", "nan != nan", True),
    ("nan-less-than-one", "nan < 1.0", False),
    ("nan-less-or-equal-one", "nan <= 1.0", False),
    ("nan-greater-than-one", "nan > 1.0", False),
    ("nan-greater-or-equal-one", "nan >= 1.0", False),
    ("nan-less-than-nan", "nan < nan", False),
    ("nan-less-or-equal-nan", "nan <= nan", False),
    ("nan-greater-than-nan", "nan > nan", False),
    ("nan-greater-or-equal-nan", "nan >= nan", False),
    ("one-less-than-nan", "1.0 < nan", False),
    ("one-greater-than-nan", "1.0 > nan", False),
    ("nan-less-or-equal-two", "nan <= 2.0", False),
    ("nan-greater-or-equal-two", "nan >= 2.0", False),
]

# The total order is a different relation and must survive the change. These are the consumers
# that need every pair to order, so a regression shows up here rather than in the operators.
TOTAL_ORDER: list[tuple[str, str, str]] = [
    ("sort", "print sort([nan, inf, 1.0, -inf])\n", "[-infinity, 1.0, infinity, NaN]"),
    ("sorted", "print sorted([nan, 5, 2])\n", "[2, 5, NaN]"),
    ("min", "print min(nan, 1.0)\n", "1.0"),
    ("max", "print max(nan, 1.0)\n", "NaN"),
    ("clamp", "print clamp(nan, 0.0, 10.0)\n", "10.0"),
]

# The same root cause produced a second contradiction that had nothing to do with NaN.
# `Double.compare` distinguishes `-0.0` from `0.0`, while `==` on doubles does not, so the
# operators reported `-0.0 < 0.0` as true and `-0.0 == 0.0` as true at the same time. ADR-0017
# already decided that `-0.0 == 0`, so the ordering was the part that was wrong.
SIGNED_ZERO: list[tuple[str, str, bool]] = [
    ("negative-zero-less", "-0.0 < 0.0", False),
    ("zero-less-negative", "0.0 < -0.0", False),
    ("negative-zero-greater", "-0.0 > 0.0", False),
    ("zero-greater-negative", "0.0 > -0.0", False),
    ("negative-zero-less-or-equal", "-0.0 <= 0.0", True),
    ("zero-less-or-equal-negative", "0.0 <= -0.0", True),
    ("negative-zero-greater-or-equal", "-0.0 >= 0.0", True),
    ("zero-greater-or-equal-negative", "0.0 >= -0.0", True),
    ("negative-zero-equals", "-0.0 == 0.0", True),
]

# `NaN` is unusable as a key or a member, which follows from `n != n` and is a trap worth
# pinning: the entry stores, then the lookup misses. This is unchanged by the ordering fix,
# and it is recorded so a future change to equality cannot silently move it.
NAN_AS_A_KEY: list[tuple[str, str, str]] = [
    ("map-stores-the-entry", "m = {}\nm[nan] = 1\nprint length(m)\n", "1"),
    ("map-cannot-read-it-back", "m = {}\nm[nan] = 1\nprint m[nan]\n", "nothing"),
    ("set-holds-two-copies", "print length(set([nan, nan]))\n", "2"),
    ("set-membership-misses", "print nan in set([nan])\n", "false"),
    ("list-membership-misses", "print nan in [nan]\n", "false"),
    ("signed-zero-is-one-key", "print length(set([-0.0, 0.0]))\n", "1"),
]

# Ordinary comparisons must be untouched, including the two shapes the parser has to disambiguate.
ORDINARY_COMPARISONS: list[tuple[str, str, str]] = [
    ("integers", "print 1 < 2\n", "true"),
    ("mixed-numbers", "print 1.5 < 2\n", "true"),
    ("equal-numbers", "print 2 <= 2\n", "true"),
    ("text", 'print "a" < "b"\n', "true"),
    ("booleans", "print false < true\n", "true"),
    ("lists", "print [1, 2] < [1, 3]\n", "true"),
    ("list-length", "print [1] < [1, 2]\n", "true"),
    ("tuples", "print (1, 2) < (1, 3)\n", "true"),
    ("infinity", "print inf > 1.0\n", "true"),
    ("infinity-self", "print inf < inf\n", "false"),
]

PREAMBLE = "big = 1.0e308 * 10\nnan = big - big\ninf = 1.0e308 * 10\n"


def run(source: str) -> tuple[int, list[str]]:
    """Runs the program and returns its exit code and its non-blank output lines."""
    with tempfile.TemporaryDirectory() as directory:
        program = pathlib.Path(directory) / "probe.oq"
        program.write_text(source, encoding="utf-8")
        completed = subprocess.run(
            ["java", "-jar", str(JAR), "run", str(program)],
            capture_output=True,
            text=True,
        )
    lines = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    if completed.returncode != 0 and not lines:
        # A run that produced nothing at all is a harness failure, not a language result. Surface
        # it here so a broken jar reports as a broken jar instead of as one mysterious empty row.
        detail = completed.stderr.strip().splitlines()
        lines = ["<no output; stderr: " + (detail[0] if detail else "empty") + ">"]
    return completed.returncode, lines


def error_line(source: str) -> tuple[int, str]:
    """Runs the program and returns its exit code and the first `error:` line."""
    with tempfile.TemporaryDirectory() as directory:
        program = pathlib.Path(directory) / "probe.oq"
        program.write_text(source, encoding="utf-8")
        completed = subprocess.run(
            ["java", "-jar", str(JAR), "run", str(program)],
            capture_output=True,
            text=True,
        )
    message = next(
        (line.strip() for line in completed.stderr.splitlines() if line.strip().startswith("error:")),
        "",
    )
    return completed.returncode, message


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="print every case")
    parser.add_argument("--check", action="store_true", help="exit 1 if any case disagrees")
    arguments = parser.parse_args()

    if not JAR.exists():
        print(f"missing {JAR}; run: ./gradlew :oq-cli:standaloneJar", file=sys.stderr)
        return 2

    failures: list[str] = []

    silent_values = 0
    for label, source, expected in SILENT_VALUES:
        code, lines = run(source)
        actual = lines[-1] if lines else ""
        if code == 0 and actual == expected:
            silent_values += 1
        else:
            failures.append(f"{label}: expected {expected!r} with exit 0, got {actual!r} with exit {code}")
        if arguments.verbose:
            print(f"value   {label:26} {actual or '<none>':14} exit {code}")

    raising = 0
    for label, source, fragment in RAISING:
        code, message = error_line(source)
        if code != 0 and fragment in message:
            raising += 1
        else:
            failures.append(f"{label}: expected a raise mentioning {fragment!r}, got {message or '<none>'} with exit {code}")
        if arguments.verbose:
            print(f"raise   {label:26} {message or '<none>':40} exit {code}")

    unordered = 0
    for label, expression, expected in NAN_COMPARISONS:
        code, lines = run(PREAMBLE + f"print {expression}\n")
        actual = lines[-1] if lines else ""
        if code == 0 and actual == str(expected).lower():
            unordered += 1
        else:
            failures.append(f"{label}: expected {str(expected).lower()}, got {actual!r} with exit {code}")
        if arguments.verbose:
            print(f"nan     {label:26} {expression:20} {actual or '<none>'}")

    total = 0
    for label, source, expected in TOTAL_ORDER:
        code, lines = run(PREAMBLE + source)
        actual = lines[-1] if lines else ""
        if code == 0 and actual == expected:
            total += 1
        else:
            failures.append(f"{label}: expected {expected!r}, got {actual!r} with exit {code}")
        if arguments.verbose:
            print(f"total   {label:26} {actual or '<none>'}")

    signed = 0
    for label, expression, expected in SIGNED_ZERO:
        code, lines = run(f"print {expression}\n")
        actual = lines[-1] if lines else ""
        if code == 0 and actual == str(expected).lower():
            signed += 1
        else:
            failures.append(f"{label}: expected {str(expected).lower()}, got {actual!r} with exit {code}")
        if arguments.verbose:
            print(f"zero    {label:26} {expression:20} {actual or '<none>'}")

    keys = 0
    for label, source, expected in NAN_AS_A_KEY:
        code, lines = run(PREAMBLE + source)
        actual = lines[-1] if lines else ""
        if code == 0 and actual == expected:
            keys += 1
        else:
            failures.append(f"{label}: expected {expected!r}, got {actual!r} with exit {code}")
        if arguments.verbose:
            print(f"key     {label:26} {actual or '<none>'}")

    ordinary = 0
    for label, source, expected in ORDINARY_COMPARISONS:
        code, lines = run(PREAMBLE + source)
        actual = lines[-1] if lines else ""
        if code == 0 and actual == expected:
            ordinary += 1
        else:
            failures.append(f"{label}: expected {expected!r}, got {actual!r} with exit {code}")
        if arguments.verbose:
            print(f"order   {label:26} {actual or '<none>'}")

    print(f"non-finite values produced silently: {silent_values} of {len(SILENT_VALUES)}")
    print(f"domain errors that raise: {raising} of {len(RAISING)}")
    print(f"NaN comparisons following IEEE order: {unordered} of {len(NAN_COMPARISONS)}")
    print(f"signed-zero comparisons following IEEE order: {signed} of {len(SIGNED_ZERO)}")
    print(f"total-order consumers still ordering NaN: {total} of {len(TOTAL_ORDER)}")
    print(f"NaN unusable as a key or member: {keys} of {len(NAN_AS_A_KEY)}")
    print(f"ordinary comparisons unchanged: {ordinary} of {len(ORDINARY_COMPARISONS)}")

    if failures:
        print()
        print("FAIL:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print()
    print("ok: NaN is unordered in the operators and ordered in the collections")
    return 0


if __name__ == "__main__":
    sys.exit(main())
