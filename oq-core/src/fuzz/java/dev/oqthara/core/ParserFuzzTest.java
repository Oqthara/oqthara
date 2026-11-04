package dev.oqthara.core;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.runtime.OqError;

/**
 * Fuzzes the parser with arbitrary text. Parsing either succeeds or raises {@link OqError};
 * any other exception is a defect, because the CLI would report it as an internal error.
 */
class ParserFuzzTest {
    @FuzzTest(maxDuration = "30s")
    void parserNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String source = data.consumeRemainingAsString();
        try {
            Parser.parse("<fuzz>", source);
        } catch (OqError expected) {
            // Syntax errors are a valid outcome.
        } catch (StackOverflowError e) {
            throw new AssertionError("parser overflowed on input of length "
                    + source.length(), e);
        } catch (RuntimeException e) {
            throw new AssertionError("parser crashed on " + repr(source) + ": " + e, e);
        }
    }

    @FuzzTest(maxDuration = "20s")
    void deeplyNestedInputDoesNotOverflow(FuzzedDataProvider data) {
        int depth = data.consumeInt(0, 400);
        String open = data.pickValue(new String[]{"(", "[", "if true\n", "fun f(\n"});
        String close = data.pickValue(new String[]{")", "]", "\nend", ")\nend"});
        String source = "x = " + open.repeat(depth) + "1" + close.repeat(depth);
        try {
            Parser.parse("<fuzz>", source);
        } catch (OqError expected) {
            // Fine: a clear error beats a crash.
        } catch (StackOverflowError e) {
            throw new AssertionError("parser overflowed at depth " + depth, e);
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
