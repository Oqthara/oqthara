# ADR-0053 — Exact rational arithmetic as a value type

**Status:** Accepted
**Date:** 2026-11-13
**Related:** ADR-0045 (exact Java numbers keep their type), ADR-0046 (whole-number range),
ADR-0051 (non-finite numbers and the transcendentals), ADR-0017 (equality and hashing)

## Context

Alpha 4 §17 asks for standard-library breadth. The numeric domain was the thinnest part of it:
after the transcendentals landed there was trigonometry and there were logarithms, but there was
still no way to hold a value that is exactly a third. `1 / 3` is `0.3333333333333333`, and every
later use of it carries that error. The stdlib inventory recorded `fraction` as absent, and the
requirement matrix recorded the numeric row as partial.

Two constraints shaped the decision before any code was written.

**A decimal domain is not available.** `Values.typeName` answers `"decimal"` for a `Double`, so an
exact-decimal type cannot take that name without either renaming the `double`'s reported type (a
breaking change to every program that branches on `type_of`) or shipping a type whose name says
one thing and whose `type_of` says another. ADR-0045 recorded the same blocker from the Java
bridge side. Naming an exact decimal is a maintainer decision, and this ADR does not make it.
Rational arithmetic has no such collision: grep across the repository before this change found
zero occurrences of `"fraction"` as a type name.

**A fraction cannot round-trip through a `double`.** The whole reason to have the type is that
`1/3` is not representable. Any operation that converts to `double` in the middle reintroduces
exactly the error the type exists to prevent, so the design has to keep every intermediate exact.

## Decision

### D1 — A `fraction` is a value type in the runtime, not a library object

`OqFraction` is a record of two `BigInteger`s in
`oq-runtime/src/main/java/dev/oqthara/runtime/`. It lives in the runtime rather than in the
stdlib because `Values` has to name it, display it and decide its truthiness, and the runtime
does not depend on the stdlib. `type_of` answers `"fraction"`, `display` renders `1/2`, and a
fraction is truthy exactly when its numerator is non-zero — the same rule `number` follows.

### D2 — Lowest terms and a positive denominator are invariants, not conventions

The compact constructor divides both parts by their greatest common divisor and moves a negative
denominator's sign onto the numerator. A denominator of zero raises. This means one value has one
representation, so `==` and set membership need no normalizing step at the comparison site:
`fraction(1, 2) == fraction(2, 4)` is true because both are stored as `1/2`. The record's
generated `equals` and `hashCode` therefore do the right thing, and `Values.equal`/`Value.hash`
already fall through to them for values that are not primitives.

### D3 — Inputs are whole numbers, and a non-integral decimal is refused

`fraction(numerator, denominator)` accepts `Long`, or a `Double` that is already integral.
`fraction(1, 2)` and `fraction(2.0, 4.0)` both work. `fraction(0.1, 1)` raises and the message
says why: a fraction built from `0.1` would carry the error that made `0.1` inexact, so it would
be a wrong answer wearing the name of an exact one. Truncating instead was rejected for the same
reason — it turns a caller's mistake into a plausible number.

The conversion for an integral `Double` goes through `new BigDecimal(double).toBigInteger()`.
`BigDecimal.valueOf(double)` is not exact and would be wrong above 2^53.

### D4 — Comparison cross-multiplies; nothing converts to `double`

`fraction_compare(a, b)` multiplies `a.num * b.den` and `b.num * a.den` and compares the products.
This is exact at any magnitude. The test that pins the decision uses two fractions that a
`double` cannot tell apart: `9999999999999999/30000000000000000` and
`10000000000000001/30000000000000000` are both exactly `1.0/3.0` as `double`s, and they sit on
opposite sides of `1/3`. Cross-multiplication orders all three correctly; a `double` comparison
would report the first as equal to `1/3`, which is false.

`Values.compare` is deliberately **not** given a fraction branch. `<`, `>`, `sort` and `sorted`
therefore refuse a fraction with the existing "cannot compare" error rather than inventing an
ordering, and `fraction_compare` is the one stated way to order two fractions. Adding the branch
would have been easy and would have made the exactness claim depend on which operation a program
happened to use.

### D5 — Rounding takes the tie rule the language already has

`fraction_round` rounds halves toward positive infinity, which is what `round` does:
`round(-0.5)` is `0`, `round(-1.5)` is `-1`, `round(2.5)` is `3`. `RoundTieTest` pins that
behaviour for `round`; `FractionTest` asserts the two agree at every one of those points rather
than restating the expected numbers, so the two cannot drift apart without a failure.

`fraction_to_decimal(value, places)` is the only conversion to an inexact form, and it is
explicit: the caller states the precision, the result is exact half-up rounding to that many
places, and trailing zeros are kept so the stated precision is visible in the text
(`fraction_to_decimal(fraction(1, 8), 4)` is `"0.1250"`). `places` is bounded 0–15, the same
range `round(value, places)` accepts.

### D6 — The parts are bounded, and the bound is enforced where every result is built

An integer exponent is the only kind `fraction_pow` accepts: a fractional exponent has no exact
rational answer in general, so it is refused with a message pointing at `pow`. The magnitude of
that exponent is bounded by `OqFraction.MAX_EXPONENT`, which is 10,000.

That bound alone is not enough, and saying so is the point of this decision. It bounds an
argument, not a result: `f = 3/2` followed by forty squarings never calls `fraction_pow` and still
reaches parts of about 2^40 bits, because multiplication is as exact as a power and grows just as
fast. A bound that the most obvious loop walks around is a bound on the wrong thing. Neither the
stdlib reference nor this ADR wants to describe a program that has done nothing illegal and yet
allocates until the heap complains.

So `OqFraction.MAX_BITS` bounds each part at 32,768 bits, and it is checked in the compact
constructor after reduction. Every result in the module, from every builtin, is built there; the
constructor is therefore the one place a bound cannot be bypassed, by a loop or by an operation
nobody thought to guard.

- **Why 32,768 bits.** That is about 9,865 decimal digits. Every rational that comes from real
  data — money at a hundredth, a ratio of two counters, a probability, a measurement scaled by its
  precision — is a few hundred bits, and stays a few hundred bits under addition and
  multiplication, because exact arithmetic on such values reduces rather than accumulates. At the
  limit a part prints as under ten thousand digits, so a fraction's text stays around twenty
  thousand characters, far under the sixteen megabytes `Stdlib.MAX_TEXT_BYTES` allows a single
  string. The type therefore cannot produce a value it cannot print: the same argument the
  exponent bound rests on, now applied to the result.
- **Checked after reduction.** The limit is on the value, not on the product that produced it. A
  pair whose parts cancel back down is accepted however large the intermediate was, so the bound
  never refuses arithmetic that is about to become small again.
- **Refused before it is computed, when that is cheap to see.** `fraction_pow` projects the bit
  length of both parts before raising them, because `a^b` has at least `b * (bits(a) - 1) + 1`
  bits; the projection cannot reject a value the constructor would have accepted, so the
  constructor remains the authority. Without it, `fraction_pow` would spend the memory the bound
  exists to protect before the constructor got a chance to refuse.

The two bounds divide the work. `MAX_EXPONENT` is the first and cheaper refusal, and it names the
argument the caller wrote. `MAX_BITS` is the authority, and it names the part that got too big.
They are not redundant: `fraction_pow(fraction(2, 3), 10000)` is accepted at about 15,850 bits,
while `fraction_pow(fraction(1234567, 7654321), 10000)` is refused by `MAX_BITS` even though its
exponent is in range. That is intended — the bound is on the result, not on the request.

**Why not charge the step budget instead.** Billing a fraction operation against the interpreter's
steps, as `Stdlib.chargeTransfer` bills a transferred byte, was the alternative. It was rejected
for two reasons. A multiplication of two twenty-thousand-bit values takes microseconds, so any
honest conversion between bits and steps is negligible: the charge would do nothing until the value
was already the size the bound exists to refuse, and charging more would bill cheap work heavily.
And the refusal a program would see is the generic "program ran for too long", which names neither
the operation nor the fix, and which arrives at a different point depending on how the program was
run, since compiled code gets a fresh ceiling from `Builtins.host()`. A refusal on the value is
uniform, states its size, and is the same however the program is executed.

### D7 — A whole-number result that outgrows the language is refused, not truncated

`fraction_floor`, `fraction_ceil`, `fraction_round`, `fraction_num` and `fraction_den` return
ordinary numbers. A `BigInteger` that fits 64 bits becomes a `Long`, which behaves like every
other whole number. One that does not raises and the message states how many bits the value has.
Saturating to `Long.MAX_VALUE` would be a wrong answer stated confidently, which is the one thing
this module does not do. Only a large power or a long chain of exact multiplications can produce
such a value, and `MAX_BITS` keeps even those inside a size the rest of the language can print, so
the refusal is rare and always traceable.

## Consequences

- `type_of(fraction(1, 2))` is `"fraction"`, a name that appears nowhere else. Programs that
  branch on `type_of` gain a case to consider, not a changed one. The `OqFraction` branch sits
  after the `Double` branch in `Values.typeName`, so `type_of(1.5)` still answers `"decimal"`;
  `FractionTest` pins that order.
- A fraction is truthy when it is non-zero, and `display` prints `1/2`. Both are the choices that
  make the type behave like the value types around it.
- `<` and `sort` refuse fractions. That is visible, and `fraction_compare` is the stated
  alternative. The stdlib reference lists the 17 new names; no method family is extended, so
  `.add()` on a fraction is a no-op that does not exist yet rather than a silent conversion.
- Growth is refused at the value, not at the operation. The first draft bounded only
  `fraction_pow`'s exponent, which a squaring loop walks straight around; `MAX_BITS` in the
  constructor is what makes the refusal true of every operation, including ones added later.
- A program that needs more precision than 32,768 bits per part has to convert. That is a real
  limit on the type, and it is stated rather than discovered: `fraction_to_decimal` with a stated
  number of places is the answer, and the refusal's hint says so.
- No exact-decimal type is added, and the `decimal` name stays reserved.

**Where the risk is.** `BigInteger` arithmetic on attacker-supplied operands is the only new
surface. A part can grow through any operation, not only through `fraction_pow`, so the limit has
to hold for all of them: `MAX_BITS` is enforced in the constructor every result passes through,
which makes the cost of one call bounded by 32,768-bit operands. Parsing is bounded by the length
of the input text, which the language already bounds by available memory for any string. No new
I/O, no new reflection, no new host boundary.

**Verification.** `FractionTest` covers normalization, every operation, division by zero, the
near-identical comparison above, the tie rule against `round`, parse errors, both bounds, `type_of`,
`==` across equivalent forms, display and set membership. The magnitude bound is pinned by four
cases: a repeated-squaring loop, run as a whole program, is refused with the limit and the
part in its message; a part exactly at the limit is accepted and one bit past it is refused for
both numerator and denominator; a pair whose parts cancel back down is accepted; and a power
within `MAX_EXPONENT` whose result is too large is refused by the projection. Every behaviour was
also checked against a built jar, both interpreted and compiled, including a program that computes
`fraction_add(fraction(1, 3), fraction(1, 6))` and prints `1/2`.

## References

- Alpha 4 master execution directive §17.
- ADR-0045, which recorded that an exact-decimal type is blocked on its name.
- ADR-0051, whose non-finite policy this module stays outside of: a fraction has no infinity and
  no `NaN`, because division by zero raises.
- `docs/governance/alpha-4-stdlib-inventory.md`, domain 4.
