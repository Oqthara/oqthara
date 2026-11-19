# ADR-0072 — List methods return new lists and never mutate their receiver

**Status:** Accepted
**Date:** 2026-11-20
**Related:** [ADR-0069](0069-oqthara-stays-dynamically-typed.md) (Oqthara stays dynamically
typed), `MethodResolverImpl` case `"sort"`, `docs/spec/language.md:505` (list methods) and
`:689` (`sorted`), row L5.7 of
[`alpha-7-language-core-capability-matrix.md`](../governance/alpha-7-language-core-capability-matrix.md)

## Context

The Alpha 7 language-core audit recorded L5.7 as **Ambiguous**, and the ambiguity was in the
specification rather than in the program. `docs/spec/language.md:689` states that the free function
`sorted` returns a **new list** and never reorders its argument. Line 505 lists `.sort()` among the
list methods and says nothing about whether it mutates. Meanwhile every method in that list whose
name sounds like a mutator — `.push`, `.pop`, `.insert`, `.remove`, `.clear`, `.remove_value` —
does mutate, so a reader's default assumption about `.sort()` is wrong.

The audit could not resolve it from the specification. It resolved it from the implementation, and
that is the evidence this record acts on: `MethodResolverImpl` case `"sort"` builds
`new ArrayList<>(list)`, sorts the copy, and returns it. The receiver is untouched. So is every
other method that looks like it mutates but is in this same shape — `.reverse()` and `.sort(key)`
also copy, which was checked by running them rather than by reading one case and generalising:

```
xs = [3, 1, 2]
print xs.sort()      # [1, 2, 3]
print xs             # [3, 1, 2]
print xs.reverse()   # [2, 1, 3]
print xs             # [3, 1, 2]
xs.push(9)
print xs             # [3, 1, 2, 9]
```

The last line is the point. `.push` mutates; `.sort` and `.reverse` do not. The API has both kinds
of method under names that do not distinguish them, and a specification that stays silent about
which is which makes every one of them a guess.

## Decision

A list method returns a new list and leaves its receiver alone, unless it is one of the small set
of methods documented as mutating. That set is `.push`, `.pop`, `.insert`, `.remove`,
`.remove_value`, and `.clear`. Everything else — including `.sort()`, `.sort(key)`, `.reverse()`,
`.map`, `.filter`, `.take`, `.skip`, `.unique`, `.flatten`, `.clone` — returns a new list.

The mutating methods return the removed value or the receiver rather than a new list: `.pop()` and
`.remove(i)` return what they took, and `.clear()` returns `nothing` after emptying the list. That
is a second distinction the specification does not currently draw, and the conformance case pins
it, because a method that mutates *and* returns the receiver would make `xs.clear()` look like a
value where the intended reading is a statement.

This is documented rather than changed, for two reasons.

The non-mutating form is the one that composes. `xs.sort()` returning a sorted list can be written
inside an expression, passed to a function, and used twice without the second use seeing the
first's side effect. A mutating `.sort()` would make `print xs.sort()` and `print xs` disagree
about what happened, and it would make `.sort()` unusable wherever a value is wanted rather than a
statement.

Changing it would be the larger change, not the smaller one. Four call sites in the standard
library and an unknown number in `examples/` rely on the copy; the mutating methods are the
minority and they are already the ones whose names announce themselves. The specification is
missing one sentence, and the remedy for a missing sentence is the sentence.

The mutating set is stated explicitly rather than as "the methods whose names suggest mutation",
because that phrase is what caused this ambiguity. A future method named `.shuffle` would be
assigned correctly by the list and incorrectly by the phrase.

## Consequences

`docs/spec/language.md` gains a sentence at the list-methods paragraph naming both groups, and a
conformance case pins the four lines above plus `.take`/`.skip`/`.unique` returning new lists and
`.push`/`.pop`/`.clear` mutating in place. The case is what makes L5.7 Complete rather than
Ambiguous.

Chaining becomes the documented style: `xs.filter(f).sort().take(3)` reads left to right because
nothing in the chain mutates. This is worth stating in the spec because it is the composition the
non-mutating choice was made for, and a reader who assumes mutation will not chain.

`.clone()` returning a new list and `.push` mutating in place means two methods whose names suggest
different things and whose behaviour is the reverse of what a C++ or Java reader expects. The spec
says which is which; it does not claim the names are ideal, and renaming them is not a change §4
authorizes.

This decision says nothing about maps and sets. Map and set mutation has its own surface, and if
it turns out to be equally undocumented it needs its own row and its own record rather than an
extension of this one.
