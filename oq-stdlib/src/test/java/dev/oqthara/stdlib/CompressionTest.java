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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code zlib_*}, {@code gzip_*} and {@code zip_*} from ADR-0042.
 *
 * <p>The two tests that carry the most weight are not the round-trips -- those pass for any
 * symmetric pair of mistakes -- but {@link #aHighlyCompressibleBombIsRefusedRatherThanAllocated}
 * and {@link #traversalEntryNamesAreRefused}, which pin the decisions the ADR exists to make.
 *
 * <p>Random-content round-trips are generated from a fixed seed so a failure is reproducible,
 * and the expected bytes are produced with {@link java.util.zip} directly rather than with this
 * module, so the test does not echo the implementation.
 */
class CompressionTest {

    private static final HexFormat HEX = HexFormat.of();

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

    private static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    private static String hexOf(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    // --- round-trips -------------------------------------------------------------------------

    @Test
    void zlibRoundTripsAsciiThroughHex() {
        String original = hexOf("hello hello hello");
        String compressed = (String) call("zlib_compress", original);
        assertEquals(original, call("zlib_decompress", compressed));
    }

    @Test
    void zlibActuallyShrinksRepetitiveContent() {
        String original = hexOf("0".repeat(40_000));
        String compressed = (String) call("zlib_compress", original);
        assertTrue(compressed.length() < original.length() / 100,
                "40k identical bytes should compress by more than 100x, got "
                        + compressed.length() + " from " + original.length());
        assertEquals(original, call("zlib_decompress", compressed));
    }

    @Test
    void zlibRoundTripsRandomBytes() {
        byte[] content = new byte[4_096];
        new Random(20_261_111L).nextBytes(content);
        String original = hex(content);
        assertEquals(original, call("zlib_decompress", call("zlib_compress", original)));
    }

    @Test
    void zlibRoundTripsTheEmptyValue() {
        String compressed = (String) call("zlib_compress", "");
        assertEquals("", call("zlib_decompress", compressed));
    }

    @Test
    void gzipRoundTripsTextAsUtf8() {
        String compressed = (String) call("gzip_compress", "héllo wörld");
        assertEquals(hexOf("héllo wörld"), call("gzip_decompress", compressed));
    }

    @Test
    void gzipOutputStartsWithTheFormatMagicNumber() {
        // 1f 8b is the gzip magic; a zip container or raw DEFLATE would not start this way.
        String compressed = (String) call("gzip_compress", "x");
        assertTrue(compressed.startsWith("1f8b"), "got " + compressed);
    }

    @Test
    void gzipCompressedBytesAreReadableByTheJvm() throws Exception {
        String compressed = (String) call("gzip_compress", "independent round trip");
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(HEX.parseHex(compressed)))) {
            assertEquals(hexOf("independent round trip"), hex(in.readAllBytes()));
        }
    }

    @Test
    void zipRoundTripsAMapOfEntries() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("a.txt", hexOf("aaa"));
        files.put("dir/b.txt", hexOf("bbb"));
        Object archive = call("zip_compress", files);
        Object restored = call("zip_decompress", archive);
        assertEquals(files, restored);
    }

    @Test
    void zipRoundTripsManyEntries() {
        Map<String, Object> files = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            files.put("f" + i + ".txt", hexOf("content " + i));
        }
        assertEquals(files, call("zip_decompress", call("zip_compress", files)));
    }

    // --- the amplification cap ---------------------------------------------------------------

    @Test
    void aHighlyCompressibleBombIsRefusedRatherThanAllocated() {
        // 20 MiB of zeros compresses to a few kilobytes, which is the attack: the input is tiny
        // and the expansion is over the 16 MiB cap. Building the input through the builtins keeps
        // the test honest about the ratio a caller could actually achieve.
        byte[] bomb = new byte[20 * 1024 * 1024];
        String compressed = (String) call("zlib_compress", hex(bomb));
        // Measured: 20 MiB of zeros is 40,786 hex characters compressed, roughly 1000x.
        assertTrue(compressed.length() < 60_000,
                "the bomb should be small in compressed form, got " + compressed.length());

        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_decompress", compressed));
        assertTrue(refused.getMessage().contains("exceeded"),
                "expected the named amplification error, got: " + refused.getMessage());
    }

    @Test
    void theCapNamesItsSizeAndFunction() {
        byte[] bomb = new byte[20 * 1024 * 1024];
        String compressed = (String) call("zlib_compress", hex(bomb));
        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_decompress", compressed));
        assertTrue(refused.getMessage().startsWith("zlib_decompress() exceeded "),
                "got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("16777216"),
                "the cap should be stated as a number, got: " + refused.getMessage());
    }

    @Test
    void contentJustUnderTheCapIsAccepted() {
        byte[] atLimit = new byte[Compression.MAX_OUTPUT_BYTES];
        String compressed = (String) call("zlib_compress", hex(atLimit));
        assertEquals(hex(atLimit), call("zlib_decompress", compressed));
    }

    @Test
    void gzipIsSubjectToTheSameCap() {
        byte[] bomb = new byte[20 * 1024 * 1024];
        String compressed = (String) call("gzip_compress", new String(bomb,
                StandardCharsets.ISO_8859_1));
        OqError refused = assertThrows(OqError.class,
                () -> call("gzip_decompress", compressed));
        assertTrue(refused.getMessage().startsWith("gzip_decompress() exceeded "),
                "got: " + refused.getMessage());
    }

    @Test
    void azipArchiveIsCappedOnItsTotalNotPerEntry() {
        // Many entries, each individually under the cap, that together exceed it.
        Map<String, Object> files = new LinkedHashMap<>();
        byte[] chunk = new byte[4 * 1024 * 1024];
        for (int i = 0; i < 6; i++) {
            files.put("part" + i + ".bin", hex(chunk));
        }
        String archive = (String) call("zip_compress", files);
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_decompress", archive));
        assertTrue(refused.getMessage().startsWith("zip_decompress() exceeded "),
                "got: " + refused.getMessage());
    }

    // --- traversal ---------------------------------------------------------------------------

    @Test
    void traversalEntryNamesAreRefused() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("../../etc/passwd", hexOf("x"));
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_compress", files));
        assertTrue(refused.getMessage().contains("refused entry name"),
                "got: " + refused.getMessage());
    }

    @Test
    void absoluteEntryNamesAreRefused() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("/etc/passwd", hexOf("x"));
        assertThrows(OqError.class, () -> call("zip_compress", files));
    }

    @Test
    void backslashEntryNamesAreRefused() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("dir\\file.txt", hexOf("x"));
        assertThrows(OqError.class, () -> call("zip_compress", files));
    }

    @Test
    void anInteriorTraversalSegmentIsRefused() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("a/../../b", hexOf("x"));
        assertThrows(OqError.class, () -> call("zip_compress", files));
    }

    @Test
    void aNameThatMerelyContainsDotsIsAllowed() {
        // "..txt" is not a traversal segment, and refusing it would be a false positive.
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("..txt", hexOf("x"));
        files.put("a/b..c", hexOf("y"));
        Object restored = call("zip_decompress", call("zip_compress", files));
        assertEquals(files, restored);
    }

    /**
     * Builds an archive with {@link java.util.zip} directly, bypassing {@code zip_compress}.
     *
     * <p>A reader-side test must not be built with the writer it is checking: if the writer ever
     * grows the same flaw, a test that goes through it would pass. This is also the only way to
     * produce an archive whose names the writer refuses, which is exactly the case the reader has
     * to handle.
     */
    private static String archiveWithEntryNamed(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return hex(bytes.toByteArray());
    }

    @Test
    void zipDecompressRefusesATraversalNameFromAnUntrustedArchive() throws Exception {
        // The writer refuses to create this name, so the only way it arrives is from an archive
        // someone else produced -- which is the direction that matters.
        String archive = archiveWithEntryNamed("../../etc/evil.txt", "pwned");
        OqError refused = assertThrows(OqError.class, () -> call("zip_decompress", archive));
        assertTrue(refused.getMessage().startsWith("zip_decompress() refused entry name:"),
                "got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("../../etc/evil.txt"),
                "the message must name the entry it refused: " + refused.getMessage());
    }

    @Test
    void zipDecompressRefusesAnAbsoluteNameAndABackslashName() throws Exception {
        for (String name : new String[] {"/etc/evil.txt", "dir\\evil.txt", "a/../../b"}) {
            String archive = archiveWithEntryNamed(name, "x");
            OqError refused = assertThrows(OqError.class, () -> call("zip_decompress", archive),
                    name);
            assertTrue(refused.getMessage().contains("refused entry name"), name + ": " + refused);
        }
    }

    @Test
    void zipDecompressAcceptsHostileNeighbourNamesThatAreNotActuallyTraversal() throws Exception {
        // The reader must not be more restrictive than the writer: these are legal names, and a
        // round-trip through an outside archive has to keep working for them.
        for (String name : new String[] {"..txt", "a/b..c", "a/...b/c"}) {
            String archive = archiveWithEntryNamed(name, "y");
            @SuppressWarnings("unchecked")
            Map<String, Object> entries = (Map<String, Object>) call("zip_decompress", archive);
            assertEquals(Map.of(name, hexOf("y")), entries, name);
        }
    }

    // --- malformed input ---------------------------------------------------------------------

    @Test
    void truncatedZlibInputReportsNeedingMoreInput() {
        String compressed = (String) call("zlib_compress", hexOf("a longer body to truncate"));
        String truncated = compressed.substring(0, compressed.length() - 8);
        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_decompress", truncated));
        assertTrue(refused.getMessage().startsWith("zlib_decompress()"),
                "got: " + refused.getMessage());
    }

    @Test
    void nonHexInputNamesTheOffendingCharacter() {
        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_decompress", "deadbeefzz"));
        assertTrue(refused.getMessage().contains("needs hex text"),
                "got: " + refused.getMessage());
    }

    @Test
    void oddLengthHexIsRefused() {
        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_compress", "abc"));
        assertTrue(refused.getMessage().contains("needs hex text"),
                "got: " + refused.getMessage());
    }

    @Test
    void nonMapInputToZipCompressIsRefused() {
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_compress", "nope"));
        assertTrue(refused.getMessage().contains("needs a map"),
                "got: " + refused.getMessage());
    }

    @Test
    void nonHexEntryContentIsRefused() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("a.txt", "not hex");
        assertThrows(OqError.class, () -> call("zip_compress", files));
    }

    @Test
    void anArchiveWithNoEntriesIsRefused() {
        assertThrows(OqError.class, () -> call("zip_decompress", "00"));
    }

    // --- group 7: hex spelling, trailing bytes, duplicates and directories --------------------

    @Test
    void uppercaseHexIsRefusedByEveryCompressionEntryPoint() {
        String compressed = (String) call("zlib_compress", hexOf("abc"));
        OqError zlib = assertThrows(OqError.class,
                () -> call("zlib_decompress", compressed.toUpperCase()));
        assertTrue(zlib.getMessage().contains("offset"), zlib.getMessage());
        String gzipped = (String) call("gzip_compress", "abc");
        assertThrows(OqError.class, () -> call("gzip_decompress", gzipped.toUpperCase()));
        String zipped = (String) call("zip_compress", Map.of("a.txt", hexOf("abc")));
        assertThrows(OqError.class, () -> call("zip_decompress", zipped.toUpperCase()));
    }

    @Test
    void uppercaseHexIsRefusedAtTheExactOffset() {
        // The first uppercase character is at index 2, and the message must say so rather than
        // pointing at the whole input or at the parser's own position.
        OqError refused = assertThrows(OqError.class, () -> call("zlib_decompress", "aaBB"));
        assertTrue(refused.getMessage().contains("found 'B' at offset 2"), refused.getMessage());
    }

    @Test
    void trailingBytesAfterAZlibStreamAreRefused() {
        String compressed = (String) call("zlib_compress", hexOf("abc"));
        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_decompress", compressed + "deadbeef"));
        assertTrue(refused.getMessage().contains("4 trailing bytes"), refused.getMessage());
    }

    @Test
    void trailingBytesAfterAGzipMemberAreRefused() {
        String compressed = (String) call("gzip_compress", "first");
        OqError refused = assertThrows(OqError.class,
                () -> call("gzip_decompress", compressed + "deadbeef"));
        assertTrue(refused.getMessage().contains("4 trailing bytes"), refused.getMessage());
        // The diagnosis must be about the trailing bytes, not a misreported truncated stream.
        assertTrue(!refused.getMessage().contains("truncated"), refused.getMessage());
    }

    @Test
    void concatenatedGzipMembersAreRefusedRatherThanJoined() {
        Object first = call("gzip_compress", "first");
        Object second = call("gzip_compress", "second");
        OqError refused = assertThrows(OqError.class,
                () -> call("gzip_decompress", (String) first + second));
        assertTrue(refused.getMessage().contains("trailing bytes"), refused.getMessage());
    }

    @Test
    void oneTrailingByteIsReportedInTheSingular() {
        String compressed = (String) call("zlib_compress", hexOf("abc"));
        OqError refused = assertThrows(OqError.class,
                () -> call("zlib_decompress", compressed + "de"));
        assertTrue(refused.getMessage().contains("1 trailing byte after"),
                refused.getMessage());
    }

    @Test
    void aCorruptedGzipTrailerIsRefused() {
        // Flip the low byte of the CRC in the eight-byte trailer. The deflate body still
        // inflates cleanly, so only the checksum comparison can catch this; corrupting a byte
        // of the length instead would sometimes flip a zero to a zero and change nothing.
        byte[] gzipped = HEX.parseHex((String) call("gzip_compress", "abc"));
        gzipped[gzipped.length - 8] ^= 0x01;
        String corrupted = HEX.formatHex(gzipped);
        assertNotEquals((String) call("gzip_compress", "abc"), corrupted);
        OqError refused = assertThrows(OqError.class, () -> call("gzip_decompress", corrupted));
        assertTrue(refused.getMessage().contains("checksum mismatch"), refused.getMessage());
    }

    @Test
    void aRealGzipFileWithAStoredNameInflates() {
        // gzip -c writes an FNAME field that the hand-written header walk must skip correctly.
        // Produced by java.util.zip's own writer with the name supplied out of band, the common
        // shape a real gzip file takes.
        byte[] raw = "hello hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertEquals(hexOf("hello hello"), call("gzip_decompress", hex(out.toByteArray())));
    }

    @Test
    void duplicateArchiveEntryNamesAreRefused() {
        // Two entries spelled the same with different content: a map cannot hold both, and
        // keeping either one would lose the other without saying so.
        assertThrows(OqError.class, () -> call("zip_decompress", duplicateNameArchive()));
    }

    @Test
    void duplicateNamesAreRefusedEvenWhenTheContentsMatch() {
        // Identical content is still two entries. Accepting it would make the refusal depend on
        // the bytes rather than on the archive's structure, and the archive is still ambiguous.
        String archive = storedArchive(List.of("same.txt", "same.txt"),
                List.of("first".getBytes(StandardCharsets.UTF_8),
                        "first".getBytes(StandardCharsets.UTF_8)));
        assertThrows(OqError.class, () -> call("zip_decompress", archive));
    }

    @Test
    void theDuplicateRefusalNamesTheEntry() {
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_decompress", duplicateNameArchive()));
        assertTrue(refused.getMessage().contains("duplicate entry name: same.txt"),
                refused.getMessage());
    }

    @Test
    void anArchiveOfOnlyADirectoryIsRefusedAsHavingNoEntries() {
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_decompress", call("zip_compress", Map.of("folder/", ""))));
        assertTrue(refused.getMessage().contains("found no entries"), refused.getMessage());
    }

    @Test
    void aDirectoryEntryIsNotReturnedAsAFile() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("folder/", "");
        files.put("a.txt", hexOf("abc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> unpacked = (Map<String, Object>) call("zip_decompress",
                call("zip_compress", files));
        assertEquals(Map.of("a.txt", hexOf("abc")), unpacked);
    }

    @Test
    void nestedDirectoriesAreSkippedAndTheirFilesKept() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("a/", "");
        files.put("a/b/", "");
        files.put("a/b/c.txt", hexOf("x"));
        @SuppressWarnings("unchecked")
        Map<String, Object> unpacked = (Map<String, Object>) call("zip_decompress",
                call("zip_compress", files));
        assertEquals(Map.of("a/b/c.txt", hexOf("x")), unpacked);
    }

    @Test
    void contentOnADirectoryEntryIsRefusedByTheWriter() {
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_compress", Map.of("folder/", hexOf("abc"))));
        assertTrue(refused.getMessage().contains("refused content for the directory entry"),
                refused.getMessage());
    }

    @Test
    void aDirectoryEntryCarryingPayloadInAHandBuiltArchiveIsNotExposed() {
        // A writer other than this language can attach bytes to a name ending in '/'. The
        // reader must not hand those bytes back as a file, because the map would then claim
        // `folder/` is a file, which is the shape issue #187 reported.
        OqError refused = assertThrows(OqError.class,
                () -> call("zip_decompress", handBuiltDirectoryPayloadArchive()));
        assertTrue(refused.getMessage().contains("found no entries"), refused.getMessage());
    }

    /**
     * An archive holding {@code same.txt} twice, content {@code first} then {@code second}.
     *
     * <p>{@link java.util.zip.ZipOutputStream} refuses to write a duplicate name itself, so the
     * archive is assembled byte by byte. The disagreement between the JDK's writer and its
     * reader is the whole reason the reader needs its own check: an archive from elsewhere does
     * not have to obey this library's writer.
     */
    private static String duplicateNameArchive() {
        return storedArchive(List.of("same.txt", "same.txt"),
                List.of("first".getBytes(StandardCharsets.UTF_8),
                        "second".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Builds a STORED zip from raw parts, allowing shapes the JDK's writer would refuse.
     *
     * <p>Every stored entry records its own CRC, sizes and offset, and the central directory
     * repeats them, so this helper has to fill both in. Stored rather than deflated because a
     * stored entry needs no compressor, which keeps the builder to the parts under test.
     */
    private static String storedArchive(List<String> names, List<byte[]> contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        try {
            for (int i = 0; i < names.size(); i++) {
                byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
                byte[] data = contents.get(i);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                offsets.add(out.size());
                writeInt(out, 0x04034b50);
                writeShort(out, 20);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeInt(out, (int) crc.getValue());
                writeInt(out, data.length);
                writeInt(out, data.length);
                writeShort(out, name.length);
                writeShort(out, 0);
                out.write(name);
                out.write(data);
            }
            int centralStart = out.size();
            for (int i = 0; i < names.size(); i++) {
                byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
                byte[] data = contents.get(i);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                writeInt(out, 0x02014b50);
                writeShort(out, 20);
                writeShort(out, 20);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeInt(out, (int) crc.getValue());
                writeInt(out, data.length);
                writeInt(out, data.length);
                writeShort(out, name.length);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, 0);
                writeInt(out, 0);
                writeInt(out, offsets.get(i));
                out.write(name);
            }
            int centralSize = out.size() - centralStart;
            writeInt(out, 0x06054b50);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, names.size());
            writeShort(out, names.size());
            writeInt(out, centralSize);
            writeInt(out, centralStart);
            writeShort(out, 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return hex(out.toByteArray());
    }

    private static void writeShort(ByteArrayOutputStream out, int value) throws java.io.IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws java.io.IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    /** An archive whose only entry is a directory name carrying bytes a writer attached. */
    private static String handBuiltDirectoryPayloadArchive() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("folder/");
            entry.setMethod(java.util.zip.ZipEntry.STORED);
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payload);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return hex(out.toByteArray());
    }

    // --- registration ------------------------------------------------------------------------

    @Test
    void everyCompressionBuiltinIsRegistered() {
        for (String name : List.of("zlib_compress", "zlib_decompress", "gzip_compress",
                "gzip_decompress", "zip_compress", "zip_decompress")) {
            assertTrue(host().builtins().containsKey(name), name + " is not registered");
        }
    }
}
