package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the curated corpus on every build. Jazzer replays it too, but keeping it as an
 * ordinary deterministic test means a regression is caught even when fuzzing is skipped.
 */
class CuratedCorpusTest {
    @Test
    void curatedInputsNeverCrash() throws IOException {
        List<String> cases = load();
        assertTrue(cases.size() >= 10, "expected a curated corpus, found " + cases.size());
        for (String source : cases) {
            try {
                Parser.parse("<corpus>", source);
            } catch (OqError expected) {
                assertNotNull(expected.getMessage(), "error without message for: " + source);
            } catch (RuntimeException e) {
                throw new AssertionError("crash on curated input: " + source, e);
            }
        }
    }

    @Test
    void deeplyNestedExpressionIsRejectedWithADiagnostic() {
        // Found by Jazzer: 390 levels of parentheses overflowed the JVM stack and crashed
        // check with a raw StackOverflowError instead of a diagnostic.
        for (int depth : new int[]{200, 390, 500, 2000}) {
            String source = "x = " + "(".repeat(depth) + "1" + ")".repeat(depth);
            OqError error = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                    () -> Parser.parse("<deep>", source),
                    "depth " + depth + " must produce an OqError, not crash");
            assertTrue(error.getMessage().contains("nested too deeply"),
                    "unexpected message at depth " + depth + ": " + error.getMessage());
        }
    }

    @Test
    void deeplyNestedBlocksAreRejectedWithADiagnostic() {
        // Same class of defect as the expression case, but for statements: 2000 nested
        // blocks crashed the parser with a raw StackOverflowError.
        for (int depth : new int[]{200, 2000}) {
            String source = "if true\n".repeat(depth) + "print 1\n" + "end\n".repeat(depth);
            OqError error = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                    () -> Parser.parse("<deep>", source),
                    "block depth " + depth + " must produce an OqError, not crash");
            assertTrue(error.getMessage().contains("nested too deeply"),
                    "unexpected message: " + error.getMessage());
        }
    }

    @Test
    void deeplyNestedFunctionsAreRejectedWithADiagnostic() {
        int depth = 400;
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < depth; i++) source.append("fun f").append(i).append("()\n");
        source.append("print 1\n");
        for (int i = 0; i < depth; i++) source.append("end\n");
        OqError error = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                () -> Parser.parse("<deep>", source.toString()));
        assertTrue(error.getMessage().contains("nested too deeply"), error.getMessage());
    }

    @Test
    void deeplyNestedUnaryChainsAreRejectedWithADiagnostic() {
        // Found by fuzzing (T-0021): `not` and unary `-` recurse through unary(), which had
        // no nesting guard, so ~20k prefixes overflowed the stack and crashed the CLI.
        for (int depth : new int[]{20000, 50000}) {
            String minus = "x = " + "-".repeat(depth) + "1";
            OqError minusError = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                    () -> Parser.parse("<deep>", minus), "unary minus at depth " + depth);
            assertTrue(minusError.getMessage().contains("nested too deeply"), minusError.getMessage());

            String not = "x = " + "not ".repeat(depth) + "true";
            OqError notError = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                    () -> Parser.parse("<deep>", not), "'not' chain at depth " + depth);
            assertTrue(notError.getMessage().contains("nested too deeply"), notError.getMessage());
        }
    }

    @Test
    void deeplyNestedPowerChainsAreRejectedWithADiagnostic() {
        // Same defect class: `**` is right-recursive and bypassed MAX_NESTING.
        String source = "x = " + "1 ** ".repeat(50000) + "1";
        OqError error = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                () -> Parser.parse("<deep>", source));
        assertTrue(error.getMessage().contains("nested too deeply"), error.getMessage());
    }

    @Test
    void reasonableUnaryAndPowerChainsStillParse() {
        Parser.parse("<ok>", "x = " + "-".repeat(100) + "1");
        Parser.parse("<ok>", "x = " + "not ".repeat(100) + "true");
        Parser.parse("<ok>", "x = " + "1 ** ".repeat(100) + "1");
    }

    @Test
    void deeplyNestedInterpolationIsRejectedQuickly() {
        // Found by fuzzing (T-0021): nested text interpolation re-parsed the inner text at
        // every level, so a ~200 byte file took tens of seconds and grew without bound.
        String inner = "0";
        for (int i = 0; i < 40; i++) inner = "{\"" + inner + "\"}";
        String source = "x = \"" + inner + "\"\n";
        long start = System.nanoTime();
        OqError error = org.junit.jupiter.api.Assertions.assertThrows(OqError.class,
                () -> Parser.parse("<deep>", source));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(error.getMessage().contains("nested too deeply"), error.getMessage());
        assertTrue(elapsedMillis < 5000, "took " + elapsedMillis + " ms, expected under 5 s");
    }

    @Test
    void shallowInterpolationStillWorks() {
        Parser.parse("<ok>", "name = \"oq\"\nprint \"hello {name}\"\n");
        Parser.parse("<ok>", "print \"{\"a\"}\"\n");
    }

    @Test
    void reasonableBlockNestingStillParses() {
        int depth = 100;
        String source = "if true\n".repeat(depth) + "print 1\n" + "end\n".repeat(depth);
        Parser.parse("<deep>", source);
    }

    @Test
    void reasonableNestingStillParses() {
        int depth = 100;
        String source = "x = " + "(".repeat(depth) + "1" + ")".repeat(depth);
        Parser.parse("<deep>", source);
    }

    private static List<String> load() throws IOException {
        String resource = "/dev/oqthara/core/curated-cases.txt";
        try (InputStream in = CuratedCorpusTest.class.getResourceAsStream(resource)) {
            if (in == null) return List.of();
            List<String> cases = new ArrayList<>();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                cases.add(unescape(trimmed));
            }
            return cases;
        }
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    default -> out.append('\\').append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
