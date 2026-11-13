package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code html_escape}, {@code html_unescape}, {@code html_escape_attribute} and
 * {@code html_entity} from ADR-0054.
 *
 * <p>The contract these tests defend is the one the standard-library inventory states for this
 * domain: escape exactly the five significant characters, let double-escaping be visible, and
 * never escape implicitly. {@link #escapingIsNotIdempotent} is the one that matters most — an
 * escape that quietly recognised existing entities could not express the literal text
 * {@code &amp;}, which makes it a smuggling route rather than a convenience.
 */
class HtmlTextTest {

    @TempDir
    Path dir;

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), dir, new MethodResolverImpl());
            Stdlib.install(host);
        }
        return host;
    }

    private Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        assertTrue(callable != null, name + " is not registered");
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private String escape(String text) {
        return (String) call("html_escape", text);
    }

    private String unescape(String text) {
        return (String) call("html_unescape", text);
    }

    // --- html_escape -------------------------------------------------------------------------

    @Test
    void allFiveSignificantCharactersAreEscaped() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", escape("&<>\"'"));
    }

    @Test
    void ordinaryTextIsUnchanged() {
        String text = "The quick brown fox, 123, \u00e9\u00e8, and nothing special.";
        assertEquals(text, escape(text));
    }

    /**
     * The order bug this module exists to prevent.
     *
     * <p>If {@code <} were replaced before {@code &}, the ampersand of {@code &lt;} would then
     * be escaped again and the output would be {@code &amp;lt;}, which renders as the literal
     * text {@code &lt;} rather than {@code <}. Asserting the exact output of a mixed string is
     * what catches that, so this case is checked character by character rather than by
     * round-tripping, which would pass either way.
     */
    @Test
    void ampersandIsReplacedBeforeTheOthersSoItsOutputIsNotEscapedAgain() {
        assertEquals("a &amp;lt; b", escape("a &lt; b"));
        assertEquals("&amp;amp;", escape("&amp;"));
        assertEquals("1 &lt; 2 &amp;&amp; 3 &gt; 2", escape("1 < 2 && 3 > 2"));
    }

    /**
     * Escaping is deliberately not idempotent, and the difference must be visible.
     *
     * <p>The second application escapes the ampersands of the entities the first produced. An
     * escape that instead left {@code &amp;} alone would be unable to express text containing
     * the literal four characters {@code &amp;}, so the visibility is a correctness property
     * and not an accident of implementation.
     */
    @Test
    void escapingIsNotIdempotent() {
        String once = escape("&");
        String twice = escape(once);
        assertEquals("&amp;", once);
        assertEquals("&amp;amp;", twice);
        assertNotEquals(once, twice);
        assertEquals(once, unescape(twice));
    }

    @Test
    void emptyTextEscapesToEmptyText() {
        assertEquals("", escape(""));
    }

    @Test
    void escapeRefusesNonText() {
        OqError error = assertThrows(OqError.class, () -> call("html_escape", 42.0));
        assertTrue(error.getMessage().contains("needs text"), error.getMessage());
    }

    // --- html_unescape -----------------------------------------------------------------------

    @Test
    void roundTripsEveryEntityTheTableKnows() {
        String raw = "&<>\"'\u00a0";
        assertEquals(raw, unescape(escape(raw)));
    }

    @Test
    void numericEntitiesAreResolvedInBothBases() {
        assertEquals("<", unescape("&#60;"));
        assertEquals("<", unescape("&#x3C;"));
        assertEquals("<", unescape("&#X3c;"));
        assertEquals("\u00e9", unescape("&#233;"));
        assertEquals("\u00e9", unescape("&#xe9;"));
    }

    @Test
    void astralCodePointsSurviveInBothDirections() {
        // A surrogate pair is only reachable through a numeric entity, so it is the case that
        // proves the numeric path builds characters rather than truncating to 16 bits.
        String emoji = "\uD83D\uDE00";
        assertEquals(emoji, unescape("&#128512;"));
        assertEquals(emoji, escape(emoji));
    }

    /**
     * A lone ampersand is prose, not a malformed entity, and must survive.
     *
     * <p>Refusing it would make the function unusable on any sentence containing an
     * ampersand, which is most sentences about ampersands. HTML is lenient in exactly this
     * one direction and the leniency is specified here rather than inherited.
     */
    @Test
    void anAmpersandThatStartsNoEntityIsKept() {
        assertEquals("a & b", unescape("a & b"));
        assertEquals("&", unescape("&"));
        assertEquals("trailing &", unescape("trailing &"));
        assertEquals("&;", unescape("&;"));
    }

    /**
     * An ampersand in prose must survive even when a semicolon appears later in the sentence.
     *
     * <p>The naive implementation looks for the next {@code ;} and treats everything between as
     * an entity name. Under that rule "Tom & Jerry; the cartoon" is an entity called
     * {@code " Jerry"} and is refused, so the function fails on ordinary text for a reason the
     * caller cannot see. The shape of the candidate is what distinguishes an attempted entity
     * from an ampersand that happens to precede a semicolon, and this test is what pins it.
     */
    @Test
    void anAmpersandInProseIsKeptEvenWhenASemicolonFollowsLater() {
        assertEquals("Tom & Jerry; the cartoon", unescape("Tom & Jerry; the cartoon"));
        assertEquals("a & b; c", unescape("a & b; c"));
        // Prose ampersand kept, real entity resolved: the leniency must not disable the table.
        assertEquals("& and & together", unescape("& and &amp; together"));
    }

    /**
     * A leading {@code #} is an unambiguous attempt at a numeric reference, so it is reported
     * rather than reinterpreted as prose.
     *
     * <p>This is the boundary of the previous test: the leniency that keeps a stray ampersand
     * must not extend to a caller who wrote {@code &#;} and expected a number. Reporting the
     * mistake is the only reading that leaves both cases correct.
     */
    @Test
    void aHashIsAlwaysReadAsANumericAttemptEvenWithNoDigits() {
        OqError empty = assertThrows(OqError.class, () -> unescape("&#;"));
        assertTrue(empty.getMessage().contains("no digits"), empty.getMessage());
        OqError hex = assertThrows(OqError.class, () -> unescape("&#x;"));
        assertTrue(hex.getMessage().contains("no digits"), hex.getMessage());
    }

    @Test
    void anUnknownNameIsRefusedRatherThanPassedThrough() {
        OqError error = assertThrows(OqError.class, () -> unescape("&bogus;"));
        assertTrue(error.getMessage().contains("does not know"), error.getMessage());
    }

    @Test
    void aMalformedNumericEntityIsRefused() {
        assertThrows(OqError.class, () -> unescape("&#;"));
        assertThrows(OqError.class, () -> unescape("&#x;"));
        assertThrows(OqError.class, () -> unescape("&#zz;"));
        assertThrows(OqError.class, () -> unescape("&#xzz;"));
    }

    /**
     * Surrogates are refused, not merely out of range.
     *
     * <p>A lone surrogate cannot be encoded, so returning one would hand back a string that
     * fails later and elsewhere — when it is written out — which is the least debuggable shape
     * a failure can take. Refusing at the boundary puts the error where the bad input is.
     */
    @Test
    void aSurrogateOrOutOfRangeCodePointIsRefused() {
        assertThrows(OqError.class, () -> unescape("&#55296;"), "U+D800 is a surrogate");
        assertThrows(OqError.class, () -> unescape("&#57343;"), "U+DFFF is a surrogate");
        assertThrows(OqError.class, () -> unescape("&#1114112;"), "beyond U+10FFFF");
        assertThrows(OqError.class, () -> unescape("&#x110000;"), "beyond U+10FFFF");
    }

    // --- html_escape_attribute ---------------------------------------------------------------

    /**
     * The attribute escape is the text escape, and this test is what keeps that statement true.
     *
     * <p>It exists as a separate name so calling code can say which context the string is
     * for. The day the two diverge — for example to refuse a control character an attribute
     * may not carry — this test fails and the divergence becomes a decision instead of a
     * silent difference.
     */
    @Test
    void theAttributeEscapeAgreesWithTheTextEscape() {
        String[] samples = {"plain", "&<>\"'", "a &lt; b", "", "quotes: \"x\" and 'y'"};
        for (String sample : samples) {
            assertEquals(escape(sample), call("html_escape_attribute", sample),
                    "attribute and text escaping disagree for: " + sample);
        }
    }

    // --- html_entity -------------------------------------------------------------------------

    @Test
    void anEntityIsAvailableByCharacterAndByName() {
        assertEquals("&amp;", call("html_entity", "&"));
        assertEquals("&amp;", call("html_entity", "amp"));
        assertEquals("&lt;", call("html_entity", "<"));
        assertEquals("&lt;", call("html_entity", "lt"));
        assertEquals("&nbsp;", call("html_entity", "\u00a0"));
        assertEquals("&nbsp;", call("html_entity", "nbsp"));
    }

    /**
     * A character with no entity is refused rather than returned unchanged.
     *
     * <p>Unchanged output would make {@code html_entity("a")} look like a success that
     * produced markup, so a caller wiring it into a template would emit a bare letter where it
     * expected an entity. Refusing states which characters have one.
     */
    @Test
    void aCharacterWithoutAnEntityIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("html_entity", "a"));
        assertTrue(error.getMessage().contains("no entity"), error.getMessage());
        assertThrows(OqError.class, () -> call("html_entity", "bogus"));
    }

    // --- the whole surface -------------------------------------------------------------------

    @Test
    void everyBuiltinInThisModuleIsRegistered() {
        for (String name : List.of("html_escape", "html_unescape", "html_escape_attribute",
                "html_entity")) {
            assertTrue(host().builtins().containsKey(name), name + " is not registered");
        }
    }
}
