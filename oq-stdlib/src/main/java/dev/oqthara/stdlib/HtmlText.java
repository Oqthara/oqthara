package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTML escaping in both directions: {@code html_escape}, {@code html_unescape},
 * {@code html_escape_attribute} and {@code html_entity}.
 *
 * <p>This is a defence, not a markup library. The standard library has no element tree, no
 * parser and no serialiser, and this module adds none of them, because every XML and HTML
 * parser on the JVM resolves external entities and processes DTDs by default and reading a
 * document's own declarations is precisely how XXE and billion-laughs attacks arrive. The gap
 * is recorded in the standard-library inventory rather than filled badly.
 *
 * <p>What is genuinely missing without this module is one line that turns a value into
 * something safe to put in a page. The alternative a program has today is to hand-roll the
 * replacement chain, and a hand-rolled chain is where the <em>order</em> bug lives: replacing
 * {@code <} before {@code &} double-escapes every already-escaped entity. Doing it here means
 * doing it once, correctly, and testing it once.
 *
 * <p>Escaping is deliberately <strong>not idempotent</strong>. Escaping an already-escaped
 * string escapes the ampersands of its entities, so {@code html_escape(html_escape(x))} is
 * visibly different from {@code html_escape(x)}. That is the correct behaviour and the
 * documented one: an escape that quietly recognised existing entities could not be used to
 * write text containing the literal characters {@code &amp;}, and every such escape is a
 * smuggling route. A caller who needs the other behaviour has {@code html_unescape} and can
 * say which one it means.
 *
 * <p>Escaping is never applied implicitly, and no function here writes a page, infers a
 * context or guesses a charset. A string is text; escaping it is an explicit call.
 */
final class HtmlText {
    private HtmlText() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("html_escape", 1, (host, args, span) ->
                escape(text(args.get(0), "html_escape", span))).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("html_unescape", 1, (host, args, span) ->
                unescape(text(args.get(0), "html_unescape", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("html_escape_attribute", 1, (host, args, span) ->
                escapeAttribute(text(args.get(0), "html_escape_attribute", span)))
                .named("text"));
        interpreter.defineBuiltin(NativeFunction.of("html_entity", 1, (host, args, span) ->
                entity(text(args.get(0), "html_entity", span), span)).named("name"));
    }

    /**
     * Every entity this module knows, as name to character, in the order a reader expects.
     *
     * <p>One table, not two. {@link #TEXT} is derived from it, the unescape lookup reads it
     * directly, and {@code html_entity} answers from either key or value — so the three
     * surfaces cannot disagree about which entities exist or what they mean. A second
     * hand-written reverse table is the kind of duplication that stays correct right up until
     * someone adds an entity to one of them.
     */
    private static final Map<String, String> NAMED = new LinkedHashMap<>();

    static {
        NAMED.put("amp", "&");
        NAMED.put("lt", "<");
        NAMED.put("gt", ">");
        NAMED.put("quot", "\"");
        NAMED.put("apos", "'");
        NAMED.put("nbsp", "\u00a0");
    }

    /**
     * Character to entity, derived from {@link #NAMED}.
     *
     * <p>The mapping is the identity of the table, not a separate decision, so it is computed
     * rather than maintained: change {@code NAMED} and every function follows.
     */
    private static final Map<Character, String> TEXT = textForms();

    private static Map<Character, String> textForms() {
        Map<Character, String> map = new LinkedHashMap<>();
        NAMED.forEach((name, character) -> map.put(character.charAt(0), "&" + name + ";"));
        return map;
    }

    /**
     * Escapes the five significant characters.
     *
     * <p>Order is the whole correctness argument: {@code &} is replaced first, so the
     * ampersands introduced by the four later replacements are not themselves replaced. Getting
     * this backwards produces {@code &amp;lt;} for {@code <}, which renders as the literal text
     * {@code &lt;} instead of {@code <} — a bug that looks like working software until someone
     * reads the page.
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String replacement = TEXT.get(c);
            if (replacement == null) out.append(c);
            else out.append(replacement);
        }
        return out.toString();
    }

    /**
     * Escapes for an attribute value, which is the same operation under this module's rules.
     *
     * <p>It exists as its own name so a reader of calling code can see which context the string
     * is destined for, and so that a future decision to diverge — for example to refuse
     * control characters an attribute may not carry — has a place to land without changing
     * {@code html_escape}. It is not currently a stricter function than {@code html_escape},
     * and the test that asserts they agree is what keeps that statement honest.
     */
    static String escapeAttribute(String text) {
        return escape(text);
    }

    /**
     * Reverses {@code html_escape} and resolves the small named table plus numeric entities.
     *
     * <p>Deliberately strict where it matters: a malformed numeric entity, an out-of-range code
     * point, or a name that is not in {@link #NAMED} is refused rather than passed through.
     * Passing it through would mean an unescape that silently produces a different string than
     * the caller asked for, and a caller unescaping untrusted input needs to know which of its
     * assumptions held. A bare {@code &} that starts no entity at all is ordinary text and is
     * kept, because "always" is not what HTML says and refusing it would make the function
     * unusable on prose that merely contains an ampersand.
     */
    static String unescape(String text, Span span) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = text.indexOf(';', i + 1);
            if (semi < 0) {
                // No terminator anywhere ahead: an ampersand in ordinary prose.
                out.append(c);
                i++;
                continue;
            }
            String body = text.substring(i + 1, semi);
            if (!isEntityShaped(body)) {
                // The span from `&` to the next `;` is not shaped like an entity at all, so the
                // ampersand is prose. Without this, "Tom & Jerry; the cartoon" would be read as
                // an entity named " Jerry" and refused, which would make the function unusable
                // on ordinary prose merely because a semicolon appeared later in the sentence.
                out.append(c);
                i++;
                continue;
            }
            String resolved = body.charAt(0) == '#'
                    ? numeric(body, span)
                    : NAMED.get(body);
            if (resolved == null) {
                throw new OqError("html_unescape() does not know the entity \"&" + body
                        + ";\"", span,
                        "Only the named entities in the standard library's table and numeric "
                                + "&#NNN; / &#xHHH; forms are recognised. Use html_escape() to "
                                + "see what this module produces.");
            }
            out.append(resolved);
            i = semi + 1;
        }
        return out.toString();
    }

    /**
     * Resolves {@code #NNN} and {@code #xHHH}, refusing anything that is not a valid scalar.
     *
     * <p>Surrogates are refused in addition to the range check. A lone surrogate cannot be
     * encoded, so a function that returned one would hand back a string that fails later and
     * elsewhere, when it is written out — the least debuggable shape a failure can take.
     */
    /**
     * Whether {@code body} has the shape of an entity name or numeric reference.
     *
     * <p>This is the decision that keeps strictness from turning into brittleness. An attempted
     * entity is refused when its digits or name are wrong; a stray ampersand followed later by
     * an unrelated semicolon is not an attempt at all. The shape test is what separates them,
     * because a space or a comma inside the candidate is proof that the author was writing
     * prose rather than markup.
     */
    private static boolean isEntityShaped(String body) {
        if (body.isEmpty()) return false;
        if (body.charAt(0) == '#') {
            // A leading `#` is an unambiguous attempt at a numeric reference, so an empty or
            // malformed digit run is reported by numeric() rather than treated as prose.
            int i = 1;
            if (i < body.length() && (body.charAt(i) == 'x' || body.charAt(i) == 'X')) i++;
            for (int j = i; j < body.length(); j++) {
                if (!Character.isLetterOrDigit(body.charAt(j))) return false;
            }
            return true;
        }
        for (int i = 0; i < body.length(); i++) {
            if (!Character.isLetterOrDigit(body.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Resolves {@code #NNN} and {@code #xHHH}, refusing anything that is not a valid scalar.
     *
     * <p>Surrogates are refused in addition to the range check. A lone surrogate cannot be
     * encoded, so a function that returned one would hand back a string that fails later and
     * elsewhere, when it is written out — the least debuggable shape a failure can take.
     */
    private static String numeric(String body, Span span) {
        int radix = 10;
        String digits = body.substring(1);
        if (!digits.isEmpty() && (digits.charAt(0) == 'x' || digits.charAt(0) == 'X')) {
            radix = 16;
            digits = digits.substring(1);
        }
        if (digits.isEmpty()) {
            throw new OqError("html_unescape() found the numeric entity \"&" + body
                    + ";\" with no digits", span);
        }
        int codePoint;
        try {
            codePoint = Integer.parseInt(digits, radix);
        } catch (NumberFormatException e) {
            throw new OqError("html_unescape() found the numeric entity \"&" + body
                    + ";\" whose digits are not valid base " + radix, span);
        }
        if (!Character.isValidCodePoint(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            throw new OqError("html_unescape() found the numeric entity \"&" + body
                    + ";\" which is not a Unicode scalar value", span,
                    "Surrogate code points U+D800..U+DFFF cannot stand alone and are refused "
                            + "rather than returned as a string that fails when written.");
        }
        return new String(Character.toChars(codePoint));
    }

    /**
     * The name of an entity, given either its character or its bare name.
     *
     * <p>Exists so a caller can build markup without writing the table itself, and so the table
     * has exactly one public expression. Input may be a single character ({@code "&"}) or the
     * name ({@code "amp"}); both answer {@code "&amp;"}. A character with no entity is refused
     * rather than returned unchanged, because returning it unchanged would make
     * {@code html_entity("a")} look like a success that produced markup.
     */
    static String entity(String name, Span span) {
        if (name.codePointCount(0, name.length()) == 1) {
            String replacement = TEXT.get(name.charAt(0));
            if (replacement != null) return replacement;
            throw new OqError("html_entity() has no entity for the character \"" + name + "\"",
                    span, "The escaped characters are & < > \" ' and the no-break space.");
        }
        if (NAMED.containsKey(name)) return "&" + name + ";";
        throw new OqError("html_entity() does not know the entity name \"" + name + "\"", span,
                "The named entities are amp, lt, gt, quot, apos and nbsp.");
    }

    private static String text(Object value, String builtin, Span span) {
        if (value instanceof String s) return s;
        throw new OqError(builtin + "() needs text, got " + Values.typeName(value), span);
    }
}
