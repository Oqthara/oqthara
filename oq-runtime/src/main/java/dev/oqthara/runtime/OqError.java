package dev.oqthara.runtime;

public class OqError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Span span;
    private final String hint;

    public OqError(String message, Span span) {
        this(message, span, null);
    }

    public OqError(String message, Span span, String hint) {
        super(message);
        this.span = span == null ? Span.synthetic() : span;
        this.hint = hint;
    }

    public Span span() {
        return span;
    }

    public String hint() {
        return hint;
    }

    public static OqError type(String message, Span span) {
        return new OqError(message, span);
    }
}
