# 7.4 Unified model and cross-backend contract

§7.1 models what a program means, §7.2 models `oq build`, and §7.3 models a running program. This
document is the contract between them: which of those models a *compiled* program actually
satisfies, and where the two execution paths are allowed to differ.

It exists because they do differ, in more places than the specification used to admit. Every
divergence below was measured by building a program and running the jar beside it, not inferred
from source. The prefixes are `U` for contract clauses and `D` for the divergences they govern.
No prefix used by §7.1–§7.3 is reused.

## 7.4.0 The two execution paths

There are two ways to run a program, and they are not the same machine.

| Path | Entry | What runs the program |
|---|---|---|
| Interpreted | `oq run file.oq` | `Interpreter` over the AST, with the full standard library and all budgets |
| Compiled | `oq build file.oq` then `java -jar` | generated class files over the IR, with a partial runtime |
| Fallback | `oq build` on an unsupported construct | a launcher jar that embeds the source and *re-interprets* it |

The third row is the one that makes the contract subtle. A build that cannot compile a construct
does not fail. It prints a `codepath:` line to standard output, writes a jar, and exits 0. That
jar contains the original source embedded as a resource and runs it through the interpreter at
startup, so a program that looks compiled may be interpreted. Measured with `aband.oq`, which is
`use java.time.LocalDate` followed by `print "main done"`:

```
$ oq build aband.oq
compiled aband.oq (0 functions, 6 main instructions)
backend=classfile (self-contained writer, JDK 21)
codepath: interpreter launcher ('use' imports a Java class, which is not compiled yet)
wrote aband.jar (337 classes, 0 skipped)
run it with: java -jar aband.jar
$ java -jar aband.jar
main done
```

The jar's manifest records which path was taken: `Oqthara-Backend: classfile-launcher`. A
consumer that needs to know whether a jar is genuine bytecode must read that header, not assume
that a successful build means compiled code.

## 7.4.1 Contract clauses

| ID | Clause | Verified by |
|---|---|---|
| `U1` | Both paths produce the same values and the same standard output for the same program. | `scripts/verify-conformance.py`, `BytecodeCompilerTest` |
| `U2` | Both paths report the same error message, the same hint and the same exit status. | `BuildJarTest.aCompiledJarStillReportsAProgramErrorWithExitCode1` |
| `U3` | Neither path reports an Oqthara defect for a program's own failure, and both report one for Oqthara's own failures, with status 70. | `BuildJarTest.anInternalErrorIsClassifiedAsExitCode70` |
| `U4` | Only the interpreted path can print the source line and caret that locate a failure. A compiled jar carries no program text, so its diagnostics name the position but do not quote the line. | measured; §7.4.2 |
| `U5` | A construct the compiler cannot emit makes the *build* fall back, not fail. The resulting jar is an interpreter launcher and must be identified as one. | `Oqthara-Backend` manifest header |
| `U6` | The step budget bounds only the interpreted path. A compiled jar has no step ceiling and runs until it finishes, exhausts memory, or is killed. | measured; §7.4.3 |
| `U7` | The recursion depth ceiling bounds only the interpreted path. A compiled jar recurses until the JVM raises `StackOverflowError`, which it then reports as a program failure. | `BuildJarTest.aCompiledJarReportsRunningOutOfMemoryAsAProgramError`, §7.4.3 |
| `U8` | The output and waiting budgets apply to both paths. | measured; §7.4.3 |
| `U9` | A task's unobserved failure does not fail the program, on either path. | measured; §7.4.4 |

`U1` and `U2` together are what "backend equivalence" means, and they are weaker than §7.1's `L12`
claimed before this document existed. `L12` said the backends produce *identical* diagnostics.
They do not: they agree on message, hint and status, and differ on whether the offending source
line is quoted. `L12` now says exactly that and delegates the detail to `U4`.

## 7.4.2 Divergence `D1` — the source excerpt

Measured on a one-line program, `print 1/0`:

```
$ oq run div.oq
error: division by zero
  --> div.oq:1:8
   1 | print 1/0
              ^

  help: Check the value you are dividing by.

$ java -jar div.jar
error: division by zero
  --> div.oq:1:8

  help: Check the value you are dividing by.
```

The jar omits two lines: the quoted source and the caret. `EmbeddedMain.renderUncaught` passes
`source` as `null` because a class-file build does not embed the program text — `JarBuilder`
embeds source only for a launcher jar, which is the one case where the text is needed to run at
all.

This is a deliberate consequence of not shipping source in a binary artifact, and it is the right
trade: a jar that embedded its own source would leak the program to anyone holding the jar, and
would double its size. It is a divergence all the same, and a tool that diffs `oq run` output
against jar output has to know about it or it will report a false regression. `check-links.py` and
`verify-conformance.py` compare the interpreted path only, so neither is affected.

## 7.4.3 Divergence `D2` — the budgets that are not shared

`M10` in §7.3 originally claimed all three budgets were "shared by both backends". The output and
waiting budgets are. The step budget and the depth ceiling are not, and the difference is large
enough to change what a hostile program can do to a machine.

```
$ oq run spin.oq
error: program ran for too long
  --> spin.oq:2:5

$ java -jar spin.jar        # killed after 30 s, still looping
```

A compiled program containing `while true` runs until something outside it stops it. There is no
step ceiling, no depth ceiling, and no way for the jar to bound its own work. The comment in
`EmbeddedMain.renderFailure` states the position plainly: *"A compiled jar has no step budget, so
runaway allocation is only stopped by the heap."*

Both budgets exist to make an untrusted program safe to run. A compiled jar therefore must not be
used to run untrusted code, and every other guarantee in this specification about bounded
execution applies to `oq run` only. `M10` now says this instead of claiming a symmetry that was
never implemented.

The output and waiting budgets *are* shared, because they live in static state in the runtime
module that both paths link against. Measured: a compiled `while true` that prints is stopped at
16 777 216 bytes with the same hint the interpreter gives.

## 7.4.4 Divergence `D3` — failure that nobody observes

A task that fails and is never awaited does not fail the program, and its error is discarded:

```
$ oq run aband.oq
main done
$ echo $?
0
```

This is `M7` in §7.3 — an unawaited task neither delays nor affects exit — applied consistently.
It is worth stating as a contract clause because the alternative reading is tempting and wrong:
the failure does not surface at exit, is not printed by default, and does not change the status.
Only an `await` delivers it (`M5`, `M6`). A program that spawns work and ignores the handle has no
way to learn that the work failed, which is the same gap `D4` names from the other side.

## 7.4.5 Divergence `D4` — no cancellation and no join

Recorded in §7.3 as an open defect; restated here because it is a *contract* difference between
what a concurrent language usually promises and what this one does. A task cannot be cancelled,
cannot be waited on except one at a time, and is abandoned at exit. A three-second task spawned
and not awaited is killed in about 0.4 s with no diagnostic.

## 7.4.6 What keeps the paths together

The mechanisms below are why `U1`–`U3` hold, and each one has a test:

| Mechanism | Keeps | Test |
|---|---|---|
| One AST, one IR, two emitters | values and stdout agree by construction | `scripts/verify-conformance.py` over 223 programs |
| `Details.render` shared by both entry points | message and hint wording cannot drift | `ErrorMessageContractTest` |
| `exitCodeFor` shared, not duplicated | status cannot drift | `BuildJarTest.anInternalErrorIsClassifiedAsExitCode70`, `.aCompiledJarStillReportsAProgramErrorWithExitCode1` |
| `renderFailure` checked before the standard renderer | Oqthara's own failures are never blamed on the program | `BuildJarTest.aCompiledJarReportsRunningOutOfMemoryAsAProgramError` |
| Static budgets in `oq-runtime` | output and waiting limits hold on both paths | `HostBuiltinLimitsTest`, `WaitedBudgetTest` |
| `Oqthara-Backend` in the manifest | a fallback jar is identifiable | `BuildJarTest` |

The conformance suite is the load-bearing one. 223 programs, of which 207 are executable, are run
through both paths and compared; 192 are genuinely compiled and 19 fall back, and the fallback
count is what keeps `U5` honest. A rise in the fallback count is a regression in the compiler even
though every test still passes, which is why `check-docs-sync.py` pins the 192/207 figure.

## 7.4.7 Recovery and diagnostics across the boundary

A failure crosses the interpreter/host boundary in one direction only: a Java exception becomes an
`OqError` at the boundary (`E31`), and an `OqError` reaches a program's `try` as a value. Nothing
crosses the other way — `try` cannot catch a host failure that is not an `OqError`, which is why
`M11` distinguishes exit 1 from exit 70 rather than treating every failure alike.

Measured consequences worth knowing before writing code that spans the boundary:

- A JDK refusal inside a host builtin is an `OqError` and is catchable: `[1,2].reduce(0, f)`
  reports `'reduce' needs a function as argument 1`, not an internal error.
- A refusal from a JDK method reached through `use` is likewise converted, not leaked.
- `StackOverflowError` and `OutOfMemoryError` are claimed by `renderFailure` as program failures
  and exit 1. This is not an inconsistency with `M11`; it is `M12`, and it is deliberate, because
  both are things a program can cause and neither is an Oqthara defect.
- A `RuntimeException` that is not an `OqError` and escapes from Oqthara itself exits 70 with
  `This is a bug in Oqthara. Please report it.` No program input reaches this path on purpose.

## 7.4.8 Observation points

| What to observe | Interpreted path | Compiled path |
|---|---|---|
| The value of the program | stdout | stdout |
| Which backend ran | the absence of a jar | `Oqthara-Backend` in the manifest |
| Whether the compiler gave up | — | the `codepath:` line on standard output |
| Resource exhaustion | step, output, waiting and depth diagnostics | output and waiting diagnostics, or the JVM dying |
| Oqthara's own failure | exit 70 | exit 70 |
| The source line of a failure | quoted with a caret | absent (`U4`) |

## 7.4.9 Test mapping

| Clause | Tests |
|---|---|
| `U1` | `scripts/verify-conformance.py`; `BytecodeCompilerTest` |
| `U2`, `U3` | `BuildJarTest.anInternalErrorIsClassifiedAsExitCode70`, `.aCompiledJarStillReportsAProgramErrorWithExitCode1`, `.aCompiledJarReportsRunningOutOfMemoryAsAProgramError` |
| `U4` | no test — measured, documented difference |
| `U5` | no test — `BuildJarTest.jarContainsManifestProgramAndSource` asserts a manifest is written, but nothing asserts the `Oqthara-Backend` value or that a fallback build exits 0 |
| `U6`, `U7` | no test — a compiled jar has no ceiling to test; `U7` is exercised only by the JVM |
| `U8` | `HostBuiltinLimitsTest.exactlySixteenMebibytesIsAllowed`, `.oneBytePastSixteenMebibytesIsRefused`, `.aPrintThatCrossesTheCeilingWritesNothing`; `WaitedBudgetTest.theLimitIsOnTheTotalNotOneCall` |
| `U9` | `ConcurrencyTest.spawnReturnsAValue`, `.taskErrorPropagatesToAwait` |

The two untested clauses are untested because the thing that would be tested is absent. A test
that a compiled jar has no step limit can only assert that the program does not stop, which is
either a hang or a timeout. `U6` and `U7` are recorded as limits, not as guarantees, and the
correct posture toward them is the one this document takes: say so, and do not run untrusted code
as a compiled jar.

## 7.4.10 Open defects this contract exposed

**A build that falls back still exits 0.** `U5` says the fallback is honest — it prints a
`codepath:` line and labels the manifest — but a build script that reads only the exit status
cannot tell a compiled artifact from an interpreted one. `oq build --strict`, or a non-zero exit
under a flag, is the obvious repair and is not implemented.

**Nothing in the language can express the step ceiling.** A program cannot discover that it is
being budgeted, cannot raise its own ceiling, and cannot ask how many steps it has used. That is
defensible for an untrusted-code sandbox and limiting for a batch job, and the two cannot be
served by one rule.

**A missing map key is `nothing` rather than an error.** Measured: `{"a": 1}["zz"]` prints
`nothing` and exits 0, on both paths. The `language.md` reference lists `.get(k, fallback)` but
does not state what indexing by an absent key does, and the list path *does* raise for an
out-of-range index — so the two container types disagree about whether an absent element is a
failure. This is either a deliberate asymmetry that needs writing down or an inconsistency that
needs fixing; it is currently neither, because it is undocumented.
