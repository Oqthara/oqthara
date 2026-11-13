package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL parsing, query strings and the escaping rules of ADR-0037.
 *
 * <p>Every expected value here was produced by running the built CLI, except the assertions about
 * shapes that are not reachable from source. Nothing in this class makes a network request: the
 * HTTP cases exercise only the failure paths, which are local, and the success path is covered by
 * the conformance suite where a request is already made.
 */
class UrlAndHttpTest {

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), java.nio.file.Path.of("."), new MethodResolverImpl());
            Stdlib.install(host);
        }
        return host;
    }

    private Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private static OqData data(Object value) {
        return assertInstanceOfOqData(value);
    }

    private static OqData assertInstanceOfOqData(Object value) {
        assertTrue(value instanceof OqData, "expected a data value, got " + value);
        return (OqData) value;
    }

    // --- url_parse ---------------------------------------------------------------------------

    @Test
    void parsingAFullUrlSplitsEveryPart() {
        OqData url = data(call("url_parse", "https://example.com/a/b?x=1&y=2#frag"));
        assertEquals("https", url.get("scheme"));
        assertEquals("example.com", url.get("host"));
        assertEquals("/a/b", url.get("path"));
        assertEquals("x=1&y=2", url.get("query"));
        assertEquals("frag", url.get("fragment"));
    }

    @Test
    void anAbsentPortIsNothingRatherThanTheSchemeDefault() {
        // 443 would make https://h indistinguishable from https://h:443, and the two are
        // different strings (ADR-0037, Decision 1).
        assertNull(data(call("url_parse", "https://example.com")).get("port"));
        assertEquals(8443L, data(call("url_parse", "https://example.com:8443/p")).get("port"));
    }

    @Test
    void aRelativeReferenceParsesWithoutAScheme() {
        OqData url = data(call("url_parse", "/just/a/path"));
        assertNull(url.get("scheme"));
        assertNull(url.get("host"));
        assertEquals("/just/a/path", url.get("path"));
    }

    @Test
    void anOpaqueUriHasNoPath() {
        // mailto: has no path. Reporting "" would claim it has an empty one.
        assertNull(data(call("url_parse", "mailto:x@y.z")).get("path"));
    }

    @Test
    void thePathIsDecoded() {
        assertEquals("/~user", data(call("url_parse", "https://example.com/%7Euser")).get("path"));
    }

    @Test
    void anIPv6LiteralKeepsItsBrackets() {
        assertEquals("[::1]", data(call("url_parse", "http://[::1]:8080/p")).get("host"));
    }

    @Test
    void textJavaRejectsIsAnErrorNamingTheInput() {
        OqError error = assertThrows(OqError.class,
                () -> call("url_parse", "https://example.com/a b"));
        assertTrue(error.getMessage().contains("https://example.com/a b"), error.getMessage());
        assertTrue(error.hint().contains("url_encode"), error.hint());
    }

    // --- url_encode / url_decode -------------------------------------------------------------

    @Test
    void encodingIsFormEncodingSoSpaceBecomesPlus() {
        assertEquals("a+b", call("url_encode", "a b"));
    }

    @Test
    void aLiteralPlusIsEscapedSoThePairRoundTrips() {
        assertEquals("a%2Bb", call("url_encode", "a+b"));
        assertEquals("a+b", call("url_decode", "a%2Bb"));
    }

    @Test
    void reservedCharactersAreEscaped() {
        assertEquals("a%26b%3Dc", call("url_encode", "a&b=c"));
    }

    @Test
    void nonAsciiIsPercentEncodedAsUtf8() {
        assertEquals("%C3%BC%2F%7E.", call("url_encode", "ü/~."));
        assertEquals("ü/~.", call("url_decode", "%C3%BC%2F%7E."));
    }

    @Test
    void decodingPlusGivesSpace() {
        assertEquals("a b", call("url_decode", "a+b"));
        assertEquals("a b", call("url_decode", "a%20b"));
    }

    @Test
    void malformedPercentEncodingIsAnError() {
        OqError error = assertThrows(OqError.class, () -> call("url_decode", "bad%zz"));
        assertTrue(error.getMessage().contains("bad%zz"), error.getMessage());
        assertTrue(error.hint().contains("%25"), error.hint());
    }

    // --- query_parse / query_encode ----------------------------------------------------------

    @Test
    void repeatedNamesAreKeptInOrder() {
        // A map would silently drop one of them, which is why the shape is a list of pairs.
        List<?> pairs = (List<?>) call("query_parse", "a=1&a=2&b=3");
        assertEquals(3, pairs.size());
        assertEquals("a", data(pairs.get(0)).get("name"));
        assertEquals("1", data(pairs.get(0)).get("value"));
        assertEquals("2", data(pairs.get(1)).get("value"));
        assertEquals("b", data(pairs.get(2)).get("name"));
    }

    @Test
    void aLeadingQuestionMarkIsAccepted() {
        List<?> pairs = (List<?>) call("query_parse", "?a=1");
        assertEquals(1, pairs.size());
        assertEquals("a", data(pairs.get(0)).get("name"));
    }

    @Test
    void aPairWithoutAnEqualsHasAnEmptyValue() {
        List<?> pairs = (List<?>) call("query_parse", "flag&x=1");
        assertEquals("flag", data(pairs.get(0)).get("name"));
        assertEquals("", data(pairs.get(0)).get("value"));
    }

    @Test
    void plusInAQueryMeansSpace() {
        assertEquals("a b", data(((List<?>) call("query_parse", "q=a+b")).get(0)).get("value"));
    }

    @Test
    void anEmptyQueryIsAnEmptyList() {
        assertEquals(List.of(), call("query_parse", ""));
    }

    @Test
    void encodingAParsedQueryRoundTrips() {
        // A space decodes from + and encodes back to +, so the text normalises rather than
        // returning byte-identical, and that is correct for form encoding.
        assertEquals("q=a+b&r=x+y", call("query_encode", call("query_parse", "q=a+b&r=x y")));
    }

    @Test
    void aLiteralPlusSurvivesTheRoundTripBecauseItIsPercentEncoded() {
        // %2B decodes to a plus, and the plus is escaped again on the way out.
        assertEquals("q=a%2Bb", call("query_encode", call("query_parse", "q=a%2Bb")));
    }

    @Test
    void encodingAcceptsAPlainMapBecauseApiValuesCannotBeBuiltFromSource() {
        // Found by probing: OqData is never constructible from source, so accepting only the
        // parsed shape left query_encode with no caller-writable input (ADR-0037, Decision 3).
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("name", "a");
        pair.put("value", "1");
        assertEquals("a=1", call("query_encode", List.of(pair)));
    }

    @Test
    void encodingRejectsAPairWithoutAValue() {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("name", "a");
        OqError error = assertThrows(OqError.class, () -> call("query_encode", List.of(pair)));
        assertTrue(error.hint().contains("value"), error.hint());
    }

    // --- url_join ----------------------------------------------------------------------------

    @Test
    void joiningARelativeReferenceResolvesAgainstTheBase() {
        assertEquals("https://example.com/a/b",
                call("url_join", "https://example.com/a/", "b"));
    }

    @Test
    void aSpaceInAReferenceIsEscapedPerSegment() {
        // url_encode would give a+b and %2F, both wrong for a path.
        assertEquals("https://example.com/a/a%20b/c",
                call("url_join", "https://example.com/a/", "a b/c"));
    }

    @Test
    void dotDotIsAppliedAsPathSyntaxNotEncoded() {
        assertEquals("https://example.com/z", call("url_join", "https://example.com/a/", "../z"));
    }

    @Test
    void aRootedReferenceReplacesTheBasePath() {
        assertEquals("https://example.com/rooted",
                call("url_join", "https://example.com/a/", "/rooted"));
    }

    @Test
    void anAbsoluteReferenceReplacesTheBaseEntirely() {
        assertEquals("https://other.test/x",
                call("url_join", "https://example.com/a/", "https://other.test/x"));
    }

    // --- HTTP failure reporting --------------------------------------------------------------

    @Test
    void aRefusedConnectionSaysSoInsteadOfEndingInNull() {
        // The shipped message was `HTTP request failed: null`, because every exception in the
        // chain carries a null message (ADR-0037, Decision 8).
        OqError error = assertThrows(OqError.class,
                () -> call("http_get", "http://127.0.0.1:1/"));
        assertFalse(error.getMessage().contains("null"), error.getMessage());
        assertTrue(error.getMessage().contains("connection refused"), error.getMessage());
    }

    @Test
    void anUnresolvableHostIsDistinguishedFromARefusedConnection() {
        // Both chains wrap a ConnectException, so the leaf is what separates them.
        OqError error = assertThrows(OqError.class,
                () -> call("http_get", "http://no-such-host.invalid/"));
        assertTrue(error.getMessage().contains("cannot resolve the host"), error.getMessage());
        assertFalse(error.getMessage().contains("connection refused"), error.getMessage());
    }

    @Test
    void dotSegmentsNeverClimbAboveTheRoot() {
        // RFC 3986 5.4.1: this base and these references resolve to http://a/g. URI.resolve
        // returned http://a/../g, leaving the segments in place (#160).
        String base = "http://a/b/c/d;p?q";
        for (String reference : new String[] {"../../../g", "../../../../g", "/./g", "/../g"}) {
            assertEquals("http://a/g", call("url_join", base, reference),
                    "reference " + reference);
        }
    }

    @Test
    void theRfcNormalExampleMatrixResolves() {
        // RFC 3986 5.4.1, normal examples, transcribed. The separator-free cases are the ones
        // the component splitter has to get right.
        String base = "http://a/b/c/d;p?q";
        String[][] cases = {
            {"g:h", "g:h"},
            {"g", "http://a/b/c/g"},
            {"./g", "http://a/b/c/g"},
            {"g/", "http://a/b/c/g/"},
            {"/g", "http://a/g"},
            {"//g", "http://g"},
            {"?y", "http://a/b/c/d;p?y"},
            {"g?y", "http://a/b/c/g?y"},
            {"#s", "http://a/b/c/d;p?q#s"},
            {"g#s", "http://a/b/c/g#s"},
            {"g?y#s", "http://a/b/c/g?y#s"},
            {"", "http://a/b/c/d;p?q"},
            {".", "http://a/b/c/"},
            {"./", "http://a/b/c/"},
            {"..", "http://a/b/"},
            {"../", "http://a/b/"},
            {"../g", "http://a/b/g"},
            {"../..", "http://a/"},
            {"../../g", "http://a/g"},
            {"g.", "http://a/b/c/g."},
            {".g", "http://a/b/c/.g"},
            {"g..", "http://a/b/c/g.."},
            {"..g", "http://a/b/c/..g"},
            {"./../g", "http://a/b/g"},
            {"./g/.", "http://a/b/c/g/"},
            {"g/./h", "http://a/b/c/g/h"},
            {"g/../h", "http://a/b/c/h"},
            {"g?y/./x", "http://a/b/c/g?y/./x"},
            {"g?y/../x", "http://a/b/c/g?y/../x"},
            {"g#s/./x", "http://a/b/c/g#s/./x"},
            {"g#s/../x", "http://a/b/c/g#s/../x"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], call("url_join", base, c[0]), "reference '" + c[0] + "'");
        }
    }

    @Test
    void anOpaqueAbsoluteReferenceReplacesTheBaseEntirely() {
        // These have a scheme but no "//", so sniffing for "://" escaped them into the base
        // path and produced https://example.com/a/mailto%3Auser%40example.com (#177).
        String base = "https://example.com/a/";
        assertEquals("mailto:user@example.com", call("url_join", base, "mailto:user@example.com"));
        assertEquals("urn:isbn:9780306406157",
                call("url_join", base, "urn:isbn:9780306406157"));
        assertEquals("file:/tmp/test", call("url_join", base, "file:/tmp/test"));
    }

    @Test
    void aColonInTheFirstSegmentCanBeASchemeOrAPath() {
        // "a:b/c" is a URI with scheme a; "a/b:c" is a relative path, because a scheme cannot
        // contain a separator.
        assertEquals("a:b/c", call("url_join", "https://example.com/x/", "a:b/c"));
        assertEquals("https://example.com/x/a/b%3Ac",
                call("url_join", "https://example.com/x/", "a/b:c"));
    }

    @Test
    void queryAndFragmentReferencesAreNotEscapedIntoThePath() {
        // Splitting the whole reference on '/' turned '?' into %3F and '#' into %23 (#186).
        String base = "https://example.com/a/b?old=1#old";
        assertEquals("https://example.com/a/b?q=1", call("url_join", base, "?q=1"));
        assertEquals("https://example.com/a/b?old=1#new", call("url_join", base, "#new"));
        assertEquals("https://example.com/a/b?old=1", call("url_join", base, ""));
        assertEquals("https://example.com/a/b?q=1#f", call("url_join", base, "?q=1#f"));
        assertEquals("https://example.com/a/c?x=1#y", call("url_join", base, "c?x=1#y"));
    }

    @Test
    void aQueryInARelativeReferenceDoesNotDisturbItsPath() {
        assertEquals("https://example.com/a/c/d?x=1#y",
                call("url_join", "https://example.com/a/b", "c/d?x=1#y"));
    }

    @Test
    void aNetworkPathReferenceKeepsTheBaseScheme() {
        assertEquals("https://other.test/p", call("url_join", "https://example.com/a/", "//other.test/p"));
    }

    @Test
    void malformedUtf8InAPercentEscapeIsReportedNotReplaced() {
        // URLDecoder substituted U+FFFD, turning corrupt input into a plausible value (#173).
        OqError decode = assertThrows(OqError.class, () -> call("url_decode", "%FF"));
        assertTrue(decode.getMessage().contains("invalid UTF-8"), decode.getMessage());
        OqError parse = assertThrows(OqError.class, () -> call("query_parse", "q=%FF"));
        assertTrue(parse.getMessage().contains("invalid UTF-8"), parse.getMessage());
    }

    @Test
    void truncatedOrNonHexEscapesAreStillReported() {
        // Both name the input and point at the %25 escape, which is the mistake being made.
        OqError truncated = assertThrows(OqError.class, () -> call("url_decode", "a%2"));
        assertTrue(truncated.getMessage().contains("a%2"), truncated.getMessage());
        assertTrue(truncated.hint().contains("%25"), truncated.hint());
        OqError nonHex = assertThrows(OqError.class, () -> call("url_decode", "a%ZZ"));
        assertTrue(nonHex.hint().contains("hexadecimal"), nonHex.hint());
    }

    @Test
    void validUtf8StillDecodes() {
        assertEquals("a b \u00e9", call("url_decode", "a%20b+%C3%A9"));
        // A plus is a space here, which is the form-encoding contract rather than a repair.
        assertEquals("a b", call("url_decode", "a+b"));
        // A supplementary character survives both directions.
        assertEquals("\uD83D\uDE00", call("url_decode", "%F0%9F%98%80"));
    }
}
