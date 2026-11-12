package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Archive extraction as a security boundary (ADR-0048 D4).
 *
 * <p>Every refusal below is a real archive produced for this test, not a mock, because the defect
 * being guarded against lives in how an archive is read. A test that stubs the reader proves the
 * stub.
 */
class SafeArchiveTest {

    /** Builds a ZIP whose entries are exactly the names and contents given. */
    private static Path zip(Path directory, String name, Map<String, byte[]> entries)
            throws IOException {
        Path file = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().endsWith("/")) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.closeEntry();
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, byte[]> entries(String... names) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, ("contents of " + name).getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private static Path root(Path parent) throws IOException {
        return Files.createDirectories(parent.resolve("out"));
    }

    // --- the happy path, so the refusals below are not achieved by refusing everything ---

    @Test
    void extractsAnOrdinaryArchive(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "ok.zip", entries("bin/java", "lib/modules", "release"));
        Path out = root(temp);

        SafeArchive.extract(archive, out);

        assertEquals("contents of bin/java",
                Files.readString(out.resolve("bin").resolve("java")));
        assertEquals("contents of release", Files.readString(out.resolve("release")));
    }

    @Test
    void createsIntermediateDirectoriesTheArchiveOmits(@TempDir Path temp) throws IOException {
        // Real JDK archives carry directory entries, but an archive is not obliged to, and one
        // without them must still extract rather than fail on a missing parent.
        Path archive = zip(temp, "nodirs.zip", entries("a/b/c/d.txt"));
        Path out = root(temp);

        SafeArchive.extract(archive, out);

        assertTrue(Files.isRegularFile(out.resolve("a/b/c/d.txt")));
    }

    // --- traversal, in every spelling ---

    @Test
    void refusesParentTraversal(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "traverse.zip", entries("../escape.txt"));
        Path out = root(temp);

        SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(rejected.getMessage().contains("traversal"), rejected.getMessage());
        assertFalse(Files.exists(temp.resolve("escape.txt")), "nothing may land outside the root");
    }

    @Test
    void refusesNestedTraversal(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "nested.zip", entries("lib/../../escape.txt"));
        Path out = root(temp);

        assertThrows(SafeArchive.ArchiveRejected.class, () -> SafeArchive.extract(archive, out));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test
    void refusesAbsolutePaths(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "absolute.zip", entries("/etc/oq-escape.txt"));
        Path out = root(temp);

        SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(rejected.getMessage().contains("absolute"), rejected.getMessage());
    }

    @Test
    void refusesDriveQualifiedPaths(@TempDir Path temp) throws IOException {
        // Checked on every platform: the archive is not trusted to know which system reads it.
        Path archive = zip(temp, "drive.zip", entries("C:/Windows/oq-escape.txt"));
        Path out = root(temp);

        SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(rejected.getMessage().contains("drive-qualified"), rejected.getMessage());
    }

    @Test
    void refusesBackslashTraversal(@TempDir Path temp) throws IOException {
        // A Windows-written archive may use backslashes; normalising them is what makes the
        // traversal visible, so this asserts the normalisation happens before the check.
        Path archive = zip(temp, "backslash.zip", entries("..\\escape.txt"));
        Path out = root(temp);

        assertThrows(SafeArchive.ArchiveRejected.class, () -> SafeArchive.extract(archive, out));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test
    void refusesUncPaths(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "unc.zip", entries("//host/share/oq-escape.txt"));
        Path out = root(temp);

        assertThrows(SafeArchive.ArchiveRejected.class, () -> SafeArchive.extract(archive, out));
    }

    // --- collisions ---

    @Test
    void refusesDuplicateEntries(@TempDir Path temp) throws IOException {
        // Two entries with one name: the second silently replaces the first in a naive extractor,
        // which is how a benign-looking archive delivers a different payload than it showed.
        // Built by hand because `ZipOutputStream` refuses to write the archive an attacker would.
        Path archive = temp.resolve("dup.zip");
        Files.write(archive, rawZip(new String[]{"lib/x.jar", "lib/x.jar"},
                new String[]{"first", "second"}));
        Path out = root(temp);

        SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(rejected.getMessage().contains("duplicate"), rejected.getMessage());
    }

    @Test
    void refusesEntriesDifferingOnlyInCaseOnACaseInsensitiveFilesystem(@TempDir Path temp)
            throws IOException {
        // Only meaningful where the filesystem folds case; elsewhere the two names are genuinely
        // distinct files and both are legitimate.
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        boolean folding = os.startsWith("windows") || os.startsWith("mac");

        Path archive = zip(temp, "case.zip", entries("lib/Foo.jar", "lib/foo.jar"));
        Path out = root(temp);

        if (folding) {
            SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                    () -> SafeArchive.extract(archive, out));
            assertTrue(rejected.getMessage().contains("duplicate"), rejected.getMessage());
        } else {
            SafeArchive.extract(archive, out);
            assertTrue(Files.isRegularFile(out.resolve("lib/Foo.jar")));
            assertTrue(Files.isRegularFile(out.resolve("lib/foo.jar")));
        }
    }

    // --- symbolic links ---

    @Test
    void refusesArchiveSymbolicLinks(@TempDir Path temp) throws IOException {
        // Built by hand so the Unix mode is set exactly as `zip -y` sets it: 0xA1FF in the high
        // 16 bits of the external-attributes field.
        Path archive = temp.resolve("link.zip");
        Files.write(archive, linkArchive("link.txt", "/etc/passwd"));
        Path out = root(temp);

        SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(rejected.getMessage().contains("symbolic link"), rejected.getMessage());
        assertFalse(Files.exists(out.resolve("link.txt")));
    }

    @Test
    void refusesToWriteThroughAnExistingSymbolicLink(@TempDir Path temp) throws IOException {
        // The archive cannot create this link; a previous run or another process did. Writing
        // through it is the escape, so the extractor must notice the link on the path.
        Path out = root(temp);
        Path outside = Files.createDirectories(temp.resolve("outside"));
        try {
            Files.createSymbolicLink(out.resolve("lib"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // The platform will not create links; the archive-link test still applies.
        }

        Path archive = zip(temp, "through.zip", entries("lib/x.txt"));

        assertThrows(SafeArchive.ArchiveRejected.class, () -> SafeArchive.extract(archive, out));
        assertFalse(Files.exists(outside.resolve("x.txt")), "nothing may be written outside");
    }

    // --- malformed and degenerate archives ---

    @Test
    void refusesAnArchiveWithNoEntries(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "empty.zip", Map.of());
        Path out = root(temp);

        SafeArchive.ArchiveRejected rejected = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(rejected.getMessage().contains("no entries"), rejected.getMessage());
    }

    @Test
    void refusesANameWithANulByte(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "nul.zip", entries("lib/a\u0000b.txt"));
        Path out = root(temp);

        assertThrows(SafeArchive.ArchiveRejected.class, () -> SafeArchive.extract(archive, out));
    }

    @Test
    void refusesAMissingArchive(@TempDir Path temp) {
        Path out;
        try {
            out = root(temp);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThrows(IOException.class,
                () -> SafeArchive.extract(temp.resolve("absent.zip"), out));
    }

    @Test
    void refusesWhenTheExtractionRootIsNotADirectory(@TempDir Path temp) throws IOException {
        Path archive = zip(temp, "ok2.zip", entries("a.txt"));
        Path file = Files.writeString(temp.resolve("notadir"), "x");

        assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, file));
    }

    @Test
    void aRefusedArchiveLeavesNoWrittenEntryBehind(@TempDir Path temp) throws IOException {
        // The dangerous half-extract: `bin/java` was already written when the second entry is
        // refused. A later run must not find it and believe a runtime is installed.
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("bin/java", "partial".getBytes(StandardCharsets.UTF_8));
        map.put("../escape.txt", "bad".getBytes(StandardCharsets.UTF_8));
        Path archive = zip(temp, "partial.zip", map);
        Path out = root(temp);

        assertThrows(SafeArchive.ArchiveRejected.class, () -> SafeArchive.extract(archive, out));
        assertFalse(Files.exists(out.resolve("bin/java")), "no partial runtime may survive");
    }

    /** An ordinary ZIP built byte by byte, so names may repeat. */
    private static byte[] rawZip(String[] names, String[] contents) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        int[] offsets = new int[names.length];
        int[] crcs = new int[names.length];
        byte[][] nameBytes = new byte[names.length][];
        byte[][] bodyBytes = new byte[names.length][];

        for (int i = 0; i < names.length; i++) {
            nameBytes[i] = names[i].getBytes(StandardCharsets.UTF_8);
            bodyBytes[i] = contents[i].getBytes(StandardCharsets.UTF_8);
            java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
            checksum.update(bodyBytes[i]);
            crcs[i] = (int) checksum.getValue();
            offsets[i] = buffer.size();

            buffer.write(le32(0x04034b50));
            buffer.write(le16(20));
            buffer.write(le16(0));
            buffer.write(le16(0));
            buffer.write(le16(0));
            buffer.write(le16(0));
            buffer.write(le32(crcs[i]));
            buffer.write(le32(bodyBytes[i].length));
            buffer.write(le32(bodyBytes[i].length));
            buffer.write(le16(nameBytes[i].length));
            buffer.write(le16(0));
            buffer.write(nameBytes[i]);
            buffer.write(bodyBytes[i]);
        }

        int centralStart = buffer.size();
        for (int i = 0; i < names.length; i++) {
            central.write(le32(0x02014b50));
            central.write(le16(0x031E));
            central.write(le16(20));
            central.write(le16(0));
            central.write(le16(0));
            central.write(le16(0));
            central.write(le16(0));
            central.write(le32(crcs[i]));
            central.write(le32(bodyBytes[i].length));
            central.write(le32(bodyBytes[i].length));
            central.write(le16(nameBytes[i].length));
            central.write(le16(0));
            central.write(le16(0));
            central.write(le16(0));
            central.write(le16(0));
            central.write(le32(0x81A4_0000));
            central.write(le32(offsets[i]));
            central.write(nameBytes[i]);
        }
        byte[] centralBytes = central.toByteArray();
        buffer.write(centralBytes);

        buffer.write(le32(0x06054b50));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(names.length));
        buffer.write(le16(names.length));
        buffer.write(le32(centralBytes.length));
        buffer.write(le32(centralStart));
        buffer.write(le16(0));

        return buffer.toByteArray();
    }

    /**
     * A one-entry ZIP whose entry is marked S_IFLNK, with the link target as its contents.
     *
     * <p>Written as raw bytes because {@link ZipEntry} has no API for the Unix mode, and the mode
     * is the whole point of the test.
     */
    private static byte[] linkArchive(String name, String target) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
        checksum.update(targetBytes);
        int crc = (int) checksum.getValue();

        // Local file header.
        buffer.write(le32(0x04034b50));
        buffer.write(le16(20));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le32(crc));
        buffer.write(le32(targetBytes.length));
        buffer.write(le32(targetBytes.length));
        buffer.write(le16(nameBytes.length));
        buffer.write(le16(0));
        buffer.write(nameBytes);
        buffer.write(targetBytes);

        int centralStart = buffer.size();

        // Central directory record: the external attributes carry the Unix mode.
        buffer.write(le32(0x02014b50));
        buffer.write(le16(0x031E));      // made by: Unix, version 3.0
        buffer.write(le16(20));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le32(crc));
        buffer.write(le32(targetBytes.length));
        buffer.write(le32(targetBytes.length));
        buffer.write(le16(nameBytes.length));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le32(0xA1FF_0000)); // S_IFLNK | 0777 in the high 16 bits
        buffer.write(le32(0));
        buffer.write(nameBytes);

        int centralSize = buffer.size() - centralStart;

        // End of central directory.
        buffer.write(le32(0x06054b50));
        buffer.write(le16(0));
        buffer.write(le16(0));
        buffer.write(le16(1));
        buffer.write(le16(1));
        buffer.write(le32(centralSize));
        buffer.write(le32(centralStart));
        buffer.write(le16(0));

        return buffer.toByteArray();
    }

    private static byte[] le16(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8)};
    }

    private static byte[] le32(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8),
                (byte) (value >>> 16), (byte) (value >>> 24)};
    }
}
