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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * {@code stat}, {@code touch}, {@code temp_dir}, {@code copy_tree},
 * {@code read_lines_or_empty}, {@code path_absolute} and {@code path_normalize}.
 *
 * <p>Nothing here asserts a size for a directory or a timestamp's exact value: the first is
 * whatever the filesystem stores for the entry and differs between ext4, APFS and NTFS, and the
 * second depends on when the test ran. The shape of the timestamp is asserted, because the shape
 * is the part callers rely on when they compare two of them as text.
 *
 * <p>Cases that need a symbolic link return early when the filesystem will not make one, which
 * is what {@code FileOperationsTest} already does for its cycle test. CI runs on Windows, where
 * creating a link needs a privilege the build does not have.
 */
class FileSystemModuleTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

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

    private OqMap stat(String path) {
        return (OqMap) call("stat", path);
    }

    /** Creates a link, or returns false where the platform will not let this process make one. */
    private boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
    }

    // --- stat ---------------------------------------------------------------------------------

    @Test
    void statOnAFileReportsItsSizeAndType() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");
        OqMap metadata = stat("a.txt");
        assertEquals(5L, metadata.get("size"));
        assertEquals(Boolean.TRUE, metadata.get("is_file"));
        assertEquals(Boolean.FALSE, metadata.get("is_dir"));
        assertEquals(Boolean.FALSE, metadata.get("is_symlink"));
        assertEquals(Boolean.TRUE, metadata.get("readable"));
        assertEquals(Boolean.TRUE, metadata.get("writable"));
    }

    @Test
    void statTimestampsAreUtcInstantsWithThreeFractionalDigits() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "");
        OqMap metadata = stat("a.txt");
        String modified = (String) metadata.get("modified");
        String created = (String) metadata.get("created");
        // The fixed shape is the contract: it lets two of them be compared as text.
        assertTrue(modified.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                modified);
        assertTrue(created.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                created);
    }

    @Test
    void statOnADirectoryReportsADirectoryAndDoesNotClaimASizeItCanVouchFor() throws Exception {
        Files.createDirectories(dir.resolve("d"));
        OqMap metadata = stat("d");
        assertEquals(Boolean.TRUE, metadata.get("is_dir"));
        assertEquals(Boolean.FALSE, metadata.get("is_file"));
        assertEquals(Boolean.FALSE, metadata.get("is_symlink"));
    }

    @Test
    void statReportsModeOnlyWhereThePlatformHasPosixPermissions() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "");
        OqMap metadata = stat("a.txt");
        if (metadata.containsKey("mode")) {
            String mode = (String) metadata.get("mode");
            assertTrue(mode.matches("[0-7]{3,4}"), mode);
        } else {
            // Windows, where a permission set is a fabrication rather than a reading. The point
            // of the test is that the key is omitted instead of guessed, so there is nothing
            // else to check.
            assertFalse(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        }
    }

    @Test
    void statOnASymbolicLinkDescribesTheLinkRatherThanWhatItPointsAt() throws Exception {
        Files.writeString(dir.resolve("target.txt"), "hello");
        if (!link(dir.resolve("link.txt"), dir.resolve("target.txt"))) return;
        OqMap metadata = stat("link.txt");
        assertEquals(Boolean.TRUE, metadata.get("is_symlink"));
        // A link is not a regular file and not a directory. Reporting the target's type here
        // would make the map true of no single entry, and the caller could not tell which keys
        // came from the link and which from its target.
        assertEquals(Boolean.FALSE, metadata.get("is_file"));
        assertEquals(Boolean.FALSE, metadata.get("is_dir"));
        // The permissions do follow the link, because they answer whether reading the path works.
        assertEquals(Boolean.TRUE, metadata.get("readable"));
    }

    @Test
    void statOnAMissingPathRaisesRatherThanReportingAnAbsentEntry() {
        OqError error = assertThrows(OqError.class, () -> stat("nope.txt"));
        assertEquals("cannot stat 'nope.txt'", error.getMessage());
        assertTrue(error.hint().contains("exists"), error.hint());
    }

    // --- touch --------------------------------------------------------------------------------

    @Test
    void touchCreatesAnEmptyFileThatIsNotThere() throws Exception {
        assertEquals(null, call("touch", "new.txt"));
        assertTrue(Files.isRegularFile(dir.resolve("new.txt")));
        assertEquals(0L, Files.size(dir.resolve("new.txt")));
    }

    @Test
    void touchUpdatesTheTimeOfAFileThatIsThere() throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000_000L));
        call("touch", "a.txt");
        assertEquals("hello", Files.readString(file), "touch() must not truncate the file");
        assertTrue(Files.getLastModifiedTime(file).toMillis() > 1_500_000_000_000L,
                "the modification time was not updated");
    }

    @Test
    void touchRaisesWhenTheParentDirectoryIsMissing() {
        OqError error = assertThrows(OqError.class, () -> call("touch", "no/such/new.txt"));
        assertTrue(error.getMessage().startsWith("cannot touch"), error.getMessage());
    }

    // --- temp_dir -----------------------------------------------------------------------------

    @Test
    void tempDirCreatesADirectoryNamedAfterThePrefix() throws Exception {
        String created = (String) call("temp_dir", "oq-test-");
        Path path = Path.of(created);
        try {
            assertTrue(Files.isDirectory(path), created);
            assertTrue(path.getFileName().toString().startsWith("oq-test-"), created);
        } finally {
            Files.delete(path);
        }
    }

    @Test
    void tempDirRefusesAnEmptyPrefix() {
        OqError error = assertThrows(OqError.class, () -> call("temp_dir", ""));
        assertEquals("temp_dir() needs a non-empty prefix", error.getMessage());
    }

    // --- copy_tree ----------------------------------------------------------------------------

    @Test
    void copyTreeCopiesNestedFilesAndDirectories() throws Exception {
        Files.createDirectories(dir.resolve("from/x/y"));
        Files.writeString(dir.resolve("from/a.txt"), "1");
        Files.writeString(dir.resolve("from/x/b.txt"), "2");
        Files.writeString(dir.resolve("from/x/y/c.txt"), "3");
        call("copy_tree", "from", "to");
        assertEquals("1", Files.readString(dir.resolve("to/a.txt")));
        assertEquals("2", Files.readString(dir.resolve("to/x/b.txt")));
        assertEquals("3", Files.readString(dir.resolve("to/x/y/c.txt")));
        assertEquals("1", Files.readString(dir.resolve("from/a.txt")), "the source moved");
    }

    @Test
    void copyTreeAcceptsAnExistingEmptyTarget() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "1");
        Files.createDirectories(dir.resolve("to"));
        call("copy_tree", "from", "to");
        assertEquals("1", Files.readString(dir.resolve("to/a.txt")));
    }

    @Test
    void copyTreeRefusesANonEmptyTargetRatherThanMerging() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "new");
        Files.createDirectories(dir.resolve("to"));
        Files.writeString(dir.resolve("to/keep.txt"), "old");
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "from", "to"));
        assertTrue(error.getMessage().contains("not empty"), error.getMessage());
        // The refusal comes before anything is written, so the old tree is untouched.
        assertEquals(List.of("keep.txt"), names(dir.resolve("to")));
    }

    @Test
    void copyTreeRefusesAnExistingTargetThatIsAFile() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "new");
        Files.writeString(dir.resolve("to"), "old");
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "from", "to"));
        assertTrue(error.getMessage().contains("already exists"), error.getMessage());
        assertEquals("old", Files.readString(dir.resolve("to")));
    }

    @Test
    void copyTreeRefusesAMissingSource() {
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "nope", "to"));
        assertEquals("cannot copy '" + dir.resolve("nope") + "'", error.getMessage());
        assertFalse(Files.exists(dir.resolve("to")));
    }

    @Test
    void copyTreeRefusesASourceThatIsAFile() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "1");
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "a.txt", "to"));
        assertTrue(error.getMessage().contains("copies a directory, not a file"), error.getMessage());
    }

    @Test
    void copyTreeRefusesASourceThatIsASymbolicLink() throws Exception {
        Files.createDirectories(dir.resolve("real"));
        if (!link(dir.resolve("link"), dir.resolve("real"))) return;
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "link", "to"));
        assertTrue(error.getMessage().contains("will not copy a symbolic link"),
                error.getMessage());
    }

    @Test
    void copyTreeRefusesASymbolicLinkInsideTheTree() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("outside.txt"), "secret");
        if (!link(dir.resolve("from/link.txt"), dir.resolve("../outside.txt"))) return;
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "from", "to"));
        assertTrue(error.getMessage().contains("will not copy a symbolic link"),
                error.getMessage());
        // Nothing has been written, so a link cannot have been recreated in the target either.
        assertFalse(Files.exists(dir.resolve("to")));
    }

    @Test
    void copyTreeRefusesToCopyADirectoryOntoItself() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "from", "from"));
        assertEquals("copy_tree() cannot copy a directory onto itself: " + dir.resolve("from"),
                error.getMessage());
    }

    @Test
    void copyTreeRefusesToCopyADirectoryIntoItself() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        OqError error = assertThrows(OqError.class, () -> call("copy_tree", "from", "from/inner"));
        assertEquals("copy_tree() cannot copy a directory into itself: " + dir.resolve("from/inner"),
                error.getMessage());
        assertFalse(Files.exists(dir.resolve("from/inner")));
    }

    @Test
    void copyTreeRaisesRatherThanTruncatingWhenTheTreeIsTooLarge() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "1");
        Files.writeString(dir.resolve("from/b.txt"), "2");
        // The root plus two entries is three, which fits a cap of three.
        FileSystem.copyTree(host(), dir.resolve("from"), dir.resolve("to"), Span.synthetic(), 3);
        assertEquals(2, names(dir.resolve("to")).size());
        Files.writeString(dir.resolve("from/c.txt"), "3");
        OqError error = assertThrows(OqError.class, () -> FileSystem.copyTree(host(),
                dir.resolve("from"), dir.resolve("three"), Span.synthetic(), 3));
        assertTrue(error.getMessage().contains("too many entries"), error.getMessage());
        assertFalse(Files.exists(dir.resolve("three")),
                "the cap is checked before anything is written");
    }

    /**
     * A tree whose files add up to more than the byte budget is refused rather than copied.
     *
     * <p>The entry cap alone does not bound this: a hundred thousand files of sixteen megabytes
     * each is 1.6 TiB, so a cap on entries with no cap on bytes would make {@code copy_tree} the
     * way around the limit {@code copy_file} enforces. The budget is checked twice -- against
     * the sizes the filesystem reports, before anything is written, and against the bytes that
     * actually move, because the reported size is only advisory. This drives the first, which is
     * the one a tree of ordinary files meets; the second is the same {@code copyBounded} call
     * {@code copy_file} makes, and its own test is in {@code FileOperationsTest}.
     */
    @Test
    void copyTreeRefusesATreeThatExceedsTheByteBudget() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "12345");
        Files.writeString(dir.resolve("from/b.txt"), "67890");
        Files.writeString(dir.resolve("from/c.txt"), "art");

        OqError error = assertThrows(OqError.class, () -> FileSystem.copyTree(host(),
                dir.resolve("from"), dir.resolve("to"), Span.synthetic(), 100, 10));

        assertTrue(error.getMessage().contains("too large to copy"), error.getMessage());
        assertTrue(error.hint().contains("10 byte limit"), error.hint());
        // Refused before the first byte, so there is no partial tree to delete and nothing to
        // clean up: the target is never created.
        assertFalse(Files.exists(dir.resolve("to")), "a tree refused up front writes nothing");
        assertEquals(13, Files.size(dir.resolve("from/a.txt")) + Files.size(dir.resolve("from/b.txt"))
                + Files.size(dir.resolve("from/c.txt")), "the source is untouched");
    }

    /** A tree of exactly the budget is copied: the bound is a maximum, not an exclusive one. */
    @Test
    void copyTreeAcceptsATreeOfExactlyTheBudget() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "12345");
        Files.writeString(dir.resolve("from/b.txt"), "67890");
        FileSystem.copyTree(host(), dir.resolve("from"), dir.resolve("to"), Span.synthetic(),
                100, 10);
        assertEquals("12345", Files.readString(dir.resolve("to/a.txt")));
        assertEquals("67890", Files.readString(dir.resolve("to/b.txt")));
    }

    /**
     * A tree is refused mid-copy when a file yields more bytes than the filesystem reported.
     *
     * <p>The up-front check counts the sizes the filesystem reports, which for a FIFO is zero
     * however much the writer sends. A tree holding one is therefore admitted by that check and
     * has to be stopped by the count in {@code copyBounded}. The refusal has to say so: the
     * error names the tree rather than the one file, because what the caller is left with is a
     * whole partial target directory, not a single file {@code copy_file} could have deleted.
     */
    @Test
    void copyTreeRefusesMidCopyWhenAFileYieldsMoreThanItReported() throws Exception {
        assumeFalse(WINDOWS, "a FIFO cannot be created reliably on Windows");
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "12345");
        FileOperationsTest.namedPipe(dir.resolve("from/pipe"));

        Thread writer = new Thread(() -> {
            try (java.io.OutputStream out = Files.newOutputStream(dir.resolve("from/pipe"))) {
                out.write(new byte[64]);
            } catch (java.io.IOException stopped) {
                // The copy stops reading; the writer's own failure is not what this asserts.
            }
        });
        writer.setDaemon(true);
        writer.start();

        OqError error = assertThrows(OqError.class, () -> FileSystem.copyTree(host(),
                dir.resolve("from"), dir.resolve("to"), Span.synthetic(), 100, 16));

        assertTrue(error.getMessage().contains("directory tree is too large to copy"),
                error.getMessage());
        assertTrue(error.hint().contains("took the tree past the 16 byte limit"), error.hint());
        assertTrue(error.hint().contains("delete the partial target"), error.hint());
        assertTrue(Files.exists(dir.resolve("to")),
                "the refusal happens mid-copy, so a partial target is left to delete");
        writer.interrupt();
    }

    /** A tree under the budget is charged for the bytes it moved, not for its entry count. */
    @Test
    void copyTreeChargesForBytesRatherThanEntries() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "0123456789");
        Files.writeString(dir.resolve("from/b.txt"), "0123456789");
        FileSystem.copyTree(host(), dir.resolve("from"), dir.resolve("to"), Span.synthetic(),
                100, 1024);
        assertEquals(10, Files.size(dir.resolve("to/a.txt")));
        assertEquals(10, Files.size(dir.resolve("to/b.txt")));
    }

    /** The real figures are the ones copy_file uses for a single file, and they refuse nothing here. */
    @Test
    void copyTreeAcceptsATreeOfOrdinarySize() throws Exception {
        Files.createDirectories(dir.resolve("from/x"));
        Files.writeString(dir.resolve("from/a.txt"), "1");
        Files.writeString(dir.resolve("from/x/b.txt"), "2");
        call("copy_tree", "from", "to");
        assertEquals("1", Files.readString(dir.resolve("to/a.txt")));
        assertEquals("2", Files.readString(dir.resolve("to/x/b.txt")));
    }

    private List<String> names(Path directory) throws Exception {
        List<String> names = new ArrayList<>();
        try (var list = Files.list(directory)) {
            list.sorted().forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }

    // --- read_lines_or_empty ------------------------------------------------------------------

    @Test
    void readLinesOrEmptyReturnsNothingForAMissingFile() {
        assertEquals(List.of(), call("read_lines_or_empty", "nope.txt"));
    }

    @Test
    void readLinesOrEmptyReturnsTheLinesOfAFileThatIsThere() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        assertEquals(List.of("one", "two"), call("read_lines_or_empty", "a.txt"));
    }

    @Test
    void readLinesStillRaisesSoOnlyTheNamedFormIsTolerant() {
        // The tolerant behaviour is confined to a name that says so. If this ever stops raising,
        // the two forms have become the same function under two names.
        assertThrows(OqError.class, () -> call("read_lines", "nope.txt"));
    }

    // --- path_absolute and path_normalize -----------------------------------------------------

    @Test
    void pathAbsoluteMakesARelativePathAbsoluteAndCollapsesDotDot() {
        String expected = dir.toAbsolutePath().resolve("b").toString();
        assertEquals(expected, call("path_absolute", "a/../b"));
    }

    @Test
    void pathAbsoluteLeavesAnAbsolutePathAbsolute() {
        String absolute = dir.toAbsolutePath().toString();
        String result = (String) call("path_absolute", absolute + "/./x/../y");
        assertEquals(dir.toAbsolutePath().resolve("y").toString(), result);
        assertTrue(Path.of(result).isAbsolute());
    }

    @Test
    void pathNormalizeIsLexicalAndDoesNotTouchTheFilesystem() {
        // Spelled through Path.of so the assertion is about normalisation rather than about the
        // host separator: path_normalize returns Path.toString(), which is `a\b` on Windows.
        // Only glob() promises `/` on every host, and that is stated separately in its own test.
        assertEquals(Path.of("a/b").toString(), call("path_normalize", "a/./b"));
        assertEquals(Path.of("b").toString(), call("path_normalize", "a/../b"));
        assertEquals(Path.of("a").toString(), call("path_normalize", "a/."));
        // A path that does not exist still normalizes, which is what makes this usable before
        // the file is created.
        assertEquals(Path.of("no/such/file").toString(),
                call("path_normalize", "no/./such/x/../file"));
    }

    @Test
    void pathNormalizeKeepsARelativePathRelativeAndAnAbsoluteOneAbsolute() {
        assertEquals(Path.of("/a/../b").normalize().toString(),
                call("path_normalize", "/a/../b"));
        assertFalse(Path.of((String) call("path_normalize", "a/b")).isAbsolute());
    }

    @Test
    void pathNormalizeSpellsTheCurrentDirectoryAsDot() {
        // Path.of("").normalize() and Path.of(".").normalize() both produce an empty string,
        // which no other function here would accept back as a path.
        assertEquals(".", call("path_normalize", ""));
        assertEquals(".", call("path_normalize", "."));
    }

    @Test
    void aPathWithANulCharacterIsRefusedByName() {
        OqError error = assertThrows(OqError.class, () -> call("path_normalize", "a\u0000b"));
        assertTrue(error.getMessage().startsWith("not a path"), error.getMessage());
        OqError absolute = assertThrows(OqError.class, () -> call("path_absolute", "a\u0000b"));
        assertTrue(absolute.getMessage().startsWith("path_absolute() needs a path"),
                absolute.getMessage());
    }

    @Test
    void copyTreeCopiesAFileAsAFileRatherThanAsALink() throws Exception {
        Files.createDirectories(dir.resolve("from"));
        Files.writeString(dir.resolve("from/a.txt"), "1");
        call("copy_tree", "from", "to");
        assertTrue(Files.isRegularFile(dir.resolve("to/a.txt"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(dir.resolve("to/a.txt")));
    }
}
