package dev.oqthara.runtime;

public record Span(String source, int line, int column, int endLine, int endColumn) {
    public Span {
        if (line < 1) line = 1;
        if (column < 1) column = 1;
    }

    public static Span at(String source, int line, int column) {
        return new Span(source, line, column, line, column);
    }

    public static Span synthetic() {
        return new Span("<unknown>", 0, 0, 0, 0);
    }

    public int width() {
        return Math.max(1, endColumn - column);
    }
}
