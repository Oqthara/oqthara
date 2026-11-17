# ADR-0067 — `sort_by` moves onto the decorate path

**Status:** Accepted
**Date:** 2026-11-17
**Amends:** [ADR-0060](0060-keyed-sorting.md) decision 4 and its consequences
**Related:** [ADR-0021](0021-collection-family-operations.md),
[ADR-0041](0041-statistics-identifiers-and-ordered-search.md),
`Stdlib.sort_by`, `Stdlib.sortedByCachedKeys`, `ListSortTest`

## Context

ADR-0060 decision 4 deliberately left `sort_by` evaluating its key once per comparison:

> `sort_by` is not reimplemented on the same path in this change. Changing its key count is a
> behaviour change to a published builtin, and the defect it exposes is better fixed by moving
> callers to `list_sort` than by silently altering what `sort_by` does.

That decision bought time, not correctness, and it stated its own exit condition:

> When `sort_by` is moved onto the decorate path, that test must move with it.

The defect is not a performance note. It makes the result of a sort depend on how many
comparisons the algorithm happened to make, which a caller cannot predict from the signature
and cannot detect from the output. Measured on the built CLI, before this change, on both
backends:

```
calls = 0
fun counterKey(x)
  calls = calls + 1
  return calls
end
print sort_by([10, 20, 30], counterKey)   # [30, 20, 10]
```

The input is already in ascending order and the output is its reverse. `list_sort` answered
`[10, 20, 30]` from the same key in the same program.

## Decision

### 1. `sort_by` and `list_sort` share one decorate path

Both now call `Stdlib.sortedByCachedKeys`: compute every key in input order, then sort an index
list against the cache. The key count is exactly the item count for both names.

The two builtins still differ, and the difference is now only in their argument rules:

| | key | `nothing` as key | empty list | one item |
| --- | --- | --- | --- | --- |
| `sort_by(items, key)` | required | refused | 0 calls | 1 call |
| `list_sort(items, key?)` | optional | treated as absent | 0 calls | 1 call |

That asymmetry is unchanged by this ADR and remains recorded in the Alpha 5 inventory. It is a
question about what a missing key means, which ADR-0060 decision 1 already answered for
`list_sort`; it is not a question about how a key is evaluated.

### 2. This is a behaviour change, and it is the one ADR-0060 named

A caller relying on `sort_by` calling its key per comparison had a program whose output
depended on the comparison sequence. That is the behaviour being removed, so no conforming
program changes meaning: a pure key produces the same order under both implementations, and a
stateful key now produces the order `list_sort` always produced.

No conformance program asserted the old order. `conformance/256-callable-builtins-compile.oq`
uses `sort_by(xs, x -> -x)`, a pure key, and both backends print the same result before and
after.

### 3. The pin moves with the change, as ADR-0060 required

The old pin, `sortByStillEvaluatesItsKeyPerComparison`, asserted `[30, 20, 10]` and its failure
message said to update it together with this change. It is now
`sortByCallsItsKeyOncePerItem`, asserting `[10, 20, 30]` and exactly three calls. The assertion
is otherwise the same, so it still fails if the key returns to the comparison path — verified by
reverting the implementation and observing
`expected: <[10, 20, 30]> but was: <[30, 20, 10]>`.

`sortByKeepsEmptyAndSingleItemLists` was added because the old path never called the key for a
one-item list: it made zero calls where the contract requires one. The count is asserted, not
just the output, because the output is correct in both cases.

## Consequences

- A stateful key produces the same order from `sort_by` and `list_sort`. The reason `list_sort`
  exists is now its optional key and its `nothing` handling, not a correctness gap.
- Keyed sorting costs one allocation per item on both names, the price ADR-0060 already
  accepted for `list_sort`.
- One implementation serves both builtins, so a future change to key evaluation cannot fix one
  name and leave the other behind.
- The Alpha 4 risk register's record of this defect is closed against this ADR and the
  measurements above, rather than deleted.
