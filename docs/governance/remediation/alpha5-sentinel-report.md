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
| #141 | list `slice`/`subList`/`take`/`skip` truncate or saturate fractional bounds | 2 | Reproduced, not started |
| #142 | text `substring`/`slice` truncate fractional bounds | 2 | Reproduced, not started |
| #145 | `"x".repeat(-1)` reports an internal error and exits 70 | 2 | Reproduced, not started |
| #146 | `"x".repeat(2.5)` truncates; `pad_start`/`pad_end` truncate | 2 | Reproduced, not started |
| #147 | `"a😀b".split("")` yields lone surrogates | 2 | Reproduced; **blocked on a semantic decision** |
| #148 | `capitalize` does not upper-case a supplementary-plane first letter | 2 | Reproduced, not started |
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
- `NumberMethodBuiltinParityTest` (new) — method and builtin answers past the 64-bit
  range, the lowest-whole-number `abs`, the wrong-receiver refusal, and parity of
  values with no `long` representation.
- `StdlibFuzzRegressionTest` — fractional and out-of-range whole-number arguments are
  refused rather than truncated.
- `BytecodeCompilerTest` — a fractional range bound is refused identically on both
  backends.

Documentation: `docs/spec/language.md` now states the whole-number rule and the
method/builtin parity promise, and notes that the statistics ends of the range are not
a special case; `docs/stdlib/reference.md` repeats the parity promise on the number
section.

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

Item 1 and item 2 are in the range/index area that Group 2 and Group 3 rewrite, so they
are listed for the owning group rather than patched here.

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

## Execution constraints

- Scheduled monitoring runs are not available in this environment; this report is
  updated as work proceeds instead of on a timer.
- No branch here is merged, and `main` is not touched by this work.
