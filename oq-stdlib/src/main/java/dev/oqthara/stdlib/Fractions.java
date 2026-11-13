package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqFraction;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.math.BigInteger;

/**
 * Exact rational arithmetic: the {@code fraction} type and the operations over it, per ADR-0053.
 *
 * <p>Every function here takes and returns a fraction value, so the whole domain is usable
 * without new syntax, operators or overloads: {@code fraction_add(fraction(1, 3), fraction(1, 6))}
 * is {@code 1/2} exactly. The type itself lives in the runtime because {@code Values} has to name
 * and display it; this class is only the surface.
 *
 * <p>The inputs to {@link #fractionConstructor} must be whole numbers. A {@code double} that is
 * not integral is refused rather than truncated, because a fraction built from {@code 0.1} would
 * carry the error that made {@code 0.1} inexact in the first place, and the type would then be
 * lying about what it is. For the same reason nothing here converts a fraction to a
 * {@code double}, and {@code fraction_compare} cross-multiplies instead of comparing decimals.
 * The single conversion outward is {@code fraction_to_decimal}, which states its precision.
 *
 * <p>What is deliberately absent: a {@code decimal} type. The name is taken — {@code type_of}
 * already answers {@code "decimal"} for a {@code double} — and an exact decimal domain over
 * {@code int}/{@code count}/{@code total}/{@code decimal}/{@code decimal_of} is a separate
 * decision this module does not make.
 */
final class Fractions {
    private Fractions() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("fraction", 2, (host, args, span) ->
                fractionConstructor(args.get(0), args.get(1), span))
                .named("numerator", "denominator"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_of", 1, (host, args, span) ->
                parse(text(args.get(0), "fraction_of", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_text", 1, (host, args, span) ->
                fraction(args.get(0), "fraction_text", span).toString()).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_num", 1, (host, args, span) ->
                whole(fraction(args.get(0), "fraction_num", span).numerator(), "fraction_num", span))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_den", 1, (host, args, span) ->
                whole(fraction(args.get(0), "fraction_den", span).denominator(), "fraction_den", span))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_add", 2, (host, args, span) ->
                fraction(args.get(0), "fraction_add", span)
                        .plus(fraction(args.get(1), "fraction_add", span)))
                .named("left", "right"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_sub", 2, (host, args, span) ->
                fraction(args.get(0), "fraction_sub", span)
                        .minus(fraction(args.get(1), "fraction_sub", span)))
                .named("left", "right"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_mul", 2, (host, args, span) ->
                fraction(args.get(0), "fraction_mul", span)
                        .times(fraction(args.get(1), "fraction_mul", span)))
                .named("left", "right"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_div", 2, (host, args, span) ->
                fraction(args.get(0), "fraction_div", span)
                        .dividedBy(fraction(args.get(1), "fraction_div", span)))
                .named("left", "right"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_neg", 1, (host, args, span) ->
                fraction(args.get(0), "fraction_neg", span).negate()).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_abs", 1, (host, args, span) ->
                fraction(args.get(0), "fraction_abs", span).abs()).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_compare", 2, (host, args, span) ->
                (long) fraction(args.get(0), "fraction_compare", span)
                        .compareTo(fraction(args.get(1), "fraction_compare", span)))
                .named("left", "right"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_floor", 1, (host, args, span) ->
                whole(fraction(args.get(0), "fraction_floor", span).floor(), "fraction_floor", span))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_ceil", 1, (host, args, span) ->
                whole(fraction(args.get(0), "fraction_ceil", span).ceil(), "fraction_ceil", span))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_round", 1, (host, args, span) ->
                whole(fraction(args.get(0), "fraction_round", span).round(), "fraction_round", span))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_pow", 2, (host, args, span) ->
                fraction(args.get(0), "fraction_pow", span)
                        .pow(exponent(args.get(1), "fraction_pow", span)))
                .named("value", "exponent"));
        interpreter.defineBuiltin(NativeFunction.of("fraction_to_decimal", 2, (host, args, span) -> {
            OqFraction value = fraction(args.get(0), "fraction_to_decimal", span);
            long places = places(args.get(1), span);
            return value.toDecimal((int) places);
        }).named("value", "places"));
    }

    /**
     * Builds a fraction from two whole numbers. The denominator's sign moves to the numerator
     * and both are reduced, so the result is the one value that pair denotes.
     */
    private static OqFraction fractionConstructor(Object numerator, Object denominator, Span span) {
        return new OqFraction(exact(numerator, "fraction", span, "the numerator"),
                exact(denominator, "fraction", span, "the denominator"));
    }

    /**
     * A whole number as a {@link BigInteger}, refusing a decimal that is not integral.
     *
     * <p>Truncating {@code 0.1} to zero would turn a caller's mistake into a plausible answer,
     * and keeping the decimal's digits would build a fraction out of a number that is already
     * wrong. The message says which argument and why, because "needs a whole number" alone
     * leaves the caller guessing whether {@code 2.0} is acceptable (it is).
     */
    private static BigInteger exact(Object value, String builtin, Span span, String role) {
        if (value instanceof Long l) return BigInteger.valueOf(l);
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new OqError(builtin + "() needs a whole number for " + role + ", got "
                        + Values.display(d), span,
                        "A fraction cannot be built from an infinity or a NaN.");
            }
            double rounded = Math.rint(d);
            if (rounded != d) {
                throw new OqError(builtin + "() needs a whole number for " + role + ", got "
                        + Values.display(d), span,
                        "A fraction built from a decimal would carry that decimal's rounding "
                                + "error, which is what a fraction exists to avoid.");
            }
            // The double is integral, so this conversion is exact at every magnitude the double
            // can hold; `BigDecimal.valueOf(double)` would not be.
            return new java.math.BigDecimal(rounded).toBigInteger();
        }
        throw new OqError(builtin + "() needs a whole number for " + role + ", got "
                + Values.typeName(value), span);
    }

    /**
     * Parses {@code "3/4"}, {@code "-2/5"} or a bare whole number such as {@code "7"}. Anything
     * else is refused: the accepted forms are listed in the message because a parser that
     * guesses at {@code "3 / 4"} or {@code "0.75"} would answer a question the caller did not
     * ask.
     */
    private static OqFraction parse(String text, Span span) {
        String trimmed = text.strip();
        int slash = trimmed.indexOf('/');
        try {
            if (slash < 0) {
                return new OqFraction(new BigInteger(trimmed), BigInteger.ONE);
            }
            if (trimmed.indexOf('/', slash + 1) >= 0) {
                throw new NumberFormatException("more than one slash");
            }
            return new OqFraction(new BigInteger(trimmed.substring(0, slash).strip()),
                    new BigInteger(trimmed.substring(slash + 1).strip()));
        } catch (NumberFormatException e) {
            throw new OqError("fraction_of() cannot read " + Values.display(text), span,
                    "Accepted forms are \"n/d\", \"-n/d\" and a bare whole number such as \"7\".");
        }
    }

    /**
     * The integer exponent for {@code fraction_pow}. A decimal exponent has no exact rational
     * answer in general, so it is refused here rather than approximated.
     */
    private static long exponent(Object value, String builtin, Span span) {
        if (value instanceof Long l) return l;
        if (value instanceof Double d && Double.isFinite(d) && Math.rint(d) == d
                && Values.fitsInLong(d)) {
            return (long) (double) d;
        }
        throw new OqError(builtin + "() needs a whole-number exponent, got "
                + Values.display(value), span,
                "A fractional exponent has no exact rational answer in general. Use pow() when "
                        + "an approximate decimal is enough.");
    }

    /**
     * The number of decimal places for {@code fraction_to_decimal}, bounded exactly as
     * {@code round(value, places)} bounds it so the two agree on what precision means.
     */
    private static long places(Object value, Span span) {
        if (!Values.isNumber(value)) {
            throw new OqError("fraction_to_decimal() needs a number, got " + Values.typeName(value),
                    span);
        }
        long places = Values.asLong(value);
        if (places < 0 || places > 15) {
            throw new OqError("fraction_to_decimal() places must be between 0 and 15, got "
                    + places, span);
        }
        return places;
    }

    /**
     * The whole-number result of {@code fraction_floor}, {@code fraction_ceil},
     * {@code fraction_round}, {@code fraction_num} and {@code fraction_den}.
     *
     * <p>A {@link BigInteger} that fits a {@code long} comes back as an ordinary number so it
     * behaves like every other whole number in the language. One that does not is refused
     * rather than truncated: a fraction can be raised to a power that outgrows the type, and
     * handing back a saturated {@link Long#MAX_VALUE} would be a wrong answer stated
     * confidently, which is the one thing this module does not do.
     */
    private static Object whole(BigInteger value, String builtin, Span span) {
        if (value.bitLength() < 64) return value.longValue();
        throw new OqError(builtin + "() result does not fit a number: it has "
                + value.bitLength() + " bits", span,
                "The language's whole numbers stop at 64 bits. Use fraction_text() for the "
                        + "exact value, or fraction_to_decimal() for a stated precision.");
    }

    private static OqFraction fraction(Object value, String builtin, Span span) {
        if (value instanceof OqFraction f) return f;
        throw new OqError(builtin + "() needs a fraction, got " + Values.typeName(value), span,
                "Build one with fraction(numerator, denominator) or fraction_of(\"3/4\").");
    }

    private static String text(Object value, String builtin, Span span) {
        if (value instanceof String s) return s;
        throw new OqError(builtin + "() needs text, got " + Values.typeName(value), span);
    }
}
