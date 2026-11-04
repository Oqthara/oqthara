package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {
    @Test
    void lexesNumbers() {
        List<Token> tokens = Lexer.lex("test", "1 2.5 1_000 1e3");
        assertEquals(6, tokens.size());
        assertEquals(1L, tokens.get(0).literal());
        assertEquals(2.5, tokens.get(1).literal());
        assertEquals(1000L, tokens.get(2).literal());
        assertEquals(1000.0, tokens.get(3).literal());
    }

    @Test
    void lexesOperators() {
        List<Token> tokens = Lexer.lex("test", "+ - * / % ** == != <= >=");
        assertEquals(Token.Kind.PLUS, tokens.get(0).kind());
        assertEquals(Token.Kind.STARSTAR, tokens.get(5).kind());
        assertEquals(Token.Kind.EQEQ, tokens.get(6).kind());
        assertEquals(Token.Kind.NOTEQ, tokens.get(7).kind());
        assertEquals(Token.Kind.GE, tokens.get(9).kind());
    }

    @Test
    void lexesKeywords() {
        List<Token> tokens = Lexer.lex("test", "if else while for in end fun return");
        assertEquals(Token.Kind.KEYWORD, tokens.get(0).kind());
        assertEquals("if", tokens.get(0).lexeme());
        assertEquals("return", tokens.get(7).lexeme());
    }

    @Test
    void tracksLineAndColumn() {
        List<Token> tokens = Lexer.lex("test", "a\n  b");
        assertEquals(1, tokens.get(0).span().line());
        assertEquals(1, tokens.get(0).span().column());
        assertEquals(2, tokens.get(2).span().line());
        assertEquals(3, tokens.get(2).span().column());
    }

    @Test
    void rejectsUnterminatedString() {
        OqError error = assertThrows(OqError.class, () -> Lexer.lex("test", "\"abc"));
        assertTrue(error.getMessage().contains("unterminated"));
    }

    @Test
    void skipsComments() {
        List<Token> tokens = Lexer.lex("test", "# hello\nx");
        assertEquals(Token.Kind.NEWLINE, tokens.get(0).kind());
        assertEquals(Token.Kind.IDENT, tokens.get(1).kind());
    }

    @Test
    void keepsNewlinesInsideBrackets() {
        // The parser decides where newlines are insignificant, because block lambdas
        // inside call parentheses need them.
        List<Token> tokens = Lexer.lex("test", "[1,\n2]");
        long newlines = tokens.stream().filter(t -> t.is(Token.Kind.NEWLINE)).count();
        assertEquals(2, newlines);
    }
}
