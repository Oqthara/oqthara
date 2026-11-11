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
    void everyFamilyButRangeCrossesTheBoundaryOneWayOrAnother() {
        // `range` is the only family that cannot cross, and the two tests above pin that.
        // The rest either already are Java collections (`list`, `tuple`, `set`, `frozenset`)
        // or reach one through a documented conversion (`text` to a list, `map` to a map).
        assertEquals("""
                2
                2
                2
                2
                3
                1
                """, ok("""
                use java.util.ArrayList
                use java.util.LinkedHashMap

                print ArrayList([1, 2]).size()
                print ArrayList((1, 2)).size()
                print ArrayList(set([1, 1, 2])).size()
                print ArrayList(frozenset([1, 2])).size()
                print ArrayList("abc".to_list()).size()
                print LinkedHashMap({"a": 1}).size()
                """));
    }

    @Test
    void aTextIsNotAJavaCollectionUntilItIsMaterialized() {
        // A text is a Java String, which is not a Collection, so handing it straight to a
        // Collection parameter is refused rather than silently coerced.
        fails("""
                use java.util.ArrayList
                print ArrayList("abc").size()
                """, "cannot create ArrayList with 1 argument");
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

    @Test
    void aJavaMethodThatWantsAConsumerAcceptsAFunction() {
        assertEquals("""
                1
                2
                3
                """, ok("""
                use java.util.ArrayList

                xs = ArrayList([1, 2, 3])
                xs.forEach(fun(x)
                    print x
                end)
                """));
    }

    @Test
    void aJavaMethodThatWantsAComparatorAcceptsAFunction() {
        assertEquals("[1, 2, 3]\n", ok("""
                use java.util.Collections

                xs = [3, 1, 2]
                Collections.sort(xs, (a, b) -> a - b)
                print xs
                """));
    }

    @Test
    void aCallbackCannotStandInForAFunctionalInterfaceItDoesNotFit() {
        // Two parameters cannot be a Consumer. The method is not missing, so the message must
        // name the interface and the arity rather than claim forEach does not exist.
        fails("""
                xs = [1, 2, 3]
                xs.forEach(fun(a, b)
                    print a
                end)
                """, "wants a Consumer, which takes 1 argument");
    }

    @Test
    void aCallbackReturnsAValueTheInterfaceCannotHold() {
        // removeIf wants a boolean. Returning text is a fault in the program, and letting the
        // proxy hand Java a null would move the failure somewhere far less legible.
        fails("""
                xs = [1, 2, 3]
                xs.removeIf(fun(x)
                    print x
                end)
                """, "returned nothing, which boolean cannot hold");
    }

    @Test
    void aFunctionReturnedByJavaStaysCallable() {
        // Comparator.naturalOrder() answers a package-private Comparators$NaturalOrderComparator.
        // Reflection reports that class's accessibility rather than the interface's, so the call
        // was refused with "cannot access a member of class ... with modifiers public" even
        // though the same call compiles in Java.
        assertEquals("-1\n1\n", ok("""
                use java.util.Comparator

                ascending = Comparator.naturalOrder()
                descending = Comparator.reverseOrder()
                print ascending.compare(1, 2)
                print descending.compare(1, 2)
                """));
    }

    @Test
    void aJavaObjectIsNotDescribedAsUncallable() {
        // The value's methods do work, so saying it "cannot be called" contradicts the line
        // above it. The help names the real distinction: callable by method, not a fun.
        fails("""
                use java.util.Comparator

                xs = [1, 2]
                print map(xs, Comparator.naturalOrder())
                """, "a Java object: call its methods with '.', but it is not a fun");
    }

    @Test
    void aBigDecimalReturnedByJavaKeepsAllOfItsDigits() {
        // A double has 53 bits of mantissa. This sum needs 120, and the digits below the
        // decimal point are exactly what BigDecimal was chosen for, so a narrowing conversion
        // would answer a different number without saying so (ADR-0045).
        assertEquals("123456789012345678901234567890.123456790\njava BigDecimal\n",
                ok("""
                        use java.math.BigDecimal

                        a = BigDecimal("123456789012345678901234567890.123456789")
                        sum = a.add(BigDecimal("0.000000001"))
                        print sum
                        print type_of(sum)
                        """));
    }

    @Test
    void aBigDecimalReturnedByJavaStillOffersItsOwnMethods() {
        assertEquals("0.30\n1\n", ok("""
                use java.math.BigDecimal

                sum = BigDecimal("0.1").add(BigDecimal("0.2"))
                print sum.setScale(2).toPlainString()
                print sum.scale()
                """));
    }

    @Test
    void aBigIntegerTooWideForANumberStaysExact() {
        assertEquals("123456789012345678901234567891\njava BigInteger\n", ok("""
                use java.math.BigInteger

                n = BigInteger("123456789012345678901234567890")
                print n.add(BigInteger("1"))
                print type_of(n.add(BigInteger("1")))
                """));
    }

    @Test
    void aBigIntegerThatFitsANumberIsStillANumber() {
        assertEquals("43\nnumber\n", ok("""
                use java.math.BigInteger

                n = BigInteger("42").add(BigInteger("1"))
                print n
                print type_of(n)
                """));
    }

    @Test
    void aFunctionStoredAsAValueIsNotTurnedIntoACallback() {
        // add(Object) takes any reference, so the function already fits and no proxy is made.
        assertEquals("1\n", ok("""
                use java.util.ArrayList

                xs = ArrayList()
                xs.add(fun(x)
                    print x
                end)
                print xs.size()
                """));
    }
}
