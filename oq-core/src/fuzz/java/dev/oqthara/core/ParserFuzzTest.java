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

    @FuzzTest(maxDuration = "20s")
    void deeplyNestedBlocksDoNotOverflow(FuzzedDataProvider data) {
        int depth = data.consumeInt(0, 600);
        String opener = data.pickValue(new String[]{"if true\n", "while true\n",
                "for i in 1 to 2\n", "fun g()\n", "try\n"});
        String closer = opener.startsWith("try") ? "catch e\nend\n" : "end\n";
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < depth; i++) source.append(opener);
        source.append("print 1\n");
        for (int i = 0; i < depth; i++) source.append(closer);
        try {
            Parser.parse("<fuzz>", source.toString());
        } catch (OqError expected) {
            // Fine: a clear error beats a crash.
        } catch (StackOverflowError e) {
            throw new AssertionError("parser overflowed on block depth " + depth, e);
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
