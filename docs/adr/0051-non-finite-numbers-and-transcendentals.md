# ADR-0051: Non-finite numbers and the transcendental functions

## Status

Accepted.

## Context

Alpha 4 §17 asks for the standard mathematical functions Oqthara does not have:
trigonometry, logarithms, exponentials, and the two angle conversions. Before
this ADR the standard library offered `abs`, `floor`, `ceil`, `round`, `sqrt`,
`pow`, `min`, `max`, `clamp` and `random` — no trigonometry, no logarithms, no
exponentials.

Adding them surfaced a policy question the language had never answered. Oqthara
raises `division by zero` for `1 / 0` and `1 % 0`, and `round` rejects `NaN`.
Yet `sqrt(-1)` returned `NaN` without a word. Measurement of the pre-existing
behaviour:

| Expression                  | Result               |
| --------------------------- | -------------------- |
| `1 / 0`, `1 % 0`            | raises               |
| `0.0 / 0.0`                 | raises               |
| `sqrt(-1)`                  | `NaN`, no error      |
| `pow(0, -1)`                | `infinity`           |
| `pow(-8, 0.5)`              | `NaN`, no error      |
| `pow(-2, 3)`                | `-8`                 |
| `0.0 ** 0`                  | `1.0`                |
| `1.0e308 * 10`              | `infinity`           |
| `1.0e-308 / 10.0`           | `0.0`                |
| `infinity - infinity`       | `NaN`                |

So two silently-`NaN` paths existed while every other arithmetic mistake raised.
Adding eight more functions that can produce `NaN` would have multiplied that
inconsistency, so the policy was settled before the functions were written.

Nothing pinned the old behaviour: `grep` for `NaN|infinity|non-finite` in
`docs/spec/language.md` finds one incidental hit (the `round` tie paragraph), no
conformance case asserts a negative `sqrt`, and the only assertions touching
non-finite values are `StdlibFuzzRegressionTest.powerThatDoesNotFitALongStaysADouble`
and `jsonWriterRejectsNonFiniteNumbersInsteadOfEmittingInvalidJson`. The policy
was therefore free to be chosen rather than legacy that had to be preserved.

## Decision

### 1. A domain error raises; overflow and underflow stay silent

A **domain error** is an input for which the function has no real-number result:
`sqrt` of a negative, the logarithm of zero or a negative, `asin`/`acos` outside
`[-1, 1]`, and a negative base raised to a fractional power. These raise `OqError`,
matching `1 / 0` and `round(NaN)`.

**Overflow** of a finite input still yields `infinity` (`exp(1000)`,
`1.0e308 * 10`, `2.0 ** 2000`) and **underflow** still yields `0.0`
(`1.0e-308 / 10.0`). Those are not domain errors — the input is in the domain and
the true answer is merely outside the representable range. Turning them into
errors would break ordinary numeric code that has always worked, which is a much
larger change than the inconsistency being fixed.

### 2. `NaN` remains reachable, and stays detectable

`infinity - infinity`, `infinity / infinity` and `0.0 * infinity` still produce
`NaN`: these are operations on values the language already hands out, not domain
errors, and IEEE 754 defines them. There is no `infinity` literal, so an infinity is
reached by overflowing a finite one — `big = 1.0e308 * 10` — and `NaN` follows from
combining two of those. `NaN` is detectable without a new builtin because it is the
one value that is not equal to itself:

```oq
big = 1.0e308 * 10
n = big - big
print n == n     // false
print n != n     // true
```

That is the documented test. No `is_nan` builtin is added.

### 3. A pole is not a domain error

`pow(0, -1)` returns `infinity`, unchanged. The input is in the domain of
`x -> x^n` for `n = -1`; the function simply grows without bound. This is
distinguished from `pow(-8, 0.5)`, which is now an error because no real number
squares to `-8`.

### 4. `StrictMath` for every new transcendental, not `Math`

`java.lang.Math` may map to platform intrinsics that vary by CPU and JVM.
`java.lang.StrictMath` is bit-reproducible by specification (fdlibm). Measured on
the development machine:

- `Math.pow` and `StrictMath.pow` agreed on all of 2,000,000 random pairs.
- `Math.sin` and `StrictMath.sin` disagreed on 36,073 of 1,000,000 values.

Reproducibility is a stated project value, so all 18 new functions use
`StrictMath`. `pow` keeps `Math.pow` because it is already measured to agree; the
new policy check is added around it.

### 5. `log2` needs an exact power-of-two fast path

`StrictMath.log(x) / StrictMath.log(2)` is inexact for 439 of the 2098 powers of
two representable as a `double`; for example `2^-1021` yields `-1021.0000000000001`.
Anyone using `log2` for bit arithmetic would see that. When `x` is exactly a power
of two the exponent is returned directly, with a `* 0x1p54` rescale so subnormals
work: after the fix all 2098 powers of two are exact, including
`log2(5e-324) == -1074.0`. `StrictMath.log10` needs no fast path — it is exact for
every `1eN` from `1e-300` to `1e300`.

### 6. Constants are zero-arity functions

`pi`, `e` and `tau` are builtins `pi()`, `e()`, `tau()`, not builtin values. The
compiled backend's `Ir.Load` resolves only to a local slot or to a constant
function reference; a builtin *value* has no representation there. A zero-arity
`NativeFunction` works in both backends with no compiler change, and `e` remains
usable as an ordinary variable name because builtins are shadowable.

### 7. The method form is mirrored, not separate

`(-1).sqrt()` must fail exactly as `sqrt(-1)` does. The domain checks live in
`Stdlib`'s builtin registrations and in `MethodResolverImpl.numberMethod`, and a
test asserts both forms agree.

## Consequences

- `sqrt(-1)`, `ln(0)`, `asin(2)` and `pow(-8, 0.5)` now raise instead of returning
  `NaN`. This is a behavioural change to `sqrt` and `pow`, recorded in the
  CHANGELOG under `Changed`.
- Numeric code relying on `sqrt` returning `NaN` must use `n != n` semantics
  explicitly, or guard the input. Nothing in the repository, the conformance
  suite or the examples did.
- `NaN` cannot be produced by accident from an in-domain input, so `NaN` seen at
  runtime always means infinities met each other.
- ADR-0017 is corrected: it claimed `0.0/0.0` is `NaN`, but `Values.divide` checks
  `divisor == 0.0` before dividing, so it raises.

## References

- Alpha 4 master execution directive §17.
- ADR-0017 (equality and hashing), corrected by this ADR.
- `docs/governance/alpha-4-stdlib-inventory.md`, domain 4.
