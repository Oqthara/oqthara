# ADR-0023 — Collection protocols: nine contracts, and where laziness stops

- **Status:** accepted
- **Date:** 2026-11-10
- **Supersedes:** nothing (extends ADR-0020)
- **Related:** ADR-0015 (type families), ADR-0016 (iteration snapshots), ADR-0017 (equality
  and hashing), ADR-0020 (iterator protocol), ADR-0021 (collection-family operations),
  ADR-0022 (hashability and frozenset)
- **Directive:** Alpha 4 Master Execution Directive §9.2 (protocols), §9.3 (operations),
  §9.4 (edge cases), §9.5 (JVM representation)

## Context

ADR-0020 named two protocols — iterable and iterator — because those were the two that
`for` needed. §9.2 asks for a coherent family covering iterable, iterator, collection,
sequence, mutable sequence, mapping, mutable mapping, set, mutable set, and the sized,
indexable and reversible abstractions, with eager/lazy, mutable/immutable, ordered/unordered
and hashable/unhashable all made explicit.

The families grew operations in ADR-0021 without a written statement of *which contract each
operation belongs to*. That gap is not cosmetic: it is how `frozenset` shipped in ADR-0022
with `union` returning a `frozenset` while `range.reverse()` returned a `list`, and how
`map` reads as the same idea as a Python generator while being eager.

This ADR states the contracts, fixes the naming, and records the family-preservation rule
that the recent bugs violated.

## What the implementation actually does

Measured against the built CLI, not assumed. Every row below was run.

| Family | `length` | index | `in` | `iter` | mutable | ordered | hashable |
|---|---|---|---|---|---|---|---|
| `list` | yes | yes | yes | yes | yes | insertion | no |
| `tuple` | yes | yes | yes | yes | no | position | if contents are |
| `map` | yes | no | yes (keys) | keys | yes | insertion | no |
| `set` | yes | no | yes | yes | yes | insertion | no |
| `frozenset` | yes | no | yes | yes | no | insertion | if contents are |
| `range` | yes | yes | yes | yes | no | counting | yes |
| `text` | yes | yes | yes (substring) | chars | no | position | yes |
| `iterator` | no | no | no | itself | position | walk order | identity |

Two entries deserve a note. `map` indexing is not an error — `m[0]` returns `nothing`,
because `[i]` on a map means "look up key `i`" and a missing key is `nothing`. And `in` on a
map tests **keys**, matching what `for` yields.

## Decision

### The nine contracts

Each is defined by the operations it guarantees. A value belongs to a contract by having the
operations, not by being declared — the same open dispatch ADR-0020 chose for `iter`.

| Contract | Guarantees | Members |
|---|---|---|
| **Iterable** | `iter() -> iterator`; may be walked repeatedly | all eight families |
| **Iterator** | `next()`, `has_next()`; consumed once | `iterator`, and any `data` with both |
| **Sized** | `length()` / `size()` | every family except `iterator` |
| **Sequence** | `length`, integer indexing, slicing, `in` | `list`, `tuple`, `text`, `range` |
| **Mutable sequence** | adds `push`/`insert`/`remove`/`pop`/`reverse`/`sort` | `list` |
| **Mapping** | `keys`, `values`, `entries`, `get`, `has` | `map` |
| **Mutable mapping** | adds `set`/`put`/`remove`/`clear`/`update`/`merge` | `map` |
| **Set** | `union`, `intersection`, `difference`, `symmetric_difference`, `issubset`, `issuperset` | `set`, `frozenset` |
| **Hashable** | usable as a key or member; equality-stable | `nothing`, numbers, booleans, `text`, `range`, tuples and frozensets of hashable values, functions by identity |

`Mapping` and `Mutable mapping` are one type (`map`) that satisfies both, exactly as Python's
`dict` is both. There is no read-only mapping wrapper: a caller who needs one holds a `map`
and does not mutate it. Inventing a second type would double the surface for a guarantee
Oqthara cannot enforce at the type level anyway.

`Reversible` is **not** a contract. ADR-0020 already rejected index-based reversal as a
protocol, because a live iterator cannot be reversed without buffering it. Reversal exists as
an operation (`reverse()`), which is where it belongs.

### The family-preservation rule

**An operation that cannot change a value's mutability must not change its family.**

This is the rule ADR-0022's `frozenset` work established and the rule `range.reverse()` broke.
Every operation is now classified:

| Kind | Rule | Examples |
|---|---|---|
| **Pure, family-preserving** | same family in, same family out | `clone`, `copy`, `union`, `intersection`, `difference`, `symmetric_difference` |
| **Pure, order-preserving where the family has an order** | same family if the receiver is an ordered sequence, else `list` | `reverse` |
| **Pure, widening** | narrower family to `list`, the general sequence | `to_list`, `items`, `sorted`, `list` |
| **Pure, converting** | explicitly names the target | `to_set`, `frozenset`, `set`, `tuple` |
| **Mutating** | returns the receiver or the removed element | `push`, `add`, `set`, `put`, `remove`, `clear`, `sort`, `update` |

`sorted()` returning a `list` even for a `text` or `set` receiver is deliberate and stays:
sorting is an ordering operation, and only a sequence has a defined order to preserve.

`reverse()` follows the same reasoning but lands differently per family, and the ADR records
what was measured rather than one tidy rule:

| Receiver | `reverse()` returns | Why |
|---|---|---|
| `list` | `list` | a reversed sequence is a sequence |
| `text` | `text` | a reversed string is a string |
| `range` | `range` | a reversed counting sequence is a counting sequence |
| `set` | `list` | a set has no order, so the reversed walk order cannot be a set |
| `map` | `list` of keys | same, for the key walk |
| `frozenset` | *no such method* | immutability is not the obstacle; it has no `reverse` at all |

Reversing a range is **O(1)**, not O(n). The endpoints come from the arithmetic
(`start + (size - 1) * step`), never from materialising the walk, so
`range(0, 2_000_000_000).reverse()` returns in 0.099s while the old list-returning form — and
`sorted()` on the same range — both exhaust memory. A range that large is legitimate; the
listing bug was also an allocation bug.

The new start is the last value the walk **reaches**, not `end`. Those differ whenever the
step does not divide the span, and using `end` is a silent wrong answer rather than a crash:
`range(0, 9, 3)` walks `0, 3, 6`, so it reverses to `6 to 0 by -3`. An earlier draft of this
fix used the `end`-based form and produced `[8, 5, 2]` — values the original range never
contained. The tests cover `range(0, 5, 2)`, `range(0, 9, 3)` and `range(0, 10, 3)` for that
reason.

Before this ADR only `range` was wrong: it returned a `list` where every other *ordered*
family kept its own type. Widening a `range` would also have crossed the mutability line, since
an immutable receiver would come back mutable. Restoring the family is the one behaviour
change in this ADR; `set` and `map` widening to `list` is pre-existing and left alone, because
there is no set-shaped answer to return.

### Laziness stops at the iterator

`map`, `filter`, `enumerate`, `zip` and `sorted` are **eager, list-returning**. There is no
lazy transformation pipeline, and none is added.

The reasoning is about what the language can promise. A lazy `map` over a collection would
have to return a `map` whose function has not run yet — which is an iterator, but an iterator
that can raise at any later `next()`. Oqthara has no way to defer that error to a place the
user can attribute, and ADR-0020's own rejection table already establishes that an uncatchable
error is not a protocol. An eager `map` runs its function in the statement that names it, so
an error carries that statement's position.

The two behaviours coexist deliberately, and the difference is the point:

| | `for` | `.iter()` | `map`/`filter` |
|---|---|---|---|
| Reads | a snapshot | the live collection | the live collection |
| Body runs | at the loop | at each `next()` | immediately |
| Result | nothing (side effects) | an `iterator` | a `list` |
| Safe to mutate during | yes | no | yes — the walk is over a snapshot taken by the operation |

`map`/`filter` snapshot before calling the function, so a callback that mutates the
collection cannot corrupt the walk. That is verified: `xs.map(fun(x) xs.push(9) end)`
returns the result for the original three elements and leaves `xs` unchanged by the walk.

A caller who wants laziness uses `.iter()` and advances it explicitly. A caller who wants a
pipeline materialises at each step, which for the collection sizes Oqthara targets is not a
cost worth a second evaluation model.

### Views are snapshots

`keys()`, `values()` and `entries()` return **new lists**, not live views. A view would be
the one place where two Oqthara values alias shared mutable state, and §9.5 warns about
exactly that ("shared mutable wrappers that surprise users"). The snapshot cost is one copy
of the key list, and `iter()` already provides the live walk for callers who want it.

### Determinism

Sets and maps are **insertion-ordered and deterministic**, backed by `LinkedHashMap` and
`LinkedHashSet`. This is a promise, not an accident: `set(["c","a","b"])` prints
`{"c", "a", "b"}` on every run and every platform. §9.4 asks for "deterministic ordering
guarantees where promised", so the promise is stated here and tested.

Unordered is the one thing Oqthara does not offer. A set that iterates differently between
runs makes reproducibility failures that users cannot debug, and the performance Oqthara
would gain is not needed at its target scale.

### Mutation during a live walk

An iterator reads the live collection, so mutating mid-walk is invalid. It is now an ordinary
error naming the problem and the fix, rather than a leaked
`ConcurrentModificationException` surfaced as "This is a bug in Oqthara" (§9.5). See
ADR-0020 for the wording and the reasoning.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| **A separate read-only `map` wrapper** | A second type to express a constraint Oqthara cannot enforce. The mutation methods would still exist at runtime; the wrapper would only move the error. `frozenset` is different because it is a *hashable* set, which is a real capability, not a restriction. |
| **Lazy `map`/`filter` returning iterators** | Errors would surface at a later `next()` with no attributable position, and the deferred work makes `repr` and error messages lie about what happened. |
| **Live `keys()`/`values()` views** | Introduces the only aliased mutable state in the language, and makes a `for` loop's snapshot guarantee (ADR-0016) inconsistent with the views. |
| **Unordered sets/maps** | Cheaper, but non-deterministic iteration across runs makes reproducibility failures undebuggable. |
| **A `Reversible` protocol** | Cannot be implemented by a live iterator without buffering, which contradicts ADR-0020. |
| **`bytes`** | Refused in ADR-0022: no `open()`, no binary consumer, so it would be a second string-like type used by nothing. |
| **Splitting `map` into mapping and mutable mapping types** | Python's `dict` is both, and every Oqthara program would pay a conversion for a distinction nothing consumes. |

## Consequences

- Every operation now has a stated kind, so a future addition can be classified rather than
  debated. The family-preservation rule is the checklist.
- `map`/`filter` stay eager. This is a **permanent** decision, revisited only if Oqthara gains
  a way to attribute deferred errors — which would require exception handling with positions,
  not a bigger iterator.
- The protocol set is nine contracts, all satisfiable by the existing eight families. No new
  type was added by this ADR except the one behaviour fix to `range.reverse()`.
- `iterator` being unhashable-by-identity-but-not-a-key needs care: iterators are hashable by
  identity (ADR-0017) and remain usable as keys, because their state is the walk position and
  using one as a key cannot make it unreachable.

## Verification

Each claim in this ADR was measured against the built CLI on 2026-11-10.

| Claim | Command | Result |
|---|---|---|
| `map` is eager and list-returning | `type_of([1,2].map(f))` | `list` |
| `map` snapshots before calling `f` | `xs=[1,2]; xs.map(fun(x) xs.push(99); return x*2 end)` | `[2, 4]` — the walk never sees the `99`s, which is what the snapshot buys |
| `filter`, `enumerate`, `zip` are eager | `type_of(...)` | `list` each |
| `keys()` is a snapshot | `k = m.keys(); m["b"] = 2; print k` | `["a"]` |
| sets are insertion-ordered | `set(["c","a","b"])` | `{"c", "a", "b"}` |
| maps are insertion-ordered | `{"z":1,"a":2}.keys()` | `["z", "a"]` |
| `range.reverse()` returned a `list` | `type_of(range(0,3).reverse())` | `list` — fixed to `range` by this ADR |
| `range.reverse()` is O(1) | `range(0, 2000000000).reverse()` | `1999999999 to 0 by -1` in 0.099s; `sorted()` on the same range runs out of memory |
| `range.reverse()` respects a step | `range(0,9,3).reverse().to_list()` | `[6, 3, 0]` — the last value reached, not `end` |
| `reverse()` is pure | `xs=[1,2,3]; xs.reverse(); print xs` | `[1, 2, 3]` |
| `reverse()` splits by family | `type_of(set([1,2]).reverse())`, `type_of("ab".reverse())` | `list`, `text` |
| `sorted()` widens to `list` | `type_of("cab".sorted())` | `list`, deliberate |
| iterator has no `length`/`index`/`in` | each raises | error, per ADR-0020 |
| mid-walk mutation is a user error | `xs.iter()` then `xs.add(1)` then `next()` | `the collection changed while this iterator was walking it` |

Python reference: CPython 3.13, consulted 2026-11-10, per §9.6.
