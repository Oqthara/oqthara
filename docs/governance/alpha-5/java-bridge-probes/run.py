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
requires two things. The compiled output must equal the interpreted output line for line, which
is what A5.7 claims ("interpreter and compiled backends agree on every probe"), and a separate
claim from "the interpreter produces the documented output": a probe can pass the first check
and still disagree across backends. The jar must also report `codepath: compiled bytecode`,
which is what A6.3 claims. Without that second check `--both` is satisfied by an interpreter
launcher, so every probe could regress to the fallback and the run would stay green; the
compiled output would match because it was produced by the interpreter.

`COMPILED` is what every probe must report, and `FALLBACK` records any exception with the
reason its own build printed. It is empty today: `d1_sort_by` used to call `sort_by`, which
the backend had never lowered, so `oq build` wrote an interpreter launcher for it. Once the
callable-taking builtins were lowered the entry became false, and the runner failed with
"recorded fallback is gone, so the gap it recorded has closed" rather than quietly changing
its verdict. The entry was removed because the gap closed, not because the check was relaxed.

Keeping the map as "compiled unless declared" is what makes that work: a second probe falling
back fails the run, so it cannot become a general licence, and a gap that closes fails the run
too. An entry can therefore not outlive the gap it records.

A declared fallback is counted apart from the probes that actually compiled, and its jar is not
counted as a two-backend comparison: that jar *is* the interpreter, so its output matching is the
interpreter agreeing with itself. The summary prints the compiled count, and a note whenever any
probe ran via the launcher, so the "both backends agree" claim cannot be read onto a probe that
only ever had one backend in play.

Exit code is 0 when every probe matches its expectation and every recorded defect reproduces
verbatim, 1 otherwise.
"""

import os
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


# A6.3 is the claim that compiled code answers `Class` self-description *without* falling back
# to the interpreter. `oq build` already prints which path it took, so the runner reads that
# line rather than inferring the path from the fact that a jar exists — an interpreter
# launcher is still a jar, and its output matches byte for byte because the interpreter
# produced it. Checking only the output therefore cannot see this claim at all.
COMPILED = "codepath: compiled bytecode"

# Probes whose build reports something other than COMPILED, mapped to the exact line the build
# printed. Not a wildcard: a probe absent from here must report COMPILED.
FALLBACK = {}

# Probes that need a second jar on the JVM classpath, mapped to the source file that jar is
# built from. `java -jar` ignores `CLASSPATH` and the working directory, so a probe resolving a
# class from anywhere other than oq.jar cannot be reached that way at all. Rather than change the
# launch form for every probe, only the probes named here are launched as `java -cp`; the rest
# keep the `-jar` form the other probes' recorded output was produced under.
#
# The jar is built from committed source on every run rather than kept in the tree. A committed
# jar is invisible to review and needs regenerating by hand whenever it changes. Building it needs
# a JDK, which this harness already requires: it runs `oq build` and expects `codepath: compiled
# bytecode`, and that needs a JDK too. A missing `javac` is therefore a failure, never a skip — a
# probe that quietly skipped itself would report a pass for a check that never ran.
EXTRA_CLASSPATH = {"c11_user_classpath": "Hello.java"}
CLI_MAIN = "dev.oqthara.cli.Main"
# What `oq build` writes into the manifest of every jar it produces.
COMPILED_MAIN = "oq.generated.Program"

# The `codepath:` line is the only field of the build output this runner reads, and it is
# printed on stdout, so a probe's own program output can never be mistaken for it.
CODEPATH = re.compile(r"^codepath: .*$", re.MULTILINE)


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
    # The refusal is a narrowing of what a name reaches, not a sandbox (ADR-0065). This is the
    # same entry point the probe above is refused: getEnclosingClass is in CLASS_REFLECTION, so
    # `c.getEnclosingClass()` fails, while MethodHandles resolves and invokes it. Reaching a
    # member the allowlist does not refuse would prove nothing, so the probe names a refused one.
    ("d2_reflection_reachable", ["nothing"], None),
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
        "d3_shadowed_names",
        ["3", "[10, 20]", "20", "set() expects 0 arguments, got 1", "nothing", "[]"],
        None,
    ),
    # C9-C12, the four directions the harness did not cover. A6.4 records that gap and names
    # these rows; adding them is what shrinks it. Each one was run under `oq run` and again as
    # a built jar before its expectation was written down here.
    #
    # `d3_shadowing` already pins `remove("x")`, but its receiver is an Oq list literal, so it
    # proves the builtin answers for an Oq list and says nothing about a Java receiver. C9.1 is
    # the stronger claim -- that a real `java.util.ArrayList` is still claimed by the builtin --
    # so this probe uses an ArrayList for both calls and adds the two-argument comparator, which
    # `d3_shadowing` does not cover. The final `[3, 1, 2]` is what separates "the builtin
    # answered" from "the builtin refused and Java ran anyway": a descending sort would reorder
    # it, and `remove(Object)` would shorten it.
    (
        "c9_precedence",
        [
            "'sort' needs a function taking 1 argument as argument 1",
            "remove takes an index, not text",
            "[3, 1, 2]",
        ],
        None,
    ),
    # C10.1-C10.4 in one program, because the claim is that the two backends agree and `--both`
    # compares this probe against itself. The nested `use` carries the weight: ADR-0066 made it
    # a run-time registration, so a backend that hoisted it would print the date even when the
    # branch did not run, and this probe would still pass. `if true` is therefore load-bearing
    # only in that it keeps the statement below the top level, not as a condition.
    ("c10_use_both_backends", ["7", "42", "b", "2026-01-01"], None),
    # C11.1. This is the one probe `run.py` launches as `java -cp` instead of `java -jar`,
    # because `-jar` ignores `CLASSPATH` and the working directory. `Hello` is only in the
    # fixture jar, so the probe cannot pass by finding it somewhere else. It was run under both
    # launch forms before its expectation was written down: `-jar` says "cannot find Java class
    # 'Hello'", `-cp` prints the line below.
    ("c11_user_classpath", ["hi world"], None),
    # C11.2. A JDK class is importable by fully qualified name, under both backends.
    ("c11_qualified_name", ["class dev.oqthara.core.Lexer"], None),
    # C12.1. The second line is the diagnostic this build actually prints: `getClass` returns a
    # `Class` value, so the refusal names the receiver and says "no static method". The
    # inventory paraphrases it as "`Class.getDeclaredMethod` is unreachable", which is the
    # outcome and not the wording — pinning the wording is what catches a reword.
    (
        "c12_public_only",
        [
            "cannot read field 'elementData' on list",
            "Java class ArrayList has no static method 'getDeclaredMethod'",
        ],
        None,
    ),
    # C12.2 is a probe and not a defect: the row is "Unsafe by design — now documented", so the
    # behaviour below is the documented one and a change to it is a change to the boundary.
    ("c12_unrestricted", ["0", "true"], None),
    # C13.7. The direction 13 rows were executed by hand and opened no window, which left layout,
    # painting, input dispatch and look-and-feel untested. Only *realising* a window needs a
    # display; the rest of the toolkit runs headless, so all four are reachable here and none of
    # them needs the machine to have a screen. That is why this is a probe and not a hand run:
    # it is deterministic, and the runner compares it across both backends.
    #
    # `painted` counts non-white pixels rather than reading a pixel, so the expectation below is
    # whitespace-independent and the line is a number that a broken paint cannot accidentally
    # produce. `getLookAndFeel().getName()` is deliberately not printed: it is "Mac OS X" on this
    # machine and "Metal" on a bare Linux JVM, so naming it would make the probe depend on where
    # it runs instead of on the bridge.
    (
        "c13_offscreen",
        ["true", "20000", '["go"]', "true", "true"],
        None,
    ),
]


def matches(lines, expected) -> bool:
    """Whether the probe's output satisfies its expectation."""
    if isinstance(expected, str):
        return expected in "\n".join(lines)
    return lines == expected


def fixture_jar(source: Path, scratch: Path) -> Path:
    """Compile `source` into a jar of its own and return its path.

    The class must end up somewhere that is *not* `oq.jar`, or the probe would be measuring the
    jar's own contents rather than the classpath it was launched with.
    """
    staged = scratch / source.stem
    staged.mkdir(parents=True, exist_ok=True)
    compiled = subprocess.run(
        ["javac", "-d", str(staged), str(source)],
        capture_output=True,
        text=True,
    )
    if compiled.returncode != 0:
        detail = (compiled.stdout + compiled.stderr).strip() or "no output"
        raise RuntimeError(f"javac failed for {source.name}: {detail}")
    target = scratch / f"{source.stem.lower()}_fixture.jar"
    packed = subprocess.run(
        ["jar", "--create", "--file", str(target), "-C", str(staged), "."],
        capture_output=True,
        text=True,
    )
    if packed.returncode != 0 or not target.is_file():
        detail = (packed.stdout + packed.stderr).strip() or "no output"
        raise RuntimeError(f"jar failed for {source.name}: {detail}")
    return target


def launch(jar: Path, *args, extra: Path | None = None):
    """The command line for `jar`, with `extra` on the classpath when one is needed.

    `java -jar` ignores both `CLASSPATH` and the working directory, so a probe that imports a
    class from another jar has to be launched with `-cp` and the main class spelled out. Only
    the probes listed in `EXTRA_CLASSPATH` take that form: every other probe's recorded output
    was produced under `-jar`, and changing the launch form for all of them would move
    evidence that has nothing to do with this.
    """
    if extra is None:
        return [sys.executable and "java", "-jar", str(jar), *args]
    return [
        sys.executable and "java",
        "-cp",
        f"{jar}{os.pathsep}{extra}",
        CLI_MAIN,
        *args,
    ]


def run(jar: Path, program: Path, extra: Path | None = None):
    result = subprocess.run(
        launch(jar, "run", str(program), extra=extra),
        capture_output=True,
        text=True,
    )
    return result.stdout + result.stderr, result.returncode


def compile_program(jar: Path, program: Path, extra: Path | None = None):
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
        launch(jar, "build", str(copied), extra=extra),
        capture_output=True,
        text=True,
    )
    return scratch / f"{program.stem}.jar", result.stdout + result.stderr, result.returncode


def compiled_launch(target: Path, extra: Path | None = None):
    """The command line that runs a built jar, mirroring `launch` for the compiler's output.

    A compiled jar's own `Main-Class` is what `-jar` would use, so the `-cp` form names that
    class explicitly instead of reading the manifest back.
    """
    if extra is None:
        return [sys.executable and "java", "-jar", str(target)]
    return [
        sys.executable and "java",
        "-cp",
        f"{target}{os.pathsep}{extra}",
        COMPILED_MAIN,
    ]


def run_compiled(target: Path, extra: Path | None = None):
    # `cwd` is set because the jar is written in a scratch directory and the program may resolve
    # a relative path.
    result = subprocess.run(
        compiled_launch(target, extra=extra),
        capture_output=True,
        text=True,
        cwd=str(target.parent),
    )
    return result.stdout + result.stderr, result.returncode


def codepath_failure(name, build_output):
    """Return why this probe's build is not the path A6.3 requires, or None if it is.

    Split out of `main` so the decision can be tested without a jar: the three outcomes are
    "compiled", "a declared fallback", and "anything else is a failure", and the last one has
    to include silence. A build that stops printing the line would otherwise read as a pass
    because the substring check never ran.
    """
    found = CODEPATH.search(build_output)
    if found is None:
        return f"{name}: oq build printed no codepath line, so the backend is unknown"
    actual = found.group(0).strip()
    expected = FALLBACK.get(name, COMPILED)
    if actual == expected:
        return None
    if name not in FALLBACK:
        return f"{name}: expected {expected!r}, got {actual!r}"
    if actual == COMPILED:
        # The gap this entry records has closed. Fail so the entry is deleted rather than
        # left claiming a fallback that no longer happens.
        return (
            f"{name}: recorded fallback is gone, so the gap it recorded has closed; expected "
            f"{expected!r}, got {actual!r}. Remove the FALLBACK entry."
        )
    # Still falling back, but the build says so differently. Updating the entry to whatever
    # the build happens to print would make it accept any reason, so this is a deliberate
    # edit, not an automatic one.
    return (
        f"{name}: the recorded fallback reason changed; recorded {expected!r}, "
        f"got {actual!r}. Re-check the gap and update the FALLBACK entry deliberately."
    )


class Tally:
    """What one `--both` run actually compared, counted so the summary cannot overstate it.

    `ran` counts probes whose jar ran; `compiled` counts the subset the compiler produced. They
    are counted apart because an interpreter launcher also produces output, so folding them
    together is what let a summary say "identical output on both backends" about a probe that
    only ever had one backend in play.
    """

    def __init__(self):
        self.ran = 0
        self.compiled = 0

    def summary(self) -> list:
        lines = [
            # A6.3, stated as its own count so a reader can see the claim, not just a tick.
            f"ok: {self.compiled} probes compared compiled bytecode against the interpreter",
        ]
        fallbacks = self.ran - self.compiled
        if fallbacks:
            lines.append(
                f"note: {fallbacks} probe(s) ran via the interpreter launcher (declared "
                f"fallback). Their codepath reason is pinned, but their output is the "
                f"interpreter compared against itself, not across two backends."
            )
        return lines


def check_probe(name, expected, defect, jar, both, tally, failures, defects):
    """Run one probe and append its verdicts to `failures` / `defects`.

    The A6.3 codepath check lives here rather than in `main` so it can be tested by calling
    this function with stubbed `run` / `compile_program` / `run_compiled`, which is the only
    way a test can tell the gate is wired rather than merely present.
    """
    program = HERE / f"{name}.oq"
    if not program.is_file():
        failures.append(f"{name}: missing probe file")
        return
    extra = None
    if name in EXTRA_CLASSPATH:
        source = HERE / EXTRA_CLASSPATH[name]
        if not source.is_file():
            failures.append(f"{name}: needs {source.name} compiled onto the classpath and it is missing")
            return
        scratch = Path(tempfile.mkdtemp())
        try:
            extra = fixture_jar(source, scratch)
        except (RuntimeError, FileNotFoundError) as error:
            # A missing `javac` lands here as FileNotFoundError. Reported as a failure rather
            # than skipped: this harness already needs a JDK to build probes.
            failures.append(f"{name}: {error}")
            return
    output, code = run(jar, program, extra=extra)
    if both:
        # Compare the two backends on the same program. A refusal is compared as text, so a
        # backend that runs a program the interpreter rejects is caught.
        target, build_output, build_code = compile_program(jar, program, extra=extra)
        if build_code != 0 or not target.is_file():
            tail = build_output.strip().splitlines()[-1] if build_output.strip() else "no output"
            failures.append(f"{name}: oq build did not produce a jar (exit {build_code}): {tail}")
            return
        # A6.3: the jar must have been produced by the compiler, not by the interpreter
        # launcher. Checked before the jar runs, so a probe that silently fell back is
        # reported as a codepath failure rather than as a puzzling output mismatch.
        refusal = codepath_failure(name, build_output)
        if refusal is not None:
            failures.append(refusal)
            return
        compiled_output, compiled_code = run_compiled(target, extra=extra)
        tally.ran += 1
        if CODEPATH.search(build_output).group(0).strip() == COMPILED:
            tally.compiled += 1
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
        # Both directions are errors. A fix is the expected direction, and it still fails so
        # the note is promoted rather than left describing a bug that is gone; any other
        # change means the wrong output moved, which the record did not predict.
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
        return

    if isinstance(expected, str):
        # A probe whose correct behaviour is a refusal: the diagnostic has to be present, and
        # the program must not have gone on to succeed first.
        if not (expected in output and code != 0):
            failures.append(
                f"{name}: expected output containing {expected!r}, got {lines!r} (exit {code})"
            )
    elif expected is not None:
        if lines != expected:
            failures.append(f"{name}: expected {expected!r}, got {lines!r} (exit {code})")
    else:
        failures.append(f"{name}: no expectation and no defect record")


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
    tally = Tally()
    for name, expected, defect in PROBES:
        check_probe(name, expected, defect, jar, both, tally, failures, defects)

    if failures:
        print("FAILED")
        for failure in failures:
            print(f"  {failure}")
        return 1

    matched = sum(1 for _, e, d in PROBES if e is not None and d is None)
    print(f"ok: {matched} probes match their expectation")
    if both:
        for line in tally.summary():
            print(line)
    if defects:
        print(f"known defects reproducing verbatim: {len(defects)}")
        for name, marker in defects:
            print(f"  {name}: {marker}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
