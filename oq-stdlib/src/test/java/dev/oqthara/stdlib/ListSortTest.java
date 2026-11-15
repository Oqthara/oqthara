package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code list_sort} from ADR-0049.
 *
 * <p>The reason this builtin exists is stated as a test rather than a comment: {@code sort_by}
 * evaluates its key once per comparison, so a key with side effects consumes a different value
 * at every comparison and the sort can return an order unrelated to the input.
 * {@link #sortByStillEvaluatesItsKeyPerComparison()} pins that wrong answer, and
 * {@link #aStatefulKeyGetsTheSameOrderItWouldHaveGotFromOneCallPerItem()} pins the right one
 * from the same key, so the difference between the two builtins is reproducible from a test.
 *
 * <p>{@link #theKeyIsCalledOncePerItem()} counts the calls rather than asserting a symptom,
 * because the count is the contract. Ten items must cost ten calls whatever the input order is;
 * an implementation that happens to produce correct output from a stateful key by luck would
 * still fail here once it made eleven calls.
 */
class ListSortTest {

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

    private static List<Object> asList(Object value) {
        assertTrue(value instanceof List, "expected a list, got " + value);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) value;
        return result;
    }

    /** Wraps a one-argument key body as a callable the interpreter will accept. */
    private static OqCallable key(NativeFunction.Body body) {
        return NativeFunction.of("key", 1, body);
    }

    /** A key returning its own call count: every item gets a distinct, position-derived key. */
    private static OqCallable countingKey(AtomicInteger calls) {
        return key((callHost, args, span) -> (long) calls.incrementAndGet());
    }

    // --- ordering ----------------------------------------------------------------------------

    @Test
    void sortsNumbersAscending() {
        assertEquals(list(1L, 2L, 3L), asList(call("list_sort", list(3L, 1L, 2L))));
    }

    @Test
    void sortsTextByCodePoint() {
        assertEquals(list("a", "b"), asList(call("list_sort", list("b", "a"))));
    }

    @Test
    void anEmptyListStaysEmpty() {
        assertEquals(list(), asList(call("list_sort", list())));
    }

    @Test
    void aSingleItemIsAlreadySorted() {
        assertEquals(list(7L), asList(call("list_sort", list(7L))));
    }

    @Test
    void duplicatesAreKept() {
        assertEquals(list(1L, 2L, 2L), asList(call("list_sort", list(2L, 1L, 2L))));
    }

    @Test
    void theArgumentIsNotModified() {
        List<Object> original = list(3L, 1L, 2L);
        List<Object> sorted = asList(call("list_sort", original));
        assertEquals(list(3L, 1L, 2L), original);
        assertEquals(list(1L, 2L, 3L), sorted);
    }

    @Test
    void acceptsAnyIterableNotJustAList() {
        List<Object> sorted = asList(call("list_sort", new java.util.LinkedHashSet<>(
                list(3L, 1L, 2L))));
        assertEquals(list(1L, 2L, 3L), sorted);
    }

    // --- the key contract --------------------------------------------------------------------

    @Test
    void theKeyIsCalledOncePerItem() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> items = list(5L, 3L, 8L, 1L, 9L, 2L, 7L, 4L, 6L, 0L);
        asList(call("list_sort", items, countingKey(calls)));
        assertEquals(10, calls.get());
    }

    @Test
    void theKeyIsCalledOncePerItemWhateverTheInputOrder() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> sortedInput = list(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        asList(call("list_sort", sortedInput, countingKey(calls)));
        assertEquals(10, calls.get());
    }

    /**
     * The defect that justifies a second sorting builtin.
     *
     * <p>A key that returns its own call count is the smallest reproducible case: every item
     * gets a distinct key equal to its position in the evaluation order, so the sorted order is
     * decided entirely by evaluation order. Calling the key once per item in input order makes
     * the result the input order; calling it once per comparison scrambles it.
     */
    @Test
    void aStatefulKeyGetsTheSameOrderItWouldHaveGotFromOneCallPerItem() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> result = asList(call("list_sort", list(10L, 20L, 30L), countingKey(calls)));
        assertEquals(list(10L, 20L, 30L), result);
    }

    /**
     * The same stateful key through {@code sort_by}, pinned to today's wrong answer.
     *
     * <p>This is not an endorsement: it records that {@code sort_by} still evaluates its key per
     * comparison, so the reason for {@code list_sort} is reproducible from a test rather than
     * only from a comment. If {@code sort_by} is later moved onto the same decorate path, this
     * test must be updated with it, because the two rows would finally agree.
     */
    @Test
    void sortByStillEvaluatesItsKeyPerComparison() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> result = asList(call("sort_by", list(10L, 20L, 30L), countingKey(calls)));
        assertEquals(list(30L, 20L, 10L), result);
        assertTrue(calls.get() > 3, "sort_by now caches keys, which is the fix this pin records; "
                + "delete or update this test together with that change. It called the key "
                + calls.get() + " times.");
    }

    @Test
    void aKeySelectsTheOrderRatherThanTheValue() {
        List<Object> result = asList(call("list_sort", list("bbb", "a", "cc"),
                key((callHost, args, span) -> (long) ((String) args.get(0)).length())));
        assertEquals(list("a", "cc", "bbb"), result);
    }

    @Test
    void equalKeysPreserveInputOrder() {
        List<Object> first = list("first");
        List<Object> second = list("second");
        List<Object> third = list("third");
        List<Object> result = asList(call("list_sort", list(first, second, third),
                key((callHost, args, span) -> 0L)));
        assertEquals(list(first, second, third), result);
    }

    @Test
    void aNothingKeyIsTreatedAsAbsent() {
        assertEquals(list(1L, 2L, 3L), asList(call("list_sort", list(3L, 1L, 2L), null)));
    }

    /**
     * {@code nothing} is Java {@code null}, and the keyed path builds the key's argument list
     * itself. Building it with {@link List#of} would reject the null and abort the process with
     * an internal error, which no program can catch. Equal keys mean the items are never
     * compared, so this must simply return them.
     */
    @Test
    void aListHoldingNothingIsSortedRatherThanCrashing() {
        List<Object> result = asList(call("list_sort", list(3L, null, 1L),
                key((h, a, s2) -> 0L)));
        assertEquals(list(3L, null, 1L), result);
    }

    /**
     * A key that reads {@code nothing} must be given {@code nothing}, not a stand-in; and if the
     * keys it returns are orderable the list sorts normally around it.
     */
    @Test
    void aNothingItemIsPassedToTheKeyAndOrdered() {
        List<Object> result = asList(call("list_sort", list(3L, null, 1L),
                key((callHost, args, span) -> args.get(0) == null ? 0L : (Long) args.get(0))));
        assertEquals(list(null, 1L, 3L), result);
    }

    // --- failures ----------------------------------------------------------------------------

    @Test
    void comparingAnythingWithTextIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("list_sort", list(1L, "a")));
        assertTrue(error.getMessage().contains("cannot compare text with number"),
                "unexpected message: " + error.getMessage());
    }

    @Test
    void aNonCallableKeyIsRefusedByName() {
        OqError error = assertThrows(OqError.class,
                () -> call("list_sort", list(1L, 2L), 3L));
        assertTrue(error.getMessage().contains("list_sort() needs a function as argument 2"),
                "unexpected message: " + error.getMessage());
    }

    @Test
    void aRaisingKeyAbortsTheSort() {
        OqCallable exploding = key((callHost, args, span) -> {
            if (args.get(0).equals(2L)) throw new OqError("boom", span);
            return args.get(0);
        });
        OqError error = assertThrows(OqError.class,
                () -> call("list_sort", list(1L, 2L, 3L), exploding));
        assertEquals("boom", error.getMessage());
    }

    @Test
    void theWrongArityIsRefused() {
        assertThrows(OqError.class, () -> call("list_sort"));
        assertThrows(OqError.class, () -> call("list_sort", list(1L), list(2L), list(3L)));
    }
}
