package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqTuple;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TupleMethodRefusalTest {
    private final MethodResolverImpl resolver = new MethodResolverImpl();
    private final Span span = Span.synthetic();
    private final OqTuple tuple = new OqTuple(Collections.singletonList(1L));

    @Test
    void everyMutatorIsRefusedAsAProgramError() {
        List<String> mutators = List.of(
                "push", "add", "pop", "insert", "remove", "remove_value", "clear", "sort",
                "reverse", "sort_by", "shuffle", "fill",
                "set", "addAll", "removeAll", "retainAll", "removeIf", "replaceAll");
        for (String name : mutators) {
            OqCallable refusal = resolver.resolve(tuple, name, span);
            assertNotNull(refusal, name + " was not intercepted");
            OqError error = assertThrows(OqError.class,
                    () -> refusal.call(null, List.of(), span), name + " did not refuse");
            assertEquals("a tuple is immutable", error.getMessage(), name);
        }
    }

    @Test
    void aViewOfATupleIsRefusedToo() {
        // A view such as subList is a plain java.util.List, so the resolver's guard never sees
        // it and the JDK's own refusal used to surface as `internal error:
        // UnsupportedOperationException`. Every view shape must report the same program error.
        OqTuple three = new OqTuple(List.of(1L, 2L, 3L));
        List<List<Object>> views = List.of(three.subList(0, 1), three.subList(0, 2), three);
        for (List<Object> view : views) {
            UnsupportedOperationException raw = assertThrows(
                    UnsupportedOperationException.class, () -> view.add(9L));
            assertEquals(OqTuple.IMMUTABLE, raw.getMessage());
        }
    }

    @Test
    void readOnlyMethodsStillResolve() {
        OqCallable length = resolver.resolve(tuple, "length", span);
        assertNotNull(length);
        assertEquals(1L, length.call(null, List.of(), span));
    }

    @Test
    void uniquePreservesTupleNess() {
        OqTuple repeated = new OqTuple(List.of(1L, 1L, 2L));
        OqCallable unique = resolver.resolve(repeated, "unique", span);
        assertNotNull(unique);
        Object result = unique.call(null, List.of(), span);
        assertTrue(result instanceof OqTuple, "unique() returned " + result.getClass().getName());
        assertEquals(2, ((OqTuple) result).size());
    }
}
