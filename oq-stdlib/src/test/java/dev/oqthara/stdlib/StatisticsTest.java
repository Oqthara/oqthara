package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code statistics}, {@code uuid}/{@code is_uuid} and {@code heap_*}/{@code bisect_*} from
 * ADR-0041.
 *
 * <p>Every expected number below came from running the built CLI before it was written down.
 * The sample statistics are checked against an external figure rather than against this
 * implementation's own output, because a test that echoes the code proves only that the code
 * is deterministic.
 *
 * <p>The tie-breaking and interpolation cases are here because the alternatives are all
 * defensible and only one is implemented: a future change to nearest-rank percentiles or
 * to "largest wins" ties should fail a test, not silently alter answers.
 */
class StatisticsTest {

    @TempDir
    Path dir;

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), dir, new MethodResolverImpl());
            Stdlib.install(host);
        }
        return host;
    }

    private Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    // --- central tendency --------------------------------------------------------------------

    @Test
    void meanOfTheOqtharaReadmeSample() {
        assertEquals(2.0, number(call("mean", list(1L, 2L, 3L))), 1e-12);
    }

    @Test
    void aWholeMeanIsAnIntegerNotADouble() {
        assertEquals(3L, call("mean", list(2L, 4L)));
    }

    @Test
    void meanOfAFractionalInputKeepsItsFraction() {
        assertEquals(2.5, number(call("mean", list(1L, 2L, 3L, 4L))), 1e-12);
    }

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertEquals(3.0, number(call("median", list(5L, 1L, 3L))), 1e-12);
    }

    @Test
    void medianOfAnEvenCountIsTheMeanOfTheTwoMiddles() {
        assertEquals(2.5, number(call("median", list(4L, 1L, 3L, 2L))), 1e-12);
    }

    @Test
    void medianDoesNotRequireSortedInput() {
        assertEquals(3.0, number(call("median", list(9L, 3L, 1L, 3L, 100L))), 1e-12);
    }

    /**
     * The mean of the two middles must not overflow at either end of the double range.
     *
     * <p>The obvious {@code (a + b) / 2} is {@code infinity} when both middles are large and
     * share a sign, and {@code 0.0} when both are tiny and equal. Neither is the true mean, and
     * both used to reach the caller: {@code median([1e308, 1e308])} answered {@code infinity}.
     * The fix moves from the lower end, which keeps the difference below the larger magnitude.
     */
    @Test
    void medianOfAnEvenCountSurvivesBothEndsOfTheDoubleRange() {
        assertEquals(1e308, number(call("median", list(1e308, 1e308))), 0.0);
        assertEquals(-1e308, number(call("median", list(-1e308, -1e308))), 0.0);
        assertEquals(4.9e-324, number(call("median", list(4.9e-324, 4.9e-324))), 0.0);
        assertEquals(5e-324, number(call("median", list(5e-324, 5e-324))), 0.0);
        // Opposite signs make the difference overflow, so the symmetric form must be used.
        assertEquals(0.0, number(call("median", list(-1e308, 1e308))), 0.0);
        assertEquals(0.0, number(call("median", list(-Double.MAX_VALUE, Double.MAX_VALUE))), 0.0);
    }

    /**
     * A subnormal pair one step apart rounds back to the lower end, because there is no double
     * between them. The answer must still be the higher of the two rather than the lower, and
     * in no case {@code 0.0}; the test compares numerically because the printer shows every
     * subnormal as {@code 0.0}.
     */
    @Test
    void medianOfTwoAdjacentSubnormalsStaysInTheSubnormalRange() {
        double middle = number(call("median", list(4.9e-324, 1e-323)));
        assertTrue(middle > 0 && middle <= 1e-323,
                "median was " + Double.doubleToLongBits(middle));
    }

    /**
     * {@code percentile} interpolates between the same two values, so it had the same overflow:
     * the symmetric form was already in use there, which is why this test is about the shared
     * helper and not a second fix.
     */
    @Test
    void percentileSurvivesBothEndsOfTheDoubleRange() {
        assertEquals(1e308, number(call("percentile", list(1e308, 1e308), 50L)), 0.0);
        assertEquals(0.0, number(call("percentile", list(-1e308, 1e308), 50L)), 0.0);
        // Out-of-order middle values are sorted first, so the ends are the middles here.
        assertEquals(1e308, number(call("percentile", list(1e308, -1e308, 1e308), 100L)), 0.0);
    }

    // --- spread ------------------------------------------------------------------------------

    /**
     * The classic worked example: the sample variance of
     * {@code [2, 4, 4, 4, 5, 5, 7, 9]} is {@code 32/7}, and its sample standard deviation is
     * {@code 2.138089935299395}. Dividing by {@code n} instead would give {@code 4} exactly,
     * so this test fails loudly if the population form is ever substituted.
     */
    @Test
    void sampleVarianceDividesByNMinusOne() {
        List<Object> sample = list(2L, 4L, 4L, 4L, 5L, 5L, 7L, 9L);
        assertEquals(32.0 / 7.0, number(call("variance", sample)), 1e-12);
        assertEquals(2.138089935299395, number(call("stdev", sample)), 1e-12);
    }

    @Test
    void varianceOfTwoIdenticalValuesIsZero() {
        assertEquals(0.0, number(call("variance", list(7L, 7L))), 1e-12);
    }

    /**
     * Squaring a delta near the top of the double range overflows long before the deviation
     * itself does: {@code (2e308)^2} is infinite. The spread of the two extremes is
     * {@code sqrt(2) * 1e308}, which is representable, so it must come back finite.
     */
    @Test
    void aDeviationOfTheTwoExtremesSurvivesSquaring() {
        double expected = Math.sqrt(2.0) * 1e308;
        assertEquals(expected, number(call("stdev", list(1e308, -1e308))));
        assertTrue(Double.isFinite(number(call("stdev", list(1e308, -1e308)))));
        // The variance is genuinely unrepresentable, so it reports infinity rather than a lie.
        assertEquals(Double.POSITIVE_INFINITY, number(call("variance", list(1e308, -1e308))));
    }

    @Test
    void aDeviationOfValuesThatCancelStaysFinite() {
        // The mean is 1e308/3, and each delta is far below the range where squaring overflows.
        assertEquals(1.1547005383792515e308,
                number(call("stdev", list(1e308, 1e308, -1e308))));
        assertEquals(1.1547005383792515e308,
                number(call("stdev", list(-1e308, 1e308, 1e308))));
    }

    @Test
    void identicalExtremesHaveNoSpread() {
        // The three values sum to 3e308, past the top of the range, but the mean is 1e308 and
        // every deviation is zero, so the answer is representable and must not be an error.
        assertEquals(0.0, number(call("stdev", list(1e308, 1e308, 1e308))));
        assertEquals(0.0, number(call("variance", list(1e308, 1e308, 1e308))));
    }

    @Test
    void aSingletonHasNoSampleVarianceAndSaysSo() {
        OqError error = assertThrows(OqError.class, () -> call("variance", list(5L)));
        assertEquals("variance() needs at least two items", error.getMessage());
        assertThrows(OqError.class, () -> call("stdev", list(5L)));
    }

    // --- mode --------------------------------------------------------------------------------

    @Test
    void modeReturnsTheMostFrequentValue() {
        assertEquals(2L, call("mode", list(1L, 2L, 2L, 3L)));
    }

    /**
     * The tie rule: the smallest wins. Python raises {@code StatisticsError} for a multimodal
     * input, which would make {@code mode} partial; this implementation is total and documents
     * the choice instead (ADR-0041 D4).
     */
    @Test
    void modeBreaksTiesByReturningTheSmallest() {
        assertEquals(1L, call("mode", list(3L, 1L, 1L, 3L)));
        assertEquals(2L, call("mode", list(7L, 2L, 7L, 2L, 9L)));
    }

    @Test
    void modeOfAUniformListIsThatValue() {
        assertEquals(4L, call("mode", list(4L, 4L, 4L)));
    }

    /**
     * {@code -0.0 == 0.0} in the language, and ADR-0017 makes them the same key, so a list
     * holding both is two copies of one value. Java's {@code Double.equals} disagrees, which
     * is why the grouping has to use the language's equality.
     */
    @Test
    void modeGroupsSignedZeroTogether() {
        assertEquals(0L, call("mode", list(0L, -0.0, 1L, 1L)));
        // The group is one value of frequency two, and mode normalises a whole result.
        assertEquals(0L, call("mode", list(-0.0, 0L)));
        // 1.5 ties with the merged zero group, and the smaller of the two wins.
        assertEquals(0L, call("mode", list(1.5, -0.0, 0L, 1.5)));
        // Four copies of zero beat two copies of one, whichever sign each copy carries.
        assertEquals(0L, call("mode", list(1L, 1L, 0L, -0.0, 0.0, -0.0)));
    }

    @Test
    void modeGroupsAWholeNumberWithTheDecimalItEquals() {
        assertEquals(2L, call("mode", list(2L, 2.0, 3L)));
        assertEquals(2L, call("mode", list(2.0, 2L, 3L)));
        // Grouped by the language, {2, 2.0} has frequency two and beats loner 3s.
        assertEquals(2L, call("mode", list(2L, 2.0, 3L, 3L, 5L)));
    }

    // --- percentile --------------------------------------------------------------------------

    @Test
    void percentileInterpolatesBetweenClosestRanks() {
        List<Object> sample = list(1L, 2L, 3L, 4L);
        assertEquals(2.5, number(call("percentile", sample, 50L)), 1e-12);
        assertEquals(1.0, number(call("percentile", sample, 0L)), 1e-12);
        assertEquals(4.0, number(call("percentile", sample, 100L)), 1e-12);
        assertEquals(1.75, number(call("percentile", sample, 25L)), 1e-12);
    }

    @Test
    void percentileAtAHundredPercentIsTheLargestValue() {
        assertEquals(9L, call("percentile", list(3L, 9L, 5L), 100L));
    }

    @Test
    void percentileOfASingleItemIsThatItemAtEveryRank() {
        assertEquals(7L, call("percentile", list(7L), 0L));
        assertEquals(7L, call("percentile", list(7L), 50L));
        assertEquals(7L, call("percentile", list(7L), 100L));
    }

    /** Out of range is a program error, not a clamp: a clamp turns a typo into an answer. */
    @Test
    void percentileOutsideZeroToOneHundredIsRejected() {
        OqError high = assertThrows(OqError.class,
                () -> call("percentile", list(1L, 2L), 101L));
        assertTrue(high.getMessage().startsWith("percentile() needs a percent between 0 and 100"));
        assertThrows(OqError.class, () -> call("percentile", list(1L, 2L), -1L));
    }

    // --- shared input rules ------------------------------------------------------------------

    @Test
    void anEmptyListIsAnErrorNamingTheFunction() {
        for (String name : List.of("mean", "median", "variance", "stdev", "mode")) {
            OqError error = assertThrows(OqError.class, () -> call(name, list()),
                    name + " should reject an empty list");
            assertEquals(name + "() needs at least one item", error.getMessage());
        }
        assertThrows(OqError.class, () -> call("percentile", list(), 50L));
    }

    /** One bad element should be named, because the caller's bug is almost always just that one. */
    @Test
    void aNonNumericItemIsNamedByPosition() {
        OqError error = assertThrows(OqError.class,
                () -> call("mean", list(1L, "two", 3L)));
        assertEquals("mean() needs numbers, but item 1 is text", error.getMessage());
    }

    @Test
    void everyStatisticsFunctionAcceptsASetOrRange() {
        Set<Object> set = new HashSet<>(List.of(1L, 2L, 3L));
        assertEquals(2.0, number(call("mean", set)), 1e-12);
    }

    @Test
    void anOverflowingSumIsReportedRatherThanReturnedAsInfinity() {
        OqError error = assertThrows(OqError.class,
                () -> call("mean", list(1e308, 1e308)));
        assertEquals("mean() overflowed", error.getMessage());
    }

    /**
     * Summing left to right loses whichever extreme arrives second: {@code 1e308 + 1e308}
     * is already infinite. The exact sum is {@code 1e308}, so every ordering of the same
     * three numbers must report the same mean.
     */
    @Test
    void meanIsIndependentOfTheOrderOfTheExtremes() {
        double expected = 1e308 / 3.0;
        assertEquals(expected, number(call("mean", list(1e308, 1e308, -1e308))));
        assertEquals(expected, number(call("mean", list(1e308, -1e308, 1e308))));
        assertEquals(expected, number(call("mean", list(-1e308, 1e308, 1e308))));
        assertEquals(-expected, number(call("mean", list(-1e308, -1e308, 1e308))));
    }

    @Test
    void anExactSumThatIsRepresentableIsNotReportedAsOverflow() {
        assertEquals(0.0, number(call("mean", list(1e308, -1e308))));
        // The naive sum of these five hits infinity; the exact sum is 1e308 and divides by five.
        assertEquals(2.0e307, number(call("mean", list(1e308, 1e308, -1e308, -1e308, 1e308))));
    }

    // --- identifiers -------------------------------------------------------------------------

    @Test
    void uuidIsCanonicalLowercaseWithVersionFour() {
        String id = (String) call("uuid");
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}"
                + "-[0-9a-f]{12}"), "unexpected uuid shape: " + id);
    }

    @Test
    void consecutiveUuidsDiffer() {
        assertNotEquals(call("uuid"), call("uuid"));
    }

    @Test
    void isUuidAcceptsWhatUuidProduces() {
        assertEquals(Boolean.TRUE, call("is_uuid", call("uuid")));
        assertEquals(Boolean.TRUE, call("is_uuid", "ea2631a9-95a8-4304-829d-060fd002af6f"));
    }

    /**
     * {@link java.util.UUID#fromString} accepts all three of these; {@code is_uuid} must not,
     * because accepting a spelling other systems reject defeats the point of a validator.
     */
    @Test
    void isUuidRejectsTheSpellingsJavaWouldAccept() {
        String id = "ea2631a9-95a8-4304-829d-060fd002af6f";
        assertEquals(Boolean.FALSE, call("is_uuid", "{" + id + "}"));
        assertEquals(Boolean.FALSE, call("is_uuid", "urn:uuid:" + id));
        assertEquals(Boolean.FALSE,
                call("is_uuid", "ea2631a995a84304829d060fd002af6f"));
    }

    @Test
    void isUuidRejectsUppercaseAndNonUuids() {
        assertEquals(Boolean.FALSE,
                call("is_uuid", "EA2631A9-95A8-4304-829D-060FD002AF6F"));
        assertEquals(Boolean.FALSE, call("is_uuid", "not-a-uuid"));
        assertEquals(Boolean.FALSE, call("is_uuid", ""));
        assertEquals(Boolean.FALSE, call("is_uuid", "ea2631a9-95a8-4304-829d-060fd002af6"));
    }

    // --- ordered search ----------------------------------------------------------------------

    @Test
    void bisectLeftAndRightDifferOnDuplicates() {
        List<Object> sample = list(1L, 2L, 2L, 3L);
        assertEquals(1L, call("bisect_left", sample, 2L));
        assertEquals(3L, call("bisect_right", sample, 2L));
    }

    @Test
    void bisectLeftAndRightAgreeWithoutDuplicates() {
        List<Object> sample = list(1L, 3L, 5L);
        assertEquals(2L, call("bisect_left", sample, 4L));
        assertEquals(2L, call("bisect_right", sample, 4L));
        assertEquals(2L, call("bisect_left", sample, 5L));
        assertEquals(3L, call("bisect_right", sample, 5L));
    }

    @Test
    void bisectOnAnExhaustedRangeIsTheLengthOrZero() {
        assertEquals(0L, call("bisect_left", list(2L, 3L), 1L));
        assertEquals(2L, call("bisect_right", list(2L, 3L), 9L));
        assertEquals(0L, call("bisect_left", list(), 9L));
    }

    /**
     * ADR-0017 says {@code -0.0 == 0.0}, so a sorted list holding both is sorted, and the
     * search must treat the two as one key: the left edge is before both, the right after.
     */
    @Test
    void bisectTreatsSignedZeroAsOneKey() {
        List<Object> sample = list(-0.0, 0.0, 1.0);
        assertEquals(1L, call("bisect_left", sample, 0L));
        assertEquals(2L, call("bisect_right", sample, 0L));
        // The language orders -0.0 below 0.0, so -0.0 itself sits at the front.
        assertEquals(0L, call("bisect_left", sample, -0.0));
        assertEquals(1L, call("bisect_right", sample, -0.0));
        // A whole-number zero is the same key as 0.0, not as -0.0.
        assertEquals(0L, call("bisect_left", list(0.0, 1.0), 0L));
        assertEquals(1L, call("bisect_right", list(0.0, 1.0), 0L));
    }

    /**
     * NaN is unordered against everything, including itself, so no comparison can say where
     * it belongs. The honest answer is one past the end, which is also where the language's
     * ordering puts it. It must not hang, and it must not report a position it cannot justify.
     */
    @Test
    void bisectWithNaNDoesNotHangAndReportsTheEnd() {
        double nan = Double.NaN;
        assertEquals(2L, call("bisect_left", list(1.0, 2.0), nan));
        assertEquals(2L, call("bisect_right", list(1.0, 2.0), nan));
        assertEquals(2L, call("bisect_left", list(1.0, 2.0, nan), nan));
        assertEquals(3L, call("bisect_right", list(1.0, 2.0, nan), nan));
        assertEquals(0L, call("bisect_left", list(), nan));
    }

    // --- heap --------------------------------------------------------------------------------

    @Test
    void heapPopReturnsAscendingOrderRegardlessOfPushOrder() {
        List<Object> items = list();
        for (Object value : List.of(5L, 1L, 4L, 1L, 3L, 9L, 0L)) {
            call("heap_push", items, value);
        }
        List<Object> popped = new ArrayList<>();
        while (!items.isEmpty()) popped.add(call("heap_pop", items));
        assertEquals(list(0L, 1L, 1L, 3L, 4L, 5L, 9L), popped);
    }

    @Test
    void heapPushMutatesInPlaceAndReturnsTheList() {
        List<Object> items = list();
        assertEquals(items, call("heap_push", items, 7L));
        assertEquals(1, items.size());
    }

    /**
     * The array is a heap, not a sorted list: {@code [1, 5, 3]} is a legitimate min-heap and
     * asserting a sorted array here would over-specify how the implementation works.
     */
    @Test
    void theArrayIsAHeapRatherThanSorted() {
        List<Object> items = list();
        call("heap_push", items, 5L);
        call("heap_push", items, 1L);
        call("heap_push", items, 3L);
        assertEquals(1L, items.get(0));
    }

    @Test
    void heapPopOnAnEmptyListIsRejected() {
        OqError error = assertThrows(OqError.class, () -> call("heap_pop", list()));
        assertEquals("heap_pop() needs a non-empty list", error.getMessage());
    }

    /**
     * A whole number and a decimal are ordered by the language's {@code <}, so a heap may
     * hold both. Java's {@code Comparable} cast failed on the pair, which made a mixed heap
     * unusable even though the values are perfectly comparable.
     */
    @Test
    void aHeapHoldsWholeNumbersAndDecimalsTogether() {
        assertEquals(list(1L, 1.5), call("heap_push", list(1L), 1.5));
        assertEquals(list(1L, 1.5), call("heap_push", list(1.5), 1L));
        assertEquals(list(1L, 2L, 1.5), call("heap_push", list(1L, 2L), 1.5));
        List<Object> items = list(1L);
        call("heap_push", items, 1.5);
        assertEquals(1L, call("heap_pop", items));
        assertEquals(1.5, call("heap_pop", items));
    }

    /**
     * A rejected push must leave the heap as it found it. The type check runs before the
     * item is added, which is what makes the failure atomic; a heap with a stray string in
     * it would corrupt every later pop.
     */
    @Test
    void aRejectedPushLeavesTheHeapUntouched() {
        List<Object> items = list();
        for (Object value : List.of(3L, 1L, 2L)) call("heap_push", items, value);
        List<Object> before = new ArrayList<>(items);
        assertThrows(OqError.class, () -> call("heap_push", items, "x"));
        assertEquals(before, items, "a rejected push must not disturb the array");
        assertEquals(1L, call("heap_pop", items));
        assertEquals(2L, call("heap_pop", items));
        assertEquals(3L, call("heap_pop", items));
    }

    @Test
    void heapFunctionsRefuseNonNumbers() {
        assertThrows(OqError.class, () -> call("heap_push", list(), "x"));
        assertThrows(OqError.class, () -> call("bisect_left", list(1L), "x"));
    }

    @Test
    void heapAndBisectRefuseANonList() {
        assertThrows(OqError.class, () -> call("heap_pop", "abc"));
        assertThrows(OqError.class, () -> call("bisect_left", 5L, 1L));
    }

    // --- rounding ties ---------------------------------------------------------------------

    /**
     * The tie rule was implemented but never stated, which the inventory recorded as a contract
     * gap. It is upward -- toward positive infinity -- not banker's rounding and not away from
     * zero. All six expected values below came from the built CLI before they were written here,
     * and the three that would differ under the two rejected rules are the point.
     */
    @Test
    void roundingTiesGoUpwardNotToEvenAndNotAwayFromZero() {
        assertEquals(1L, call("round", 0.5));
        assertEquals(3L, call("round", 2.5));
        assertEquals(2L, call("round", 1.5));
        // Banker's rounding gives -2 and away-from-zero gives -2; the implementation gives -1.
        assertEquals(-1L, call("round", -1.5));
        assertEquals(-2L, call("round", -2.5));
        assertEquals(0L, call("round", -0.5));
    }

    /**
     * A named consequence of the value being a binary double rather than an exact decimal:
     * {@code 1.005} is stored a little below the decimal it names, so no tie is reached and the
     * result is {@code 1.0}. The spec states this and points at exact decimal arithmetic as the
     * remedy. This test fails if that behaviour is ever silently "fixed" without changing the
     * numeric model, which would be a different language.
     */
    @Test
    void aDecimalThatABinaryFloatCannotHoldRoundsByItsStoredValue() {
        assertEquals(1.0, number(call("round", 1.005, 2L)), 1e-12);
        assertEquals(0.13, number(call("round", 0.125, 2L)), 1e-12);
    }

    // --- registration ------------------------------------------------------------------------

    @Test
    void everyNewNameIsRegistered() {
        Set<String> builtins = host().builtins().keySet();
        for (String name : List.of("mean", "median", "variance", "stdev", "mode", "percentile",
                "uuid", "is_uuid", "heap_push", "heap_pop", "bisect_left", "bisect_right")) {
            assertTrue(builtins.contains(name), name + " is not registered");
        }
    }
}
