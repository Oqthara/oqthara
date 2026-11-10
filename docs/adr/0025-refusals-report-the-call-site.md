# ADR-0025 — A refusal reports the call site that caused it

- **Status:** accepted
- **Date:** 2026-11-10
- **Supersedes:** nothing
- **Related:** ADR-0022 (hashability and frozenset), ADR-0017 (equality and hashing)
- **Directive:** Alpha 4 Master Execution Directive §9.2 (protocols), §9.4 (edge cases)

## Context

ADR-0022 added `frozenset` and made mutable values unusable as map keys or set members.
The refusal is raised deep inside the collection, in `Value.requireHashable`, which has no
source position of its own and therefore reads `Errors.current()`.

ADR-0022's "Positions" section records the first repair: the set and map **literals** and
`Values.setIndex` were given the span they already held, and the compiled backend began
marking the literal's span. That repair covered the three programs it named. It did not
cover the **constructor and algebra paths**, which reach the same refusal through a
different route.

Measured on `main` at `31a49e1`, with the offending expression on line 2 so that a correct
answer is `:2:...` and a synthetic one is visibly wrong:

| Program (line 2) | Compiled backend | Interpreter |
| --- | --- | --- |
| `print(set([[1]]))` | `:2:10` | `<unknown>:1:1` |
| `print(frozenset([[1]]))` | `:2:16` | `<unknown>:1:1` |
| `print(unique([[1]]))` | `:2:13` | `<unknown>:1:1` |
| `print([[1]].to_set())` | `:2:12` | `<unknown>:1:1` |
| `print(set([1]).union([[1]]))` | `:2:15` | `<unknown>:1:1` |
| `print(set([1]).add([[1]]))` | `:2:15` | `<unknown>:1:1` |
| `print(frozenset([1]).union([[1]]))` | `:2:21` | `<unknown>:1:1` |
| `print(frozenset([1]).difference([[1]]))` | `:2:21` | `<unknown>:1:1` |
| `print([[1]].unique())` | `:2:12` | `<unknown>:1:1` |
| `print(par_map([[1]], x -> set([x])))` | *(falls back to the interpreter)* | `<unknown>:1:1` |

The defect is therefore **one-sided: the interpreter never marks a span at all.**
`Interpreter.java` contains no call to `Errors.mark`, so every runtime helper that reads
`Errors.current()` — which is every helper shared with compiled code — sees
`Span.synthetic()`, whose source is the literal string `<unknown>` and whose line and column
are 0. The compiled backend marks correctly at every operation, which is why its column is
right in every row above.

This is not specific to hashability. Any refusal raised from `oq-runtime` during
interpretation inherits the synthetic span. The hashability refusals were simply the first
ones common enough to be noticed, because `set(...)`, `frozenset(...)` and `unique(...)`
receive an arbitrary value and must check it.

The last row is not a compiled defect. `par_map` is in `Builtins.HOST_DEPENDENT`, so a
program that calls it is compiled to an interpreter launcher rather than to bytecode. That
row is the interpreter's defect again, reached through a callback.

## Decision

**The interpreter marks the span of a call before it dispatches, and the mark is per
thread.**

1. `Interpreter.invoke` is the single funnel every builtin, every user function and every
   method reaches — `evaluateCall` for `f(x)`, `evaluateMethod` for `x.f()`, and the one
   callback entry point. Marking `Errors.current()` there gives all of them the position of
   the call expression, in one place, instead of patching each refusal site.

2. **The mark is per thread, not per process.** Oqthara runs user code on virtual threads
   through `spawn` and `par_map`, which are interpreter-only builtins. `Errors.current` was a
   `static volatile` field, so two concurrent callbacks would overwrite each other's position
   and report a line from the other thread's code. It is now a `ThreadLocal`, which is also
   the correct model for the compiled backend: a compiled program is single-threaded
   precisely because every concurrency builtin is `HOST_DEPENDENT`, so a per-thread slot holds
   exactly what a per-program slot held.

No other change was needed. The plan that produced this ADR also called for span-taking
`OqSet`/`OqFrozenSet` constructors and `add` overloads, on the theory that a callback's first
operation on a fresh virtual thread would find nothing marked. That theory was wrong:
`par_map` calls each callback through `invoke`, so the callback's own frame marks before it
runs. The span-taking constructor form was implemented first, measured to change no result,
and dropped as dead weight. `Value.requireHashable(value, role, span)` stays for the literal
path that already had a span; nothing else needs it.

## What this costs

`Errors.current` is read on the compiled path for every arithmetic operation and every
collection access, so changing a `static volatile` field to a `ThreadLocal` is a change on a
hot path. The expectation was a small regression; measurement found the opposite.

A 3,000,000-iteration loop doing `total = total + i * 2` and `i = i + 1`, compiled to a jar,
run five times each way with `/usr/bin/time -p` on JDK 21:

| | real (s) |
| --- | --- |
| `static volatile` (before) | 0.53, 0.54, 0.55, 0.54, 0.54 |
| `ThreadLocal` (after) | 0.46, 0.46, 0.47, 0.45, 0.46 |

The per-thread version is about 15% faster. That is plausible rather than surprising: a
`volatile` read is a memory barrier that forbids reordering, while a `ThreadLocal` read is a
map lookup on the current thread with no barrier. The change removes a synchronisation point
from the arithmetic loop, so the cost section is a gain, not a cost. The figures are from one
machine and one JDK; the direction is what matters, and it is not a regression either way.

The alternative — keeping one static slot and accepting that concurrent callbacks report
each other's positions — was rejected. A wrong position sends the reader to a line they did
not write, which is worse than an honestly vague one, and `par_map` is a documented feature.

## Rejected alternatives

**Mark inside the collections instead of at the call.** `OqSet.add` has no span and no way
to learn one; threading it through every internal caller is the per-site patching this ADR
avoids. It was tried: the span-taking constructor form changed no measured result, because
the mark at the funnel had already set the position before the constructor ran.

**Leave the interpreter alone and document the limitation.** The interpreter is the backend
`oq run` uses and the one most tests exercise, so "one backend reports a real position" is
not a limitation worth accepting. It also cannot be documented honestly: the language
reference promises errors carry a position.

**Give `Errors` a stack of spans instead of a thread-local.** A stack is needed only if an
outer operation must report its own position after an inner one has marked. Nothing does:
the innermost mark describes the operation about to fail, which is the one wanted. The
compiled backend already relies on that property.

**Fix only the sites the table lists.** The table is what was probed, not what exists. Any
future builtin that reads `Errors.current()` would reintroduce the defect, and the
constructor and algebra sites are reachable through many spellings (`unique`, `union`,
`intersection`, `|`, ...). A funnel plus a span-taking constructor covers the class.

## Verification

Each program was run through `oq run`, and where the compiled backend supports it, through
a jar built by `oq build`; the two must agree. The offending expression is on line 2 so a
synthetic `<unknown>:1:1` cannot be mistaken for a correct answer.

| Program (line 2) | Compiled backend | Interpreter after the fix | Interpreter before |
| --- | --- | --- | --- |
| `print(set([[1]]))` | `:2:10` | `:2:10` | `<unknown>:1:1` |
| `print(frozenset([[1]]))` | `:2:16` | `:2:16` | `<unknown>:1:1` |
| `print(unique([[1]]))` | `:2:13` | `:2:13` | `<unknown>:1:1` |
| `print([[1]].to_set())` | `:2:12` | `:2:12` | `<unknown>:1:1` |
| `print(set([1]).union([[1]]))` | `:2:15` | `:2:15` | `<unknown>:1:1` |
| `print(set([1]).add([[1]]))` | `:2:15` | `:2:15` | `<unknown>:1:1` |
| `print(frozenset([1]).union([[1]]))` | `:2:21` | `:2:21` | `<unknown>:1:1` |
| `print(frozenset([1]).difference([[1]]))` | `:2:21` | `:2:21` | `<unknown>:1:1` |
| `print([[1]].unique())` | `:2:12` | `:2:12` | `<unknown>:1:1` |
| `print(par_map([[1]], x -> set([x])))` | *(interpreter fallback)* | `:2:30` | `<unknown>:1:1` |

The two backends now agree on every row, which is what
`verify-conformance.py` checks across the example corpus and what the new
`BuildJarTest` case pins for these programs specifically.

`ErrorMessageContractTest.anUnhashableMemberIsReportedAtTheCallThatBuiltIt` asserts the line
and the absence of `<unknown>` for each of them, and additionally that the `par_map` case
reports `:3:30` — the inner `set([x])`, on a virtual thread. That is the assertion that
fails if the mark ever goes back to a single shared slot.

The Python reference consulted was CPython 3.13 on 2026-11-10, per §9.6. CPython reports
the failing call's own location for the equivalent `set([[1]])`, which is what this ADR
makes Oqthara do on both backends.

### The second layer, found later

The repair above was necessary and not sufficient, and the way it was insufficient is worth
recording because the two defects hid each other.

With a `null` span the error rendered as `<unknown>`, so the fact that the *marked* position
was also wrong could not be seen. On `main` before this repair the program

```
fs = frozenset([1])
fs.add(2)
```

reported `<unknown>:1:1` — synthetic, because of the null span. The stale-mark defect was
therefore never visible on `main`; it became observable only once the nulls were replaced,
which is exactly why it had survived. With the nulls fixed but the mark still stale, the
same two-line program reported line 1 for an error on line 2.

`evaluateMethod` evaluated the receiver and every argument before dispatching, and each of
those evaluations marked its own call. The JavaBridge fallback raises from inside the
runtime, so it read whichever call had marked last — in the case above, the `frozenset([1])`
on line 1. With an inner call in the arguments it was worse: `fs.add(frozenset([2]).length())`
reported the position of `.length()`.

The fix is one line in `evaluateMethod`, marking the method call after its operands are
evaluated:

```java
Errors.mark(call.span());
```

The compiled backend never had this defect: `Lowering` emits `mark(call.span())` for
`Ast.MethodCall`, so it was already reporting the method call's own position. The
interpreter now agrees with it exactly — both backends report `:2:3` for the program above,
which is the start of the receiver `fs`, and both report `:2:3` for
`fs.add(frozenset([2]).length())`, where the stale mark used to land on `.length()`.

`ErrorMessageContractTest.anImmutableCollectionRefusalNamesTheCallThatTriedToChangeIt`
covers three mutators, the nested-call case, and `range(0, 5, 0)`, asserting both the
position and the absence of `<unknown>`.

## Relationship to Python

Python raises `TypeError: unhashable type: 'list'` from the frame that called `set`, and
`traceback` points at that line. Oqthara's column is finer — the call expression rather than
the statement — because it already carries spans at expression granularity for every other
error. The message stays Oqthara's own: it names the value's type and tells the reader what
to use instead, which Python's does not.
