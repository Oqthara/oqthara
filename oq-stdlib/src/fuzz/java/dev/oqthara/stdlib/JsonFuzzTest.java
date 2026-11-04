package dev.oqthara.stdlib;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

/**
 * Fuzzes the JSON reader and writer. Parsing arbitrary text must either produce a value or
 * raise {@link OqError}; anything else is a defect. Round-tripping a parsed value must not
 * crash the writer.
 */
class JsonFuzzTest {
    @FuzzTest(maxDuration = "30s")
    void jsonParseNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();
        try {
            Object value = Json.parse(text, Span.synthetic());
            // The writer must also survive whatever the parser produced.
            Json.write(value);
            Json.writePretty(value);
        } catch (OqError expected) {
            // Invalid JSON is a valid outcome.
        } catch (StackOverflowError e) {
            throw new AssertionError("json parser overflowed on input of length "
                    + text.length(), e);
        } catch (RuntimeException e) {
            throw new AssertionError("json parser crashed on " + repr(text) + ": " + e, e);
        }
    }

    @FuzzTest(maxDuration = "20s")
    void deeplyNestedJsonDoesNotOverflow(FuzzedDataProvider data) {
        int depth = data.consumeInt(0, 2000);
        String text = "[".repeat(depth) + "]".repeat(depth);
        try {
            Json.parse(text, Span.synthetic());
        } catch (OqError expected) {
            // Fine.
        } catch (StackOverflowError e) {
            throw new AssertionError("json parser overflowed at depth " + depth, e);
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
