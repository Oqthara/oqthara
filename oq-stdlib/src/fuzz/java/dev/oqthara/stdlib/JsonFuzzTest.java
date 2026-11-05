package dev.oqthara.stdlib;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Fuzzes the JSON reader and writer with arbitrary text and arbitrary bytes. Parsing must
 * either produce a value or raise {@link OqError}; any other {@link Throwable} is a defect.
 *
 * <p>A successful parse is also round-tripped: the writer must accept the value, and parsing
 * its output must give back the same JSON value. A writer that silently changes a number (for
 * example by saturating a large double to {@link Long#MAX_VALUE}) or that emits text the reader
 * rejects is therefore a finding, not just a cosmetic difference.
 *
 * <p>Every input is capped at {@value #MAX_INPUT_BYTES} bytes and the generated nesting shapes
 * stop at 270 levels (just past {@code Json.MAX_DEPTH}), so no seed can make the target allocate
 * unbounded memory or spend minutes in one call.
 */
class JsonFuzzTest {
    /** Hard cap on text and bytes handed to the reader, so a seed stays cheap. */
    private static final int MAX_INPUT_BYTES = 4096;

    @FuzzTest(maxDuration = "30s")
    void jsonParseNeverThrowsUnexpectedExceptions(FuzzedDataProvider data) {
        String text = boundedInput(data);
        Object value;
        try {
            value = Json.parse(text, Span.synthetic());
        } catch (OqError expected) {
            return;
        } catch (StackOverflowError e) {
            throw new AssertionError("json parser overflowed on input of length "
                    + text.length() + ": " + repr(text), e);
        } catch (RuntimeException e) {
            throw new AssertionError("json parser crashed on " + repr(text) + ": " + e, e);
        }

        // The writer must survive whatever the parser produced, and its output must be
        // readable again with the same values. OqError from the writer is not acceptable
        // here: a value that came out of the parser is by definition writable JSON.
        String compact = writeOrFail(value, text, false);
        String pretty = writeOrFail(value, text, true);
        reparseOrFail(compact, value, text, "compact");
        reparseOrFail(pretty, value, text, "pretty");
    }

    @FuzzTest(maxDuration = "20s")
    void deeplyNestedJsonDoesNotOverflow(FuzzedDataProvider data) {
        // Past MAX_DEPTH on purpose: the reader must report the limit as an OqError, not let
        // the JVM stack decide. Realistic documents are far below this.
        int depth = data.consumeInt(0, 2000);
        String text = "[".repeat(depth) + "]".repeat(depth);
        try {
            Json.parse(text, Span.synthetic());
        } catch (OqError expected) {
            // Fine: a clear diagnostic is the contract.
        } catch (StackOverflowError e) {
            throw new AssertionError("json parser overflowed at depth " + depth, e);
        }
    }

    /**
     * Builds one bounded input. Most runs use the raw bytes decoded as UTF-8, so invalid byte
     * sequences become replacement characters; the remaining runs reach nesting and escape
     * shapes directly instead of waiting for the mutator to find them.
     */
    private static String boundedInput(FuzzedDataProvider data) {
        byte[] raw = data.consumeBytes(MAX_INPUT_BYTES);
        String text = switch (data.consumeInt(0, 5)) {
            case 0 -> new String(raw, StandardCharsets.UTF_8);
            case 1 -> {
                int depth = data.consumeInt(0, 270);
                yield "[".repeat(depth) + "]".repeat(depth);
            }
            case 2 -> {
                int depth = data.consumeInt(0, 270);
                yield "{\"k\":".repeat(depth) + "1" + "}".repeat(depth);
            }
            case 3 -> {
                int depth = data.consumeInt(0, 270);
                yield "{\"k\":[".repeat(depth) + "1" + "]}".repeat(depth);
            }
            case 4 -> "\"" + "\\u".repeat(data.consumeInt(0, 1000)) + "\"";
            default -> data.consumeRemainingAsString();
        };
        return text.length() > MAX_INPUT_BYTES ? text.substring(0, MAX_INPUT_BYTES) : text;
    }

    private static String writeOrFail(Object value, String input, boolean pretty) {
        try {
            return pretty ? Json.writePretty(value) : Json.write(value);
        } catch (OqError e) {
            throw new AssertionError("json writer rejected a parsed value (" + repr(input)
                    + " -> " + Values.repr(value) + "): " + e.getMessage(), e);
        } catch (StackOverflowError e) {
            throw new AssertionError("json writer overflowed on " + repr(input), e);
        } catch (RuntimeException e) {
            throw new AssertionError("json writer crashed on " + repr(input) + ": " + e, e);
        }
    }

    private static void reparseOrFail(String written, Object original, String input, String kind) {
        Object back;
        try {
            back = Json.parse(written, Span.synthetic());
        } catch (OqError e) {
            throw new AssertionError("the " + kind + " writer produced text the parser rejects "
                    + "for " + repr(input) + ": " + repr(written), e);
        } catch (RuntimeException e) {
            throw new AssertionError("re-parsing " + kind + " output for " + repr(input)
                    + " crashed: " + e, e);
        }
        if (!jsonEqual(original, back)) {
            throw new AssertionError("the " + kind + " writer changed the value for "
                    + repr(input) + ": " + Values.repr(original) + " became " + Values.repr(back)
                    + " through " + repr(written));
        }
    }

    /**
     * JSON equality: numbers compare by value, because writing the decimal {@code 1.0} as
     * {@code 1} is legal and parsing it back yields the whole number {@code 1}. Plain
     * {@link Values#equal(Object, Object)} only applies that rule at the top level, not inside
     * lists and maps.
     */
    private static boolean jsonEqual(Object a, Object b) {
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!jsonEqual(x.get(i), y.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (Object key : x.keySet()) {
                if (!jsonEqual(x.get(key), y.get(key))) return false;
            }
            return true;
        }
        return Values.equal(a, b);
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
