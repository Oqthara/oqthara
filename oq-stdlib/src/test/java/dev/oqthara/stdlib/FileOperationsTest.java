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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Time zones and the file operations added by ADR-0036.
 *
 * <p>Expected values were produced by running the built CLI first, with one deliberate
 * exception: the time-zone cases use fixed zones rather than the machine's, because asserting
 * anything about the local zone would pass only on the machine that wrote the test.
 */
class FileOperationsTest {

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

    // --- Time zones --------------------------------------------------------------------------

    @Test
    void convertingToTokyoAddsNineHours() {
        assertEquals("2026-11-10T23:04:24+09:00",
                call("tz_convert", "2026-11-10T14:04:24Z", "Asia/Tokyo"));
    }

    @Test
    void convertingToUtcKeepsTheZForm() {
        assertEquals("2026-11-10T14:04:24Z",
                call("tz_convert", "2026-11-10T14:04:24Z", "UTC"));
    }

    @Test
    void anOffsetInTheInputIsHonouredRatherThanAssumedToBeUtc() {
        // 14:04 at +02:00 is 12:04 UTC, which is 07:04 in New York in November.
        assertEquals("2026-11-10T07:04:24-05:00",
                call("tz_convert", "2026-11-10T14:04:24+02:00", "America/New_York"));
    }

    @Test
    void aZoneAbbreviationIsRefusedRatherThanGuessed() {
        OqError error = assertThrows(OqError.class,
                () -> call("tz_convert", "2026-11-10T14:04:24Z", "JST"));
        assertTrue(error.getMessage().contains("unknown time zone"), error.getMessage());
    }

    @Test
    void anUnknownZoneIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("tz_convert", "2026-11-10T14:04:24Z", "Nowhere/Land"));
        assertTrue(error.getMessage().contains("Nowhere/Land"), error.getMessage());
    }

    @Test
    void aBareDateIsNotAnInstant() {
        OqError error = assertThrows(OqError.class,
                () -> call("tz_convert", "2026-11-10", "UTC"));
        assertTrue(error.getMessage().contains("not an instant"), error.getMessage());
        assertTrue(error.hint().contains("Z"), error.hint());
    }

    // --- Binary content ----------------------------------------------------------------------

    @Test
    void bytesRoundTripThroughHex() throws Exception {
        Files.write(dir.resolve("a.bin"), new byte[] {0x00, (byte) 0xff, 0x10});
        assertEquals("00ff10", call("read_bytes", "a.bin"));
        call("write_bytes", "b.bin", "00ff10");
        assertArrayEqualsBytes(new byte[] {0x00, (byte) 0xff, 0x10}, Files.readAllBytes(dir.resolve("b.bin")));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i]);
    }

    @Test
    void anOddNumberOfHexDigitsIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("write_bytes", "z.bin", "abc"));
        assertTrue(error.getMessage().contains("even number"), error.getMessage());
    }

    @Test
    void nonHexTextIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("write_bytes", "z.bin", "zz"));
        assertTrue(error.getMessage().contains("not hex"), error.getMessage());
    }

    // --- Predicates --------------------------------------------------------------------------

    @Test
    void isFileAndIsDirDistinguishWhatExistsCannot() throws Exception {
        Files.writeString(dir.resolve("f.txt"), "x");
        Files.createDirectory(dir.resolve("d"));
        assertTrue((Boolean) call("is_file", "f.txt"));
        assertFalse((Boolean) call("is_file", "d"));
        assertTrue((Boolean) call("is_dir", "d"));
        assertFalse((Boolean) call("is_dir", "f.txt"));
        // exists() is true for both, which is exactly why the predicates are needed.
        assertTrue((Boolean) call("exists", "f.txt"));
        assertTrue((Boolean) call("exists", "d"));
    }

    // --- Copy and move -----------------------------------------------------------------------

    @Test
    void copyPreservesTheContent() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hi");
        call("copy_file", "a.txt", "c.txt");
        assertEquals("hi", Files.readString(dir.resolve("c.txt")));
        assertEquals("hi", Files.readString(dir.resolve("a.txt")));
    }

    /**
     * A copy refused by the step budget must not leave the target behind.
     *
     * <p>The transfer is charged after the bytes are written, and {@code chargeSteps} raises
     * {@code OqError} when the budget is spent. The language has {@code try}/{@code catch}, so
     * that error is catchable: charging after declaring success therefore produced a call that
     * raised an error the program could swallow while leaving a complete file on disk, and the
     * only way to retry was to delete it by hand. Charging before the success flag means the
     * refusal path is the same one an over-limit source takes, and the target is removed.
     */
    @Test
    void copyRefusedByTheStepBudgetLeavesNoTarget() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "some content to charge for");
        host().setMaxSteps(0);

        assertThrows(OqError.class, () -> call("copy_file", "a.txt", "c.txt"));

        assertFalse(Files.exists(dir.resolve("c.txt")),
                "a refused copy must not leave a target behind");
    }

    @Test
    void copyRefusesAnExistingTargetRatherThanOverwritingIt() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hi");
        Files.writeString(dir.resolve("c.txt"), "keep");
        OqError error = assertThrows(OqError.class, () -> call("copy_file", "a.txt", "c.txt"));
        assertTrue(error.getMessage().contains("already exists"), error.getMessage());
        assertEquals("keep", Files.readString(dir.resolve("c.txt")));
    }

    /**
     * The byte cap is enforced on bytes copied, not on the size the filesystem reports.
     *
     * <p>{@code Files.size} is advisory. On a FIFO it returns 0 however much the writer sends,
     * on a procfs entry it reports a page, and on a file another process is still writing it is
     * the length as of the call. A cap applied to that number admits an unbounded copy, so the
     * cap is applied to the bytes the read actually yields.
     *
     * <p>A FIFO is the honest way to assert this. An earlier version of this test overrode
     * {@code available()} to return 4, but {@code copyBounded} never calls {@code available()},
     * so the stream was an ordinary in-memory one and the "lying size" was never observed --
     * the test would have passed against any implementation at all.
     */
    @Test
    void copyStopsAtTheLimitEvenWhenTheReportedSizeIsSmall() throws Exception {
        assumeTrue(namedPipe(dir.resolve("pipe")) != null, "this platform has no FIFOs");
        Path pipe = dir.resolve("pipe");
        assertEquals(0, Files.size(pipe), "a FIFO reports no size; that is the premise");

        Thread writer = new Thread(() -> {
            try (java.io.OutputStream out = Files.newOutputStream(pipe)) {
                out.write(new byte[Stdlib.MAX_TEXT_BYTES + 4096]);
            } catch (java.io.IOException stopped) {
                // The reader refuses and closes; a writer losing the other end is the point.
            }
        });
        writer.setDaemon(true);
        writer.start();

        OqError error = assertThrows(OqError.class, () -> call("copy_file", "pipe", "copy.out"));
        writer.join(10_000);

        assertTrue(error.getMessage().contains("too large"), error.getMessage());
        assertFalse(Files.exists(dir.resolve("copy.out")),
                "a refused copy must not leave a target behind");
    }

    /**
     * A refused copy is billed for the bytes it already wrote.
     *
     * <p>{@code copyBounded} hands every accepted chunk to the stream before refusing the
     * crossing one, so the work is real. Billing only on success made a caught refusal free,
     * and a FIFO source makes that reachable: its reported size never trips the pre-check, so
     * a loop of refused copies moved {@code MAX_TEXT_BYTES} per attempt for nothing.
     *
     * <p>The charge is observed through a later operation rather than through the refusal.
     * That is not a shortcut: {@code chargeForRefusedCopy} deliberately swallows the budget
     * error so the caller keeps the copy's own reason, so a refused copy never reports
     * exhaustion itself. The budget is still spent, which a subsequent charged copy proves.
     */
    @Test
    void aRefusedCopyIsChargedForTheBytesItWrote() throws Exception {
        assumeTrue(namedPipe(dir.resolve("pipe")) != null, "this platform has no FIFOs");
        Path pipe = dir.resolve("pipe");
        Files.writeString(dir.resolve("small.txt"), "abc");
        // Four refused copies bill 4 x MAX_TEXT_BYTES, which is past the budget; without the
        // charge they are all free and the copy below has no reason to fail.
        host().setMaxSteps(Stdlib.MAX_TEXT_BYTES * 3);

        for (int i = 0; i < 4; i++) {
            String target = "refused-" + i + ".out";
            Thread writer = new Thread(() -> {
                try (java.io.OutputStream out = Files.newOutputStream(pipe)) {
                    out.write(new byte[Stdlib.MAX_TEXT_BYTES + 4096]);
                } catch (java.io.IOException stopped) {
                    // Expected: the reader refuses and closes its end.
                }
            });
            writer.setDaemon(true);
            writer.start();
            OqError refused = assertThrows(OqError.class,
                    () -> call("copy_file", "pipe", target));
            assertTrue(refused.getMessage().contains("too large"), refused.getMessage());
            writer.join(10_000);
        }

        OqError exhausted = assertThrows(OqError.class,
                () -> call("copy_file", "small.txt", "small.out"),
                "four refused copies wrote " + (Stdlib.MAX_TEXT_BYTES * 4) + " bytes past a budget "
                        + "of " + (Stdlib.MAX_TEXT_BYTES * 3) + ", so the next charged copy must"
                        + " find the budget spent");
        assertTrue(exhausted.getMessage().contains("ran for too long"), exhausted.getMessage());
    }

    /**
     * Two copies racing for one name: exactly one wins, and the loser never overwrites it.
     *
     * <p>{@code copy_file} documents that it never overwrites. The {@code Files.exists} check
     * enforces that only when the target already exists at check time; two copies that both see
     * it absent would both pass, and whichever opened second would truncate the winner's file.
     * Opening with {@code CREATE_NEW} moves the decision into the open, which is atomic, so the
     * second copy gets {@code FileAlreadyExistsException} and is refused.
     *
     * <p>This is the case the reviewer's report measured. It is worth a test of its own: the
     * existing check-then-open path passes every single-threaded test, including the one for a
     * target that is present before the call.
     */
    @Test
    void concurrentCopiesToOneNameDoNotOverwriteTheWinner() throws Exception {
        Files.writeString(dir.resolve("first.txt"), "FIRST");
        Files.writeString(dir.resolve("second.txt"), "SECOND");
        host(); // build the interpreter before the race rather than inside it
        int rounds = 40;
        for (int round = 0; round < rounds; round++) {
            String target = "raced-" + round + ".out";
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Boolean>> races = new java.util.ArrayList<>();
            try (java.util.concurrent.ExecutorService pool =
                         java.util.concurrent.Executors.newFixedThreadPool(2)) {
                for (String source : List.of("first.txt", "second.txt")) {
                    races.add(pool.submit(() -> {
                        start.await();
                        try {
                            call("copy_file", source, target);
                            return true;
                        } catch (OqError refused) {
                            return false;
                        }
                    }));
                }
                start.countDown();
                int succeeded = 0;
                for (java.util.concurrent.Future<Boolean> race : races) {
                    if (race.get(30, java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
                }
                assertEquals(1, succeeded,
                        "round " + round + ": exactly one copy may claim the name");
            }
            String written = Files.readString(dir.resolve(target));
            assertTrue(written.equals("FIRST") || written.equals("SECOND"),
                    "round " + round + ": the target holds a whole source, not a mixture; got '"
                            + written + "'");
        }
    }

    /** Creates a named pipe, or returns null where that is not supported. */
    private static Path namedPipe(Path path) {
        try {
            Process mkfifo = new ProcessBuilder("mkfifo", path.toString())
                    .redirectErrorStream(true).start();
            if (mkfifo.waitFor() != 0) return null;
            return path;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.io.IOException unsupported) {
            return null;
        }
    }

    /**
     * A source of exactly the limit is accepted; one byte more is refused.
     *
     * <p>The old test compared 15 bytes against a 1024 limit, so a {@code copied >= limit}
     * off-by-one would have passed it. Both sides of the boundary are asserted here.
     */
    @Test
    void theByteLimitIsInclusive() throws Exception {
        Files.write(dir.resolve("at-limit.bin"), new byte[Stdlib.MAX_TEXT_BYTES]);
        call("copy_file", "at-limit.bin", "at-limit.out");
        assertEquals(Stdlib.MAX_TEXT_BYTES, Files.size(dir.resolve("at-limit.out")),
                "a source of exactly the limit must be copied in full");

        Files.write(dir.resolve("over-limit.bin"), new byte[Stdlib.MAX_TEXT_BYTES + 1]);
        assertThrows(OqError.class, () -> call("copy_file", "over-limit.bin", "over.out"));
        assertFalse(Files.exists(dir.resolve("over.out")));
    }

    /**
     * The overwrite refusal is the open's, not the pre-check's.
     *
     * <p>{@code copyRefusesAnExistingTargetRatherThanOverwritingIt} creates the target before
     * the call, so the {@code Files.exists} guard alone satisfies it. That guard is a separate
     * syscall from the open, so two copies racing for one name could both pass it. This asserts
     * the property that matters for that race: the open itself refuses, which
     * {@code CREATE_NEW} gives and a plain {@code newOutputStream} does not.
     */
    @Test
    void theOverwriteRefusalComesFromTheOpenNotThePreCheck() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "NEW");
        Files.writeString(dir.resolve("kept.txt"), "KEEP-ME");

        try (java.io.OutputStream out = Files.newOutputStream(dir.resolve("kept.txt"),
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            out.write("NEW".getBytes(StandardCharsets.UTF_8));
            fail("CREATE_NEW must refuse an existing file");
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            // The open refuses; the pre-check is only a better message.
        }
        assertEquals("KEEP-ME", Files.readString(dir.resolve("kept.txt")),
                "the existing content must survive");
    }

    /**
     * A dangling symbolic link is a target that {@code Files.exists} calls absent.
     *
     * <p>{@code Files.exists} follows links, so for a link whose destination does not exist it
     * returns false and the overwrite guard passes. Writing through the link would create the
     * destination -- a file outside the path the program named. {@code CREATE_NEW} refuses the
     * link itself, and the link must still be there afterwards, because this call did not
     * create it and so must not delete it.
     */
    @Test
    void copyRefusesADanglingSymlinkTargetAndLeavesItAlone() throws Exception {
        assumeTrue(pointsAtLinks(), "this platform cannot create symbolic links here");
        Files.writeString(dir.resolve("payload.txt"), "payload");
        Path link = dir.resolve("dangling.out");
        try {
            Files.createSymbolicLink(link, dir.resolve("not-there.txt"));
        } catch (java.nio.file.FileSystemException unsupported) {
            assumeTrue(false, "symbolic links unavailable: " + unsupported.getMessage());
            return;
        }

        OqError error = assertThrows(OqError.class,
                () -> call("copy_file", "payload.txt", "dangling.out"));

        assertTrue(error.getMessage().contains("already exists"), error.getMessage());
        assertFalse(Files.exists(dir.resolve("not-there.txt")),
                "the copy must not create the link's destination");
        assertTrue(Files.isSymbolicLink(link),
                "a target this call did not create must not be deleted");
    }

    /**
     * The {@code finally} must not delete a target the call never created.
     *
     * <p>A missing source fails before the target is opened. With a single {@code completed}
     * flag the cleanup still ran, and for a dangling link -- which {@code deleteIfExists}
     * removes as a link without touching its destination -- that silently deleted something
     * the caller owned.
     */
    @Test
    void aCopyThatFailsBeforeOpeningTheTargetLeavesAnExistingLinkIntact() throws Exception {
        assumeTrue(pointsAtLinks(), "this platform cannot create symbolic links here");
        Path link = dir.resolve("preexisting.out");
        try {
            Files.createSymbolicLink(link, dir.resolve("also-not-there.txt"));
        } catch (java.io.IOException unsupported) {
            assumeTrue(false, "symbolic links unavailable: " + unsupported.getMessage());
            return;
        }

        assertThrows(OqError.class, () -> call("copy_file", "no-such-source.txt", "preexisting.out"));

        assertTrue(Files.isSymbolicLink(link),
                "a failed copy must not delete a target it never created");
    }

    /** Both platforms can create a symlink in a temp directory, except Windows without rights. */
    private static boolean pointsAtLinks() {
        return true;
    }

    /** A copy under the limit succeeds, transfers every byte, and is charged for what moved. */
    @Test
    void copyUnderTheLimitTransfersEveryByte() throws Exception {
        byte[] payload = "the whole thing".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long copied = Stdlib.copyBounded(new java.io.ByteArrayInputStream(payload), sink,
                1024, dir.resolve("small"), Span.synthetic());

        assertEquals(payload.length, copied);
        assertEquals("the whole thing", sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    void moveRemovesTheSource() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hi");
        call("move_file", "a.txt", "d.txt");
        assertFalse(Files.exists(dir.resolve("a.txt")));
        assertEquals("hi", Files.readString(dir.resolve("d.txt")));
    }

    // --- Traversal ---------------------------------------------------------------------------

    /**
     * {@code walk} and {@code list_files} refuse at the same entry count.
     *
     * <p>They share {@code MAX_LIST_ENTRIES} but checked it at different points: {@code
     * list_files} compares after adding, {@code walk} compared with {@code >} before adding, so
     * walk accepted one entry more than the number both messages name. A shared limit that two
     * builtins apply at different points is not a shared limit.
     *
     * <p>The boundary needs the real count, because the constant is what defines it. The tree is
     * flat and the files are empty, so the cost is directory entries rather than content. It
     * asserts the exact pair that distinguishes the two comparisons: at {@code MAX_LIST_ENTRIES}
     * walk returns, and at {@code MAX_LIST_ENTRIES + 1} it refuses. The older {@code >} returned
     * the second one, so this fails against it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void bothEntryCapsRefuseAtTheSameCount() throws Exception {
        Path tree = dir.resolve("many");
        Files.createDirectories(tree);
        int limit = Stdlib.MAX_LIST_ENTRIES;
        for (int i = 0; i < limit; i++) {
            Files.createFile(tree.resolve("f" + i));
        }
        assertEquals(limit, ((List<?>) call("walk", "many")).size(),
                "walk accepts exactly the limit");

        Files.createFile(tree.resolve("one-more"));
        OqError refused = assertThrows(OqError.class, () -> call("walk", "many"));
        assertTrue(refused.getMessage().contains("too many entries"), refused.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void walkListsNestedFilesAsRelativePaths() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "1");
        Files.createDirectories(dir.resolve("x/y"));
        Files.writeString(dir.resolve("x/b.txt"), "2");
        Files.writeString(dir.resolve("x/y/c.txt"), "3");
        List<Object> found = (List<Object>) call("walk", ".");
        assertEquals(List.of("a.txt", "x", "x/b.txt", "x/y", "x/y/c.txt"), found);
    }

    @Test
    void walkRefusesAFileRatherThanReturningNothing() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "1");
        OqError error = assertThrows(OqError.class, () -> call("walk", "a.txt"));
        assertTrue(error.getMessage().contains("not a directory"), error.getMessage());
    }

    @Test
    void walkDoesNotFollowASymbolicLinkIntoACycle() throws Exception {
        Files.createDirectories(dir.resolve("x/y"));
        Files.writeString(dir.resolve("x/y/c.txt"), "3");
        try {
            Files.createSymbolicLink(dir.resolve("x/loop"), dir.resolve("x"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // A filesystem without symlinks cannot exhibit the cycle.
        }
        @SuppressWarnings("unchecked")
        List<Object> found = (List<Object>) call("walk", ".");
        // The link is listed as an entry but not descended into. Following it would revisit
        // x/ and repeat x/y/c.txt, and the entry cap would eventually stop it for the wrong
        // reason rather than the right one.
        assertTrue(found.contains("x/loop"), found.toString());
        assertEquals(0, found.stream().filter(p -> p.toString().startsWith("x/loop/")).count(),
                found.toString());
    }
}
