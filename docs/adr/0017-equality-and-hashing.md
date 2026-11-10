# 0017 — Equality and hashing agree

Status: accepted (2026-11-09, Alpha 4 Phase 9)

## Context

[ADR-0015](0015-type-families.md) recorded an inconsistency and left it open:

```
print 1 == 1.0            # true
print [1] == [1.0]        # false
print (1,) == (1.0,)      # false
print {1: "a"} == {1.0: "a"}   # false
print set([1]) == set([1.0])   # false
```

The numeric rule stopped at the container boundary. `Values.equal` widened a `Long` against
a `Double` at the top level and then delegated to `Object.equals`, where
`Long.equals(Double)` is `false`.

The hash half of the same defect was worse, because it was not merely inconsistent but
*unsound*. Java's `Long` and `Double` hash differently, so a `HashSet` could hold two values
that compare equal:

```
print length(set([1, 1.0]))   # 2    -- one value, counted twice
print 1 in set([1.0])         # false -- but 1 == 1.0 is true
m = {1: "a"}
print m[1.0]                  # nothing
```

A set that reports length 2 while holding one value, and a map that cannot find a key it was
given, are not stylistic problems. Both violate the contract the standard library relies on
for `unique()`, `difference()`, `has_value()` and every other operation that is implemented
in terms of membership.

There was a second, independent gap. `OqData` derives `hashCode` from its fields and its
fields are mutable, so a `data` value used as a key becomes unreachable as soon as it is
mutated. Appendix "Consequences" below states what is done about that, which is less than a
full fix.

## Decision

### One equality rule, applied at every depth

`Values.equal` is the single definition of `==`, and it applies recursively:

- `nothing` equals only `nothing`.
- A whole number and a whole decimal that denote the same value are equal. `1 == 1.0`,
  `-0.0 == 0`, and `2**53` cases follow `double` comparison, as before.
- Tuples compare structurally, in order. Tuples never equal lists, whatever they contain.
- Lists compare structurally, in order.
- Maps compare as unordered entry sets, by equal keys and equal values.
- Sets compare as unordered element sets.
- `data` values compare nominally: same type name and equal fields.
- Everything else uses its own `equals`.

Collection comparison therefore no longer differs from element comparison. A nested
collection at any depth uses the same rule as the values it holds.

### Hashing is structural and agrees with equality

Every value has a hash derived from the same structure: lists and tuples fold their elements,
maps fold their entries, sets fold their elements, and a whole decimal hashes as the whole
number it equals. The invariant is:

> if `a == b` then `hash(a) == hash(b)`

Concretely, a whole decimal is normalised onto the integral hash:

```java
if (Values.fitsInLong(value)) return Long.hashCode((long) value);
return Double.hashCode(value);
```

This is not `Double.hashCode` for whole values, which would disagree with `Long.hashCode`,
and it is not truncation for all values, which would collapse distinct decimals.

### How the containers enforce it

`OqSet` and `OqMap` are new runtime types that store `Value` boxes rather than raw values.
A `Value` delegates `equals` to `Values.equal` and implements `hashCode` structurally, so the
underlying `LinkedHashSet`/`LinkedHashMap` sees a consistent pair.

Both implement `Set<Object>` and `Map<Object, Object>` and **unwrap on the way out**, so
iteration, `keys()`, `values()` and `entries()` hand back ordinary values. A box is never
observable from the language; it exists only inside the backing store.

Insertion order is preserved. `set([3, 1, 2])` still prints and iterates in insertion order,
so determinism is not traded for the fix.

One consequence of structural hashing is that a **list is hashable**, even though it is
mutable. That is consistent with `data` values also being hashable and mutable. The contract
is documented rather than enforced: mutating a value after using it as a key makes the entry
unreachable. This is recorded as a known limitation, not as a promise.

### Self-referential collections are rejected, not stack-overflowed

A collection that contains itself cannot be hashed or compared to a finite depth:

```
l = [1]
l.push(l)
print length(set([l]))
```

Hashing descends until a bound and then raises `InvalidHashException`, which the call sites
convert into a normal Oqthara error. Without the bound this is a `StackOverflowError` leaking
out of `HashSet.add`. Printing such a list already raised `the program recursed too deeply`
before this change; equality and hashing now do the same rather than crashing.

## Alternatives rejected

**Keep widening top-level only and document it.** This is what ADR-0015 did. It cannot be
made sound, because the hash half is not a documentation problem: `set([1, 1.0])` reporting
length 2 is a wrong answer, not an undocumented one.

**Make `==` hash-free and linear.** Comparing every container element pairwise with
`Values.equal` would fix equality without touching containers, and was tempting because it
needs no new types. It leaves `set` and `map` — which are inherently hash-keyed — still
broken, which is the more damaging half.

**Narrow `1 == 1.0` to `false` instead.** This makes Java's own `equals` and `hashCode`
correct for free, and is a defensible language design. It was rejected because it changes
what every existing program means and contradicts the documented arithmetic model, where
`1 + 1.5` is `2.5` and division returns a whole number when exact. Equality should follow
arithmetic, not the host's box types.

**Forbid mutable values as keys.** The correct long-term answer, and it is not taken here
because it needs a hashability predicate the type system does not have yet. It is recorded
under Consequences.

## Consequences

**Fixed.**

- `[1] == [1.0]`, `(1,) == (1.0,)`, `{1: "a"} == {1.0: "a"}`, `set([1]) == set([1.0])` are
  all `true`.
- `length(set([1, 1.0]))` is 1, `1 in set([1.0])` is `true`, and `{1: "a"}[1.0]` is `"a"`.
- `unique()`, `difference()`, `union()`, `intersection()`, `has_value()` and `remove_value()`
  now behave consistently with `==` at every depth.
- Equality no longer depends on how deeply a value is nested.

**Not fixed, and now precisely bounded.**

1. **Mutating a key makes it unreachable.** Mutating a list, map or `data` value after using
   it as a set element or map key makes the entry unfindable. Structural hashing makes this
   true for lists as well as `data`, so the gap is slightly wider than before even though
   equality is now sound. Enforcing hashability needs a type-level predicate.
2. **Cycles raise an error** instead of printing or hashing. This is deliberate and bounded,
   but a user who builds a cycle gets a diagnostic where they used to get a stack overflow.
3. **`-0.0 == 0` is `true`** and both hash identically, which follows from numeric equality.
   `0.0/0.0` is `NaN`, and `NaN == NaN` is `false`, so `NaN` never equals itself; a set may
   therefore hold several references to the same `NaN`.

**Follow-up this ADR does not do.** Requiring hashability where it matters — rejecting an
unhashable key at insertion rather than silently losing it — is the natural next step and
belongs with the collection protocol work in [the contract register](../spec/contracts.md).

## Verification

`oq-cli/src/test/java/dev/oqthara/cli/LanguageFeatureTest.java` pins both halves:
`numericEqualityReachesInsideCollections` and `equalNumbersHashAlike`. Both replace tests
that deliberately asserted the old behaviour, so the change could not be made silently.
`oq-runtime/src/test/java/dev/oqthara/runtime/ValueTest.java` covers the hash/equality
invariant directly, including the self-referential guard and `data` fields.

**A defect this ADR found in itself.** The first draft left `OqData` delegating to
`Object.equals`, so `P(1, 2) == P(1.0, 2)` was `false` — the rule stopped one level short
again, at the object boundary rather than the container boundary. Writing the language
reference example for `data` and running it exposed this; the implementation now routes
`OqData.equals` and `OqData.hashCode` through `Values.dataFieldsEqual` and `Value.hashing`,
and `ValueTest` covers it. Recorded here because a reader should know that the "one rule at
every depth" claim was, for a short time, false in exactly the way it was written to fix.
