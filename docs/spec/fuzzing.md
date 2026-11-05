# Fuzzing

Oqthara uses **Jazzer** (`com.code-intelligence:jazzer-junit`) for coverage-guided JVM
fuzzing. Fuzzing is part of the permanent engineering lifecycle, not a one-off audit.

## What runs

| Target | Task | What it fuzzes | What counts as a bug |
|---|---|---|---|
| `LexerFuzzTest#lexerNeverThrowsUnexpectedExceptions` | `fuzzLexerText` | arbitrary UTF-8 plus deterministic deep-nesting shapes through the lexer | any exception other than `OqError`; a span outside the source, a span that runs backwards, or a token out of source order |
| `ParserFuzzTest#parserNeverThrowsUnexpectedExceptions` | `fuzzParserText` | arbitrary text through the parser | any exception other than `OqError`; an AST node whose span is invalid (line/column below 1, ends before it starts) |
| `ParserFuzzTest#deeplyNestedInputDoesNotOverflow` | `fuzzParserNesting` | parentheses, lists, unary chains (`-`, `not`) and `**` chains up to 2000 deep, plus nested text interpolation | `StackOverflowError`, or any run that does not finish promptly |
| `ParserFuzzTest#deeplyNestedBlocksDoNotOverflow` | `fuzzParserBlockNesting` | block nesting up to 600 levels (`if`, `while`, `for`, `fun`, `try`) | `StackOverflowError` |
| `PipelineFuzzTest#fullPipelineNeverThrowsUnexpectedExceptions` | `fuzzPipeline` | parse → lower → class-file emission | any exception other than `OqError`/`NotSupported`, or a malformed class file |
| `JsonFuzzTest#jsonParseNeverThrowsUnexpectedExceptions` | `fuzzJsonParse` | JSON text, then round-trips through both writers | any exception other than `OqError` |
| `JsonFuzzTest#deeplyNestedJsonDoesNotOverflow` | `fuzzJsonNesting` | arrays nested up to 2000 deep | `StackOverflowError` |
| `StdlibFuzzTest#pureBuiltinsNeverThrowUnexpectedExceptions` | `fuzzStdlibBuiltins` | pure text, math, JSON and collection builtins with arbitrary arguments | any exception other than `OqError`; a result the JSON writer cannot express |

**A lexical or syntax error is a valid outcome.** The contract is that user input produces a
diagnostic, never an internal crash.

Two invariants go beyond "no exception", because a wrong result is also a user-visible defect:

- **Lexer spans.** Every token must point at a line and column that exist in the source, its
  span must not end before it starts, and token start offsets must be non-decreasing. This
  catches off-by-one position bugs that only show up in error messages.
- **Parser spans.** Every node in a successfully parsed tree must carry a valid span. The
  target walks the whole AST iteratively (never recursively, so the walk itself cannot
  overflow) and pushes child nodes through a work list.

## Why one task per target

Jazzer's JUnit integration runs **one `@FuzzTest` method per JVM**. Running a class with
several targets would silently skip the rest. Each target therefore has its own Gradle task
(`fuzzLexerText`, `fuzzParserText`, `fuzzParserNesting`, `fuzzParserBlockNesting`,
`fuzzPipeline`, `fuzzJsonParse`, `fuzzJsonNesting`) and `fuzzTest` depends on all of them.

`fuzzTest` is **not** part of `check`, so ordinary builds stay fast. It runs in the dedicated
`jazzer-fuzz` CircleCI job on every push and pull request, and in the **Nightly Fuzzing**
GitHub Actions workflow (`.github/workflows/nightly.yml`) every day at 03:00 UTC. The nightly
caps every target at 60 seconds through `JAZZER_MAX_DURATION`, so the whole pass stays bounded;
on a finding it fails and uploads the generated corpus and crash inputs as an artifact.

## Running it

Fuzzing mode is enabled by `JAZZER_FUZZ=1`. Without it the same classes replay their regression
corpus once, which is what the `fuzz*` tasks do by default.

```bash
# Every target, using each @FuzzTest(maxDuration = ...) annotation.
JAZZER_FUZZ=1 ./gradlew --no-daemon fuzzTest

# One target, bounded by the environment instead of the annotation.
JAZZER_FUZZ=1 JAZZER_MAX_DURATION=120s ./gradlew --no-daemon :oq-core:fuzzParserText

# The 10-minute parser acceptance pass (two targets, ten minutes each).
JAZZER_FUZZ=1 JAZZER_MAX_DURATION=600s ./gradlew --no-daemon \
  :oq-core:fuzzParserText :oq-core:fuzzParserNesting
```

`JAZZER_MAX_DURATION` overrides `@FuzzTest(maxDuration)`; Jazzer reads it as its `max_duration`
option and passes `-max_total_time` to libFuzzer. Time is a per-target budget, so a command with
two targets runs for about twice the value.

### Results recorded for T-0021

```
JAZZER_FUZZ=1 JAZZER_MAX_DURATION=60s ./gradlew --no-daemon \
  :oq-core:fuzzLexerText :oq-core:fuzzParserText \
  :oq-core:fuzzParserNesting :oq-core:fuzzParserBlockNesting

fuzzLexerText        172,062 runs in 61 s   cov: 767  ft: 5343  corp: 962/120Kb   PASSED
fuzzParserText        66,889 runs in 61 s   cov: 1061 ft: 6517  corp: 1270/107Kb  PASSED
fuzzParserNesting     45,252 runs in 61 s   cov: 407  ft: 2442  corp: 116/292b    PASSED
fuzzParserBlockNesting 64,498 runs in 61 s  cov: 337  ft: 2211  corp: 92/233b     PASSED
```

Zero crashes across 348,701 executions. The task report for T-0021 records the matching
ten-minute parser pass; the commands above reproduce both.

## Reproducing a finding

Jazzer JUnit writes findings next to the module, not into `build/`:

- **Crash input:** `<module>/crash-<sha1>` (for example `oq-core/crash-1a2b3c...`). It contains
  the exact bytes that made the target throw.
- **Generated corpus:** `<module>/.cifuzz-corpus/<fuzz-class>/<method>/` holds every input
  Jazzer kept for coverage. Both paths are cached between runs, so a finding stays reproducible
  after the fact.

To reproduce:

1. Run the failing target once without fuzzing, so the corpus and any seed are replayed:
   `./gradlew --no-daemon :oq-core:fuzzParserText`. Without `JAZZER_FUZZ` this is a plain JUnit
   run, and the crash fails the build with a stack trace.
2. To replay the exact saved input, copy it into the curated corpus (below) or feed it to the
   CLI: `./gradlew :oq-cli:standaloneJar` then `java -jar oq-cli/build/libs/oq.jar crash-<sha1>`.
3. Keep the input. Every finding must end up as a permanent test: add the source text to
   `curated-cases.txt` and, when it has a stable shape (a depth, a byte pattern), add a focused
   JUnit case to `CuratedCorpusTest`. A finding is never closed by deleting the input.

CI uploads `build/fuzz-artifacts`, `**/.cifuzz-corpus/**`, `**/crash-*` and the fuzz test
results when a run fails, so the reproducer survives the runner.

## Regression corpus

`oq-core/src/fuzz/resources/dev/oqthara/core/curated-cases.txt` holds curated inputs that
exercise boundaries: nested braces in text, literal-brace escapes, unterminated strings,
deeply nested parentheses, module imports that fail, emoji, NUL bytes, and the T-0021
unary/power/interpolation nesting cases. They are replayed by `CuratedCorpusTest` on every
build, so a regression is caught even when fuzzing is skipped.

## Findings so far

| Cycle | Target | Finding | Fixed by |
|---|---|---|---|
| 17 | `fuzzParserNesting` | 390 levels of nested parentheses crashed `oq check` with a raw `StackOverflowError` and exit 70 | explicit `MAX_NESTING = 128` guard that raises a normal diagnostic, plus regression tests at depths 200/390/500/2000 |
| 18 | manual follow-up | 2000 nested `if` blocks crashed the parser the same way | `MAX_BLOCK_NESTING = 128` guard for statements, fuzz target and tests at 200/2000 levels |
| 19 | `fuzzJsonParse` | `json_parse(".")` escaped as a raw `NumberFormatException` | the reader now requires digits before a fraction or exponent and reports every malformed number as JSON syntax error; regression tests |
| 19 | `fuzzJsonNesting` (CI only) | 1884-deep arrays overflowed the reader on CircleCI, while the same target passed locally: the stack size differs per JVM | explicit `MAX_DEPTH = 256` in the JSON reader, with tests at 300/1884/5000 and 100 levels still parsing |
| 21 (T-0021) | `fuzzParserNesting` (manual probe) | `not` and unary `-` chains bypassed `MAX_NESTING`: 20k prefixes produced a raw `StackOverflowError` | the unary recursion now enters the same `nesting` counter; limits are 127 accepted, 128 rejected, with tests at 20k/50k |
| 21 (T-0021) | `fuzzParserNesting` (manual probe) | `**` is right-recursive and bypassed `MAX_NESTING` in the same way | the right-hand `power()` recursion enters the counter; regression test at 50k |
| 21 (T-0021) | `fuzzParserNesting` (manual probe) | nested text interpolation re-lexed and re-parsed the inner text at every level: 20 levels took ~0.4 s, 24 levels ~6 s, ~26 levels over 20 s, and a ~200-byte file could hang the CLI | `MAX_INTERPOLATION_NESTING = 16` carried across the lexer/parser pair; test asserts a diagnostic under 5 s plus a shallow-interpolation positive test |

This is the whole point of fuzzing: the defects were invisible to unit tests and would have
been reported as "internal error: StackOverflowError" to a user.

## Adding a target

1. Add the `@FuzzTest` method to a class under `<module>/src/fuzz/java`.
2. Register it in `build.gradle.kts` in the `allFuzzTargets` list with its owning module.
3. Make the target side-effect free: no network, no real filesystem writes, no processes.
4. Treat only the project's own error type as an acceptable failure; everything else is a
   finding. Assert structural postconditions too (spans, order, bounds), not just the absence
   of exceptions.
5. If the target needs generated input shapes rather than raw text, branch on
   `data.consumeInt(...)` inside the single `@FuzzTest`, because only one target method runs per
   JVM.

## What is not covered yet

- The rest of the stdlib: text operations, path resolution, CSV.
- Semantic analysis, which has no separate pass yet.
- Expression and block limits are separate constants because the parser recurses through
  different call paths for each. Unary, power and interpolation now share the expression limit.
- Class files are checked for magic only; full verification happens when the JVM loads the
  generated class in `BytecodeCompilerTest`.
