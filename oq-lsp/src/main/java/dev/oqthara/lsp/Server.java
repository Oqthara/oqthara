package dev.oqthara.lsp;

import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.stdlib.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal Language Server for Oqthara over stdio. It speaks JSON-RPC 2.0 with
 * Content-Length framing, publishes parse diagnostics and answers formatting requests.
 * Everything runs on one thread: stdin reads block, and each message is handled before the
 * next frame is read. {@code exit} ends the loop; EOF is also treated as a clean end.
 */
public final class Server {
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Map<String, String> documents = new LinkedHashMap<>();
    private boolean shuttingDown;
    private boolean exited;
    private int exitStatus;

    public Server(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public int run() throws IOException {
        while (!exited) {
            byte[] payload = readFrame();
            if (payload == null) break;
            handle(payload);
        }
        return exited ? exitStatus : 0;
    }

    private void handle(byte[] payload) throws IOException {
        Object parsed;
        try {
            parsed = Json.parse(new String(payload, StandardCharsets.UTF_8), Span.synthetic());
        } catch (RuntimeException e) {
            sendError(null, -32700, "parse error: " + e.getMessage());
            return;
        }
        Map<String, Object> request = asMap(parsed);
        if (request == null) {
            sendError(null, -32600, "invalid request: expected an object");
            return;
        }
        Object id = request.get("id");
        boolean isRequest = request.containsKey("id");
        String method = asString(request.get("method"));
        if (method == null) {
            if (isRequest) sendError(id, -32600, "invalid request: missing method");
            return;
        }
        Map<String, Object> params = asMap(request.get("params"));
        if (!isRequest) {
            handleNotification(method, params);
            return;
        }
        try {
            handleRequest(id, method, params);
        } catch (RuntimeException e) {
            sendError(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private void handleNotification(String method, Map<String, Object> params) throws IOException {
        switch (method) {
            case "exit" -> {
                exited = true;
                exitStatus = shuttingDown ? 0 : 1;
            }
            case "initialized" -> {
            }
            case "textDocument/didOpen" -> {
                Map<String, Object> document = textDocument(params);
                if (document == null) return;
                String uri = asString(document.get("uri"));
                String text = asString(document.get("text"));
                if (uri == null || text == null) return;
                documents.put(uri, text);
                publishDiagnostics(uri, text);
            }
            case "textDocument/didChange" -> {
                String uri = uri(params);
                List<?> changes = changes(params);
                if (uri == null || changes == null || changes.isEmpty()) return;
                Map<String, Object> change = asMap(changes.get(changes.size() - 1));
                String text = change == null ? null : asString(change.get("text"));
                if (text == null) return;
                documents.put(uri, text);
                publishDiagnostics(uri, text);
            }
            case "textDocument/didClose" -> {
                String uri = uri(params);
                if (uri == null) return;
                documents.remove(uri);
                publishDiagnostics(uri, "");
            }
            default -> {
                // Unknown notifications are ignored, as the specification requires.
            }
        }
    }

    private void handleRequest(Object id, String method, Map<String, Object> params) throws IOException {
        switch (method) {
            case "initialize" -> sendResult(id, initializeResult());
            case "shutdown" -> {
                shuttingDown = true;
                sendResult(id, null);
            }
            case "textDocument/formatting" -> sendResult(id, formatting(params));
            case "textDocument/hover" -> sendResult(id, hover(params));
            case "textDocument/signatureHelp" -> sendResult(id, signatureHelp(params));
            default -> sendError(id, -32601, "method not found: " + method);
        }
    }

    private static Map<String, Object> initializeResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("textDocumentSync", 1L);
        capabilities.put("documentFormattingProvider", true);
        capabilities.put("hoverProvider", true);
        capabilities.put("signatureHelpProvider", Map.of(
                "triggerCharacters", List.of("(", ","),
                "retriggerCharacters", List.of(",")));
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "oq-lsp");
        serverInfo.put("version", "0.1.0-alpha.3");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    /** Returns full-document TextEdits, or null when the document is missing or does not parse. */
    private Object formatting(Map<String, Object> params) {
        String uri = uri(params);
        if (uri == null) return null;
        String source = documents.get(uri);
        if (source == null) return null;
        try {
            Parser.parse(uri, source);
            String formatted = Formatter.format(source);
            Parser.parse(uri, formatted);
            if (formatted.equals(source)) return List.of();
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("range", fullRange(source));
            edit.put("newText", formatted);
            return List.of(edit);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns {@code {contents, range}} for the identifier under the cursor, or null when
     * nothing is declared there. A null result is the protocol's way of saying "show
     * nothing"; an empty string would open an empty popup.
     */
    private Object hover(Map<String, Object> params) {
        String uri = uri(params);
        if (uri == null) return null;
        String source = documents.get(uri);
        if (source == null) return null;
        Map<String, Object> position = asMap(params.get("position"));
        if (position == null) return null;
        Long line = asLong(position.get("line"));
        Long character = asLong(position.get("character"));
        if (line == null || character == null) return null;
        String text = Hover.hover(uri, source, (int) (long) line, (int) (long) character);
        if (text == null) return null;
        Map<String, Object> hover = new LinkedHashMap<>();
        hover.put("contents", Map.of("kind", "markdown", "value", text));
        Map<String, Object> wordRange = Hover.rangeAt(source, (int) (long) line,
                (int) (long) character);
        if (wordRange != null) hover.put("range", wordRange);
        return hover;
    }

    /**
     * Returns signature help, or null when the position is not inside a described call. The
     * protocol accepts null as "nothing to show"; an empty signature list would draw an empty
     * popup in every editor.
     */
    private Object signatureHelp(Map<String, Object> params) {
        String uri = uri(params);
        if (uri == null) return null;
        String source = documents.get(uri);
        if (source == null) return null;
        Map<String, Object> position = asMap(params.get("position"));
        if (position == null) return null;
        Long line = asLong(position.get("line"));
        Long character = asLong(position.get("character"));
        if (line == null || character == null) return null;
        SignatureHelp.Reply reply = SignatureHelp.signatureHelp(uri, source,
                (int) (long) line, (int) (long) character);
        if (reply == null) return null;

        List<Map<String, Object>> signatures = new ArrayList<>();
        for (SignatureHelp.Signature signature : reply.signatures()) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("label", signature.label());
            List<Map<String, Object>> parameters = new ArrayList<>();
            int offset = signature.label().indexOf('(') + 1;
            for (String parameter : signature.parameters()) {
                Map<String, Object> label = new LinkedHashMap<>();
                label.put("label", List.of((long) offset, (long) (offset + parameter.length())));
                parameters.add(label);
                offset += parameter.length() + 2;
            }
            if (!parameters.isEmpty()) rendered.put("parameters", parameters);
            signatures.add(rendered);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signatures", signatures);
        result.put("activeSignature", 0L);
        result.put("activeParameter", (long) reply.activeParameter());
        return result;
    }

    private void publishDiagnostics(String uri, String text) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);
        params.put("diagnostics", diagnostics(uri, text));
        sendNotification("textDocument/publishDiagnostics", params);
    }

    private static List<Map<String, Object>> diagnostics(String uri, String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Parser.parse(uri, text);
        } catch (OqError e) {
            result.add(diagnostic(e, text));
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result.add(diagnostic(message, range(0, 0, 0, 0)));
        }
        return result;
    }

    private static Map<String, Object> diagnostic(OqError error, String text) {
        String message = error.getMessage();
        if (error.hint() != null && !error.hint().isBlank()) message += "\n" + error.hint();
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("range", rangeFor(error.span(), text));
        diagnostic.put("severity", 1L);
        diagnostic.put("source", "oq");
        diagnostic.put("message", message);
        return diagnostic;
    }

    private static Map<String, Object> diagnostic(String message, Map<String, Object> range) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("range", range);
        diagnostic.put("severity", 1L);
        diagnostic.put("source", "oq");
        diagnostic.put("message", message);
        return diagnostic;
    }

    private static Map<String, Object> rangeFor(Span span, String text) {
        String[] lines = text.split("\n", -1);
        int startLine = clamp(span.line() - 1, 0, lines.length - 1);
        int startChar = clamp(span.column() - 1, 0, lineLength(lines[startLine]));
        int endLine = clamp(span.endLine() - 1, startLine, lines.length - 1);
        int endChar = clamp(span.endColumn() - 1, 0, lineLength(lines[endLine]));
        if (endLine == startLine && endChar <= startChar) {
            endChar = Math.min(startChar + 1, lineLength(lines[startLine]));
        }
        return range(startLine, startChar, endLine, endChar);
    }

    private static Map<String, Object> fullRange(String text) {
        String[] lines = text.split("\n", -1);
        int endLine = Math.max(0, lines.length - 1);
        return range(0, 0, endLine, lineLength(lines[endLine]));
    }

    private static Map<String, Object> range(int startLine, int startChar, int endLine, int endChar) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", position(startLine, startChar));
        range.put("end", position(endLine, endChar));
        return range;
    }

    private static Map<String, Object> position(int line, int character) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", (long) line);
        position.put("character", (long) character);
        return position;
    }

    private static int lineLength(String line) {
        return line.endsWith("\r") ? line.length() - 1 : line.length();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private void sendResult(Object id, Object result) throws IOException {
        Map<String, Object> response = envelope(id);
        response.put("result", result);
        send(response);
    }

    private void sendError(Object id, int code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", (long) code);
        error.put("message", message);
        Map<String, Object> response = envelope(id);
        response.put("error", error);
        send(response);
    }

    private void sendNotification(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        send(notification);
    }

    private static Map<String, Object> envelope(Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        return response;
    }

    private void send(Map<String, Object> message) throws IOException {
        byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Reads one Content-Length framed message; null on clean EOF or a truncated frame. */
    byte[] readFrame() throws IOException {
        StringBuilder header = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            header.append((char) b);
            if (header.length() > MAX_HEADER_BYTES) {
                throw new IOException("JSON-RPC header is too large");
            }
            if (header.length() >= 4
                    && header.charAt(header.length() - 4) == '\r'
                    && header.charAt(header.length() - 3) == '\n'
                    && header.charAt(header.length() - 2) == '\r'
                    && header.charAt(header.length() - 1) == '\n') {
                break;
            }
        }
        int contentLength = -1;
        for (String line : header.toString().split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("invalid Content-Length header");
                }
            }
        }
        if (contentLength < 0) throw new IOException("missing Content-Length header");
        byte[] body = in.readNBytes(contentLength);
        return body.length == contentLength ? body : null;
    }

    private static String uri(Map<String, Object> params) {
        Map<String, Object> document = textDocument(params);
        return document == null ? null : asString(document.get("uri"));
    }

    private static Map<String, Object> textDocument(Map<String, Object> params) {
        return params == null ? null : asMap(params.get("textDocument"));
    }

    private static List<?> changes(Map<String, Object> params) {
        Object changes = params == null ? null : params.get("contentChanges");
        return changes instanceof List<?> list ? list : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
