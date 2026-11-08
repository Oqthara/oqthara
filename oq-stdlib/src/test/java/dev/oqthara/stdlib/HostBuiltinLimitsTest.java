package dev.oqthara.stdlib;

import com.sun.net.httpserver.HttpServer;
import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the resource controls on the host-facing builtins.
 *
 * <p>These builtins reach the filesystem, the network and the operating system. Oqthara is
 * explicitly <em>not</em> a sandbox: a program can still read any file it has permission to
 * read, open a socket to any host, and run any command. What these tests pin down is narrower
 * and honest: each of those reachable operations is bounded, so a hostile program cannot use
 * one call to exhaust memory or block the interpreter forever.
 */
class HostBuiltinLimitsTest {

    @TempDir
    Path dir;

    private Interpreter interpreter;
    private ByteArrayOutputStream outBytes;

    @BeforeEach
    void setUp() {
        outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        interpreter = new Interpreter(out, out,
                new java.io.BufferedReader(new java.io.StringReader("")),
                List.of(), dir, new MethodResolverImpl());
        Stdlib.install(interpreter);
    }

    private Object call(String name, Object... args) {
        OqCallable callable = interpreter.builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(interpreter, values, Span.synthetic());
    }

    // --- output ---------------------------------------------------------------------------

    @Test
    void printStillWritesWhatItAlwaysWrote() {
        // The charge must not change behaviour for ordinary output.
        call("print", "hello", 1L);
        assertEquals("hello 1" + System.lineSeparator(),
                outBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void outputIsChargedAgainstTheStepBudget() {
        // print is a single interpreter step, so before this the only cost of a huge write was
        // the bytes themselves. The charge makes output consume the same budget as any other
        // work, which is what lets one limit cover both.
        int before = interpreter.maxSteps();
        interpreter.setMaxSteps(1_000);
        OqError error = assertThrows(OqError.class,
                () -> call("print", "x".repeat(100_000)));
        assertTrue(error.getMessage().contains("ran for too long"), error.getMessage());
        interpreter.setMaxSteps(before);
    }

    @Test
    void writeDoesNotAppendASeparatorOrANewline() {
        // Pins the contract `write` was undocumented for: no separator between arguments and
        // no trailing newline, unlike `print`.
        call("write", "a", 1L, "b");
        assertEquals("a1b", outBytes.toString(StandardCharsets.UTF_8));
    }

    // --- file reads -----------------------------------------------------------------------

    @Test
    void readReturnsTheWholeFile() {
        Path file = dir.resolve("small.txt");
        assertDoesNotThrow(() -> Files.writeString(file, "line one\nline two\n"));
        assertEquals("line one\nline two\n", call("read", file.toString()));
    }

    @Test
    void readRefusesAFileLargerThanTheLimit() throws IOException {
        Path file = dir.resolve("huge.txt");
        // Sparse-ish: one byte past the cap is enough, and writing it in one call keeps the
        // test fast without depending on the filesystem being sparse.
        byte[] filler = new byte[Stdlib.MAX_TEXT_BYTES + 1];
        java.util.Arrays.fill(filler, (byte) 'a');
        Files.write(file, filler);

        OqError error = assertThrows(OqError.class, () -> call("read", file.toString()));
        assertTrue(error.getMessage().contains("too large"), error.getMessage());
    }

    @Test
    void readLinesRefusesTheSameOversizedFile() throws IOException {
        // read_lines delegates to the same bounded read, so splitting into lines is not a way
        // around the limit.
        Path file = dir.resolve("huge.txt");
        byte[] filler = new byte[Stdlib.MAX_TEXT_BYTES + 1];
        java.util.Arrays.fill(filler, (byte) 'a');
        Files.write(file, filler);

        OqError error = assertThrows(OqError.class, () -> call("read_lines", file.toString()));
        assertTrue(error.getMessage().contains("too large"), error.getMessage());
    }

    @Test
    void readLinesSplitsOnEveryLineEndingWithoutATrailingEmptyLine() throws IOException {
        // The bounded rewrite replaced Files.readAllLines, so the splitting contract has to be
        // pinned: \n, \r\n and \r all separate, and a trailing newline adds no empty line.
        Path file = dir.resolve("mixed.txt");
        Files.writeString(file, "one\ntwo\r\nthree\rfour\n");
        assertEquals(List.of("one", "two", "three", "four"), call("read_lines", file.toString()));
    }

    @Test
    void readOfAMissingFileIsAProgramError() {
        OqError error = assertThrows(OqError.class,
                () -> call("read", dir.resolve("absent.txt").toString()));
        assertTrue(error.getMessage().contains("cannot read file"), error.getMessage());
    }

    // --- process --------------------------------------------------------------------------

    @Test
    void runCommandCapturesOutputAndExitCode() throws IOException {
        OqData result = (OqData) call("run_command", script("echo oqthara"));
        assertEquals(0L, result.get("code"));
        assertEquals("oqthara" + System.lineSeparator(), result.get("output"));
    }

    @Test
    void runCommandReportsANonZeroExitCodeInsteadOfThrowing() throws IOException {
        // A failing command is an ordinary result, not a program error: the caller gets the
        // code and decides.
        OqData result = (OqData) call("run_command",
                script("echo boom; exit 3"));
        assertEquals(3L, result.get("code"));
        assertTrue(String.valueOf(result.get("output")).contains("boom"));
    }

    @Test
    void runCommandDoesNotGoThroughAShell() throws IOException {
        // The contract is one executable path with no arguments. A caller who assumes a shell
        // gets a clear program error rather than silently different behaviour.
        assertThrows(OqError.class, () -> call("run_command", "echo oqthara"));
        OqError error = assertThrows(OqError.class,
                () -> call("run_command", "/bin/echo one; /bin/echo two"));
        assertTrue(error.getMessage().contains("cannot run command"), error.getMessage());
    }

    @Test
    void runCommandTimeoutKillsTheWholeProcessTree() throws Exception {
        // A script that starts a long-lived child and then waits. Destroying only the script
        // would leave the child running after the program errored.
        Path marker = dir.resolve("child.pid");
        Path script = script("sleep 60 & echo $! > " + marker + "; wait");
        OqError error = assertThrows(OqError.class, () -> call("run_command", script.toString()));
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        long pid = Long.parseLong(Files.readString(marker).trim());
        // Give the kill a moment to land, then assert the grandchild is gone.
        for (int i = 0; i < 50 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                i++) {
            Thread.sleep(100);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "the child process " + pid + " outlived the timeout");
    }

    @Test
    void runCommandTimeoutsACommandThatNeverClosesItsOutput() throws Exception {
        // Holding the output stream open forever must be caught by the same timeout, not hang
        // the program waiting for end of stream.
        Path script = script("sleep 60");
        OqError error = assertThrows(OqError.class, () -> call("run_command", script.toString()));
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
    }

    /** Writes an executable script and returns its path, since run_command takes no arguments. */
    private Path script(String body) throws IOException {
        Path file = dir.resolve("script.sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true), "could not mark the script executable");
        return file;
    }

    @Test
    void runCommandOfAMissingExecutableIsAProgramError() {
        OqError error = assertThrows(OqError.class,
                () -> call("run_command", "oqthara-no-such-command-9d3f"));
        assertTrue(error.getMessage().contains("cannot run command"), error.getMessage());
    }

    @Test
    void runCommandOutputIsBounded() throws IOException {
        // Without the cap the child's whole output was read into memory.
        OqError error = assertThrows(OqError.class,
                () -> call("run_command", script("head -c 20000000 /dev/zero")));
        assertTrue(error.getMessage().contains("too large"), error.getMessage());
    }

    // --- HTTP -----------------------------------------------------------------------------

    @Test
    void httpGetReturnsStatusBodyAndOk() throws IOException {
        HttpServer server = serve(200, "{\"ok\":true}");
        try {
            OqData result = (OqData) call("http_get", url(server, "/"));
            assertEquals(200L, result.get("status"));
            assertEquals("{\"ok\":true}", result.get("body"));
            assertEquals(true, result.get("ok"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpGetMarksAnErrorStatusAsNotOk() throws IOException {
        HttpServer server = serve(404, "missing");
        try {
            OqData result = (OqData) call("http_get", url(server, "/"));
            assertEquals(404L, result.get("status"));
            assertEquals(false, result.get("ok"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpPostSendsTheBody() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> received = new ArrayList<>();
        server.createContext("/", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "got it".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            OqData result = (OqData) call("http_post", url(server, "/"), "{\"n\":1}");
            assertEquals(201L, result.get("status"));
            assertEquals("got it", result.get("body"));
            assertEquals(List.of("{\"n\":1}"), received);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpWithAMalformedUrlIsAProgramError() {
        // URI.create throws IllegalArgumentException, which used to be a possible internal
        // error; it has to surface as an ordinary program error.
        OqError error = assertThrows(OqError.class, () -> call("http_get", "not a url"));
        assertTrue(error.getMessage().contains("invalid URL"), error.getMessage());
    }

    @Test
    void httpRefusesAnOversizedResponse() throws IOException {
        HttpServer server = serve(200, "y".repeat(Stdlib.MAX_TEXT_BYTES + 100));
        try {
            OqError error = assertThrows(OqError.class, () -> call("http_get", url(server, "/")));
            assertTrue(error.getMessage().contains("too large"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpToAnUnreachableHostIsAProgramError() {
        // Port 1 on loopback refuses connections, so this is a fast, offline failure.
        OqError error = assertThrows(OqError.class,
                () -> call("http_get", "http://127.0.0.1:1/"));
        assertTrue(error.getMessage().contains("HTTP request failed"), error.getMessage());
    }

    private static HttpServer serve(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
