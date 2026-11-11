package dev.oqthara.lsp;

import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.stdlib.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks for the stdio language server: framing, diagnostics derived from the
 * parser's span, formatting edits that parse, robustness and a clean shutdown. One test
 * runs the real subprocess; the rest drive {@link Server} in-process.
 */
class ServerTest {
    private static final String URI = "file:///tmp/hello.oq";
    private static final String BROKEN = "if true\nprint \"no end\"\n";

    @Test
    void inProcessSessionDiagnosesFormatsAndExits() throws Exception {
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        writeFrame(requests, request(1, "initialize", "{}"));
        writeFrame(requests, notification("initialized", "{}"));
        writeFrame(requests, notification("textDocument/didOpen", document(BROKEN)));
        writeFrame(requests, notification("textDocument/didChange", change("x=1+2\n")));
        writeFrame(requests, request(2, "textDocument/formatting", formattingParams()));
        writeFrame(requests, request(3, "shutdown", "{}"));
        writeFrame(requests, notification("exit", "{}"));

        ByteArrayOutputStream responses = new ByteArrayOutputStream();
        Server server = new Server(new ByteArrayInputStream(requests.toByteArray()), responses);
        assertEquals(0, server.run(), "clean shutdown exits with status 0");

        List<Map<String, Object>> messages = readAll(responses.toByteArray());
        Map<String, Object> initialize = byId(messages, 1L);
        assertEquals("oq-lsp", at(initialize, "result", "serverInfo", "name"));
        assertEquals(1L, at(initialize, "result", "capabilities", "textDocumentSync"));
        assertEquals(Boolean.TRUE, at(initialize, "result", "capabilities", "documentFormattingProvider"));
        assertEquals(Boolean.TRUE, at(initialize, "result", "capabilities", "hoverProvider"));
        // An object rather than true: the editor needs to know which characters reopen the popup.
        assertNotNull(at(initialize, "result", "capabilities", "signatureHelpProvider"),
                "signature help must be advertised to the editor");

        List<Map<String, Object>> diagnostics = diagnosticsFor(messages, URI);
        OqError expected = parseError(BROKEN);
        Map<String, Object> diagnostic = diagnostics.get(0);
        assertEquals((long) expected.span().line() - 1, at(diagnostic, "range", "start", "line"));
        assertEquals((long) expected.span().column() - 1, at(diagnostic, "range", "start", "character"));
        assertEquals(1L, at(diagnostic, "severity"));
        assertTrue(String.valueOf(at(diagnostic, "message")).contains("'end'"));

        Map<String, Object> formatting = byId(messages, 2L);
        String newText = String.valueOf(at(formatting, "result", "0", "newText"));
        assertEquals("x = 1 + 2\n", newText);
        Parser.parse(URI, newText);

        Map<String, Object> shutdown = byId(messages, 3L);
        assertTrue(shutdown.containsKey("result"));
        assertNull(shutdown.get("result"));
    }

    @Test
    void splitBuffersAndPipelinedFramesAreRead() throws Exception {
        byte[] both = concat(
                frame(request(1, "initialize", "{}")),
                frame(notification("textDocument/didOpen", document("x=1+2\n"))));

        ByteArrayOutputStream responses = new ByteArrayOutputStream();
        assertEquals(0, new Server(new ChunkedInputStream(both, 1), responses).run());

        List<Map<String, Object>> messages = readAll(responses.toByteArray());
        assertNotNull(byId(messages, 1L));
        assertTrue(diagnosticsFor(messages, URI).isEmpty(), "a valid document has no diagnostics");
    }

    @Test
    void unknownMethodsAndMalformedInputDoNotCrashTheServer() throws Exception {
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        writeFrame(requests, request(1, "workspace/unknown", "{}"));
        writeFrame(requests, notification("textDocument/didOpen", document("\u0000bad")));
        writeFrame(requests, request(2, "textDocument/formatting", formattingParams()));
        requests.write(frame("{not json"));
        writeFrame(requests, request(3, "initialize", "{}"));

        ByteArrayOutputStream responses = new ByteArrayOutputStream();
        assertEquals(0, new Server(new ByteArrayInputStream(requests.toByteArray()), responses).run());

        List<Map<String, Object>> messages = readAll(responses.toByteArray());
        assertEquals(-32601L, at(byId(messages, 1L), "error", "code"));
        assertEquals("oq", at(diagnosticsFor(messages, URI).get(0), "source"));
        assertEquals(-32700L, at(errorResponse(messages), "error", "code"));
        assertNull(byId(messages, 2L).get("result"), "no edits for a document that does not parse");
        assertNotNull(byId(messages, 3L), "the server keeps answering after bad input");
    }

    @Test
    void subprocessSpeaksFramingOverStdioAndExitsCleanly() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "dev.oqthara.lsp.Main")
                .redirectErrorStream(true)
                .start();
        try {
            byte[] session = frames(
                    request(1, "initialize", "{}"),
                    notification("textDocument/didOpen", document("x=1+2\nprint x\n")),
                    request(2, "textDocument/formatting", formattingParams()),
                    request(3, "shutdown", "{}"),
                    notification("exit", "{}"));
            OutputStream stdin = process.getOutputStream();
            for (int i = 0; i < session.length; i += 7) {
                stdin.write(session, i, Math.min(7, session.length - i));
                stdin.flush();
            }
            stdin.close();
            List<Map<String, Object>> messages = readAll(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "server exited");
            assertEquals(0, process.exitValue());
            assertNotNull(byId(messages, 1L));
            String formatted = String.valueOf(at(byId(messages, 2L), "result", "0", "newText"));
            assertEquals("x = 1 + 2\nprint x\n", formatted);
            Parser.parse(URI, formatted);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void formatterIsIdempotentAndItsOutputParses() {
        String[] samples = {
                "x=1\nif x>0\nprint\"a\"\nend\n",
                "data P(x,y)\nfun f(a,b)\nreturn a+b\nend\n",
                "m={\"a\":1,\"b\":2}\nprint m[\"a\"]\n",
                "xs=[1,2,3]\nprint xs.map(x->x*2)\n",
                "x=-1\ny=-x\n",
        };
        for (String sample : samples) {
            String once = Formatter.format(sample);
            assertEquals(once, Formatter.format(once), "not idempotent: " + sample);
            Parser.parse(URI, once);
        }
    }

    private static OqError parseError(String source) {
        try {
            Parser.parse(URI, source);
        } catch (OqError e) {
            return e;
        }
        throw new AssertionError("source should not parse: " + source);
    }

    @Test
    void hoverAnswersWithMarkdownAndAWrongPositionAnswersWithNull() throws Exception {
        String source = "\"\"\"Adds.\"\"\"\nfun add(a, b)\n  return a + b\nend\n\nprint add(1, 2)\n";
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        writeFrame(requests, request(1, "initialize", "{}"));
        writeFrame(requests, notification("textDocument/didOpen", document(source)));
        writeFrame(requests, request(2, "textDocument/hover", hoverParams(5, 6)));
        writeFrame(requests, request(3, "textDocument/hover", hoverParams(0, 1)));
        writeFrame(requests, request(4, "shutdown", "{}"));
        writeFrame(requests, notification("exit", "{}"));

        ByteArrayOutputStream responses = new ByteArrayOutputStream();
        new Server(new ByteArrayInputStream(requests.toByteArray()), responses).run();
        List<Map<String, Object>> messages = readAll(responses.toByteArray());

        Map<String, Object> answered = byId(messages, 2L);
        assertEquals("2.0", answered.get("jsonrpc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) answered.get("result");
        assertNotNull(result, "the documented function should resolve");
        @SuppressWarnings("unchecked")
        Map<String, Object> contents = (Map<String, Object>) result.get("contents");
        assertEquals("markdown", contents.get("kind"));
        assertTrue(contents.get("value").toString().contains("fun add(a, b)"),
                contents.get("value").toString());
        assertTrue(contents.get("value").toString().contains("Adds."), contents.get("value").toString());
        assertNotNull(result.get("range"), "a hover in a word reports the word's range");

        Map<String, Object> empty = byId(messages, 3L);
        assertNull(empty.get("result"), "a position that resolves to nothing returns null");
    }

    @Test
    void signatureHelpTravelsOverTheWireWithParameterRanges() throws Exception {
        String source = """
                fun add(a, b)
                  return a + b
                end

                print add(1, 
                """.stripTrailing() + "\n";

        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        writeFrame(requests, request(1, "initialize", "{}"));
        writeFrame(requests, notification("initialized", "{}"));
        writeFrame(requests, notification("textDocument/didOpen", document(source)));
        writeFrame(requests, request(2, "textDocument/signatureHelp", hoverParams(4, 12)));
        writeFrame(requests, request(3, "textDocument/signatureHelp", hoverParams(0, 9)));
        writeFrame(requests, request(4, "shutdown", "{}"));
        writeFrame(requests, notification("exit", "{}"));

        ByteArrayOutputStream responses = new ByteArrayOutputStream();
        Server server = new Server(new ByteArrayInputStream(requests.toByteArray()), responses);
        assertEquals(0, server.run());

        List<Map<String, Object>> messages = readAll(responses.toByteArray());
        Map<String, Object> result = byId(messages, 2L);
        assertEquals("add(a, b)", at(result, "result", "signatures", "0", "label"));
        assertEquals(1L, at(result, "result", "activeParameter"),
                "the cursor is past the comma, so the second parameter is active");
        assertEquals(0L, at(result, "result", "activeSignature"));
        // Each parameter is reported as a label range, which is how an editor highlights one.
        assertEquals(4L, at(result, "result", "signatures", "0", "parameters", "0", "label", "0"));
        assertEquals(5L, at(result, "result", "signatures", "0", "parameters", "0", "label", "1"));
        assertEquals(7L, at(result, "result", "signatures", "0", "parameters", "1", "label", "0"));

        Map<String, Object> declaration = byId(messages, 3L);
        assertNull(declaration.get("result"),
                "the declaration header is not a call, so there is nothing to show there");
    }

    private static String hoverParams(int line, int character) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("uri", URI);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", (long) line);
        position.put("character", (long) character);
        return Json.write(Map.of("textDocument", document, "position", position));
    }

    private static String document(String text) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("uri", URI);
        document.put("languageId", "oq");
        document.put("version", 1L);
        document.put("text", text);
        return Json.write(Map.of("textDocument", document));
    }

    private static String change(String text) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("uri", URI);
        document.put("version", 2L);
        return Json.write(Map.of("textDocument", document,
                "contentChanges", List.of(Map.of("text", text))));
    }

    private static String formattingParams() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("uri", URI);
        return Json.write(Map.of("textDocument", document, "options", Map.of()));
    }

    private static String request(long id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":" + Json.write(method)
                + ",\"params\":" + params + "}";
    }

    private static String notification(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":" + Json.write(method) + ",\"params\":" + params + "}";
    }

    private static byte[] frame(String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        return concat(header, body);
    }

    private static byte[] frames(String... messages) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (String message : messages) all.writeBytes(frame(message));
        return all.toByteArray();
    }

    private static void writeFrame(OutputStream out, String message) throws IOException {
        out.write(frame(message));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private static List<Map<String, Object>> readAll(byte[] bytes) throws IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        InputStream in = new ByteArrayInputStream(bytes);
        while (true) {
            String header = readHeader(in);
            if (header == null) return messages;
            int length = -1;
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                }
            }
            assertTrue(length >= 0, "response has Content-Length");
            byte[] body = in.readNBytes(length);
            assertEquals(length, body.length, "response body was not truncated");
            messages.add(asMap(Json.parse(new String(body, StandardCharsets.UTF_8), Span.synthetic())));
        }
    }

    private static String readHeader(InputStream in) throws IOException {
        StringBuilder header = new StringBuilder();
        int matched = 0;
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            header.append((char) b);
            matched = switch (matched) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                case 3 -> b == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) return header.substring(0, header.length() - 4);
        }
    }

    private static Map<String, Object> errorResponse(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            if (message.containsKey("error") && message.get("id") == null) return message;
        }
        throw new AssertionError("no error response in " + messages);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> diagnosticsFor(List<Map<String, Object>> messages, String uri) {
        for (Map<String, Object> message : messages) {
            if (!"textDocument/publishDiagnostics".equals(message.get("method"))) continue;
            Map<String, Object> params = asMap(message.get("params"));
            if (uri.equals(params.get("uri"))) {
                return (List<Map<String, Object>>) params.get("diagnostics");
            }
        }
        throw new AssertionError("no diagnostics for " + uri);
    }

    private static Map<String, Object> byId(List<Map<String, Object>> messages, long id) {
        for (Map<String, Object> message : messages) {
            if (Long.valueOf(id).equals(message.get("id"))) return message;
        }
        throw new AssertionError("no response with id " + id + " in " + messages);
    }

    private static Object at(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (current instanceof List<?> list) {
                current = list.get(Integer.parseInt(key));
            } else {
                throw new AssertionError("cannot descend into " + current + " with " + key);
            }
            assertNotNull(current, "missing path: " + String.join("/", path));
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Emits at most {@code chunk} bytes per read, forcing the frame reader to loop. */
    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int chunk;
        private int pos;

        ChunkedInputStream(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (pos >= data.length) return -1;
            int count = Math.min(Math.min(length, chunk), data.length - pos);
            System.arraycopy(data, pos, target, offset, count);
            pos += count;
            return count;
        }
    }
}
