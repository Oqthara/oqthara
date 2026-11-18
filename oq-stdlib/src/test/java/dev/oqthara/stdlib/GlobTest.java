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
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void aCharacterOutsideTheBasicPlaneIsOneCharacter() {
        // Found by fuzzing the pattern translator directly: it walked `char`, so an emoji was
        // quoted as two surrogate halves. The resulting regex matched neither half alone nor
        // the pair together, so an emoji pattern never matched itself -- any name containing an
        // emoji was unreachable, and `fnmatch("😀", "😀")` answered false. Every expected value
        // matches Python's fnmatch.
        String emoji = "\uD83D\uDE00";
        assertEquals(Boolean.TRUE, call("fnmatch", emoji, emoji));
        assertEquals(Boolean.TRUE, call("fnmatch", "a" + emoji, "a" + emoji));
        assertEquals(Boolean.TRUE, call("fnmatch", emoji, "*"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a" + emoji, "?" + emoji));
        assertEquals(Boolean.FALSE, call("fnmatch", "a", emoji));
    }

    @Test
    void anAstralMemberOfAClassIsOneMember() {
        // The class body has the same `char` hazard as the literal path -- the two loops look
        // alike -- but when the literal bug was found the regex engine still rejoined surrogate
        // halves inside a class, so only the literal was broken. Pinned because the matcher now
        // compares code points directly and expands ranges itself, which is exactly the kind of
        // rewrite that can quietly lose this. Every value matches Python's fnmatch.
        String emoji = "\uD83D\uDE00";
        assertEquals(Boolean.TRUE, call("fnmatch", emoji, "[" + emoji + "]"));
        assertEquals(Boolean.FALSE, call("fnmatch", "a", "[" + emoji + "]"));
        assertEquals(Boolean.FALSE, call("fnmatch", emoji, "[!" + emoji + "]"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a", "[!" + emoji + "]"));
        assertEquals(Boolean.TRUE, call("fnmatch", emoji, "[" + emoji + "-" + emoji + "]"));
    }

    @Test
    void aClassSpanningMostOfUnicodeCostsOnlyItsOwnLength() {
        // Found while reviewing the rewrite, not by fuzzing: a first draft expanded every range
        // into its members when the pattern was built. A single class covering most of Unicode
        // then became 1.1 million ints, so a 360-character pattern exhausted a 256 MB heap before
        // one name was looked at -- a pattern far shorter than the name it is matched against.
        // Ranges are kept as endpoints now, so this builds and matches and the cost tracks the
        // pattern's length. A wide class is legal and must keep matching its members.
        String low = new String(Character.toChars(1));
        String high = new String(Character.toChars(0x10FFFF));
        String wide = "[" + low + "-" + high + "]";
        String pattern = wide.repeat(200);
        // A regression here is an OutOfMemoryError, which would otherwise take down the whole
        // test JVM and report as an executor crash rather than as this test failing. Catching it
        // keeps the regression attributable to the case that causes it.
        Object matched;
        try {
            matched = call("fnmatch", "z".repeat(200), pattern);
        } catch (OutOfMemoryError e) {
            throw new AssertionError("a " + pattern.length() + "-character pattern exhausted the"
                    + " heap; class ranges are being expanded into their members", e);
        }
        assertEquals(Boolean.TRUE, matched);
        assertEquals(Boolean.FALSE, call("fnmatch", "z".repeat(199), pattern));
        assertEquals(Boolean.TRUE, call("fnmatch", "az", "[" + low + "-" + high + "]z"));
    }

    @Test
    void aPatternFullOfStarsIsMatchedInLinearTime() {
        // Found by fuzzing: Jazzer's regex-injection sanitizer reported a user-built regex, and
        // the shape that triggered it was a long run of `*a` against a name of `a`s. Under the
        // old translator each `[^/]*` was a separate quantifier that could split the same run of
        // `a`s, so the engine had exponentially many ways to fail and took 1564 ms at twelve
        // stars, doubling with every two stars added. The matcher now advances one row per
        // pattern element, so this case is a handful of passes over the name.
        //
        // The assertion is deliberately on the answer rather than on a clock: a timing threshold
        // is flaky on a loaded machine, and the defect's signature under the old engine was that
        // this call did not return within the fuzz target's whole budget. Matching the answer
        // and returning at all is the regression.
        String pattern = "*a".repeat(12) + "*b";
        assertEquals(Boolean.FALSE, call("fnmatch", "a".repeat(30), pattern));
        assertEquals(Boolean.TRUE, call("fnmatch", "a".repeat(30) + "b", pattern));
    }

    @Test
    void aGlobstarConsumesWholeSegmentsSoItCannotSwallowAHalfName() {
        // A globstar is `(?:[^/]*/)*`, not `.*`. Modelling it as "anything at any depth" would
        // still match every case in this file, because the following literal happened to start
        // after a separator -- so it needs a case whose literal starts mid-name to be caught.
        String name = "a";
        assertEquals(Boolean.FALSE, call("fnmatch", "a/b" + name, "**/" + name));
        assertEquals(Boolean.TRUE, call("fnmatch", "a/" + name, "**/" + name));
        assertEquals(Boolean.TRUE, call("fnmatch", name, "**/" + name));
        assertEquals(Boolean.TRUE, call("fnmatch", "a/b/c/" + name, "**/" + name));
    }

    @Test
    void onlyALeadingBangNegatesSoACaretIsALiteral() {
        // `[^a]` is the spelling people reach for after `[!ab]`, but in this dialect -- as in
        // Python and fnmatch(3) -- only `!` negates, so the caret is an ordinary member.
        assertEquals(Boolean.FALSE, call("fnmatch", "b", "[^a]"));
        assertEquals(Boolean.TRUE, call("fnmatch", "^", "[^a]"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a", "[^a]"));
    }

    @Test
    void anAmpersandInAClassIsALiteralNotAnIntersection() {
        // Java regex reads `&&` as an intersection; the dialect has no such operator.
        assertEquals(Boolean.TRUE, call("fnmatch", "&", "[a&&b]"));
        assertEquals(Boolean.TRUE, call("fnmatch", "a", "[a&&b]"));
        assertEquals(Boolean.FALSE, call("fnmatch", "c", "[a&&b]"));
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

    @Test
    void aReversedRangeIsAUserErrorNotAnInternalOne() {
        // The dialect accepts this class; the regex engine does not. Unwrapped, the
        // PatternSyntaxException reached the CLI as an internal error with exit 70, so a
        // caller checking for the usual failure exit saw a different class of failure.
        OqError error = assertThrows(OqError.class, () -> call("fnmatch", "a", "[z-a]"));
        assertTrue(error.getMessage().startsWith("bad pattern: "), error.getMessage());
    }

    @Test
    void aCaretOnlyClassIsALiteralClass() {
        // `[^]` used to reach the engine unquoted and fail as "Unclosed character class". With
        // `^` quoted it is an ordinary one-member class, which is what the dialect says it is.
        assertEquals(Boolean.TRUE, call("fnmatch", "^", "[^]"));
        assertEquals(Boolean.FALSE, call("fnmatch", "x", "[^]"));
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
    void anUnreadableDirectoryIsAUserErrorNotAnInternalOne() throws IOException {
        // Files.walk reports a traversal failure as an UncheckedIOException, so the IOException
        // branch never saw it and the failure surfaced as an internal error with exit 70. Two
        // hosts have nothing to assert: Windows has no POSIX permission bits at all, and root
        // ignores them.
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "this host has no POSIX permissions, so a directory cannot be made unreadable");
        Path locked = dir.resolve("locked");
        Files.createDirectories(locked);
        touch("a.oq");
        Set<PosixFilePermission> open = Files.getPosixFilePermissions(locked);
        Files.setPosixFilePermissions(locked, Set.of());
        try {
            assumeTrue(!Files.isReadable(locked),
                    "this host ignores directory permissions, so there is nothing to refuse");
            OqError error = assertThrows(OqError.class, () -> call("glob", ".", "**/*.oq"));
            assertTrue(error.getMessage().contains("cannot search"), error.getMessage());
        } finally {
            Files.setPosixFilePermissions(locked, open);
        }
    }

    @Test
    void aPatternThatMatchesNothingReturnsAnEmptyList() throws IOException {
        touch("a.oq");
        assertEquals(List.of(), call("glob", ".", "*.nomatch"));
    }

    /**
     * A pattern that matches nothing still refuses once the traversal itself is too large.
     *
     * <p>The result cap counts matches, so it cannot bound a search that never matches: every
     * non-matching entry is traversal the call paid for and buffered but never returned. The
     * previous form drained {@code Files.walk(dir).sorted()} — a stateful, exhaustive stream —
     * before applying any cap, so a big enough tree exhausted the heap and the program died with
     * {@code the program ran out of memory} instead of a diagnostic naming the directory (issue
     * 185). This asserts the visited budget instead: the same tree now refuses by name.
     *
     * <p>The tree is the size of the real budget, so it costs a few seconds of empty-file
     * creation. It is worth that: the refusal is the only observable difference, and on the
     * previous code this exact call returned an empty list.
     */
    @Test
    void aNonMatchingPatternOverTooManyEntriesIsRefusedByName() throws IOException {
        Path many = dir.resolve("many");
        Files.createDirectories(many);
        for (int i = 0; i < Stdlib.MAX_VISITED_ENTRIES; i++) {
            Files.createFile(many.resolve("f" + i));
        }
        // Exactly the budget is accepted: the cap bounds how much is buffered, so the boundary
        // belongs to the same inclusive point walk() and list_files use.
        assertEquals(List.of(), call("glob", "many", "**/*.nomatch"));

        Files.createFile(many.resolve("one-more"));
        OqError error = assertThrows(OqError.class,
                () -> call("glob", "many", "**/*.nomatch"));
        assertTrue(error.getMessage().contains("patterns visited too many entries"),
                error.getMessage());
        assertTrue(error.getMessage().contains("many"), error.getMessage());
    }

    /** The search must not descend into a directory a non-recursive pattern cannot use. */
    @Test
    void aNonRecursivePatternDoesNotListNestedFiles() throws IOException {
        touch("sub/d.oq");
        assertEquals(List.of(), call("glob", ".", "*.oq"));
    }

    @Test
    void everyTestMethodInThisFileCarriesAnAnnotation() throws IOException {
        // Inserting a case above an annotated method consumed that method's @Test while this
        // suite still reported every visible test passing -- the ReDoS regression case had
        // silently stopped running. A bare `void name()` in a test class is never a test, so the
        // two counts must match; if they diverge, an annotation was lost or a method was added
        // without one. Verified by removing this file's `@Test` from the linear-time case: the
        // guard then fails with "32 annotated, 33 declared".
        String source = Files.readString(Path.of("src/test/java/dev/oqthara/stdlib/GlobTest.java"));
        int annotated = 0;
        int methods = 0;
        for (String line : source.split("\n", -1)) {
            // Count annotation lines, not occurrences of the word: this method's own
            // comment and message mention it, and a raw occurrence count would hide a real
            // loss. `private void` helpers are not tests, so only package-private methods
            // take part in the comparison.
            if (line.strip().equals("@" + "Test")) annotated++;
            if (line.startsWith("    void ")) methods++;
        }
        assertEquals(annotated, methods,
                "every void method in a test class needs @Test; " + annotated + " annotated, "
                        + methods + " declared");
    }

    @Test
    void aLiteralNameBehavesLikeAnEqualityTest() throws IOException {
        touch("a.oq");
        touch("b.oq");
        assertEquals(List.of("a.oq"), call("glob", ".", "a.oq"));
        assertFalse(((List<?>) call("glob", ".", "a.oq")).contains("b.oq"));
    }
}
