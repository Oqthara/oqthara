package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Count and bound arguments for the list and text methods, and character case mapping.
 *
 * <p>Before this class the methods narrowed their arguments on their own. Every one of them
 * accepted a fractional count by truncating it ({@code xs.take(1.5)} was {@code [10]}), and
 * {@code xs.take("a")} reached a raw cast and escaped as {@code internal error:
 * ClassCastException}, which is the one shape a program error must never take.
 *
 * <p>Two rules are asserted separately. A bound the language cannot honour is refused, and a
 * bound that happens to be written as a decimal but names a whole number is accepted, because
 * {@code 1 == 1.0} (ADR-0017) means {@code 2.0} and {@code 2} are the same value. A test that
 * only checked the refusals would pass a change that also broke the second rule.
 */
class TextAndListBoundTest {

    private final MethodResolverImpl resolver = new MethodResolverImpl();
    private final Span span = Span.synthetic();

    private List<Object> list(long... values) {
        List<Object> items = new ArrayList<>(values.length);
        for (long value : values) items.add(value);
        return items;
    }

    private Object invoke(Object target, String name, Object... args) {
        OqCallable callable = resolver.resolve(target, name, span);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(null, values, span);
    }

    private String refusal(Object target, String name, Object... args) {
        OqError error = assertThrows(OqError.class, () -> invoke(target, name, args),
                name + " should have refused its argument");
        assertFalse(error.getMessage().contains("internal error"),
                "a refusal must be a program error, not a JVM failure: " + error.getMessage());
        return error.getMessage();
    }

    // --- fractional counts -------------------------------------------------------------------

    @Test
    void aFractionalCountIsRefusedRatherThanTruncated() {
        // Each of these silently dropped the fractional part before: 1.5 meant 1.
        assertEquals("a count for take must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "take", 1.5));
        assertEquals("a count for skip must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "skip", 1.5));
        assertEquals("a slice start must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "subList", 1.5, 3.5));
        assertEquals("a slice start must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "slice", 1.5, 3.5));
        assertEquals("a slice start must be a whole number, got decimal",
                refusal("abcdef", "substring", 1.5, 4L));
        assertEquals("a slice start must be a whole number, got decimal",
                refusal("abcdef", "slice", 1.5, 4L));
    }

    @Test
    void aFractionalEndIsRefusedUnlessTheStartIsAlreadyWrong() {
        // The start is checked first, so a program with two bad bounds is told about the first
        // one it wrote. The end still has to be checked on its own when the start is sound.
        assertEquals("a slice end must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "subList", 1L, 3.5));
        assertEquals("a slice end must be a whole number, got decimal",
                refusal("abcdef", "substring", 1L, 3.5));
    }

    @Test
    void aWholeNumberWrittenAsADecimalIsStillAccepted() {
        // ADR-0017: 2.0 and 2 are the same value, so refusing 2.0 would contradict equality.
        assertEquals(list(10, 20), invoke(list(10, 20, 30, 40), "take", 2.0));
        assertEquals(list(30, 40), invoke(list(10, 20, 30, 40), "skip", 2.0));
        assertEquals(list(20, 30), invoke(list(10, 20, 30, 40), "subList", 1.0, 3.0));
        assertEquals("bcd", invoke("abcdef", "substring", 1.0, 4.0));
    }

    // --- arguments that are not numbers at all ------------------------------------------------

    @Test
    void aCountThatIsNotANumberIsRefusedInsteadOfCrashingTheRuntime() {
        // Was `internal error: ClassCastException: class java.lang.String cannot be cast to
        // class java.lang.Number`, exit code 70.
        assertEquals("a count for take must be a whole number, got text",
                refusal(list(10, 20), "take", "a"));
        assertEquals("a count for skip must be a whole number, got text",
                refusal(list(10, 20), "skip", "a"));
        assertEquals("a slice end must be a whole number, got nothing",
                refusal(list(10, 20), "subList", 0L, null));
        assertEquals("a width for pad_start must be a whole number, got text",
                refusal("x", "pad_start", "a", "0"));
    }

    // --- non-finite and huge bounds -----------------------------------------------------------

    @Test
    void aNonFiniteBoundIsRefusedRatherThanSaturatedToZeroOrTheMaximum() {
        // NaN is not a whole number, so it takes the fractional refusal. An infinity is past
        // the 64-bit range, which the out-of-range branch reports. Before either check, NaN
        // made take() return nothing and skip() return everything, and an infinity was
        // narrowed to Long.MAX_VALUE.
        assertEquals("a count for take must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "take", Double.NaN));
        assertEquals("a count for skip must be a whole number, got decimal",
                refusal(list(10, 20, 30, 40), "skip", Double.NaN));
        assertTrue(refusal(list(10, 20, 30, 40), "slice", Double.POSITIVE_INFINITY, 2L)
                        .startsWith("a slice start is out of range: "),
                "an infinity is past the range, not fractional");
        assertTrue(refusal(list(10, 20, 30, 40), "skip", Double.NEGATIVE_INFINITY)
                        .startsWith("a count for skip is out of range: "),
                "a negative infinity is past the range, not fractional");
    }

    @Test
    void aWholeBoundPastTheSixtyFourBitRangeIsRefusedAsOutOfRange() {
        // A value this large is whole, so calling it fractional would be a lie. It also names
        // no position any collection can have, which is the part worth saying.
        OqError error = assertThrows(OqError.class,
                () -> invoke(list(10, 20), "take", 1e300));
        assertTrue(error.getMessage().startsWith("a count for take is out of range: "),
                error.getMessage());
        assertTrue(error.hint().contains("9223372036854775807"), error.hint());
    }

    @Test
    void anIndexPastTheSixtyFourBitRangeIsRefusedAsOutOfRange() {
        // Indexing does not go through the resolver, so it had the same cast and reported the
        // saturated index Long.MAX_VALUE instead of the value the program wrote.
        OqError error = assertThrows(OqError.class,
                () -> dev.oqthara.runtime.Values.index(list(10, 20), 1e300, span));
        assertTrue(error.getMessage().startsWith("an index is out of range: "),
                error.getMessage());
    }

    // --- repeat ------------------------------------------------------------------------------

    @Test
    void aNegativeRepeatIsAProgramError() {
        // Was `internal error: IllegalArgumentException: count is negative: -1`, exit code 70:
        // the String.repeat contract escaped the language.
        OqError error = assertThrows(OqError.class, () -> invoke("x", "repeat", -1L));
        assertEquals("repeat() needs a count of zero or more, got -1", error.getMessage());
        assertTrue(error.hint().contains("negative"), error.hint());
    }

    @Test
    void zeroRepeatsAreEmptyTextAndAGoodCountStillRepeats() {
        assertEquals("", invoke("x", "repeat", 0L));
        assertEquals("ababab", invoke("ab", "repeat", 3L));
    }

    @Test
    void aRepeatThatWouldNotFitIsRefusedBeforeAllocating() {
        // The product is what would overflow, so the guard has to be on the product rather
        // than on the count. Two characters at Integer.MAX_VALUE is already too much.
        OqError error = assertThrows(OqError.class,
                () -> invoke("ab", "repeat", (long) Integer.MAX_VALUE));
        assertTrue(error.getMessage().startsWith("repeat() count is too large: "),
                error.getMessage());
    }

    @Test
    void aFractionalRepeatIsRefused() {
        assertEquals("a count for repeat must be a whole number, got decimal",
                refusal("x", "repeat", 2.5));
    }

    // --- pad ---------------------------------------------------------------------------------

    @Test
    void aFractionalPaddingWidthIsRefused() {
        // `pad_start(9.5)` produced nine characters, one short of the width asked for.
        assertEquals("a width for pad_start must be a whole number, got decimal",
                refusal("x", "pad_start", 9.5, "0"));
        assertEquals("a width for pad_end must be a whole number, got decimal",
                refusal("x", "pad_end", 9.5, "0"));
    }

    @Test
    void paddingStillPadsOnTheSideItNames() {
        assertEquals("000x", invoke("x", "pad_start", 4L, "0"));
        assertEquals("x000", invoke("x", "pad_end", 4L, "0"));
        // Already wider than the target, so nothing is added and nothing is removed.
        assertEquals("abc", invoke("abc", "pad_start", 2L, "0"));
    }

    // --- capitalize --------------------------------------------------------------------------

    @Test
    void capitalizeUpperCasesACharacterOutsideTheBasicMultilingualPlane() {
        // `charAt(0)` reads half of a surrogate pair and `Character.toUpperCase(char)` cannot
        // map the letter at all, so this returned its input unchanged (issue #148).
        assertEquals("\uD801\uDC00x", invoke("\uD801\uDC28x", "capitalize"));
    }

    @Test
    void capitalizeLeavesTheRestOfTheTextAlone() {
        assertEquals("Hello world", invoke("hello world", "capitalize"));
        assertEquals("", invoke("", "capitalize"));
        // Only the first character is mapped, and the second is already upper case.
        assertEquals("AB", invoke("aB", "capitalize"));
    }

    @Test
    void capitalizeKeepsALeadingCharacterItCannotUpperCase() {
        // A digit and an emoji have no upper case, so the text is unchanged rather than
        // truncated to the case mapping's length.
        assertEquals("1abc", invoke("1abc", "capitalize"));
        assertEquals("\uD83D\uDE00abc", invoke("\uD83D\uDE00abc", "capitalize"));
    }
}
