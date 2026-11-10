#!/usr/bin/env python3
"""Independent conformance comparison: `oq run` against `java -jar` for every case.

This is deliberately not a Gradle test. It drives the real CLI artifact the way a user
does, so a divergence between the interpreter and the compiled jar cannot hide behind a
test harness that shares code with either backend.

`oq build` writes its jar next to the source file, so each program is copied into a
scratch directory together with the conformance fixtures before building. That keeps the
corpus clean and still exercises relative `read()` exactly as the suite does.

A program with a sibling .err must fail with the same message on both backends. Every
other program must print byte-identical stdout through both backends.
"""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CASES = ROOT / "conformance"
JAR = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"

# Measured from a clean build. Neither number is aspirational: `executable` pins the size of
# the corpus so a case cannot quietly join or leave it, and `compiled` pins how many of those
# cases the bytecode backend handles, matching BytecodeCompilerTest. A regressing number means
# a real capability was lost; a growing number means the corpus changed and the pin is stale.
# Update both only from a fresh measurement, and say so in the commit message.
BASELINE = {"executable": 212, "compiled": 196}

FIXTURES = ["readme-fixture.txt"]


def run(cmd, cwd):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


def normalize(text):
    return text.replace("\r\n", "\n")


def measure_corpus(cases):
    """Return every conformance program and the subset that is expected to run cleanly.

    A program with a sibling `.err` file is a case that must fail, so it is not part of the
    executable set even though it still takes part in the backend comparison.
    """
    programs = sorted(cases.glob("*.oq"))
    executable = [p for p in programs if not (cases / (p.stem + ".err")).exists()]
    return programs, executable


def evaluate(observed, divergences):
    """Compare a measurement against BASELINE and return human-readable failures."""
    problems = []
    if observed["executable"] != BASELINE["executable"]:
        problems.append(
            f"executable corpus changed: {observed['executable']} != {BASELINE['executable']}"
            " (a case gained or lost a .err sibling; update BASELINE from a fresh measurement)")
    if observed["compiled"] < BASELINE["compiled"]:
        problems.append(
            f"compiled conformance regressed: {observed['compiled']} < {BASELINE['compiled']}")
    if divergences:
        problems.append(f"{len(divergences)} divergence(s)")
    return problems


def main():
    if not JAR.exists():
        print(f"error: {JAR} is missing; run ./gradlew :oq-cli:standaloneJar")
        return 2

    programs, executable = measure_corpus(CASES)
    divergences = []
    compiled = 0
    fallback = 0

    with tempfile.TemporaryDirectory() as scratch:
        work = pathlib.Path(scratch)
        for fixture in FIXTURES:
            if (CASES / fixture).exists():
                shutil.copy2(CASES / fixture, work / fixture)

        for program in programs:
            name = program.name
            local = work / name
            shutil.copy2(program, local)
            build_dir = work / f"build-{program.stem}"
            build_dir.mkdir()
            # The fixture files live beside the program in the scratch root.
            for fixture in FIXTURES:
                if (CASES / fixture).exists():
                    shutil.copy2(CASES / fixture, build_dir / fixture)
            shutil.copy2(program, build_dir / name)

            expected_err = CASES / (program.stem + ".err")

            interpreted = run(["java", "-jar", str(JAR), "run", str(local)], work)
            interpreted = (interpreted.returncode,
                           normalize(interpreted.stdout), normalize(interpreted.stderr))

            built = run(["java", "-jar", str(JAR), "build", str(build_dir / name)], build_dir)
            build_out = normalize(built.stdout + built.stderr)
            if built.returncode != 0:
                # A program that does not parse cannot be built, and that is correct. It is
                # only acceptable when the corpus says this case must fail and `oq run`
                # agrees, so an unexpected build failure can never pass as a divergence.
                if expected_err.exists() and interpreted[0] != 0:
                    continue
                divergences.append(f"{name}: oq build failed: {build_out.strip()[:300]}")
                continue

            if "codepath: compiled bytecode" in build_out:
                # The recorded 182/197 baseline counts compilable programs that must run,
                # exactly as BytecodeCompilerTest does by skipping .err cases.
                if not expected_err.exists():
                    compiled += 1
            else:
                fallback += 1

            jar_path = build_dir / (program.stem + ".jar")
            if not jar_path.exists():
                divergences.append(f"{name}: oq build produced no jar: {build_out.strip()[:300]}")
                continue

            jarred = run(["java", "-jar", str(jar_path)], build_dir)
            jarred = (jarred.returncode,
                      normalize(jarred.stdout), normalize(jarred.stderr))

            if expected_err.exists():
                fragment = expected_err.read_text(encoding="utf-8").strip()
                for label, result in (("oq run", interpreted), ("java -jar", jarred)):
                    if result[0] == 0:
                        divergences.append(f"{name}: {label} exited 0 but an error was expected")
                    elif fragment not in result[2]:
                        divergences.append(
                            f"{name}: {label} error message mismatch\n"
                            f"  expected to contain: {fragment}\n"
                            f"  actual: {result[2].strip()[:400]}")
                # A compiled jar embeds no source, so its caret block is intentionally
                # absent; only the message and exit code are compared for error cases.
                if interpreted[0] != jarred[0]:
                    divergences.append(
                        f"{name}: exit codes differ (run={interpreted[0]}, jar={jarred[0]})")
                continue

            if interpreted[1] != jarred[1]:
                divergences.append(
                    f"{name}: stdout differs\n"
                    f"  oq run:    {interpreted[1]!r}\n"
                    f"  java -jar: {jarred[1]!r}\n"
                    f"  build: {build_out.strip()[:200]}")
            if interpreted[0] != jarred[0]:
                divergences.append(
                    f"{name}: exit codes differ (run={interpreted[0]}, jar={jarred[0]})")

    summary = {
        "programs": len(programs),
        "executable": len(executable),
        "compiled": compiled,
        "fallback": fallback,
        "divergenceCount": len(divergences),
        "baseline": BASELINE,
        "divergences": divergences[:20],
    }
    print(json.dumps(summary, indent=2))
    problems = evaluate(summary, divergences)
    if problems:
        for problem in problems:
            print(f"error: {problem}")
        return 1
    print(f"ok: {compiled} compiled, {fallback} fallback, 0 divergences "
          f"across {len(programs)} programs ({len(executable)} executable)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
