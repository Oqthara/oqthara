# ADR-0063 — The relational operators follow IEEE order, and collections keep a total order

**Status:** Accepted
**Date:** 2026-11-16
**Related:** ADR-0051 (non-finite numbers and transcendentals), ADR-0017 (equality and
hashing), ADR-0060 (keyed sorting), ADR-0041 (ordered search), the requirement matrix §60

## Context

[ADR-0051](0051-non-finite-numbers-and-transcendentals.md) decided that domain errors raise
and that overflow, underflow and the pole of a fractional power produce non-finite values
silently. That decision makes `NaN` and the infinities reachable from ordinary source, which
raises a question the ADR did not answer: **how do the relational operators treat them?**

Nothing answered it, and the behaviour that existed was self-contradictory. Probing the built
CLI found that `==` and `<` disagreed about the same pair of values:

| Expression | Before | IEEE-754 |
|---|---|---|
| `nan == nan` | `false` | correct |
| `nan != nan` | `true` | correct |
| `nan < 1.0` | `false` | correct |
| `nan <= 1.0` | `false` | correct |
| **`nan > 1.0`** | **`true`** | **wrong** |
| **`nan >= 1.0`** | **`true`** | **wrong** |
| `nan < nan` | `false` | correct |
| **`nan <= nan`** | **`true`** | **wrong** |
| `nan > nan` | `false` | correct |
| **`nan >= nan`** | **`true`** | **wrong** |
| **`1.0 < nan`** | **`true`** | **wrong** |
| **`1.0 <= nan`** | **`true`** | **wrong** |
| `1.0 > nan` | `false` | correct |
| `1.0 >= nan` | `false` | correct |
| `nan <= 2.0` | `false` | correct |
| **`nan >= 2.0`** | **`true`** | **wrong** |

Seven of these sixteen were wrong, and the wrong ones were not uniformly wrong: every one of the
four operators was wrong in at least one operand order. `nan > 1.0` was true while `nan == 1.0`
was false, so the language said a value was greater than another while denying the two were
unequal. `nan <= nan` was true while `nan < nan` was false, so `<=` was not `(<) or (==)`.

### The same cause produced a second contradiction, with no `NaN` involved

`Double.compare` is a total order, so it must distinguish `-0.0` from `0.0` and does: it ranks
`-0.0` below `0.0`. Java's `==` on two doubles does not, and neither does this language's,
because [ADR-0017](0017-equality-and-hashing.md) decided that `-0.0 == 0`. Feeding the total
order to the operators therefore made the language assert both of these at once:

| Expression | Before | IEEE-754 |
|---|---|---|
| `-0.0 < 0.0` | `true` | **wrong** |
| `-0.0 <= 0.0` | `true` | correct |
| `-0.0 > 0.0` | `false` | correct |
| **`-0.0 >= 0.0`** | **`false`** | **wrong** |
| `-0.0 == 0.0` | `true` | correct |

`-0.0 < 0.0` and `-0.0 == 0.0` were true together, so `<` contradicted `==` here too. This is
worth recording separately because it shows the defect was never about `NaN`: `NaN` was only
where it became easy to notice. One relation was serving two incompatible purposes, and every
pair where the two orders disagree was affected. The fix repairs both without naming either.

### The cause: one function serving two different orders

The relational operators consumed `Values.compare`, which for two numbers is
`Double.compare`. That is a **total order**, deliberately: it places `NaN` above every number
so that `NaN`, `-0.0` and `0.0` all have a defined position and a sort is reproducible. It is
the right relation for a collection and the wrong relation for an operator.

Two independent code paths consumed it, so the defect was in neither backend alone:

- the tree-walking interpreter's `applyBinary` (`Interpreter.java`), mapping
  `LT`/`LE`/`GT`/`GE` to `compare(...) < 0`, `<= 0`, `> 0`, `>= 0`;
- the bytecode backend's `Arithmetic.less`/`lessOrEqual`/`greater`/`greaterOrEqual`, reached
  from `BytecodeCompiler.emitBinary`, which maps all twelve operators to `Arithmetic` names.

Both now agree, because both were changed; the conformance case runs on both.

### What cannot change

`Values.compare` is load-bearing for six other call sites that genuinely need every pair to
order: `sort`, `sorted`, `min`/`max` through `extremeOf`, `clamp`, `list_sort`, and the
`Statistics` heap. Changing `compare` to return "unordered" would break `sort`, which is why
the fix is a second relation rather than a changed first one. This was verified rather than
assumed: six consumers were probed after the change and all still order `NaN` — `sort`,
`sorted`, `min`, `max`, `clamp`, `list_sort`, plus `heap_push`/`heap_pop` (see Consequences).

## Decision

### 1. The relational operators are a partial order defined from `<` and `==`

`Values.lessThan(a, b, span)` is the single definition of `<`, and `Values.greaterThan` is the
same relation with the operands exchanged — spelled out per pair rather than delegating as
`lessThan(b, a)`, because the exchange is where a diagnostic naming the types in reverse order
comes from. `Values.lessOrEqual` and `Values.greaterOrEqual` are the single definitions of the
inclusive pair, so neither backend can build them differently:

```
a <= b  is  a < b  or  a == b
a >= b  is  a > b  or  a == b
```

Defining them that way rather than as separate predicates makes `<=` consistent with `==` **by
construction**: no pair can be `<=` while being neither `<` nor `==`. The alternative —
`compare(...) <= 0` — is exactly how the defect arose, because a total order answers every
comparison even where the answer is meaningless.

For two numbers `lessThan` is `asDouble(a) < asDouble(b)`, which is Java's IEEE-754 double
comparison: every comparison involving `NaN` is false, and `-0.0` equals `0.0`. Text uses
`compareTo`, booleans use `Boolean.compare`, and lists and tuples order lexicographically by
the *same* relation — the first unequal pair decides, and a length difference decides only
after a common prefix is equal. A list therefore inherits the partial order rather than
bypassing it: `[nan] < [nan]` is false, and so is `[1.0, nan] < [1.0, 1.0]`.

An unorderable pair still raises the same message it always did, and still names its types in
source order (`1 > "a"` says `cannot compare number with text`), because the fallback calls
`compare` in the original argument order and keeps the refusal and its hint in one place.

### 2. `Values.compare` stays a total order, and says so

`compare` is unchanged for numbers. Its javadoc now states that it is the collection order and
explicitly **not** the definition of `<`, naming `NaN` and signed zero as the two places where
they differ. The `Statistics` heap javadoc claimed the heap and the operator "agree by
construction"; that was true before this ADR and is false after it, so it was corrected to say
the heap agrees with `sort` and `min` — which is the property the heap actually needs.

### 3. `min`, `max` and `clamp` keep the total order, and are not NaN-aware

They return order-dependent answers for `NaN` — `min(nan, 1.0)` is `1.0` while
`max(nan, 1.0)` is `NaN` — because they are total-order functions and `NaN` sorts last. This
ADR **blesses that** rather than changing it, for three reasons:

- The alternative is not obviously better. Python's `min`/`max` happen to propagate `NaN`
  because they compare pairwise and short-circuit, which is an implementation artifact, not a
  contract; the language does not promise either behaviour today.
- They are collection-shaped functions, and the rule "collections order, operators do not" is
  one sentence that a reader can hold. Making them exceptional would need a second sentence
  explaining which builtins are special.
- A caller who cares about `NaN` has the test ADR-0051 decision 2 already provides: `n != n`.
  Filter first, then aggregate.

This is recorded because it is a decision, not a defect. `min`/`max`/`clamp` are probed after
the change so the behaviour cannot drift silently.

### 4. The policy is reproducible from a script

`scripts/probe-non-finite.py` runs 68 cases across seven groups: silently produced non-finite
values, domain errors that raise, the sixteen `NaN` comparisons, the nine signed-zero
comparisons, the eight total-order consumers, the six `NaN` key and membership cases, and ten
ordinary comparisons. `--check` exits non-zero if any disagrees. The numbers quoted in this ADR
come from that script rather than being transcribed from a one-off session, and the counts below
are its output.

Against `origin/main` (the commit before this change) the script reports **9 of 16** `NaN`
comparisons correct, so 7 are wrong. And **5 of 9** signed-zero comparisons are correct, so 4 are wrong.
All five other groups pass, so the failures are exactly the seven `NaN` rows and the four
signed-zero rows above and nothing else. After the change it reports **16 of 16** and **9 of 9**,
with every other group unchanged at 8/8, 11/11, 8/8, 6/6 and 10/10. The isolation matters: it
shows the change touched the operators and nothing else.

### 5. `NaN` remains the only value that identifies itself by inequality

ADR-0051 decision 2 stands: `n != n` is the test for `NaN`. This ADR removes the contradiction
that made it unreliable rather than replacing it. No `is_nan` builtin was added; there is one
obvious way, and it is already documented.

## Consequences

- All sixteen `NaN` relational outcomes in the probe are now the IEEE ones, in both backends;
  seven of them were wrong before.
- `-0.0` and `0.0` compare as equal under all four relational operators, so `<` no longer
  contradicts `==` for signed zero either.
- `<=` and `>=` are consistent with `==` for every pair the ordering accepts: any pair that
  raises for `<` still raises, and any pair that orders satisfies `a <= b` exactly when
  `a < b or a == b`. The exception the operators inherit from `<` is `nothing`, which
  `nothing == nothing` calls equal yet `<` refuses to place.
- `sort`, `sorted`, `min`, `max`, `clamp`, `list_sort` and the heap are unchanged. Verified by
  the eight total-order rows in the probe and by the existing standard-library tests.
- `conformance/245-nan-is-unordered.oq` pins all sixteen `NaN` comparison outcomes in the probe
  plus all five signed-zero cases, and runs on both backends because it compiles (208 of 217
  executable programs now).
- The requirement matrix row 59 (division by zero) had carried a stale gap: it claimed
  `sqrt(-1)` "returns `NaN` without a word … remains unresolved". It raises
  `sqrt() has no real result for -1.0`, and has since ADR-0051. That is a **separate
  documentation correction** bundled into this change rather than a consequence of it — the
  behaviour was already right and only the matrix was wrong. Row 59 was corrected and rows
  60–63 were added for the ordering rule, the total order, signed zero and `NaN` as a key.
- One behaviour is now inconsistent on purpose and is worth knowing: `nan != nan` is true, so
  `length(set([nan, nan]))` is 2. That follows from ADR-0017's `double` equality, not from this
  ADR, and it is the price of `n != n` being the `NaN` test. The same equality makes `NaN`
  unusable as a key: `m[n] = 1` stores an entry that `m[n]` cannot read back, and
  `n in set([n])` is false. This predates the change and is untouched by it, but it sits in the
  paragraph the ordering rule lives in and was previously undocumented, so `docs/spec/language.md`
  now says so and the probe pins all six cases.
