# 7.1 Language state machine

The model of what a running Oqthara program *means*. It is deliberately independent of how the
program is executed: the interpreter and the compiled backend are two realisations of this one
model, and §7.4 states the contract that keeps them the same. Where this document says "the
implementation", it names the file that owns the behaviour.

Every transition is labelled `T<n>`, every invariant `L<n>`, and every invariant maps to at
least one executable test in the table at the end. A state that cannot be entered is listed
under **Invalid transitions** with the diagnostic the implementation actually produces.

## States

Execution alternates between evaluating expressions and executing statements, with a stack of
environments threaded through both.

| State | Meaning | Owner |
|---|---|---|
| `S0 Idle` | No program is running. All budgets reset. | `EmbeddedMain` |
| `S1 Evaluate` | An expression is being reduced to a value. | `Interpreter.evaluate` |
| `S2 Bind` | A binding is created or updated in the current environment. | `Env` |
| `S3 Call` | A callable has been selected and its body is about to run. | `Interpreter.callFunction` |
| `S4 Block` | A statement list is executing in a child environment. | `Interpreter.executeBlock` |
| `S5 Raise` | An `OqError` is propagating. | `Interpreter` / `Errors` |
| `S6 Handle` | A `catch` clause is running with the error bound. | `Interpreter` `Try` case |
| `S7 Loop` | A `for`/`while` body is repeating. | `Interpreter` loop cases |
| `S8 Task` | A spawned task is running on its own virtual thread. | `OqTask` |
| `S9 Done` | The program produced a final value and the process exits. | `EmbeddedMain` |
| `SX Failed` | The program terminated on an uncaught error. | `EmbeddedMain.renderFailure` |

`S3`, `S4`, `S7` and `S8` are *nested*: each is entered from `S1` or `S4` and returns a value
back to the state that entered it. `S5` and `S6` are not nested in that sense — a raise unwinds
every environment between the raise site and the handler, which is what makes `catch` a
recovery point rather than an expression form.

## Transitions

### Expressions and statements

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `T1` | `S0` | program supplied | parsed and compiled-or-interpreted | reset budgets | `S1` |
| `T2` | `S1` | literal, name, or operator | operands reduce to values | apply the operation | `S1` caller |
| `T3` | `S1` | name lookup | name is bound | read `Binding.value` | `S1` caller |
| `T4` | `S1` | name lookup | name unbound | raise `'x' is not defined` | `S5` |
| `T5` | `S4` | statement is a declaration | — | `S2` | `S4` |
| `T6` | `S4` | statement list exhausted | — | pop environment | `S4` parent or `S9` |
| `T7` | `S1` | call expression | callee resolves and arity matches | `S3` | `S3` |

### Bindings

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `T8` | `S2` | `x = v` | `x` already local and mutable | replace `Binding.value` | `S2` done |
| `T9` | `S2` | `x = v` | `x` not local | define new mutable binding | `S2` done |
| `T10` | `S2` | `const x = v` | `x` not already in this scope | define with `mutable=false`, `fixedBy` set | `S2` done |
| `T10a` | `S2` | `const x = v`, `x = v` by declaration, or a `const a, b = v` target | `x` already in this scope | raise `'<name>' is already declared in this scope` | `S5` |
| `T11` | `S2` | assignment to a binding with `mutable=false` | — | raise `cannot change '<name>'` naming `fixedBy` | `S5` |
| `T12` | `S2` | loop variable or `catch` binding | — | `defineFixed` with its own `hint` | `S2` done |

### Calls

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `T13` | `S3` | body is a `Return` | — | unwind to caller with the value | `S1` caller |
| `T14` | `S3` | body falls off the end | — | value is `nothing` | `S1` caller |
| `T15` | `S3` | body recurses | block depth `< maxDepth` (800) | `S4` | `S4` |
| `T16` | `S3` | body recurses | block depth would exceed `maxDepth` | raise `call stack is too deep` | `S5` |
| `T17` | `S3` | call names a closure | — | captured environment is read at call time, not at creation | `S4` |
| `T18` | `S3` | overload selection | exactly one arity matches | bind that definition | `S3` |
| `T19` | `S3` | overload selection | no arity matches | raise, listing accepted arities | `S5` |

### Errors and recovery

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `T20` | any | operation raises `OqError` | a `try` is lexically enclosing | bind `err` record | `S6` |
| `T21` | any | operation raises `OqError` | no enclosing `try` | render diagnostic | `SX` |
| `T22` | `S6` | handler body completes | — | value is the handler's value | `S1` caller |
| `T23` | `S6` | handler raises | — | propagate outward | `S5` |
| `T24` | any | `RuntimeException` that is not `OqError` | — | render `internal error`, exit **70** | `SX` |

`T24` is the escape hatch this model exists to close. Every case in this document that reaches
it is a defect, not a state.

### Iteration

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `T25` | `S1` | `for x in e` | `e` is a list, set, map, range or text | snapshot via `Values.iterable` | `S7` |
| `T26` | `S1` | `for x in e` | `e` is anything else | raise `cannot loop over <type>` | `S5` |
| `T27` | `S7` | next item | items remain in the snapshot | bind loop variable, `S4` body | `S4` |
| `T28` | `S7` | snapshot exhausted | — | value is `nothing` | `S1` caller |
| `T29` | `S4` | `break` | inside a loop | discard the loop | `S1` caller |
| `T30` | `S4` | `continue` | inside a loop | next item | `S7` |
| `T31` | `S7` | body mutates the collection | — | snapshot is unaffected; the loop is well defined | `S7` |

`T31` is the decision recorded in ADR-0016. Before it, this transition reached `T24`.

### Tasks

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `T32` | `S1` | `spawn(f)` | — | start `S8` on a virtual thread, return a `task` | `S1` caller |
| `T33` | `S8` | body returns | — | task becomes done with that value | `S8` done |
| `T34` | `S8` | body raises `OqError` | — | task becomes done with that failure | `S8` failed |
| `T35` | `S1` | `await(t)` | task completed successfully | value | `S1` caller |
| `T36` | `S1` | `await(t)` | task failed with `OqError` | re-raise in the awaiting context | `S5` |
| `T37` | `S1` | `await(t)` | argument is not a task | raise `await` expects a task | `S5` |
| `T38` | `S1` | `await(t)` | the wait budget (60 s total) is exhausted | raise `the program waited for too long` | `S5` |

## Invariants

| ID | Invariant | Enforced by |
|---|---|---|
| `L1` | A program either produces a value or raises `OqError`; nothing else escapes as a value. | `S5`/`SX` are the only terminal error states |
| `L2` | Every `OqError` carries a message and a `Span`; a hint is optional but present for user errors. | `OqError` constructor, `ErrorMessageContractTest` |
| `L3` | A `catch` clause binds a real record with `message`, `hint`, `line`, `column`, `file`. | `OqError.toValue()` |
| `L4` | Only `OqError` is catchable. An internal error is never caught by user code. | `T20` guard |
| `L5` | A binding declared `const`, as a loop variable, or as a `catch` variable cannot be reassigned, and the refusal names why. A name cannot be declared twice in one scope, so a `const` cannot be defeated by redeclaring it, whether the second declaration is a plain `const` or a `const a, b = ...` target; a refused destructuring writes none of its targets. | `T10`, `T10a`, `T11`, `T12` |
| `L6` | Declaration order never affects overload resolution. | `T18` |
| `L7` | Every loop terminates when its snapshot is exhausted, whatever the body does to the source collection. | `T31` |
| `L8` | `break` and `continue` outside a loop are user errors, not exceptions. | `T29`, `T30` guards |
| `L9` | Block depth never exceeds 800, so recursion fails with a diagnostic instead of a `StackOverflowError`. | `T16` |
| `L10` | A failed task's error is catchable at `await` and the task is still reported done. | `T34`, `T36` |
| `L11` | The value of a block is the value of its final expression statement, or `nothing`. | `T22`, spec §Blocks |
| `L12` | Both backends agree on values, stdout, message text, hint and exit status. The compiled jar omits the source excerpt, which it does not carry (`U4`). | §7.4, `U4` |

## Invalid transitions

| Attempt | Diagnostic |
|---|---|
| `x = 1` then `const x = 2` in one scope | `error: 'x' is already declared in this scope` |
| `const x = 1` then `const x = 2` | `error: 'x' is already declared in this scope` |
| `const a = 1` then `const a, b = (2, 3)` | `error: 'a' is already declared in this scope` |
| reassigning a `const` | `error: cannot change 'x'`, with `fixedBy` naming the `const` binding |
| reassigning a loop variable | `error: cannot change 'x'`, `fixedBy` naming "the loop variable of this 'for'" |
| `break` outside a loop | `error: 'break' outside a loop` |
| `for x in 5` | `error: cannot loop over number` (hint suggests `for n in 1 to 5`) |
| `await(1)` | `error: await() needs a task from spawn(), got number` |
| two `fun f(a)` with the same arity | `error: 'f' is already defined with 1 parameter` |
| `f(1, 2)` when `f` takes one parameter | names both the expected and actual counts |

## Test mapping

| Invariant | Test |
|---|---|
| `L1`, `L2` | `ErrorMessageContractTest.runtimeErrorsAreNotInternal`, `.everyErrorHasAPositionOrAnExplicitReason` |
| `L3` | `BytecodeCompilerTest.catchBindsTheSameErrorValueAsTheInterpreter` |
| `L4` | `BytecodeCompilerTest.onlyOqErrorsAreCaughtByCompiledHandlers` |
| `L5` | `ErrorMessageContractTest.redeclaringAConstantIsRefusedNotSilentlyOverwritten`; `BytecodeCompilerTest.redeclaringAConstantIsRefusedByBothBackends`; `LanguageFeatureTest` `const` suite |
| `L6` | `LanguageFeatureTest` overload suite; `BytecodeCompilerTest` overload parity |
| `L7`, `L8` | `ErrorMessageContractTest.iterationErrorsSuggestARange`, `.changingACollectionDuringIterationIsWellDefined`, `.changingACollectionInsideACallbackIsWellDefined` |
| `L9` | `NestingLimitTest.deeplyNestedBlocksAreRejectedNotCrashed`, `.oneLevelBelowTheLimitIsAcceptedAndOneAboveIsRejected` |
| `L10` | `ConcurrencyTest.taskErrorPropagatesToAwait`, `.doneReflectsCompletion` |
| `L11` | `BytecodeCompilerTest.aTryExpressionProducesTheSameValueInBothCodepaths` |
| `L12` | `BytecodeCompilerTest.mutationDuringIterationMatchesTheInterpreter`, `scripts/verify-conformance.py` |
