package dev.oqthara.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks hover resolution against real source text. The interesting cases are the ones an
 * editor actually hits: a documented declaration, an undocumented one, a builtin, and a
 * position that resolves to nothing at all.
 */
class HoverTest {
    private static final String URI = "file:///tmp/hover.oq";

    private static final String SOURCE = """
            \"\"\"Adds two numbers.

            @param a the first addend
            @param b the second addend
            @return the sum
            @since 0.1.0
            \"\"\"
            fun add(a, b)
              return a + b
            end

            \"\"\"A point.\"\"\"
            data Point(x, y) immutable

            class Counter(count) implements Shape
              fun bump()
                count = count + 1
              end
            end

            contract Shape
              fun bump()
            end

            fun undocumented(x)
              return x
            end

            print add(1, 2)
            """;

    /** Cursor positions are computed by locating the text, so the tests survive reformatting. */
    private static int[] at(String source, String needle) {
        int offset = source.indexOf(needle);
        assertTrue(offset >= 0, "needle not found: " + needle);
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }

    @Test
    void hoveringAUseOfADocumentedFunctionShowsItsSignatureAndTags() {
        int[] position = at(SOURCE, "add(1, 2)");
        String hover = Hover.hover(URI, SOURCE, position[0], position[1]);
        assertNotNull(hover);
        assertTrue(hover.contains("fun add(a, b)"), hover);
        assertTrue(hover.contains("Adds two numbers."), hover);
        assertTrue(hover.contains("**a** the first addend"), hover);
        assertTrue(hover.contains("Returns: the sum"), hover);
        assertTrue(hover.contains("Since: 0.1.0"), hover);
    }

    @Test
    void hoveringTheDeclarationItselfResolvesToo() {
        int[] position = at(SOURCE, "fun add(a, b)");
        String hover = Hover.hover(URI, SOURCE, position[0], position[1] + 4);
        assertNotNull(hover);
        assertTrue(hover.contains("fun add(a, b)"), hover);
    }

    @Test
    void undocumentedDeclarationsShowTheSignatureAndNothingInvented() {
        int[] position = at(SOURCE, "undocumented(x)");
        String hover = Hover.hover(URI, SOURCE, position[0], position[1]);
        assertNotNull(hover);
        assertTrue(hover.contains("fun undocumented(x)"), hover);
        assertTrue(!hover.contains("**"), "no parameter bullets for an undocumented function: " + hover);
    }

    @Test
    void dataDeclarationsRenderTheirModifierAndFields() {
        int[] position = at(SOURCE, "Point(x, y)");
        String hover = Hover.hover(URI, SOURCE, position[0], position[1]);
        assertNotNull(hover);
        assertTrue(hover.contains("data Point(x, y) immutable"), hover);
        assertTrue(hover.contains("A point."), hover);
    }

    @Test
    void classDeclarationsRenderTheirContract() {
        int[] position = at(SOURCE, "Counter(count)");
        String hover = Hover.hover(URI, SOURCE, position[0], position[1]);
        assertNotNull(hover);
        assertTrue(hover.contains("class Counter(count) implements Shape"), hover);
    }

    @Test
    void contractDeclarationsResolve() {
        int[] position = at(SOURCE, "Shape");
        // The first occurrence is on the class line; the contract's own name is later.
        int contractLine = at(SOURCE, "contract Shape")[0];
        String hover = Hover.hover(URI, SOURCE, contractLine, 9);
        assertNotNull(hover);
        assertTrue(hover.contains("contract Shape"), hover);
    }

    @Test
    void builtinsResolveFromTheInterpreterTableNotACopy() {
        int[] position = at(SOURCE, "print add(1, 2)");
        String hover = Hover.hover(URI, SOURCE, position[0], position[1]);
        assertNotNull(hover);
        assertTrue(hover.contains("print("), hover);
        assertTrue(hover.contains("Standard library function."), hover);
    }

    @Test
    void aVariadicThatAcceptsNothingRendersOneEllipsisRatherThanAStrayComma() {
        // `print` accepts any number of arguments including none, so the minimum contributes no
        // placeholder and the ellipsis must not be joined to an empty argument list.
        String hover = Hover.hover(URI, "print 1\n", 0, 1);
        assertNotNull(hover);
        assertTrue(hover.contains("print(...)"), hover);
        assertFalse(hover.contains("print(, ...)"), hover);
    }

    @Test
    void aPositionWithNoIdentifierReturnsNull() {
        int[] position = at(SOURCE, "  return a + b");
        // Column 0 is leading whitespace, so there is no word there.
        assertNull(Hover.hover(URI, SOURCE, position[0], 0));
    }

    @Test
    void anUnknownNameReturnsNull() {
        String source = "print nobody_knows_this\n";
        assertNull(Hover.hover(URI, source, 0, 7));
    }

    @Test
    void anUnparseableDocumentDescribesNothing() {
        String source = "if true\nprint \"no end\"\n";
        assertNull(Hover.hover(URI, source, 1, 0));
    }

    @Test
    void wordAtClampsPastTheEndOfALineAndOfTheDocument() {
        String source = "abc\ndef\n";
        assertEquals("abc", Hover.wordAt(source, 0, 3).text());
        assertEquals("def", Hover.wordAt(source, 1, 99).text());
        assertNull(Hover.wordAt(source, 9, 0));
    }

    @Test
    void rangeAtCoversExactlyTheIdentifier() {
        String source = "print add(1, 2)\n";
        var range = Hover.rangeAt(source, 0, 7);
        assertNotNull(range);
        @SuppressWarnings("unchecked")
        var start = (java.util.Map<String, Object>) range.get("start");
        @SuppressWarnings("unchecked")
        var end = (java.util.Map<String, Object>) range.get("end");
        assertEquals(6L, start.get("character"));
        assertEquals(9L, end.get("character"));
    }

    @Test
    void aTagTheLanguageDoesNotKnowIsStillShown() {
        String source = """
                \"\"\"Does a thing.

                @example add(1, 2)
                \"\"\"
                fun add(a, b)
                  return a + b
                end
                """;
        String hover = Hover.hover(URI, source, 4, 5);
        assertNotNull(hover);
        assertTrue(hover.contains("Example: add(1, 2)"), hover);
    }
}
