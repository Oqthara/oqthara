# Backend benchmark

Compares the compiled bytecode backend against the interpreter on the same
programs, on the same JVM, with the same output.

The script builds each `.oq` file in `bench/programs/` with `oq build`, keeps
only the ones that print `codepath: compiled bytecode`, verifies byte-for-byte
that stdout and stderr match `oq run`, then warms up and measures whole CLI
invocations (`java -jar oq.jar run` vs `java -jar <program>.jar`). Medians are
reported, so a single slow JVM start does not move the result.

## Command

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :oq-cli:standaloneJar

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  python3 scripts/bench-backends.py
```

Options: `--runs N` (measured runs per backend, default 5), `--warmup N`
(default 2), `--limit N` (maximum compiled programs, default 4), `--verbose`
(print every sample), `--markdown` (table only), `--programs DIR`, `--jar PATH`.

The process exits `0` when every measured program agrees between backends,
`1` when any output diverges, and `2` on a setup or build failure. Programs
that fall back to the interpreter launcher are listed as rejected with the
reason from `oq build` and are never timed.

## Workloads

All sizes are fixed constants in the source files, so runs are deterministic
and need no network. Total runtime is about 18 seconds on the machine below.

| File | What it does |
|---|---|
| `programs/numeric_loop.oq` | 5,000,000 iterations of add and modulo |
| `programs/string_list.oq` | 100,000 rounds over a five-word list with `length` and string indexing |
| `programs/recursion.oq` | naive `fib(28)`, 1,028,457 calls |
| `programs/map_ops.oq` | 500,000 map writes, then 500,000 reads |

## Context

- Machine: Apple M2, 8 cores, 8 GiB RAM, macOS 27.2 (Darwin 27.2.0, arm64)
- JDK: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`,
  OpenJDK 21.0.13 (`java -version` reports `Homebrew 21.0.13.0.3`)
- CLI: `oq-cli/build/libs/oq.jar` built from `54378e4` (the `origin/main`
  commit this branch started from)
- Date: 2026-11-05
- No JVM tuning flags: both sides get the same default JVM.

## Numbers

Produced by running the command above on the context above, 2 warmup and 5
measured runs per backend:

```
program           interpreter ms   compiled ms   speedup  runs
map_ops                    320.1         115.6     2.77x     5
numeric_loop               645.9          81.5     7.92x     5
recursion                  580.4          75.2     7.72x     5
string_list                313.0          81.6     3.83x     5
```

| Program | Interpreter median (ms) | Compiled median (ms) | Speedup | Runs |
|---|---:|---:|---:|---:|
| `map_ops` | 320.1 | 115.6 | 2.77x | 5 |
| `numeric_loop` | 645.9 | 81.5 | 7.92x | 5 |
| `recursion` | 580.4 | 75.2 | 7.72x | 5 |
| `string_list` | 313.0 | 81.6 | 3.83x | 5 |

Median speedup across the four programs is 5.78x.

The compiled jar includes the runtime classes, so every measurement also pays
one JVM start. That start is roughly 50-70 ms, which is why the map workload
with comparatively little work behind it shows the smallest speedup. Numbers
will move with the machine, the JDK build and background load; compare runs on
one machine rather than across machines.
