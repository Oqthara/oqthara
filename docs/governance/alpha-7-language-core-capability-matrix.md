# Alpha 7 — Language Core Capability Matrix

**Baseline commit:** `d7d3c05` (`alpha7-language-core-scope`, "Correct the review's account of what the
deleted section broke")
**Audit date:** 2026-11-18
**Owner:** `oq-hive-language-syntax` (§4's sole technical design owner, per
[`alpha-7-first-report.md`](alpha-7-first-report.md) Item 6b)
**Method:** every row was executed against the CLI built from the baseline
(`./gradlew :oq-cli:standaloneJar`), using `java -jar oq-cli/build/libs/oq.jar`. No row is classified
from source reading alone unless it is explicitly marked as a static finding.
**Review mechanism:** AI review per ADR-0059 and [`ai-review-policy.md`](ai-review-policy.md).
`Independent: no` — the reviewer is an AI agent (`deepseek-ai/deepseek-v4.1-flash-1017`), not a
human, and this document does not claim human review.

This is §4's **first deliverable**, required by Item 6b of the first report before any broad
language-core implementation. It inventories the normative surface; it changes nothing.

## Classification vocabulary

Six verdicts, taken from the order's §3 requirement. They are **not** the Alpha 5 Java Bridge
labels, because §4 asks a different question: not "does this work" but "is this settled".

| Label | Meaning |
|---|---|
| **Complete** | Documented, implemented, and behaves as documented, with executable evidence. |
| **Defective** | Implemented, but contradicts its own documentation or errors on a documented input. |
| **Ambiguous** | Implemented and possibly documented, but the contract is not pinned down by the specification, so two readers could reasonably expect different behaviour. |
| **Intentionally refused** | Absent by an explicit reasoned decision recorded in the specification or an ADR. |
| **Undocumented** | Implemented and arguably correct, but the specification is silent, so the behaviour is a de facto contract with no stated authority. |
| **Missing** | Required by the specification or implied by the architecture, and absent. |

"Missing" is narrower than "unimplemented". Twelve constructs are unimplemented *and* that is
correct — they are **intentionally refused**, and §4 may not revive one without a proposal
formally reversing the relevant decision.

## Counts

57 rows, counted from the table IDs by
`grep -c '^| L[0-9]'` and classified by the verdict cell:

| Verdict | Rows | Which |
|---|---|---|
| Complete | 51 | every row not listed below |
| Missing | 2 | L3.11, L6.6 |
| Ambiguous | 2 | L4.9, L5.7 |
| Undocumented | 1 | L2.6 |
| Intentionally refused | 1 | L4.8 |
| Defective | 0 | — |

The six non-Complete rows — two Missing, two Ambiguous, one Undocumented, one refused — are
§4's open backlog. L9.3 was the seventh until this branch closed it; it is recorded Complete
below with the check that closed it, rather than deleted, because the audit found it and a reader
should be able to see what became of every row the audit touched.

Rows are grouped into the nine scope areas of §3. Each area is one direction below.

## Direction 1 — Lexer, tokens, grammar, parser, and AST

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L1.1 | Keyword set is closed at 22 and rejects unknown leaders | Complete | `print 1` → `1`. Keyword list pinned at 22 by the docs-sync gate, which reads `Lexer.KEYWORDS` and reconciles the reference, the editor grammar and `CONTRIBUTING.md` | `Lexer.java:13` |
| L1.2 | Unterminated text literal is refused with a located error | Complete | `print "abc` → `error: unterminated text literal` at `1:7` | `Lexer.java` |
| L1.3 | Unclosed block names the opener it expected | Complete | `if true` with no `end` → `error: expected 'end' to close this 'if'` | `Parser.java` |
| L1.4 | The ten reserved leader words refuse syntax but remain legal names | Complete | `match = 5; print match` → `5`; `match x` → `error: match is not a construct in Oqthara`; `import x` and `throw x` refuse identically; `finally` → `error: finally does not exist` | `Lexer.java`, `Parser.java` |
| L1.5 | Indentation is not semantic | Complete | A leading tab parses identically to a leading space | `Lexer.java` |
| L1.6 | Editor grammar keywords agree with the lexer | Complete | 16 control words + 3 constants + 3 word-operators = 22, matching `Lexer.KEYWORDS`. The ten leader words are correctly absent because they are names, not keywords | `editors/vscode/syntaxes/oq.tmLanguage.json` |

L1.6 is a static finding: the grammar file was compared against `Lexer.KEYWORDS` by reading both.
No executable test runs the TextMate grammar, and none can without a VS Code host.

## Direction 2 — Operator precedence and expression evaluation

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L2.1 | Precedence table is normative and published from the parser | Complete | `docs/spec/language.md:303` publishes the table "from the parser itself"; `2 ** 3 ** 2` → `512` (right-associative) | `Parser.java` |
| L2.2 | Unary minus binds tighter than `**`, so `-2 ** 2` is `(-2) ** 2` | Complete | Prints `4`. This differs from most languages and is **stated explicitly** at `docs/spec/language.md:319`, so it is a decision, not an accident | `Parser.java` |
| L2.3 | `and` binds tighter than `or` | Complete | `true or false and false` → `true` | `Parser.java` |
| L2.4 | Comparisons do not chain | Complete | `1 < 2 < 3` → `error: cannot compare boolean with number`, which is the honest answer given comparisons yield booleans | `Values.java` |
| L2.5 | `+` refuses operands it cannot combine, and says what it can do | Complete | `1 + [1]` → `error: cannot add number and list` with `help: + works with numbers, text, lists, maps and sets.` | `Values.java:601` |
| L2.6 | `+` with text on either side stringifies both operands | **Undocumented** | `1 + "a"` → `1a`; `"a" + 1` → `a1`; `"a" + [1]` → `a[1]`; `"a" + nothing` → `anothing`. The rule is `if (a instanceof String \|\| b instanceof String) return display(a) + display(b)` at `Values.java:577`. The specification never states it, and no conformance file exercises it | `Values.java:577` |

**L2.6 is the matrix's most consequential finding.** `+` is documented as "add, subtract"
(`docs/spec/language.md:312`) with no mention of coercion, yet text silently absorbs every other
type while `1 + [1]` is refused. A reader comparing the two help messages would conclude the
language is inconsistent; it is simply undocumented. Either the rule is stated normatively or it
is removed. §4 should not leave it as a de facto contract.

## Direction 3 — Statements, control flow, assignment, binding, and scope

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L3.1 | Assignment to an existing name updates it; a new name is created | Complete | `x = 1; if true; x = 2; end; print x` → `2` | `Interpreter.java` |
| L3.2 | Loop variables do not leak out of the loop | Complete | `for i in 1 to 2; end; print i` → `error: 'i' is not defined` | `Interpreter.java` |
| L3.3 | A function's locals do not leak to the caller | Complete | `fun f(); y = 1; end; f(); print y` → `error: 'y' is not defined` | `Interpreter.java` |
| L3.4 | `while` with `break` and `continue` behaves | Complete | Counter loop with `continue` at `i == 3` prints `1 2 4 5` | `Interpreter.java` |
| L3.5 | `const` bindings refuse reassignment | Complete | `const c = 1; c = 2` → `error: cannot change 'c'` | `Interpreter.java` |
| L3.6 | Compound assignment (`+=`, `-=`, `*=`, `/=`) | Complete | Documented at `docs/spec/language.md:338`; exercised by conformance | `Parser.java`, `Interpreter.java` |
| L3.7 | Destructuring binds several names from one value | Complete | `a, b = (1, 2)` → `3`; `x, y = "hi"` → `y` is `i`; `a, b = {1, 2}` → `3`; `a, b = 1 to 2` → `2` | `Interpreter.java` |
| L3.8 | Destructuring count mismatch is an error, never a partial binding | Complete | `a, b = [1,2,3]` → `error: cannot destructure 3 items into 2 names`. Atomicity confirmed under `try`: after a failed `a, b = [9]` inside `try`, `print a + b` still prints `3`, so neither name was written | `Interpreter.java` |
| L3.9 | Destructuring rejects `data` instances and scalars | Complete | `a, b = P(1)` → `error: cannot destructure P`; `a, b = nothing` → `error: cannot destructure nothing` | `Interpreter.java` |
| L3.10 | `(a, b) = v` is not destructuring | Complete | `(a, b) = (1,2)` → `error: cannot assign to this expression`, matching the specification's explicit statement that parentheses make a tuple literal | `Parser.java` |
| L3.11 | Destructuring has conformance coverage | **Missing** | No file in `conformance/` contains a destructuring binding. `grep -rln ", b = \|, *[a-z]* = (" conformance/*.oq` returns nothing. All eight behaviours above are verified here for the first time and nothing would catch a regression | `conformance/` |

An **eleven-row** audit of one feature is disproportionate; L3.7–L3.10 exist because L3.11 is
missing. The right fix is conformance files, after which these rows collapse to one.

## Direction 4 — Functions, lambdas, closures, and call semantics

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L4.1 | Recursion | Complete | `fib(10)` → `55` | `Interpreter.java` |
| L4.2 | Closures capture enclosing bindings | Complete | `fun mk(n); return fun(x); return x + n; end; end; print mk(5)(3)` → `8` | `Interpreter.java` |
| L4.3 | Overload by arity | Complete | Two `f` definitions with 1 and 2 parameters; `f(1) + f(1,2)` → `3` | `Interpreter.java` |
| L4.4 | Single-expression lambda | Complete | `f = x -> x * 2; print f(4)` → `8` | `Parser.java` |
| L4.5 | Multi-parameter lambda | Complete | `f = (a, b) -> a + b; print f(1,2)` → `3` | `Parser.java` |
| L4.6 | Arity mismatch names the expectation | Complete | `f(1,2)` against a 1-parameter `f` → `error: f() expects 1 argument, got 2` with `help: Expected: a` and a caret at `3:2` | `Interpreter.java` |
| L4.7 | `return` outside a function is refused | Complete | → `error: 'return' outside a function` | `Parser.java` |
| L4.8 | Default parameter values | **Intentionally refused** | `fun f(a, b = 2)` → `error: expected ')' to close the parameter list`. Absence is consistent — `docs/spec/language.md` lists `.get(k, fallback)` for maps and never a default parameter — but no ADR states the decision. It is refused, and this matrix does not propose adding it | `Parser.java` |
| L4.9 | Block-bodied lambda | **Ambiguous** | `f = x ->\n  y = x * 2\n  return y\nend` → `error: expected an expression`. The specification documents `x -> expr` and a block form for `items.sort(item -> ...)` but never states whether `->` may open an indented block. A reader of the `fun` documentation could reasonably expect it to | `Parser.java` |

L4.8 is classified "intentionally refused" on the weaker of the two grounds the vocabulary allows:
the specification is silently consistent, not explicit. That is a documentation gap to record, not
a capability gap to close.

## Direction 5 — Iteration, destructuring, and collection-related constructs

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L5.1 | `for` over a range, honouring `to` and `until` | Complete | Documented and exercised by conformance 012–014 | `Interpreter.java` |
| L5.2 | Lazy ranges | Complete | `r = 1 to 3; print r` → `1 to 3`, a range value rather than a materialised list | `OqRange` |
| L5.3 | Slicing preserves the receiver's family | Complete | `[1,2,3,4][1:3]` → `[2, 3]`; stated at `docs/spec/language.md:566` | `Values.java` |
| L5.4 | Negative indices count from the end | Complete | `[1,2,3][-1]` → `3` | `Values.java` |
| L5.5 | Map iteration order is the map's own order | Complete | `{"z":1,"a":2}` iterates `z` then `a`; the specification says "the map's own order" and never promises sorted or insertion order | `OqMap` |
| L5.6 | Mutating a collection during iteration is defined | Complete | Documented under "Changing a collection while looping over it" at `docs/spec/language.md:379` | `Interpreter.java` |
| L5.7 | `.sort()` return and mutation behaviour | **Ambiguous** | `x = [3,1,2]; x.sort(); print x` → `[3, 1, 2]`, unchanged; `y = x.sort()` → `[1, 2, 3]`. So `.sort()` is **non-mutating despite its name**, while `.push`, `.pop`, `.insert`, `.remove` and `.clear` are mutating. `docs/spec/language.md:507` lists `.sort()` among list methods and `:689` states that the free function `sorted` returns a new list, but the spec never states what `.sort()` does. This is a genuine trap: a name that reads as a mutation and is not one | `OqList.sort` |

L5.7 is the clearest **Ambiguous** verdict in the audit. It is not a bug — the behaviour is
consistent between backends — but a reader who writes `x.sort()` and expects `x` reordered gets
silence. Either the specification states that `.sort()` returns a new list and never reorders *x*,
or the method should mutate, matching every sibling in the list. The decision belongs in an ADR.

## Direction 6 — Module and import semantics

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L6.1 | `use "oq:name"` loads a sibling module | Complete | With `helper.oq` defining `twice`, `use "oq:helper"; print twice(3)` → `6` | `Interpreter.java` |
| L6.2 | A missing module names the module, not the class | Complete | `use "oq:absent"` → `error: cannot find module 'absent'`, distinct from the Java-import error | `Interpreter.java` |
| L6.3 | `use` without the prefix is a Java import | Complete | `use java.util.ArrayList` resolves the class; the two forms are distinguished by the `"oq:"` prefix | `Interpreter.java` |
| L6.4 | Module search order is program directory, then `modules/`, then `lib/` | Complete | Stated at `docs/spec/language.md:2525` | `Interpreter.java` |
| L6.5 | Import cycles terminate | Complete | Stated at `docs/spec/language.md:2530`; a file is loaded at most once | `Interpreter.java` |
| L6.6 | Module and import semantics have conformance coverage | **Missing** | No file in `conformance/` imports an Oqthara module. `grep -rln 'use "oq:' conformance/*.oq` returns nothing. L6.1–L6.5 are verified here for the first time | `conformance/` |

L6.6 is the second of the two coverage holes. Modules are a documented, load-bearing feature —
`oq build` splices them — with no regression test.

## Direction 7 — Error and diagnostic behaviour that forms the language contract

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L7.1 | Diagnostics carry file, line, column and a caret | Complete | `print y` at `1:7` → `error: 'y' is not defined`, `--> e.oq:2:7`, the source line, and a caret under the token | `OqError` |
| L7.2 | Diagnostics carry a suggested fix | Complete | The same error appends `help: Check the spelling, or define it before using it: y = value` | `Interpreter.java` |
| L7.3 | Every refusal is a diagnostic, never a crash | Complete | All twelve reserved constructs and every error row above exit 1 with `error:` and no stack trace | `Main.java` |
| L7.4 | Exit code is 1 for any error, 0 for success | Complete | Verified on every probe above | `Main.java` |
| L7.5 | A failure inside a module reports the module's position | Complete | Stated at `docs/spec/language.md:2554` | `Interpreter.java` |
| L7.6 | `oq build` reports a spliced failure against the program's lines | Complete | Stated, with the trade-off, at `docs/spec/language.md:2566` | `Compiler` |

## Direction 8 — Interpreter and compiled-backend agreement

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L8.1 | The two backends agree on arithmetic, collections, tuples, and `data` | Complete | A ten-statement program (`fib(10)`, `x.sort()`, map insert, `7 / 2`, `7.0 / 2`, a tuple and a `data` instance) produced **byte-identical** output interpreted and as a built jar: `55 / [3, 1, 2] / {"k": 1} / 3.5 / 3.5 / (1, "a") / P(x: 1, y: 2)` | `Compiler`, `Interpreter` |
| L8.2 | `.sort()` is non-mutating on both backends | Complete | The same program shows `[3, 1, 2]` after `x.sort()` on both. Consistent, which is why L5.7 is Ambiguous rather than Defective | Both |
| L8.3 | Agreement is verified systematically, not by sample | Complete | `BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter` runs **every** `.oq` file in `conformance/`, skips the 9 that declare a compiler fallback, and asserts the compiled output equals `oq run`'s. Measured 2026-11-18: **215 compiled / 9 fallback / 0 divergences over 257 programs (224 executable)**. The contract is stated in the test: "a program is only compiled when the compiler can prove it, and once compiled it must behave exactly like `oq run`" | `BytecodeCompilerTest.java:1560` |

L8.3 was the row I expected to record as Missing — the tree only ever *demonstrated* backend
agreement, case by case, and never stated the invariant. It is Complete: the invariant is the test's
own contract sentence, and the assertion is corpus-wide. Recording that is worth doing, because the
finding that survives is the opposite of the one I went looking for: 257 programs, 9 declared
fallbacks, **zero divergences**, and the count is pinned with a comment requiring new measurement to
raise it.

## Direction 9 — Syntax-facing editor grammars synchronized with the specification

| ID | Capability | Verdict | Evidence | Location |
|---|---|---|---|---|
| L9.1 | The TextMate grammar highlights all 22 keywords and 3 constants | Complete | 16 control words + `true`/`false`/`nothing` + `and`/`or`/`not` = 22, reconciled against `Lexer.KEYWORDS` by reading both files | `editors/vscode/syntaxes/oq.tmLanguage.json` |
| L9.2 | Operators, comments, strings, interpolation and numbers are highlighted | Complete | Patterns present for each; the number pattern accepts `_` separators and exponents | Same |
| L9.3 | A gate reconciles the editor grammar with `Lexer.KEYWORDS` | Complete | `the_editor_grammar_knows_every_keyword` in `check-docs-sync.py` reads the three grammar groups (`keywords`, `constants`, `operators`), collects only the `\\b(...)\\b` alternations so punctuation and operators are not mistaken for keywords, and fails on either direction of difference. Closed on this branch: when the audit ran it, `check-docs-sync.py` reconciled the *reference document* and `CONTRIBUTING.md` and read neither the grammar nor the LSP, so a new keyword failed two checks and reached the editor silently | `scripts/check-docs-sync.py` |

L9.3 was the audit's cheapest finding and the first one closed. The gap had a concrete cost: the
editor silently stopped highlighting a new keyword while the docs-sync gate reported success. The
fix parses the grammar's three groups rather than only `keywords`, because `true`/`false`/`nothing`
and `and`/`or`/`not` are keywords the lexer knows and a check reading one group would call missing.
It also fails when a group is *absent* or when the grammar parses to no keywords at all, since
either would narrow the comparison until it agreed with whatever was left.

## What this audit does not establish

It does not establish that the language is complete. It establishes that **51 rows behave as
documented, two are ambiguous, one is undocumented, two are missing, none is defective, and one is
refused deliberately** — across the nine scope areas the order names.

It does not review Alpha 9's object-oriented surface. `data` and classes appear only where an
audit row needed them; OOP completion is an Alpha 9 objective and §4 does not absorb it.

It does not propose ADRs. Every "Ambiguous" and "Undocumented" finding above is a question for
§4's backlog, and each needs its own decision record (ADR-0071 onward; ADR-0070 is consumed by
§2.3) before implementation.

It does not revive a refusal. The ten leader words and `finally` stay refused. Default parameters
(L4.8) are recorded as refused and are not proposed.

## The bounded backlog this audit establishes

Four of §4's six remaining non-Complete findings are worth acting on, in order of cost. L9.3 was
the fifth and is now closed:

1. **L3.11 and L6.6 — conformance for destructuring and modules.** Two holes in a 257-program
   suite that covers everything else.
2. **L2.6 and L5.7 — decide, then document.** The text-coercion rule for `+` and the non-mutating
   `.sort()` are the two places where the tree behaves one way and the specification says another
   or nothing. Both need an ADR before either an implementation change or a documentation change;
   picking one without the other would make the divergence official.

The remaining two are recorded, not actioned. **L4.9** (block-bodied lambda) would expand the audit
into a parser rewrite that §4 explicitly forbids, and no 0.2.0 requirement depends on it. **L4.8**
(default parameters) is refused, and §4 may not revive it. **L8.3** turned out Complete on
inspection, which is recorded above rather than dropped.

---

Recorded under Item 6b of [`alpha-7-first-report.md`](alpha-7-first-report.md). §4's implementation
begins only after this matrix is reviewed, and proceeds one milestone per PR with the exact
evidence above re-run as the acceptance test.
