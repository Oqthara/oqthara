# ADR-0024 — Text is a sequence of code points

Status: accepted

## Context

Two documents promise that a text value is a sequence of characters counted in code
points. `docs/spec/language.md` says destructuring over text "yields its characters, one
per code point", and [ADR-0022](0022-hashability-and-frozenset.md) says "text is a
sequence of characters" in the section explaining why there is no `bytes` type.

Neither is true of the implementation. Text is backed by `java.lang.String`, and the
operations that walk or measure it split into two groups that disagree:

| Operation | Implementation | Unit |
|---|---|---|
| `.chars()` | `text.codePoints()` | code point |
| text slicing (`text[1:3]`) | `s.codePoints()` | code point |
| `.length()` | `s.length()` | UTF-16 code unit |
| `text[i]` | `s.charAt(i)` | UTF-16 code unit |
| `.to_list()` | `s.charAt(i)` loop | UTF-16 code unit |
| `.to_set()`, `.sorted()`, `.characters()` | `s.charAt(i)` loop | UTF-16 code unit |
| iteration (`for c in text`, `.iter()`) | `s.charAt(i)` loop | UTF-16 code unit |

Measured on `"a😀b"`, whose middle character is U+1F600 and therefore two UTF-16 code
units:

| Probe | Result |
|---|---|
| `"a😀b".length()` | `4` |
| `"a😀b".chars()` | `["a", "😀", "b"]` |
| `"a😀b".characters()` | `["a", "?", "?", "b"]` |
| `"a😀b".to_list()` | `["a", "?", "?", "b"]` |
| `"a😀b"[1]` | a lone high surrogate, printed as `?` |
| `"a😀b"[1:3]` | `"😀b"` — characters 1 and 2 |
| `for c in "a😀b"` | emits `a`, `?`, `?`, `b` |

So a string of three characters reports a length of four, `.chars()` disagrees with
`.to_list()` on the same value, and indexing and slicing address different units. The
`?` in the table is a lone surrogate: an invalid text value that cannot be encoded as
UTF-8. Writing one to a file would either corrupt it or throw, depending on the encoder.

This is worse than the two documentation bugs it was found under. A lone surrogate is a
value the language cannot round-trip: `"a😀b".to_list()` produces values that are not
text, and joining them back is not guaranteed to reproduce the original. The
inconsistency is silent — nothing warns, and the wrong answer looks like a right one.

## Decision

Text is a sequence of **code points**. Every operation that measures, indexes, walks or
slices text addresses code points, not UTF-16 code units.

This is what both documents already claim, and it is the only definition under which
`.length()`, indexing, slicing, iteration and `.chars()` agree. Java's `String` stays
the storage type — it is compact and its `codePoints()` stream is exact — but it is an
implementation detail behind the language, not the language's unit.

### Consequences

- `.length()` counts code points. `"a😀b".length()` is `3`.
- `text[i]` returns the i-th code point as a one-character text. `"a😀b"[1]` is `"😀"`.
- Iteration, `.to_list()`, `.to_set()`, `.sorted()` and `.characters()` yield code
  points, so none of them can produce a lone surrogate.
- Slicing stays code-point based and is unchanged.
- `"😀".reverse()` is `"😀"`, not the same two units in the other order.
- Indexing text becomes O(n) in the position, because locating the i-th code point in a
  UTF-16 buffer requires scanning. `.length()` becomes O(n) per call as well.

The last point is the real cost and is accepted deliberately. It is not a regression in
practice: text in Oqthara is short, and a program that indexes a very long text by
position is already doing something the language does not optimise for. The alternative —
storing text as an `int[]` of code points — would make indexing O(1) but multiply memory
by two to four for the overwhelmingly common ASCII case, which is the wrong trade for a
language whose text values are mostly short.

A cache is not introduced. `.length()` on a 10-million-character text would rescan on
every call, but the fix for a program doing that is to hoist the call, which it should
do anyway.

## Verification

Every row below is a probe run against the built CLI after the change.

| Expression | Result |
|---|---|
| `"a😀b".length()` | `3` |
| `"a😀b"[1]` | `"😀"` |
| `"a😀b".to_list()` | `["a", "😀", "b"]` |
| `"a😀b".chars()` | `["a", "😀", "b"]` |
| `"a😀b".characters()` | `["a", "😀", "b"]` |
| `"😀".reverse()` | `"😀"` |
| `for c in "a😀b"` | 3 iterations: `a`, `😀`, `b` |
| `"a😀b"[1:3]` | `😀b` |
| `"héllo".length()` | `5` |
| `"€".length()` | `1` (was already `1`; a BMP character is one unit) |
| `"abc".length()` | `3` (ASCII unchanged) |

The ASCII path must not change: every existing test that counts or indexes ASCII text
has to keep passing untouched.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| **Document that text is UTF-16 units** | Cheapest, and it makes the current code correct by fiat. Rejected because it makes `.chars()` and slicing wrong instead, forces users to reason about surrogates in a language that does not expose them, and cannot prevent `.to_list()` from yielding lone surrogates — the one outcome that is never acceptable. |
| **Grapheme clusters as the unit** | What a user means by "character": `"é"` as one thing whether composed or decomposed. It is the right long-term answer and the wrong next step: no JDK primitive computes it, it needs a boundary algorithm (`java.text.BreakIterator` or ICU), and it would make `.length()` disagree with Python, which the ecosystem is modelled on. Code points match Python 3, which is the stated reference. |
| **Store text as `int[]` of code points** | Makes indexing O(1) and removes the scan, but doubles or quadruples memory for ASCII text, the common case, and breaks `java.util.regex` and `String` interop that `replace`, `split`, `find` and `to_upper` rely on. |
| **Leave indexing as-is and only fix `.to_list()`/iteration** | Removes lone surrogates from the worst path but leaves `.length()` disagreeing with `.chars()` and indexing disagreeing with slicing. Half a definition is still no definition. |
| **Expose `bytes` and let users handle units themselves** | [ADR-0022](0022-hashability-and-frozenset.md) already defers `bytes` until file and network I/O needs it. Surfacing an encoding problem to users does not solve it. |

## Relationship to Python

Python 3 strings are sequences of code points, so `len("a😀b") == 3` and `"a😀b"[1] ==
"😀"`. This decision matches that, which is what the ecosystem reference in
[ADR-0019](0019-slicing.md) and the `str` methods modelled throughout the standard
library already assume. Python's own storage is flexible (one byte per character for
Latin-1, more for wider strings); Oqthara keeps UTF-16 storage and pays the scan instead,
which is a difference in performance, not in observable behaviour.

CPython 3.13 `Objects/unicodeobject.c` and PEP 393, consulted 2026-11-10 for the code point
unit and the indexing rule. Oqthara adopts the unit and the observable behaviour and rejects
the flexible per-string encoding, for the reason given above.
