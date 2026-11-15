#!/usr/bin/env python3
"""Runs the Alpha 5 Java Bridge probes and checks every documented behaviour.

Each probe is a real `.oq` program. The expectations below are the outputs the design
requires, not whatever the build happens to print: a probe that starts failing means the
bridge regressed, and a probe that starts passing means a defect from the inventory was
fixed and its expectation must be promoted from `known_defect`.

Usage:
    python3 docs/governance/alpha-5/java-bridge-probes/run.py <path-to-oq.jar>

Exit code is 0 when every non-defect probe matches, 1 otherwise.
"""

import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# (name, expected stdout lines, known-defect marker)
# A known defect records what the build prints today so a fix is visible as a mismatch
# rather than silently changing a passing line.
#
# `expected` is either a list of stdout lines, or a string meaning the output must contain
# that text — used by a probe whose correct behaviour is a diagnostic rather than a value.
PROBES = [
    ("c1_construct", ["[]", "2026-01-31"], None),
    ("c2_instance", ["2", "false", "3", "true", "-1"], None),
    (
        "c3_static",
        ["2147483647", "3.141592653589793", "-9223372036854775808", "7", "42"],
        None,
    ),
    ("c4_conversion", ["true", "A", "{MONDAY, FRIDAY}", "0.3", "tmp"], None),
    ("c5_varargs", ["a-b", "a", "", "2"], None),
    ("c6_exceptions", ['caught: NumberFormatException: For input string: "zz"', "after"], None),
    ("c7_callbacks", ["3", "1", "2", "[1, 2, 3]"], None),
    (
        "c8_reverse",
        ["-1", "-1", "42", "[1, 3]", "1", "3", "[1, 2, 3]", "[2]"],
        None,
    ),
    # D1, fixed: a callable Java object is accepted wherever a function is accepted.
    # Each probe first calls the Java value directly, so a failure separates "the value
    # is not callable" from "the builtin would not take it".
    ("d1_sort", ["3", "[1, 2, 3]"], None),
    ("d1_sort_by", ["[1, 2, 3]"], None),
    ("d1_list_sort", ["[1, 2, 3]"], None),
    ("d1_reduce", ["2", "3"], None),
    # The arity rule is what keeps a two-argument comparator from being used as a
    # one-argument key. The refusal must name the counts, not deny callability.
    ("d1_key_arity", "error: 'sort' needs a function taking 1 argument as argument 1", None),
    # D2, fixed: a class object answers questions about itself. Statics are tried first, so
    # no program that worked changed meaning; only calls that used to fail reach this path.
    ("d2_class_instance", ["java.lang.String", "String", "false", "false"], None),
    # The same fix must not open reflection: getDeclaredMethod is still refused, and the
    # refusal now says it is reflection rather than claiming the method is not static.
    (
        "d2_reflection_refused",
        "help: 'getDeclaredMethod' is reflection, which is not exposed",
        None,
    ),
    # D3: Oqthara's method wins a name it shares with Java's List. The behaviour is the
    # documented precedence; the diagnostic is the one the spec quotes verbatim.
    ("d3_shadowing", "error: remove takes an index, not text", None),
    # The names the two agree on keep working, so the precedence paragraph is not claiming
    # that Java is unreachable for list receivers.
    ("d3_precedence_agree", ["true", "1", "2"], None),
]


def run(jar: Path, program: Path):
    result = subprocess.run(
        [sys.executable and "java", "-jar", str(jar), "run", str(program)],
        capture_output=True,
        text=True,
    )
    return result.stdout + result.stderr, result.returncode


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip())
        return 2
    jar = Path(sys.argv[1]).resolve()
    if not jar.is_file():
        print(f"no jar at {jar}")
        return 2

    failures = []
    defects = []
    for name, expected, defect in PROBES:
        program = HERE / f"{name}.oq"
        if not program.is_file():
            failures.append(f"{name}: missing probe file")
            continue
        output, code = run(jar, program)
        # Trailing blank lines are stripped; inner ones are kept, because a probe such as
        # `String.join(",", [])` prints an empty line on purpose.
        lines = output.splitlines()
        while lines and not lines[-1].strip():
            lines.pop()
        lines = [line.strip() for line in lines]

        if isinstance(expected, str):
            # A probe whose correct behaviour is a refusal: the diagnostic has to be
            # present, and the program must not have gone on to succeed first.
            if expected in output and code != 0:
                pass
            elif defect is not None:
                defects.append((name, defect, lines))
            else:
                failures.append(
                    f"{name}: expected output containing {expected!r}, got {lines!r} (exit {code})"
                )
        elif expected is not None:
            if lines == expected:
                pass
            elif defect is not None:
                # A probe with an expectation and a defect marker is a behaviour that
                # should hold but does not yet: the mismatch is the defect, not a
                # regression.
                defects.append((name, defect, lines))
            else:
                failures.append(
                    f"{name}: expected {expected!r}, got {lines!r} (exit {code})"
                )
        elif defect is not None:
            defects.append((name, defect, lines))

    if failures:
        print("FAILED")
        for failure in failures:
            print(f"  {failure}")
        return 1

    matched = sum(1 for name, e, _ in PROBES if e is not None and not any(name == d[0] for d in defects))
    print(f"ok: {matched} probes match their expectation")
    if defects:
        print(f"known defects still reproducing: {len(defects)}")
        for name, defect, lines in defects:
            # The diagnostic is what makes the defect actionable, so show the first error
            # line rather than the probe's earlier successful output.
            detail = next((line for line in lines if "error:" in line), lines[0] if lines else "(no output)")
            print(f"  {name}: {defect}")
            print(f"    {detail}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
