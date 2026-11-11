# ADR-0041: Descriptive statistics, identifiers and ordered search

- **Status:** Accepted
- **Date:** 2026-11-11
- **Related:** [ADR-0033](0033-encoding-and-hashing.md), [ADR-0039](0039-executors-and-cancellation.md), [ADR-0040](0040-database-support.md)

## Context

Measuring the standard library rather than guessing at it
([`alpha-4-stdlib-inventory.md`](../governance/alpha-4-stdlib-inventory.md)) ranked the absent
domains by value against JVM cost. Three of them need no new language concept, no new value
type and no new dependency:

| Rank | Domain | Absent names | Why it sits here |
| --- | --- | --- | --- |
| 3 | `statistics` | 1 | mean/median/stdev are the first thing a scripting language is used for after text and files |
| 4 | `uuid` | 1 | stable identifiers for records, file names and correlation ids |
| 5 | `heapq` + `bisect` | 2 | ordered insertion and binary search are the algorithmic layer under `sort`/`sorted` |

Probing confirmed each was genuinely missing rather than merely undocumented:

```
print mean([1, 2, 3])
# error: 'mean' is not defined

print uuid()
# error: 'uuid' is not defined

print bisect_left([1, 3, 5], 4)
# error: 'bisect_left' is not defined
```

The three share a property that matters: **none of them needs a resource-lifetime construct**,
which is what blocks `itertools` (rank 8) and what made `decimal` (rank 7) a design decision
rather than a wrapper. This ADR is therefore an implementation decision, not a language one.

## Decision

### D1 — `statistics` is six functions, not a module of twelve

Offered: `mean`, `median`, `variance`, `stdev`, `mode`, `percentile`.

Declined from Python's `statistics`, with reasons: `fmean`, `geometric_mean`,
`harmonic_mean` (each is one expression over `mean`, and a language this size does not need
three spellings of averaging), `quantiles`/`multimode` (they return shapes that need their own
decisions about ties and boundary cases), `NormalDist` and the linear-regression helpers (they
are a statistics course, not a primitive), `pvariance`/`pstdev` (see D2).

### D2 — Sample, not population, and the name says so

`variance` and `stdev` divide by `n − 1`. Python's `statistics.variance` also divides by `n − 1`
and its `pvariance` divides by `n`; offering only one forces a choice, and the honest choice for
data drawn from a larger population is the sample form.

This is a real trap: dividing by `n` understates the variance of a sample, and the error is
invisible on large inputs. Two mitigations, because a documented footnote is not a control:
`variance`/`stdev` **require at least two items** and raise `error: variance() needs at least two
items` for a singleton, rather than silently returning `0`. A population variance of a
one-element list is genuinely zero; a *sample* variance of it is undefined, so returning zero
would be the wrong answer stated confidently.

### D3 — Empty and singleton inputs are program errors with specific messages

| Call | Result |
| --- | --- |
| `mean([])` | `error: mean() needs at least one item` |
| `median([])` | `error: median() needs at least one item` |
| `variance([1])` | `error: variance() needs at least two items` |
| `mode([])` | `error: mode() needs at least one item` |
| `percentile([], 50)` | `error: percentile() needs at least one item` |

No function returns `nothing` for an empty list. A missing answer and the number zero are
different facts, and a statistics function that conflates them is worse than an absent one.

### D4 — Ties are resolved and documented, not incidental

- `median` of an even-length list is the mean of the two middle values, so it can be a decimal.
- `mode` returns the **smallest** most-frequent value when several tie. Python raises
  `StatisticsError` for a multimodal input; returning the smallest is a documented total
  function, which composes better inside a program than a call that sometimes fails. A
  frequency table is a one-liner for a caller that wants the whole picture.
- `percentile(items, p)` uses **linear interpolation between closest ranks** (the same rule as
  Python's `statistics.quantiles(method='inclusive')` and NumPy's default), with `p` in `0..100`.
  `p` outside that range is a program error rather than a clamp, because a clamp turns a typo
  into a plausible answer.

Interpolation is stated because the alternatives are not equivalent: on `[1, 2, 3, 4]`,
`percentile(items, 50)` is `2.5` under this rule and `2` or `3` under nearest-rank rules.

### D5 — `uuid()` is v4 only, and the hyphenated lowercase form is the only output

`uuid()` returns a version-4 (random) UUID in canonical 8-4-4-4-12 lowercase form, drawn from
`UUID.randomUUID()`, which uses a cryptographically strong generator on every supported JDK.

Version 1 (time- and MAC-based) is deliberately **not** offered: it leaks the host's MAC address
and the wall clock into every identifier a program mints, which is a privacy defect in a
correlation id. Version 7 is not offered either — it is time-ordered, which makes identifiers
guessable, and the ordering benefit is not worth a second constructor for this language.

Validation is offered as `is_uuid(text)` rather than as a parser that returns a value, because
there is no UUID type to return. It accepts the canonical hyphenated form only: the brace,
`urn:uuid:`, and 32-character unhyphenated spellings are all `false`. A permissive validator
would accept strings that other systems reject, which is the wrong direction for a check whose
purpose is refusing input.

### D6 — `heapq` and `bisect` are functions over lists, not a heap type

Offered: `heap_push(list, value)`, `heap_pop(list)`, `bisect_left(list, value)`,
`bisect_right(list, value)`.

The heap functions mutate the list in place and return it (for `heap_push`) or the smallest
element (for `heap_pop`), matching how `push`/`pop` already behave on lists. There is no heap
value: a heap is an array with an invariant, and a language that already has arrays should not
grow a second one that is secretly the same thing.

`bisect_left` returns the index of the first element **not less than** `value`; `bisect_right`
the first **greater than** it. Both assume a sorted list and do not check: verifying sortedness
on every call would turn a `O(log n)` primitive into `O(n)` and punish the correct caller for
the incorrect one. The distinction between the two is the whole reason both exist — on
`[1, 2, 2, 3]`, `bisect_left(..., 2)` is `1` and `bisect_right(..., 2)` is `3`.

### D7 — Ordering for heaps and `bisect` is the language's existing comparison

Values are ordered with the same comparison the `<` operator uses, so a list of numbers and a
list of text both work and a mixed list fails with the same diagnostic it would get from `sort`.
No separate key function is introduced: `sort_by` covers that need and already exists.

## Consequences

- **12 new names** — 131 → 143 globals, 142 → 154 registrations. The inventory's ranking table
  loses ranks 3, 4 and 5 to delivery; rank 2 (compression) becomes the highest-ranked domain
  still absent.
- **Four domains move from "absent" to "covered"**: `statistics` [A], `uuid` [A], and `heapq`
  and `bisect` [A]. Filesystem pattern matching was the first, in `a4df608`; this is the second
  batch, and the first that needs no new file-level mechanism.
- **A sample-versus-population decision is now baked into a name.** A future `population_variance`
  can be added without breaking anything, because the existing name is the sample form and its
  documentation says so.
- **No new type, no new dependency and no new lifetime rule**, so nothing in this ADR constrains
  a later concurrency or resource-management design.
- **`uuid()` is not a source of unguessable secret material.** It is an identifier generator; a
  token needs `secure_random_hex`, which already exists and is documented as the cryptographic
  primitive. This is stated in the spec so the two are not confused.

## Alternatives considered

**Offer `pvariance`/`pstdev` too, as Python does.** Rejected: with both spellings available and
no type to distinguish them, callers pick by name recognition rather than by intent, and the
wrong choice is silent. One documented rule is a smaller surface with fewer wrong answers.

**Return `nothing` from `mean([])`.** Rejected per D3. In a language where `nothing` is a normal
value that programs test with `is_nothing`, returning it for an empty list would make
`mean(xs) == nothing` the idiom for an empty input, and a caller that forgot the test would get
a confusing type error two steps later instead of a precise one at the call.

**Expose a `Heap` value with encapsulated storage.** Rejected per D6: it would need equality,
iteration, printing and a conversion table, which is exactly the cost that kept `bytes` out in
ADR-0033.

**Sort inside `bisect` for safety.** Rejected per D6. It changes the complexity class of the
primitive and hides a caller's bug rather than reporting it.
