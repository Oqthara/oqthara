# ADR-0026 — Range membership follows the walk, not the bounds

- **Status:** accepted
- **Date:** 2026-11-11
- **Supersedes:** nothing
- **Related:** ADR-0020 (iterator protocol), ADR-0023 (collection protocols)
- **Directive:** Alpha 4 Master Execution Directive §9.7 (collection acceptance criteria, edge cases)

## Context

A `range` is an arithmetic progression: it has a start, an end, a direction and a step.
Membership was decided by comparing the item against the two ends alone, so the step and
the direction were ignored. Every strided or downward range therefore answered "is this
between the ends?" when the question was "does the walk reach this number?".

Measured on `main` at `fcb2741`, with `oq run`:

| Program | Before | After | The walk |
| --- | --- | --- | --- |
| `2 in range(0, 10, 3)` | `true` | `false` | `0, 3, 6, 9` |
| `1 in range(0, 10, 3)` | `true` | `false` | `0, 3, 6, 9` |
| `3 in range(0, 10, 3)` | `true` | `true` | `0, 3, 6, 9` |
| `3 in range(5, 1, -1)` | `false` | `true` | `5, 4, 3, 2` |
| `5 in range(5, 1, -1)` | `false` | `true` | `5, 4, 3, 2` |
| `9 in range(5, 1, -1)` | `false` | `false` | `5, 4, 3, 2` |
| `range(0, 10, 3).contains(1)` | `true` | `false` | `0, 3, 6, 9` |
| `range(0, 10, 3).contains(6)` | `true` | `true` | `0, 3, 6, 9` |
| `range(5, 1, -1).contains(3)` | `false` | `true` | `5, 4, 3, 2` |

The forward strided rows are wrong answers, not merely surprising ones: `2 in range(0, 10, 3)`
returns `true` for a number the range does not contain, while `range(0, 10, 3).to_list()`
prints `[0, 3, 6, 9]`. The downward rows are the same defect seen from the other side: the
bounds test assumed `end > start`, so every downward range contained nothing at all.

Two implementations had drifted apart, which is why the two spellings disagreed in different
ways:

- `Values.contains` (the `in` operator) compared against the ends, required a whole-number
  decimal, and assumed a forward walk.
- `MethodResolverImpl`'s `range.contains` method compared against the ends and required a
  `Long`, so `r.contains(3.0)` was `false` while `3.0 in r` was `true`.

## Decision

**`OqRange.contains(long)` is the single answer, and both spellings call it.**

The predicate is "the walk reaches this number":

1. Reject anything outside the walked interval. The interval is bounded above by `high()`
   for a forward step and below by it for a backward step, so the check respects direction
   rather than assuming one.
2. Require the distance from `start` to be a whole number of steps. Once the bounds check has
   passed, `value - start` and `step` share a sign, so `(value - start) % step == 0` is the
   reachability test and needs no absolute value.

Both properties are constant-time, so a range that is too large to materialise still answers
membership without allocating: `1999999999 in range(0, 2000000000)` is immediate, and so is
the same question on a two-billion-element range stepping by 1000.

The `in` operator keeps its decimal coercion — a whole-valued decimal is the same number as
the integer it names, which is the rule the rest of the language already follows — and then
converts to `long` and delegates. The `.contains` method accepts the same items for the same
reason, so the two spellings answer identically for every input. Neither holds the
reachability rule itself; both call `OqRange.contains`, so the rule has one implementation.

## What this costs

Nothing measurable. The predicate is two comparisons and one remainder, where the old one
was two comparisons, so the shape of the work is unchanged. The large-range check above
confirms the O(1) property is preserved: a materialising fix (`value in r.to_list()`) would
have been correct but would exhaust memory on the ranges the language documents as cheap.

## Rejected alternatives

**Materialise the range and use list membership.** `r.to_list().contains(value)` is the
obvious correct implementation and is what the property test compares against. It is
unusable: `range(0, 2000000000)` is a legal value, ADR-0023 documents `length()` of it, and
a membership question must not allocate two billion boxed longs. The test keeps it as the
oracle; the implementation must not be it.

**Fix only the `in` operator.** The `.contains` method is documented in the language
reference as part of the range method surface, appears in the conformance corpus, and had
its own copy of the defect. Fixing one spelling would have left the other wrong, and the
two disagreeing is how the defect would be found again.

**Keep two implementations and test that they agree.** A shared predicate is a stronger
guarantee than a test that two copies happen to match, and there is no reason for the
copies to exist.

**Require an integer in both spellings.** That would make `3.0 in range(0, 5)` false,
contradicting `3.0 == 3` and the language's existing rule that equality crosses the
integer/decimal boundary. It would also leave `.contains` refusing an item that `in` accepts,
which is the disagreement this ADR removes.

## Verification

`ValuesTest` gains five tests. Four are named cases; the fifth is the property the defect
violated:

- `aStridedRangeExcludesNumbersBetweenItsSteps` — the `range(0, 10, 3)` rows, including
  bounds that must still be rejected.
- `aBackwardRangeHoldsOnlyTheNumbersItWalksDownTo` — `range(5, 1, -1)` and the strided
  downward `range(9, 1, -2)`, covering both exclusive and inclusive bounds.
- `membershipAgreesWithTheWalkItCanBeMaterialisedInto` — for ten `(start, end, step)` shapes,
  every candidate from -12 to 12 is checked against `to_list().contains(n)`. This is the
  statement "`in` answers yes exactly for the numbers the range walks", checked against the
  oracle rather than against a hand-written expectation.
- `anEmptyRangeHoldsNothing` — a forward range whose end is behind its start.
- `aFractionalOrNonNumericItemIsNeverInARange` — `3.5` and `"3"` are refused, `3.0` converts.

`conformance/226-range-membership-respects-step.oq` pins the observable behaviour on both
backends: forward strided, downward, and even-stride ranges, through `in` and `.contains`
alike. `verify-conformance.py` reports 0 divergences, so the interpreter and the compiled
backend agree on every line of it.

The executable corpus grew from 209 to 210 and the compiled count from 194 to 195;
`BASELINE` in `verify-conformance.py`, `BytecodeCompilerTest`, and the three claim sites in
`docs/spec/backend.md` were raised together, from this measurement.

The Python reference consulted was CPython 3.13 on 2026-11-11. CPython's `range.__contains__`
implements the same reachability test (`(item - start) % step == 0` after a bounds check)
rather than comparing bounds, which is the rule this ADR adopts.
