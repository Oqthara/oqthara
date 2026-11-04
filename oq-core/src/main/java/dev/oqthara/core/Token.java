package dev.oqthara.core;

import dev.oqthara.runtime.Span;

public record Token(Kind kind, String lexeme, Object literal, Span span) {
    public enum Kind {
        NUMBER, STRING, IDENT, KEYWORD,
        NEWLINE, EOF,
        LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
        COMMA, COLON, DOT, PIPE, SEMICOLON,
        PLUS, MINUS, STAR, SLASH, PERCENT, STARSTAR,
        ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
        EQEQ, NOTEQ, LT, LE, GT, GE
    }

    @Override
    public String toString() {
        return kind + (literal != null ? "(" + literal + ")" : "('" + lexeme + "')");
    }

    public boolean is(Kind k) {
        return kind == k;
    }
}
