package dev.oqthara.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuesTest {
    private final Span span = Span.synthetic();

    @Test
    void addsNumbers() {
        assertEquals(3L, Values.add(1L, 2L, span));
        assertEquals(3.5, Values.add(1L, 2.5, span));
    }

    @Test
    void concatenatesText() {
        assertEquals("ab", Values.add("a", "b", span));
        assertEquals("a1", Values.add("a", 1L, span));
    }

    @Test
    void dividesIntegersExactly() {
        assertEquals(2L, Values.divide(4L, 2L, span));
        assertEquals(2.5, Values.divide(5L, 2L, span));
    }

    @Test
    void comparesStrings() {
        assertTrue(Values.compare("a", "b", span) < 0);
    }

    @Test
    void indexesLists() {
        assertEquals(1L, Values.index(List.of(1L, 2L, 3L), 0L, span));
        assertEquals(3L, Values.index(List.of(1L, 2L, 3L), -1L, span));
    }

    @Test
    void indexesMaps() {
        assertEquals(1L, Values.index(Map.of("a", 1L), "a", span));
    }

    @Test
    void truthiness() {
        assertFalse(Values.truthy(null));
        assertFalse(Values.truthy(0L));
        assertFalse(Values.truthy(""));
        assertFalse(Values.truthy(List.of()));
        assertTrue(Values.truthy(1L));
        assertTrue(Values.truthy("x"));
    }

    @Test
    void formatsDecimals() {
        assertEquals("1.5", Values.formatDouble(1.5));
        assertEquals("2.0", Values.formatDouble(2.0));
        assertEquals("0.25", Values.formatDouble(0.25));
    }

    @Test
    void equalityAcrossNumbers() {
        assertTrue(Values.equal(1L, 1.0));
        assertTrue(Values.equal("a", "a"));
        assertFalse(Values.equal(1L, 2L));
    }

    @Test
    void reprOfNested() {
        assertEquals("[1, 2]", Values.repr(List.of(1L, 2L)));
        assertEquals("\"x\"", Values.repr("x"));
        assertEquals("nothing", Values.repr(null));
    }
}
