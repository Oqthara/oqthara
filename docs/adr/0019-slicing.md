# ADR-0019 — Slicing

Status: accepted

## Context

The language has no slice syntax. `xs[1:3]` fails to parse:

```
error: expected ']' to close the index
  --> probe.oq:1:11
   1 | print [1,2,3][0:2]
```

The only way to take a sub-sequence is `.slice(from, to)` or `.subList(from, to)`, and both
are wrong in a way that hides: `clampIndex` maps every negative bound to `0`.

| Probe on `[1, 2, 3, 4, 5]` | Result | Expected |
|---|---|---|
| `.slice(-2, 5)` | `[1, 2, 3, 4, 5]` | `[4, 5]` |
| `.slice(0, -1)` | `[]` | `[1, 2, 3, 4]` |
| `.slice(-2)` | `[1, 2, 3, 4, 5]` | `[4, 5]` |

This is the same defect class as [ADR-0018](0018-index-semantics.md) one layer up. There, an
index silently named nothing; here, a bound silently names the wrong position. A negative
bound is not a mistake to clamp, because the language already gave `-1` a meaning: the last
element. `xs[-2:]` and `xs[length()-2:]` should not differ.

The `clampIndex` comment says the clamping is deliberate: it exists so a bound outside
`[0, size]` cannot reach `java.util.List.subList`, which throws
`IndexOutOfBoundsException` and would surface as a defect in the language. That reasoning is
correct and is preserved here. The fault is not the clamping of *large* bounds, which is
harmless; it is that negative bounds were folded into the same rule.

## Decision

Add slice syntax `xs[from:to]` and `xs[from:to:step]`, and fix `.slice()`/`.subList()` to
agree with it.

### The rule

**A slice bound is a position, resolved by the same rule as an index, then clamped.** A
negative bound counts from the end. After that resolution the bound is clamped to
`[0, size]`, so `xs[0:999]` is the whole list rather than an error. This is deliberately
different from ADR-0018, and the difference is principled:

- an *index* selects exactly one element, so a position that names nothing has no sensible
  reading and must be refused;
- a *bound* delimits a range, so a bound past the end has an obvious reading — the end — and
  refusing it would make `xs[i:i+10]` fail depending on the length of `xs`, which is the
  idiom that makes slicing useful at all.

This is also what Python does, and here the reasoning coincides: `s[i:j]` is clamped,
`s[i]` is not.

**An omitted bound means the corresponding end.** `xs[:3]` is `xs[0:3]`, `xs[2:]` is
`xs[2:length()]`, and `xs[:]` is a copy of the whole list. `xs[:]` is a copy, never the same
object: it is the documented shallow-copy spelling, and a reader who writes it is usually
about to mutate the result.

**Step defaults to `1`.** Negative steps are supported and reverse the traversal, so
`xs[::-1]` is the list reversed and `xs[::-2]` takes every second element from the end.
A step of `0` is an error: it names no traversal. Reversing with a negative step also swaps
the meaning of the two bounds, because the range is traversed backwards — `xs[4:1:-1]` is
`[5, 4, 3]`. Bounds are not swapped for the caller; the same two values mean what they mean
in the direction of travel.

**The result is always a new value**, and slicing preserves the family of the thing sliced:
a list slice is a list, a tuple slice is a tuple, a text slice is text, and a range slice is a
list because a sub-range with a step is not a range. Preserving the family is what the
existing `subList` on a tuple already does, and immutability is part of a tuple's value: a
caller who sliced one to inspect a run should not be handed a mutable copy by accident. Text
stays text because a text slice is a substring, and returning a list of one-character texts
would make `"abc"[1:]` useless.

**Slicing nothing is an error**, consistent with indexing it.

**Slicing is not assignable.** `xs[1:3] = ...` stays a syntax error; only an index assignment
is supported. Slice assignment has replacement-length semantics that interact with every
other rule here, and nothing needs it yet.

### What this does not change

`take(n)`, `skip(n)` and `subList(from, to)` keep clamping, and `.slice()` now resolves
negatives the same way the syntax does. `.subList()` keeps its name and behaviour as the
non-negative-only spelled-out form; both remain because existing programs call them and
neither is wrong once negatives are resolved rather than folded to `0`.

## Rejected alternatives

**Make `xs[0:999]` an error.** Consistent with ADR-0018, and rejected for the reason above:
it breaks `xs[i:i+n]`, which is the main reason to have slicing.

**Wrap Python's semantics for negative step and swapped bounds wholesale.** Python's rules
here are subtle enough that its own documentation needs a paragraph of examples, and
`xs[::-1]` reading as "reverse" is the only part users rely on. The rule above is stated in
one sentence — bounds are positions, then clamped, and the step says which way to travel —
and it agrees with Python on every case a user is likely to write. Where it could differ,
the difference is that this version refuses nothing that has an obvious meaning.

**Make a slice a view, like `java.util.List.subList`.** A view would alias the original, so
mutating it would mutate the source, and the runtime's list identity is used for ADR-0017
hashing. A copy is the only version whose behaviour is easy to state.

**Introduce a separate slice object and a `slice()` builtin as Python does.** Unnecessary:
`xs[1:3]` has no ambiguity to resolve at runtime.

## Consequences

**Fixed.**

- `xs[1:3]`, `xs[:3]`, `xs[2:]`, `xs[:]`, `xs[::2]`, `xs[::-1]` all parse and run.
- `.slice(-2, 5)` returns `[4, 5]` instead of the whole list.
- `.slice(0, -1)` returns `[1, 2, 3, 4]` instead of `[]`.

**Bounded.**

Errors report the offending bound and the length, like ADR-0018's, with one exception: a
step of `0` reports the step, because no bound is at fault.

**New syntax cost.** `[` now begins either an index or a slice, and the parser decides after
reading the first expression by looking for `:`. `xs[a:b]` and `xs[a]` therefore diverge one
token in, which the existing single-token lookahead already supports.

## Verification

Measured against `[1, 2, 3, 4, 5]` after implementation:

| Expression | Result |
|---|---|
| `xs[1:3]` | `[2, 3]` |
| `xs[:3]` | `[1, 2, 3]` |
| `xs[2:]` | `[3, 4, 5]` |
| `xs[:]` | `[1, 2, 3, 4, 5]` — a copy |
| `xs[-2:]` | `[4, 5]` |
| `xs[0:-1]` | `[1, 2, 3, 4]` |
| `xs[0:999]` | `[1, 2, 3, 4, 5]` — clamped, not an error |
| `xs[3:1]` | `[]` — an inverted range is empty |
| `xs[::2]` | `[1, 3, 5]` |
| `xs[::-1]` | `[5, 4, 3, 2, 1]` |
| `xs[::-3]` | `[5, 2]` |
| `xs[4:1:-1]` | `[5, 4, 3]` |
| `xs[::0]` | error: `a slice step cannot be 0` |
| `xs["a":2]` | error: `a slice start must be a whole number, got text` |
| `"abcdef"[1:3]` | `bc` — text, not a list |
| `"abc"[::-1]` | `cba` |
| `t[0:2]` on `(1,2,3)` | `(1, 2)` — still a tuple |
| `range(0,10)[2:5]` | `[2, 3, 4]` |
| `[][:]`, `[][::-1]` | `[]` |
| `nothing[0:2]` | error: `cannot slice nothing` |
| `{"a":1}[0:1]` | error: `cannot slice map` |
| `xs[0:1] = [9]` | error: `cannot assign to this expression` |

`LanguageFeatureTest` pins these:
`aSliceTakesARangeAndOmittingABoundMeansThatEnd`,
`aNegativeSliceBoundCountsFromTheEnd`,
`aStepWalksTheSliceAndANegativeStepReversesIt`,
`anOutOfRangeSliceBoundIsClampedButAZeroStepIsRefused`,
`aTextSliceIsTextAndATupleSliceIsAList`,
`theSliceMethodResolvesNegativesLikeTheSyntax`.

Both backends produce byte-identical output on the slice probes: the same program run
through the interpreter and compiled to a jar via `oq build`.

Python reference: [Common sequence operations](https://docs.python.org/3/library/stdtypes.html#common-sequence-operations)
and the [slice objects](https://docs.python.org/3/library/functions.html#slice) section,
read 2026-11-10 against CPython 3.13 documentation. Clamping of out-of-range bounds and the
meaning of negative bounds are followed; slice assignment and the `slice` type are not
adopted. This is not a compatibility claim.
