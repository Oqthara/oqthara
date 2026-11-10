package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code data} evolution (ADR-0028). Alpha 3 semantics are pinned here so that adding the
 * {@code immutable} modifier cannot quietly change a program that does not use it.
 */
class DataTest {
    private String run(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    private String failure(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(1, result.code(), "expected a program error, got: " + result.stdout());
        return result.stderr();
    }

    // --- Alpha 3 behaviour that must not change -------------------------------------------

    @Test
    void constructsWithDefaultsAndPrintsInDeclarationOrder() {
        assertEquals("Point(x: 1, y: 5)\n", run("""
                data Point(x, y = 5)
                print Point(1)
                """));
    }

    @Test
    void equalityIsStructural() {
        assertEquals("true\nfalse\n", run("""
                data Point(x, y)
                print Point(1, 2) == Point(1, 2)
                print Point(1, 2) == Point(1, 3)
                """));
    }

    @Test
    void mutableDataStillAcceptsFieldWrites() {
        assertEquals("Point(x: 9, y: 2)\n", run("""
                data Point(x, y)
                p = Point(1, 2)
                p.x = 9
                print p
                """));
    }

    @Test
    void defaultsAreEvaluatedPerConstruction() {
        assertEquals("[]\n", run("""
                data Bag(items = [])
                a = Bag()
                b = Bag()
                a.items.push(1)
                print b.items
                """));
    }

    @Test
    void mutableDataIsStillRefusedAsAKey() {
        assertTrue(failure("""
                data Point(x, y)
                m = {Point(1, 2): "a"}
                """).contains("cannot be used as a map key"));
    }

    @Test
    void mutableDataIsStillRefusedAsASetMember() {
        assertTrue(failure("""
                data Point(x, y)
                print set([Point(1, 2)])
                """).contains("cannot be used as a set member"));
    }

    // --- the immutable modifier -------------------------------------------------------------

    @Test
    void immutableDataCanBeAMapKey() {
        assertEquals("a\n", run("""
                data Point(x, y) immutable
                m = {Point(1, 2): "a"}
                print m[Point(1, 2)]
                """));
    }

    @Test
    void immutableDataCollapsesInASet() {
        assertEquals("1\n", run("""
                data Point(x, y) immutable
                print length(set([Point(1, 2), Point(1, 2)]))
                """));
    }

    @Test
    void immutableDataRefusesFieldWrites() {
        assertTrue(failure("""
                data Point(x, y) immutable
                p = Point(1, 2)
                p.x = 9
                """).contains("Point is immutable, so field 'x' cannot be changed"));
    }

    @Test
    void theRefusalNamesTheAssignmentNotTheConstruction() {
        String stderr = failure("""
                data Point(x, y) immutable
                p = Point(1, 2)
                p.x = 9
                """);
        assertTrue(stderr.contains("3:2"), "expected the write position, got: " + stderr);
    }

    @Test
    void theRefusalShowsHowToRebuildTheValue() {
        String stderr = failure("""
                data Point(x, y) immutable
                p = Point(1, 2)
                p.x = 9
                """);
        assertTrue(stderr.contains("Build a new value instead: Point(x: ..., y: 2)"),
                "expected a rebuild hint, got: " + stderr);
    }

    @Test
    void aRebuiltValueIsWhatTheHintSuggests() {
        assertEquals("Point(x: 9, y: 2)\n", run("""
                data Point(x, y) immutable
                p = Point(1, 2)
                q = Point(9, p.y)
                print q
                """));
    }

    @Test
    void immutableDataStillRendersAndEquatesLikeMutableData() {
        assertEquals("Point(x: 1, y: 2)\ntrue\n", run("""
                data Point(x, y) immutable
                print Point(1, 2)
                print Point(1, 2) == Point(1, 2)
                """));
    }

    @Test
    void immutableIsContextualNotReserved() {
        assertEquals("7\n", run("""
                immutable = 7
                print immutable
                """));
    }

    @Test
    void immutableHoldsAMutableValueWithoutFreezingIt() {
        assertEquals("[1]\n", run("""
                data Box(items) immutable
                b = Box([])
                b.items.push(1)
                print b.items
                """));
    }

    @Test
    void immutableWithDefaultsUsesTheDefault() {
        assertEquals("Point(x: 1, y: 5)\n", run("""
                data Point(x, y = 5) immutable
                print Point(1)
                """));
    }

    @Test
    void aMissingFieldIsReportedBeforeImmutability() {
        assertTrue(failure("""
                data Point(x, y) immutable
                p = Point(1, 2)
                p.nope = 9
                """).contains("has no field named 'nope'"));
    }
}
