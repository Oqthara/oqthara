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

    @Test
    void copyRefusesAnExistingTargetRatherThanOverwritingIt() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hi");
        Files.writeString(dir.resolve("c.txt"), "keep");
        OqError error = assertThrows(OqError.class, () -> call("copy_file", "a.txt", "c.txt"));
        assertTrue(error.getMessage().contains("already exists"), error.getMessage());
        assertEquals("keep", Files.readString(dir.resolve("c.txt")));
    }

    @Test
    void moveRemovesTheSource() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hi");
        call("move_file", "a.txt", "d.txt");
        assertFalse(Files.exists(dir.resolve("a.txt")));
        assertEquals("hi", Files.readString(dir.resolve("d.txt")));
    }

    // --- Traversal ---------------------------------------------------------------------------

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
