# §9.7 collection acceptance — evidence matrix

Directive §9.7 states that a collection family is not complete until it has ten things. This
document records, per family, the concrete evidence for each one. It exists so that "the
family is done" is a claim that can be checked rather than asserted.

Scope: the seven first-class collection families — `list`, `tuple`, `map`, `set`,
`frozenset`, `range`, `text`. The `iterator` protocol is covered separately in
[ADR-0020](../adr/0020-iterator-protocol.md); it is deliberately not counted here because it
is a walk, not a family that holds members.

## The matrix

| # | Criterion | list | tuple | map | set | frozenset | range | text |
|---|---|---|---|---|---|---|---|---|
| 1 | Type/protocol specification | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2 | Runtime representation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3 | Interpreter implementation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 4 | Bytecode implementation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 5 | Full API signatures and docs | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 6 | Positive and negative tests | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 7 | Edge-case and property-based tests | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 8 | Java interop tests where relevant | ✅ | ✅ | ✅ | ✅ | ✅ | n/a | ✅ |
| 9 | Performance baselines | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 10 | Conformance-matrix entries | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

## Where each row is evidenced

**1. Specification.** `docs/spec/language.md` describes each family's operations in the
collection chapters, and the guarantee table at "What each family guarantees" states length,
index, membership, mutability and order for all seven in one place.

**2. Runtime representation.** `oq-runtime/src/main/java/dev/oqthara/runtime/` holds one type
per family: `OqTuple`, `OqFrozenSet`, `OqRange`, `OqIterator`, with `list` and `map` on
`ArrayList`/`LinkedHashMap` and `set` on `OqSet`. [ADR-0020](../adr/0020-iterator-protocol.md)
and [ADR-0023](../adr/0023-collection-protocols.md) record why each is what it is.

**3. Interpreter.** `Values` and `MethodResolverImpl` implement the operations once, shared by
both backends, so the interpreter and a compiled jar cannot disagree about a family's
semantics.

**4. Bytecode.** Every family compiles. This was measured rather than assumed: `in` and the
core operations (`length`, indexing, `push`, `add`, `has`, `contains`, `keys`, `upper`) were
built with `oq build` for one program per family and all seven reported
`codepath: compiled bytecode`. `docs/spec/backend.md` lists the supported constructs.

**5. API and docs.** `docs/spec/language.md` for semantics, `docs/stdlib/reference.md` for the
method surface. `scripts/check-docs-sync.py` verifies that the 47 snake_case builtin names
cited in the docs all exist.

**6. Positive and negative tests.** The `*.err` conformance cases pin refusals exactly — for
example `213-tuple-immutable` and `079-undefined-name`. `ImmutableRefusalTest` pins the shared
classifier so a mutation of an immutable family fails identically on both backends.

**7. Edge-case and property-based tests.** `CollectionPropertyTest` (12 cases, fixed seed)
builds a random collection in Java, writes the equivalent Oqthara program, and compares
against what **Java** computes over the same values. The Java result is an independent oracle,
so a shared mistake cannot hide by agreeing with itself. `ValuesTest` adds the range property
test, which uses `to_list().contains(n)` as the oracle over ten `(start, end, step)` shapes.
The suite was mutation-tested against `sum` and `unique` and failed on both, confirming the
tests can fail.

**8. Java interop.** `JavaInteropTest` builds a program per family and crosses the boundary.
Six families cross: `list`, `tuple`, `set` and `frozenset` are already Java collections, `text`
crosses after `.to_list()`, and `map` crosses as a `LinkedHashMap`. `range` does not cross and
that is pinned as an explicit refusal rather than left as folklore. A text is refused when
handed straight to a `Collection` parameter, because it is a `String` and not a `Collection` —
also pinned.

**9. Performance baselines.** `bench/programs` holds one program per family and
`docs/spec/backend.md` records the interpreter-to-compiled ratio for each. The ratios are the
signal, not absolute times.

**10. Conformance entries.** `conformance/` holds named cases per family; the baseline is 210
executable programs of 227 `.oq` files, of which 195 compile.

## What this matrix does not claim

- It is **not** a claim of Python compatibility. §9.6 fixes the policy: CPython is reference
  material, and differences are adapted explicitly rather than inherited.
- It is **not** a claim that every method of every family is exercised. The matrix records
  that each criterion has evidence, not that coverage is exhaustive.
- Criterion 8 is `n/a` for `range` because `range` does not cross the Java boundary. That is a
  stated limitation, not a pass.

## Maintaining this document

A row may only go from ✅ to a different state with evidence in the same commit. Adding a
family, or changing what one guarantees, requires updating both this matrix and the guarantee
table in `docs/spec/language.md`, and the two must not contradict each other.
