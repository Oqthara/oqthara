package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the boundary where Oqthara values cross into real Java objects.
 *
 * <p>The collection families are not converted by the bridge. They already are Java
 * collections: {@code OqTuple} extends {@code AbstractList}, {@code OqFrozenSet} extends
 * {@code AbstractSet} and {@code OqMap} extends {@code AbstractMap}, so a parameter typed
 * {@code Collection} accepts them unchanged and no copy is made. {@code list} is a plain
 * {@code ArrayList}. {@code range} is the exception and the tests below pin that down
 * rather than leaving it as folklore.
 */
class JavaInteropTest {
    private String ok(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    private void fails(String source, String fragment) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(1, result.code(), "expected failure, got: " + result.stdout());
        assertTrue(result.stderr().contains(fragment),
                "expected '" + fragment + "' in: " + result.stderr());
    }

    @Test
    void everyCollectionFamilyIsAcceptedAsAJavaCollection() {
        assertEquals("""
                2
                2
                2
                2
                1
                """, ok("""
                use java.util.ArrayList
                use java.util.LinkedHashSet
                use java.util.LinkedHashMap
                print ArrayList([1, 2]).size()
                print ArrayList((1, 2)).size()
                print ArrayList(frozenset([1, 2])).size()
                print LinkedHashSet([1, 1, 2]).size()
                print LinkedHashMap({"a": 1}).size()
                """));
    }

    @Test
    void aRangeIsNotAJavaCollectionAndSaysSo() {
        // OqRange extends nothing, so it cannot satisfy a Collection parameter. The
        // failure is a plain arity mismatch, which is the honest message: the constructor
        // was never offered an argument it could accept.
        fails("""
                use java.util.ArrayList
                print ArrayList(1 to 3).size()
                """, "cannot create ArrayList with 1 argument");
    }

    @Test
    void aMaterializedRangeCrossesTheBoundary() {
        // The workaround is explicit, which is the right shape: turning a range into a
        // list is a real allocation and the language should not do it behind a user's back.
        assertEquals("3\n", ok("""
                use java.util.ArrayList
                xs = []
                for i in 1 to 3
                  xs.push(i)
                end
                print ArrayList(xs).size()
                """));
    }

    @Test
    void javaStringsConvertToTextAndBack() {
        assertEquals("abc\n3\n", ok("""
                use java.lang.String
                s = String("abc")
                print s
                print s.length()
                """));
    }

    @Test
    void aJavaMapIsReadableThroughTheBridge() {
        assertEquals("""
                1
                1
                """, ok("""
                use java.util.LinkedHashMap
                m = LinkedHashMap()
                m.put("a", 1)
                print m.get("a")
                print m.size()
                """));
    }

    @Test
    void aDocumentedExampleFromTheSpecStillWorks() {
        // docs/spec/language.md, "Interoperability with Java" — kept here so the published
        // example cannot rot without a test failing.
        assertEquals("""
                2026-01-31
                1
                """, ok("""
                use java.time.LocalDate
                use java.util.ArrayList

                today = LocalDate(2026, 1, 1)
                print today.plusDays(30)

                list = ArrayList()
                list.add("a")
                print list.size()
                """));
    }
}
