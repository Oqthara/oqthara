package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code fnmatch} and {@code glob}, the first entries from the inventory's ranked list.
 *
 * <p>Every expected value below was produced by running the built CLI over a real temporary
 * directory before it was written down, except where a case is about the pattern translator
 * alone and naming a file would add nothing.
 *
 * <p>The {@code **} cases exist because the platform matcher gets them wrong: Java requires at
 * least one directory for a globstar, so {@code **}{@code /*.oq} misses a top-level file. These
 * tests fail if the translator is ever replaced by {@code getPathMatcher}, which is the point.
 */
class GlobTest {

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

    private void touch(String relative) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }

    // --- fnmatch -----------------------------------------------------------------------------

    @Test
    void aStarMatchesWithinOneSegment() {
        assertEquals(Boolean.TRUE, call("fnmatch", "a.oq", "*.oq"));
        assertEquals(Boolean.FALSE, call("fnmatch", "note.oq.bak", "*.oq"));
    }

    @Test
    void aQuestionMarkMatchesExactlyOneCharacter() {
        assertEquals(Boolean.TRUE, call("fnmatch", "a.oq", "?.oq"));
        assertEquals(Boolean.FALSE, call("fnmatch", "ab.oq", "?.oq"));
    }

    @Test
    void aCharacterClassMatchesAnyListedCharacter() {
        assertEquals(Boolean.TRUE, call("fnmatch", "a.oq", "[ab].oq"));
        assertEquals(Boolean.FALSE, call("fnmatch", "c.oq", "[ab].oq"));
    }

    @Test
    void aLeadingBangInAClassNegatesIt() {
        assertEquals(Boolean.TRUE, call("fnmatch", "c.oq", "[!ab].oq"));
        assertEquals(Boolean.FALSE, call("fnmatch", "a.oq", "[!ab].oq"));
    }

    @Test
    void aDashInAClassIsARange() {
        assertEquals(Boolean.TRUE, call("fnmatch", "m3.txt", "m[0-9].txt"));
        assertEquals(Boolean.FALSE, call("fnmatch", "mx.txt", "m[0-9].txt"));
    }

    @Test
    void aStarDoesNotCrossASeparator() {
        assertEquals(Boolean.FALSE, call("fnmatch", "sub/a.oq", "*"));
    }

    @Test
    void aGlobstarMatchesAcrossSeparators() {
        assertEquals(Boolean.TRUE, call("fnmatch", "sub/deep/a.oq", "**"));
    }

    /** A literal must never reach the regex engine as syntax. */
    @Test
    void regexMetacharactersInAPatternAreLiteral() {
        assertEquals(Boolean.TRUE, call("fnmatch", "a+b.oq", "a+b.oq"));
        assertEquals(Boolean.FALSE, call("fnmatch", "aab.oq", "a+b.oq"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a.b", "a.b"));
        assertEquals(Boolean.FALSE, call("fnmatch", "axb", "a.b"));
        assertEquals(Boolean.TRUE, call("fnmatch", "(x)", "(x)"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a|b", "a|b"));
    }

    /**
     * `fnmatch` asks whether two strings match; it does not require the name to be a legal path
     * on the host. Windows refuses `|` and `?` in a file name, so converting the name to a
     * {@link java.nio.file.Path} threw `InvalidPathException` before the pattern was consulted.
     * This passed on macOS and Linux and failed only on Windows, which is why the names are
     * checked as literals rather than by creating files.
     */
    @Test
    void aNameThatIsIllegalOnThisHostStillMatches() {
        assertEquals(Boolean.TRUE, call("fnmatch", "a|b", "a|b"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a?b", "a?b"));
        // `?` is the single-character wildcard, so `axb` matches `a?b` -- that is the dialect,
        // not a bug. What matters here is that the name is not rejected before matching.
        assertEquals(Boolean.TRUE, call("fnmatch", "axb", "a?b"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a<b>c", "a<b>c"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a\"b", "a\"b"));
        // A NUL byte is rejected by Paths.get on every platform, not only Windows, so this line
        // fails wherever the name is routed through a Path. Without it the test would only
        // discriminate on Windows, where `|` and `?` are illegal file-name characters.
        assertEquals(Boolean.TRUE, call("fnmatch", "a\u0000b", "a\u0000b"));
    }

    // --- bad patterns ------------------------------------------------------------------------

    @Test
    void anEmptyPatternIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("fnmatch", "a", ""));
        assertTrue(error.getMessage().contains("a pattern cannot be empty"), error.getMessage());
    }

    @Test
    void anUnclosedClassIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("fnmatch", "a", "[abc"));
        assertTrue(error.getMessage().contains("no closing ']'"), error.getMessage());
    }

    @Test
    void anAnchoredGlobstarIsRefusedRatherThanGuessed() {
        OqError error = assertThrows(OqError.class, () -> call("fnmatch", "a", "a**b"));
        assertTrue(error.getMessage().contains("must stand alone"), error.getMessage());
    }

    // --- glob --------------------------------------------------------------------------------

    @Test
    void globListsOneLevelForAPatternWithNoSeparator() throws IOException {
        touch("a.oq");
        touch("b.oq");
        touch("c.txt");
        touch("sub/d.oq");
        assertEquals(List.of("a.oq", "b.oq"), call("glob", ".", "*.oq"));
    }

    /**
     * The reason this file exists. Python's {@code **}{@code /} matches zero directories, so a
     * top-level file is included; Java's matcher excludes it. A program written against the
     * reference must not silently lose files.
     */
    @Test
    void aGlobstarMatchesZeroDirectoriesAsWellAsMany() throws IOException {
        touch("a.oq");
        touch("sub/d.oq");
        touch("sub/deep/e.oq");
        assertEquals(List.of("a.oq", "sub/d.oq", "sub/deep/e.oq"),
                call("glob", ".", "**/*.oq"));
    }

    @Test
    void aTrailingGlobstarListsEverythingBelow() throws IOException {
        touch("a.oq");
        touch("sub/d.oq");
        touch("sub/deep/e.oq");
        assertEquals(List.of("a.oq", "sub", "sub/d.oq", "sub/deep", "sub/deep/e.oq"),
                call("glob", ".", "**"));
    }

    @Test
    void aDirectoryPrefixNarrowsTheSearch() throws IOException {
        touch("a.oq");
        touch("sub/d.oq");
        touch("sub/deep/e.oq");
        assertEquals(List.of("sub/d.oq"), call("glob", ".", "sub/*.oq"));
    }

    @Test
    void resultsUseForwardSlashesOnEveryPlatform() throws IOException {
        touch("sub/d.oq");
        assertEquals(List.of("sub/d.oq"), call("glob", ".", "**/*.oq"));
    }

    @Test
    void aDirectoryThatDoesNotExistIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("glob", "nope", "*"));
        assertTrue(error.getMessage().contains("not a directory"), error.getMessage());
    }

    @Test
    void aPatternThatMatchesNothingReturnsAnEmptyList() throws IOException {
        touch("a.oq");
        assertEquals(List.of(), call("glob", ".", "*.nomatch"));
    }

    /** The search must not descend into a directory a non-recursive pattern cannot use. */
    @Test
    void aNonRecursivePatternDoesNotListNestedFiles() throws IOException {
        touch("sub/d.oq");
        assertEquals(List.of(), call("glob", ".", "*.oq"));
    }

    @Test
    void aLiteralNameBehavesLikeAnEqualityTest() throws IOException {
        touch("a.oq");
        touch("b.oq");
        assertEquals(List.of("a.oq"), call("glob", ".", "a.oq"));
        assertFalse(((List<?>) call("glob", ".", "a.oq")).contains("b.oq"));
    }
}
