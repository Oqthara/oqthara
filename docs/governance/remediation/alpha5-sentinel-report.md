# Alpha 5 Sentinel remediation report

Baseline: `6954a86` (`origin/main` at the time of the audit).
Scope: issues #140–#156 in `Oqthara/oqthara` for groups 1–3, the JSON, CSV,
URL and text-wrapping defects #157, #160–#162, #170, #172, #173, #177, #179 and #186
for group 4, and the numeric-semantics and statistics defects #163–#169 and #174 for
group 5.
Authority: independent remediation agent, working only in isolated worktrees on
`sentinel/alpha5/...` branches. The Primary AI works Alpha 4 in parallel; no file,
branch or worktree of the Primary AI or of the user's working copy is touched, and
nothing is merged into `main` by this report.

Defects are reproduced through the real CLI — both the interpreter
(`java -jar oq.jar run`) and the bytecode backend (`java -jar oq.jar build` then
`java -jar <program>.jar`) — before any source change, and each fix is re-verified on
both backends.

## Status board

| Issue | Defect | Group | Status |
| --- | --- | --- | --- |
| #140 | `median`/`percentile` overflow at the ends of the double range | 1 | Fixed, verified, tests added |
| #143 | `is_even`/`is_odd` narrow the decimal to `long` first | 1 | Fixed, verified, tests added |
| #144 | `abs`/`floor`/`ceil`/`round` methods disagree with the builtins | 1 | Fixed, verified, tests added |
| #154 | `range`-family builtins silently truncate fractional bounds | 1 | Fixed for `range`, `range_list`, `round(x, places)`, `random(a, b)`; tests added |
| #141 | list `slice`/`subList`/`take`/`skip` truncate or saturate fractional bounds | 2 | Fixed, verified, tests added |
| #142 | text `substring`/`slice` truncate fractional bounds | 2 | Fixed, verified, tests added |
| #145 | `"x".repeat(-1)` reports an internal error and exits 70 | 2 | Fixed, verified, tests added |
| #146 | `"x".repeat(2.5)` truncates; `pad_start`/`pad_end` truncate | 2 | Fixed, verified, tests added |
| #147 | `"a😀b".split("")` yields lone surrogates | 2 | Reproduced; **blocked on a semantic decision** |
| #148 | `capitalize` does not upper-case a supplementary-plane first letter | 2 | Fixed, verified, tests added |
| #149 | `length(0 until MIN)` and friends overflow to a negative size | 3 | Fixed, verified, tests added |
| #150 | range `.first()`/`.last()`/`iter().next()` materialise the whole range | 3 | Fixed, verified, tests added |
| #151 | `MAX to MAX` `.to_list()` never terminates | 3 | Fixed, verified, tests added |
| #152 | `range(MIN, MAX, 3).contains(1)` is wrong through `%` overflow | 3 | Fixed, verified, tests added |
| #153 | `contains(9.223372036854776e18)` and `2^63 in r` are wrongly true | 3 | Fixed, verified, tests added |
| #155 | `for n in 0 to 10000000` exhausts memory before the first body run | 3 | Fixed, verified, tests added |
| #156 | `r[0]` and `r[-1]` materialise the whole range | 3 | Fixed, verified, tests added |
| #157 | A supplementary-character CSV delimiter is rejected | 4 | Fixed, verified, tests added |
| #160 | `url_join` removes dot segments above the root | 4 | Fixed, verified, tests added |
| #161 | `json_parse` silently overwrites duplicate keys | 4 | Fixed, verified, tests added |
| #162 | `json_parse` accepts raw control characters and non-JSON whitespace | 4 | Fixed, verified, tests added |
| #170 | `json_parse` accepts numbers with leading zeroes | 4 | Fixed, verified, tests added |
| #172 | The JSON writer can emit duplicate member names | 4 | Fixed, verified, tests added |
| #173 | `url_decode`/`query_parse` silently replace malformed UTF-8 | 4 | Fixed, verified, tests added |
| #177 | `url_join` misclassifies opaque absolute references | 4 | Fixed, verified, tests added |
| #179 | Text wrapping counts UTF-16 units instead of code points | 4 | Fixed, verified, tests added |
| #186 | `url_join` mishandles query-only, fragment-only and empty references | 4 | Fixed, verified, tests added |
| #163 | Whole numbers above `2^53` hash differently from the decimal they equal | 5 | Fixed, verified, tests added |
| #164 | Decimals print with twelve fractional places and do not parse back | 5 | Fixed, verified, tests added |
| #165 | `mean` is order-dependent when an intermediate sum overflows | 5 | Fixed, verified, tests added |
| #166 | `stdev` overflows its intermediate variance even when the result is finite | 5 | Fixed, verified, tests added |
| #167 | `mode` groups signed zero differently from language equality | 5 | Fixed, verified, tests added |
| #168 | heap operations reject mixed whole and decimal numbers | 5 | Fixed, verified, tests added |
| #169 | `bisect_left` disagrees with language ordering for signed zero | 5 | Fixed, verified, tests added |
| #174 | `bisect` returns the wrong insertion point for `NaN` | 5 | Fixed, verified, tests added |

Additional defects found while reproducing the backlog are recorded in
[Newly discovered defects](#newly-discovered-defects); they are reported rather than
silently folded into the fixes above.

Each group lands as its own Draft PR, so a test added by one group is not present on
another group's branch. A test class that belongs to a different group is therefore cited
by its file path rather than by class name, so that a reader on any one branch can tell
which citations that branch can actually run.

Every defect above was reproduced at `6954a86`, and that stays the reference point for the
"before" column throughout this document. Each branch has since merged `main` so that it can
be built and reviewed against current code; see
[Why CI did not run](#why-ci-did-not-run-and-what-it-taught).

## Group 1 — numeric semantics

Branch `sentinel/alpha5/group1-numeric`. Four issues, confined to `oq-stdlib` plus one
cross-backend CLI test, so that the change review does not overlap the range model that
Group 3 rewrites.

### #140 — median and percentile overflow

Reproduction at baseline:

```
median([1e308, 1e308])   -> infinity
median([-1e308, -1e308]) -> -infinity
median([4.9e-324, 4.9e-324]) -> 0.0
```

Root cause: the even-length median and the percentile interpolation both computed the
mean as `low + (high - low) * weight`. The difference overflows for two large values of
the same sign, and underflows to zero for two subnormals.

Fix: `Statistics.interpolate` keeps the exact answer for equal values, uses the
difference form only when it cannot overflow, and otherwise falls back to the weighted
sum `low * (1 - weight) + high * weight`, which cannot overflow when both ends share a
sign. `normalize` still turns a `-0.0` into `0.0`.

### #143 — parity of decimals

Reproduction at baseline: `1e20.is_even()` → `false`, `1e20.is_odd()` → `true`,
`1.5.is_even()` → `false`, `1.5.is_odd()` → `true`.

Root cause: the method narrowed the receiver with `longValue()` before testing the
parity, so a value past the 64-bit range saturated to `Long.MAX_VALUE` (odd) and a
fractional value truncated silently.

Fix: `MethodResolverImpl.isMultipleOfTwo` tests a `Long` directly, refuses a non-finite
or fractional decimal with `"is_even() needs a whole number, got " + display`, and
otherwise tests the decimal's own remainder so no narrowing happens. A whole decimal
therefore has the parity of the whole number it equals, and a fractional number has
none.

### #144 — method/builtin divergence

Reproduction at baseline: `1e300.floor()`, `1e300.ceil()` and `1e300.round()` all
returned `9223372036854775807` while the builtins returned `1e300`;
`(-9223372036854775808).abs()` returned a negative number while
`abs(-9223372036854775808)` returned a positive decimal.

Root cause: the method surface carried its own narrowing cast that the builtins did not,
so the two forms were separate implementations that drifted at the edges of the range.

Fix: the methods delegate to the builtins' implementation
(`Stdlib.abs/floor/ceil/round(Object, Span)`), so there is one implementation and no
possible divergence. `abs` of the lowest whole number has no whole-number answer; both
forms return the positive decimal rather than a negative number.

### #154 — fractional whole-number arguments

Reproduction at baseline: `range(0, 5, 1.5)` walked `0..5` by `1`, `range_list(0.5, 5.5)`
returned `[0, 1, 2, 3, 4]`, `round(1.25, 1.5)` used one place, and `random(1.5, 2.5)`
drew from `1..2`.

Root cause: `Stdlib.wholeNumber` truncated a `Double` with `longValue()` instead of
refusing a value that is not a whole number, and several call sites did not route
through it at all.

Fix: `wholeNumber` returns a `Long` unchanged, refuses a non-integral or out-of-range
decimal with `"<builtin>() needs a whole number, got <value>"` and a help line, and
`range_list`, `random(a, b)` and `round(x, places)` now validate every count through it.
A whole decimal such as `2.0` is still accepted, because it is the same value as `2`.

### Verification

Every reproduction was re-run on both backends after the fix; the observed values are
recorded in the Draft PR body. Error paths are byte-identical between the interpreter
and bytecode, including the `--> file:line:col` mark and the `help:` line, and exit
with status 1 rather than 70.

Tests added or extended:

- `StatisticsTest` — three tests at both ends of the double range and in the subnormal
  range.
- `oq-stdlib/src/test/java/dev/oqthara/stdlib/NumberMethodBuiltinParityTest.java` (new,
  on the Group 1 branch) — method and builtin answers past the 64-bit range, the
  lowest-whole-number `abs`, the wrong-receiver refusal, and parity of values with no
  `long` representation.
- `StdlibFuzzRegressionTest` — fractional and out-of-range whole-number arguments are
  refused rather than truncated.
- `BytecodeCompilerTest` — a fractional range bound is refused identically on both
  backends.

Documentation: `docs/spec/language.md` now states the whole-number rule and the
method/builtin parity promise, and notes that the statistics ends of the range are not
a special case; `docs/stdlib/reference.md` repeats the parity promise on the number
section.

## Group 2 — text and sequence bounds

Branch `sentinel/alpha5/group2-text-and-index`, cut from `6954a86` so that it is
reviewable without Group 1. Five issues plus the #147 analysis. The whole group is one
rule — how a count or an index argument is read — applied at the points that were each
narrowing on their own.

### #141 and #142 — fractional and non-finite bounds

Reproduction at baseline:

```
xs = [10, 20, 30, 40]
xs.take(1.5)      -> [10]              xs.take(2.0)  -> [10, 20]
xs.skip(1.5)      -> [20, 30, 40]      xs.take(NaN)  -> []
xs.slice(1.5, 3.5)   -> [20, 30]       xs.take(1e300) -> [10, 20, 30, 40]
xs.subList(1.5, 3.5) -> [20, 30]       xs.skip(1e300) -> []
"abcdef".substring(1.5, 4) -> "bcd"    "abcdef".slice(1.5, 4) -> "bcd"
```

Root cause: `MethodResolverImpl.resolvedBound` and `clampIndex` narrowed a `Double` with
`longValue()` before clamping or checking a range, so `1.5` became `1`, `NaN` became `0`
and `1e300` saturated to `Long.MAX_VALUE`. The text methods had their own copy of the same
narrowing. `xs.take("a")` reached a raw cast and escaped as
`internal error: ClassCastException`, exit code 70 — a defect found while reproducing, not
in the audit backlog.

Fix: `Values.integerIndex(Object, Span, String)` is now the single place a count or a bound
is read, and the resolver's bound handling and the text methods both call it. A `Long` is
returned as is; a `Double` must be whole (`must be a whole number, got decimal`) and must
fit in a 64-bit value (`is out of range: <value>`, with a help line naming the bounds); a
non-number is refused as `must be a whole number, got <type>`. Bounds are then clamped in
`long` arithmetic, so the clamp cannot overflow either.

A whole decimal is still accepted — `xs.take(2.0)` is `xs.take(2)` — because
`1 == 1.0` (ADR-0017) makes them the same value. Refusing `2.0` while accepting `2` would
contradict the language's own equality rule, so the tests assert that half of the contract
too.

### #145 and #146 — repeat and pad counts

Reproduction at baseline: `"x".repeat(-1)` reported
`internal error: IllegalArgumentException: count is negative: -1` and exited 70;
`"x".repeat(2.5)` was `"xx"`; `"x".pad_start(9.5, "0")` and `pad_end` produced nine
characters, one short of the requested width.

Root cause: `repeat` passed the count straight to `String.repeat`, whose contract allows
only a non-negative count, and both `repeat` and `pad` narrowed with `longValue()`
first.

Fix: both go through `Values.integerIndex` for the whole-number rule.
`repeat` additionally refuses a negative count as
`repeat() needs a count of zero or more, got -1` with a help line, and refuses a product
that would not fit in a `String` as `repeat() count is too large: <n>` **before**
allocating, guarded on `times > Integer.MAX_VALUE / text.length()` so the guard itself
cannot overflow. `pad` refuses a width that would not fit in an `int`.

### #148 — capitalize and the supplementary plane

Reproduction at baseline: `"𐐨x".capitalize()` returned `"𐐨x"` unchanged, while
`"hello".capitalize()` returned `"Hello"`.

Root cause: `Character.toUpperCase(text.charAt(0))` reads the first UTF-16 unit, not the
first code point. The letter U+10428 is a surrogate pair, so `charAt(0)` returns half of
it, and the `char` overload of `toUpperCase` cannot map a character outside the basic
multilingual plane at all.

Fix: `capitalize` reads `text.codePointAt(0)`, maps with `Character.toUpperCase(int)`,
writes the result with `Character.toChars`, and appends the rest from
`Character.charCount(first)`. `"𐐨x"` is now `"𐐀x"`, and a first character with no upper
case (a digit, an emoji) is kept as written rather than truncated to one half of a pair.

### #147 — split on the empty string

`"a😀b".split("")` returns five entries, and the first and last are empty. That is
Java's `String.split` semantics for an empty pattern, not a decision this language made.
Two readings are defensible:

- **(a) One entry per code point**, `["a", "😀", "b"]`. Consistent with ADR-0024, in
  which text is a sequence of code points: `chars()`, `to_list()` and `reverse()` all
  produce exactly this. The result has one entry per character, which is what a caller
  splitting on `""` almost always wants.
- **(b) One boundary between every pair of code points**,
  `["", "a", "😀", "b", ""]`. This is what Python, Ruby and JavaScript do for a
  zero-length separator, and it preserves the identity that joining the result with the
  separator restores the input.

**Recommendation: (a).** Under (b) the result length is `n + 1` for a separator that is
not in the text, which is a surprising answer for the one separator that cannot appear;
under (a) `split("")` is the inverse of `join("")`, and the "restores the input" identity
becomes `join("")` of the characters. (b) is the more familiar answer to a programmer
arriving from another language, which is the argument for it.

No behaviour change is proposed here. Changing the result to (a) would change every
program that splits on the empty string, and the alternative is a one-line decision a
maintainer can make without re-reading this branch; the analysis is attached instead.

### Verification

Every Group 2 reproduction was re-run on both backends after the fix. The fractional,
non-number, non-finite and out-of-range refusals are byte-identical between the
interpreter and bytecode, including the `--> file:line:col` mark, and exit with status 1
rather than 70 — including `"x".repeat(-1)`, which was a JVM failure before.

Tests added:

- `oq-stdlib/src/test/java/dev/oqthara/stdlib/TextAndListBoundTest.java` (new, 16 tests,
  on the Group 2 branch) — the refusals for every method, the accepted
  whole-decimal case, the non-number case, non-finite and past-64-bit bounds, the
  `repeat` domain and its overflow guard, `pad` on both sides, and `capitalize` on a
  supplementary-plane letter, a digit and an emoji.
- a `BytecodeCompilerTest` case on the Group 2 branch (nine programs, each asserted to
  exit 1 on both backends, to contain no `internal error` and no stack trace, and to
  produce the interpreter's diagnostic lines in the compiled output).

Documentation: `docs/spec/language.md` gains a "Counts and bounds" subsection stating the
whole-number rule, the accepted whole-decimal case, the out-of-range refusal and the
`repeat` domain, and `capitalize` joins the code-point table with `"𐐨x"` → `"𐐀x"`.

## Group 3 — the range model

Branch `sentinel/alpha5/group3-range-model`, cut from `6954a86` so it is reviewable
without Groups 1 and 2. Seven issues, and they are one defect wearing seven faces: a
range was **enumerated** everywhere it was used — for a count, a first item, an index, a
loop, a list — instead of being **described** and walked lazily. Every one of #149–#156
follows from that.

### Root cause

`OqRange` was a record whose only operations were `size()`, `toList()` and `contains(long)`.
`Values` implemented `length`, indexing, slicing, ordering and iteration by calling
`toList()` and working on the resulting `ArrayList`. So any operation on a range, however
small its answer, allocated one `Object` per element:

- `(0 to 10000000).first()` built ten million boxed `Long`s (#150).
- `(0 to 10000000)[0]` did the same (#156).
- `for n in 0 to 10000000` materialised the range before the first body ran (#155).
- `(MAX to MAX).to_list()` and `length(0 until MIN)` computed the count with arithmetic
  that wrapped, so the allocation either never stopped (#151) or was written as a negative
  `int` size (#149).
- `contains` computed `(value - start) % step`, which overflows for a range spanning the
  type (#152), and `Values.contains` converted even a `Long` item through `double`
  (#153).
- A counter loop lowered to a hidden `_i` variable incremented by a fixed `1` and compared
  with `<=`, so `for i in MAX to MAX` added one to `MAX` and wrapped to `MIN`, looping
  forever (#155's sibling, found here).

`for n in a to b` also diverged between backends for fractional bounds: the interpreter
truncated with `asLong`, while bytecode kept the decimals and compared them generically
(#149's neighbourhood, reproduced as newly discovered defect 3).

### The fix

`OqRange` is now a lazy, immutable `Iterable`:

- `size()` computes the count with `BigInteger` and raises `this range is too long to count`
  when the answer has more than 63 bits, instead of wrapping to a negative number. An
  empty range still counts 0, so `length(0 until -9223372036854775808)` is 0, not `MIN`.
- `last()` is the last value the walk actually reaches, computed with
  `Math.floorMod` offsets so `0 to 10 by 3` ends at 9 and neither `bound - start` nor a
  remainder can overflow.
- `iterator()` stops at `last()` and throws `NoSuchElementException` correctly, so one pass
  costs O(1) memory no matter how wide the range is.
- `toList()` refuses more than `Integer.MAX_VALUE` elements with
  `this range is too long to turn into a list` rather than exhausting the heap, and
  pre-sizes only `min(count, 1024)`.
- `contains(long)` tests congruence with `Math.floorMod`, never `value - start`.
- `isEmpty()` gives `truthy` an answer that cannot raise even for an uncountable range.

`Values` now asks the range for what it needs instead of calling `toList()`: `index` and
`slice` compute the value at an offset arithmetically, `size` reports the `BigInteger`
count, `iterable` returns the range itself, and `contains` keeps a `Long` item exact,
converting a `Double` only when it is whole and fits. A range slice counts its window
first and refuses one longer than a list can hold
(`this slice is too long to produce`), then materialises exactly that many values, so
neither a huge forward window nor a step that jumps past the end can run away.
`Collection.rangeInclusive`, `rangeExclusive` and the new `counterRange` validate both
bounds through one `Values.wholeNumber`, so the interpreter and bytecode read a
fractional, textual or boolean bound the same way.

The counter loop no longer keeps a variable. `Lowering.forRange` emits the new
`Ir.CounterRange(boolean inclusive)` opcode, which calls
`Collections.counterRange(from, to, inclusive)`; the runtime builds the range with the
direction taken from the bounds, and the loop walks it with the same iterator template
`for x in xs` already used. **The step and end arithmetic is gone, so it cannot wrap and
cannot disagree with the interpreter** — the interpreter's `Ast.ForRange` runs the same
`counterRange` and the same iterator. One new opcode buys parity by construction rather
than by two implementations agreeing.

`MethodResolverImpl.rangeMethod` follows: `first`, `last`, `min` and `max` are O(1)
arithmetic on the range's own fields, `contains` keeps the `Double`/`fitsInLong` guard,
and `reverse` refuses a `Long.MIN_VALUE` step it cannot negate.

### Reproduction and verification

Every row was reproduced at `6954a86` through the real CLI on **both** backends, then
re-verified the same way after the change.

| Issue | Program | Before | After |
| --- | --- | --- | --- |
| #149 | `length(0 until -9223372036854775808)` | `-9223372036854775808` | `0` |
| #149 | `length(-9223372036854775808 to -1)` | `-9223372036854775808` | raises `this range is too long to count` |
| #149 | `length(0 to 9223372036854775807)` | `-9223372036854775808` | raises `this range is too long to count` |
| #149 | `length(0 until 9223372036854775807)` | `-9223372036854775808` | `9223372036854775807` |
| #150 | `(0 to 10000000).first()` under `-Xmx32m` | out of memory, exit 1 | `0` |
| #151 | `(9223372036854775807 to 9223372036854775807).to_list()` | out of memory, exit 1 | `[9223372036854775807]` |
| #152 | `range(MIN, MAX, 3).contains(1)` | `false` | `true` |
| #153 | range containing `9.223372036854776e18` | `true` | `false` |
| #155 | `for n in (0 to 10000000)` + `break` under `-Xmx32m` | out of memory, exit 1 | `0` |
| #156 | `(0 to 10000000)[0]` under `-Xmx32m` | out of memory, exit 1 | `0` |
| #156 | `(0 to 4000000000)[0:3000000000]` | out of memory, exit 1 | raises `this slice is too long to produce` |

The two memory tests are the load-bearing ones: they run under `-Xmx32m`, which is less
than a tenth of what the old path needed for a ten-million-element range.

Tests added or extended:

- `oq-core/src/test/java/dev/oqthara/runtime/RangeArithmeticTest.java` (new, 17 tests) — the count at all four corners of the type, the
  `toList` refusal, `contains` past 2^53 and for a strided range spanning the type, the
  last *reachable* value for an unaligned step, membership agreeing with the walk at both
  ends, the empty-range `first`/`last` messages, the truthiness of an uncountable
  range, and the slice guards: a forward window too long for a list, a reversed window
  counted the same way, and a window at the top of the type whose offset product
  overflows a `long`.
- `BytecodeCompilerTest` — `aRefusedCounterLoopBoundReportsTheSameDiagnosticOnBothBackends`
  (six programs), `aCounterLoopAtTheLargestNumberTerminatesOnBothBackends`, and
  `aLongRangeLoopDoesNotMaterialiseTheRange`, which runs the jar under `-Xmx32m`.
- `LoweringTest` — `lowersForRange` now asserts the `CounterRange` opcode and the iterator
  shape and the **absence** of `_i` and `<=`; `descendingRangeIsNotSimplifiedIntoAnAscendingLoop`
  and the new `anUntilLoopBuildsAnExclusiveCounterRange` pin the direction rules.
- `anInternalErrorIsClassifiedAsExitCode70` in `BuildJarTest.java` replaces
  `aCompiledJarReportsAnInternalErrorWithExitCode70`, which existed at baseline. Its only
  vehicle was `print 1 to true`, which reached the old bound check and threw
  `IllegalArgumentException`; that program is now an ordinary user error, which is the
  point of #149's neighbour. The 70-versus-1 contract is asserted directly at
  `EmbeddedMain.exitCodeFor` and `EmbeddedMain.renderFailure` instead, so the contract stays
  pinned without depending on a defect existing.

Documentation: `docs/spec/language.md` corrects the claim that a range "counts down" when
`a > b` (a range *value* is empty; only a counter loop counts down), documents the
describe-don't-enumerate rule, the two cardinality refusals, and a new "Counter loops"
subsection. The claim that ranges "were already unaffected because both are converted to a
list before the loop starts" was also wrong and is corrected.

## Group 4 — JSON, CSV, URLs and text measurement

Ten defects, all in `oq-stdlib`, all reproduced through the real CLI before a line changed.
The two families share a cause: a helper measured with the wrong unit (a UTF-16 unit where the
language counts a code point, or a Java API's idea of a URL where RFC 3986 has its own), and a
parser that accepted inputs its specification excludes.

### #161, #162 and #170 — the JSON reader is stricter

`Json.object()` read a member's value before it could notice a repeated name, so
`{"x":1,"x":2}` kept only the last one and said nothing. It now remembers the names it has
already seen and refuses a repeat before reading the value. The comparison is on the
*decoded* name, so `{"a":1,"\u0061":2}` collides — an earlier version that compared the raw
source text would have let that through.

`skipWhitespace()` used `Character.isWhitespace`, which accepts `U+00A0`, `U+2028` and
`U+3000`; JSON allows exactly four characters. `string()` accepted raw `U+0000`–`U+001F` and
unpaired surrogates, both raw and through `\u` escapes. Both now refuse, naming the offending
code point.

`number()` accepted a leading zero (`01`, `-01`, `01.5`) and measured digits with
`Character.isDigit`, which accepts Arabic-Indic and fullwidth digits and then failed inside
`NumberFormatException`, escaping as an internal error instead of a diagnosed one. Leading
zeroes are refused with the offending text, and `isJsonDigit` now admits only `0`–`9`.

### #172 — the writer cannot produce what the reader refuses

Closing the reader makes the writer's `Map` branch able to emit a document that Oqthara itself
would reject: `1` and `"1"` are distinct keys but render as the same member name. `writeValue`
now tracks the names it has already emitted and refuses a collision rather than writing a file
that cannot be read back. The check is on the rendered name, so it catches the case the reader
catches.

### #160, #177 and #186 — `url_join` follows RFC 3986

The old implementation delegated to `java.net.URI.resolve`, which is not the algorithm the RFC
describes: it lets dot segments climb above the root (`/b/c/d;p?q` joined with `../../../g`
produced `/../../g`), it rejects the opaque absolute references `mailto:`, `urn:` and
`file:/`, and it mishandles the reference forms that have no path at all. `urlJoin` is now
transcribed from RFC 3986 section 5.2 — `refScheme`, `refAuthority`, `refPath`, `refQuery`,
`refFragment`, `mergePaths`, `removeDotSegments` as the literal 5.2.4 loop, and `buildUrl` —
with `URI.create` used only to validate the result. A reference whose first segment contains a
colon is a scheme only when the text before the colon is a valid scheme, and the RFC 3986
section 5.4 normal and abnormal examples all resolve as the RFC says they should.

The repository has a deliberate policy of percent-escaping path segments, which
`url_join` preserves: only the *path of a relative reference* is escaped, absolute references
pass through untouched. That is why `;x` and `g;x` come back escaped rather than verbatim; the
RFC's own table has no opinion on escaping and #186 asks for the policy to be kept.

### #173 — malformed percent-escapes are diagnosed

`url_decode` and `query_parse` decoded through a default `URLDecoder`-style path that replaced
every malformed byte with `U+FFFD`, so `%E9` and `%FF` produced a valid-looking string that had
never been in the input. Both now share `decodeFormComponent`, which decodes `+` to a space,
validates the `%XX` hex itself, and runs a strict `CharsetDecoder` with
`CodingErrorAction.REPORT`, so the error names the input and says what was wrong. Supplementary
characters are handled by encoding the surrogate pair together before decoding.

### #157 — a CSV delimiter is a code point

`csv_delimiter` validated its argument with `String.length()`, so a single emoji — one code
point, two UTF-16 units — was rejected as "more than one character". The delimiter is now an
`int` code point throughout (`csvDelimiter`, `csvParse`, `csvWrite`, `csvField`), compared with
`codePointAt` and counted with `codePointCount`, so a supplementary delimiter parses as one
separator and is quoted when it appears in data. A genuine multi-character string is still
refused.

### #179 — text is measured in code points

`wrap_text` and `shorten_text` measured and split with `String.length()` and `substring`, which
count UTF-16 units, so a width of 1 halved a surrogate pair and produced two lone surrogates.
Both now measure with `codePointCount` and split at a code-point offset, so an emoji is one
character wide and is never split.

### Reproduction and verification

Each row below was run through `java -jar oq-cli/build/libs/oq.jar run` on both the interpreter
and, for the sampled cases, the compiled backend.

| Input | Result |
| --- | --- |
| `{"x":1,"x":2}` | `duplicate object key 'x'` |
| `{"a":1,"\u0061":2}` | `duplicate object key 'a'` |
| a raw TAB inside a string | `unescaped control character U+0009` |
| `U+000B`, `\f`, `U+00A0`, `U+2028`, `U+3000` used as whitespace | rejected; ordinary spaces still parse |
| `{"n":01}` | `leading zero in '01'` |
| `\ud800` alone, `\ude00` alone, `\ud83d\ud83d` | rejected, naming the surrogate |
| `\ud83d\ude00` and a raw emoji | both parse to one character |
| a map with keys `1` and `"1"` | `cannot write two map keys as the JSON member '1'` |
| `csv_parse("left😀right", "😀")` | `[["left", "right"]]` |
| `url_join("http://a/b/c/d;p?q", "../../../g")` | `http://a/g` |
| `mailto:`, `urn:`, `file:/` references | returned verbatim |
| `?q=1`, `#new`, `""`, `?q=1#f`, `c/d?x=1#y`, `/root?z=2`, `//other.test/p` | each matches the RFC 3986 section 5.4 table |
| the 39-reference RFC 3986 section 5.4 matrix | all match except the two `;x` cases, which take the escaping policy above |
| `url_decode("a%20b+%C3%A9")` | `a b é` |
| `query_parse("q=x&r=%E9")` | `invalid UTF-8 in query 'q=x&r=%E9'` |
| `wrap_text("😀a", 1)` | `["😀", "a"]`, no surrogate split |
| `shorten_text("😀" repeated five times, 6)` | unchanged — five characters, not ten |

Test classes: `JsonFuzzRegressionTest` (16 tests, 10 new), and the new cases in
`UrlAndHttpTest`, `CsvAndDatesTest` and `TextWrapTest`. The full suite reports 1,422 tests with
0 failures, and `oq-core` line coverage stays at 91.5%.

## Group 5 — numeric semantics and statistics

Eight defects across `oq-runtime` and `oq-stdlib`. Two of them (#163, #164) are about a number
surviving a round trip through its own text or its own hash; the other six are one observation
repeated: an answer that exists was being reported as an error, or an ordering that the language
defines was being taken from Java instead.

### #163 — a whole number and the decimal it equals hash apart

`Values.equal` compares both numeric kinds by widening to `double` (ADR-0017), so
`9007199254740993 == 9007199254740992.0` is `true` — the `long` does not survive the trip
through `double`, and both sides land on the same value. Hashing did not follow: `Value.hash`
hashed a `Long` with `Long.hashCode` and a `Double` with `Double.hashCode`, which are different
functions of the same widening. Two values that are equal therefore hashed apart, which
`OqSet` and `OqMap` cannot tolerate.

The fix routes *both* kinds through one `hashNumber(double)`, so the hash is a function of the
widened value alone. The existing integral normalisation — so that `2.0` and `2` agree — is
still there, because `Double.hashCode(1.0)` and `Long.hashCode(1)` differ.

### #164 — decimals printed as a projection, not a representation

`Values.formatDouble` formatted every non-integral value with exactly twelve fractional places
and then trimmed trailing zeroes. That is not a representation of a binary64, it is a lossy
projection of one: `1e-13` printed as `0.0`, and any value needing more than twelve fractional
digits came back different from `number(value.text())`. Twelve was never going to be enough —
53 bits of mantissa is about 17 significant decimal digits.

The replacement takes the shortest text that round-trips. `Double.toString` already computes
those digits and both of the language's readers accept what it emits — `Lexer.number()` takes a
decimal point and a signed `e` or `E`, and `number(text)` routes anything containing `.`, `e` or
`E` through `Double.parseDouble` — so only the spelling is ours to choose. Scientific notation
is lower-cased, because the language's own literals are lower-case. A value whose scale is at
most twelve fractional places keeps the plain form the old formatter gave it; anything else
prints in exponent form. Integral values keep a trailing `.0`, and `-0.0` keeps its sign,
because `Values.compare` orders it below positive zero.

That boundary is deliberately drawn around the values the old formatter already printed
losslessly, so the change is confined to the ones it got wrong. Measured over 2,750 finite
doubles — every decade from `1e-320` to `1e308`, curated edge cases, 1,200 random bit patterns
and 900 sampled values — 1,088 failed to round-trip before and all of them round-trip now, 1,088
texts changed, and **no value that previously round-tripped prints differently**. An earlier
version of this fix used a magnitude window instead (`1e-4 ≤ |d| < 1e16`), which rewrote
`1e16` and `1e30` from expansions into exponents: output that was already correct and had no
defect behind it. The scale rule avoids that.

This one had a bug of its own on the way in, caught before it shipped: the trailing-zero trim
counted back over *all* trailing zeroes rather than only those after the decimal point, so
`1e15` collapsed to `10` in the intermediate version.

### #165 — `mean` was order-dependent

`total` accumulated left to right in `double` and rejected a non-finite running total. On
`[1e308, 1e308, -1e308]` the first two terms overflow before the negative one arrives, so
`mean` reported an overflow for a dataset whose mean is plainly `3.33e307`; the same three
numbers in any other order answered correctly. A mean that depends on permutation is not a
mean.

Every finite double is an exact decimal, so the sum is now taken in `BigDecimal`, which is
exact and therefore order-independent by construction. The overflow test moved to the exact
sum, so it fires only when the mathematical answer is out of range, never because an
intermediate step was. This also removes ordinary cancellation error: the exact sum of
`[0.1, 0.2, 0.3]` is `0.6`, where the rounded one is `0.6000000000000001`.

`mean([1e308, 1e308])` still reports `mean() overflowed`, and should: the exact mean `1e308` is
representable but the contract is that a mean whose sum is out of range has no double.

### #166 — `stdev` overflowed on values it could still take the root of

`stdev` computed `sampleVariance` and then took the square root, so `[1e308, -1e308]` squared
to `2e616`, overflowed to `infinity`, and the root could not recover it — even though
`sqrt(2) * 1e308` is perfectly representable. Squaring now happens in `BigDecimal` and the root
is taken at `DECIMAL128`, 34 significant digits, far more than the 17 a double carries, so the
final rounding is decided by the exact value rather than by the precision of the intermediate.

The classic sample is unchanged to the last bit: `variance` is `4.571428571428571` and `stdev`
is `2.138089935299395`. `variance` of the extreme pair is still `infinity`, which is honest —
the variance genuinely is out of range. The point of the split is that `stdev` no longer
inherits that failure.

Dividing the mean out in `BigDecimal` exposed a second problem, which a test caught: rounding
the mean to `DECIMAL128` first leaves a residue on values that have none. Three copies of
`1e308` have a mean needing 309 digits; the 34 that survive are not it, so every deviation came
out near `1e274` instead of at zero. The deviations are now written as the exact integer
`n·x − S` over `n`, deferring the division to a single step at the end, and
`stdev([1e308, 1e308, 1e308])` is `0`. That case also forced the sum to be read through a new
`exactTotal` rather than `total`: the three values sum to `3e308`, out of range, but the answer
is an ordinary zero, and refusing the sum would reject a question whose answer exists. A value
that is already infinite or `NaN` is still refused in both paths, so the language reports
`mean() overflowed` where it used to and never a Java `NumberFormatException`.

### #167 — `mode` split a signed zero from a zero

The grouping test was `Double.equals`, which is not the language's equality: it separates `0`
from `-0.0`, and it would separate a whole number from the decimal it equals. Grouping now uses
`Values.equal`, so `mode([0, -0.0, 1, 1])` is `0`. The result goes through the existing
`normalize`, so a whole result comes back as a whole number.

### #168 — the heap refused to compare what `<` compares

`heap_push` cast an element to `Comparable` and called `compareTo`. Java's `Long` and `Double`
refuse to compare with each other, so `heap_push([1], 1.5)` failed with "cannot compare decimal
with number" while the expression `1 < 1.5` succeeded. Both now defer to `Values.compare`, which
is the language's ordering (ADR-0041 D7), so the heap and the operator agree by construction.

The type check also had to move ahead of the mutation for the push to be atomic: a rejected
`heap_push(heap, "x")` leaves the array byte-identical.

### #169 and #174 — `bisect` ignored the language's ordering

`bisect` compared raw `double`s, so it had the opposite problem from the heap: it *lost* the
distinction the language draws. The language orders `-0.0` below `0.0` (`Values.compare`),
while `probe < target` sees them as equal, so `bisect_left([-0.0, 0.0, 1.0], 0)` returned `0`
instead of `1`. Both bounds now compare via `Values.compare`.

`NaN` is the case where raw comparison fails hardest: every comparison against it is false, so
the loop collapses every probe to "not less than" and returns `0` — the bottom of the list,
whatever the data. Under the language's ordering `NaN` sorts above every number, so
`bisect_left([1.0, 2.0], NaN)` is `2`. A `NaN` inside the list is handled by the same rule
rather than by a special case.

One deliberate non-choice: the list is still not checked for sortedness. ADR-0041 D6 says a
list is a list, and a search over an unsorted list is the caller's business.

### Reproduction and verification

Each row was run through the real CLI at `deefc3e` (before) and on this branch (after).

| Input | Before | After |
| --- | --- | --- |
| `9007199254740993 == 9007199254740992.0` | `true` | `true` |
| the same pair as set members | two elements | one element |
| the same pair as a map key and lookup | lookup misses | lookup hits |
| `print 1e-13` | `0.0` | `1.0e-13` |
| `number(1.1234567890123457.text()) == 1.1234567890123457` | `false` | `true` |
| `mean([1e308, 1e308, -1e308])` | `mean() overflowed` | `3333333333333333…000.0` |
| `mean([1e308, -1e308, 1e308, -1e308, 1e308])` | `200000…000.0` | `200000…000.0` |
| `mean([1e308, 1e308])` | `mean() overflowed` | `mean() overflowed` |
| `stdev([1e308, -1e308])` | `infinity` | `1414213562373095…000.0` |
| `stdev([1e308, 1e308, 1e308])` | `variance() overflowed` | `0` |
| `variance([1e308, -1e308])` | `infinity` | `infinity` |
| `variance([2, 4, 4, 4, 5, 5, 7, 9])` | `4.571428571429` | `4.571428571428571` |
| `stdev([2, 4, 4, 4, 5, 5, 7, 9])` | `2.138089935299` | `2.138089935299395` |
| `mode([0, -0.0, 1, 1])` | `1` | `0` |
| `heap_push([1], 1.5)` | `cannot compare decimal with number` | `[1, 1.5]` |
| `heap_push([1, 2], "x")`, then read the list | `[1, 2]` | `[1, 2]` |
| `bisect_left([-0.0, 0.0, 1.0], 0)` | `0` | `1` |
| `bisect_left([1.0, 2.0], NaN)` | `0` | `2` |

Both `mean` rows hold the same multiset of magnitudes and cancel to the same answer. The three-item
one put two `1e308`s side by side, so it overflowed before reaching the negative; the five-item one
reaches a negative first and already answered. Which of them failed was an accident of ordering,
which is the whole of #165. After the fix every order agrees. A large *integral* result is still
written out in full rather than as an exponent, exactly as the previous formatter wrote such
values — `…` abbreviates the omitted digits. `stdev` of one extreme pair was `infinity` before, not
an overflow, because the old code formed the variance as a difference of squares.

`NaN` is not constructible in the language by any of the obvious routes — `0.0 / 0.0`,
`sqrt(-1.0)` and `ln(-1.0)` are all domain errors, and `nan` is not an identifier. It *is*
reachable as `1e308 * 1e308 - 1e308 * 1e308`, which the test suite uses.

Test classes: `ValueTest` (3 new tests for #163), `ValuesTest` (4 new tests for #164, one of
them a 22-value round trip) and `StatisticsTest` (11 new tests covering #165–#169 and #174). The
suite goes from 1,476 tests at `deefc3e` to 1,494 with 0 failures, and `oq-core` line coverage
stays at 91.5%. Merged as `6c1b930` (pull request #204); a fresh clone of that commit builds and
reports 1,513 tests with 0 failures — 1,494 plus the 19 that arrived with #205 — and every row in
the table above was re-run there and returns the "After" value.

Regenerating the conformance baseline was part of the change: `230-math-transcendental.out`
lines 9, 18, 19 and 20 carried the old twelve-place projection. Line 17 is untouched because
it is a value the program rounds itself, so it still prints eleven places.

## Newly discovered defects

These are not in the audit backlog. Each was reproduced at `6954a86` and is reported
here rather than bundled into an unrelated fix.

1. `print "a" to 3` reports `internal error: IllegalArgumentException: not a number:
   class java.lang.String` and exits 70, where the language rule is a user error with
   exit 1. A parse of `a to b` in expression position is not type-checked before the
   range is built.
2. `xs[1e300]` reports the saturated index `9223372036854775807` instead of refusing
   the out-of-range index, because `Values.integerIndex` narrows before it checks.
3. A fractional counter loop diverges between backends: `for i in 1.5 to 3.5` runs
   `1, 2, 3` on the interpreter and `1.5, 2.5, 3.5` on bytecode. The interpreter
   truncates the bounds; the compiler keeps them and compares generically.
4. A subnormal `percentile` underflowed to `0.0` at baseline, the same root cause as
   #140 but on a different code path, and is fixed by the same change.
5. At `Long.MAX_VALUE` scale the method and builtin forms disagreed by more than the
   #144 examples, which is the same root cause and is covered by the parity test.
6. `xs.take("a")` reported `internal error: ClassCastException: class java.lang.String
   cannot be cast to class java.lang.Number` and exited 70, because the count was cast
   before it was type-checked.
7. `Values.contains` converted every member candidate through `double`, including a
   `Long` item. For a range spanning more than 2^53 this made `contains` and `in`
   disagree with the walk: `r = 0 to 9223372036854775806` answered
   `r.contains(9223372036854775806)` as `true` but `9223372036854775806 in r` as
   `false`, and `9007199254740993 to 9007199254740993` was the same. ADR-0026 makes
   `x in r` and `r.contains(x)` the same question, so one of them was wrong.
8. A counter loop ending at `Long.MAX_VALUE` never terminated:
   `for i in 9223372036854775807 to 9223372036854775807` printed `9223372036854775807`,
   then `-9223372036854775808`, `-9223372036854775807`, and so on indefinitely. The loop
   added a step of `1` to its hidden counter and re-tested `<=`, so the counter wrapped
   while the condition stayed true.
9. *(Found while writing the Group 3 tests, then fixed within the same branch.)* The
   first version of `OqRange.last()` returned the range's bound rather than the last value
   the walk reaches. For an unaligned step the walk never produces the bound, so a
   strided walk past the end of the type ran indefinitely. `last()` now returns the last
   reachable value.
10. *(Found while reviewing the Group 3 diff, then fixed within the same branch.)* The
   guard that refuses a range slice too long to hold in a list computed the window's
   length as `start - end - 1`, which is negative for a forward window. The normal case
   was therefore never refused: `(0 to 4000000000)[0:3000000000]` was accepted and then
   exhausted memory instead of raising. The distance is now taken unsigned, and a window
   is materialised by counting rather than by comparing a moving position against the
   end — that comparison was also what a step could jump past. Asserted in
   `oq-core/src/test/java/dev/oqthara/runtime/RangeArithmeticTest.java` on the interpreter
   and in `BytecodeCompilerTest` on a jar.

Items 2 and 6 are fixed by Group 2, which put every count and bound through one
narrowing rule. Items 1, 3, 7, 8 and 10 are fixed by Group 3, which is the range model
rewrite. Item 9 was introduced and corrected inside Group 3 and is recorded so the
history is not silent about it.

## Decisions and open questions

- **#147 (`split("")`) is a semantic decision, not a bug fix.** Splitting on the empty
  string can mean "one entry per code point" (consistent with ADR-0024) or "one entry
  between every pair of code points" (which is what most languages do, and which
  produces `n + 1` entries). The two answers differ for every input. Alternatives:
  (a) one entry per code point, `["a", "😀", "b"]`; (b) boundaries between code points,
  `["", "a", "😀", "b", ""]`. Recommendation: (a), because Oqthara's text model is code
  points and every other text operation counts code points; (b) would also make the
  result length depend on a separator that is not there. This needs a maintainer
  decision before any Draft PR changes behaviour, so #147 is stopped here.
- Group 1 deliberately does **not** validate range-literal or counter-loop bounds.
  Those belong to #141/#142 (index bounds) and to the range model in Group 3, and
  teaching two branches to edit the same methods would break the grouping rule.
- **Group 2 refuses a fractional count instead of rounding it.** The alternative was to
  round to nearest, which would make `xs.take(1.5)` mean `xs.take(2)`. Truncation was
  the old behaviour and is what the issue reports. Rounding is defensible for a length
  and not for an index, and the language has no precedent for a method that rounds an
  index, so the reading that matches `xs[i]` was chosen: a bound that is not a whole
  number is a mistake in the program.
- **Group 2 keeps `xs.take(2.0)` working.** `1 == 1.0` (ADR-0017) makes an integral
  decimal the same value as the whole number it equals, so refusing `2.0` as a count
  would contradict equality. The refusal is reserved for values with no whole-number
  equivalent.
- **An infinite bound is reported as out of range, not as fractional.** `infinity` is
  not a fractional value; it is an unrepresentable position. `NaN` takes the fractional
  refusal, because it is not whole in any sense.

### Group 3 decisions

- **A count that does not fit in a whole number raises.** `length(0 to
  Long.MAX_VALUE)` has no whole-number answer. The alternatives were to saturate at
  `Long.MAX_VALUE` or to wrap. Wrapping is what #149 reports as a defect. Saturation
  would make `length` answer a number that is not the length, which is a silent
  lie a program cannot detect. Raising is the only answer that is accurate, and
  ADR-0051 already prefers a domain error to a silent overflow. An empty range still
  counts 0, and testing a range for truthiness (`if r`) uses `isEmpty()` so it can never
  raise for an uncountable range.
- **`to_list()` refuses rather than exhausting the heap.** A range longer than
  `Integer.MAX_VALUE` cannot be a Java list at all, and allocating until the JVM dies is
  not a diagnosable failure. The refusal happens before any allocation. A count above
  `Long.MAX_VALUE` is refused one step earlier, by `size()`, with the count message.
- **Counter-loop bounds are validated on both backends.** The interpreter truncated a
  fractional bound and bytecode kept it, so the same loop printed different numbers
  depending on how the program was run. Both now call `Values.wholeNumber`, which
  refuses a fractional, textual or boolean bound with the same words: the compiled
  backend can no longer invent a different answer, which is newly discovered defect 3.
- **`first`/`last`/`min`/`max` are O(1) and the pinned messages are unchanged.** The
  wording of `this range is empty` and its help lines are pinned by existing tests and
  are preserved verbatim.
- **`sum`, `sorted`, `to_set` and `to_list` still materialise.** They answer with a
  collection, so they have to; that is a documented limitation, not an oversight. The
  operations that answer a single value no longer do.
- **`reverse` refuses `step == Long.MIN_VALUE`.** Negating it overflows, and there is no
  honest answer for that step.
- **Three whole-number rules now exist, and unifying them is deliberate debt.**
  `Stdlib.wholeNumber` (Group 1, builtin arguments), `Values.integerIndex` (Group 2,
  counts and bounds reaching the resolver) and `Values.wholeNumber` (Group 3, range
  bounds) all implement the same idea with the same wording. They live in three modules
  that Groups 1, 2 and 3 each had to edit, and the grouping rule forbids two concurrent
  branches rewriting the same method. Unifying them into one helper is the correct
  follow-up and should be a single small PR **after** all three land; it is recorded here
  as debt rather than silently left for a reader to discover. The refusal wording is
  already identical, so unifying them cannot change a diagnostic.
- **The `CounterRange` opcode is new IR surface.** The compiled backend can only reach a
  runtime helper through `Builtins.supports`-gated `Ir.Call` — which would expose a
  user-callable name that the interpreter does not have, and diverge — or through a
  dedicated opcode. A dedicated opcode keeps the helper private to the two backends and
  keeps `Ir.format` (an exhaustive switch over a sealed interface) the single place the
  new shape is described.

## Execution constraints

- Scheduled monitoring runs are not available in this environment; this report is
  updated as work proceeds instead of on a timer.
- No branch in groups 1–4 is merged, and `main` is not touched by this work.

## Why CI did not run, and what it taught

Three branches were pushed and left the checks column empty, and the natural reading was that
Actions had dropped the events. It had not. Both pull requests were `CONFLICTING`, because
`main` advanced 6,452 lines while they were open (fractions, the filesystem module,
configuration formats, keyless release signing), and **GitHub does not build a merge ref for
a conflicting pull request**, so a workflow that triggers only on `pull_request` has no ref to
run against and emits no run at all. The symptom -- no run, rather than a failed run -- is
what a merge conflict looks like from the outside.

The diagnostic that settles it is `gh pr view <n> --json mergeable,mergeStateStatus`: the
third branch reported `MERGEABLE` and had a full set of runs, while the two that reported
`CONFLICTING` had none. `gh api .../commits/<sha>/check-suites` returning only the third-party
apps, with no `github-actions` suite, confirms that nothing was queued rather than lost.

Close-and-reopen does not help, because the ref is still unmergeable; neither would an empty
commit. The only fix is to make the branch mergeable. Merging `main` in costs one conflict per
branch and, unlike a rebase, keeps the published history intact -- which matters because this
repository's `AGENTS.md` forbids force-pushes.

- Group 1 conflicted only in `docs/governance/alpha-4-stdlib-inventory.md`, where both sides
  had repointed the same table: this branch by 223 lines, `main` again by adding three
  modules. Resolved by taking `main`'s table and re-running `main`'s own
  `scripts/remap-stdlib-citations.py` over the merged tree, which rewrote 98 citations with 0
  unresolved.
- Group 3 conflicted only in `Values.truthy`: this branch had replaced `r.size() > 0` with
  `!r.isEmpty()` so truthiness stops counting a range, and `main` had added the `OqFraction`
  arm on the same two lines. Both were kept.

After the merges, both branches produce complete runs on their merge commits, and Group 2 --
which was already `MERGEABLE` -- had been green throughout. The lesson is worth recording
because a silently absent CI run looks identical to a broken workflow, and the cheap check
that tells them apart is one API call on mergeability.
