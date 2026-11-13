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
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code wrap_text}, {@code fill_text}, {@code dedent_text}, {@code indent_text},
 * {@code shorten_text} and {@code unified_diff} from ADR-0043.
 *
 * <p>The expected values were taken from Python's {@code textwrap} and {@code difflib} running
 * with {@code break_on_hyphens=False}, which the ADR names as this module's reference. The two
 * cases that matter most are {@link #aHyphenIsAnOrdinaryCharacter} -- the deliberate divergence
 * -- and {@link #identicalInputProducesNoDiffAtAll}, without which every generated report would
 * carry a pair of headers announcing that nothing changed.
 */
class TextWrapTest {

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

    private List<String> wrap(String text, int width) {
        return list(call("wrap_text", text, (double) width));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return (List<String>) value;
    }

    private static List<Object> lines(String... text) {
        return new ArrayList<>(List.of(text));
    }

    // --- wrap_text ---------------------------------------------------------------------------

    @Test
    void wrapBreaksOnSpacesAndKeepsWordsWhole() {
        assertEquals(List.of("The quick brown fox", "jumps over the lazy", "dog"),
                wrap("The quick brown fox jumps over the lazy dog", 20));
    }

    @Test
    void wrapPacksSingleSpacesTightly() {
        assertEquals(List.of("a b", "c"), wrap("a b c", 3));
    }

    @Test
    void wrapSplitsAWordThatCannotFitOnALineOfItsOwn() {
        assertEquals(List.of("abcd", "efgh", "ij"), wrap("abcdefghij", 4));
        assertEquals(List.of("super", "calif", "ragil", "istic"),
                wrap("supercalifragilistic", 5));
    }

    @Test
    void wrapAtWidthOneYieldsOneCharacterLines() {
        assertEquals(List.of("w", "o", "r", "d"), wrap("word", 1));
        assertEquals(List.of("a", "b"), wrap("a b", 1));
    }

    @Test
    void wrapKeepsLeadingIndentWhenItStillLeavesRoom() {
        assertEquals(List.of("  lead and trail"), wrap("  lead and trail   ", 30));
    }

    @Test
    void wrapDropsAnIndentThatLeavesNoRoomForTheFirstWord() {
        // The indent would fill the whole first line, so it is dropped rather than emitting
        // a line of nothing but spaces.
        assertEquals(List.of("abc", "def"), wrap("  abc def", 3));
    }

    @Test
    void wrapOfEmptyTextIsNoLinesAtAll() {
        assertEquals(List.of(), wrap("", 20));
    }

    @Test
    void aHyphenIsAnOrdinaryCharacter() {
        // ADR-0043 D3: Python with break_on_hyphens=True gives ['a-b-', 'c-', 'd-e'] here by
        // treating the hyphen as a break opportunity. Oqthara does not, because choosing those
        // breaks needs an opinion about syllables that the language does not have.
        assertEquals(List.of("a-b-", "c-d-", "e"), wrap("a-b-c-d-e", 4));
        assertEquals(List.of("co-ope", "rate", "and", "re-ent", "er"),
                wrap("co-operate and re-enter", 6));
    }

    @Test
    void wrapRefusesAWidthBelowOneWithANamedError() {
        OqError error = assertThrows(OqError.class, () -> call("wrap_text", "abc", 0d));
        assertTrue(error.getMessage().contains("at least 1"), error.getMessage());
        assertThrows(OqError.class, () -> call("wrap_text", "abc", -3d));
    }

    @Test
    void wrapRefusesAFractionalWidth() {
        OqError error = assertThrows(OqError.class, () -> call("wrap_text", "abc", 2.5d));
        assertTrue(error.getMessage().contains("whole number"), error.getMessage());
    }

    @Test
    void wrapRefusesNonText() {
        assertThrows(OqError.class, () -> call("wrap_text", 5d, 3d));
    }

    // --- fill_text ---------------------------------------------------------------------------

    @Test
    void fillTextIsWrapJoinedByNewlines() {
        assertEquals("The quick\nbrown fox\njumps", call("fill_text", "The quick brown fox jumps", 10d));
    }

    @Test
    void fillTextOfEmptyTextIsTheEmptyValue() {
        assertEquals("", call("fill_text", "", 10d));
    }

    // --- dedent_text -------------------------------------------------------------------------

    @Test
    void dedentRemovesTheSharedIndentAndNothingMore() {
        assertEquals("a\n  b", call("dedent_text", "  a\n    b"));
    }

    @Test
    void dedentLeavesTextWithoutASharedIndentUntouched() {
        assertEquals("no indent", call("dedent_text", "no indent"));
        assertEquals("a\n  b", call("dedent_text", "a\n  b"));
    }

    @Test
    void dedentOfWhitespaceOnlyTextIsTheEmptyValue() {
        assertEquals("", call("dedent_text", ""));
        assertEquals("", call("dedent_text", "   "));
    }

    @Test
    void dedentIgnoresBlankLinesWhenComputingTheSharedIndent() {
        // The blank first two lines must not count as zero indent, or nothing is removed.
        assertEquals("\n\nx", call("dedent_text", "\n\n  x"));
    }

    // --- indent_text -------------------------------------------------------------------------

    @Test
    void indentPrefixesEveryNonBlankLine() {
        assertEquals("> a\n\n> b", call("indent_text", "a\n\nb", "> "));
    }

    @Test
    void indentOfTheEmptyValueIsTheEmptyValue() {
        assertEquals("", call("indent_text", "", "  "));
    }

    @Test
    void indentAndDedentRoundTripASharedPrefix() {
        assertEquals("\n\nx", call("dedent_text", call("indent_text", "\n\nx", "    ")),
                "indenting blank lines, then dedenting, must return the original");
    }

    // --- shorten_text ------------------------------------------------------------------------

    @Test
    void shortenCollapsesWhitespaceAndAppendsTheMarker() {
        assertEquals("Hello [...]", call("shorten_text", "Hello  world  foo bar", 12d));
        assertEquals("padded [...]", call("shorten_text", "  padded   text  here  ", 14d));
    }

    @Test
    void shortenLeavesTextThatAlreadyFitsAlone() {
        assertEquals("short", call("shorten_text", "short", 40d));
        // Text that fits is still whitespace-collapsed, which is what a one-line summary wants.
        assertEquals("a b", call("shorten_text", " a   b ", 40d));
    }

    @Test
    void shortenWithNoRoomForAWordReturnsOnlyTheMarker() {
        // Never a leading space: the marker is a standalone placeholder, not a suffix.
        assertEquals("[...]", call("shorten_text", "exactly ten", 10d));
    }

    @Test
    void shortenRefusesAWidthThatCannotHoldTheMarker() {
        OqError error = assertThrows(OqError.class,
                () -> call("shorten_text", "hello world", 4d));
        assertTrue(error.getMessage().contains("placeholder"), error.getMessage());
        assertTrue(error.hint() != null && error.hint().contains("at least"),
                "the error should say what width works, got " + error.hint());
    }

    // --- unified_diff ------------------------------------------------------------------------

    @Test
    void aSingleChangedLineProducesOneHunkWithContext() {
        assertEquals(List.of(
                        "--- before", "+++ after",
                        "@@ -1,3 +1,3 @@",
                        " a", "-b", "+x", " c"),
                list(call("unified_diff", lines("a", "b", "c"), lines("a", "x", "c"),
                        "before", "after")));
    }

    @Test
    void identicalInputProducesNoDiffAtAll() {
        // Not even the headers: an empty list is what lets a caller print the diff only when
        // there is one, without a second comparison.
        assertEquals(List.of(), list(call("unified_diff", lines("a", "b"), lines("a", "b"))));
    }

    @Test
    void anEmptyBeforeSideIsAnInsertionAtLineZero() {
        assertEquals(List.of("--- ", "+++ ", "@@ -0,0 +1 @@", "+a"),
                list(call("unified_diff", lines(), lines("a"))));
    }

    @Test
    void anEmptyAfterSideIsADeletionToLineZero() {
        assertEquals(List.of("--- ", "+++ ", "@@ -1 +0,0 @@", "-a"),
                list(call("unified_diff", lines("a"), lines())));
    }

    @Test
    void distantChangesBecomeSeparateHunksAndCloseOnesMerge() {
        List<Object> before = new ArrayList<>();
        for (int i = 1; i <= 30; i++) before.add("line" + i);

        List<Object> far = new ArrayList<>(before);
        far.set(2, "CHANGED3");
        far.set(25, "CHANGED26");
        List<String> farDiff = list(call("unified_diff", before, far));
        assertEquals(2, farDiff.stream().filter(line -> line.startsWith("@@")).count(),
                "changes 22 lines apart must not share a hunk: " + farDiff);

        List<Object> near = new ArrayList<>(before);
        near.set(9, "X10");
        near.set(16, "X17");
        List<String> nearDiff = list(call("unified_diff", before, near));
        assertEquals(1, nearDiff.stream().filter(line -> line.startsWith("@@")).count(),
                "changes separated by six unchanged lines share a hunk: " + nearDiff);
    }

    @Test
    void hunkHeadersCarryTheCorrectLineCounts() {
        List<Object> before = new ArrayList<>();
        for (int i = 1; i <= 30; i++) before.add("line" + i);
        List<Object> after = new ArrayList<>(before);
        after.set(2, "CHANGED3");
        after.set(25, "CHANGED26");
        List<String> diff = list(call("unified_diff", before, after));
        // Three leading lines plus the change, and three trailing after it.
        assertTrue(diff.contains("@@ -1,6 +1,6 @@"), diff.toString());
        assertTrue(diff.contains("@@ -23,7 +23,7 @@"), diff.toString());
    }

    @Test
    void everyDiffReproducesTheAfterSideFromTheBeforeSide() {
        // ADR-0043 D5 states this property and cites a sixty-case random check. A unified diff
        // omits unchanged lines beyond its context, so the only faithful test is to apply it the
        // way a patch tool does: splice each hunk at the line offset its header names, keeping
        // the context and added lines and dropping the removed ones.
        Random random = new Random(20261111L);
        String[] alphabet = {"a", "b", "c", "d"};
        for (int trial = 0; trial < 60; trial++) {
            List<String> before = randomLines(random, alphabet);
            List<String> after = randomLines(random, alphabet);
            List<Object> args = new ArrayList<>(List.of(new ArrayList<>(before),
                    new ArrayList<>(after)));
            List<String> diff = list(call("unified_diff", args.toArray()));
            assertEquals(after, apply(before, diff), "trial " + trial + " before=" + before
                    + " after=" + after + " diff=" + diff);
        }
    }

    @Test
    void aHunkHeaderAlwaysAgreesWithTheLinesUnderIt() {
        // The counts in `@@ -a,b +c,d @@` are what a patch tool trusts to find the next hunk, so
        // a wrong count corrupts every hunk after it. Each hunk must also be exactly as wide as
        // its header claims, on both sides.
        Random random = new Random(424242L);
        String[] alphabet = {"x", "y", "z"};
        for (int trial = 0; trial < 60; trial++) {
            List<String> before = randomLines(random, alphabet);
            List<String> after = randomLines(random, alphabet);
            List<Object> args = new ArrayList<>(List.of(new ArrayList<>(before),
                    new ArrayList<>(after)));
            List<String> diff = list(call("unified_diff", args.toArray()));
            assertHunkCountsMatch(diff, before, after, trial);
        }
    }

    private static void assertHunkCountsMatch(List<String> diff, List<String> before,
            List<String> after, int trial) {
        int index = 0;
        while (index < diff.size()) {
            String header = diff.get(index++);
            if (!header.startsWith("@@")) continue;
            var counts = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").matcher(header);
            assertTrue(counts.matches(), "unparseable hunk header " + header);
            int beforeCount = counts.group(2) == null ? 1 : Integer.parseInt(counts.group(2));
            int afterCount = counts.group(4) == null ? 1 : Integer.parseInt(counts.group(4));
            int seenBefore = 0;
            int seenAfter = 0;
            while (index < diff.size() && !diff.get(index).startsWith("@@")
                    && !diff.get(index).startsWith("---")) {
                String line = diff.get(index++);
                if (line.startsWith(" ")) {
                    seenBefore++;
                    seenAfter++;
                } else if (line.startsWith("-")) {
                    seenBefore++;
                } else if (line.startsWith("+")) {
                    seenAfter++;
                }
            }
            assertEquals(beforeCount, seenBefore, "trial " + trial + " header " + header);
            assertEquals(afterCount, seenAfter, "trial " + trial + " header " + header);
        }
    }

    private static List<String> randomLines(Random random, String[] alphabet) {
        int length = random.nextInt(7);
        List<String> lines = new ArrayList<>(length);
        for (int i = 0; i < length; i++) lines.add(alphabet[random.nextInt(alphabet.length)]);
        return lines;
    }

    /** Applies a unified diff to {@code before} the way a patch tool does. */
    private static List<String> apply(List<String> before, List<String> diff) {
        List<String> rebuilt = new ArrayList<>();
        int consumed = 0;
        int index = 0;
        while (index < diff.size()) {
            String header = diff.get(index++);
            if (!header.startsWith("@@")) continue;
            var counts = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").matcher(header);
            assertTrue(counts.matches(), "unparseable hunk header " + header);
            int beforeStart = Integer.parseInt(counts.group(1)) - 1;
            // lines before the hunk are unchanged and are copied verbatim
            while (consumed < beforeStart) rebuilt.add(before.get(consumed++));
            while (index < diff.size() && !diff.get(index).startsWith("@@")
                    && !diff.get(index).startsWith("---")) {
                String line = diff.get(index++);
                if (line.startsWith(" ")) {
                    rebuilt.add(line.substring(1));
                    consumed++;
                } else if (line.startsWith("-")) {
                    consumed++;
                } else if (line.startsWith("+")) {
                    rebuilt.add(line.substring(1));
                }
            }
        }
        // anything after the last hunk is unchanged
        while (consumed < before.size()) rebuilt.add(before.get(consumed++));
        return rebuilt;
    }

    @Test
    void diffRefusesABareStringRatherThanDiffingCharacters() {
        // A string is iterable as characters, so accepting one would silently compare
        // characters instead of lines and report a meaningless diff.
        OqError error = assertThrows(OqError.class,
                () -> call("unified_diff", "abc", lines("a")));
        assertTrue(error.getMessage().contains("list of lines"), error.getMessage());
    }

    @Test
    void diffRefusesLinesThatAreNotText() {
        OqError error = assertThrows(OqError.class,
                () -> call("unified_diff", List.of(1d), lines("a")));
        assertTrue(error.getMessage().contains("lines of text"), error.getMessage());
    }

    @Test
    void diffAcceptsLabelsAsOptionalArguments() {
        assertEquals(List.of("--- old", "+++ new", "@@ -1 +1 @@", "-a", "+b"),
                list(call("unified_diff", lines("a"), lines("b"), "old", "new")));
        assertEquals(List.of("--- ", "+++ ", "@@ -1 +1 @@", "-a", "+b"),
                list(call("unified_diff", lines("a"), lines("b"))));
    }

    // --- Characters are code points, not UTF-16 units (#179) --------------

    @Test
    void aSupplementaryCharacterIsOneCharacterWide() {
        // "😀a" is two characters, so width 1 wraps it into two lines. String.length() said
        // three and split the surrogate pair, producing two unpaired halves.
        assertEquals(List.of("\uD83D\uDE00", "a"), list(call("wrap_text", "\uD83D\uDE00a", 1L)));
        assertEquals(List.of("\uD83D\uDE00\uD83D\uDE00", "\uD83D\uDE00"),
                list(call("wrap_text", "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 2L)));
    }

    @Test
    void wrappingNeverSplitsASurrogatePair() {
        for (String line : list(call("wrap_text", "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 1L))) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                assertTrue(!Character.isHighSurrogate(c) || i + 1 < line.length()
                                && Character.isLowSurrogate(line.charAt(i + 1)),
                        "line starts a pair it does not finish: " + line);
                assertTrue(!Character.isLowSurrogate(c) || i > 0
                                && Character.isHighSurrogate(line.charAt(i - 1)),
                        "line ends with an unpaired low surrogate: " + line);
            }
        }
    }

    @Test
    void shorteningCountsSupplementaryCharactersOnce() {
        // Five characters in a width of 6 needs no ellipsis; counting units said 10 and shortened.
        assertEquals("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00",
                call("shorten_text", "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 6L));
    }

    // --- unified_diff resource bound (issue 178) ---------------------------------------------

    @Test
    void aDiffTooLargeForTheTableIsRefusedRatherThanExhaustingTheHeap() {
        // The reported reproduction: 5,001 distinct lines on both sides asked for a 25-million
        // cell matrix, about 100 MB of cell data, and a 96 MiB heap died before anything could
        // report it. The refusal must be an ordinary Oqthara error with a help line.
        List<Object> before = new ArrayList<>();
        List<Object> after = new ArrayList<>();
        for (int i = 0; i <= 5000; i++) {
            before.add("a" + i);
            after.add("b" + i);
        }
        OqError error = assertThrows(OqError.class,
                () -> call("unified_diff", before, after));
        assertTrue(error.getMessage().contains("5001 lines with 5001 lines"),
                error.getMessage());
        assertTrue(error.getMessage().contains("25020004"),
                "the error must name the size it refused: " + error.getMessage());
        assertTrue(error.hint() != null && error.hint().contains("another language"),
                "the error must say what to do instead: " + error.hint());
    }

    @Test
    void theRefusalIsSymmetricInTheTwoSides() {
        // A wide-before/narrow-after input and its transpose must both be refused: the cost is
        // the product, so neither ordering can slip under the limit.
        List<Object> wide = new ArrayList<>();
        for (int i = 0; i < 8000; i++) wide.add("w" + i);
        List<Object> narrow = new ArrayList<>();
        for (int i = 0; i < 800; i++) narrow.add("n" + i);
        assertThrows(OqError.class, () -> call("unified_diff", wide, narrow));
        assertThrows(OqError.class, () -> call("unified_diff", narrow, wide));
    }

    @Test
    void aDiffJustUnderTheLimitStillProducesAFullResult() {
        // The bound must not be off by a constant that quietly rejects work it could afford.
        // 1,999 x 1,999 is 4,000,000 cells exactly, which the limit admits.
        List<Object> before = new ArrayList<>();
        List<Object> after = new ArrayList<>();
        for (int i = 0; i < 1999; i++) {
            before.add("line" + i);
            after.add(i == 1000 ? "CHANGED" : "line" + i);
        }
        List<String> diff = list(call("unified_diff", before, after));
        assertEquals(1, diff.stream().filter(line -> line.startsWith("@@")).count(),
                "one change in a large file is still one hunk: " + diff.size() + " lines");
        assertTrue(diff.contains("-line1000"), diff.toString());
        assertTrue(diff.contains("+CHANGED"), diff.toString());
    }

    @Test
    void oneLineOverTheLimitIsRefused() {
        // 2,000 x 2,001 is 4,002,000 cells: the first size past the limit, so a boundary test
        // proves the comparison is `>` and not `>=`.
        List<Object> before = new ArrayList<>();
        List<Object> after = new ArrayList<>();
        for (int i = 0; i < 2000; i++) before.add("x" + i);
        for (int i = 0; i < 2001; i++) after.add("y" + i);
        assertThrows(OqError.class, () -> call("unified_diff", before, after));
    }

    @Test
    void aHugeButIdenticalPairIsStillRefusedRatherThanCompared() {
        // Identical inputs produce no diff, but the table is built before that is known, so the
        // bound applies here too. Returning an empty list would be the right answer and the
        // wrong expense: the whole matrix would have to be filled to prove it.
        List<Object> same = new ArrayList<>();
        for (int i = 0; i < 5000; i++) same.add("s" + i);
        assertThrows(OqError.class, () -> call("unified_diff", same, same));
    }

    @Test
    void anEmptySideCostsNothingAndIsNeverRefused() {
        // An empty list makes the product zero regardless of the other side's length, so the
        // bound must not fire: this is the one shape where a huge input is genuinely cheap.
        List<Object> huge = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) huge.add("h" + i);
        assertEquals(List.of("--- ", "+++ ", "@@ -0,0 +1,50000 @@"),
                list(call("unified_diff", lines(), huge)).subList(0, 3));
        assertEquals(List.of("--- ", "+++ ", "@@ -1,50000 +0,0 @@"),
                list(call("unified_diff", huge, lines())).subList(0, 3));
    }
}
