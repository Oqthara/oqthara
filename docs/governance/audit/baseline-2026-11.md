# Baseline audit — November 2026

Read-only audit of the Oqthara implementation against its own specification. Task
`OQ-TASK-BASELINE-001`. No implementation file was changed by this audit, per
[OQ-DEC-2026-004](../README.md#oq-dec-2026-004--baseline-audit-is-read-only-and-evidence-bound).

Baseline commit: `7ffb675` on branch `oqthara-hive-bootstrap-baseline-audit`.

Working-tree note, for completeness: `gradlew.bat` already carried an uncommitted
CRLF-to-LF line-ending change when this audit began. It is unrelated to this work and was
neither caused nor committed by it. The only other changes are the new
`docs/governance/` and `.github/agents/` trees and the regenerated `docs/catalog.json`.

## Method

Three independent specialists audited separate areas, each required to cite `path:line`
for every claim and to mark anything uninspected as `NOT INSPECTED`. Their findings were
then re-checked directly against the repository before publication. Where a specialist
claim could be verified by a command, the command was re-run.

Statuses in the matrix below mean exactly:

| Status | Meaning |
| --- | --- |
| `VERIFIED` | Specification, implementation, and an executable test all agree on the same contract |
| `PARTIALLY VERIFIED` | Implementation and a test exist, but the written contract is missing, weaker than the code, or not asserted by that test |
| `INCONSISTENT` | The specification and the implementation demonstrably disagree |
| `MISSING` | The contract exists but no implementation or no test backs it |
| `NOT INSPECTED` | Outside what this audit covered |

## Executed evidence

Transcripts below are abridged: Gradle task lines and key output lines are reproduced,
but some intermediate output is elided (marked `...`). The commands are exactly as run,
and the summary lines are verbatim.

```
$ ./gradlew test --rerun-tasks --console=plain
> Task :oq-runtime:test
> Task :oq-core:test
> Task :oq-stdlib:test
> Task :oq-lsp:test
> Task :bench-jmh:test
> Task :oq-compiler:test
> Task :oq-cli:test
BUILD SUCCESSFUL in 56s
33 actionable tasks: 33 executed
```

`--rerun-tasks` was used deliberately: a plain `./gradlew test` reports
`33 actionable tasks: 33 up-to-date` in under a second, which is a cache hit and not
evidence that anything ran. The run above executed every task.

```
$ ./gradlew build --console=plain
> Task :coverageGate
oq-core line coverage: 84.6% (gate 80%)
BUILD SUCCESSFUL in 1s
```

The coverage figure is produced by `coverageGate` (`build.gradle.kts:180-201`), which
parses `build/reports/jacoco/jacocoAggregate/jacocoAggregate.xml` and fails under 80% for
package `dev/oqthara/core`.

```
$ find . -name "*Test.java" -path "*/src/test/*" | wc -l
28
$ grep -rh "@Test" --include="*.java" */src/test/java | wc -l
353
$ grep -A5 "KEYWORDS = Set.of" oq-core/src/main/java/dev/oqthara/core/Lexer.java | grep -o '"[a-z]*"' | wc -l
20
$ ls docs/adr/*.md | wc -l
11
```

Conformance corpus, counted directly:

```
$ ls conformance | sed 's/.*\.//' | sort | uniq -c
 12 err
209 oq
197 out
  1 txt
```

197 `.out` expectations plus 12 `.err` expectations equals 209 programs, so every `.oq`
file carries exactly one expectation. There are no `.in` stdin fixtures. The stray `1 txt`
is `conformance/readme-fixture.txt`, a fixture read by a conformance case rather than a
case itself.

## Traceability matrix

| Language requirement | Specification | Implementation | Tests | Status |
| --- | --- | --- | --- | --- |
| Keywords are a closed set of 20 | `docs/stdlib/reference.md:8-11` (delegated, not in the spec) | `oq-core/src/main/java/dev/oqthara/core/Lexer.java:13-18` | `LexerTest.java:34-43` | PARTIALLY VERIFIED |
| Operator precedence | absent from the spec | `Parser.java:313-441` (10-level chain) | `conformance/006-precedence.oq` | PARTIALLY VERIFIED |
| Operator associativity | absent from the spec | `Parser.java:407-414` (`**` right-assoc) | `conformance/111-exponent-negative.oq` | PARTIALLY VERIFIED |
| `in` as a membership operator | absent from `docs/spec/language.md:98` | `Parser.java:376`, `Interpreter.java:375` | `conformance/055-list-contains.oq` | PARTIALLY VERIFIED |
| Compound assignment `+= -= *= /=` | absent from the spec entirely | `Lexer.java:23-32`, `Parser.java:124-141` | `LanguageFeatureTest.java:22-44` (unit only, no conformance case) | PARTIALLY VERIFIED |
| `write` command | absent from the spec entirely | `Parser.java:11` | `conformance/117-write.oq` | PARTIALLY VERIFIED |
| Block scoping | not stated as a rule (`language.md:42`, `:123-124` discuss only loop variables) | `Env.java:88-95`, `Interpreter.java:167-275` | `conformance/088-block-scope.oq`, `.err` | PARTIALLY VERIFIED |
| Loop variable shadows, loop body is own scope | `docs/spec/language.md:123-124` | `Interpreter.java:192`, `Env.java:32` | `conformance/190-loop-variable-scope.oq` | VERIFIED |
| `end` closes blocks, newline terminates statements | stated only by example (`docs/spec/language.md:86-102`) | `Parser.java:301-310` (`blockInner`) | whole conformance corpus | PARTIALLY VERIFIED |
| Formal grammar | absent (no `*.ebnf`, `*.bnf`, `*.g4`, or `::=` anywhere) | prose parser | — | MISSING |
| Version identity | `docs/spec/language.md:3` says `0.1.0-alpha.1` | `oq-cli/.../cli/Main.java:27`, `build.gradle.kts:8` say `0.1.0-alpha.2` | `scripts/check-docs-sync.py` does not compare the spec header | INCONSISTENT |
| Error messages carry a source position | `docs/adr/0005-errors.md:19-38` | `oq-core/.../core/Diagnostics.java`, spans throughout | `ErrorMessageContractTest.java:95-96` | VERIFIED |
| Error cases fail with non-zero exit | `docs/spec/language.md:247-276` | interpreter error path | `ConformanceTest.java:42-53` | PARTIALLY VERIFIED |
| Bytecode backend covers a documented subset | `docs/spec/backend.md:92-95` (182 of 197) | `BytecodeCompilerTest.java:1037` asserts the count; `BytecodeCompiler.java:139` is the entry point | `BytecodeCompilerTest.java:1037` | VERIFIED |
| Bytecode backend refuses what it cannot compile | `docs/spec/backend.md:99-122` | `oq-compiler/.../compiler/Lowering.java`, `Ir.Refuse` | `BytecodeCompilerTest.java:531,576,582,745` | VERIFIED |
| Nesting and recursion limits | not in the spec | `Parser.java:19,21,28`, `Interpreter.java:35,37,417` | `NestingLimitTest.java:40-48` | PARTIALLY VERIFIED |
| Java interop through `use` | `docs/spec/language.md:474` | `JavaBridge.java:46-128`, `Interpreter.java:280` | `InterpreterTest.java:123` | VERIFIED |
| Host I/O — files | `docs/spec/language.md:281-282` | `Stdlib.java:322-407` | `StdlibCoverageTest.java:87` | VERIFIED |
| Host I/O — HTTP | `docs/spec/language.md:289-290` | `Stdlib.java:438-473` | none (no test references either function) | MISSING |
| Host I/O — process and env | `docs/spec/language.md:292-293` | `Stdlib.java:525-551` | `StdlibCoverageTest.java:172` (env only; `run_command` untested) | PARTIALLY VERIFIED |
| Security posture of arbitrary programs | `SECURITY.md:25-27` | `JavaBridge` has no allow-list | no test asserts a restriction | MISSING |
| Fuzzing contract | `docs/spec/fuzzing.md:3-20` | 8 targets under `*/src/fuzz/**` | nightly workflow only | PARTIALLY VERIFIED |
| LSP diagnostics and formatting | `docs/spec/language.md:412` | `oq-lsp/Server.java:173` | `oq-lsp/src/test/java/dev/oqthara/lsp/ServerTest.java:36-37` | VERIFIED |
| `oq format` alias | not in `docs/reference/cli.md` | `oq-cli/.../cli/Main.java:66` | none (the alias itself is untested) | MISSING |

Row totals: 24 requirements — 7 `VERIFIED`, 12 `PARTIALLY VERIFIED`, 1 `INCONSISTENT`,
4 `MISSING`, 0 `NOT INSPECTED`. The pattern is consistent: the implementation is real and
mostly tested, but the written contract is frequently absent or weaker than the code. Only
one requirement is a direct contradiction (version identity); four have no contract or no
test at all; twelve are implemented with a gap somewhere in the contract-test chain.

## Findings

### F-01 — The specification has no grammar and no precedence rules

**Severity: HIGH.** There is no formal grammar anywhere in the repository, and
`docs/spec/language.md` contains zero occurrences of "precedence" or "associativity"
(`grep -c` returns 0). The parser implements a real ten-level precedence chain
(`Parser.java:313-441`), so the behaviour is fully determined but entirely undocumented.
A new contributor cannot know whether `2 ** 3 ** 2` is 64 or 512 without running it, and
a conformance suite that changes would silently change a contract nobody wrote down.

This is precisely the case `docs/README.md:11` calls a specification bug.

### F-02 — Four language features exist in code but have no normative contract

**Severity: HIGH.** `in` as an expression operator, the `write` command, and the `format`
CLI alias are implemented and reachable. None is described in the normative specification.

Compound assignment (`+=`, `-=`, `*=`, `/=`) is implemented (`Lexer.java:23-32`,
`Parser.java:124-141`) and is covered by `LanguageFeatureTest.compoundAssignment`
(`oq-cli/src/test/java/dev/oqthara/cli/LanguageFeatureTest.java:22-44`), which asserts the
result of all four operators. It is undocumented in the spec and absent from the
conformance corpus, so the behaviour is tested by a unit test the specification does not
authorise.

Two distinct gaps, then. `in`, `write` and `format` have no entry in the normative
specification, though all three are named in secondary documents: `in` and `write` appear
in `docs/spec/backend.md:116,118` as rows of the bytecode backend's supported-feature
table, and both are listed in `docs/stdlib/reference.md:9,100`, which is generated from the
implementation and so reflects the code rather than constraining it. `oq fmt` is
documented in `docs/reference/cli.md:18` under its canonical name; only the `format` alias
is undocumented. Compound assignment has a test but no contract anywhere, including the
secondary documents.

### F-03 — The specification version lags the shipped version

**Severity: MEDIUM.** `docs/spec/language.md:3` says `0.1.0-alpha.1`; `oq-cli/.../cli/Main.java:27` and
`build.gradle.kts:8` both say `0.1.0-alpha.2`. `scripts/check-docs-sync.py:187-219`
compares `Main.java`, `build.gradle.kts` and `CHANGELOG.md` but not the spec header, which
is why this drift survived a green CI run. The checker's blind spot is the bug.

### F-04 — Error-message assertions match by substring

**Severity: MEDIUM.** `ConformanceTest.java:48-53` asserts
`actual.stderr().contains(expected)`. A regression that appends noise to an error message,
or that reports a different but overlapping message, still passes.
`conformance/200-error-message-includes-position.err` contains only
`'missing_variable' is not defined`, with no position fragment, despite its name promising
one. `ErrorMessageContractTest` does assert position and exit code, so the contract is
covered elsewhere, just not by the conformance corpus.

### F-05 — The step budget is armed, but three unbounded paths remain

**Severity: MEDIUM.** `Interpreter.java:35` initialises `maxSteps = 50_000_000` inline, so
the guard is live on every interpreter instance without configuration. `tick()` runs per
statement (`Interpreter.java:151`), per loop iteration (`:178`, `:191`, `:209`) and per
expression (`:311`). An infinite `while true` loop therefore terminates with
`program ran for too long` rather than hanging. The unused `setMaxSteps` setter
(`Interpreter.java:62`) is a dead tuning knob, not a missing guard.

Three paths the budget does not cover:

- `print` writes to the stream with no size cap (`Stdlib.java:53-61`).
- `read` loads a whole file into memory (`Stdlib.java:326`).
- `run_command` captures all child output with no timeout (`Stdlib.java:528-546`).

A hostile `.oq` program can therefore exhaust memory or block on a child process, but it
cannot spin forever in interpreter bytecode. `SECURITY.md:25-27` states the posture
plainly: running an untrusted `.oq` file is equivalent to running an untrusted jar.

### F-06 — HTTP and process builtins have no automated test

**Severity: HIGH.** The two HTTP builtins and `run_command` are reachable from any `.oq`
program and nothing exercises them:

- `installHttp` defines `http_get` and `http_post` (`Stdlib.java:438-473`). No test exercises
  either one. The only mention in any test or fuzz source is a comment in
  `StdlibFuzzTest.java:27-28` listing them among the side-effecting builtins deliberately
  excluded from fuzzing. There is no unit test and no conformance case.
- `run_command` (`Stdlib.java:528-546`) is likewise untested. `StdlibCoverageTest.processFunctions`
  (`:171-176`) asserts only `cpu_count`, `memory_mb` and `env`; `run_command` appears only in
  the same fuzz-exclusion comment.

JaCoCo agrees: `Stdlib.java:447-470` and `:530-546` show `ci=0` in the aggregate report.
These functions launch child processes and make outbound network calls, so an unnoticed
regression here is both a correctness and a security exposure. The matrix previously
reported this whole area as `VERIFIED`; it is now split into three rows and the HTTP row
is `MISSING`.

### F-07 — The documentation checker tolerates a command that does not exist

**Severity: LOW.** `scripts/check-docs-sync.py:112` accepts `serve` in its command
whitelist, but `Main.dispatch` (`oq-cli/.../cli/Main.java:55-77`) never implements `serve`. The check is
therefore slightly wider than the reality it guards.

### F-08 — Two agent architectures now coexist

**Severity: LOW.** `.kilo/agents/` describes one orchestrator and eleven specialists in a
frontmatter dialect that the runtime used here does not load. `.github/agents/` now
describes six agents in a dialect it does load. Both describe the same intent. Recorded as
[OQ-DEC-2026-001](../README.md#oq-dec-2026-001--agent-architecture-uses-the-native-custom-agent-format).

### F-09 — Per-agent model pinning is not achievable today

**Severity: LOW.** `copilot --model` rejects the session's own DeepSeek identifier, and
the custom-agent `model` field only works for resolvable models. Every Hive agent
therefore inherits the session model. Recorded as
[OQ-DEC-2026-002](../README.md#oq-dec-2026-002--per-agent-model-pinning-is-not-configured).

## What this audit did not cover

- **Runtime semantics beyond the conformance corpus.** The 209 programs are broad but they
  are the project's own definition of correct.
- **Performance.** `bench-jmh` modules do run in `build` (`:bench-jmh:test` and
  `:bench-jmh:check` are in the graph, confirmed with `./gradlew build --dry-run`), but that
  only runs its unit tests. The benchmark itself is the `jmh` JavaExec task
  (`bench-jmh/build.gradle.kts:28`), which nothing invokes, so no benchmark was run.
  `CHANGELOG.md:135` states the module is unwired; that is inaccurate and this audit had
  originally repeated it.
- **The website.** It lives in a separate repository (`docs/adr/0011`).
- **Actual fuzz execution.** The targets exist; a 60-second nightly run was not reproduced.
- **Whether CircleCI is enabled on the repository.** That is a hosting setting, not a file.
- **A pass/fail claim for the 182-of-197 backend figure.** The count is asserted by
  `BytecodeCompilerTest.java:1037`, and `oq-cli:test` executed successfully in the
  `--rerun-tasks` run above, so the figure is corroborated by a passing assertion rather
  than independently re-derived.

## Next engineering task

The audit's strongest finding is F-01 and F-02 together: the language's operator
semantics are real, tested, and undocumented, and four features have no normative
contract.

Recommended single task, scoped to fit one change:

> Add an operator precedence and associativity section to `docs/spec/language.md`,
> documenting the ten levels the parser actually implements, plus conformance coverage
> for compound assignment.

That is small, it is verifiable by `python3 scripts/check-docs-sync.py` and a new
conformance case, it fixes the highest-severity inconsistency, and it adds no keyword.
Fixing F-03 belongs in the same change because the spec header is being edited anyway.
