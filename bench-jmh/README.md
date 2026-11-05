# Interpreter benchmarks (JMH)

Steady-state throughput of `dev.oqthara.core.Interpreter` on fixed programs.

This is **not** the same question as `bench/README.md`. That script times whole CLI
invocations (`oq run` vs `java -jar`), so JVM startup dominates. Here JMH keeps one
JVM alive, warms the interpreter up and reports nanoseconds of interpretation per
operation. Use this module to compare interpreter changes against a recorded
baseline; use `scripts/bench-backends.py` to compare backends.

## Workloads

Each workload is a small program plus its exact expected stdout
(`src/main/java/dev/oqthara/bench/Workloads.java`). The sources are inlined rather
than taken from `bench/programs/` because those files are sized for whole-process
timing (5,000,000 iterations, `fib(28)`); in-process they would take seconds per
invocation. Same shapes, smaller constants.

| Benchmark | Program | What it exercises |
|---|---|---|
| `numericLoop` | `for i in 1 to 1000`, add to an accumulator, `print` | loop dispatch, arithmetic, variable assignment |
| `fib` | naive `fib(20)`, 21,891 recursive calls | user function call overhead, `if`, `return` |
| `listOps` | build 500 elements with `push`, `sum`, `sort` twice | list allocation/growth, native builtin calls |
| `stringBuild` | 200 string concatenations, `length` | string allocation and concatenation |

Every benchmark parses its program once in `@Setup` (`Scope.Benchmark`) and runs it
on a **fresh** interpreter per invocation, writing to a `ByteArrayOutputStream`.
A fresh interpreter is required: globals and `fun` definitions would otherwise leak
between invocations. Console I/O is excluded on purpose.

`@Setup` also asserts the program prints exactly the recorded output. A benchmark
whose program errors out or prints the wrong thing fails immediately instead of
recording meaningless time. `src/test/java` runs the same assertion for all four
workloads under plain JUnit, so `./gradlew build` keeps them honest without
running JMH.

## Run

```bash
# everything (default 3 warmup + 5 measurement iterations, 1 fork)
./gradlew :bench-jmh:jmh

# a single workload, short run
./gradlew :bench-jmh:jmh -PjmhArgs="-wi 1 -i 2 -f 1 -w 200ms -r 200ms numericLoop"

# list what is available
./gradlew :bench-jmh:jmh -PjmhArgs="-l"
```

Benchmark names match the workload names, so a plain `numericLoop` filters one
benchmark. Everything in `-PjmhArgs` is passed to `org.openjdk.jmh.Main`
unchanged, so any JMH flag works (`-f`, `-wi`, `-i`, `-prof`, `-ro`, ...).

The task is `:bench-jmh:jmh` and it is deliberately **not** wired into
`check`/`build`: a benchmark is a measurement, and a build must not depend on
machine load or take minutes.

## Baseline comparison

Record a run as JSON, commit the JSON, then diff medians between runs.

```bash
# 1. record the baseline on a quiet machine
./gradlew :bench-jmh:jmh -PjmhArgs="-wi 3 -i 5 -f 1 -rf json -rff bench/results/2026-11-05-interpreter.json"

# 2. after changing the interpreter, record another run
./gradlew :bench-jmh:jmh -PjmhArgs="-wi 3 -i 5 -f 1 -rf json -rff bench/results/after.json"

# 3. compare medians per benchmark
python3 - <<'PY'
import json, sys
def load(path):
    with open(path) as f:
        return {r["benchmark"]: r["primaryMetric"]["score"] for r in json.load(f)}
base, new = load("bench/results/2026-11-05-interpreter.json"), load("bench/results/after.json")
for name in sorted(base):
    b, n = base[name], new[name]
    print(f"{name:45s} {b:10.3f} -> {n:10.3f} us/op  {(n / b - 1) * 100:+.1f}%")
PY
```

JMH writes one JSON record per benchmark containing every iteration and the
`primaryMetric` score (the mean, in the unit shown in the table). Diff that
`score`, not a single iteration, and only compare runs made with the same
`-wi`/`-i`/`-f` flags on the same machine and JDK. Raw results are not committed
yet; `bench/results/` is where they belong once a baseline is chosen.

## Module layout

- `src/main/java/dev/oqthara/bench` — `Workloads` (programs + expected output) and
  `Runner` (CLI-equivalent interpreter construction, output capture, verification).
- `src/jmh/java/dev/oqthara/bench` — one JMH benchmark class per workload. The
  `jmh` source set is separate so benchmark classes never ship in `main`.
- `src/test/java/dev/oqthara/bench` — JUnit guard for the workloads, part of `test`.
