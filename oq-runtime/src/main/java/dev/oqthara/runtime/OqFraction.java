package dev.oqthara.runtime;

import java.math.BigInteger;

/**
 * An exact rational number: a numerator and a denominator, never a floating-point
 * approximation of them.
 *
 * <p>The pair is always in lowest terms with a positive denominator, which is what makes two
 * fractions equal exactly when their parts are equal. {@code 1/2} built from {@code (1, 2)} and
 * from {@code (2, 4)} is one value, so {@code ==}, set membership and map keys work without the
 * containers knowing this type exists.
 *
 * <p>{@link BigInteger} backs both parts because overflow has to be impossible for the type to
 * keep its promise. A fraction that wrapped around would be a wrong answer stated confidently,
 * which is the failure this type exists to prevent. The cost is that exact arithmetic does not
 * round, so a part grows with every operation instead of settling at a fixed width. Two bounds
 * answer that: {@link #MAX_EXPONENT} refuses one call's exponent, and {@link #MAX_BITS} refuses a
 * result whose part is too large to be useful. The second is the one that makes the type safe,
 * because every result is built here and every construction is checked.
 *
 * <p>Every operation here is exact, and there is deliberately no conversion to {@code double}:
 * turning a fraction into a decimal loses digits, so the standard library exposes exactly one
 * such conversion, with the number of places the caller asked for stated in its name.
 */
public record OqFraction(BigInteger numerator, BigInteger denominator) {

    /**
     * The largest exponent magnitude {@link #pow} accepts. {@code fraction_pow(2/3, 10000)}
     * already builds a numerator of about 3011 digits and a denominator of about 4772; an
     * exponent in the millions would allocate gigabytes and then take longer to print than the
     * call took to compute, which reads as a hang rather than a result.
     *
     * <p>This is the first refusal rather than the last: a large enough base reaches
     * {@link #MAX_BITS} at a much smaller exponent, and that bound answers.
     */
    public static final int MAX_EXPONENT = 10_000;

    /**
     * The largest bit length either part may have: 32,768 bits, which is about 9,865 decimal
     * digits.
     *
     * <p>{@link #MAX_EXPONENT} bounds one call's exponent, but a program can reach the same
     * allocation without ever calling {@code fraction_pow}: {@code f = 3/2} followed by forty
     * squarings doubles the bit length each time. A bound on an exponent bounds an argument; a
     * bound on the parts bounds the result, and since every result is built by this constructor,
     * no operation and no loop can get around it.
     *
     * <p>32,768 bits is chosen to be far past any fraction a program needs and still small
     * enough that an accepted value is a result rather than a hang. Every rational that arises
     * from real data — money at a hundredth, a ratio of two counters, a probability, a
     * measurement scaled by its precision — is a few hundred bits at most, and stays a few
     * hundred bits as it is added and multiplied. At the limit a part prints as under ten
     * thousand digits, so a fraction's text stays around twenty thousand characters, well under
     * the sixteen megabytes the standard library allows a single string. The type therefore
     * cannot produce a value it cannot print, which is the same argument {@link #MAX_EXPONENT}
     * rests on.
     */
    public static final int MAX_BITS = 32_768;

    public OqFraction {
        if (denominator.signum() == 0) {
            // `Errors.current()` rather than a null span, as in OqRange: the value has no span
            // of its own and the call that asked for it has already marked one (ADR-0025).
            throw new OqError("a fraction denominator cannot be 0", Errors.current(),
                    "A fraction with a denominator of 0 has no value. Check the value you are "
                            + "dividing by.");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger divisor = numerator.gcd(denominator);
        if (!divisor.equals(BigInteger.ONE)) {
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }
        // Checked after reduction, so the bound is on the value rather than on the product that
        // produced it: an intermediate that cancels back down is allowed, one that does not is
        // refused before it can be handed to a caller.
        if (numerator.bitLength() > MAX_BITS) {
            throw tooLarge("numerator", "this one has " + numerator.bitLength() + " bits");
        }
        if (denominator.bitLength() > MAX_BITS) {
            throw tooLarge("denominator", "this one has " + denominator.bitLength() + " bits");
        }
    }

    /**
     * The refusal for a part above {@link #MAX_BITS}, naming the part and its size so the caller
     * can see which operation grew it.
     *
     * @param detail a clause completing "cannot have more than N bits, but ..."
     */
    private static OqError tooLarge(String part, String detail) {
        return new OqError("a fraction " + part + " cannot have more than " + MAX_BITS
                + " bits, but " + detail, Errors.current(),
                "Exact arithmetic does not round, so repeated multiplication grows a fraction "
                        + "without limit. Use fraction_to_decimal() when a stated precision is "
                        + "enough.");
    }

    /** A whole number written as a fraction with a denominator of one. */
    public static OqFraction whole(long value) {
        return new OqFraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public OqFraction plus(OqFraction other) {
        return new OqFraction(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public OqFraction minus(OqFraction other) {
        return new OqFraction(
                numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public OqFraction times(OqFraction other) {
        return new OqFraction(numerator.multiply(other.numerator),
                denominator.multiply(other.denominator));
    }

    public OqFraction dividedBy(OqFraction other) {
        if (other.numerator.signum() == 0) {
            throw new OqError("division by zero", Errors.current(),
                    "Check the value you are dividing by.");
        }
        return new OqFraction(numerator.multiply(other.denominator),
                denominator.multiply(other.numerator));
    }

    public OqFraction negate() {
        return new OqFraction(numerator.negate(), denominator);
    }

    public OqFraction abs() {
        return numerator.signum() < 0 ? negate() : this;
    }

    /**
     * Orders two fractions by cross-multiplication, returning -1, 0 or 1.
     *
     * <p>Converting to {@code double} first would reintroduce exactly the error the type exists
     * to avoid: {@code 1/3} and {@code 33333333333333333/100000000000000000} differ in the
     * seventeenth significant digit and compare equal as decimals. Both denominators are
     * positive, so multiplying through by them preserves the order.
     */
    public int compareTo(OqFraction other) {
        return Integer.signum(numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator)));
    }

    /**
     * The largest whole number that is not greater than this fraction. Exact: the division
     * happens on {@link BigInteger}, never through a {@code double} that could round first.
     */
    public BigInteger floor() {
        return floorPart()[0];
    }

    /** The smallest whole number that is not less than this fraction. */
    public BigInteger ceil() {
        BigInteger[] parts = floorPart();
        return parts[1].signum() == 0 ? parts[0] : parts[0].add(BigInteger.ONE);
    }

    /**
     * The nearest whole number, with a tie going toward positive infinity: {@code -1/2} rounds
     * to 0 and {@code -3/2} to -1. That is the rule {@code round} pins in {@code RoundTieTest}
     * for decimals, restated exactly so the two cannot drift apart.
     */
    public BigInteger round() {
        BigInteger[] parts = floorPart();
        return parts[1].shiftLeft(1).compareTo(denominator) >= 0
                ? parts[0].add(BigInteger.ONE)
                : parts[0];
    }

    /**
     * The floor and the remainder of the division, with the remainder always in
     * {@code [0, denominator)}. {@link BigInteger#divideAndRemainder} truncates toward zero,
     * which puts both round-half cases of a negative value on the wrong side, so the floor is
     * recovered here once and every whole-number result is built from it.
     */
    private BigInteger[] floorPart() {
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        if (parts[1].signum() < 0) {
            parts[0] = parts[0].subtract(BigInteger.ONE);
            parts[1] = parts[1].add(denominator);
        }
        return parts;
    }

    /**
     * This fraction raised to a whole-number power.
     *
     * @throws OqError when {@code exponent} is larger in magnitude than {@link #MAX_EXPONENT},
     *                 because the result would allocate without bound
     */
    public OqFraction pow(long exponent) {
        if (exponent > MAX_EXPONENT || exponent < -MAX_EXPONENT) {
            throw new OqError("a fraction exponent must be from -" + MAX_EXPONENT + " to "
                    + MAX_EXPONENT + ", got " + exponent, Errors.current(),
                    "The result of a larger exponent has more digits than a program can print. "
                            + "Raise the fraction in steps, or use pow() when an approximate "
                            + "decimal is enough.");
        }
        if (exponent == 0) return whole(1);
        int magnitude = (int) Math.abs(exponent);
        OqFraction raised = basePow(magnitude);
        return exponent < 0 ? raised.reciprocal() : raised;
    }

    /** The power itself, kept separate so a negative exponent's reciprocal is one more call. */
    private OqFraction basePow(int magnitude) {
        // A base in lowest terms stays in lowest terms when both parts are raised, so the
        // constructor's reduction is redundant here and cheap: gcd of two powers is one step.
        //
        // Refuse before allocating rather than after. The constructor's bound still applies, but
        // reaching it by computing tens of megabytes of digits first would spend the memory the
        // bound exists to protect. A power's bit length is at least `magnitude * (bits - 1) + 1`,
        // so a refusal based on that cannot reject a value that would have been accepted; the
        // constructor remains the authority for anything the projection lets through.
        project("numerator", numerator, magnitude);
        project("denominator", denominator, magnitude);
        return new OqFraction(numerator.pow(magnitude), denominator.pow(magnitude));
    }

    private static void project(String part, BigInteger base, int magnitude) {
        long least = (long) (base.bitLength() - 1) * magnitude + 1;
        if (least > MAX_BITS) {
            throw tooLarge(part, "this power's would be at least " + least + " bits");
        }
    }

    private OqFraction reciprocal() {
        if (numerator.signum() == 0) {
            throw new OqError("division by zero", Errors.current(),
                    "Zero raised to a negative power has no value.");
        }
        return new OqFraction(denominator, numerator);
    }

    /**
     * This fraction as text with exactly {@code places} digits after the point, rounded to
     * nearest with a tie going toward positive infinity, so the digits agree with {@link #round}
     * and with {@code round(x, places)}. The digits are always written out, including trailing
     * zeros: {@code 1/2} to four places is {@code "0.5000"}, because the count is the caller's
     * statement of how precise the answer is meant to be.
     *
     * <p>This is the only place a fraction becomes an inexact form. The rounding is therefore
     * never silent: the caller asks for a specific number of places and gets exactly that.
     */
    public String toDecimal(int places) {
        BigInteger scaled = numerator.multiply(BigInteger.TEN.pow(places));
        BigInteger[] parts = scaled.divideAndRemainder(denominator);
        BigInteger rounded = parts[0];
        if (parts[1].signum() < 0) {
            rounded = rounded.subtract(BigInteger.ONE);
            parts[1] = parts[1].add(denominator);
        }
        if (parts[1].shiftLeft(1).compareTo(denominator) >= 0) {
            rounded = rounded.add(BigInteger.ONE);
        }
        StringBuilder digits = new StringBuilder(rounded.abs().toString());
        while (digits.length() <= places) digits.insert(0, '0');
        if (places > 0) digits.insert(digits.length() - places, '.');
        return rounded.signum() < 0 ? "-" + digits : digits.toString();
    }

    /** {@code "n/d"}, or {@code "n"} when the denominator is 1, which is the canonical form. */
    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE)
                ? numerator.toString()
                : numerator + "/" + denominator;
    }
}
