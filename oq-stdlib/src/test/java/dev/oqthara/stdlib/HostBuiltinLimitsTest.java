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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** Windows cannot launch a shell script, so the fixtures below carry two bodies. */
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private Interpreter interpreter;
    private ByteArrayOutputStream outBytes;

    @BeforeEach
    void setUp() {
        // The output ceiling is shared by every interpreter in the JVM, so a test that spends
        // it must not silently refuse the next test's writes.
        dev.oqthara.runtime.OutputBudget.reset();
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
        // The charge must not change behaviour for ordinary output. The separator is added by
        // `print` itself, not between arguments, and the final newline is what `println` appends.
        call("print", "hello", 1L);
        assertEquals("hello 1" + System.lineSeparator(),
                outBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void outputHasItsOwnByteCeilingRatherThanTheStepBudget() {
        // Output is charged against a dedicated byte counter, not the step budget. Charging it
        // as steps made the documented 16 MiB unreachable: the budget tripped at 195,312 bytes.
        int before = interpreter.maxSteps();
        interpreter.setMaxSteps(1_000);
        call("print", "x".repeat(100_000));
        interpreter.setMaxSteps(before);
    }

    @Test
    void aPrintThatCrossesTheCeilingWritesNothing() {
        // The charge lands before the bytes, so a refused print must not emit them first.
        outBytes.reset();
        OqError error = assertThrows(OqError.class,
                () -> call("print", "x".repeat((int) Interpreter.MAX_OUTPUT_BYTES + 1)));
        assertEquals("the program wrote too much output", error.getMessage());
        assertEquals(0, outBytes.size(), "a refused print must not write its bytes first");
    }

    @Test
    void exactlySixteenMebibytesIsAllowed() {
        // `print` adds a newline outside the text it was given, so the newline is part of what
        // the ceiling counts. Spend the newline first, then fill the rest with text.
        outBytes.reset();
        int newline = System.lineSeparator().length();
        call("print", "x".repeat((int) Interpreter.MAX_OUTPUT_BYTES - newline));
        assertEquals(Interpreter.MAX_OUTPUT_BYTES, outBytes.size());
    }

    @Test
    void oneBytePastSixteenMebibytesIsRefused() {
        // The boundary has to bite exactly once: 16 MiB is allowed, one byte more is not.
        outBytes.reset();
        int newline = System.lineSeparator().length();
        OqError error = assertThrows(OqError.class,
                () -> call("print", "x".repeat((int) Interpreter.MAX_OUTPUT_BYTES - newline + 1)));
        assertEquals("the program wrote too much output", error.getMessage());
        assertEquals(0, outBytes.size(), "a refused print must not write its bytes first");
    }

    @Test
    void theNewlinePrintAddsCountsTowardsTheCeiling() {
        // `println` adds the newline outside the text argument, so it used to be free. An empty
        // print emitted nothing charged, which made a loop of them unbounded output.
        dev.oqthara.runtime.OutputBudget.reset();
        int newline = System.lineSeparator().length();
        int prints = (int) Interpreter.MAX_OUTPUT_BYTES / newline;
        for (int i = 0; i < prints - 1; i++) call("print", "");
        int before = outBytes.size();
        assertEquals(newline * (prints - 1), before);
        OqError error = assertThrows(OqError.class, () -> {
            for (int i = 0; i < prints; i++) call("print", "");
        });
        assertEquals("the program wrote too much output", error.getMessage());
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
    void readAcceptsAFileWellPastTheOld195KilobyteCeiling() throws IOException {
        // Transfers used to be billed at 256 steps per byte against a 50,000,000-step budget,
        // which refused anything past 195,312 bytes however large the documented limit was.
        // The read limit is 16 MiB, so a 1 MiB file must load.
        Path file = dir.resolve("onemib.txt");
        byte[] filler = new byte[1024 * 1024];
        java.util.Arrays.fill(filler, (byte) 'a');
        Files.write(file, filler);
        assertEquals(1024 * 1024, ((String) call("read", file.toString())).length());
    }

    @Test
    void readAcceptsExactlyTheWholeLimit() throws IOException {
        // The documented 16 MiB is reachable: exactly the limit passes, one byte more does not.
        Path file = dir.resolve("exactly.txt");
        byte[] filler = new byte[Stdlib.MAX_TEXT_BYTES];
        java.util.Arrays.fill(filler, (byte) 'a');
        Files.write(file, filler);
        assertEquals(Stdlib.MAX_TEXT_BYTES, ((String) call("read", file.toString())).length());
    }

    @Test
    void readingPastTheLimitIsATooLargeErrorNotAStepError() throws IOException {
        // The refusal must name the size limit, not the step budget: the two used to be
        // confused, and the step budget won.
        Path file = dir.resolve("oversize.txt");
        byte[] filler = new byte[Stdlib.MAX_TEXT_BYTES + 1];
        java.util.Arrays.fill(filler, (byte) 'a');
        Files.write(file, filler);
        OqError error = assertThrows(OqError.class, () -> call("read", file.toString()));
        assertEquals("the file is too large to read: " + file, error.getMessage());
        assertFalse(error.getMessage().contains("ran for too long"), error.getMessage());
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
        assertEquals("the file is too large to read: " + file, error.getMessage());
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
        // Both fixtures write the same line and a trailing newline, so the exact assertion
        // below holds on either platform.
        OqData result = (OqData) call("run_command", script("echo oqthara", "echo oqthara"));
        assertEquals(0L, result.get("code"));
        assertEquals("oqthara" + System.lineSeparator(), result.get("output"));
    }

    @Test
    void runCommandReportsANonZeroExitCodeInsteadOfThrowing() throws IOException {
        // A failing command is an ordinary result, not a program error: the caller gets the
        // code and decides.
        OqData result = (OqData) call("run_command",
                script("echo boom; exit 3", "echo boom & exit /b 3"));
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
    void aChildProcessInheritsTheWholeEnvironment() throws Exception {
        // Documented in docs/spec/language.md: a child started by run_command gets the parent's
        // environment, because the ProcessBuilder sets none of its own. Asserting it keeps that
        // disclosure, and the security consequence written beside it, from quietly becoming
        // wrong -- if an allow-list is ever added this fails, and the spec has to change with it.
        //
        // The probe reads a variable this process already has rather than injecting one:
        // ProcessEnvironment.theUnmodifiableEnvironment is unreachable under the module system
        // (InaccessibleObjectException), and every JVM sets something. PATH is the one variable
        // that exists on every platform the build runs on.
        String name = "PATH";
        String inherited = System.getenv(name);
        assertNotNull(inherited, "PATH is set on every supported platform");
        assertFalse(inherited.isEmpty(), "PATH is never empty on a working build machine");

        OqData result = (OqData) call("run_command",
                script("printf %s \"$" + name + "\"", "Write-Output $env:" + name));
        assertEquals(0L, result.get("code"));
        assertEquals(inherited, String.valueOf(result.get("output")),
                "the child saw the same PATH this process holds, so it inherited the environment");
        assertEquals(inherited, call("env", name),
                "env() reads the same environment the child inherited");
    }

    @Test
    void runCommandTimeoutKillsTheWholeProcessTree() throws Exception {
        // A script that starts a long-lived child and then waits. Destroying only the script
        // would leave the child running after the program errored.
        Path marker = dir.resolve("child.pid");
        Path script = script(
                "sleep 60 & echo $! > " + marker + "; wait",
                powershell("$i = New-Object System.Diagnostics.ProcessStartInfo; "
                        + "$i.FileName = 'powershell.exe'; "
                        + "$i.Arguments = '-NoProfile -Command Start-Sleep -Seconds 60'; "
                        + "$i.UseShellExecute = $false; "
                        + "$c = [System.Diagnostics.Process]::Start($i); "
                        + "[IO.File]::WriteAllText('" + marker + "', [string]$c.Id); "
                        + "$c.WaitForExit()"));
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
        Path script = script("sleep 60", powershell("Start-Sleep -Seconds 60"));
        OqError error = assertThrows(OqError.class, () -> call("run_command", script.toString()));
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
    }

    /**
     * Writes an executable fixture and returns its path, since run_command takes no arguments.
     *
     * <p>A POSIX host runs the {@code #!/bin/sh} form directly. Windows cannot execute a shell
     * script at all: CreateProcess rejects it as a bad image format, because a text file is not a
     * valid executable image and there is no shebang handling. The same behaviour is produced
     * there by a batch file, which the JDK detects as a shell file and hands to cmd.exe. Each
     * caller supplies both bodies, so a test keeps one set of assertions and the platform
     * difference stays here rather than being duplicated into every test.
     */
    private Path script(String posixBody, String windowsBody) throws IOException {
        if (WINDOWS) {
            // cmd.exe wants CRLF and can misread a final line with no terminator.
            Path file = dir.resolve("fixture.bat");
            // Leave the test's temp directory before doing anything else. run_command makes the
            // interpreter's directory the child's working directory, and that directory is
            // `dir`, which @TempDir owns. Windows refuses to delete a directory that a live
            // process still holds as its current directory, and a child killed for exceeding a
            // limit is not necessarily torn down by the time @TempDir cleanup runs, so the
            // teardown failed instead of the assertion that actually guards the contract.
            // Doing this at the source means no fixture can reintroduce it.
            Files.writeString(file, "@echo off\r\n"
                    + "cd /d \"%TEMP%\"\r\n"
                    + windowsBody.replace("\n", "\r\n") + "\r\n");
            return file;
        }
        Path file = dir.resolve("fixture.sh");
        Files.writeString(file, "#!/bin/sh\n" + posixBody + "\n");
        // Only POSIX carries an execute bit; Windows has no equivalent and needs none, since
        // the image format alone decides what can be launched.
        assertTrue(file.toFile().setExecutable(true), "could not mark the fixture executable");
        return file;
    }

    /** Runs a PowerShell snippet as the body of a Windows fixture. */
    private static String powershell(String snippet) {
        return "powershell.exe -NoProfile -Command \"" + snippet + "\"";
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
                () -> call("run_command", script("head -c 20000000 /dev/zero",
                        powershell("$b = [Text.Encoding]::ASCII.GetBytes('x' * 65536); "
                                + "$o = [Console]::OpenStandardOutput(); "
                                + "for ($i = 0; $i -lt 320; $i++) { $o.Write($b, 0, $b.Length) }; "
                                + "$o.Flush()"))));
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

    @Test
    void appendNamesItselfRatherThanSayingSave() throws IOException {
        // `append` used to report `cannot save`, which sent the reader looking for a save call
        // that was not in their program.
        String big = "x".repeat(Stdlib.MAX_TEXT_BYTES + 1);
        Path file = dir.resolve("appended.txt");
        OqError error = assertThrows(OqError.class,
                () -> call("append", file.toString(), big));
        assertEquals("cannot append " + big.length() + " bytes in one call", error.getMessage());
        assertFalse(Files.exists(file), "the refused append must not create the file");
    }

    @Test
    void saveNamesItself() {
        String big = "x".repeat(Stdlib.MAX_TEXT_BYTES + 1);
        OqError error = assertThrows(OqError.class,
                () -> call("save", dir.resolve("saved.txt").toString(), big));
        assertEquals("cannot save " + big.length() + " bytes in one call", error.getMessage());
    }

    @Test
    void theWriteLimitCountsBytesNotCharacters() {
        // A non-ASCII character is one character but several bytes. Counting characters would
        // let a program write four times the documented limit with astral characters.
        String emoji = "\uD83D\uDE00".repeat(Stdlib.MAX_TEXT_BYTES / 4 + 1);
        OqError error = assertThrows(OqError.class,
                () -> call("save", dir.resolve("emoji.txt").toString(), emoji));
        assertTrue(error.getMessage().startsWith("cannot save " + emoji.getBytes(
                StandardCharsets.UTF_8).length + " bytes"), error.getMessage());
    }

    @Test
    void theWriteLimitIsPerCallSoSaveThenAppendCanBuildALargerFile() throws IOException {
        // The limit is stated as "in one call", so a program that writes in pieces must be able
        // to pass it. A cumulative check would refuse the second call and make `append` useless
        // for exactly the case its help text recommends it for.
        Path file = dir.resolve("pieces.txt");
        String chunk = "y".repeat(Stdlib.MAX_TEXT_BYTES - 1);
        call("save", file.toString(), chunk);
        call("append", file.toString(), chunk);
        assertEquals((Stdlib.MAX_TEXT_BYTES - 1) * 2L, Files.size(file));
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

    // --- billing units ---------------------------------------------------------------------

    /**
     * Sets a budget just below what one call would cost, so the call trips it and the charge is
     * observable. {@code steps} is private to the interpreter, and adding a test-only getter to
     * production code to read it back is worse than proving the charge by what it refuses.
     */
    private void budgetExactly(long units) {
        interpreter.resetSteps();
        interpreter.setMaxSteps((int) units);
    }

    private static void assertTrips(OqError error, String what) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("program ran for too long")) return;
        }
        throw new AssertionError(what + " did not charge the step budget", error);
    }

    @Test
    void listFilesBillsOneStepPerDirectoryEntry() throws IOException {
        for (String name : List.of("a.txt", "b.txt", "c.txt")) {
            Files.writeString(dir.resolve(name), "x");
        }
        // Three entries in the working directory. A budget of 3 is exactly enough; asking for
        // one less must trip, which is only true if the charge is the entry count and not a
        // flat cost or a byte count.
        budgetExactly(3);
        assertEquals(3, ((List<?>) call("list_files", ".")).size());
        budgetExactly(2);
        OqError error = assertThrows(OqError.class, () -> call("list_files", "."));
        assertTrips(error, "list_files");
    }

    @Test
    void inputBillsOneStepPerCharacterRatherThanPerByte() {
        // "é" is one Java char but two UTF-8 bytes, so it bills exactly 1. A budget of 1 must
        // therefore suffice. Billing bytes would charge 2 and trip, which is what this pins.
        Interpreter line = interpreterReading("é\n");
        line.setMaxSteps(1);
        assertEquals("é", line.builtins().get("input").call(line, List.of(), Span.synthetic()));

        // A budget of 0 refuses before the first character is appended.
        Interpreter none = interpreterReading("x\n");
        none.setMaxSteps(0);
        OqError error = assertThrows(OqError.class,
                () -> none.builtins().get("input").call(none, List.of(), Span.synthetic()));
        assertTrips(error, "input");
    }

    private Interpreter interpreterReading(String text) {
        dev.oqthara.runtime.OutputBudget.reset();
        Interpreter created = new Interpreter(
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new java.io.BufferedReader(new java.io.StringReader(text)),
                List.of(), dir, new MethodResolverImpl());
        Stdlib.install(created);
        return created;
    }

    @Test
    void theInputLimitMessageCountsCharactersNotBytes() {
        // The bound is checked against sb.length(), which counts Java chars, so the refusal
        // must not call them bytes: an astral character is one char but four UTF-8 bytes. The
        // reader never ends, so the call is guaranteed to stop at the bound rather than EOF.
        Interpreter tight = new Interpreter(
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new java.io.BufferedReader(new java.io.Reader() {
                    @Override
                    public int read(char[] buffer, int offset, int length) {
                        java.util.Arrays.fill(buffer, offset, offset + length, 'a');
                        return length;
                    }

                    @Override
                    public int read() {
                        return 'a';
                    }

                    @Override
                    public void close() {
                    }
                }),
                List.of(), dir, new MethodResolverImpl());
        Stdlib.install(tight);
        OqError error = assertThrows(OqError.class, () -> {
            OqCallable input = tight.builtins().get("input");
            input.call(tight, List.of(), Span.synthetic());
        });
        assertEquals("input line is too large", error.getMessage());
        String hint = error.hint();
        assertTrue(hint.contains("character"), "hint said: " + hint);
        assertFalse(hint.contains("byte"), "hint said: " + hint);
    }
}
