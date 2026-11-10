# ADR-0022 — Hashability, and why `frozenset` is not just a Python import

- **Status:** accepted
- **Date:** 2026-11-10
- **Supersedes:** nothing
- **Related:** ADR-0017 (equality and hashing), ADR-0020 (iteration), ADR-0021 (family operations)
- **Directive:** Alpha 4 Master Execution Directive §9.1 (families), §9.2 (protocols)

## Context

§9.1 lists "immutable set/frozen set" among the expected families and warns that a type
must not be added merely because Python has one. So the question is not "does Python have
`frozenset`" but "is anything actually broken without it".

Something is. **Any value can be a map key or a set member, including ones that mutate**,
and the hash is structural:

```oq
k = [1]
m = {k: "a"}
print m[[1]]       # a
k.add(2)
print m[[1]]       # nothing
print m[k]         # nothing
```

The entry is still in the map. It is simply unreachable: its bucket was chosen from the
hash of `[1]` and the key now hashes as `[1, 2]`. Nothing reports an error, `length(m)` still
counts it, and `repr(m)` still prints it. The same applies to a set used as a member:

```oq
s = set([1])
outer = set([s])
s.add(2)           # outer is now corrupt in exactly the same way
```

`Value.hash` hashes `List`, `Map`, `Set` and `OqData` structurally (`Value.java`). So this
affects every mutable family, including `data`:

```oq
data P(x)
k = P(1)
m = {k: "a"}
print m[k]         # a
k.x = 2
print m[k]         # nothing
```

Tuples are hashed structurally too, but they cannot change, so they are safe.

This is a soundness bug, not a missing convenience. A keyed collection that silently loses
entries is worse than one that refuses the key.

## Decision

### Hashability is a property of the type, and it is checked

| Family | Hashable | Why |
| --- | --- | --- |
| number, text, boolean, `nothing` | yes | immutable |
| function | yes, by identity | identity never changes |
| tuple | yes, when every element is | immutable, and structural equality is stable |
| **`data`** | **no** | fields are mutable (`p.y = 5`), so it corrupts like a list |
| `frozenset` | yes, when every member is | the point of the type |
| range | yes | immutable |
| **list** | **no** | mutable; this is the bug |
| **set** | **no** | mutable; this is the bug |
| **map** | **no** | mutable; this is the bug |
| iterator, function | no | identity with mutable state behind it (ADR-0020) |

Using an unhashable value as a map key or a set member is an **error**, not a silent
mutation hazard:

```oq
{[1]: "a"}          # error: a list cannot be used as a map key
set([[1]])          # error: a list cannot be used as a set member
```

The check walks a tuple deeply, because `([1],)` is as mutable as `[1]`:

```oq
{([1],): "a"}       # error: a tuple containing a list cannot be used as a map key
{(1, 2): "a"}       # fine
```

### `frozenset` is introduced because it is the answer to a real question

Without it, the rule above would leave no way to say "a set of sets", which is a thing
programs actually need (equivalence classes, graph adjacency keyed by component, deduping a
collection of collections). `frozenset` is an **immutable** set:

```oq
a = frozenset([1, 2])
print type_of(a)          # frozenset
a.add(3)                  # error: a frozenset is immutable
{frozenset([1]): "x"}     # fine -- it is hashable
frozenset([1, 2]) == frozenset([2, 1])   # true
```

It supports the read-only half of the set surface — `length`, `has`, `union`,
`intersection`, `difference`, `symmetric_difference`, `issubset`, `issuperset`,
`to_list`, `iter`, `sorted`, `sum`, `min`, `max` — and no mutators. `set(fs)` and
`fs.to_set()` convert between the two.

Structural equality with `set` is **not** provided: `frozenset([1]) == set([1])` is `false`.
They are different types with different guarantees, and `type_of` already distinguishes them.
Use `fs.to_set()` to compare.

### `bytes` is not introduced

`bytes` exists in Python to hold raw octets separately from text, because Python 3 made
`str` a sequence of code points rather than bytes. Oqthara has no such split: text is a
sequence of characters, there is no `bytearray`, and there is no `open()` returning binary
data yet. Adding `bytes` now would create a second string-like type with no operation that
needs it, which is the thing §9.1 warns against.

When file and network I/O arrives, binary payloads will need a representation, and that is
when `bytes` should be designed — against a real consumer rather than ahead of one. Until
then the absence is deliberate and recorded here, not an oversight.

## Rejected alternatives

**Reject mutable keys by returning an error only from the map, not the set.** A set member
is a key in everything but name; leaving `set([[1]])` legal would keep half the bug.

**Copy every key on insertion.** That would make `{[1]: "a"}` work, but the copy in `m`
and the caller's list would be different values, so a later `m[k]` with the caller's `k`
would miss. Silently copying the key is a worse trap than refusing it.

**Hash mutable values by identity.** Then `{[1]: "a"}` would be unreachable by a fresh
`[1]`, and `[1] == [1]` while their hashes differ — breaking the contract `Value.equals`
already relies on.

**Make `frozenset` a flag on `set`.** A `const`-style flag would have to be enforced on
every mutator anyway, and `type_of` would have to lie. A separate type keeps the guarantee
in the type system, where the existing tuple immutability already lives.

**Offer `frozenset` but skip the hashability check.** Then the type exists and the bug
remains, which is the worst of both.

## Consequences

- `{[1]: "a"}` and `set([[1]])` are **breaking**: they used to be accepted, and accepted
  wrongly. No conformance program relies on them, and the conformance baseline is unchanged.
- Existing maps holding mutable keys cannot be constructed any more, so the corruption
  cannot be reached by a new program.
- A program that needs a mutable collection as a key must convert: a list becomes a tuple
  (if its length is fixed) or a `frozenset` (for a set).
- The hashability check runs once per insertion and walks nested tuples and frozensets, so
  `(([1],),)` is refused at the inner list. A `data` value is refused outright rather than
  walked, since every field is mutable anyway. The cost is proportional to the key, which
  hashing already was.

## Verification

Every row was produced by running the built CLI. Rows marked "was" describe the previous
behaviour and were measured before the change.

| Expression | Was | Now |
| --- | --- | --- |
| `{[1]: "a"}` | `{[1]: "a"}` | error: `a list cannot be used as a map key` |
| `{[1]: "a"}` then `k.add(2)` | entry unreachable, no error | cannot be written |
| `set([[1]])` | `{[1]}` | error: `a list cannot be used as a set member` |
| `{set([1]): "x"}` | `{{1}: "x"}` | error: `a set cannot be used as a map key` |
| `{([1],): "a"}` | accepted | error: `a list cannot be used as a map key` |
| `data P(x)` … `{P(1): "a"}`, then `k.x = 2` | entry unreachable, no error | cannot be written |
| `{(1, 2): "a"}` | `{(1, 2): "a"}` | unchanged |
| `frozenset([1, 2])` | `'frozenset' is not defined` | `frozenset({1, 2})` |
| `frozenset([1]).add(2)` | n/a | error: `a frozenset is immutable` |
| `{frozenset([1]): "x"}` | n/a | `{frozenset({1}): "x"}` |
| `frozenset([1,2]) == frozenset([2,1])` | n/a | `true` |
| `frozenset([1]) == set([1])` | n/a | `false` |
| `bytes(3)` | `'bytes' is not defined` | still absent, deliberately |

### Positions, added after this ADR merged

The refusal above was correct in message and wrong in position. It is raised inside the
collection, which has no span, so it fell back to `Errors.current()` — the last marked
position. The compiled backend marks spans, so it reported a plausible-looking line; the
**interpreter marks none**, so it reported `<unknown>:1:1` and pointed at line 1 of whatever
the program was. Both are wrong, and the interpreter's is unusable.

| Program | Was (interpreter) | Now (both backends) |
| --- | --- | --- |
| `m = {}` / `m[[1]] = 2` | `<unknown>:1:1`, blames line 1 | `:2:2` |
| `s = {[1]}` | `<unknown>:1:1` | `:1:6` |
| `m = {[1]: "a"}` | `<unknown>:1:1` (interpreter), `<unknown>:1:1` (compiled) | `:1:6` |

`Value.requireHashable` gained a span-taking overload; the interpreter's set and map literals
and `Values.setIndex` pass the span they already hold, and the compiled set and map literals
now `mark` their span the way every other operation does. `ErrorMessageContractTest` asserts
the reported line, so this cannot silently regress.

The Python reference consulted for naming was CPython 3.13 on 2026-11-10, per §9.6.
