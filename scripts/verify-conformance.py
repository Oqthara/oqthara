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

Each program is exercised in its own directory so the cases cannot observe each other, and
independent cases run concurrently. Concurrency changes only scheduling: every program is
still built and run, and the counts, divergence list and exit code are aggregated from
results keyed by program name, so the verdict does not depend on completion order.
"""
import argparse
import concurrent.futures
import json
import os
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
BASELINE = {"executable": 212, "compiled": 203}

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
    tally = observed["compiled"] + observed.get("fallback", 0)
    if tally > observed["executable"]:
        problems.append(
            f"compiled and fallback exceed the executable corpus: {tally} > {observed['executable']}"
            " (a case that cannot run is being counted in the compiler tally)")
    if divergences:
        problems.append(f"{len(divergences)} divergence(s)")
    return problems


def check_program(program, cases, jar, case_root):
    """Exercise one program through both backends inside its own scratch tree.

    Returns `(compiled, fallback, divergences)` for that program. The caller aggregates by
    name, so the result cannot depend on the order cases happen to finish in.
    """
    name = program.name
    stem = program.stem
    work = case_root / "work"
    build_dir = case_root / "build"
    work.mkdir(parents=True)
    build_dir.mkdir(parents=True)

    for fixture in FIXTURES:
        if (cases / fixture).exists():
            shutil.copy2(cases / fixture, work / fixture)
    shutil.copy2(program, work / name)

    # `oq build` writes its jar beside the source, so the build directory holds this one
    # program: a sibling from another case can never change what gets compiled.
    for fixture in FIXTURES:
        if (cases / fixture).exists():
            shutil.copy2(cases / fixture, build_dir / fixture)
    shutil.copy2(program, build_dir / name)

    local = work / name
    expected_err = cases / (stem + ".err")
    divergences = []
    compiled = 0
    fallback = 0

    interpreted = run(["java", "-jar", str(jar), "run", str(local)], work)
    interpreted = (interpreted.returncode,
                   normalize(interpreted.stdout), normalize(interpreted.stderr))

    built = run(["java", "-jar", str(jar), "build", str(build_dir / name)], build_dir)
    build_out = normalize(built.stdout + built.stderr)
    if built.returncode != 0:
        # A program that does not parse cannot be built, and that is correct. It is only
        # acceptable when the corpus says this case must fail and `oq run` agrees, so an
        # unexpected build failure can never pass as a divergence.
        if expected_err.exists() and interpreted[0] != 0:
            return compiled, fallback, divergences
        divergences.append(f"{name}: oq build failed: {build_out.strip()[:300]}")
        return compiled, fallback, divergences

    # `compiled` counts only programs that must run, as BytecodeCompilerTest does by
    # skipping .err cases. `fallback` is narrowed the same way so that the two are
    # disjoint halves of the executable set: a .err case that happens to build reaches
    # the interpreter for bytecode that cannot be produced, which says nothing about how
    # much of the corpus the compiler covers. Counting it inflated compiled + fallback
    # past the executable total.
    if not expected_err.exists():
        if "codepath: compiled bytecode" in build_out:
            compiled = 1
        else:
            fallback = 1

    jar_path = build_dir / (stem + ".jar")
    if not jar_path.exists():
        divergences.append(f"{name}: oq build produced no jar: {build_out.strip()[:300]}")
        return compiled, fallback, divergences

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
        # A compiled jar embeds no source, so its caret block is intentionally absent; only
        # the message and exit code are compared for error cases.
        if interpreted[0] != jarred[0]:
            divergences.append(
                f"{name}: exit codes differ (run={interpreted[0]}, jar={jarred[0]})")
        return compiled, fallback, divergences

    if interpreted[1] != jarred[1]:
        divergences.append(
            f"{name}: stdout differs\n"
            f"  oq run:    {interpreted[1]!r}\n"
            f"  java -jar: {jarred[1]!r}\n"
            f"  build: {build_out.strip()[:200]}")
    if interpreted[0] != jarred[0]:
        divergences.append(
            f"{name}: exit codes differ (run={interpreted[0]}, jar={jarred[0]})")
    return compiled, fallback, divergences


def default_jobs():
    """Return a conservative default for concurrent cases.

    Each case runs up to three JVMs in sequence, so the useful ceiling is well below the
    core count; four captures most of the gain without turning the suite into a memory
    spike on a shared runner.
    """
    return max(1, min(4, os.cpu_count() or 1))


def collect(programs, cases, jar, scratch, jobs):
    """Run every program and return its result indexed by program name.

    A worker that raises is recorded as a divergence instead of aborting the run, so one
    broken case reports itself rather than taking the other 228 with it.
    """
    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {
            pool.submit(check_program, program, cases, jar,
                        scratch / f"case-{program.stem}"): program
            for program in programs
        }
        for future in concurrent.futures.as_completed(futures):
            program = futures[future]
            try:
                results[program.name] = future.result()
            except Exception as exc:  # noqa: BLE001 - reported as a divergence below
                results[program.name] = (0, 0, [f"{program.name}: verifier raised {exc!r}"])
    return results


def aggregate(programs, results):
    """Fold per-program results into totals, in corpus order.

    Completion order is deliberately ignored: iterating `programs` means the counts and the
    divergence list are identical at every concurrency level, so a run is reproducible and a
    diff between two runs is a real difference rather than a scheduling artefact.
    """
    compiled = 0
    fallback = 0
    divergences = []
    for program in programs:
        case_compiled, case_fallback, case_divergences = results.get(
            program.name, (0, 0, [f"{program.name}: no result recorded"]))
        compiled += case_compiled
        fallback += case_fallback
        divergences.extend(case_divergences)
    return compiled, fallback, divergences


def main(argv=None):
    parser = argparse.ArgumentParser(description="Compare `oq run` against `java -jar`.")
    parser.add_argument(
        "--jobs", type=int, default=default_jobs(),
        help="cases to run concurrently (default: %(default)s)")
    args = parser.parse_args(argv)
    if args.jobs < 1:
        parser.error("--jobs must be at least 1")

    if not JAR.exists():
        print(f"error: {JAR} is missing; run ./gradlew :oq-cli:standaloneJar")
        return 2

    programs, executable = measure_corpus(CASES)

    with tempfile.TemporaryDirectory() as scratch:
        results = collect(programs, CASES, JAR, pathlib.Path(scratch), args.jobs)

    compiled, fallback, divergences = aggregate(programs, results)

    summary = {
        "programs": len(programs),
        "executable": len(executable),
        "compiled": compiled,
        "fallback": fallback,
        "divergenceCount": len(divergences),
        "jobs": args.jobs,
        "baseline": BASELINE,
        "divergences": divergences[:20],
    }
    print(json.dumps(summary, indent=2))
    problems = evaluate(summary, divergences)
    if problems:
        for problem in problems:
            print(f"error: {problem}")
        return 1
    print(f"ok: {compiled} compiled, {fallback} fallback "
          f"({compiled + fallback} of {len(executable)} executable), "
          f"0 divergences across {len(programs)} programs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
