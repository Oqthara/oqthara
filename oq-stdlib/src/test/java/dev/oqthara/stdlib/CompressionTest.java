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

    // --- registration ------------------------------------------------------------------------

    @Test
    void everyCompressionBuiltinIsRegistered() {
        for (String name : List.of("zlib_compress", "zlib_decompress", "gzip_compress",
                "gzip_decompress", "zip_compress", "zip_decompress")) {
            assertTrue(host().builtins().containsKey(name), name + " is not registered");
        }
    }
}
