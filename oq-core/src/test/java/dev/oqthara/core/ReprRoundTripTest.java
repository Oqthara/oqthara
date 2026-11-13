package dev.oqthara.core;

import dev.oqthara.runtime.Values;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code repr} is documented as producing source that can be pasted back, and
 * {@code OqData#rebuildHint} tells the caller to do exactly that. The guarantee only holds if the
 * lexer reads the rendering as the same text, so it is checked here, where the lexer lives, rather
 * than by comparing {@code repr} against a hand-written string.
 *
 * <p>Two characters broke it. A brace opens interpolation, so {@code repr("{x}")} rendered
 * {@code "{x}"} and pasting it produced the value of {@code x} — the text changed meaning without
 * changing spelling. A NUL byte had no spelling at all: it was emitted raw, and the surrounding
 * quotes closed at the wrong place.
 */
class ReprRoundTripTest {

    /**
     * Read a rendering back the way a caller pasting it would: parse it as an expression and
     * insist it came out as one run of text. A rendering that produced an interpolation would
     * satisfy the string comparison in some cases but not this one, which is the whole point.
     */
    private static String lexBack(String quoted) {
        Ast.Program program = Parser.parse("<pasted>", quoted + "\n");
        Ast.StringLit literal = (Ast.StringLit) ((Ast.ExprStmt) program.statements().get(0)).expr();
        StringBuilder text = new StringBuilder();
        for (Ast.StringPart part : literal.parts()) {
            if (!(part instanceof Ast.TextPart run)) {
                throw new AssertionError(quoted + " pasted back as an interpolation, not text");
            }
            text.append(run.text());
        }
        return text.toString();
    }

    private static void assertPastesBack(String value) {
        String quoted = Values.repr(value);
        assertEquals(value, lexBack(quoted),
                "pasting " + quoted + " did not yield the original text");
    }

    @Test
    void textWithBracesPastesBackAsItself() {
        assertPastesBack("{x}");
        assertPastesBack("{a} and {b}");
        assertPastesBack("{");
        assertPastesBack("}");
        assertPastesBack("{{x}}");
        assertPastesBack("a{b}c");
    }

    @Test
    void aNulBytePastesBackAsItself() {
        assertPastesBack("\0");
        assertPastesBack("a\0b");
        assertPastesBack("\0\0");
    }

    @Test
    void theOrdinaryEscapesStillPasteBack() {
        assertPastesBack("plain");
        assertPastesBack("tab\there");
        assertPastesBack("line\nbreak");
        assertPastesBack("carriage\rreturn");
        assertPastesBack("quote\"inside");
        assertPastesBack("back\\slash");
        assertPastesBack("");
        assertPastesBack("é");
        assertPastesBack("😀");
    }

    /**
     * The whole byte range below 0x80, one value at a time and then joined, so a character whose
     * escape was forgotten cannot hide behind the ones that work.
     */
    @Test
    void everyAsciiCharacterPastesBackAsItself() {
        for (int c = 0; c < 0x80; c++) {
            assertPastesBack(String.valueOf((char) c));
        }
        StringBuilder all = new StringBuilder();
        for (int c = 0; c < 0x80; c++) all.append((char) c);
        assertPastesBack(all.toString());
    }

    /**
     * The same property for containers, because {@code rebuildHint} renders whole records and a
     * nested string has to survive the same trip.
     */
    @Test
    void textNestedInAListPastesBackInsideTheRendering() {
        String rendered = Values.repr(List.of("{x}", "\0", "plain"));
        assertEquals("[\"{{x}}\", \"\\0\", \"plain\"]", rendered);
        assertEquals("{x}", lexBack("\"{{x}}\""));
        assertEquals("\0", lexBack("\"\\0\""));
    }
}
