# Fuzzing

Oqthara uses **Jazzer** (`com.code-intelligence:jazzer-junit`) for coverage-guided JVM
fuzzing. Fuzzing is part of the permanent engineering lifecycle, not a one-off audit.

## What runs

| Target | Task | What it fuzzes | What counts as a bug |
|---|---|---|---|
| `LexerFuzzTest#lexerNeverThrowsUnexpectedExceptions` | `fuzzLexerText` | arbitrary UTF-8 through the lexer | any exception other than `OqError`, or a span outside the source |
| `ParserFuzzTest#parserNeverThrowsUnexpectedExceptions` | `fuzzParserText` | arbitrary text through the parser | any exception other than `OqError` |
| `ParserFuzzTest#deeplyNestedInputDoesNotOverflow` | `fuzzParserNesting` | expression nesting up to 400 levels | `StackOverflowError` |
| `ParserFuzzTest#deeplyNestedBlocksDoNotOverflow` | `fuzzParserBlockNesting` | block nesting up to 600 levels (`if`, `while`, `for`, `fun`, `try`) | `StackOverflowError` |
| `PipelineFuzzTest#fullPipelineNeverThrowsUnexpectedExceptions` | `fuzzPipeline` | parse → lower → class-file emission | any exception other than `OqError`/`NotSupported`, or a malformed class file |
| `JsonFuzzTest#jsonParseNeverThrowsUnexpectedExceptions` | `fuzzJsonParse` | JSON text, then round-trips through both writers | any exception other than `OqError` |
| `JsonFuzzTest#deeplyNestedJsonDoesNotOverflow` | `fuzzJsonNesting` | arrays nested up to 2000 deep | `StackOverflowError` |

**A lexical or syntax error is a valid outcome.** The contract is that user input produces a
diagnostic, never an internal crash.

## Why one task per target

Jazzer's JUnit integration runs **one `@FuzzTest` method per JVM**. Running a class with
several targets would silently skip the rest. Each target therefore has its own Gradle task
(`fuzzLexerText`, `fuzzParserText`, `fuzzParserNesting`, `fuzzPipeline`) and `fuzzTest`
depends on all of them.

`fuzzTest` is **not** part of `check`, so ordinary builds stay fast. It runs in the dedicated
`jazzer-fuzz` CircleCI job on every push and pull request, with a longer nightly profile.

## Running it

```bash
JAZZER_FUZZ=1 ./gradlew --no-daemon fuzzTest
```

Fuzzing mode is enabled by `JAZZER_FUZZ=1`; without it the same classes run their regression
corpus once, which is what `./gradlew test` would do if they were in the test source set.

Current bounded run (four targets, ~30 s each): roughly **1.7 million executions, zero
crashes** on JDK 21.

## Regression corpus

`oq-core/src/fuzz/resources/dev/oqthara/core/curated-cases.txt` holds curated inputs that
exercise boundaries: nested braces in text, literal-brace escapes, unterminated strings,
deeply nested parentheses, module imports that fail, emoji, NUL bytes. They are replayed by
`CuratedCorpusTest` on every build, so a regression is caught even when fuzzing is skipped.

Jazzer's own generated corpus is cached between runs and uploaded as a CI artifact
(`build/fuzz-artifacts`). Crashing inputs are preserved there before any fix is made.

## Findings so far

| Cycle | Target | Finding | Fixed by |
|---|---|---|---|
| 17 | `fuzzParserNesting` | 390 levels of nested parentheses crashed `oq check` with a raw `StackOverflowError` and exit 70 | explicit `MAX_NESTING = 128` guard that raises a normal diagnostic, plus regression tests at depths 200/390/500/2000 |
| 18 | manual follow-up | 2000 nested `if` blocks crashed the parser the same way | `MAX_BLOCK_NESTING = 128` guard for statements, fuzz target and tests at 200/2000 levels |
| 19 | `fuzzJsonParse` | `json_parse(".")` escaped as a raw `NumberFormatException` | the reader now requires digits before a fraction or exponent and reports every malformed number as JSON syntax error; regression tests |

This is the whole point of fuzzing: the defect was invisible to unit tests and would have
been reported as "internal error: StackOverflowError" to a user.

## Adding a target

1. Add the `@FuzzTest` method to a class under `<module>/src/fuzz/java`.
2. Register it in `build.gradle.kts` in the `allFuzzTargets` list with its owning module.
3. Make the target side-effect free: no network, no real filesystem writes, no processes.
4. Treat only the project's own error type as an acceptable failure; everything else is a
   finding.

## What is not covered yet

- The rest of the stdlib: text operations, path resolution, CSV.
- Semantic analysis, which has no separate pass yet.
- Deeply nested *statements* are now guarded too. Expression and block limits are separate
  constants because the parser recurses through different call paths for each.
- Class files are checked for magic only; full verification happens when the JVM loads the
  generated class in `BytecodeCompilerTest`.
