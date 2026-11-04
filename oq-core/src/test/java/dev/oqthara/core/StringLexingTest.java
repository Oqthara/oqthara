package dev.oqthara.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringLexingTest {
    private String lexRaw(String source) {
        List<Token> tokens = Lexer.lex("test", source);
        return (String) tokens.get(0).literal();
    }

    @Test
    void nestedBracesSurvive() {
        assertEquals("{\\\"a\\\": {\\\"b\\\": [1, 2]}}",
                lexRaw("\"{\\\"a\\\": {\\\"b\\\": [1, 2]}}\""));
    }

    @Test
    void singleClosingBraceSurvives() {
        assertEquals("x}", lexRaw("\"x}\""));
        assertEquals("}}}", lexRaw("\"}}}\""));
    }

    @Test
    void escapedBracesArePreservedForTheParser() {
        assertEquals("a {{literal}} brace", lexRaw("\"a {{literal}} brace\""));
    }

    @Test
    void interpolationIsKeptWhole() {
        assertEquals("hi {name}", lexRaw("\"hi {name}\""));
        assertEquals("{a + b}", lexRaw("\"{a + b}\""));
    }

    @Test
    void interpolationWithNestedString() {
        assertEquals("{m[\\\"k\\\"]}", lexRaw("\"{m[\\\"k\\\"]}\""));
    }

    @Test
    void newlineInsideStringIsAnError() {
        dev.oqthara.runtime.OqError error = org.junit.jupiter.api.Assertions.assertThrows(
                dev.oqthara.runtime.OqError.class, () -> Lexer.lex("test", "\"abc\ndef\""));
        assertTrue(error.getMessage().contains("unterminated"));
    }

    @Test
    void tripleQuotedString() {
        assertEquals("\n  a\n  b\n  ", lexRaw("\"\"\"\n  a\n  b\n  \"\"\""));
    }

    @Test
    void tripleQuotedStringMissingEnd() {
        dev.oqthara.runtime.OqError error = org.junit.jupiter.api.Assertions.assertThrows(
                dev.oqthara.runtime.OqError.class, () -> Lexer.lex("test", "\"\"\"abc"));
        assertTrue(error.getMessage().contains("unterminated multi-line"));
    }

    @Test
    void escapesArePreservedInTheRawLiteral() {
        assertEquals("a\\nb", lexRaw("\"a\\nb\""));
    }

    @Test
    void unterminatedInterpolationFallsBackToLiteral() {
        assertEquals("a { b", lexRaw("\"a { b\""));
    }
}
