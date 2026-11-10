# ADR-0020 — The iteration protocol

- **Status:** accepted
- **Date:** 2026-11-10
- **Supersedes:** nothing
- **Related:** ADR-0016 (iteration snapshots), ADR-0018 (index semantics), ADR-0019 (slicing)
- **Directive:** Alpha 4 Master Execution Directive §9.1 (families), §9.2 (protocols), §9.3 (operations)

## Context

`for x in xs` works today for lists, sets, maps, ranges and text. There is no way to
iterate manually, and no way to write one function that consumes any of those five
families.

What exists today is not a protocol, and it is inconsistent in three ways.

**A `for` loop is eager and snapshotting, but nothing else is.** ADR-0016 made `for`
copy its subject before looping, so mutating a list inside the loop does not disturb the
walk:

```oq
xs = [1, 2, 3]
for x in xs
    if x == 1 then xs.add(99) end
    print x
end
// prints 1 2 3, and xs is [1, 2, 3, 99]
```

That is a deliberate decision and it stays. But `Values.iterable` performs the copy, and
it is the only path. A method that wants to walk a value has no cheaper option.

**The compiler and the interpreter iterate differently.** `for` lowers to
`Ir.IterNew` / `IterHasNext` / `IterNext`, which call
`Collections.iterator` → `Values.iterable(...).iterator()`. The interpreter's `for`
calls `Values.iterableList` directly. Both copy, so they agree — but they agree by
coincidence of two separate implementations rather than by a shared contract.

**There is no value the language calls an iterator.** `Collections.iterator` returns a
raw `java.util.Iterator`. If it escaped to a program, `typeName` would report
`java java.util.ArrayList$Itr`, `repr` would print a JVM identity string, and equality
would be Java identity. Nothing stops that today only because no expression can produce
one.

Python's `iter`/`next`/`StopIteration` is the reference point, but its model — a
protocol built on raising a specific exception — is a poor fit for a language that has
one error type and no `try`/`except` for exceptions. §9.2 asks for the protocols to be
defined before the operations. This ADR defines them.

## Decision

Iteration is described by **two protocols**, and one concrete runtime type implements the
second.

### The two protocols

| Protocol | Meaning | Members | Implemented by |
|---|---|---|---|
| **Iterable** | *Can produce* an iterator. Not consumed. | `iter() -> iterator` | list, set, map (keys), range, text, tuple, and any `data` with an `iter` method |
| **Iterator** | *Tracks* a position. Consumed once. | `next() -> value`, `has_next() -> boolean` | the `iterator` type, and anything with both methods |

The distinction is the one §9.2 asks for, and it has three consequences worth stating
because they are the ones that go wrong:

1. **An iterable can be walked many times; an iterator only once.** `[1,2,3].iter()`
   produces a fresh iterator each call. Reusing a spent iterator yields nothing.
2. **An iterable is not an iterator.** `xs.next()` must fail. Python makes this mistake
   easy because `iter(xs) is not xs` but both respond to `__next__` after `__iter__`; Oqthara
   refuses the call outright, so the error names the fix.
3. **`for` accepts both, and a `for` loop over an iterator consumes it.** That follows
   from the definitions rather than needing a separate rule.

### Exhaustion: `has_next`, not an exception

`next()` on a spent iterator is an **error**, not a sentinel value and not an exception
to be caught:

```
error: this iterator is exhausted
```

with the help line naming the fix (`Check has_next() before calling next().`).

The alternatives were considered and rejected in the table below. The decisive reason is
that Oqthara has no `try`/`except` for exceptions — `try` expressions (ADR-0014) only
capture values — so a "stop" exception would be uncatchable, and a `nothing` sentinel
would make `next()` unanswerable for a list that legitimately contains `nothing`.

`has_next()` is therefore part of the protocol, not an optional convenience. Python's
`StopIteration` and Java's `NoSuchElementException` both put the burden on the caller to
know in advance; making `has_next()` a protocol member states it in the type's surface.

### The concrete type

A new language type `iterator`, backed by `OqIterator`, wrapping a `java.util.Iterator`
plus the value it came from (for `repr` and for the error message).

| Surface | Behaviour |
|---|---|
| `type_of` | `iterator` |
| `repr` | `iterator(<source repr>)`, e.g. `iterator([1, 2, 3])` |
| `display` | the `repr` |
| `length` | error — an iterator has no length; it has a remainder |
| `in` | error — testing membership would consume it |
| indexing | error — an iterator is not indexable |
| slicing | error — `cannot slice iterator` |
| equality | **identity**, like a task; two iterators over the same list are not equal |
| usable as a set element or map key | yes, by identity — consistent with equality |
| `for` | consumes it |

Identity equality is the honest choice: an iterator *is* a position, so two of them over
the same list at different positions must not be equal. Structural equality would have to
compare positions, which leaks state a value type should not expose.

### Laziness

**`.iter()` is lazy; `for` is eager.** This is not an inconsistency, it is the point of
having both.

- `for` snapshots (ADR-0016) so that mutating the subject cannot corrupt the walk. It
  costs one copy and buys a guarantee.
- `.iter()` does **not** copy. It reads the live collection. That is what an iterator
  means, and it is what makes it usable for walking a large collection without
  duplicating it.

Both behaviours are stated here so that the difference is a decision rather than a bug
someone finds later. The consequence to document for users: do not mutate a collection
while iterating it with `.iter()`; use `for`, which is safe.

**What happens when you do it anyway.** The rule above says what not to do; it does not say
what the language does when you break it. Left to `java.util`, the walk fails fast
with `ConcurrentModificationException`, which surfaced as
`internal error: ConcurrentModificationException: null / This is a bug in Oqthara`. That
diagnosis is false — the user caused it — and it gave them nothing to act on. `next()` now
translates the host exception into an ordinary Oqthara error with a position and a hint:

```
error: the collection changed while this iterator was walking it
  help: An iterator reads the live collection, so it cannot survive a change.
        Use `for`, which walks a snapshot, or finish iterating first.
```

This is the same principle ADR-0016 applied to `for`, and §9.5 of the directive states it
directly: mutation diagnostics must remain Oqthara-level errors rather than leaking
inconsistent host exceptions. The `for` path is unaffected because it never sees a live
iterator.

### Where the protocol is checked

Dispatch is by method presence, matching how `data` methods already resolve:

- `iter()` on a value whose family is one of the five iterables → the family's iterator.
- `iter()` on a value with an `iter` method (a `data`) → calls it.
- `next()`/`has_next()` on the `iterator` type → advances.
- `next()`/`has_next()` on any other value with those methods → calls them.

A `data` that defines `iter` therefore joins the protocol without a new type declaration.
That is the same open-dispatch rule the rest of the method surface uses, so a protocol is
not a closed list of built-in types.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| **`next()` returns `nothing` when exhausted** | Indistinguishable from a list containing `nothing`. `[nothing].iter().next()` and a spent iterator would be the same value, so a correct loop cannot be written. |
| **`next()` raises a catchable `StopIteration`** | Oqthara has no exception handling construct. ADR-0014's `try` captures values, not exceptions. An uncatchable error is not a protocol. |
| **`for` over a live collection (no snapshot)** | Reverses ADR-0016, which exists because concurrent modification corrupted walks. `.iter()` already offers the live view for callers who want it, so `for` need not. |
| **Iterators are a `data` type, not a built-in** | Loses `repr` quality (`iterator([1, 2, 3])` versus a field dump), and every iterator would allocate a field map. Making it a built-in keeps it small and lets the runtime recognise it for `for`. |
| **`.iter()` copies, matching `for`** | Then it is just `for` with extra steps, and walking a large collection doubles memory for no benefit. Laziness is the reason to expose an iterator at all. |
| **Structural equality for iterators** | Two iterators at different positions over the same list would compare equal unless position is compared, and comparing position exposes mutable state through `==`. Identity is simpler and cannot lie. |

## Consequences

- `for` and `.iter()` remain separate code paths with separate guarantees (snapshot versus
  live). Both are documented in `docs/spec/language.md` so the difference is stated where
  users will look.
- `Values.iterable` keeps its copying behaviour, because `for` depends on it. `.iter()`
  gets its own non-copying entry point, `Values.iterator`.
- `Collections.iterator` currently returns a raw `java.util.Iterator`, which the compiler
  consumes. It must keep doing so — `Ir.IterNew` is already emitted — so `OqIterator`
  wraps that path rather than replacing it, and the compiler is unchanged.
- Every collection family gains `.iter()` through one implementation, not five.
- `has_next()` becomes a required member of the protocol. Anything presenting itself as an
  iterator must supply it, or `for` over it would be the only way to consume it.
- The error for `.next()` on a non-iterator names the fix (`Call iter() first.`), matching
  the ADR-0018 rule that an index error names the operation that would have worked.

## Verification

Measured against the built CLI after implementation; every row below is an observed
result, not a prediction.

| Expression | Result |
|---|---|
| `type_of([1].iter())` | `iterator` |
| `repr([1, 2, 3].iter())` | `iterator([1, 2, 3])` |
| `it = [1, 2].iter(); it.next()` twice | `1`, then `2` |
| third `it.next()` | error: `this iterator is exhausted` |
| `it.has_next()` after exhaustion | `false` |
| `[1, 2, 3].iter().has_next()` | `true` |
| `[].iter().has_next()` | `false` |
| `xs.iter() == xs.iter()` | `false` (identity) |
| `xs.next()` | error naming `iter()`, with the help line `Call iter() first: list is iterable, not an iterator.` |
| `length(xs.iter())` | error: an iterator has no length |
| `1 in xs.iter()` | error: testing membership would consume it |
| `xs.iter()[0]` | error: an iterator is not indexable |
| `xs.iter()[0:2]` | error: `cannot slice iterator` |
| `for x in xs.iter()` | walks the iterator, consuming it |
| `for x in xs` | still snapshots: mutation inside the loop does not affect the walk |
| `.iter()` on set, map, range, text, tuple | each yields its members |
| `.iter()` on a map | yields keys, matching `for` |
| `.iter()` twice on the same list | two independent iterators |
| `enumerate(["a", "b"])` | `[[0, "a"], [1, "b"]]` |
| `enumerate([])` | `[]` |
| `range(0, 10, 2).to_list()` | `[0, 2, 4, 6, 8]` |
| `range(5, 0, -2).to_list()` | `[5, 3, 1]` |
| `length(range(0, 10, 3))` | `4` |
| `range(0, 5, 0)` | error: `a range step cannot be 0` |
| `(0 until 3).to_list()` | `[0, 1, 2]` |
| `(0 until 3).first()`, `.last()` | `0`, `2` |
| `(0 until 3).contains(2)`, `.contains(3)` | `true`, `false` |

## References

- CPython 3.13 `Objects/iterobject.c` and the `PyIter_Next` contract, consulted
  2026-11-10 for the exhaustion rule. Oqthara adopts the `has_next`/`next` pair and
  rejects the `StopIteration` signalling mechanism, for the reason given above.
- ADR-0016 — iteration snapshots.
- ADR-0018 — index semantics; the "error names the operation that would have worked" rule.
