package dev.oqthara.core;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.runtime.OqError;

import java.util.List;

/**
 * Fuzzes the lexer with arbitrary text. The only acceptable outcome is a token stream or a
 * clean {@link OqError}; any other exception is a bug.
 *
 * <p>Successful token streams are also checked for span sanity: every token must point at a
 * real line and column inside the source, spans must not run backwards, and tokens must arrive
 * in source order. Positions are how diagnostics point at the offending text, so a wrong span
 * is a user-visible defect even when no exception is thrown.
 *
 * <p>Every few inputs the provider is asked for a deterministic shape instead of random text,
 * so the same target also reaches deep nesting, unbalanced brackets and escape runs.
 */
class LexerFuzzTest {
    @FuzzTest(maxDuration = "30s")
    void lexerNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String source = switch (data.consumeInt(0, 15)) {
            case 0 -> "\"" + "(".repeat(data.consumeInt(0, 20000)) + "\"";
            case 1 -> "\"" + "{{".repeat(data.consumeInt(0, 20000)) + "\"";
            case 2 -> "[\\".repeat(data.consumeInt(0, 20000));
            case 3 -> "\"" + "\\\"".repeat(data.consumeInt(0, 20000)) + "\"";
            case 4 -> "\"" + "{".repeat(data.consumeInt(0, 2000))
                    + "1" + "}".repeat(data.consumeInt(0, 2000)) + "\"";
            case 5 -> "#".repeat(data.consumeInt(0, 20000)) + "\n";
            default -> data.consumeRemainingAsString();
        };
        lex(source);
    }

    private static void lex(String source) {
        List<Token> tokens;
        try {
            tokens = Lexer.lex("<fuzz>", source);
        } catch (OqError expected) {
            return;
        } catch (StackOverflowError e) {
            throw new AssertionError("lexer overflowed on input of length " + source.length()
                    + ": " + repr(source), e);
        } catch (RuntimeException e) {
            throw new AssertionError("lexer crashed on " + repr(source) + ": " + e, e);
        }
        checkPositions(tokens, source);
    }

    private static void checkPositions(List<Token> tokens, String source) {
        int[] lineStarts = new int[source.length() + 2];
        int lines = 1;
        lineStarts[0] = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') lineStarts[lines++] = i + 1;
        }
        int previousStart = 0;
        for (Token token : tokens) {
            var span = token.span();
            if (span.line() < 1 || span.column() < 1
                    || span.endLine() < span.line() || span.endColumn() < 1
                    || span.line() > lines || span.endLine() > lines) {
                throw new AssertionError("token " + token.kind() + " reports span " + span
                        + " for a source with " + lines + " lines: " + repr(source));
            }
            int start = lineStarts[span.line() - 1] + span.column() - 1;
            int end = lineStarts[span.endLine() - 1] + span.endColumn() - 1;
            if (start < previousStart) {
                throw new AssertionError("token " + token.kind() + " at " + span
                        + " appears before the previous token: " + repr(source));
            }
            if (start < 0 || end > source.length() || end < start) {
                throw new AssertionError("token " + token.kind() + " spans outside the source ("
                        + start + ".." + end + ", length " + source.length() + "): " + repr(source));
            }
            previousStart = start;
        }
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
