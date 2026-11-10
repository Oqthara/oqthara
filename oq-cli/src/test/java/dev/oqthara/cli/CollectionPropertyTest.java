package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property tests for the collection families that do not have one.
 *
 * <p>Each case builds a random collection in Java, writes the equivalent Oqthara source, and
 * compares what Oqthara reports against what Java computes over the same values. The Java
 * result is an independent oracle: it is not derived from Oqthara's own implementation, so a
 * shared mistake cannot hide by agreeing with itself.
 *
 * <p>The seed is fixed. A failure is therefore reproducible, and the message carries the
 * generated program so the offending input can be read directly.
 */
class CollectionPropertyTest {
    private static final long SEED = 20261109L;
    private static final int CASES = 60;

    private String run(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr() + "\nprogram:\n" + source);
        return result.stdout().strip();
    }

    /** A list literal in Oqthara syntax, with a space after each comma to match the printer. */
    private static String literal(List<Long> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }

    private static List<Long> randomValues(Random random, int maxLength) {
        int length = random.nextInt(maxLength + 1);
        List<Long> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            values.add((long) random.nextInt(21) - 10);
        }
        return values;
    }

    @Test
    void listLengthAgreesWithTheNumberOfValuesWritten() {
        Random random = new Random(SEED);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            String program = "print length(" + literal(values) + ")";
            assertEquals("" + values.size(), run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void listSumAgreesWithAddingTheSameValuesInJava() {
        Random random = new Random(SEED + 1);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            long expected = values.stream().mapToLong(Long::longValue).sum();
            String program = "print sum(" + literal(values) + ")";
            assertEquals("" + expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void sortedListAgreesWithJavaOrder() {
        Random random = new Random(SEED + 2);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            List<Long> expected = new ArrayList<>(values);
            expected.sort(Long::compare);
            String program = "print sorted(" + literal(values) + ")";
            assertEquals(literal(expected), run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void aSetHoldsEachDistinctValueOnce() {
        Random random = new Random(SEED + 3);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            int expected = new LinkedHashSet<>(values).size();
            String program = "print length(set(" + literal(values) + "))";
            assertEquals("" + expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void aFrozenSetHoldsWhatASetHolds() {
        Random random = new Random(SEED + 4);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            int expected = new LinkedHashSet<>(values).size();
            String program = "print length(frozenset(" + literal(values) + "))";
            assertEquals("" + expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void uniqueKeepsTheFirstOfEachValueInOrder() {
        Random random = new Random(SEED + 5);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            List<Long> expected = new ArrayList<>(new LinkedHashSet<>(values));
            String program = "print unique(" + literal(values) + ")";
            assertEquals(literal(expected), run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void aTupleReportsTheLengthOfWhatItWasBuiltFrom() {
        Random random = new Random(SEED + 6);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 12);
            if (values.isEmpty()) {
                // An empty `()` is not a tuple literal, so draw again rather than skipping --
                // a skipped case would silently shrink the sample without saying so.
                caseIndex--;
                continue;
            }
            // A tuple literal needs at least one element and a trailing comma to distinguish it
            // from a parenthesised expression.
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(values.get(i));
            }
            if (values.size() == 1) sb.append(',');
            String program = "print length(" + sb.append("))");
            assertEquals("" + values.size(), run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void aMapReportsTheLengthOfItsDistinctKeys() {
        Random random = new Random(SEED + 7);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            int pairs = random.nextInt(8);
            List<Long> keys = new ArrayList<>(pairs);
            for (int i = 0; i < pairs; i++) keys.add((long) random.nextInt(10));
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append("k").append(keys.get(i)).append("\": ").append(i);
            }
            sb.append('}');
            int expected = new LinkedHashSet<>(keys).size();
            String program = "print length(" + sb + ")";
            assertEquals("" + expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void textLengthAgreesWithTheNumberOfCharacters() {
        Random random = new Random(SEED + 8);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            int length = random.nextInt(13);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < length; i++) {
                text.append((char) ('a' + random.nextInt(6)));
            }
            String program = "print length(\"" + text + "\")";
            assertEquals("" + length, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void membershipAgreesWithWhetherJavaHoldsTheValue() {
        Random random = new Random(SEED + 9);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 10);
            long probe = random.nextInt(21) - 10;
            String expected = "" + values.contains(probe);
            String program = "print " + probe + " in " + literal(values);
            assertEquals(expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void setMembershipAgreesWithWhetherTheDistinctValuesHoldIt() {
        Random random = new Random(SEED + 10);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            List<Long> values = randomValues(random, 10);
            long probe = random.nextInt(21) - 10;
            String expected = "" + new TreeSet<>(values).contains(probe);
            String program = "print " + probe + " in set(" + literal(values) + ")";
            assertEquals(expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }

    @Test
    void textMembershipAgreesWithSubstring() {
        Random random = new Random(SEED + 11);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            int length = random.nextInt(13);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < length; i++) text.append((char) ('a' + random.nextInt(3)));
            String needle = "" + (char) ('a' + random.nextInt(4));
            String expected = "" + text.toString().contains(needle);
            String program = "print \"" + needle + "\" in \"" + text + "\"";
            assertEquals(expected, run(program), "case " + caseIndex + "\n" + program);
        }
    }
}
