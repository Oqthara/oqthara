package dev.oqthara.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Java contracts these collections expose at the interop boundary.
 *
 * <p>{@code OqMap} and the two set types implement {@code java.util.Map} and
 * {@code java.util.Set}, so they must obey the same two rules the JDK's own collections do.
 * Both were broken in the same way: the code was correct in Oqthara's terms and wrong in
 * Java's, because Oqthara widens {@code 1} and {@code 1.0} into one value while Java's
 * {@code hashCode} does not, and because a map may hold {@code nothing} as an ordinary value.
 */
class CollectionContractTest {

    /**
     * {@code Set.remove} must report whether the set changed, and a map entry whose value is
     * {@code nothing} is still an entry. The backing map stores {@code nothing} as Java null,
     * so a returned null used to be read as "absent" after the key had already been removed.
     */
    @Test
    void removingAKeyWhoseValueIsNothingStillReportsAChange() {
        OqMap map = new OqMap();
        map.put("x", null);
        assertEquals(1, map.size());

        assertTrue(map.keySet().remove("x"), "the key was present and was removed");
        assertEquals(0, map.size(), "and the map is now empty");
    }

    @Test
    void removingAKeyThatIsAbsentReportsNoChange() {
        OqMap map = new OqMap();
        map.put("x", 1L);
        assertFalse(map.keySet().remove("y"));
        assertEquals(1, map.size(), "the map is untouched");
    }

    @Test
    void removingAKeyWhoseValueIsPresentReportsAChange() {
        OqMap map = new OqMap();
        map.put("x", 1L);
        assertTrue(map.keySet().remove("x"));
        assertEquals(0, map.size());
    }

    /**
     * Equal collections must hash alike. The inherited hash summed each element's <em>Java</em>
     * hash, so {@code set([1])} and {@code set([1.0])} hashed differently while the language
     * called them equal; putting both in a Java hash-based container would have kept two.
     */
    @Test
    void setsTheLanguageCallsEqualHashAlike() {
        OqSet whole = new OqSet();
        whole.add(1L);
        OqSet decimal = new OqSet();
        decimal.add(1.0);

        assertTrue(Values.equal(whole, decimal), "1 and 1.0 are the same set member");
        assertEquals(whole.hashCode(), decimal.hashCode());
    }

    @Test
    void frozenSetsTheLanguageCallsEqualHashAlike() {
        OqFrozenSet whole = new OqFrozenSet(new OqSet(java.util.List.of(1L)));
        OqFrozenSet decimal = new OqFrozenSet(new OqSet(java.util.List.of(1.0)));

        assertTrue(Values.equal(whole, decimal));
        assertEquals(whole.hashCode(), decimal.hashCode());
    }

    @Test
    void mapsTheLanguageCallsEqualHashAlike() {
        Map<Object, Object> whole = new LinkedHashMap<>();
        whole.put(1L, "x");
        Map<Object, Object> decimal = new LinkedHashMap<>();
        decimal.put(1.0, "x");

        OqMap a = new OqMap();
        a.putAll(whole);
        OqMap b = new OqMap();
        b.putAll(decimal);

        assertTrue(Values.equal(a, b), "the keys are equal by the language's rule");
        assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * The hash has to stay a function of the elements. A set that hashed every set to a
     * constant would pass the tests above and be useless.
     */
    @Test
    void setsWithDifferentMembersHashDifferently() {
        OqSet one = new OqSet();
        one.add(1L);
        OqSet two = new OqSet();
        two.add(2L);
        assertNotEquals(one.hashCode(), two.hashCode());
    }

    /**
     * Hashing is order-independent, because set equality ignores insertion order. Two sets
     * built in opposite orders must agree, exactly as the JDK's own sets do.
     */
    @Test
    void setHashingIgnoresInsertionOrder() {
        OqSet forward = new OqSet();
        forward.add(1L);
        forward.add(2L);
        OqSet backward = new OqSet();
        backward.add(2L);
        backward.add(1L);
        assertEquals(forward.hashCode(), backward.hashCode());
        assertTrue(Values.equal(forward, backward));
    }
}
