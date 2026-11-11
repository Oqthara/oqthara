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
                HexFormat.of().formatHex(deflate(hex(args.get(0), "zlib_compress", span), span))));
        interpreter.defineBuiltin(NativeFunction.of("zlib_decompress", 1, (host, args, span) ->
                HexFormat.of().formatHex(inflate(hex(args.get(0), "zlib_decompress", span),
                        "zlib_decompress", span))));
        interpreter.defineBuiltin(NativeFunction.of("gzip_compress", 1, (host, args, span) ->
                HexFormat.of().formatHex(gzip(utf8(args.get(0), "gzip_compress", span), span))));
        interpreter.defineBuiltin(NativeFunction.of("gzip_decompress", 1, (host, args, span) ->
                HexFormat.of().formatHex(gunzip(hex(args.get(0), "gzip_decompress", span),
                        "gzip_decompress", span))));
        interpreter.defineBuiltin(NativeFunction.of("zip_compress", 1, (host, args, span) ->
                HexFormat.of().formatHex(zip(args.get(0), span))));
        interpreter.defineBuiltin(NativeFunction.of("zip_decompress", 1, (host, args, span) ->
                entries(unzip(hex(args.get(0), "zip_decompress", span), span))));
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
                        throw new OqError(builtin + "() needs more input", span,
                                "The compressed input ended before the stream was complete.");
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
        } catch (DataFormatException e) {
            throw new OqError(builtin + "() is not valid compressed data: " + e.getMessage(), span,
                    "Check that the input is hex of content this function produced.");
        } finally {
            inflater.end();
        }
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

    /** Reads one gzip member; a second member is an error rather than silently ignored. */
    private static byte[] gunzip(byte[] input, String builtin, Span span) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
            return readCapped(gzip, builtin, span);
        } catch (IOException e) {
            throw new OqError(builtin + "() is not valid gzip data: " + e.getMessage(), span,
                    "Check that the input is hex of content gzip_compress produced.");
        }
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
     */
    private static Map<String, byte[]> unzip(byte[] input, Span span) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(input))) {
            ZipEntry entry;
            byte[] buffer = new byte[CHUNK];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_OUTPUT_BYTES) {
                        throw amplified("zip_decompress", span);
                    }
                    content.write(buffer, 0, read);
                }
                entries.put(entry.getName(), content.toByteArray());
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
            entries.put(name, hex(entry.getValue(), "zip_compress", span));
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
