package dev.oqthara.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The invariant this class protects is the one ADR-0017 is about: if two values are equal
 * then their hashes are equal. Every case here is a pair that {@link Values#equal} calls
 * equal, so a container keyed by hash must be unable to tell them apart.
 */
class ValueTest {

    private static void assertHashAgrees(Object a, Object b) {
        assertTrue(Values.equal(a, b), "expected the pair to be equal: " + a + " and " + b);
        assertEquals(Value.of(a).hashCode(), Value.of(b).hashCode(),
                "equal values must hash alike: " + a + " and " + b);
        assertEquals(Value.of(a), Value.of(b), "boxed values must be equal");
    }

    @Test
    void wholeNumbersAndWholeDecimalsInterchange() {
        assertHashAgrees(1L, 1.0);
        assertHashAgrees(-7L, -7.0);
        assertHashAgrees(0L, 0.0);
        assertHashAgrees(0L, -0.0);
    }

    /**
     * Above 2^53 a decimal cannot name every whole number, so the pair
     * {@code 9007199254740993} and {@code 9007199254740992.0} is equal by the language's own
     * rule: widening the long loses the odd bit. Equal values must hash alike, or a set holds
     * both and a map loses the entry.
     */
    @Test
    void wholeNumbersThatWidenOntoADecimalHashAlike() {
        assertHashAgrees(9007199254740993L, 9007199254740992.0);
        assertHashAgrees(9007199254740995L, 9007199254740996.0);
        assertHashAgrees(-9007199254740993L, -9007199254740992.0);
    }

    @Test
    void aWidenedPairCollapsesInASetAndAMap() {
        assertEquals(1, new OqSet(List.of(9007199254740993L, 9007199254740992.0)).size());
        assertEquals(1, new OqSet(List.of(9007199254740992.0, 9007199254740993L)).size());
        Map<Object, Object> map = new java.util.HashMap<>();
        map.put(Value.of(9007199254740992.0), "x");
        assertEquals("x", map.get(Value.of(9007199254740993L)));
    }

    @Test
    void wholeNumbersAndDecimalsAgreeEverywhereTheLongFits() {
        assertHashAgrees(Long.MAX_VALUE, (double) Long.MAX_VALUE);
        assertHashAgrees(Long.MIN_VALUE, (double) Long.MIN_VALUE);
        assertHashAgrees(1L << 53, (double) (1L << 53));
        assertHashAgrees(-(1L << 53), -(double) (1L << 53));
    }

    @Test
    void decimalsThatDifferDoNotCollideByAccident() {
        assertNotEquals(Value.of(1.5), Value.of(1L));
        assertFalse(Values.equal(1.5, 2.0));
        // A decimal too large for a long cannot normalise onto an integral hash.
        assertFalse(Values.equal(1.0e300, 1L));
    }

    @Test
    void containersHashByContent() {
        assertHashAgrees(List.of(1L, 2L), List.of(1.0, 2.0));
        assertHashAgrees(new OqTuple(List.of(1L)), new OqTuple(List.of(1.0)));
        assertHashAgrees(Map.of(1L, "a"), Map.of(1.0, "a"));
        assertHashAgrees(new OqSet(List.of(1L)), new OqSet(List.of(1.0)));
    }

    @Test
    void nestingDoesNotChangeTheRule() {
        assertHashAgrees(List.of(List.of(1L)), List.of(List.of(1.0)));
        assertHashAgrees(Map.of("k", List.of(1L)), Map.of("k", List.of(1.0)));
    }

    @Test
    void aTupleNeverEqualsAList() {
        assertFalse(Values.equal(new OqTuple(List.of(1L)), List.of(1L)));
        assertNotEquals(Value.of(new OqTuple(List.of(1L))), Value.of(List.of(1L)));
    }

    @Test
    void setsCollapseEqualNumbersAndReportOneItem() {
        OqSet set = new OqSet();
        assertTrue(set.add(1L));
        assertFalse(set.add(1.0), "1.0 and 1 are the same value, so the second add changes nothing");
        assertEquals(1, set.size());
        assertTrue(set.contains(1L));
        assertTrue(set.contains(1.0));
    }

    @Test
    void mapsFindKeysWrittenWithTheOtherNumericType() {
        OqMap map = new OqMap();
        map.put(1L, "a");
        assertEquals("a", map.get(1.0));
        assertTrue(map.containsKey(1.0));
        map.put(1.0, "b");
        assertEquals(1, map.size(), "the second put replaced the first rather than adding a key");
        assertEquals("b", map.get(1L));
    }

    @Test
    void iterationHandsBackRawValuesNotBoxes() {
        OqSet set = new OqSet(List.of(1L, 1.0, 2L));
        assertEquals(2, set.size());
        for (Object item : set) {
            assertFalse(item instanceof Value, "a box escaped the container: " + item);
        }
        OqMap map = new OqMap();
        map.put(1L, "a");
        for (Object key : map.keySet()) {
            assertFalse(key instanceof Value, "a box escaped the map: " + key);
        }
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            assertFalse(entry.getKey() instanceof Value, "a box escaped the entry set");
        }
    }

    private static OqData data(String type, Object... namesAndValues) {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return new OqData(type, fields);
    }

    @Test
    void numbersWidenInsideDataFields() {
        assertHashAgrees(data("P", "x", 1L, "y", 2L), data("P", "x", 1.0, "y", 2L));
    }

    @Test
    void dataOfADifferentTypeIsNotEqual() {
        assertFalse(Values.equal(data("P", "x", 1L), data("Q", "x", 1L)));
    }

    @Test
    void dataFieldsCompareInOrderAndByValue() {
        assertFalse(Values.equal(data("P", "x", 1L, "y", 2L), data("P", "x", 2L, "y", 1L)));
        assertHashAgrees(data("P", "x", List.of(1L)), data("P", "x", List.of(1.0)));
    }

    @Test
    void setsCollapseEqualTupleValuesOfDifferentNumericTypes() {
        // The intent is that equivalent members collapse on load. This used `data`, which is now
        // refused as a member because its fields are mutable (ADR-0022); a tuple carries the
        // same intent and is immutable.
        assertEquals(1, new OqSet(List.of(new OqTuple(List.of(1L)), new OqTuple(List.of(1.0)))).size());
    }

    @Test
    void mutableViewsAreRefusedAsSetMembersAndMapKeys() {
        // Hashing is structural, so a mutable member would change hash after insertion and
        // become unreachable. ADR-0022 refuses it up front instead.
        assertThrows(Value.UnhashableValueException.class, () -> new OqSet(List.of(List.of(1L))));
        assertThrows(Value.UnhashableValueException.class, () -> new OqSet(List.of(new OqSet())));
        assertThrows(Value.UnhashableValueException.class, () -> new OqMap().put(List.of(1L), "a"));
        assertThrows(Value.UnhashableValueException.class, () -> new OqMap().put(data("P", "x", 1L), "a"));
    }

    @Test
    void aTupleHoldingAMutableViewIsRefusedBecauseItIsOnlyAsImmutableAsItsContents() {
        assertThrows(Value.UnhashableValueException.class,
                () -> new OqSet(List.of(new OqTuple(List.of(List.of(1L))))));
        assertThrows(Value.UnhashableValueException.class,
                () -> new OqMap().put(new OqTuple(List.of(data("P", "x", 1L))), "a"));
    }

    @Test
    void immutableViewsAreAcceptedAsSetMembersAndMapKeys() {
        OqMap map = new OqMap();
        assertNull(map.put(new OqTuple(List.of(1L)), "a"));
        assertEquals("a", map.get(new OqTuple(List.of(1.0))));
        assertEquals(1, new OqSet(List.of(new OqFrozenSet(List.of(1L)))).size());
    }

    @Test
    void aFrozenSetIsHashableOnlyWhenItsMembersAre() {
        assertEquals(1, new OqSet(List.of(new OqFrozenSet(List.of(1L)))).size());
        assertThrows(Value.UnhashableValueException.class,
                () -> new OqFrozenSet(List.of(List.of(1L))));
    }

    @Test
    void aSelfReferentialCollectionIsRejectedRatherThanOverflowing() {
        List<Object> cycle = new java.util.ArrayList<>();
        cycle.add(1L);
        cycle.add(cycle);
        assertThrows(Value.InvalidHashException.class, () -> Value.of(cycle).hashCode());
    }
}
