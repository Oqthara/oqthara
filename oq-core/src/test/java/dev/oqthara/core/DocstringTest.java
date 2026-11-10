package dev.oqthara.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocstringTest {

    @Test
    void theSummaryIsEverythingBeforeTheFirstTag() {
        Docstring doc = Docstring.parse("Adds two numbers.\n\n@param a the first");
        assertEquals("Adds two numbers.", doc.summary());
        assertEquals("Adds two numbers.", doc.firstLine());
    }

    @Test
    void aParamTagIsKeyedByTheParameterNameItNames() {
        Docstring doc = Docstring.parse("""
                Adds.
                @param a the first addend
                @param b the second addend
                """);
        assertEquals("the first addend", doc.param("a"));
        assertEquals("the second addend", doc.param("b"));
    }

    @Test
    void aParamTagForAParameterTheFunctionDoesNotHaveIsNotReported() {
        Docstring doc = Docstring.parse("Adds.\n@param a the first");
        assertNull(doc.param("b"));
    }

    @Test
    void aMultiWordParameterNameIsNotSplitAtTheFirstWord() {
        // The bug this guards: splitting on whitespace and comparing only the first token
        // would match `@param count the number` against a parameter named `the`.
        Docstring doc = Docstring.parse("Counts.\n@param count the number of items");
        assertEquals("the number of items", doc.param("count"));
        assertNull(doc.param("the"));
    }

    @Test
    void everyKnownTagIsReadBack() {
        Docstring doc = Docstring.parse("""
                Does things.
                @return the result
                @throws Error when it cannot
                @since 0.1.0
                @author the maintainer
                @deprecated use other instead
                @see other
                """);
        assertEquals("the result", doc.tag("return"));
        assertEquals("Error when it cannot", doc.tag("throws"));
        assertEquals("0.1.0", doc.tag("since"));
        assertEquals("the maintainer", doc.tag("author"));
        assertEquals("use other instead", doc.tag("deprecated"));
        assertEquals("other", doc.tag("see"));
    }

    @Test
    void anUnknownTagIsKeptRatherThanRefused() {
        Docstring doc = Docstring.parse("Does.\n@example add(1, 2)");
        assertEquals("add(1, 2)", doc.tag("example"));
        assertTrue(doc.tagNames().contains("example"));
    }

    @Test
    void anAtSignThatDoesNotStartATagIsSummaryText() {
        // `look at @home` mid-sentence is prose. Only a line that *begins* with @name is a tag.
        Docstring doc = Docstring.parse("Sends mail to @home when idle.");
        assertEquals("Sends mail to @home when idle.", doc.summary());
        assertTrue(doc.tagNames().isEmpty());
    }

    @Test
    void aLoneAtSignIsNotATag() {
        Docstring doc = Docstring.parse("Costs @ each.");
        assertEquals("Costs @ each.", doc.summary());
    }

    @Test
    void parsingNoCommentAtAllIsEmptyRatherThanAFailure() {
        // Hover and `oq doc --source` both ask about declarations that have no comment,
        // so null has to be a value the model accepts rather than an error it throws.
        Docstring none = Docstring.parse(null);
        assertNull(none.summary());
        assertTrue(none.tagNames().isEmpty());
        assertNull(none.tag("return"));
    }

    @Test
    void aTagWithNoTextIsAbsentRatherThanEmpty() {
        Docstring doc = Docstring.parse("Does.\n@return");
        assertNull(doc.tag("return"));
    }

    @Test
    void blankLinesInsideTheSummaryAreKeptAsParagraphBreaks() {
        Docstring doc = Docstring.parse("First paragraph.\n\nSecond paragraph.\n@since 0.1.0");
        assertEquals("First paragraph.\n\nSecond paragraph.", doc.summary());
    }
}
