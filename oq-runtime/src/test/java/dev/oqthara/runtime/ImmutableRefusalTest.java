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

        OqError error = Errors.refusal(refusal, span);

        assertTrue(error.getMessage().startsWith("cannot change an unchangeable collection"),
                "got: " + error.getMessage());
        assertFalse(error.getMessage().contains("internal error"));
        assertTrue(error.hint() != null && error.hint().contains("List.of"),
                "the hint should name the usual cause, got: " + error.hint());
    }

    @Test
    void aTupleKeepsItsOwnWording() {
        OqError error = Errors.refusal(
                new UnsupportedOperationException(OqTuple.IMMUTABLE), span);

        assertEquals(OqTuple.IMMUTABLE, error.getMessage());
        assertTrue(error.hint().contains("Build a new tuple"));
    }

    @Test
    void aRefusalWithNoMessageStillReadsAsASentence() {
        OqError error = Errors.refusal(new UnsupportedOperationException(), span);

        assertEquals("cannot change an unchangeable collection: the operation is not supported",
                error.getMessage());
    }

    @Test
    void aHeadlessGuiIsNotReportedAsAnUnchangeableCollection() {
        OqError error = Errors.refusal(
                new java.awt.HeadlessException("No X11 DISPLAY variable was set"), span);

        assertEquals("HeadlessException: No X11 DISPLAY variable was set", error.getMessage());
        assertFalse(error.getMessage().contains("unchangeable collection"),
                "a GUI with no display has nothing to do with an unchangeable collection");
        assertFalse(error.hint() != null && error.hint().contains("List.of"),
                "the List.of hint would send a reader looking in the wrong place");
    }

    @Test
    void aReadOnlyBufferIsNotReportedAsAnUnchangeableCollection() {
        OqError error = Errors.refusal(new java.nio.ReadOnlyBufferException(), span);

        assertEquals("ReadOnlyBufferException", error.getMessage());
        assertFalse(error.getMessage().contains("unchangeable collection"),
                "a read-only buffer is not a collection");
    }

    @Test
    void aBareRefusalStaysACollectionRefusal() {
        OqError error = Errors.refusal(
                new UnsupportedOperationException("the operation is not supported"), span);

        assertEquals("cannot change an unchangeable collection: the operation is not supported",
                error.getMessage());
        assertTrue(error.hint() != null && error.hint().contains("List.of"),
                "an UnsupportedOperationException that is not one of the known impostors stays a "
                        + "collection refusal, hint included");
    }
}
