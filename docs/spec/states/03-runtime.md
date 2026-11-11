# 7.3 Runtime state machine

The model of the machinery that executes a program: values, environments, tasks, the host
boundary, resource budgets and process shutdown. §7.1 models what a program *means*; this
document models the objects that give that meaning a lifetime.

The prefix is `R` for states, `E` for transitions and `M` for invariants. §7.1 uses `S`/`T`/`L`
and §7.2 uses `S`/`C`/`K`; no prefix is reused for a different kind of identifier.

Every diagnostic quoted here was measured against a running build. That process corrected four
claims this document originally made from reading source, and found three defects that are
recorded in **Open defects** rather than smoothed over.

## 7.3.0 States

| State | Meaning | Owner |
|---|---|---|
| `R0 Cold` | The process is up; no interpreter, no budgets. | `Main` |
| `R1 Ready` | An `Interpreter` exists, standard library installed, budgets at zero. | `Interpreter.run` |
| `R2 Evaluate` | A value is being produced. | `Interpreter.evaluate` |
| `R3 Bind` | A name is being created or updated in an `Env`. | `Env` |
| `R4 Invoke` | A host builtin or user function body is running. | `Interpreter.call` |
| `R5 Iterate` | A collection is being walked through a snapshot. | `Values.iterable` |
| `R6 Spawn` | A task exists on its own virtual thread. | `OqTask` |
| `R7 Await` | The calling thread is blocked on a task. | `OqTask.await` |
| `R8 Interop` | Control is inside a Java method reached through `use`. | `JavaBridge` |
| `R9 HostIo` | A host builtin is waiting on a file, socket or child process. | `Stdlib` |
| `R10 Shutdown` | The program has returned and budgets are being discarded. | `EmbeddedMain` / `Runtime` |
| `RX Failed` | A failure is being rendered and an exit status chosen. | `EmbeddedMain.renderFailure` |

`R5`, `R6`, `R7` and `R8` are *leaf* states: each is entered from `R2` or `R4` and returns a
value. `R6` is the only one that outlives the frame that created it — an unawaited task may still
be running when `R10` is entered, and the process does not wait for it.

## 7.3.1 Transitions

### Value and environment lifecycle

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `E1` | `R0` | `oq run` parses a program | source parses | build `Interpreter`, `Stdlib.install` | `R1` |
| `E2` | `R1` | `run(program)` | — | `resetSteps()`: steps, output bytes and waited millis all zero | `R2` |
| `E3` | `R2` | a literal, or arithmetic on two numbers | operands share a numeric type, or one is `decimal` | produce the value | `R4`-return |
| `E4` | `R2` | `+` on two texts | combined length under the text ceiling | concatenate | `R4`-return |
| `E5` | `R2` | a list, map or set literal | — | fresh, mutable collection | `R4`-return |
| `E6` | `R2` | a tuple literal | — | immutable `OqTuple` | `R4`-return |
| `E7` | `R2` | `x = v` | `x` is not a `const` and not a loop variable | rebind in the innermost scope that owns `x` | `R3` |
| `E8` | `R2` | `x = v` where `x` is not visible | — | declare `x` in the current scope | `R3` |
| `E9` | `R3` | a name is declared twice in one scope | — | raise `'x' is already declared in this scope` | `RX` |
| `E10` | `R2` | `const x = v` | `x` is not visible in this scope | declare a fixed binding | `R3` |
| `E11` | `R2` | assignment to a `const` | — | raise `cannot change 'x'` | `RX` |

Value identity is Java object identity for every collection; only the scalar types and tuples are
immutable. `E7` therefore rebinds a *reference*, so one list stored in two places observes one
mutation through both. Measured: storing a list in a map and in a tuple, then calling `add` on the
map's copy, is visible through the tuple — `([1, 9], 2)`.

### Collection and iteration lifecycle

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `E12` | `R2` | `for x in c` begins | `c` is iterable | take a **snapshot** of `c` | `R5` |
| `E13` | `R5` | the body mutates `c` | — | the snapshot is unaffected; the loop runs its original length | `R5` |
| `E14` | `R5` | the snapshot is exhausted | — | discard the snapshot | `R4`-return |
| `E15` | `R2` | a callback builtin (`map`, `filter`, `each`, `any`, `all`, `find`, `find_index`, `reduce`, `flatten`) runs | — | snapshot the receiver before invoking the callback | `R4` |
| `E16` | `R2` | `zip`, `random_item` or `par_map` receives a set | — | `Values.iterableList` converts it | `R4`-return |
| `E17` | `R2` | index outside a list | — | raise `index 5 is out of range for a list of length 2` | `RX` |

`E12` is what makes mutation during iteration well defined. Before it, a `for` over a list that
the body appended to reached `java.util`'s fail-fast check and died on exit 70 with a message no
`try` could catch. The snapshot is taken once per loop; ranges and text were already
snapshot-equivalent, which is why only the collection path was ever wrong.

### Task lifecycle

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `E18` | `R2` | `spawn(f)` | `f` is callable | new virtual thread, `CompletableFuture` | `R6` |
| `E19` | `R6` | the body returns a value | — | the future completes normally | `R6` |
| `E20` | `R6` | the body raises `OqError` | — | the future completes exceptionally with that error | `R6` |
| `E21` | `R7` | `await(t)` on a completed task | — | return the value, or re-raise the error **in the caller** | `R4`-return |
| `E22` | `R7` | the awaiting thread is interrupted | — | set the interrupt flag, raise `interrupted while waiting for a task` | `RX` |
| `E23` | `R2` | `done(t)` | `t` is a task | return its completion flag | `R4`-return |
| `E24` | `R2` | `await(v)` where `v` is not a task | — | raise `await() needs a task from spawn(), got number` | `RX` |
| `E25` | `R7` | `await(t)` is called a second time | the future still holds its result | return the same value again | `R7` |
| `E26` | `R10` | the program returns with tasks still running | — | abandon them; the process exits without joining | `R10` |

`E21` is why `await` is a recovery point: a failed task's `OqError` surfaces at the `await` that
observes it. Measured, a task whose body divides by zero is caught by a `try` around `await(t)`
and reports `division by zero`. Awaiting the same task twice returns the same value both times,
so a task is a memoised result, not a consumable.

`E26` is deliberate and is the runtime's sharpest edge. Measured: a program that spawns a task
sleeping three seconds, prints `main done` and returns exits 0 in about 0.4 s, and the task's
output never appears. Measured again at scale: 50 such tasks, same result. There is no `join` and
no shutdown hook that drains tasks.

### Host boundary

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `E27` | `R2` | `use java.pkg.Class` | the class loads | register it for construction and static calls | `R4` |
| `E28` | `R4` | a Java method returns `int`, `short`, `byte`, `long`, `float`, `char`, or a `BigInteger` that fits a `long` | — | `JavaBridge.normalize` widens to `number` or `text` | `R4`-return |
| `E29` | `R4` | a Java method returns `void` | — | `null`, which is `nothing` | `R4`-return |
| `E30` | `R4` | a Java method returns an unmapped type | — | the object is used as-is and reports `java <SimpleName>` | `R4`-return |
| `E31` | `R8` | the JDK throws `UnsupportedOperationException` | — | convert to an `OqError` naming the refusal | `RX` |
| `E32` | `R8` | any other Java exception escapes | — | propagate as a `RuntimeException`, which is an internal error, not an `OqError` | `RX` |

`E30` is subtler than it looks, and the first draft of this document got it wrong. `normalize`
does **not** wrap foreign objects into Oq types; it only widens boxed numerics and `char`. A
`java.util.List` reports `list` because `Values.typeName` tests `instanceof List`, not because
anything converted it. The same holds for `Map`, `Set` and `String`. So:

```
ArrayList()          -> list        (it implements java.util.List)
String("hi")         -> text        (java.lang.String is the Oq text type)
LocalDate.now()      -> java LocalDate
```

There is no conversion layer to inspect because there is no conversion: the JDK class hierarchy is
doing the work. That is convenient, and it is exactly why `E31` matters — a JDK collection that
refuses mutation raises a Java exception where the language promised a mutable `list`.

`E28` carries a deliberate exception to that. `BigDecimal`, and a `BigInteger` too wide for a
`long`, stay as Java objects rather than narrowing to a `double`, because narrowing either one
discards the exactness that is the only reason to choose it. Writing
`BigDecimal("0.1").add(BigDecimal("0.2"))` and getting a `decimal` back would report 16 significant
digits as if they were the whole answer. Such a value therefore does not take part in Oq
arithmetic: `x + 1` on a `BigDecimal` is `cannot add java BigDecimal and number`, and a program
that wants to mix them converts explicitly. [ADR-0045](../../adr/0045-exact-java-numbers.md) records
the measurements and the decision.

### Resource budgets

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `E33` | `R2` | an evaluated step | `steps` at 50 000 000 | raise `program ran for too long` | `RX` |
| `E34` | `R9` | `print` | output bytes at 16 MiB | charge before writing, so a refused call writes nothing | `RX` |
| `E35` | `R9` | `sleep(n)` | `n` over 10 seconds in one call | raise `cannot sleep for more than 10 seconds` | `RX` |
| `E36` | `R9` | cumulative waiting | 60 000 ms across the program | raise `the program waited for too long` | `RX` |
| `E37` | `R9` | `read` a file | file over the text ceiling | raise a too-large error, not a step error | `RX` |
| `E38` | `R9` | `run_command` | 30 s wall clock for output **and** exit together | `destroyTree`, raise `command timed out after 30 seconds` | `RX` |
| `E39` | `R9` | `run_command` output | over `MAX_TEXT_BYTES` | `destroyTree`, raise `command output is too large` | `RX` |
| `E40` | `R4` | a user call | depth at 800 | raise `call stack is too deep` | `RX` |
| `E41` | `R4` | the JVM stack overflows anyway | — | `StackOverflowError` rendered as `the program recursed too deeply` | `RX` |
| `E42` | `R2` | allocation exhausts the heap | — | `OutOfMemoryError` rendered as `the program ran out of memory` | `RX` |

`sleep` takes **seconds**, not milliseconds. Measured: `sleep(9)` succeeds and `sleep(25000)` is
refused with `cannot sleep for more than 10 seconds`, so a call of 9000 is not nine seconds — it
is 2.5 hours and outside the per-call cap. Measured on the total: six `sleep(9)` calls take 54 s
and succeed; the seventh is refused with `the program waited for too long`.

`E33`, `E34` and `E36` are three *separate* counters reset together by one call. Output has its
own ceiling because charging it as steps made the documented 16 MiB unreachable — the step budget
tripped first. Waiting has its own because billing a millisecond as one step let a single program
wait nearly fourteen hours. `E41` and `E42` are the only two failures a `try` cannot catch: they
are reported as program failures, but they are not `OqError`s.

### Shutdown and failure

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `E43` | `R2` | `main` returns | — | discard the interpreter | `R10` |
| `E44` | `RX` | the failure is an `OqError` | — | render message, position and hint; **exit 1** | process end |
| `E45` | `RX` | the failure is a `RuntimeException` that is not an `OqError` | — | render `internal error: ...`, `This is a bug in Oqthara`; **exit 70** | process end |
| `E46` | `RX` | the failure is `StackOverflowError` or `OutOfMemoryError` | — | render a program-level message; **exit 1** | process end |
| `E47` | `R2` | `exit(n)` | — | unwind without rendering | process end, status `n` |

`E45` versus `E46` reads like an inconsistency and is not one. `renderFailure` is consulted
*before* `exitCodeFor` and claims `StackOverflowError` and `OutOfMemoryError` as program failures,
so they never reach the internal-error branch or its status 70. Only a `RuntimeException` that is
neither an `OqError` nor one of those two exits 70. Measured: `exit(3)` prints nothing further and
the process status is 3.

The single slot in `Errors.current` — the source position a compiled helper reads instead of
carrying one — is documented as safe because compiled code is single-threaded. That holds for a
reason the comment does not give: `spawn` is not compiled. Measured, `oq build` on a program that
calls `spawn` prints `codepath: interpreter launcher (call to 'spawn' with 1 argument(s) is not
compiled yet)`, so a compiled program cannot run two threads that both mark that slot. If `spawn`
is ever compiled, this reasoning expires and the slot must become a `ThreadLocal`.

## 7.3.2 Invariants

| ID | Invariant |
|---|---|
| `M1` | Every value has exactly one `typeName`, and it never depends on how the value was produced. |
| `M2` | Scalars and tuples are immutable; lists, maps and sets are mutable and shared by reference. |
| `M3` | Rebinding a name never copies its value. Two names may refer to one collection. |
| `M4` | A `for` loop and every callback builtin walk a snapshot taken when they started. |
| `M5` | A task's value or error is delivered at an `await`, in the awaiting context, and again on every later `await`. |
| `M6` | A failed task's `OqError` is catchable; a failed task's non-`OqError` failure is not. |
| `M7` | An unawaited task does not delay or affect process exit. |
| `M8` | A Java value's Oq type is decided by `instanceof`, not by a conversion pass. |
| `M9` | A JDK refusal surfaces as an `OqError`, never as an internal error. |
| `M10` | The output and waiting budgets are separate, reset per program, and shared by both backends. The step budget and the recursion depth ceiling exist only in the interpreter; a compiled jar has neither (`U6`, `U7`). |
| `M11` | Only an `OqError` exits 1 with a source position; only a non-`OqError` `RuntimeException` exits 70. |
| `M12` | `StackOverflowError` and `OutOfMemoryError` are reported as program failures and exit 1. |
| `M13` | The step and output budgets are shared by every thread a program runs, but the recursion depth counter is not thread-safe: concurrent tasks corrupt it. |
| `M14` | Two values that `==` calls equal hash alike at every depth, so a set cannot hold both and a map finds a key whichever numeric representation wrote it (ADR-0017). |

## 7.3.3 Invalid transitions

| Attempt | Result |
|---|---|
| `const x = 1` then `x = 2` | `error: cannot change 'x'` |
| `x = 1` then `const x = 2` in one scope | `error: 'x' is already declared in this scope` |
| reassigning a `for` loop variable | `error: cannot change 'x'`, naming "the loop variable of this 'for'" |
| `for x in 5` | `error: cannot loop over number` |
| `xs.add(...)` inside `for x in xs` | allowed; the loop sees the snapshot (was exit 70) |
| `zip({1, 2}, {3, 4})` | allowed since `E16` (was `ClassCastException`, exit 70) |
| `[1, 2].reduce(0, f)` | `error: 'reduce' needs a function as argument 1` (was exit 70) |
| `await(1)` | `error: await() needs a task from spawn(), got number` |
| `t.is_done()` | `error: type task has no method 'is_done'` — completion is the builtin `done(t)` |
| `done(1)` | `error: done() needs a task from spawn(), got number` |
| `sleep(25000)` | `error: cannot sleep for more than 10 seconds` |
| seven `sleep(9)` calls | `error: the program waited for too long` after the seventh |
| `run_command("sleep 100")` | `error: cannot run command: Cannot run program "sleep 100"` — no shell, no arguments |
| `run_command("/tmp/slow.sh")` | `error: command timed out after 30 seconds: /tmp/slow.sh` |
| `read("/nope/nothing.txt")` | `error: cannot read file '/nope/nothing.txt'` |
| 128 nested parentheses | `error: expression is nested too deeply`, `The limit is 128 levels` |
| 129 nested `if` blocks | `error: blocks are nested too deeply` |
| 100 000 nested lists, then `text(x)` | `error: the program recursed too deeply` |

The last three are different limits in different components. Measured, 127 nested parentheses run
and 128 is refused, so the parser's limit is exactly 128. Block nesting has its own limit and its
own message. And the *renderer* overflows the JVM stack independently of either: a program can be
shallow enough to parse and still be too deep to print.

## 7.3.4 Where the states are observable

- `R1` → `E2`: the step, output and wait counters are process-wide statics in `OutputBudget` and
  `WaitedBudget`, so a second program in one JVM must call `resetSteps`. `Repl` does this per
  entry; `oq run` does it once.
- `R5`: the only externally visible sign that iteration is snapshot-based is that mutation during
  iteration no longer terminates the program. No API exposes the snapshot.
- `R6`/`R7`: `type_of(t)` reports `task`, `print t` reports `<task running>` or `<task done>`, and
  `done(t)` reports the flag. There is no task id, no cancellation and no join.
- `R8`: `type_of` is the only window. A value either reports an Oq type because the JDK class
  happens to implement `List`, `Map` or `Set`, or reports `java X` because it does not.
- `R10`: the exit status is the whole observable, and `E44`–`E47` is the complete mapping.

## 7.3.5 Test mapping

| Invariant | Test |
|---|---|
| `M1` | `ValuesTest.tupleIsItsOwnType`, `.tupleNeverEqualsAList` |
| `M2` | `ValuesTest.tupleIsImmutable`, `.aTupleOfOnlyNothingIsStillATuple`; `ImmutableRefusalTest.aTupleKeepsItsOwnWording` |
| `M3` | `ValuesTest.indexesLists`, `.indexesMaps` |
| `M4` | `ErrorMessageContractTest` iteration suite; `BytecodeCompilerTest.mutationDuringIterationMatchesTheInterpreter` |
| `M5`, `M6` | `ConcurrencyTest.taskErrorPropagatesToAwait`, `.parMapPropagatesErrors`, `.awaitRejectsNonTask` |
| `M7` | `ConcurrencyTest.spawnReturnsAValue`, `.manyTasksDoNotLeakStack` |
| `M8`, `M9` | `ImmutableRefusalTest.aJdkRefusalBecomesAProgramErrorNotABug`, `.aRefusalWithNoMessageStillReadsAsASentence` |
| `M10` | `StepBudgetTest.printLoopStopsAtTheOutputCeiling`, `.oversizedReadIsRefused`; `WaitedBudgetTest.theLimitIsOnTheTotalNotOneCall`; `HostBuiltinLimitsTest.exactlySixteenMebibytesIsAllowed`, `.oneBytePastSixteenMebibytesIsRefused` |
| `M11`, `M12` | `BuildJarTest.aCompiledJarReportsAnInternalErrorWithExitCode70`, `.aCompiledJarStillReportsAProgramErrorWithExitCode1`, `.aCompiledJarReportsRunningOutOfMemoryAsAProgramError` |
| `M13` | `ConcurrencyTest.twoTasksBothComplete`, `.parMapPreservesOrder`; the shared-counter half is exercised there, the depth half is not |
| `M14` | `ValueTest.equalNumbersHashAlike`, `.crossTypeLookup`, `.setCollapsesEqualValues`; `LanguageFeatureTest.equalNumbersHashAlike`, `.numericEqualityReachesInsideCollections` |
| `E38`, `E39` | `HostBuiltinLimitsTest.runCommandTimeoutKillsTheWholeProcessTree`, `.runCommandTimeoutsACommandThatNeverClosesItsOutput`, `.runCommandOutputIsBounded`, `.runCommandDoesNotGoThroughAShell` |
| `E35`, `E36` | `WaitedBudgetTest.waitingPastTheLimitIsRefused`, `.waitingUpToTheLimitIsAllowed`, `.theLimitIsOnTheTotalNotOneCall` |
| `E40` | `StepBudgetTest.unboundedRecursionStopsWithADiagnostic` |
| `E34` | `HostBuiltinLimitsTest.aPrintThatCrossesTheCeilingWritesNothing` |

## 7.3.6 Open defects in this model

**Closed: equality and hashing disagreed across the container boundary.** This was the defect
that motivated the Phase 2 exit gate's equality and hashability work. `Values.equal` widened
`number` against `decimal` only at the top level and then fell through to `Object.equals` for
collections, while sets and maps hashed raw `Long`/`Double`, which hash differently. Measured
before the fix:

```
print 1 == 1.0                   -> true
print [1] == [1.0]               -> false
print (1,) == (1.0,)             -> false
print {"a": 1} == {"a": 1.0}     -> false
print length(set([1, 1.0]))      -> 2
print 1 in set([1, 1.0])         -> true
print 1.0 in [1]                 -> true
```

A set claimed to hold two distinct elements while reporting membership for both equal numbers, and
a list said `1.0 in [1]` while `[1] == [1.0]` was false. [ADR-0017](../../adr/0017-equality-and-hashing.md)
fixed it by giving values an `equals`/`hashCode` contract that delegates to `Values.equal` and
hashes structurally, and by backing sets and maps with it. Measured after the fix, `[1] == [1.0]`,
`(1,) == (1.0,)` and `{"a": 1} == {"a": 1.0}` are all `true`, and `length(set([1, 1.0]))` is `1`.
The invariant `M14` in §7.3.2 states the rule.

Two bounded limitations remain and are accepted rather than open: a value used as a key that is
then mutated can no longer be found, and a self-referential collection raises rather than being
hashed. Both are recorded in ADR-0017.

**A collection reached through `use` may not be an Oq collection.** `M8` means a JDK type that
implements `List` is presented as a `list` without conversion. If that implementation is
fixed-size or unmodifiable, the first `add` raises a Java exception, which `E31` converts to an
`OqError` — correctly, but the type said `list` and the language documents lists as mutable.

**`spawn` allocates an executor per call and never closes it.** Each `spawn` builds a fresh
`newVirtualThreadPerTaskExecutor()`. Measured, 200 000 spawn/await pairs complete in 1.8 s with no
growth in per-call wall-clock cost, so this is not an observable leak today: an executor with no
live threads holds nothing. A `spawn` that gained cancellation would need a shared executor and a
real lifecycle.

**There is no cancellation and no join.** `E26` abandons tasks silently. A program cannot ask a
task to stop, cannot learn that it was abandoned, and cannot wait for all tasks. `M7` states this
as an invariant so that it is a decision rather than an omission — but it is a decision real
concurrent work will eventually want reversed, and reversing it is a language change, not a fix.

**The recursion depth counter is a plain `int` shared by every thread.** `Interpreter.depth` is
incremented and decremented around every block on whatever virtual thread is running, with no
synchronisation. `spawn` hands the *same* `Interpreter` to the task, so two concurrent tasks
interleave their increments and see a depth that belongs to neither of them. Measured, with two
tasks each looping thirty million times:

```
run 1: error: program ran for too long     (the shared step budget, correctly)
run 2: error: call stack is too deep       (a stack of depth 2 reported as overflow)
```

The same program reports a different failure on different runs, and the second message names a
source line that is not recursing at all. This is not cosmetic: `call stack is too deep` tells a
programmer to look for infinite recursion in a program that has none. The step counter next to it
is an `AtomicLong` and carries a comment explaining why it must be; `depth` did not get the same
treatment because the interpreter was single-threaded when it was written and `spawn` arrived
later. Making it per-thread is a small change, but it is a change to failure behaviour under
concurrency and belongs with the other task work rather than in a documentation branch.
