# 0018 — Index semantics are uniform across collection operations

Status: accepted (2026-11-10, Alpha 4 Phase 9)

## Context

Oqthara has four ways to address a position in a list: `l[i]`, `.get(i)`, `insert(i, x)`
and `remove(i)`. Probing all of them against the same three inputs — a valid index, a
negative index, and an out-of-range index — produced six different behaviours:

```
l = [1, 2, 3]

l[99]            error: index 99 is out of range for a list of length 3   (exit 1)
l[-1]            3
l.get(99)        error: index 99 is out of range for a list of length 3   (exit 1)
l.get(-1)        3
l.insert(99, 0)  internal error: IndexOutOfBoundsException: Index: 99     (exit 70)
l.insert(-1, 0)  internal error: IndexOutOfBoundsException: Index: -1     (exit 70)
l.remove(99)     nothing, list unchanged                                  (exit 0)
l.remove(-1)     nothing, list unchanged                                  (exit 0)
```

Three separate problems, and only one of them is cosmetic:

1. **`insert` crashes.** An out-of-range index reaches `java.util.ArrayList.add`, which
   throws `IndexOutOfBoundsException`. `EmbeddedMain.renderFailure` classifies every
   `RuntimeException` that is not an `OqError` as an Oqthara bug, so the program exits 70
   with `This is a bug in Oqthara. Please report it.` A program cannot catch the error,
   because `OqError` is the only catchable type. A user mistake is being reported to the
   user as a defect in the language.
2. **`remove` lies about failure.** `remove(99)` on a three-element list returns `nothing`
   and removes nothing. There is no way for a caller to distinguish "removed the value at
   index 99" from "did nothing". Negative indexes, which work everywhere else, also do
   nothing instead of counting from the end.
3. **The rules are not shared.** `l[i]` and `.get(i)` both accept negative indexes and both
   raise a located error; `insert` accepts neither and crashes; `remove` accepts negatives
   as a silent no-op. A reader must learn four rules to address one list.

`take` and `skip` clamp rather than raise, and `subList` clamps both bounds. That is a
deliberate and documented choice for those three: they are range operations whose argument
is a count, not a position, and clamping is what Python and most range APIs do. They are
correct and this ADR does not change them.

## Decision

**An index argument is a position. Positions are checked, negatives count from the end,
and a position that does not exist is a located error.**

Applied to `l[i]`, `.get(i)`, `.insert(i, x)` and `.remove(i)` alike:

| Input on `[1, 2, 3]` | Result |
|---|---|
| `l[1]`, `.get(1)` | `2` |
| `l[-1]`, `.get(-1)` | `3` |
| `l[-3]`, `.get(-3)` | `1` |
| `l[3]`, `l[-4]`, `.get(3)` | error: index 3 is out of range for a list of length 3 |
| `.remove(1)` | removes and returns `2`; the list becomes `[1, 3]` |
| `.remove(-1)` | removes and returns `3` |
| `.remove(3)` | error: index 3 is out of range for a list of length 3 |
| `.insert(1, 9)` | `[1, 9, 2, 3]` |
| `.insert(-1, 9)` | `[1, 2, 9, 3]` — before the last element |
| `.insert(3, 9)` | `[1, 2, 3, 9]` — appending at `length` is allowed |
| `.insert(4, 9)` | error: index 4 is out of range for a list of length 3 |

The rule has three parts, and each needs a reason because each is a choice:

**Negatives count from the end, for every index, including `insert`.** A list already
offers `l[-1]`, so a negative index has exactly one meaning in the language. `insert`
rejecting it would make the same expression mean different things in two adjacent methods.
Python permits it (`l.insert(-1, x)` inserts before the last element), and containment of
surprise favours matching.

**`insert` accepts `length` but not `length + 1`.** Inserting at `length` is appending, and
`.push` exists precisely to do that at the end, so refusing it would make `insert` the one
method that cannot express the position after the last element. Any larger index has no
position to name. Python uses `list.insert(i, x)` with silent clamping (`l.insert(99, x)`
appends), which is rejected here: an index that does not name a position should say so, and
silent clamping is how the current `remove` bug hides.

**Removal by value is `.remove_value(x)`, never `.remove(x)`.** Both already exist.
`remove` takes a position and `remove_value` takes a value, which is the split the
documentation already states. The old code accepted a non-numeric argument to `remove` and
fell back to a value search, so `l.remove("x")` was a second, undocumented spelling of
`remove_value`, and `l.remove(1)` was ambiguous: index 1 or the value 1? It was the index,
but nothing said so. `remove` now requires a number; a non-number gets a located error
naming `remove_value`.

**Error shape is the existing one.** Out-of-range indexes already have a message, a
position, and a hint (`Valid indexes are 0 to 2, or negative from the end.`) from
`Values.index`. All four operations use it, so a reader who has seen one error has seen all
of them, and no path can reach a JDK exception.

## Rejected alternatives

**Clamp everywhere, as Python does.** `insert(99, x)` appending and `remove(99)` doing
nothing are both defensible, and `take`/`skip`/`subList` already clamp. But clamping on a
*mutating* operation is the worst case: the caller believes a specific position changed
when a different one did. `remove(99)` removing the last element would be silently wrong
rather than loudly refused. Range operations clamp because a count past the end has an
obvious meaning; a position past the end does not.

**Wrap `IndexOutOfBoundsException` into `OqError` at the boundary.** This makes the crash
a clean error without deciding anything, and it was the smallest possible fix. It was
rejected because it leaves three different rules in place and because catching the JDK
exception accepts that the runtime can be driven into one at all. The check belongs before
the call.

**Make `remove` raise on a missing value instead of returning `nothing`.** `remove_value`
returns `nothing` when the value is absent, and the map's `remove` does too, so changing
only the list would create the same inconsistency this ADR removes. Absent-value and
absent-position are different questions: the first is "is this value here", answered by
`find_index` when the caller cares, and the second is "does this list have 99 elements",
which is always a mistake.

## Consequences

**Fixed.**

- `insert` out of range raises a located `OqError` and exits 1 instead of exiting 70 with
  `This is a bug in Oqthara`.
- `insert` accepts negative indexes.
- `remove` accepts negative indexes.
- `remove` out of range raises instead of returning `nothing` and doing nothing.
- `remove` with a non-number raises and names `remove_value`.
- All four index operations share one rule and one error message.

**Unchanged and now documented as deliberate.**

- `take(n)`, `skip(n)` and `subList(from, to)` clamp. Their argument is a count or a range
  bound, not a position.
- `remove_value(x)` returns `nothing` when the value is absent.
- `.pop()` on an empty list raises; `.pop()` cannot be given an index.

**Bounded.**

The `internal error` path still exists for any `RuntimeException` that is not an `OqError`.
This ADR removes the two ways to reach it from list indexing; it does not make the runtime
provably unreachable. `EmbeddedMain.renderFailure` is the correct place for the
classification, and it is right to report an unexpected host exception as a defect — the
fault was the two call sites that made an expected one possible.

Python reference: [Sequence types — list](https://docs.python.org/3/library/stdtypes.html#mutable-sequence-types)
and [Common sequence operations](https://docs.python.org/3/library/stdtypes.html#common-sequence-operations),
read 2026-11-10 against CPython 3.13 documentation. The negative-index rule is followed;
Python's clamping for `list.insert` and its `IndexError`-versus-`ValueError` split for
`remove` are deliberately not followed, for the reasons above. This is not a compatibility
claim.

## Verification

`LanguageFeatureTest` pins each row of the table above as a pairing of a valid case and a
refused case, so a regression in either direction fails the build:

- `anIndexOfZeroRemovesTheFirstElement`
- `aNegativeIndexOfMinusOneRemovesTheLastElement`
- `removePastTheEndIsRefusedRatherThanIgnored`
- `removeNeedsAnIndexAndNamesRemoveValueForAValue`
- `insertAcceptsTheLengthAndNegativeIndexes`
- `insertPastTheLengthIsRefusedWithoutAnInternalError`
- `anOutOfRangeIndexReadIsStillRefused`

The last one is a guard, not a new behaviour: it asserts `.get(99)` keeps the message all
four operations now share.
