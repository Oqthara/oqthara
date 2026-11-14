package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV and date parsing, per ADR-0035.
 *
 * <p>Every case here was first run through the built CLI and the expected value copied from what
 * it printed. The round-trip test is the one that matters: a writer and a reader that agree with
 * each other and not with the format is the failure this file exists to catch.
 */
class CsvAndDatesTest {

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), Path.of("."), new MethodResolverImpl());
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

    @SuppressWarnings("unchecked")
    private List<List<String>> parse(Object... args) {
        return (List<List<String>>) (List<?>) call("csv_parse", args);
    }

    // --- CSV reading -------------------------------------------------------------------------

    @Test
    void plainFieldsSplitOnTheDelimiter() {
        assertEquals(List.of(List.of("a", "b", "c")), parse("a,b,c"));
    }

    @Test
    void aQuotedFieldMayContainTheDelimiter() {
        assertEquals(List.of(List.of("a", "b,c")), parse("a,\"b,c\""));
        assertEquals(List.of(List.of("a", ",b")), parse("a,\",b\""),
                "a leading delimiter inside quotes is data, which splitting cannot know");
    }

    @Test
    void aDoubledQuoteInsideAQuotedFieldIsOneQuote() {
        assertEquals(List.of(List.of("a", "he said \"hi\"")), parse("a,\"he said \"\"hi\"\"\""));
    }

    @Test
    void aQuotedFieldMayContainALineBreak() {
        assertEquals(List.of(List.of("a", "w\nq")), parse("a,\"w\nq\""),
                "the unit of CSV is the row, not the line");
    }

    @Test
    void CRLFEndsARowLikeLFDpc() {
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), parse("a,b\r\n1,2\r\n"),
                "a trailing line break ends the last row rather than adding an empty one");
    }

    @Test
    void theDelimiterCanBeChanged() {
        assertEquals(List.of(List.of("a", "b")), parse("a;b", ";"));
    }

    // --- CSV reading errors ------------------------------------------------------------------

    @Test
    void anUnterminatedQuotedFieldIsRefused() {
        OqError error = assertThrows(OqError.class, () -> parse("a,\"b"));
        assertTrue(error.getMessage().contains("inside a quoted field"), error.getMessage());
        assertTrue(error.hint().contains("closing quote"), "the hint says what to do: " + error.hint());
    }

    @Test
    void aQuoteInsideAnUnquotedFieldIsRefused() {
        OqError error = assertThrows(OqError.class, () -> parse("a\"b,c"));
        assertTrue(error.getMessage().contains("unquoted field"), error.getMessage());
    }

    @Test
    void textAfterAClosingQuoteIsRefused() {
        OqError error = assertThrows(OqError.class, () -> parse("a,\"b\"c,d"));
        assertTrue(error.getMessage().contains("after a closing quote"), error.getMessage());
    }

    @Test
    void aMultiCharacterDelimiterIsRefused() {
        OqError error = assertThrows(OqError.class, () -> parse("a,b,c", ";;"));
        assertTrue(error.getMessage().contains("exactly one character"), error.getMessage());
    }

    @Test
    void aQuoteOrLineBreakCannotBeTheDelimiter() {
        assertThrows(OqError.class, () -> parse("a,b", "\""));
        assertThrows(OqError.class, () -> parse("a,b", "\n"));
    }

    // --- CSV comments ------------------------------------------------------------------------

    @Test
    void aLeadingHashIsDataUnlessCommentsAreAskedFor() {
        assertEquals(List.of(List.of("# note"), List.of("a", "b")), parse("# note\na,b"),
                "dropping a row for anyone whose first column holds a part number is worse");
        assertEquals(List.of(List.of("a", "b")), parse("# note\na,b", ",", true));
    }

    @Test
    void aQuotedHashIsDataEvenWhenCommentsAreOn() {
        assertEquals(List.of(List.of("#keep", "b")), parse("\"#keep\",b", ",", true),
                "the caller quoted it, so the caller meant it");
    }

    // --- CSV writing -------------------------------------------------------------------------

    @Test
    void onlyFieldsThatNeedQuotingAreQuoted() {
        assertEquals("a,b\n", call("csv_write", List.of(List.of("a", "b"))));
        assertEquals("a,\"b,c\"\n", call("csv_write", List.of(List.of("a", "b,c"))));
        assertEquals("d,\"e\"\"f\"\n", call("csv_write", List.of(List.of("d", "e\"f"))));
    }

    @Test
    void writingThenReadingGivesBackTheSameRows() {
        List<List<String>> rows = List.of(
                List.of("plain", "with,comma", "with\"quote", "with\nnewline"));
        Object written = call("csv_write", rows);
        assertEquals(rows, parse(written));
    }

    @Test
    void csvWriteRefusesSomethingThatIsNotARowOfFields() {
        assertThrows(OqError.class, () -> call("csv_write", "a,b"));
        assertThrows(OqError.class, () -> call("csv_write", List.of("a", "b")));
    }

    // --- Dates -------------------------------------------------------------------------------

    @Test
    void aDateParsesToTheSameShapeTodayProduces() {
        assertEquals("2026-11-10", call("date_parse", "2026-11-10"));
    }

    @Test
    void anInstantParsesToTheSameShapeNowProduces() {
        assertEquals("2026-11-10T13:58:06Z", call("instant_parse", "2026-11-10T13:58:06Z"));
        assertEquals("2026-11-10T11:58:06Z", call("instant_parse", "2026-11-10T13:58:06+02:00"),
                "an explicit offset is converted, not discarded");
    }

    @Test
    void anUnparseableDateIsAnErrorRatherThanNothing() {
        OqError date = assertThrows(OqError.class, () -> call("date_parse", "10/11/2026"));
        assertTrue(date.getMessage().contains("10/11/2026"), date.getMessage());

        OqError instant = assertThrows(OqError.class,
                () -> call("instant_parse", "2026-11-10"));
        // The message names the input; the hint explains that a zone is not invented for it.
        assertTrue(instant.getMessage().contains("2026-11-10"), instant.getMessage());
        assertTrue(instant.hint().contains("offset"),
                "a date with no offset is not silently given one: " + instant.hint());
    }

    // --- The delimiter is one code point (#157) ---------------------------

    @Test
    void aSupplementaryDelimiterIsOneCharacter() {
        // U+1F600 is one character (ADR-0024) but two UTF-16 units; rejecting it on length
        // contradicted the language's own text model.
        assertEquals(List.of(List.of("left", "right")),
                parse("left\uD83D\uDE00right", "\uD83D\uDE00"));
        // csv_write always terminates the row, so the newline is part of the contract.
        assertEquals("left\uD83D\uDE00right\n",
                call("csv_write", List.of(List.of("left", "right")), "\uD83D\uDE00"));
    }

    @Test
    void aSupplementaryDelimiterIsQuotedWhenItAppearsInData() {
        String emoji = "\uD83D\uDE00";
        String written = (String) call("csv_write", List.of(List.of("a" + emoji + "b")), emoji);
        assertEquals("\"a" + emoji + "b\"\n", written);
        assertEquals(List.of(List.of("a" + emoji + "b")), parse(written, emoji));
    }

    // --- JSON round trip ---------------------------------------------------------------------

    @Test
    void whatJsonWriteProducesJsonParseReadsBack() {
        // json_write shipped with no unit test at all -- only end-to-end CLI tests that never
        // called it -- so a round-trip failure would have been invisible here.
        String written = (String) call("json_write", call("json_parse", "{\"a\": [1, 2], \"b\": \"x\"}"));
        assertEquals(call("json_parse", written), call("json_parse", "{\"a\": [1, 2], \"b\": \"x\"}"),
                "write then read must reproduce the value");
    }

    @Test
    void jsonWriteIsCompactAndKeepsKeyOrder() {
        OqMap value = new OqMap();
        // Insertion order is the observable contract: a map that reordered its keys would emit
        // a different document for the same value on every run.
        value.put("b", 1L);
        value.put("a", 2L);
        assertEquals("{\"b\":1,\"a\":2}", call("json_write", value),
                "no spaces, and the order the keys were inserted");
    }

    @Test
    void jsonWriteRendersNothingAsNullAndAStringAsAQuotedString() {
        // Cast, not a bare null: a lone null is the varargs *array*, not a one-element list.
        assertEquals("null", call("json_write", (Object) null));
        assertEquals("\"plain\"", call("json_write", "plain"));
    }

    @Test
    void aMultiCharacterDelimiterIsStillRejected() {
        OqError error = assertThrows(OqError.class, () -> parse("a,b", ",,"));
        assertTrue(error.getMessage().contains("exactly one character"), error.getMessage());
        // Two supplementary characters are two characters, not one.
        OqError two = assertThrows(OqError.class,
                () -> parse("a", "\uD83D\uDE00\uD83D\uDE00"));
        assertTrue(two.getMessage().contains("exactly one character"), two.getMessage());
    }
}
