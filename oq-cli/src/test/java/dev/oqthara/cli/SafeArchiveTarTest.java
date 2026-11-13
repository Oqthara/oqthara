package dev.oqthara.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tarball extraction as a security boundary (ADR-0048 D4).
 *
 * <p>Four of the five pinned runtime archives are gzipped tar, so this is the path almost every
 * user installs through. The same rule applies as for ZIP — nothing may land outside the
 * extraction root — and the identical refusals are exercised here because a second extractor is a
 * second place to get them wrong.
 */
class SafeArchiveTarTest {

    /** One entry in a test archive. */
    private record Entry(String name, char type, String linkTarget, byte[] contents, int mode) {
    }

    private static Entry file(String name, String contents) {
        return new Entry(name, '0', null, contents.getBytes(StandardCharsets.UTF_8), 0644);
    }

    private static Entry executable(String name, String contents) {
        return new Entry(name, '0', null, contents.getBytes(StandardCharsets.UTF_8), 0755);
    }

    private static Entry directory(String name) {
        return new Entry(name, '5', null, new byte[0], 0755);
    }

    private static Entry symlink(String name, String target) {
        return new Entry(name, '2', target, new byte[0], 0777);
    }

    private static Entry hardLink(String name, String target) {
        return new Entry(name, '1', target, new byte[0], 0644);
    }

    /** Builds a gzipped tar from entries, writing real headers with real checksums. */
    private static Path targz(Path directory, String name, Entry... entries) throws IOException {
        Path file = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            for (Entry entry : entries) {
                byte[] body = entry.type() == '2' || entry.type() == '1'
                        ? new byte[0] : entry.contents();
                gz.write(header(entry.name(), entry.type(), entry.linkTarget(), body.length,
                        entry.mode()));
                gz.write(body);
                int padding = (512 - (body.length % 512)) % 512;
                gz.write(new byte[padding]);
            }
            gz.write(new byte[1024]);
        }
        return file;
    }

    private static byte[] header(String name, char type, String linkTarget, long size, int mode) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        putOctal(header, 100, 8, mode);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, 0);
        header[156] = (byte) type;
        if (linkTarget != null) {
            byte[] link = linkTarget.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(link, 0, header, 157, Math.min(link.length, 100));
        }
        byte[] magic = "ustar  ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        // The checksum is computed over the header with its own field read as spaces.
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        putOctal(header, 148, 8, sum);
        return header;
    }

    /** Writes an octal field the way tar does: zero-padded digits, then a NUL or space. */
    private static void putOctal(byte[] header, int offset, int length, long value) {
        String digits = Long.toOctalString(value);
        StringBuilder field = new StringBuilder();
        for (int i = digits.length(); i < length - 1; i++) {
            field.append('0');
        }
        field.append(digits);
        byte[] bytes = field.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
        header[offset + length - 1] = 0;
    }

    private static Path root(Path parent) throws IOException {
        return Files.createDirectories(parent.resolve("out"));
    }

    @Test
    void extractsATarball(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                directory("jdk-21/"),
                directory("jdk-21/bin/"),
                executable("jdk-21/bin/java", "#!/bin/sh\necho hi\n"),
                file("jdk-21/release", "JAVA_VERSION=\"21\"\n"));
        Path out = root(temp);

        SafeArchive.extract(archive, out);

        assertEquals("JAVA_VERSION=\"21\"\n",
                Files.readString(out.resolve("jdk-21/release")));
        assertTrue(Files.isRegularFile(out.resolve("jdk-21/bin/java")));
        assertTrue(Files.isExecutable(out.resolve("jdk-21/bin/java")),
                "the archive's execute bit must survive extraction");
    }

    /**
     * A relative link inside the root is created.
     *
     * <p>This is the case that made tar support necessary: every Temurin tarball carries its
     * `legal/` cross-references as relative links, so refusing all links would leave no install
     * path on Linux or macOS at all.
     */
    @Test
    void createsARelativeLinkInsideTheRoot(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                directory("jdk-21/legal/java.base/"),
                file("jdk-21/legal/java.base/LICENSE", "the license"),
                directory("jdk-21/legal/jdk.jdeps/"),
                symlink("jdk-21/legal/jdk.jdeps/LICENSE", "../java.base/LICENSE"));
        Path out = root(temp);

        SafeArchive.extract(archive, out);

        Path link = out.resolve("jdk-21/legal/jdk.jdeps/LICENSE");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("the license", Files.readString(link),
                "the link must resolve to the file it names");
    }

    @Test
    void refusesAnAbsoluteLink(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                symlink("jdk-21/link", "/etc/passwd"));
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("absolute"), refused.getMessage());
    }

    /**
     * A link that leaves the root is refused.
     *
     * <p>The target is three levels up, not two: {@code ../../outside} from {@code jdk-21/bin/}
     * resolves to {@code <root>/outside}, which is inside and therefore allowed. The containment
     * test is lexical, so the number of levels is the thing that matters.
     */
    @Test
    void refusesALinkThatLeavesTheRoot(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                symlink("jdk-21/bin/link", "../../../outside"));
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("leaves the"), refused.getMessage());
    }

    @Test
    void refusesAHardLink(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                file("jdk-21/release", "x"),
                hardLink("jdk-21/hard", "jdk-21/release"));
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("hard link"), refused.getMessage());
    }

    @Test
    void refusesTraversalInATarball(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                file("../escape", "payload"));
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("traversal"), refused.getMessage());
        assertTrue(!Files.exists(temp.resolve("escape")), "nothing may be written outside the root");
    }

    @Test
    void refusesAnAbsolutePathInATarball(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                file("/etc/passwd", "payload"));
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("absolute"), refused.getMessage());
    }

    @Test
    void refusesADuplicatePathInATarball(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "runtime.tar.gz",
                file("jdk-21/release", "first"),
                file("jdk-21/release", "second"));
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("duplicate"), refused.getMessage());
    }

    /**
     * A header whose checksum does not match is refused.
     *
     * <p>Everything after the header trusts the size field to find the next one, so a corrupt
     * header is a stream-position error as much as an integrity one.
     */
    @Test
    void refusesACorruptHeader(@TempDir Path temp) throws IOException {
        Path good = targz(temp, "good.tar.gz", file("jdk-21/release", "x"));
        byte[] bytes = Files.readAllBytes(good);
        // Decompress, damage one byte of the first header, recompress.
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            in.transferTo(raw);
        }
        byte[] plain = raw.toByteArray();
        plain[0] = (byte) (plain[0] ^ 0xFF);
        Path damaged = temp.resolve("damaged.tar.gz");
        try (OutputStream out = Files.newOutputStream(damaged);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(plain);
        }
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(damaged, out));
        assertTrue(refused.getMessage().contains("checksum"), refused.getMessage());
    }

    /** The format is read from the bytes, so a tarball named `.zip` still extracts. */
    @Test
    void dispatchesOnContentRatherThanName(@TempDir Path temp) throws IOException {
        Path archive = targz(temp, "misnamed.zip", file("jdk-21/release", "content"));
        Path out = root(temp);
        SafeArchive.extract(archive, out);
        assertEquals("content", Files.readString(out.resolve("jdk-21/release")));
    }

    /** An empty gzip stream is refused with the same message a ZIP gets. */
    @Test
    void refusesAnEmptyTarball(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("empty.tar.gz");
        try (OutputStream out = Files.newOutputStream(archive);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(new byte[1024]);
        }
        Path out = root(temp);
        SafeArchive.ArchiveRejected refused = assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(archive, out));
        assertTrue(refused.getMessage().contains("no entries"), refused.getMessage());
    }

    /** An entry whose declared size runs past the end of the stream is refused. */
    @Test
    void refusesATruncatedEntry(@TempDir Path temp) throws IOException {
        Path good = targz(temp, "good.tar.gz", file("jdk-21/release", "0123456789"));
        byte[] bytes = Files.readAllBytes(good);
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            in.transferTo(raw);
        }
        byte[] plain = raw.toByteArray();
        // Keep the header, drop the payload: the declared size no longer has bytes behind it.
        byte[] truncated = java.util.Arrays.copyOf(plain, 512 + 2);
        Path damaged = temp.resolve("truncated.tar.gz");
        try (OutputStream out = Files.newOutputStream(damaged);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(truncated);
        }
        Path out = root(temp);
        assertThrows(SafeArchive.ArchiveRejected.class,
                () -> SafeArchive.extract(damaged, out));
    }

    /** A ZIP still extracts, so the dispatch did not replace one format with the other. */
    @Test
    void stillExtractsZip(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("runtime.zip");
        try (OutputStream out = Files.newOutputStream(archive);
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("jdk-21/release"));
            zip.write("zip content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path out = root(temp);
        SafeArchive.extract(archive, out);
        assertEquals("zip content", Files.readString(out.resolve("jdk-21/release")));
    }
}
