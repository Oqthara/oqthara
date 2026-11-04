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
