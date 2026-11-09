package dev.oqthara.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A compiled program calls methods through {@link Methods}, not through the interpreter, so the
 * same unchangeable value has to reach the same verdict on both backends. These cases pin the
 * shared classifier directly, which is what keeps a compiled jar from reporting an Oqthara bug
 * for something the program did.
 */
class ImmutableRefusalTest {
    private final Span span = Span.synthetic();

    @Test
    void aJdkRefusalBecomesAProgramErrorNotABug() {
        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class,
                () -> List.of(1L, 2L).add(3L));

        OqError error = Errors.immutableRefusal(refusal, span);

        assertTrue(error.getMessage().startsWith("cannot change an unchangeable collection"),
                "got: " + error.getMessage());
        assertFalse(error.getMessage().contains("internal error"));
        assertTrue(error.hint() != null && error.hint().contains("List.of"),
                "the hint should name the usual cause, got: " + error.hint());
    }

    @Test
    void aTupleKeepsItsOwnWording() {
        OqError error = Errors.immutableRefusal(
                new UnsupportedOperationException(OqTuple.IMMUTABLE), span);

        assertEquals(OqTuple.IMMUTABLE, error.getMessage());
        assertTrue(error.hint().contains("Build a new tuple"));
    }

    @Test
    void aRefusalWithNoMessageStillReadsAsASentence() {
        OqError error = Errors.immutableRefusal(new UnsupportedOperationException(), span);

        assertEquals("cannot change an unchangeable collection: the operation is not supported",
                error.getMessage());
    }

    @Test
    void aViewOfATupleStillSaysItIsATuple() {
        // The tuple answers its own mutators, but a view such as subList comes from the backing
        // list. A plain unmodifiableList throws with no message there, which used to blame "an
        // unchangeable collection" for something the program named as a tuple.
        OqTuple tuple = OqTuple.of(List.of(1L, 2L, 3L));

        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class,
                () -> tuple.subList(0, 2).add(9L));

        assertEquals(OqTuple.IMMUTABLE, refusal.getMessage());
        assertEquals(OqTuple.IMMUTABLE, Errors.immutableRefusal(refusal, span).getMessage());
    }

    @Test
    void aTupleStillHoldsNothing() {
        // `nothing` is a null, so the backing list must not reject nulls the way List.copyOf does.
        OqTuple tuple = OqTuple.of(java.util.Arrays.asList(1L, null));

        assertEquals(2, tuple.size());
        assertEquals(null, tuple.get(1));
    }

}
