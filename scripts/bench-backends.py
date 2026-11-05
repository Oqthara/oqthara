#!/usr/bin/env python3
"""Benchmarks the compiled bytecode backend against the interpreter.

Builds every program in bench/programs with `oq build`, keeps only programs that
report `codepath: compiled bytecode`, checks that both backends print the same
bytes, then warms up and measures whole CLI invocations.
"""
import argparse
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROGRAMS = ROOT / "bench" / "programs"
DEFAULT_JAR = ROOT / "oq-cli" / "build" / "libs" / "oq.jar"
BUILD_DIR = ROOT / "bench" / "build"
COMPILED_MARKER = "codepath: compiled bytecode"
LAUNCHER_MARKER = "codepath: interpreter launcher"


class BenchError(Exception):
    pass


def java_executable():
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.exists():
            return str(candidate)
    found = shutil.which("java")
    if not found:
        raise BenchError("no java executable found; set JAVA_HOME or put java on PATH")
    return found


def java_major(java):
    result = subprocess.run([java, "-version"], capture_output=True, text=True)
    match = re.search(r'version "(\d+)', result.stdout + result.stderr)
    if not match:
        raise BenchError("could not read the java version from `java -version`")
    return int(match.group(1))


def run(command, cwd=ROOT, timeout=300):
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def build_program(java, jar, source):
    result = run([java, "-jar", str(jar), "build", str(source)])
    output = result.stdout + result.stderr
    if result.returncode != 0:
        raise BenchError(f"`oq build {source.name}` failed:\n{output.strip()}")
    if COMPILED_MARKER not in result.stdout:
        reason = "unknown"
        for line in result.stdout.splitlines():
            if line.startswith(LAUNCHER_MARKER):
                reason = line[len(LAUNCHER_MARKER):].strip(" ()")
        return None, reason
    produced = source.with_suffix(".jar")
    if not produced.exists():
        raise BenchError(f"`oq build {source.name}` did not write {produced.name}")
    target = BUILD_DIR / f"{source.stem}.jar"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(produced), str(target))
    return target, None


def interpreter_command(java, jar, source):
    return [java, "-jar", str(jar), "run", str(source)]


def compiled_command(java, program_jar):
    return [java, "-jar", str(program_jar)]


def invoke(command, timeout=120):
    started = time.perf_counter()
    try:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                                timeout=timeout)
    except subprocess.TimeoutExpired:
        raise BenchError(f"`{' '.join(command[:3])} ...` timed out after {timeout}s")
    return time.perf_counter() - started, result


def check_divergence(java, jar, source, program_jar):
    _, reference = invoke(interpreter_command(java, jar, source))
    _, compiled = invoke(compiled_command(java, program_jar))
    problems = []
    if reference.returncode != 0:
        problems.append(f"interpreter exited {reference.returncode}")
    if compiled.returncode != 0:
        problems.append(f"compiled jar exited {compiled.returncode}")
    if reference.stdout != compiled.stdout:
        problems.append("stdout differs")
    if reference.stderr != compiled.stderr:
        problems.append("stderr differs")
    return problems


def measure(java, jar, source, program_jar, runs, warmup):
    interp = interpreter_command(java, jar, source)
    compiled = compiled_command(java, program_jar)
    for _ in range(warmup):
        invoke(interp)
        invoke(compiled)
    interp_samples = []
    compiled_samples = []
    for _ in range(runs):
        interp_samples.append(invoke(interp)[0])
        compiled_samples.append(invoke(compiled)[0])
    return interp_samples, compiled_samples


def markdown_table(rows):
    print("| Program | Interpreter median (ms) | Compiled median (ms) | Speedup | Runs |")
    print("|---|---:|---:|---:|---:|")
    for row in rows:
        print(f"| `{row['name']}` | {row['interp_ms']:.1f} | {row['compiled_ms']:.1f} "
              f"| {row['speedup']:.2f}x | {row['runs']} |")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--programs", default=str(DEFAULT_PROGRAMS),
                        help="directory of .oq workloads (default: bench/programs)")
    parser.add_argument("--jar", default=str(DEFAULT_JAR),
                        help="standalone CLI jar (default: oq-cli/build/libs/oq.jar)")
    parser.add_argument("--runs", type=int, default=5, help="measured runs per backend")
    parser.add_argument("--warmup", type=int, default=2, help="warmup runs per backend")
    parser.add_argument("--limit", type=int, default=4, help="maximum programs to select")
    parser.add_argument("--markdown", action="store_true", help="print only a markdown table")
    parser.add_argument("--verbose", action="store_true", help="print every sample")
    args = parser.parse_args()

    if args.runs < 1 or args.warmup < 0:
        print("bench: --runs must be >= 1 and --warmup >= 0", file=sys.stderr)
        return 2
    jar = Path(args.jar).resolve()
    programs_dir = Path(args.programs).resolve()
    if not jar.exists():
        print(f"bench: missing {jar}", file=sys.stderr)
        print("bench: build it with: JAVA_HOME=<jdk21> ./gradlew :oq-cli:standaloneJar",
              file=sys.stderr)
        return 2
    if not programs_dir.is_dir():
        print(f"bench: missing workload directory {programs_dir}", file=sys.stderr)
        return 2
    sources = sorted(programs_dir.glob("*.oq"))
    if not sources:
        print(f"bench: no .oq files in {programs_dir}", file=sys.stderr)
        return 2

    try:
        java = java_executable()
        major = java_major(java)
        if major < 21:
            raise BenchError(f"java {major} is too old; the compiled backend needs 21+")
        BUILD_DIR.mkdir(parents=True, exist_ok=True)
        selected = []
        rejected = []
        for source in sources:
            if len(selected) >= args.limit:
                break
            program_jar, reason = build_program(java, jar, source)
            if program_jar is None:
                rejected.append((source.name, reason))
                print(f"bench: rejected {source.name}: {reason}", file=sys.stderr)
                continue
            selected.append((source, program_jar))
        if not selected:
            raise BenchError("no program compiled to bytecode; nothing to measure")

        started = time.perf_counter()
        rows = []
        divergent = []
        if not args.markdown:
            print(f"bench: java {major} ({java})")
            print(f"bench: {platform.system()} {platform.release()} {platform.machine()}, "
                  f"{os.cpu_count()} cpus")
            print(f"bench: jar {jar}")
            noun = "program" if len(selected) == 1 else "programs"
            print(f"bench: {len(selected)} {noun}, {args.warmup} warmup + {args.runs} "
                  f"measured runs per backend")
        for source, program_jar in selected:
            problems = check_divergence(java, jar, source, program_jar)
            interp_samples, compiled_samples = measure(
                java, jar, source, program_jar, args.runs, args.warmup)
            interp_median = statistics.median(interp_samples) * 1000
            compiled_median = statistics.median(compiled_samples) * 1000
            speedup = interp_median / compiled_median if compiled_median else float("inf")
            if problems:
                divergent.append((source.name, problems))
            rows.append({
                "name": source.stem,
                "interp_ms": interp_median,
                "compiled_ms": compiled_median,
                "speedup": speedup,
                "runs": args.runs,
            })
            if args.verbose:
                print(f"bench: {source.stem} interpreter samples ms: "
                      + ", ".join(f"{s * 1000:.1f}" for s in interp_samples))
                print(f"bench: {source.stem} compiled samples ms: "
                      + ", ".join(f"{s * 1000:.1f}" for s in compiled_samples))

        if args.markdown:
            markdown_table(rows)
        else:
            print()
            print(f"{'program':<16}{'interpreter ms':>16}{'compiled ms':>14}"
                  f"{'speedup':>10}{'runs':>6}")
            for row in rows:
                print(f"{row['name']:<16}{row['interp_ms']:>16.1f}{row['compiled_ms']:>14.1f}"
                      f"{row['speedup']:>9.2f}x{row['runs']:>6}")
            speedups = [row["speedup"] for row in rows]
            print()
            noun = "program" if len(rows) == 1 else "programs"
            print(f"median speedup: {statistics.median(speedups):.2f}x "
                  f"across {len(rows)} {noun}")
            print(f"total wall time: {time.perf_counter() - started:.1f} s")
            if rejected:
                print(f"rejected (not compiled): {len(rejected)}")
                for name, reason in rejected:
                    print(f"  {name}: {reason}")

        if divergent:
            print(file=sys.stderr)
            for name, problems in divergent:
                print(f"bench: DIVERGENCE {name}: " + ", ".join(problems), file=sys.stderr)
            print("bench: backends diverged; numbers are not comparable", file=sys.stderr)
            return 1
    except (BenchError, subprocess.TimeoutExpired) as error:
        print(f"bench: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
