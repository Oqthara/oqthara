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
 * Keyed sorting: {@code sort_by} and {@code list_sort}, from ADR-0060 and ADR-0067.
 *
 * <p>The reason {@code list_sort} exists was once stated as a test rather than a comment:
 * {@code sort_by} evaluated its key once per comparison, so a key with side effects consumed a
 * different value at every comparison and the sort could return an order unrelated to the input.
 * ADR-0067 moved {@code sort_by} onto the same decorate path, so both builtins now cache one key
 * per item and the old contrast is gone.
 *
 * <p>{@link #sortByCallsItsKeyOncePerItem()} and {@link #theKeyIsCalledOncePerItem()} assert the
 * same contract for the two names; they are kept separate because the two builtins have
 * separate argument rules and a regression in one must not be masked by the other.
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
     * The defect this test used to pin, now asserted as fixed (ADR-0067).
     *
     * <p>Before ADR-0067 the key was called on every comparison, so a counter key on
     * {@code [10, 20, 30]} returned {@code [30, 20, 10]} — the reverse of the input, decided by
     * evaluation order. The assertion is the same one, so the test still bites if the key is
     * moved back onto the comparison path. It is kept separate from
     * {@link #aStatefulKeyGetsTheSameOrderItWouldHaveGotFromOneCallPerItem()} because the two
     * builtins have separate argument rules, and a regression in one must not be masked by the
     * other.
     */
    @Test
    void sortByCallsItsKeyOncePerItem() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> result = asList(call("sort_by", list(10L, 20L, 30L), countingKey(calls)));
        assertEquals(list(10L, 20L, 30L), result);
        assertEquals(3, calls.get(), "the key must be called once per item, in input order");
    }

    @Test
    void aKeySelectsTheOrderRatherThanTheValue() {
        List<Object> result = asList(call("list_sort", list("bbb", "a", "cc"),
                key((callHost, args, span) -> (long) ((String) args.get(0)).length())));
        assertEquals(list("a", "cc", "bbb"), result);
    }

    /** The same edge cases through {@code sort_by}, which shares the decorate path since ADR-0067. */
    @Test
    void sortByKeepsEmptyAndSingleItemLists() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(list(), asList(call("sort_by", list(), countingKey(calls))));
        assertEquals(0, calls.get(), "an empty list needs no key calls");
        assertEquals(list(7L), asList(call("sort_by", list(7L), countingKey(calls))));
        assertEquals(1, calls.get(), "a single item needs exactly one key call");
    }

    @Test
    void sortByKeepsDuplicatesAndEqualKeyOrder() {
        assertEquals(list(1L, 2L, 2L),
                asList(call("sort_by", list(2L, 1L, 2L), key((h, a, s) -> a.get(0)))));
        List<Object> first = list("first");
        List<Object> second = list("second");
        List<Object> third = list("third");
        assertEquals(list(first, second, third),
                asList(call("sort_by", list(first, second, third), key((h, a, s) -> 0L))));
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

    /**
     * A Java functional value is a function, so it must be usable as one.
     *
     * <p>This builtin used to coerce its argument with {@code instanceof OqCallable}, which a
     * {@code java.util.function.Function} is not. The value was callable — {@code
     * Function.identity()(3)} answered {@code 3} — so the refusal denied something the
     * language had just allowed, and {@code map}/{@code filter} accepted the same value
     * because they pass it to the host unresolved. The arity check is what keeps this from
     * being a free pass: a two-argument comparator cannot be used as a one-argument key.
     */
    @Test
    void aJavaFunctionalValueIsAcceptedAsAKey() {
        assertEquals(list(1L, 2L, 3L),
                asList(call("list_sort", list(3L, 1L, 2L),
                        java.util.function.Function.<Object>identity())));
    }

    @Test
    void aTwoArgumentComparatorIsRefusedAsAKeyWithItsArityNamed() {
        OqError error = assertThrows(OqError.class,
                () -> call("list_sort", list(3L, 1L, 2L),
                        java.util.Comparator.<Long>naturalOrder()));
        assertTrue(error.getMessage().contains("taking 1 argument"),
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
