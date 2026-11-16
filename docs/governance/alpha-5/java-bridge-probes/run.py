#!/usr/bin/env python3
"""Runs the Alpha 5 Java Bridge probes and checks every documented behaviour.

Each probe is a real `.oq` program. The expectations below are the outputs the design
requires, not whatever the build happens to print: a probe that starts failing means the
bridge regressed, and a probe that starts passing means a defect from the inventory was
fixed and its expectation must be promoted from `known_defect`.

A known defect is recorded with the exact output it produced when the note was written, and
either direction of change is an error. Fixing it fails the run, so the marker cannot outlive
the bug; changing the wrong output also fails the run, so a defect cannot hide a regression
inside the part of the behaviour that is already wrong. Only one verdict is silent — the
recorded defect reproducing exactly — which is the only case where nothing has been learned.

Usage:
    python3 docs/governance/alpha-5/java-bridge-probes/run.py <path-to-oq.jar>
    python3 docs/governance/alpha-5/java-bridge-probes/run.py <path-to-oq.jar> --both

`--both` additionally compiles every probe with `oq build` and runs the resulting jar, then
requires the compiled output to equal the interpreted output line for line. That is what A5.7
claims ("interpreter and compiled backends agree on every probe"), and it is a separate claim
from "the interpreter produces the documented output": a probe can pass the first check and
still disagree across backends. Probes whose documented behaviour is a diagnostic are compared
too, because a backend that accepts what the interpreter refuses is exactly the disagreement
worth catching.

Exit code is 0 when every probe matches its expectation and every recorded defect reproduces
verbatim, 1 otherwise.
"""

import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent

# A rendered diagnostic quotes the offending source line and points a caret at the column. A
# compiled jar embeds no source at all, so `oq build` documents that its excerpt is absent
# (`docs/spec/backend.md`, "Runtime error reporting"). Comparing the raw text therefore failed
# every probe whose documented behaviour is a diagnostic the moment the probe started
# compiling. The excerpt is removed from both sides so the comparison is about the message,
# the position and the hint, which both backends must agree on.
EXCERPT = re.compile(r"^\s+(?:\d+ \|.*|\^\s*)$", re.MULTILINE)
# Removing two lines from one side and none from the other also leaves a different number of
# blank lines behind, so runs of them collapse before the comparison.
BLANK_RUN = re.compile(r"\n\s*\n(?:\s*\n)+")


POSITION = "  --> "


def comparable(text):
    # Ordinary output is compared verbatim, blank lines included: `String.join(",", [])` prints
    # an empty line on purpose, so normalising whitespace everywhere would hide a real
    # difference. Only a rendered diagnostic gets the documented exemption.
    if POSITION not in text:
        return text
    return BLANK_RUN.sub("\n\n", EXCERPT.sub("", text))


# (name, expected stdout lines, known-defect record)
# A known-defect record is (description, the exact lines the build printed when the note was
# written). It is not a way to accept a mismatch: see the module docstring.
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
    # The eight shadowed names and the two the audit miscounted: `size` and `subList` are
    # shadowed, `clear` is not, and `set` is the language's to_set. Each line of the spec's
    # precedence paragraph is checked here rather than trusted.
    (
        "d5_unit_names",
        ["3", "[10, 20]", "20", "set() expects 0 arguments, got 1", "nothing", "[]"],
        None,
    ),
]


def matches(lines, expected) -> bool:
    """Whether the probe's output satisfies its expectation."""
    if isinstance(expected, str):
        return expected in "\n".join(lines)
    return lines == expected


def run(jar: Path, program: Path):
    result = subprocess.run(
        [sys.executable and "java", "-jar", str(jar), "run", str(program)],
        capture_output=True,
        text=True,
    )
    return result.stdout + result.stderr, result.returncode


def compile_program(jar: Path, program: Path):
    """Compiles a probe in a scratch copy.

    `oq build` has no output flag: it writes `<name>.jar` beside the source. Copying the probe
    to a temporary directory keeps the generated jars out of the working tree, which matters
    because this runs in CI where a dirty tree fails the docs check.
    """
    import shutil

    scratch = Path(tempfile.mkdtemp())
    copied = scratch / program.name
    shutil.copy2(program, copied)
    result = subprocess.run(
        [sys.executable and "java", "-jar", str(jar), "build", str(copied)],
        capture_output=True,
        text=True,
    )
    return scratch / f"{program.stem}.jar", result.stdout + result.stderr, result.returncode


def run_compiled(target: Path):
    result = subprocess.run(
        [sys.executable and "java", "-jar", str(target)],
        capture_output=True,
        text=True,
        cwd=str(target.parent),
    )
    return result.stdout + result.stderr, result.returncode


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    both = "--both" in sys.argv[1:]
    if len(args) != 1:
        print(__doc__.strip())
        return 2
    jar = Path(args[0]).resolve()
    if not jar.is_file():
        print(f"no jar at {jar}")
        return 2

    failures = []
    defects = []
    compared = 0
    for name, expected, defect in PROBES:
        program = HERE / f"{name}.oq"
        if not program.is_file():
            failures.append(f"{name}: missing probe file")
            continue
        output, code = run(jar, program)
        if both:
            # Compare the two backends on the same program. A refusal is compared as text, so
            # a backend that runs a program the interpreter rejects is caught.
            target, build_output, build_code = compile_program(jar, program)
            if build_code != 0 or not target.is_file():
                tail = build_output.strip().splitlines()[-1] if build_output.strip() else "no output"
                failures.append(
                    f"{name}: oq build did not produce a jar (exit {build_code}): {tail}"
                )
                continue
            compiled_output, compiled_code = run_compiled(target)
            compared += 1
            if comparable(compiled_output) != comparable(output):
                failures.append(
                    f"{name}: backends disagree; interpreter {output.splitlines()!r} "
                    f"(exit {code}), compiled {compiled_output.splitlines()!r} (exit {compiled_code})"
                )
        # Trailing blank lines are stripped; inner ones are kept, because a probe such as
        # `String.join(",", [])` prints an empty line on purpose.
        lines = output.splitlines()
        while lines and not lines[-1].strip():
            lines.pop()
        lines = [line.strip() for line in lines]

        if defect is not None:
            # Both directions are errors. A fix is the expected direction, and it still fails
            # so the note is promoted rather than left describing a bug that is gone; any
            # other change means the wrong output moved, which the record did not predict.
            marker, recorded = defect
            if lines == recorded:
                defects.append((name, marker))
            elif isinstance(expected, str) and expected in output and code != 0:
                failures.append(
                    f"{name}: recorded defect appears fixed ({marker}); "
                    f"remove the marker and expect {expected!r}"
                )
            else:
                failures.append(
                    f"{name}: recorded defect {marker!r} no longer reproduces exactly; "
                    f"recorded {recorded!r}, got {lines!r} (exit {code}). "
                    f"Re-check the defect and update the record deliberately."
                )
            continue

        if isinstance(expected, str):
            # A probe whose correct behaviour is a refusal: the diagnostic has to be
            # present, and the program must not have gone on to succeed first.
            if expected in output and code != 0:
                pass
            else:
                failures.append(
                    f"{name}: expected output containing {expected!r}, got {lines!r} (exit {code})"
                )
        elif expected is not None:
            if lines == expected:
                pass
            else:
                failures.append(f"{name}: expected {expected!r}, got {lines!r} (exit {code})")
        else:
            failures.append(f"{name}: no expectation and no defect record")

    if failures:
        print("FAILED")
        for failure in failures:
            print(f"  {failure}")
        return 1

    matched = sum(1 for _, e, d in PROBES if e is not None and d is None)
    print(f"ok: {matched} probes match their expectation")
    if both:
        print(f"ok: {compared} probes produce identical output on both backends")
    if defects:
        print(f"known defects reproducing verbatim: {len(defects)}")
        for name, marker in defects:
            print(f"  {name}: {marker}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
