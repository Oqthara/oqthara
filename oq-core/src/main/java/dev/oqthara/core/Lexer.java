package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Lexer {
    public static final Set<String> KEYWORDS = Set.of(
            "if", "else", "while", "for", "in", "end",
            "fun", "return", "data", "true", "false", "nothing",
            "and", "or", "not", "break", "continue",
            "use", "try", "catch", "const"
    );

    private static final Map<String, Token.Kind> TWO_CHAR = new LinkedHashMap<>();
    private static final Map<String, Token.Kind> ONE_CHAR = new LinkedHashMap<>();

    static {
        TWO_CHAR.put("**", Token.Kind.STARSTAR);
        TWO_CHAR.put("==", Token.Kind.EQEQ);
        TWO_CHAR.put("!=", Token.Kind.NOTEQ);
        TWO_CHAR.put("<=", Token.Kind.LE);
        TWO_CHAR.put(">=", Token.Kind.GE);
        TWO_CHAR.put("+=", Token.Kind.PLUS_ASSIGN);
        TWO_CHAR.put("-=", Token.Kind.MINUS_ASSIGN);
        TWO_CHAR.put("*=", Token.Kind.STAR_ASSIGN);
        TWO_CHAR.put("/=", Token.Kind.SLASH_ASSIGN);

        ONE_CHAR.put("(", Token.Kind.LPAREN);
        ONE_CHAR.put(")", Token.Kind.RPAREN);
        ONE_CHAR.put("[", Token.Kind.LBRACKET);
        ONE_CHAR.put("]", Token.Kind.RBRACKET);
        ONE_CHAR.put("{", Token.Kind.LBRACE);
        ONE_CHAR.put("}", Token.Kind.RBRACE);
        ONE_CHAR.put(",", Token.Kind.COMMA);
        ONE_CHAR.put(":", Token.Kind.COLON);
        ONE_CHAR.put(".", Token.Kind.DOT);
        ONE_CHAR.put("|", Token.Kind.PIPE);
        ONE_CHAR.put(";", Token.Kind.SEMICOLON);
        ONE_CHAR.put("+", Token.Kind.PLUS);
        ONE_CHAR.put("-", Token.Kind.MINUS);
        ONE_CHAR.put("*", Token.Kind.STAR);
        ONE_CHAR.put("/", Token.Kind.SLASH);
        ONE_CHAR.put("%", Token.Kind.PERCENT);
        ONE_CHAR.put("=", Token.Kind.ASSIGN);
        ONE_CHAR.put("<", Token.Kind.LT);
        ONE_CHAR.put(">", Token.Kind.GT);
    }

    private final String sourceName;
    private final String src;
    private final int interpolationDepth;
    private int pos = 0;
    private int line = 1;
    private int column = 1;
    private int bracketDepth = 0;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String sourceName, String src) {
        this(sourceName, src, 0);
    }

    private Lexer(String sourceName, String src, int interpolationDepth) {
        this.sourceName = sourceName;
        this.src = src;
        this.interpolationDepth = interpolationDepth;
    }

    public static List<Token> lex(String sourceName, String src) {
        return lex(sourceName, src, 0);
    }

    /** Same as {@link #lex(String, String)} but carrying the interpolation depth. */
    static List<Token> lex(String sourceName, String src, int interpolationDepth) {
        Lexer lexer = new Lexer(sourceName, src, interpolationDepth);
        lexer.run();
        return lexer.tokens;
    }

    private void run() {
        while (!atEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                newline();
            } else if (c == '#') {
                skipComment();
            } else if (c == '"' || c == '\'') {
                string(c);
            } else if (isDigit(c)) {
                number();
            } else if (isIdentStart(c)) {
                ident();
            } else {
                operator();
            }
        }
        tokens.add(new Token(Token.Kind.NEWLINE, "\n", null, Span.at(sourceName, line, column)));
        tokens.add(new Token(Token.Kind.EOF, "", null, Span.at(sourceName, line, column)));
    }

    private void newline() {
        int startLine = line;
        int startCol = column;
        advance();
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).is(Token.Kind.NEWLINE)) {
            return;
        }
        tokens.add(new Token(Token.Kind.NEWLINE, "\n", null, Span.at(sourceName, startLine, startCol)));
    }

    private void skipComment() {
        while (!atEnd() && peek() != '\n') advance();
    }

    private void string(char quote) {
        if (quote == '"' && pos + 2 < src.length() && src.charAt(pos + 1) == '"' && src.charAt(pos + 2) == '"') {
            multilineString();
            return;
        }
        int startLine = line;
        int startCol = column;
        advance();
        StringBuilder raw = new StringBuilder();
        boolean closed = false;
        while (!atEnd()) {
            char c = peek();
            if (c == '\\') {
                raw.append(c);
                advance();
                if (atEnd()) break;
                raw.append(peek());
                advance();
                continue;
            }
            if (c == quote) {
                advance();
                closed = true;
                break;
            }
            if (c == '\n') {
                break;
            }
            if (c == '{' && peekNext() == '{') {
                raw.append("{{");
                advance();
                advance();
                continue;
            }
            if (c == '{' && couldStartExpression(pos + 1)) {
                int scan = findClosingBrace(pos);
                if (scan < 0 || !looksLikeInterpolation(pos, scan)) {
                    raw.append(c);
                    advance();
                    continue;
                }
                for (int i = pos; i <= scan; i++) {
                    raw.append(src.charAt(i));
                    advance();
                }
                continue;
            }
            raw.append(c);
            advance();
        }
        if (!closed) {
            throw new OqError("unterminated text literal", Span.at(sourceName, startLine, startCol),
                    "Add a closing " + quote + " at the end of the text.");
        }
        Span span = new Span(sourceName, startLine, startCol, line, column);
        tokens.add(new Token(Token.Kind.STRING, raw.toString(), raw.toString(), span));
    }

    /**
     * A brace is interpolation only when its contents parse as a single expression. This
     * keeps JSON objects and quoted map-like text inside strings intact: {@code "{\"a\": 1}"}
     * is text, while {@code "{name}"} and {@code "{''.length()}"} interpolate.
     */
    private boolean looksLikeInterpolation(int openIndex, int closeIndex) {
        String inner = src.substring(openIndex + 1, closeIndex);
        if (inner.isBlank()) return false;
        if (inner.contains(":")) return false;
        if (interpolationDepth >= Parser.MAX_INTERPOLATION_NESTING) {
            throw new OqError(Parser.INTERPOLATION_LIMIT_MESSAGE,
                    Span.at(sourceName, line, column),
                    "The limit is " + Parser.MAX_INTERPOLATION_NESTING
                            + " levels. Move the inner expression to a named value.");
        }
        try {
            // Keep the caller's file name: the only error that escapes from here is the
            // nesting limit, and pointing it at a pseudo-file would confuse the user.
            var tokens = lex(sourceName, inner, interpolationDepth + 1);
            var parser = new dev.oqthara.core.Parser("<interpolation>", tokens);
            var expr = parser.interpolationExpression();
            return expr != null;
        } catch (OqError e) {
            if (isNestingLimit(e)) throw e;
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isNestingLimit(OqError error) {
        return Parser.INTERPOLATION_LIMIT_MESSAGE.equals(error.getMessage());
    }

    private int findClosingBrace(int openIndex) {
        int depth = 1;
        boolean inString = false;
        char quote = 0;
        for (int i = openIndex + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                continue;
            }
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '\n') return -1;
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private boolean couldStartExpression(int from) {
        if (from >= src.length()) return false;
        char next = src.charAt(from);
        if (Character.isLetterOrDigit(next) || next == '_') return true;
        return next == '(' || next == '[' || next == '!' || next == '-'
                || next == '"' || next == '\'';
    }

    private boolean hasClosingBrace() {
        for (int i = pos + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '}') return true;
            if (c == '\n') return false;
        }
        return false;
    }

    private void multilineString() {
        int startLine = line;
        int startCol = column;
        advance();
        advance();
        advance();
        StringBuilder raw = new StringBuilder();
        boolean closed = false;
        while (!atEnd()) {
            if (peek() == '"' && pos + 2 < src.length()
                    && src.charAt(pos + 1) == '"' && src.charAt(pos + 2) == '"') {
                advance();
                advance();
                advance();
                closed = true;
                break;
            }
            char c = peek();
            raw.append(c);
            advance();
        }
        if (!closed) {
            throw new OqError("unterminated multi-line text literal", Span.at(sourceName, startLine, startCol),
                    "Add a closing \"\"\" after the last line.");
        }
        Span span = new Span(sourceName, startLine, startCol, line, column);
        tokens.add(new Token(Token.Kind.STRING, raw.toString(), raw.toString(), span));
    }

    private void number() {
        int startLine = line;
        int startCol = column;
        StringBuilder sb = new StringBuilder();
        boolean decimal = false;
        while (!atEnd() && (isDigit(peek()) || peek() == '_')) {
            char c = peek();
            if (c != '_') sb.append(c);
            advance();
        }
        if (!atEnd() && peek() == '.' && isDigit(peekNext())) {
            decimal = true;
            sb.append('.');
            advance();
            while (!atEnd() && (isDigit(peek()) || peek() == '_')) {
                char c = peek();
                if (c != '_') sb.append(c);
                advance();
            }
        }
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            int save = pos;
            char savePeek = peek();
            StringBuilder exp = new StringBuilder();
            exp.append(savePeek);
            advance();
            if (!atEnd() && (peek() == '+' || peek() == '-')) {
                exp.append(peek());
                advance();
            }
            if (!atEnd() && isDigit(peek())) {
                while (!atEnd() && isDigit(peek())) {
                    exp.append(peek());
                    advance();
                }
                sb.append(exp);
                decimal = true;
            } else {
                pos = save;
                column -= exp.length();
            }
        }
        Span span = new Span(sourceName, startLine, startCol, line, column);
        String text = sb.toString();
        if (decimal) {
            tokens.add(new Token(Token.Kind.NUMBER, text, Double.parseDouble(text), span));
        } else {
            try {
                tokens.add(new Token(Token.Kind.NUMBER, text, Long.parseLong(text), span));
            } catch (NumberFormatException e) {
                throw new OqError("number " + text + " is too large", span,
                        "The largest supported whole number is " + Long.MAX_VALUE + ".");
            }
        }
    }

    private void ident() {
        int startLine = line;
        int startCol = column;
        StringBuilder sb = new StringBuilder();
        while (!atEnd() && isIdentPart(peek())) {
            sb.append(peek());
            advance();
        }
        String text = sb.toString();
        Span span = new Span(sourceName, startLine, startCol, line, column);
        if (KEYWORDS.contains(text)) {
            tokens.add(new Token(Token.Kind.KEYWORD, text, text, span));
        } else {
            tokens.add(new Token(Token.Kind.IDENT, text, text, span));
        }
    }

    private void operator() {
        int startLine = line;
        int startCol = column;
        if (pos + 1 < src.length()) {
            String two = src.substring(pos, pos + 2);
            Token.Kind kind = TWO_CHAR.get(two);
            if (kind != null) {
                advance();
                advance();
                if (kind == Token.Kind.LBRACKET) bracketDepth++;
                tokens.add(new Token(kind, two, two, new Span(sourceName, startLine, startCol, line, column)));
                return;
            }
        }
        String one = String.valueOf(peek());
        Token.Kind kind = ONE_CHAR.get(one);
        if (kind == null) {
            throw new OqError("unexpected character '" + one + "'", Span.at(sourceName, startLine, startCol),
                    "Remove it or replace it with a valid operator.");
        }
        advance();
        switch (kind) {
            case LPAREN, LBRACKET, LBRACE -> bracketDepth++;
            case RPAREN, RBRACKET, RBRACE -> bracketDepth = Math.max(0, bracketDepth - 1);
            default -> {
            }
        }
        tokens.add(new Token(kind, one, one, new Span(sourceName, startLine, startCol, line, column)));
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    private char peekNext() {
        return pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
    }

    private void advance() {
        if (atEnd()) return;
        if (src.charAt(pos) == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        pos++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
