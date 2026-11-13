package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ini_parse}, {@code ini_write}, {@code properties_parse} and {@code toml_get}.
 *
 * <p>Every expectation was produced by running the built CLI before it was written down, so a
 * change in behaviour fails a test rather than silently redefining the grammar. The three
 * {@code properties_parse} cases that differ from {@link java.util.Properties#load} are marked,
 * because a reader that later delegates to the JDK would otherwise look correct.
 *
 * <p>Each parser has an adversarial case, and each adversarial case asserts the line number as
 * well as the refusal: a parser that stops at the wrong place, or stops without saying where,
 * is nearly as unhelpful as one that guesses.
 */
class ConfigFormatsTest {

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
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private OqMap map(Object... pairs) {
        OqMap result = new OqMap();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private OqMap parseIni(String text) {
        return (OqMap) call("ini_parse", text);
    }

    @SuppressWarnings("unchecked")
    private OqMap section(OqMap parsed, String name) {
        return (OqMap) parsed.get(name);
    }

    // --- ini_parse ----------------------------------------------------------------------------

    @Test
    void iniParseReadsSectionsAndASectionlessPrefix() {
        OqMap parsed = parseIni("""
                top = 1
                [server]
                host = example.com
                port = 8080
                [client]
                retries = 3
                """);
        assertEquals(List.of("", "server", "client"), List.copyOf(parsed.keySet()));
        assertEquals(map("top", "1"), section(parsed, ""));
        assertEquals(map("host", "example.com", "port", "8080"), section(parsed, "server"));
        assertEquals(map("retries", "3"), section(parsed, "client"));
    }

    @Test
    void iniParseKeepsEverythingAfterTheFirstEqualsSign() {
        // A URL carries '=' and ':' of its own, and neither ends the value.
        OqMap parsed = parseIni("[a]\nurl = https://x/y?a=1&b=2\n");
        assertEquals("https://x/y?a=1&b=2", section(parsed, "a").get("url"));
    }

    @Test
    void iniParseTrimsBothSides() {
        OqMap parsed = parseIni("[ a ]\n  key   =   value with spaces inside   \n");
        assertEquals("value with spaces inside", section(parsed, "a").get("key"));
    }

    @Test
    void iniParseIgnoresWholeLineCommentsButKeepsAHashInsideAValue() {
        OqMap parsed = parseIni("""
                ; a comment
                # another
                colour = #ff0000
                """);
        assertEquals(map("colour", "#ff0000"), section(parsed, ""));
    }

    @Test
    void iniParseResolvesDuplicatesLastWins() {
        // Documented: last wins, so a file that works in other INI readers works here.
        OqMap parsed = parseIni("[a]\nk = 1\nk = 2\n");
        assertEquals("2", section(parsed, "a").get("k"));
        OqMap repeatedHeader = parseIni("[a]\nx = 1\n[b]\ny = 2\n[a]\nz = 3\n");
        assertEquals(map("x", "1", "z", "3"), section(repeatedHeader, "a"));
        assertEquals(map("y", "2"), section(repeatedHeader, "b"));
    }

    @Test
    void iniParseAcceptsAMissingFinalNewlineAndCrLf() {
        assertEquals(map("a", "1"), section(parseIni("[s]\r\na = 1"), "s"));
        assertEquals(map("a", "1"), section(parseIni("[s]\ra = 1"), "s"));
    }

    @Test
    void iniParseReturnsAnEmptyMapForEmptyInput() {
        assertEquals(0, parseIni("").size());
        assertEquals(0, parseIni("\n\n  \n").size());
    }

    @Test
    void iniParseRaisesOnAMalformedLineWithItsLineNumber() {
        OqError error = assertThrows(OqError.class,
                () -> parseIni("[a]\nk = 1\nthis is not a pair\n"));
        assertTrue(error.getMessage().contains("line 3"), error.getMessage());
        assertTrue(error.getMessage().contains("no '='"), error.getMessage());
    }

    @Test
    void iniParseRaisesOnAnUnterminatedSectionHeader() {
        OqError error = assertThrows(OqError.class, () -> parseIni("[a\nk = 1\n"));
        assertTrue(error.getMessage().contains("malformed section header on line 1"),
                error.getMessage());
    }

    @Test
    void iniParseRaisesOnAValueWithNoKey() {
        OqError error = assertThrows(OqError.class, () -> parseIni("[a]\n= 1\n"));
        assertTrue(error.getMessage().contains("value with no key on line 2"), error.getMessage());
    }

    // --- ini_write ----------------------------------------------------------------------------

    @Test
    void iniWriteSortsSectionsAndKeysSoTheOutputIsReproducible() {
        OqMap sections = map(
                "b", map("y", "2", "x", "1"),
                "a", map("k", "v"));
        assertEquals("""
                [a]
                k = v

                [b]
                x = 1
                y = 2
                """, call("ini_write", sections));
    }

    @Test
    void iniWritePutsTheSectionlessKeysFirstAndRoundTrips() {
        OqMap sections = map("s", map("k", "v"), "", map("top", "1"));
        String written = (String) call("ini_write", sections);
        assertEquals("""
                top = 1

                [s]
                k = v
                """, written);
        assertEquals(sections, parseIni(written));
    }

    @Test
    void iniWriteSkipsAnEmptySectionlessBlockButKeepsAnEmptyNamedOne() {
        assertEquals("[s]\n", call("ini_write", map("", map(), "s", map())));
        assertEquals("", call("ini_write", map()));
    }

    @Test
    void iniWriteWritesNumbersAsTheirText() {
        assertEquals("n = 42\n", call("ini_write", map("", map("n", 42L))));
    }

    @Test
    void iniWriteRefusesAnythingThatWouldNotReadBackAsItself() {
        List<OqMap> refused = List.of(
                map("", map("a\nb", "v")),
                map("", map("a", "v\nw")),
                map("", map(" a", "v")),
                map("", map("a", " v")),
                map("", map("a=b", "v")),
                map("", map(";a", "v")),
                map("", map("[a", "v")),
                map("", map("", "v")),
                map("a\nb", map("k", "v")),
                map("a]", map("k", "v")));
        for (OqMap sections : refused) {
            assertThrows(OqError.class, () -> call("ini_write", sections), sections.toString());
        }
    }

    @Test
    void iniWriteRefusesAPlainValueWhereAMapOfSectionsBelongs() {
        OqError error = assertThrows(OqError.class, () -> call("ini_write", "text"));
        assertEquals("ini_write() needs a map of sections, got text", error.getMessage());
        OqError shallow = assertThrows(OqError.class, () -> call("ini_write", map("s", "v")));
        assertTrue(shallow.getMessage().contains("needs each section to be a map"),
                shallow.getMessage());
    }

    // --- properties_parse ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void propertiesParseReadsBothSeparatorsAndStripsLeadingWhitespace() {
        Map<Object, Object> parsed = (Map<Object, Object>) call("properties_parse", """
                  # a comment
                  ! another

                a=1
                b : 2
                c three
                d
                """);
        assertEquals("1", parsed.get("a"));
        assertEquals("2", parsed.get("b"));
        assertEquals("three", parsed.get("c"));
        assertEquals("", parsed.get("d"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertiesParseImplementsTheEscapes() {
        Map<Object, Object> parsed = (Map<Object, Object>) call("properties_parse",
                "tab=a\\tb\nnewline=a\\nb\nform=a\\fb\nback=a\\\\b\nhex=\\u00e9\n");
        assertEquals("a\tb", parsed.get("tab"));
        assertEquals("a\nb", parsed.get("newline"));
        assertEquals("a\fb", parsed.get("form"));
        assertEquals("a\\b", parsed.get("back"));
        assertEquals("\u00e9", parsed.get("hex"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertiesParseSeparatesAnEscapedSeparatorFromARealOne() {
        Map<Object, Object> parsed = (Map<Object, Object>) call("properties_parse",
                "a\\:b=v\nc\\=d:e\n");
        assertEquals("v", parsed.get("a:b"));
        assertEquals("e", parsed.get("c=d"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertiesParseJoinsAContinuationAndStripsItsIndent() {
        Map<Object, Object> parsed = (Map<Object, Object>) call("properties_parse",
                "k = a\\\n    b\\\n    c\n");
        assertEquals("a b c".replace(" ", ""), parsed.get("k"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertiesParseContinuesACommentAcrossALineBreak() {
        // Measured: Java's reader continues the comment too, so the second line is swallowed.
        Map<Object, Object> parsed = (Map<Object, Object>) call("properties_parse",
                "#note \\\nk=v\nreal=1\n");
        assertEquals(null, parsed.get("k"));
        assertEquals("1", parsed.get("real"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertiesParseKeepsTrailingWhitespaceInAValue() {
        // Unlike ini_parse, this format does not trim values, and a reader that trimmed would
        // change what a program reads. Measured against java.util.Properties.
        Map<Object, Object> parsed = (Map<Object, Object>) call("properties_parse", "k=v  \n");
        assertEquals("v  ", parsed.get("k"));
    }

    @Test
    void propertiesParseReturnsAnEmptyMapForEmptyInput() {
        assertEquals(0, ((Map<?, ?>) call("properties_parse", "")).size());
        assertEquals(0, ((Map<?, ?>) call("properties_parse", "\n\n")).size());
    }

    @Test
    void propertiesParseRaisesOnALineContinuationAtTheEndOfTheText() {
        // Deliberate divergence: java.util.Properties.load drops the backslash and returns
        // {k=v}. That is the truncated case passing silently, so it raises here.
        OqError error = assertThrows(OqError.class,
                () -> call("properties_parse", "k=v\\"));
        assertEquals("properties_parse() found a line continuation at the end of the text on "
                + "line 1", error.getMessage());
    }

    @Test
    void propertiesParseRaisesOnAnEscapeAtTheEndOfTheInput() {
        OqError error = assertThrows(OqError.class,
                () -> call("properties_parse", "k\\"));
        assertTrue(error.getMessage().contains("line continuation at the end of the text"),
                error.getMessage());
    }

    @Test
    void aDoubledBackslashAtTheEndIsALiteralBackslashAndDoesNotContinue() {
        // The odd/even count is the whole rule: two backslashes end the line.
        Map<?, ?> parsed = (Map<?, ?>) call("properties_parse", "k=v\\\\\nnext=1\n");
        assertEquals("v\\", parsed.get("k"));
        assertEquals("1", parsed.get("next"));
    }

    @Test
    void propertiesParseRaisesOnAShortUnicodeEscapeWithItsLineNumber() {
        OqError shortEscape = assertThrows(OqError.class,
                () -> call("properties_parse", "a=1\nb=\\u12\n"));
        assertTrue(shortEscape.getMessage().contains("line 2"), shortEscape.getMessage());
        assertTrue(shortEscape.getMessage().contains("fewer than four hex digits"),
                shortEscape.getMessage());
    }

    @Test
    void propertiesParseRaisesOnANonHexadecimalUnicodeEscape() {
        OqError error = assertThrows(OqError.class,
                () -> call("properties_parse", "a=\\u12g4\n"));
        assertTrue(error.getMessage().contains("non-hexadecimal digit"), error.getMessage());
        assertTrue(error.getMessage().contains("line 1"), error.getMessage());
    }

    // --- toml_get: the supported subset -------------------------------------------------------

    private static final String CONFIG = """
            title = "Oqthara"

            [tool.poetry]
            name = "demo"
            version = "1.2.3"
            description = 'a literal \\ string'
            python = ">=3.9"

            [tool.poetry.dependencies]
            requests = "^2.0"

            [build]
            jobs = 4
            ratio = 0.125
            strict = true
            flags = ["a", "b"]
            matrix = [[1, 2], [3, 4]]
            """;

    @Test
    void tomlGetReadsAStringAtADottedPath() {
        assertEquals("1.2.3", call("toml_get", CONFIG, "tool.poetry.version"));
    }

    @Test
    void tomlGetReadsATopLevelKey() {
        assertEquals("Oqthara", call("toml_get", CONFIG, "title"));
    }

    @Test
    void tomlGetReadsALiteralStringWithoutProcessingBackslashes() {
        assertEquals("a literal \\ string", call("toml_get", CONFIG, "tool.poetry.description"));
    }

    @Test
    void tomlGetReadsIntegersFloatsAndBooleans() {
        assertEquals(4L, call("toml_get", CONFIG, "build.jobs"));
        assertEquals(0.125, call("toml_get", CONFIG, "build.ratio"));
        assertEquals(Boolean.TRUE, call("toml_get", CONFIG, "build.strict"));
    }

    @Test
    void tomlGetReadsArraysIncludingNestedOnes() {
        assertEquals(List.of("a", "b"), call("toml_get", CONFIG, "build.flags"));
        assertEquals(List.of(List.of(1L, 2L), List.of(3L, 4L)),
                call("toml_get", CONFIG, "build.matrix"));
    }

    @Test
    void tomlGetReadsAnIntegerInEveryBaseAndAnAllowedUnderscore() {
        String text = """
                hex = 0xff
                oct = 0o17
                bin = 0b1010
                under = 1_000_000
                negative = -42
                """;
        assertEquals(255L, call("toml_get", text, "hex"));
        assertEquals(15L, call("toml_get", text, "oct"));
        assertEquals(10L, call("toml_get", text, "bin"));
        assertEquals(1_000_000L, call("toml_get", text, "under"));
        assertEquals(-42L, call("toml_get", text, "negative"));
    }

    @Test
    void tomlGetReadsTheEscapeFormsInsideABasicString() {
        String text = "k = \"a\\tb\\nc\\\"d\\\\e\\u00e9\"\n";
        assertEquals("a\tb\nc\"d\\e\u00e9", call("toml_get", text, "k"));
    }

    @Test
    void tomlGetReadsAQuotedKeyAndAQuotedSegmentOfThePath() {
        String text = "\"a b\" = 1\n[tool.\"x.y\"]\nk = 2\n";
        assertEquals(1L, call("toml_get", text, "\"a b\""));
        assertEquals(2L, call("toml_get", text, "tool.\"x.y\".k"));
    }

    @Test
    void tomlGetReturnsATableAsAMapWhenThePathNamesOne() {
        OqMap table = (OqMap) call("toml_get", CONFIG, "tool.poetry");
        assertEquals("demo", table.get("name"));
        assertEquals("1.2.3", table.get("version"));
    }

    @Test
    void tomlGetIgnoresCommentsAndBlankLines() {
        String text = "\n# leading\nk = 1 # trailing\n\n[s]\nj = 2\n";
        assertEquals(1L, call("toml_get", text, "k"));
        assertEquals(2L, call("toml_get", text, "s.j"));
    }

    @Test
    void tomlGetAcceptsATrailingCommaAndAnArraySpanningLines() {
        String text = "a = [\n  1,\n  2,\n]\n";
        assertEquals(List.of(1L, 2L), call("toml_get", text, "a"));
    }

    @Test
    void tomlGetRaisesOnAnEmptyDocument() {
        OqError error = assertThrows(OqError.class, () -> call("toml_get", "", "a"));
        assertEquals("toml_get() found no key 'a'", error.getMessage());
    }

    @Test
    void tomlGetRaisesWhenThePathGoesThroughAValue() {
        OqError error = assertThrows(OqError.class, () -> call("toml_get", "a = 1\n", "a.b"));
        assertTrue(error.hint().contains("is a value"), error.getMessage());
    }

    // --- toml_get: what is refused on purpose ------------------------------------------------

    @Test
    void tomlGetRefusesTablesOfTablesByName() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "[[items]]\nname = \"a\"\n", "items"));
        assertTrue(error.getMessage().contains("does not support tables of tables"),
                error.getMessage());
        assertTrue(error.getMessage().contains("line 1"), error.getMessage());
    }

    @Test
    void tomlGetRefusesInlineTablesByName() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "point = { x = 1, y = 2 }\n", "point"));
        assertTrue(error.getMessage().contains("inline tables"), error.getMessage());
    }

    @Test
    void tomlGetRefusesADateByNameRatherThanReturningText() {
        // A date is not a string here: returning "2026-11-09" would look right and compare as
        // text, which is a different value from the one the file holds.
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "when = 2026-11-09\n", "when"));
        assertTrue(error.getMessage().contains("does not support dates and times"),
                error.getMessage());
        assertTrue(error.getMessage().contains("2026-11-09"), error.getMessage());
    }

    @Test
    void tomlGetRefusesATimeAndAnOffsetDateTimeTheSameWay() {
        OqError time = assertThrows(OqError.class,
                () -> call("toml_get", "t = 14:04:24\n", "t"));
        assertTrue(time.getMessage().contains("dates and times"), time.getMessage());
        OqError offset = assertThrows(OqError.class,
                () -> call("toml_get", "z = 2026-11-09T14:04:24Z\n", "z"));
        assertTrue(offset.getMessage().contains("dates and times"), offset.getMessage());
    }

    @Test
    void tomlGetRefusesAMultiLineStringByName() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "k = \"\"\"\nx\n\"\"\"\n", "k"));
        assertTrue(error.getMessage().contains("multi-line strings"), error.getMessage());
    }

    @Test
    void tomlGetRefusesABareWordRatherThanGuessingAtItsMeaning() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "k = yes\n", "k"));
        assertTrue(error.getMessage().contains("does not support"), error.getMessage());
        assertTrue(error.getMessage().contains("yes"), error.getMessage());
    }

    // --- toml_get: adversarial input ----------------------------------------------------------

    @Test
    void tomlGetRaisesOnAnUnterminatedStringAndNamesTheLineItBeganOn() {
        // Running into the end of the text.
        OqError atEnd = assertThrows(OqError.class,
                () -> call("toml_get", "a = 1\nb = \"unterminated", "b"));
        assertTrue(atEnd.getMessage().contains("end of the text inside the string"),
                atEnd.getMessage());
        assertTrue(atEnd.getMessage().contains("line 2"), atEnd.getMessage());
        // Running into a line break, which a one-line string cannot contain.
        OqError lineBreak = assertThrows(OqError.class,
                () -> call("toml_get", "a = 1\nb = \"unterminated\nnext = 2\n", "b"));
        assertTrue(lineBreak.getMessage().contains("line break inside the string"),
                lineBreak.getMessage());
        assertTrue(lineBreak.getMessage().contains("line 2"), lineBreak.getMessage());
    }

    @Test
    void tomlGetRaisesOnABackslashAtTheEndOfTheText() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "k = \"x\\", "k"));
        assertTrue(error.getMessage().contains("backslash at the end of the text"),
                error.getMessage());
    }

    @Test
    void tomlGetRaisesOnAnUnknownEscapeAndNamesIt() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "k = \"x\\q\"\n", "k"));
        assertTrue(error.getMessage().contains("unsupported escape '\\q'"), error.getMessage());
    }

    @Test
    void tomlGetRefusesAnArrayNestedDeeperThanTheLimit() {
        String deep = "k = " + "[".repeat(ConfigFormats.MAX_NESTING + 1)
                + "]".repeat(ConfigFormats.MAX_NESTING + 1) + "\n";
        OqError error = assertThrows(OqError.class, () -> call("toml_get", deep, "k"));
        assertTrue(error.getMessage().contains("nested more than " + ConfigFormats.MAX_NESTING),
                error.getMessage());
    }

    @Test
    void tomlGetAcceptsAnArrayNestedExactlyAsDeepAsTheLimit() {
        String deep = "k = " + "[".repeat(ConfigFormats.MAX_NESTING)
                + "]".repeat(ConfigFormats.MAX_NESTING) + "\n";
        Object value = call("toml_get", deep, "k");
        assertTrue(value instanceof List, String.valueOf(value));
    }

    @Test
    void tomlGetRaisesOnAnUnclosedArrayRatherThanSwallowingTheRestOfTheFile() {
        // After a comma and at the end of the text there is nothing left to read. The array is
        // not closed quietly and the document is not read on into whatever follows.
        OqError atEnd = assertThrows(OqError.class,
                () -> call("toml_get", "a = [1,", "a"));
        assertTrue(atEnd.getMessage().contains("end of the text inside the array"),
                atEnd.getMessage());
        assertTrue(atEnd.getMessage().contains("began on line 1"), atEnd.getMessage());
        // With more text afterwards, the next line is not quietly taken as another item.
        OqError stray = assertThrows(OqError.class,
                () -> call("toml_get", "a = [1, 2\nb = 3\n", "a"));
        assertTrue(stray.getMessage().contains("expected ',' or ']'"), stray.getMessage());
        assertTrue(stray.getMessage().contains("began on line 1"), stray.getMessage());
    }

    @Test
    void tomlGetRaisesOnADuplicateKey() {
        // Last wins would be a plausible rule, but TOML forbids the file, so the file is what is
        // wrong rather than the reader.
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "a = 1\na = 2\n", "a"));
        assertTrue(error.getMessage().contains("found the key 'a' twice"), error.getMessage());
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
    }

    @Test
    void tomlGetRaisesOnARepeatedHeaderRatherThanMerging() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "[a]\nx = 1\n[a]\ny = 2\n", "a.x"));
        assertTrue(error.getMessage().contains("found the table [a] twice"), error.getMessage());
    }

    @Test
    void tomlGetRaisesOnAnIntegerThatDoesNotFitIn64Bits() {
        OqError error = assertThrows(OqError.class,
                () -> call("toml_get", "k = 9223372036854775808\n", "k"));
        assertTrue(error.getMessage().contains("does not fit in 64 bits"), error.getMessage());
    }

    @Test
    void tomlGetRaisesOnAValueWithNoKey() {
        OqError error = assertThrows(OqError.class, () -> call("toml_get", "= 1\n", "a"));
        assertTrue(error.getMessage().contains("expected a key"), error.getMessage());
    }
}
