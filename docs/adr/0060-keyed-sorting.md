# ADR-0060 — Keyed sorting, and why `list_sort` exists

**Status:** Accepted
**Date:** 2026-11-14
**Related:** ADR-0023 (collection protocols), ADR-0048 (Runtime Manager), ADR-0041
(ordered search), the standard-library inventory §17.1, the requirement matrix §17

## Context

The Runtime Manager's compatibility check named `list_sort` and `json_stringify` in the
program it runs on a candidate JVM. Neither was a builtin. Runtime installation therefore
refused every JDK with `error: 'list_sort' is not defined`, because a version number says
what a JVM claims rather than what it can run, and the check is what turns the claim into a
measurement. [ADR-0048](0048-runtime-manager.md) records the discovery and an earlier
decision to rename the two names in the check to builtins that already existed.

That decision was reversed by the maintainer, on the grounds that a compatibility check may
not be made to pass by weakening it: if the project's own check names a capability, the
capability is what should exist. `list_sort` is therefore implemented for real, and it stays
in the check so that the check is satisfied by the capability rather than by its absence.

`json_stringify` is a different case and is **not** implemented. The language spells that
operation `json_write`, which already exists and is the documented name;
`json_stringify` was an invented alias with no contract behind it, and adding it would give
the standard library two names for one function with no rule saying which to use. The check
uses `json_write`.

### What was measured before the contract was chosen

Three existing sorting surfaces were probed against the built CLI. The measurements decided
the contract; they are recorded here because the alternatives are all defensible and only one
is implemented.

| Name | Works on a `set`? | Accepts a key? | Mutates its receiver? |
|---|---|---|---|
| `sort(x)` | no — `type set has no method 'sort'` | no — `sort() expects 1 argument, got 2` | no |
| `sorted(x)` | yes, widens to `list` | no — `sorted() expects 1 argument, got 2` | no |
| `items.sort(key)` | no — method form | yes, 0 or 1 | no |
| `sort_by(items, key)` | yes | yes, required | no |

Two defects showed up while taking that table.

**`sort(set(...))` names a method the caller never wrote.** The global delegates to the
`.sort()` method, so a rejected call reports `set has no method 'sort'` even though the source
line says `sort(set(...))`. The message describes the implementation rather than the call.

**`sort_by` evaluates its key on every comparison.** On one ten-item list this cost 46 calls
where Python's `sorted(key=...)` guarantees ten; the exact figure depends on the input, and the
same ten items in another order cost 18. Both are double the comparison count, because each
comparison evaluates both operands. That is not only slow: it makes the result depend on how
many comparisons the algorithm happened to make. With a key that returns its own call
count, `sort_by([10, 20, 30], key)` returns `[30, 20, 10]` — the reverse of the input, decided
by evaluation order, because each comparison consumed the next counter value. A caller cannot
see that from the signature, and no error is raised.

## Decision

### 1. `list_sort(items, key?)` is the keyed sort, and the key is called once per item

The contract is decorate-sort-undecorate: every key is computed, in input order, before any
comparison happens. The comparison then reads cached keys.

This makes the key count exactly the number of items, which is the property Python's
`sorted(key=...)` provides and the property a caller reasonably assumes. It also makes a
stateful key produce the same answer it would produce from one call per item — the same
`[10, 20, 30]` key that scrambles `sort_by` returns `[10, 20, 30]` from `list_sort`.

The key is optional. Omitting it, or passing `nothing`, sorts by the values themselves through
`Values.compare`, which is the same ordering `<` uses, so an unorderable pair fails with the
same message instead of a second, sorting-specific one.

### 2. `list_sort` is a new name, not a change to `sort` or `sorted`

Both existing names are single-arity. Giving either a second parameter would break a caller
that passes a function as the value to sort, and `sort` is additionally the name of a method
whose receiver is a `list`. A third name is cheaper than a compatibility break, and the
inventory's rule is that a name is added with a contract rather than an alias.

### 3. Stability is part of the contract

Two items whose keys are equal keep their input order. This is stated because the alternative
is a legitimate implementation choice, and a caller who reorders a table by one column relies
on the others not moving.

### 4. `sort_by` keeps its behaviour, and the reason is pinned by a test

`sort_by` is not reimplemented on the same path in this change. Changing its key count is a
behaviour change to a published builtin, and the defect it exposes is better fixed by moving
callers to `list_sort` than by silently altering what `sort_by` does. The wrong answer is
pinned by `ListSortTest.sortByStillEvaluatesItsKeyPerComparison`, so the difference between the
two names is reproducible from a test rather than only from this paragraph. When `sort_by` is
moved onto the decorate path, that test must move with it.

### 5. `list_sort` compiles

It is deliberately **not** added to `Builtins.HOST_DEPENDENT`. That set exists for builtins that
read state outside the program or that the compiler cannot emit a call site for; `list_sort`
does neither. Both the one-argument and the keyed form were built with `oq build` and produced
`codepath: compiled bytecode`, and the built jar printed the same result as the interpreter.
Adding it to the set would have pushed the common one-argument case onto the interpreter
launcher for no reason.

## Consequences

- The Runtime Manager's compatibility check passes on a real JDK because `list_sort` exists.
  Verified against a real JDK 21 and the built jar at two levels: by calling
  `RuntimeCompatibility.validate` directly, and by running the command that was failing end to
  end — `oq runtime install` now downloads, checks and installs the pinned runtime, and
  `oq java` then reports the managed runtime in use.
- The keyed form costs one allocation per item to hold the cached keys. That is the price of
  the guarantee, and it is the same price `sorted(key=...)` pays.
- `sort_by` remains a documented builtin with a measured defect. That is recorded here and in
  the test suite rather than left to be rediscovered.
- `sort`'s misleading error on a `set` receiver is unchanged by this ADR and remains an open
  defect.
