package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Whole-value compression: raw DEFLATE, gzip and zip, all byte-in and byte-out over hex.
 *
 * <p>There is no bytes type, so binary content is spelled as lowercase hex, exactly as
 * {@code read_bytes} and {@code hex_encode} already spell it. Every function here consumes a
 * complete value and returns a complete value: no stream, no handle, no lifetime.
 *
 * <p>The security-relevant decision is that decompression counts the bytes it actually produces
 * and stops at {@link #MAX_OUTPUT_BYTES}. Decompression is the textbook amplification attack, and
 * the defence cannot be a length field in the input, because a hostile producer writes whatever
 * length it likes. Counting real output inside the inflate loop is the only form of the cap that
 * cannot be lied to.
 */
final class Compression {

    /** The same budget {@code Stdlib.MAX_TEXT_BYTES} applies to text read from disk. */
    static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

    private static final int CHUNK = 8192;

    private Compression() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("zlib_compress", 1, (host, args, span) ->
                HexFormat.of().formatHex(deflate(hex(args.get(0), "zlib_compress", span), span))).named("data"));
        interpreter.defineBuiltin(NativeFunction.of("zlib_decompress", 1, (host, args, span) ->
                HexFormat.of().formatHex(inflate(hex(args.get(0), "zlib_decompress", span),
                        "zlib_decompress", span))).named("hex"));
        interpreter.defineBuiltin(NativeFunction.of("gzip_compress", 1, (host, args, span) ->
                HexFormat.of().formatHex(gzip(utf8(args.get(0), "gzip_compress", span), span))).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("gzip_decompress", 1, (host, args, span) ->
                HexFormat.of().formatHex(gunzip(hex(args.get(0), "gzip_decompress", span),
                        "gzip_decompress", span))).named("hex"));
        interpreter.defineBuiltin(NativeFunction.of("zip_compress", 1, (host, args, span) ->
                HexFormat.of().formatHex(zip(args.get(0), span))).named("entries"));
        interpreter.defineBuiltin(NativeFunction.of("zip_decompress", 1, (host, args, span) ->
                entries(unzip(hex(args.get(0), "zip_decompress", span), span))).named("hex"));
    }

    /**
     * Inflates incrementally, testing the running total after every chunk.
     *
     * <p>The obvious implementation -- {@code readAllBytes()} on an {@code InflaterInputStream} --
     * allocates the entire expansion before any cap can be consulted, so it does not implement the
     * cap at all. A hostile input would be stopped only after the memory was already committed.
     */
    private static byte[] inflate(byte[] input, String builtin, Span span) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            byte[] out = drain(inflater, builtin, span);
            // The inflater stops at the end of the stream, not at the end of the array. Whatever
            // it did not read is content the caller supplied and nothing consumed, so returning
            // the decompressed value would silently discard it.
            int remaining = inflater.getRemaining();
            if (remaining > 0) {
                throw trailing(builtin, remaining, span);
            }
            return out;
        } catch (DataFormatException e) {
            throw new OqError(builtin + "() is not valid compressed data: " + e.getMessage(), span,
                    "Check that the input is hex of content this function produced.");
        } finally {
            inflater.end();
        }
    }

    /** What a raw inflation produced, and how much of the supplied range it actually read. */
    private record Raw(byte[] bytes, int consumed) {
    }

    private static Raw inflateRaw(byte[] input, int offset, int length, String builtin, Span span)
            throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input, offset, length);
            byte[] out = drain(inflater, builtin, span);
            return new Raw(out, length - inflater.getRemaining());
        } finally {
            inflater.end();
        }
    }

    /**
     * Inflates until the stream ends, testing the running total before every write.
     *
     * <p>Shared by the zlib and gzip paths so the amplification cap has exactly one
     * implementation, and so neither can forget it.
     */
    private static byte[] drain(Inflater inflater, String builtin, Span span)
            throws DataFormatException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK];
        while (!inflater.finished()) {
            int produced = inflater.inflate(buffer);
            if (produced == 0) {
                // An empty stream legitimately produces nothing and reports itself
                // finished, so this must be tested before the truncation branch.
                if (inflater.finished()) {
                    break;
                }
                if (inflater.needsInput()) {
                    // The stream ended without a final block: the input is truncated, not
                    // merely small, so this is a caller error rather than a cap failure.
                    throw truncated(builtin, span);
                }
                if (inflater.needsDictionary()) {
                    throw new OqError(builtin + "() needs a preset dictionary", span,
                            "This stream requires a dictionary that the language cannot supply.");
                }
                throw new OqError(builtin + "() could not make progress", span,
                        "The compressed input is malformed.");
            }
            if (out.size() + produced > MAX_OUTPUT_BYTES) {
                throw amplified(builtin, span);
            }
            out.write(buffer, 0, produced);
        }
        return out.toByteArray();
    }

    private static OqError amplified(String builtin, Span span) {
        return new OqError(builtin + "() exceeded " + MAX_OUTPUT_BYTES + " bytes of output", span,
                "Compressed input that expands past " + MAX_OUTPUT_BYTES
                        + " bytes is refused rather than allocated.");
    }

    private static byte[] deflate(byte[] input, Span span) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[CHUNK];
            while (!deflater.finished()) {
                int produced = deflater.deflate(buffer);
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] gzip(byte[] input, Span span) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(input);
            gzip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new OqError("gzip_compress() failed: " + e.getMessage(), span,
                    "This indicates an internal error, not a problem with the input.");
        }
    }

    /**
     * Reads exactly one gzip member and refuses anything after it.
     *
     * <p>{@code GZIPInputStream} cannot be used to make that decision. It reads through its own
     * buffer, so bytes belonging to a second member -- or to nothing at all -- are already
     * consumed and lost before it reports the first member finished. The header and trailer are
     * therefore parsed here, and the deflate body is inflated with the raw inflater, which
     * reports precisely how much of the input it used. That remainder is then required to be the
     * eight-byte trailer and nothing more.
     *
     * <p>Concatenated members are refused rather than joined. A caller who passes two members
     * made a mistake, and silently returning the first would hide it; returning both would
     * invent a concatenation the language does not otherwise have.
     */
    private static byte[] gunzip(byte[] input, String builtin, Span span) {
        if (input.length < 18) {
            throw new OqError(builtin + "() needs more input", span,
                    "A gzip member is at least a header, an empty stream and an eight-byte trailer.");
        }
        if ((input[0] & 0xFF) != 0x1f || (input[1] & 0xFF) != 0x8b) {
            throw new OqError(builtin + "() is not valid gzip data: bad magic", span,
                    "Check that the input is hex of content gzip_compress produced.");
        }
        if ((input[2] & 0xFF) != 8) {
            throw new OqError(builtin + "() is not valid gzip data: unsupported method " + (input[2] & 0xFF),
                    span, "Only DEFLATE, method 8, is defined.");
        }
        int flags = input[3] & 0xFF;
        if ((flags & 0xE0) != 0) {
            throw new OqError(builtin + "() is not valid gzip data: reserved flags are set", span,
                    "The reserved bits of the header must be zero.");
        }
        int at = 10;
        if ((flags & 0x04) != 0) {
            if (at + 2 > input.length) {
                throw truncated(builtin, span);
            }
            int extra = (input[at] & 0xFF) | ((input[at + 1] & 0xFF) << 8);
            at += 2 + extra;
            if (at > input.length) {
                throw truncated(builtin, span);
            }
        }
        if ((flags & 0x08) != 0) {
            at = skipZeroTerminated(input, at, builtin, span);
        }
        if ((flags & 0x10) != 0) {
            at = skipZeroTerminated(input, at, builtin, span);
        }
        if ((flags & 0x02) != 0) {
            at += 2;
            if (at > input.length) {
                throw truncated(builtin, span);
            }
        }
        byte[] out;
        int consumed;
        try {
            Raw raw = inflateRaw(input, at, input.length - at, builtin, span);
            out = raw.bytes();
            consumed = raw.consumed();
        } catch (DataFormatException e) {
            throw new OqError(builtin + "() is not valid gzip data: " + e.getMessage(), span,
                    "Check that the input is hex of content gzip_compress produced.");
        }
        // The trailer sits where the deflate stream stopped, not at the end of the array. That
        // distinction is the whole point: anchoring at the end would treat appended bytes as
        // part of the member and misreport them as a truncated stream.
        int trailerAt = at + consumed;
        if (trailerAt + 8 > input.length) {
            throw truncated(builtin, span);
        }
        if (trailerAt + 8 < input.length) {
            throw trailing(builtin, input.length - trailerAt - 8, span);
        }
        long expectedCrc = le32(input, trailerAt);
        long expectedSize = le32(input, trailerAt + 4);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(out, 0, out.length);
        if (crc.getValue() != expectedCrc) {
            throw new OqError(builtin + "() is not valid gzip data: checksum mismatch", span,
                    "The content does not match the checksum in the trailer.");
        }
        if ((out.length & 0xFFFFFFFFL) != expectedSize) {
            throw new OqError(builtin + "() is not valid gzip data: length mismatch", span,
                    "The content does not match the length in the trailer.");
        }
        return out;
    }

    private static long le32(byte[] data, int at) {
        return (data[at] & 0xFFL) | ((data[at + 1] & 0xFFL) << 8)
                | ((data[at + 2] & 0xFFL) << 16) | ((data[at + 3] & 0xFFL) << 24);
    }

    private static int skipZeroTerminated(byte[] input, int at, String builtin, Span span) {
        while (at < input.length && input[at] != 0) {
            at++;
        }
        if (at >= input.length) {
            throw truncated(builtin, span);
        }
        return at + 1;
    }

    private static OqError truncated(String builtin, Span span) {
        return new OqError(builtin + "() needs more input", span,
                "The compressed input ended before the stream was complete.");
    }

    private static OqError trailing(String builtin, int count, Span span) {
        return new OqError(builtin + "() found " + count + " trailing byte"
                + (count == 1 ? "" : "s") + " after the compressed stream", span,
                "The input must be exactly one complete stream. Concatenated streams are not "
                        + "joined, and bytes after the stream are refused rather than ignored.");
    }

    /**
     * Reads a stream to its end, stopping the moment the running total passes the cap.
     *
     * <p>This is the cap for every container whose reader decides its own chunking, so the
     * defence does not depend on a header field any producer can write.
     */
    private static byte[] readCapped(java.io.InputStream stream, String builtin, Span span)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) > 0) {
            total += read;
            if (total > MAX_OUTPUT_BYTES) {
                throw amplified(builtin, span);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static byte[] zip(Object value, Span span) {
        Map<String, byte[]> entries = namedEntries(value, span);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new OqError("zip_compress() failed: " + e.getMessage(), span,
                    "This indicates an internal error, not a problem with the input.");
        }
    }

    /**
     * Reads every entry, summing the unpacked total against the cap.
     *
     * <p>The cap is on the archive's total rather than per entry: a thousand entries each just
     * under the limit is the same amplification attack as one entry over it.
     *
     * <p>Entry names are validated here as well as in {@code zip_compress}, and for a stronger
     * reason. The writer validates names it was given by the program; the reader validates names
     * it was given by whoever produced the archive, which is the untrusted direction. An archive
     * from anywhere can name an entry {@code ../../etc/evil.txt}, and returning that name to the
     * caller means any program that trusts the map — writing each entry to its key, the obvious
     * use — writes outside the directory it intended. The name is refused rather than rewritten,
     * on the rule that a name which had to be altered is not the name that was asked for.
     */
    private static Map<String, byte[]> unzip(byte[] input, Span span) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(input))) {
            ZipEntry entry;
            byte[] buffer = new byte[CHUNK];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!isSafeEntryName(name)) {
                    throw new OqError("zip_decompress() refused entry name: " + name, span,
                            "An entry name may not be absolute, contain a '..' segment, "
                                    + "a backslash or a NUL. Nothing was returned.");
                }
                // A directory entry is structure, not content. The language has no directory
                // type, so returning one as a file would claim `folder/` holds bytes it does
                // not. Directories are therefore skipped, and any payload attached to one is
                // skipped with it rather than exposed as a file the writer did not describe.
                // An archive of nothing but directories is then an archive with no entries,
                // which the guard below already refuses.
                if (entry.isDirectory() || name.endsWith("/")) {
                    continue;
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_OUTPUT_BYTES) {
                        throw amplified("zip_decompress", span);
                    }
                    content.write(buffer, 0, read);
                }
                // The result is a map, so it cannot hold both. Keeping the last one would lose
                // the first silently, and an archive that names one entry twice is ambiguous
                // rather than merely redundant, so it is refused instead of resolved.
                if (entries.containsKey(name)) {
                    throw new OqError("zip_decompress() found a duplicate entry name: " + name,
                            span, "A map has one value per name. Nothing was returned.");
                }
                entries.put(name, content.toByteArray());
            }
        } catch (IOException e) {
            throw new OqError("zip_decompress() is not valid zip data: " + e.getMessage(), span,
                    "Check that the input is hex of an archive this function produced.");
        }
        if (entries.isEmpty()) {
            throw new OqError("zip_decompress() found no entries", span,
                    "The input is not an archive, or it contains only directories.");
        }
        return entries;
    }

    /**
     * Validates each entry name before it is written.
     *
     * <p>A name that had to be rewritten to be safe is a name the caller did not mean, so it is
     * refused rather than normalised. Storing a quietly different name than the one asked for is
     * the wrong-answer failure mode this project treats as a defect.
     */
    private static Map<String, byte[]> namedEntries(Object value, Span span) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new OqError("zip_compress() needs a map of names to hex content, but got "
                    + Values.typeName(value), span,
                    "Write zip_compress({\"a.txt\": \"68656c6c6f\"}).");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = textOf(entry.getKey());
            if (name == null) {
                throw new OqError("zip_compress() needs text entry names, but got "
                        + Values.typeName(entry.getKey()), span, "Entry names are text.");
            }
            if (!isSafeEntryName(name)) {
                throw new OqError("zip_compress() refused entry name: " + name, span,
                        "A name may not be absolute, contain a '..' segment, a backslash or a NUL.");
            }
            byte[] content = hex(entry.getValue(), "zip_compress", span);
            // A trailing slash marks a directory, and a directory holds no bytes. Accepting
            // content here would write a file the decompressor then has to hide, so the
            // contradiction is refused instead of resolved in one direction or the other.
            if (name.endsWith("/") && content.length > 0) {
                throw new OqError("zip_compress() refused content for the directory entry: "
                        + name, span,
                        "A name ending in '/' is a directory and holds no content. "
                                + "Name the file inside it, such as '" + name + "a.txt'.");
            }
            entries.put(name, content);
        }
        return entries;
    }

    private static boolean isSafeEntryName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static Object entries(Map<String, byte[]> entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            result.put(entry.getKey(), HexFormat.of().formatHex(entry.getValue()));
        }
        return result;
    }

    private static byte[] hex(Object value, String builtin, Span span) {
        String text = textOf(value);
        if (text == null) {
            throw new OqError(builtin + "() needs hex text, but got " + Values.typeName(value),
                    span, "Binary content is spelled as lowercase hex.");
        }
        // The JDK parser accepts 'A'-'F' as readily as 'a'-'f', but the language reference
        // and ADR-0033 define hex as lowercase, and hex_decode enforces it. Accepting the
        // wider spelling here would make the compression calls the one place where case does
        // not matter, so the alphabet is checked before the parser sees it.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!digit) {
                throw new OqError(builtin + "() needs hex text: found '" + c + "' at offset "
                        + i, span,
                        "Binary content is spelled as lowercase hex, two characters per byte: "
                                + "0-9 or a-f.");
            }
        }
        try {
            return HexFormat.of().parseHex(text);
        } catch (IllegalArgumentException e) {
            throw new OqError(builtin + "() needs hex text: " + e.getMessage(), span,
                    "Binary content is spelled as lowercase hex, two characters per byte.");
        }
    }

    private static byte[] utf8(Object value, String builtin, Span span) {
        String text = textOf(value);
        if (text == null) {
            throw new OqError(builtin + "() needs text, but got " + Values.typeName(value), span,
                    "Encode binary content with hex_encode first.");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String textOf(Object value) {
        return value instanceof String s ? s : null;
    }
}
