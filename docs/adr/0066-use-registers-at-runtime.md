# ADR-0066 — `use` registers at run time, so it compiles below the top level

**Status:** Accepted
**Date:** 2026-11-17
**Related:** ADR-0064 (use binds a class value, which this refines),
[`language.md`](../spec/language.md) §use,
[`backend.md`](../spec/backend.md) §What the compiled path does not do,
[`alpha-6-requirement-matrix.md`](../governance/alpha-6-requirement-matrix.md) A6.1,
`Interpreter.javaTypes`, `Lowering.useStatement`

## Context

ADR-0064 refused two programs by name:

```
'use' only compiles at the top level
'<Name>' is already the name of a used class
```

Its reasoning for the first was that the interpreter's registration is conditional on the path
being taken, so "the leak is not a scope rule that can be modelled statically — whether the name
exists depends on a value the compiler cannot read." Its reasoning for the second was that the
interpreter rebinds the name as each statement runs "while a compiled name is fixed for the whole
method."

The first sentence is true and the conclusion does not follow from it. The interpreter *does*
have a table — `Interpreter.javaTypes` — and a table populated by executing statements is
exactly what a compiled program can also have. What the compiler cannot do is decide the table's
contents at build time; it can still emit the operations that fill it at run time. Refusing on
the grounds that a value is not readable at build time was a conclusion about the wrong
mechanism.

The second sentence is false about the interpreter and, once the registry is a run-time map,
false about the compiled path too. `use java.util.Date` / `use java.sql.Date` under the
interpreter prints `class java.sql.Date` — the later statement replaces the earlier binding. A
map written in execution order reproduces that with no special case.

Alpha 6's execution directive named nested `use` as an unverified obligation and required the
semantics to come from the specification and accepted decisions rather than from what is easy to
support.

## Decision

`use` is lowered as a **run-time registration**, not as a build-time constant.

- A new `Ir.Use(path, simpleName, span)` resolves the class **when it executes** and stores it in
  a program-wide registry keyed by the bare name.
- A read or construction of a name whose `use` exists anywhere in the program becomes
  `Ir.LoadUsed(simpleName)`, which reads that registry and raises the interpreter's own
  `'<name>' is not defined` when the name was never registered.
- The registry is `dev.oqthara.runtime.UsedClasses`, a `static` map in `oq-runtime`, reached from
  generated code the same way `Callables`, `Collections` and `Errors` already are.
- A `use` at the top level of `main` additionally keeps its build-time class literal, so
  `BuildCommandTest.anUnknownClassNameIsADiagnosticNotAStackTrace` still fails the build on a
  misspelled top-level path instead of deferring it to run time.

The two refusals are deleted.

## What this reproduces, and why it is the faithful choice

Every case below was executed against the interpreter before the change and is pinned by a
conformance program after it.

| Program | Interpreter | Why a registry is the only faithful model |
| --- | --- | --- |
| `use` then read, same block | prints the class | registration happened |
| read then `use` | `'Math' is not defined` | not hoisted |
| `use` in a called `fun`, read after | prints the class | leaks out of the body |
| `use` in an untaken `if`, read after | `'Math' is not defined` | registration is conditional on execution |
| `use` in an uncalled `fun` | never resolves, never fails | same, and a misspelling is never reported |
| `use` inside a taken `if` with a misspelled path | `cannot find Java class ...` at the `use` | the path is resolved when reached |
| `use java.util.Date` / `use java.sql.Date` | `class java.sql.Date` | last registration wins |
| block-local `x = 7` beside `use java.lang.Math`, `print x` inside then outside | `7` then `6` | a frame-local binding wins over the registry |

The last row is the one that decides the lowering shape. `Env.hasLocal` is checked before
`javaTypes`, and only *local* — an enclosing frame's binding does not hide the class. Lowering
therefore consults `inCurrentScope`, not `boundSomewhere`, preserving the existing ADR-0064
behaviour for that case.

Lexical scoping is wrong on rows 3, 4 and 5. Static hoisting is wrong on rows 2 and 5. Either
would compile a program whose result differs from the interpreted one, which is the outcome
ADR-0031 and ADR-0062 exist to prevent.

## Consequences

**The compiler no longer reads a `use` path at build time below the top level.** A misspelled
nested path becomes a run-time error at the `use` span, with the interpreter's message and hint,
instead of a build diagnostic. That is not a regression: a nested misspelling in a branch that is
never taken is *not an error* under the interpreter, so failing the build on it would have been
wrong.

**The depth counter loses its `use` role.** `Lowering.depth` still guards other constructs and
stays; only the `use` test on it goes.

**A program-wide mutable registry is process state.** A compiled jar registers into one static
map. Tests already run each jar as its own JVM process, so no test can observe another's
registrations; a program cannot register a name it did not write a `use` for, and `use` is not
reachable from a lambda that survives the program.

**`use` is now compiled where it was refused, so the conformance baseline moves up.** The new
programs added for these cases raise both counts; the numbers are re-measured, never asserted
forward.

## Alternatives considered

**Hoist every `use` to the top of `main`.** Simplest, and wrong: rows 2 and 5 print a class where
the interpreter raises or stays silent.

**Scope `use` lexically to its block.** Also simple, and wrong on rows 3, 4 and 5.

**Keep the refusal.** Defensible only if the semantics could not be reproduced. It can, exactly,
in about forty lines of emitter code, so the refusal was a limitation of the implementation
presented as a property of the language.

**A `static` field on the generated class instead of a runtime helper.** Would work, and would
require emitting a field into the class file, which the generator currently writes with a zero
field count. A helper beside the six others generated code already calls is the smaller change
and keeps the class file simple, which is the product.

## References

`Interpreter.java` `javaTypes` and its `UseStmt` arm; `Env.hasLocal`; `Lowering.useStatement`;
`Ir.Use`; `Ir.LoadUsed`; `BytecodeCompiler.plan`; `BytecodeCompiler.emit`;
`dev.oqthara.runtime.UsedClasses`.
