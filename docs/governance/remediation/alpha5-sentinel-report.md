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
| #149 | `length(0 until MIN)` and friends overflow to a negative size | 3 | Reproduced, not started |
| #150 | range `.first()`/`.last()`/`iter().next()` materialise the whole range | 3 | Reproduced, not started |
| #151 | `MAX to MAX` `.to_list()` never terminates | 3 | Reproduced, not started |
| #152 | `range(MIN, MAX, 3).contains(1)` is wrong through `%` overflow | 3 | Reproduced, not started |
| #153 | `contains(9.223372036854776e18)` and `2^63 in r` are wrongly true | 3 | Reproduced, not started |
| #155 | `for n in 0 to 10000000` exhausts memory before the first body run | 3 | Reproduced, not started |
| #156 | `r[0]` and `r[-1]` materialise the whole range | 3 | Reproduced, not started |

Additional defects found while reproducing the backlog are recorded in
[Newly discovered defects](#newly-discovered-defects); they are reported rather than
silently folded into the fixes above.

Each group lands as its own Draft PR, so a test added by one group is not present on
another group's branch. A test class that belongs to a different group is therefore cited
by its file path rather than by class name, so that a reader on any one branch can tell
which citations that branch can actually run.

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

- `TextAndListBoundTest` (new, 16 tests) — the refusals for every method, the accepted
  whole-decimal case, the non-number case, non-finite and past-64-bit bounds, the
  `repeat` domain and its overflow guard, `pad` on both sides, and `capitalize` on a
  supplementary-plane letter, a digit and an emoji.
- `BytecodeCompilerTest.aRefusedCountOrBoundReportsTheSameDiagnosticOnBothBackends` — nine
  programs, each asserted to exit 1 on both backends, to contain no `internal error` and
  no stack trace, and to produce the interpreter's diagnostic lines in the compiled
  output.

Documentation: `docs/spec/language.md` gains a "Counts and bounds" subsection stating the
whole-number rule, the accepted whole-decimal case, the out-of-range refusal and the
`repeat` domain, and `capitalize` joins the code-point table with `"𐐨x"` → `"𐐀x"`.

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

Items 2 and 6 are fixed by Group 2, which put every count and bound through one
narrowing rule. Item 1 and item 3 are in the range model that Group 3 rewrites, so they
are listed for that group rather than patched here.

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

## Execution constraints

- Scheduled monitoring runs are not available in this environment; this report is
  updated as work proceeds instead of on a timer.
- No branch here is merged, and `main` is not touched by this work.
