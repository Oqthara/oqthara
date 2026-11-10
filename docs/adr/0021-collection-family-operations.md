# ADR-0021 — Collection-family operations: aggregation, conversion and set algebra

- **Status:** accepted
- **Date:** 2026-11-10
- **Supersedes:** nothing
- **Related:** ADR-0017 (equality and hashing), ADR-0020 (iteration protocol)
- **Directive:** Alpha 4 Master Execution Directive §9.1 (families), §9.2 (protocols), §9.3 (operations)

## Context

ADR-0020 gave every iterable family an iterator. It did not give them a common
*operation* surface, and the gaps were inconsistent in a way that made the families feel
unrelated:

```oq
sum([1, 2, 3])        // 6
sum(set([1, 2, 3]))   // error: set has no method 'sum'
sum(range(0, 4))      // error: range has no method 'sum'

min(1, 2)             // 1        (two arguments only)
min([3, 1, 2])        // error: min() expects 2 arguments, got 1

sorted([3, 1, 2])     // error: 'sorted' is not defined

[1, 1, 2].to_set()    // error: type list has no method 'to_set'
"ab".to_list()        // error: type text has no method 'to_list'
```

None of this was a missing type. Every family already reported its length, iterated
under `for`, and supported `in`. The operations existed on exactly one family each, so
"can I sum this?" answered differently depending on the value's spelling.

Two behaviours were also wrong rather than missing:

- `(1, 2).clone()` returned a **list**, so cloning an immutable value produced something
  mutable. A clone must not change what a value is.
- `(3, 1).sort()` was refused as a mutator even though `sort` is pure and returns a new
  list. A previous test asserted this refusal deliberately, to stop a tuple quietly
  becoming a mutable list through the pure path. That intent is kept, and the test is
  unchanged; see the rejected alternative below.

## Decision

### Aggregation reads any iterable

`sum`, `min`, `max` and `sorted` accept **any** value that iterates, and go through
`Values.iterableList` rather than a per-type method. The family table no longer decides
whether an aggregation works.

| Call | Result |
| --- | --- |
| `sum([1,2,3])` / `sum(set([1,2,3]))` / `sum(range(0,4))` | `6` |
| `sum([])` | `0` |
| `min([3,1,2])` / `min(set([3,1,2]))` | `1` |
| `min(3, 1, 2)` / `max(3, 1, 2)` | `1` / `3` |
| `min([])` | error: `min() needs at least one value` |
| `sorted([3,1,2])` | `[1, 2, 3]` |
| `sorted("cba")` | `["a", "b", "c"]` |

`min` and `max` are **variadic from one argument**: one argument means "the extreme of
this collection", several mean "the extreme among these values". Oqthara has no rest
parameters, so the arity is the only signal available, and both readings of a single
argument would be surprising (`min([[1],[0]])` compares lists, not their members).

`sorted` returns a **new list** and never reorders its argument:

```oq
x = [3, 1, 2]
y = sorted(x)
print x            // [3, 1, 2]
print y            // [1, 2, 3]
```

Sorting is **stable** and compares with `Values.compare`, the same ordering `min` and
`max` use, so a value that cannot be ordered produces the same diagnostic everywhere.

### Conversion is total between list, set, text and tuple

Every family that holds members can produce a **list** and a **set**:

| From | `.to_list()` | `.to_set()` |
| --- | --- | --- |
| list | the list itself (copy) | `{…}` in first-seen order |
| set | members as a list | the set itself (copy) |
| tuple | members as a list | members as a set |
| text | single-character strings | distinct characters |
| map | **keys**, matching `for k in map` | **keys** |
| range | the values in order | the values as a set |

A map converts to its **keys** because that is what a map iterates as (ADR-0013 makes the
same choice for destructuring and ADR-0020 for `.iter()`). Values are reached through
`m[k]`, or through `m.items()`.

### `map.items()` yields pairs

`items()` returns a list of `[key, value]` two-element lists, which is what `enumerate`
produces and what an indexing loop consumes:

```oq
for kv in {"a": 1}.items()
    print kv[0], kv[1]
end
```

`entries()` keeps returning the existing `Entry` data values; the two are not merged
because `entries()` already had a shape its users depend on.

### `merge` is `update`, and it mutates

`m.merge(other)` and `m.update(other)` are the same operation under two spellings:
`other`'s entries win, and the receiver changes **in place**. Both spellings return the
receiver, so `print m.merge({...})` shows the merged map.

Two names for one behaviour is a deliberate concession: `update` is the Python word and
`merge` is the word most other languages use, and picking one would leave the other
missing for no benefit. The mutation is the part that must be explicit, and it is: the
receiver changes, nothing returns a new map.

`m.pop(key)` removes and returns the value, `m.pop(key, default)` returns the default
when the key is absent, and `m.pop(key)` with no default is an error rather than a
silent `nothing` (which a map holding `nothing` would be indistinguishable from).

### Set algebra is complete and non-mutating

| Call | Result |
| --- | --- |
| `s.union(t)` / `s.intersection(t)` / `s.difference(t)` | a new set |
| `s.symmetric_difference(t)` | a new set |
| `s.issubset(t)` / `s.issuperset(t)` | `true` / `false` |
| `s.clone()` / `s.copy()` | an independent set |

All of these build a new set and leave both operands alone. Membership goes through
`OqSet`, so `1` and `1.0` agree here exactly as they do under `==` (ADR-0017) and `in`.

Result order is the set's own insertion order, which is what the set already guarantees
for `repr` and `for`.

### Keyed sorting reads any iterable

`sort_by(coll, key)` builds a list from **any** iterable and sorts it by a one-argument
key, **stably**:

```oq
sort_by([(1, "a"), (1, "b"), (0, "c")], fun(t) return t[0] end)
// [(0, "c"), (1, "a"), (1, "b")]
```

It used to delegate to a `sort` *method*, which only a list has, so
`sort_by(set([3, 1]), key)` failed with `set has no method 'sort'` — naming a method the
caller never wrote. It now reads the collection directly, which also makes it work on a
set, a range, a map (keys) and text.

### `clone` preserves the family

`clone()` and `copy()` return the **same family** as the receiver: a list clone is a
list, a set clone is a set, a map clone is a map, and a tuple clone is a **tuple**. The
last one is the correction this ADR makes most explicit — the old code built an
`ArrayList`, so `type_of((1, 2).clone())` was `list`.

## Rejected alternatives

**Make `sort` and `reverse` work on tuples because they are pure.** The methods do return
a new list rather than mutating, so refusing them looks inconsistent. But the refusal is
load-bearing: `sort` on a tuple would leave the caller holding a *list* where a tuple was,
and nothing in the call says so. The existing test
`aViewOfATupleCannotBeSortedInPlace` documents exactly this, and it is unchanged. A
caller who wants a sorted list from a tuple writes `sorted(t)` or `t.sorted()`, which
return a list **and say so in the name**.

**Have `min`/`max` take only collections, or only values.** Either choice breaks a
spelling that already worked. Two-argument `min(1, 2)` shipped in Alpha 3, and collection
form is what every other language offers.

**Return the receiver from the set operations.** Then `s.union(t)` would silently mutate
`s`, and the result would be indistinguishable from `s.update(t)`. Unions are the one
place where a non-mutating operation is the whole point.

**Add `sorted(coll, key)` with a key function.** Deferred rather than half-built. The
builtin `sort_by(coll, key)` already covers the case and takes any iterable, so a second
spelling would be one too many; if `sorted` grows a key argument it should first be decided
which of the two remains.

**Make `items()` return `Entry` data values.** It would collide with `entries()` and
would make the destructuring loop awkward, since `Entry` is not destructurable
(ADR-0013 restricts destructuring to sequences and maps).

## Consequences

- The five collection families now share an aggregation and conversion surface, so a
  function can accept "anything that iterates" rather than five branches.
- `sum`, `min`, `max` and `sorted` copy their argument once through `iterableList`. That
  is the same cost `for` already pays (ADR-0016), and it means a mutation during
  aggregation cannot disturb the walk.
- `min([])` and `max([])` are errors rather than `nothing`. `min([])` returning `nothing`
  would be indistinguishable from `min([nothing])`.
- A range carries the same aggregation methods as the other families, because a range is
  the one family where a method spelling is the natural one: `(0 until 3).sum()` reads
  better than `sum(0 until 3)`, and leaving only the builtin would mean the asymmetry
  survived in the one place it is most visible.

## Verification

Every row below was produced by running the built CLI, not by reading the code.

| Expression | Result |
| --- | --- |
| `sum(set([1,2,3]))` | `6` |
| `sum(range(0,4))` | `6` |
| `min([3,1,2])`, `max(3,1,2)` | `1`, `3` |
| `min([])` | error: `min() needs at least one value` |
| `sorted([3,1,2])` | `[1, 2, 3]` |
| `sorted("cba")` | `["a", "b", "c"]` |
| `(3,1,2).sorted()` | `[1, 2, 3]` |
| `[1,1,2].to_set()` | `{1, 2}` |
| `"ab".to_list()` | `["a", "b"]` |
| `"aab".to_set()` | `{"a", "b"}` |
| `(1,2).to_set()` | `{1, 2}` |
| `{"a":1}.to_list()` | `["a"]` |
| `{"a":1,"b":2}.items()` | `[["a", 1], ["b", 2]]` |
| `{"a":1}.merge({"b":2})` | `{"a": 1, "b": 2}`, receiver changed |
| `{"a":1}.pop("a")` | `1`, receiver is `{}` |
| `{"a":1}.pop("z", 9)` | `9` |
| `{"a":1}.pop("z")` | error: `this map has no key "z"` |
| `set([1,2]).symmetric_difference(set([2,3]))` | `{1, 3}` |
| `set([1]).issubset(set([1,2]))` | `true` |
| `set([1,9]).issubset(set([1,2]))` | `false` |
| `set([1,2]).issuperset(set([1]))` | `true` |
| `type_of((1,2).clone())` | `tuple` |
| `(1,2).push(3)` | error: `a tuple is immutable` |
| `(3,1).sort()` | error: `a tuple is immutable` |
| `set([1,2,3]).reverse()` | `[3, 2, 1]` |
| `"abc".reverse()` | `cba` |
| `sort_by(set([3,1]), key)` | `[1, 3]` |
| `sort_by([(1,"a"),(1,"b"),(0,"c")], t[0])` | `[(0, "c"), (1, "a"), (1, "b")]` — stable |
| `sort_by([1], 5)` | error: `sort_by() needs a key function` |
| `(0 until 3).sum()` | `3` |
| `range(0,3).min()`, `range(0,3).max()` | `0`, `2` |
| `range(0,3).reverse()` | `[2, 1, 0]` |
| `range(0,0).sum()` | `0` |

Both backends produced **byte-identical** output for the whole surface above.

The Python reference consulted for naming and defaults was CPython 3.13 on 2026-11-10,
per §9.6 of the directive. Where Oqthara differs, it is recorded as a decision above.
