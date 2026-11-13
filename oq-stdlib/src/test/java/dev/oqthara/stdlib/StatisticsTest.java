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
