# Alpha 5 Sentinel remediation report

Baseline: `6954a86` (`origin/main` at the time of the audit).
Scope: issues #140–#156 in `Oqthara/oqthara`.
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

- `RangeArithmeticTest` (new, 17 tests) — the count at all four corners of the type, the
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
- `BuildJarTest.anInternalErrorIsClassifiedAsExitCode70` replaces
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

### A defect found in review, after the group 3 tests were green

The independent pre-merge review found a wrong-answer bug in the group 3 slice path that every
test above had missed. `Values.rangeValueAt` scaled the slice *offset* by the range step and then
added the window *start* unscaled, which is only the same thing when the step is 1:

- `range(0, 10, 2)[1:3]` returned `[1, 3]`; the window is positions 1 and 2 of a range that
  names 0, 2, 4, 6, 8, so it is `[2, 4]`.
- `(0 to 9).reverse()[1:3]` returned `[10, 9]`; 10 is not a member of the range at all, and the
  window is `[8, 7]`.

The start is now scaled like the offset. The gap was in the tests rather than in the reasoning:
every slice assertion in `RangeArithmeticTest` sliced a `to`/`until` literal, whose step is 1.
Three tests now cover a strided lateral slice, a descending one, and indexing-versus-slicing
agreement at four positions, so a step that is not 1 cannot pass through this path unnoticed
again. Reported here rather than quietly amended because it is the reason the merge order below
puts group 3 last: a fix that is green is not the same as a fix that is right.

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
   `RangeArithmeticTest` on the interpreter and in `BytecodeCompilerTest` on a jar.

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
- No branch here is merged, and `main` is not touched by this work.

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
