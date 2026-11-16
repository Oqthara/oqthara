# Alpha 6 requirement traceability matrix

This matrix describes **only what the repository actually contains**. A capability moves to
`DONE` when the implementation is merged, a test pins the behaviour, the specification describes
it as implemented, and the conformance baseline was re-measured rather than adjusted by hand.
Nothing here is marked done because it was planned, attempted, or believed to work.

Legend: `DONE` merged, tested and specified · `PARTIAL` implemented but some acceptance
criterion is unmet · `NOT STARTED` no implementation · `BLOCKED` waiting on something outside
the work itself · `REFUSED` deliberately not implemented, and the refusal is pinned by a test.

**How the capability rows were produced.** Every row below was probed by executing a program
against `oq-cli/build/libs/oq.jar` and recording the exit status and the first line of stderr.
The probe set is reproducibility, not testimony: for each `DONE` row the program exits 0 and
prints the value in the Evidence column; for each `REFUSED` row the program exits 1 and prints
the diagnostic quoted there. A row is not `DONE` because a document claims it.

**Last verified:** the branch that adds this file, at the commit named in the pull request that
merged it. The pin must name a commit reachable from the branch under review, because
squash-merge discards branch SHAs and a pin to one of them stops resolving.

## Language surface

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| `finally` cannot be silently absorbed | `DONE` | ADR-0061 · `Lexer.RESERVED` · `ParserTest` (3 added; 18 in the file) · conformance `238`, `239`, `240` · 45 probed positions, 0 accepted | None. `finally` is a hard reserved word, so it is unavailable as an identifier; nothing in the corpus used it |
| Classes and contracts | `DONE` | `contract S` / `class C(v) implements S` executes and prints `9` · ADR-0027 · `ClassTest` · conformance `228` | Runs on the interpreter; the bytecode backend refuses classes and reports a fallback |
| `data` declarations and field access | `DONE` | `data P(x, y)` then `P(1, 2).x` prints `1` · ADR-0028 · `DataTest` · conformance `229` | Typed fields (`data P(x: Int)`) are not implemented — see below |
| `immutable` data is hashable | `DONE` | `data P(x) immutable` then `length(set([P(1), P(1)]))` prints `1`; a mutable `data` in a set is refused · conformance `229` | `immutable` is a postfix modifier on the declaration; it is not accepted before `data` |
| Closures capture their defining scope | `DONE` | `fun counter() ... return () -> 1` then `counter()()` prints `1` | Capture is by reference: `n = 5` after the closure is created is visible through it, which is why the mutation row below behaves as it does |
| Bare-name assignment writes the captured variable | `DONE` | inside a closure, `n = 5` then `return f()` prints `5` · block-scope counterpart prints `2` | This is assignment, not declaration; Oqthara has no `let`-style distinction to get wrong |
| Arrow lambdas | `DONE` | `((x) -> x * 2)(3)` prints `6` | Single expression only |
| Multiline strings | `DONE` | a `"""` literal spanning two lines prints both lines | — |
| String interpolation | `DONE` | `n = 3` then `"n is {n}"` prints `n is 3` | — |
| `use` Java interop | `DONE` | `use java.lang.Math` then `Math.abs(-3)` prints `3` | — |
| `try` / `catch` with a bound error | `DONE` | `try print 1/0 catch e print "caught"` prints `caught` | — |
| Pattern matching (`match` / `case`) | `NOT STARTED` | `match x` exits 1 with `match is not a construct in Oqthara`; `case 1:` likewise | Not implemented in either form (statement or expression). ADR-0062 makes the diagnostic name the construct |
| List comprehension | `NOT STARTED` | `[n * 2 for n in 1 to 3]` exits 1 with `list comprehensions are not implemented` | Not implemented. ADR-0062 makes the diagnostic name the shape |
| Map comprehension | `NOT STARTED` | `{n: n for n in 1 to 3}` exits 1 with `map comprehensions are not implemented` | Not implemented |
| Set comprehension | `NOT STARTED` | `{n for n in 1 to 3}` exits 1 with `set comprehensions are not implemented` | Not implemented |
| Generator expression | `NOT STARTED` | `(n for n in 1 to 3)` exits 1 with `generator comprehensions are not implemented` | Not implemented |
| `yield` | `NOT STARTED` | `yield 1` exits 1 with `yield is not a construct in Oqthara` | Not implemented. `yield` remains a legal identifier, so the refusal is contextual (ADR-0062) |
| Lazy iteration over a range | `NOT STARTED` | `(1 to 1000000).take(3)` exits 1 with `type range has no method 'take'` | Ranges are eager and materialised; there is no lazy view or iterator protocol |
| `switch` | `NOT STARTED` | `switch 1` exits 1 with `switch is not a construct in Oqthara` | Not implemented |
| Typed `data`/`class` fields and parameters | `REFUSED` | `data P(x: Int)` and `class C(v: Int)` exit 1 with `typed fields are not supported`; `fun f(a: Int)`, anonymous `fun` and contract signatures exit 1 with `typed parameters are not supported`; every hint says the language is dynamically typed · `ParserTest.aTypedFieldNamesTheDecisionInsteadOfABracket`, `ParserTest.aParameterNamesTheDecisionInsteadOfABracket`, `ParserTest.aFieldDefaultSurvivesAsADefault` · conformance `246` | Decided against on 2026-11-13 — see [type-system-decision-brief.md](remediation/type-system-decision-brief.md). The refusal used to report `expected ')' to close the field list` / `... parameter list`, a bracket that was never unclosed. Fields and parameters are one row because one decision answers both |
| `import` as a statement | `NOT STARTED` | `import math` exits 1 with `import is not a construct in Oqthara` | `use` is the only import mechanism. `import` remains a legal identifier |
| `throw` statement | `NOT STARTED` | `throw "boom"` exits 1 with `throw is not a construct in Oqthara` | No source-level raise; `error("message")` is the mechanism. The hint says so |
| `def` as a function keyword | `NOT STARTED` | `def f(x)` exits 1 with `def is not a construct in Oqthara` | `fun` is the only keyword. `def` is deliberately not accepted |
| `async` / `await` | `NOT STARTED` | `async fun f()` exits 1 with `async is not a construct in Oqthara`; `await f()` gives the same shape | Not implemented. The diagnostic no longer names `fun`, one token late |

## Numerics (IEEE-754 policy)

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| Overflow of a finite double is `infinity` | `DONE` | `1.0e308 * 10` prints `infinity` · ADR-0051 · conformance `241` | — |
| Underflow of a finite double is `0.0` | `DONE` | `1.0e-308 / 1.0e20` prints `0.0` · conformance `241` | The spec previously claimed this with `1.0e-308 / 10.0`, which is a subnormal, not zero; corrected, and `241` is the corpus's first non-finite coverage |
| `NaN` is not equal to itself | `DONE` | `b = 1.0e308 * 10`, `n = b - b`, `n == n` prints `false` · conformance `241` | — |
| An integer literal past the `long` range is refused | `DONE` | `print 9223372036854775808` exits 1 with `number 9223372036854775808 is too large` | Integer arithmetic itself wraps rather than raising; the literal check is at parse time |
| Integer and float division by zero raise | `DONE` | `1 / 0`, `1 / 0.0`, `1 % 0`, `0.0 / 0.0` all exit 1 with `division by zero` · ADR-0051 §3 · `scripts/probe-non-finite.py` (11 of 11 domain errors raise) | The gap this row carried was false: it claimed `sqrt(-1)` "returns `NaN` without a word … remains unresolved", but it has raised `sqrt() has no real result for -1.0` since ADR-0051. Measured 2026-11-16 and corrected. The raise is deliberate — IEEE-754 would make `1.0 / 0.0` an infinity — and ADR-0051 §3 keeps a *pole* of a fractional power (`pow(0, -1)` → `infinity`) distinct from a division, because a pole has a real answer on both sides |
| `NaN` is unordered, so every relational operator is false for it | `DONE` | `nan > 1.0`, `nan >= 1.0`, `nan <= nan`, `nan >= nan` and `1.0 < nan` all print `false` · ADR-0063 · conformance `245` · `scripts/probe-non-finite.py` (16 of 16) | This was a real defect until ADR-0063: the operators consumed the `Double.compare` total order, so seven of the sixteen `NaN` comparisons returned `true` while `==` and `<` returned `false`. Both backends were affected — `Interpreter.applyBinary` and `Arithmetic.less` — and both now share one `Values.lessThan` |
| Signed zero compares equal under every relational operator | `DONE` | `-0.0 < 0.0` and `0.0 < -0.0` both print `false`, `-0.0 <= 0.0` and `-0.0 >= 0.0` both print `true`, and `-0.0 == 0.0` prints `true` · ADR-0063 §1 · conformance `245` · `scripts/probe-non-finite.py` (9 of 9) | The same defect as the `NaN` row, with no `NaN` involved: `Double.compare` ranks `-0.0` below `0.0` while `==` calls them one value, so `-0.0 < 0.0` was `true` at the same time as `-0.0 == 0.0`. ADR-0017 had already decided `-0.0 == 0`, which made the ordering the half that was wrong. Worth its own row because it shows the cause was one relation serving two purposes, not anything specific about `NaN` |
| Collections still order `NaN`, and `min`/`max`/`clamp` with it | `DONE` | `sort([nan, inf, 1.0, -inf])` prints `[-infinity, 1.0, infinity, NaN]` · ADR-0063 §3 · `scripts/probe-non-finite.py` (8 of 8) | Deliberate: a collection needs every pair to order. `min(nan, 1.0)` is `1.0` and `max(nan, 1.0)` is `nan`, which follows from `NaN` sorting last; ADR-0063 §3 records the decision to bless that rather than make three builtins exceptional |
| `NaN` cannot be used as a key or a set member | `DONE` | `m = {}` then `m[nan] = 1` leaves `length(m)` at `1` but `m[nan]` answers `nothing`; `nan in set([nan])` and `nan in [nan]` both print `false` · ADR-0063 Consequences · `docs/spec/language.md` · `scripts/probe-non-finite.py` (6 of 6) | Not a defect and not caused by the ordering fix — measured identical on `origin/main`. It follows from ADR-0017's `double` equality: `nan != nan`, so no stored copy can ever be matched again. Undocumented until now, which is why it was worth a row: the same paragraph documents `n != n` as the `NaN` test and did not warn that it also makes `NaN` unaddressable once stored |

## Diagnostics quality

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| An unimplemented construct is refused rather than accepted | `DONE` | all 12 `NOT STARTED` rows exit 1; none exits 0 (re-run one by one with the fixed jar, 2026-11-15) | — |
| An unimplemented construct is named in its diagnostic | `DONE` | 10 leaders (`match`, `case`, `switch`, `yield`, `def`, `async`, `await`, `from`, `import`, `throw`) report `<word> is not a construct in Oqthara` in every expression position; 5 comprehension shapes name themselves; `scripts/probe-construct-leaders.py` reports 10/10 operands, 10/10 hints, 20/20 assignment values, 80/80 nested positions, 5/5 shapes, 60/60 name positions and 150/150 continuation positions still accepted, and exits 1 on the pre-change build | None. The refusal is contextual (ADR-0062): it fires only when the next token cannot continue an expression, so all ten words remain legal names and `match`/`from`/`await` keep working in `examples/` |

## Alpha 6 acceptance criteria

The four criteria are defined in
[alpha-5-java-bridge-capability-inventory.md](alpha-5-java-bridge-capability-inventory.md), which is
where their wording is authoritative. A6.1, A6.3 and A6.4 are listed because each now meets
this file's executed-probe method: A6.1 is pinned by conformance programs that run on both
backends, A6.3 by the probe harness asserting the codepath of every probe, and A6.4 is the gate
itself. A6.2 is not listed, because it is a capability decision that has not been made and no
executed probe exists for it. Its state stays in the inventory.

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| A6.1 compiled code calls Java directly | `DONE` | A top-level `use` lowers to a class constant, so a static call, a static field, a constructor and the class value all compile. `conformance/248-java-interop-by-use.oq` exercises all four plus a variable shadowing the class name, and `conformance/249-use-name-shadowing-scopes.oq` pins which binding hides the class (a loop variable, a destructured name and a caught error are current-scope; an outer assignment is not). Both are byte-identical between `oq run` and the jar. `python3 scripts/verify-conformance.py` reports `210 compiled, 9 fallback (219 of 219 executable), 0 divergences across 249 programs`; the baseline was re-measured twice, from 217/208 to 218/209 to 219/210, not asserted. `run.py --both` reports `ok: 17 probes compared compiled bytecode against the interpreter` (the 18th is the declared fallback, counted apart). The two tests that asserted `use` was refused were rewritten and were observed failing against the old expectation | A `use` below the top level is still refused by the statement (`'use' only compiles at the top level`), so boundary item 2 in `docs/spec/backend.md` is narrowed rather than deleted. `oq-lsp` has no completion feature at all, so nothing there changed and nothing there is claimed |
| A6.3 compiled code answers `Class` self-description without falling back | `DONE` | `d2_class_instance.oq` builds with `codepath: compiled bytecode` and its jar prints `java.lang.String`, `String`, `false`, `false` — byte-identical to `oq run`. Emitting the IR shows why it is real and not a launcher: `const class java.lang.String`, `call_value/1`, `method_call getClass/1`, and the writer reports `backend=classfile (self-contained writer, JDK 21)`. The claim was true but ungated, so `run.py --both` now requires `codepath: compiled bytecode` per probe and prints `ok: 17 probes compared compiled bytecode against the interpreter` plus a note naming the one probe that ran via the launcher. A regression is caught in both directions: renaming the one declared fallback out of `FALLBACK` fails the run with `expected 'codepath: compiled bytecode'`, and declaring a probe that does compile fails with `recorded fallback is gone`. A build that prints no codepath line at all fails rather than passing silently. `scripts/tests/test_ci_probe_wiring.py` covers this at two levels: 9 tests over the decision function, and 4 that call `main` with the subprocess calls stubbed, so deleting the check from the runner fails a test instead of leaving every test green. Measured: reverting `run.py` alone to its pre-gate state gives 1 failure and 11 errors out of 22; deleting only the `codepath_failure` call from `check_probe` fails 2 | One probe still falls back: `d1_sort_by` calls `sort_by`, which the backend has never lowered. It is declared with the reason its own build prints, so the fallback is a stated fact and not a tolerance. Reflection remains refused, which is A6.2's question, not this one |
| A6.4 the probe harness runs in CI | `DONE` | The `Decision probes (A6.4)` step in `ci.yml` runs `run.py oq-cli/build/libs/oq.jar --both` plus the three `scripts/probe-*.py --check` harnesses against the jar the conformance step builds; all four exit 0. A one-day drift in one probe's expected date makes `run.py` print `FAILED`, name the probe, and exit 1. `scripts/tests/test_ci_probe_wiring.py` fails if the step is removed, moved to a job without the matrix leg, gated differently from the conformance step, or has a harness commented out | Covers C1–C8 and the D1/D2/D3/D5 defect probes; the ten C9–C12 rows have no probe and are not gated. A6.2 remains in the inventory |

## How a row is closed

A row may move to `DONE` only when all of the following hold on `main`:

1. The implementation is merged through a protected pull request with all required checks green.
2. The behaviour is pinned by a test that has been shown to fail without the change.
3. The specification and any ADR describe the behaviour as implemented, not as intended.
4. The conformance baseline has been re-measured rather than adjusted by hand.

Anything less is `PARTIAL`, and the row says what is missing.

## What this matrix does not cover

The rows above are language surface, numerics and diagnostics — the capabilities that can be
established by executing a program. The directive's remaining scope also includes work that is
not a language capability: bytecode backend parity, IR verification before emission, the Java
Bridge, dead-code audit, test-suite speed, and editor/LSP synchronisation. Those are tracked in
their own documents and by their own gates; listing them here as `NOT STARTED` would imply they
were probed by the method this file describes, and they were not.
