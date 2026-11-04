package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deterministic parser fuzzing. The invariant is that any input either parses or raises an
 * {@link OqError}. A {@code StackOverflowError}, {@code NullPointerException},
 * {@code IndexOutOfBoundsException} or similar is a bug, because the CLI would report it as
 * an internal error and blame the user's source.
 */
class FuzzTest {
    private static final String[] FRAGMENTS = {
            "if", "else", "end", "fun", "return", "for", "in", "to", "until", "while",
            "break", "continue", "data", "try", "catch", "use", "print", "and", "or", "not",
            "true", "false", "nothing",
            "x", "y", "foo", "bar", "_", "print", "length", "map",
            "0", "1", "42", "3.14", "1e9", "\"text\"", "\"a{b}c\"", "\"\"\"multi\"\"\"",
            "(", ")", "[", "]", "{", "}", ",", ":", ".", "|", ";",
            "+", "-", "*", "/", "%", "**", "=", "+=", "-=", "*=", "/=",
            "==", "!=", "<", "<=", ">", ">=", "\n", "\n", "\n", " ", " ", "#comment\n",
    };

    @Test
    void randomTokenSoupNeverCrashes() {
        Random random = new Random(20261104L);
        int parsed = 0;
        int rejected = 0;
        for (int round = 0; round < 4000; round++) {
            StringBuilder source = new StringBuilder();
            int length = 1 + random.nextInt(24);
            for (int i = 0; i < length; i++) {
                source.append(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
                if (random.nextInt(4) != 0) source.append(' ');
            }
            String text = source.toString();
            try {
                Parser.parse("<fuzz>", text);
                parsed++;
            } catch (OqError expected) {
                rejected++;
            } catch (StackOverflowError | NullPointerException | IndexOutOfBoundsException
                     | IllegalArgumentException | ClassCastException e) {
                fail("parser crashed on: " + text + "\n" + e, e);
            }
        }
        if (parsed + rejected != 4000) {
            fail("fuzz loop did not run every round");
        }
    }

    @Test
    void deeplyNestedExpressionsDoNotOverflow() {
        StringBuilder source = new StringBuilder();
        int depth = 200;
        source.append("x = ");
        source.append("(".repeat(depth));
        source.append("1");
        source.append(")".repeat(depth));
        try {
            Parser.parse("<fuzz>", source.toString());
        } catch (OqError expected) {
            // Acceptable: a clear error is fine, a JVM crash is not.
        } catch (StackOverflowError e) {
            fail("parser overflowed on deep nesting", e);
        }
    }

    @Test
    void unterminatedConstructsGiveErrors() {
        String[] cases = {
                "\"abc", "\"\"\"abc", "if true", "fun f(", "[1, ", "{1: ",
                "for i in", "try", "data", "1 +", "(", "x =",
        };
        for (String source : cases) {
            try {
                Parser.parse("<fuzz>", source);
            } catch (OqError expected) {
                if (expected.getMessage() == null || expected.getMessage().isBlank()) {
                    fail("error without a message for: " + source);
                }
            } catch (RuntimeException e) {
                fail("crash on " + source + ": " + e, e);
            }
        }
    }

    @Test
    void randomStringsNeverCrashTheLexer() {
        Random random = new Random(7L);
        String alphabet = "ab0 \t\n\"'{}()[]<>+-*/%#";
        for (int round = 0; round < 3000; round++) {
            StringBuilder source = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) {
                source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = source.toString();
            try {
                Lexer.lex("<fuzz>", text);
            } catch (OqError expected) {
                if (expected.getMessage() == null) fail("silent lexer error on: " + text);
            } catch (RuntimeException e) {
                fail("lexer crashed on: " + text + "\n" + e, e);
            }
        }
    }
}
