# ADR-0045 — Exact Java numbers keep their type

**Status:** Accepted
**Date:** 2026-11-11
**Supersedes:** part of `E28` in [the runtime state document](../spec/states/03-runtime.md)
**Related:** ADR-0032 (static fields and varargs), ADR-0015 (type families)

## Context

The host boundary normalizes what a Java method returns into an Oq value. `E28` in the runtime
state document says a method returning `BigInteger` or `BigDecimal` "widens to `number`, `decimal`
or `text`", and `JavaBridge.normalize` did that by narrowing both to `double`:

```java
if (value instanceof java.math.BigInteger big) {
    try {
        return big.longValueExact();
    } catch (ArithmeticException e) {
        return big.doubleValue();
    }
}
if (value instanceof java.math.BigDecimal dec) return dec.doubleValue();
```

Both classes exist for exactly one reason: to carry values a `double` cannot hold. A `double` has
53 bits of mantissa, so narrowing either one discards the property the caller selected the class
for, and does it silently. Measured against the built CLI before this ADR was written:

```
use java.math.BigDecimal
big = BigDecimal("123456789012345678901234567890.123456789")
print big.add(BigDecimal("0.000000001"))
```

```
123456789012345680000000000000.0
```

The exact answer is `123456789012345678901234567890.123456790`. The program asked for 120
significant digits, was handed 16, and had nothing to tell it so. The same program reported the
result as a `decimal`, which is the name `type_of` uses for a `Double`, so the value also lost
every method `BigDecimal` offers: `sum.setScale(2)` answered `type decimal has no method
'setScale'`.

`BigInteger` had a second inconsistency of its own. A `BigInteger` that fits a `long` narrowed to
a number, but a wider one fell through to `doubleValue()`, so the same class answered two
different Oq types depending on magnitude:

```
use java.math.BigInteger
n = BigInteger("42").add(BigInteger("1"))
print n                      # 43
print type_of(n)             # number
wide = BigInteger("123456789012345678901234567890").add(BigInteger("1"))
print wide                   # 123456789012345680000000000000.0
print type_of(wide)          # decimal
```

## Decision

**D1 — A number that fits a `long` stays a `number`.** This is unchanged, and it keeps the
common case ergonomic: `String.length()` is already an `int` for this reason, and an index
returned by a Java method should behave like one.

**D2 — A `BigInteger` too wide for a `long` stays the Java object.** The overflow branch returns
the value itself instead of calling `doubleValue()`. The value then reports `java BigInteger`,
keeps its exactness, and keeps every method it has, so `.add`, `.multiply` and `.toString` all
keep working.

**D3 — A `BigDecimal` always stays the Java object.** It no longer narrows to `double` at all,
whatever its magnitude. A `BigDecimal` is always chosen deliberately — its constructor takes a
`String` or another `BigDecimal`, never a `double` — so there is no common case to keep
ergonomic, and the class's whole purpose is exactness.

**D4 — Exact values do not take part in Oq arithmetic.** A `BigDecimal` answers `cannot add java
BigDecimal and number` rather than silently converting. Arithmetic stays exact on both sides or
not at all, and the error names the type so the fix is visible; a program that wants to combine
them calls `toPlainString()` or `doubleValue()` explicitly on the Java object.

**D5 — `E28` is narrowed to match.** The row now says a `BigInteger` fitting a `long` widens to
`number` and that both classes otherwise stay as Java objects. The runtime state document is the
canonical description of this boundary, so it is edited rather than left contradicting the code.

## Consequences

The change turns three silent wrong answers into either a right answer or a named error, which is
the rule the project applies everywhere else.

The cost is that a program which used `BigDecimal` for arithmetic now has to convert explicitly,
and `type_of` answers `java BigDecimal` where it used to answer `decimal`. Both are visible and
both are the point of the decision: the old answer was wrong, and code that relied on it was
relying on a value that had already lost its digits.

The stdlib inventory's rank-7 `decimal` entry said the domain was "blocked on a naming decision,
not on engineering". That is now only partly true. The naming collision is still real — an
exact-decimal domain cannot take the name `decimal` while `type_of` returns it for a `Double` —
but the bridge no longer degrades exact values on the way in, so the implementation prerequisite
is satisfied and only the naming decision remains.

**Where the risk is.** None new. Retaining an object reference is strictly less work than
converting it, and no boundary, traversal or amplification surface is added.

**Verification.** Every output above was produced by running the program against the built CLI.
Four cases in `JavaBridgeExactnessTest` pin the normalization boundary, and four in
`JavaInteropTest` pin it end to end through the CLI, including the digits, the type names and a
method call on the returned value.
