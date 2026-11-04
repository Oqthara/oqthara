package dev.oqthara.core;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.runtime.OqError;

/**
 * Fuzzes the lexer with arbitrary text. The only acceptable outcome is a token stream or a
 * clean {@link OqError}; any other exception, and any position outside the source, is a bug.
 */
class LexerFuzzTest {
    @FuzzTest(maxDuration = "30s")
    void lexerNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String source = data.consumeRemainingAsString();
        try {
            var tokens = Lexer.lex("<fuzz>", source);
            checkPositions(tokens, source);
        } catch (OqError expected) {
            // A lexical error is a valid outcome.
        } catch (StackOverflowError e) {
            throw new AssertionError("lexer overflowed on input of length " + source.length(), e);
        } catch (RuntimeException e) {
            throw new AssertionError("lexer crashed on " + repr(source) + ": " + e, e);
        }
    }

    private static void checkPositions(java.util.List<Token> tokens, String source) {
        int lines = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') lines++;
        }
        for (Token token : tokens) {
            var span = token.span();
            if (span.line() < 1 || span.line() > lines + 1) {
                throw new AssertionError("token " + token.kind() + " reports line " + span.line()
                        + " for a source with " + lines + " lines");
            }
            if (span.column() < 1) {
                throw new AssertionError("token " + token.kind() + " reports column " + span.column());
            }
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
