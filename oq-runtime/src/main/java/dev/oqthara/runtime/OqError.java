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

    public OqData toValue() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("message", getMessage());
        fields.put("hint", hint);
        fields.put("line", (long) span.line());
        fields.put("column", (long) span.column());
        fields.put("file", span.source());
        return new OqData("Error", fields);
    }
}
