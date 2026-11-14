package dev.oqthara.cli;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transfer rules for the runtime downloader (ADR-0048 D3).
 *
 * <p>These run against a loopback server rather than the vendor, because what is under test is not
 * "can we reach Adoptium" — that is a network question and would make the suite flaky and offline
 * hostile — but the rules the transfer itself must obey: refuse an error status, refuse a body that
 * is not the size the pin names, and leave nothing behind that could later be mistaken for a
 * complete archive.
 */
class RuntimeDownloaderTest {

    /** Runs {@code body} for any GET, then shuts the server down. */
    private static HttpServer serving(int status, byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runtime.tar.gz", exchange -> {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static URI uriOf(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/runtime.tar.gz");
    }

    @Test
    void writesTheArchiveWhenTheTransferSucceeds(@TempDir Path temp) throws Exception {
        byte[] payload = "runtime-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = serving(200, payload);
        try {
            Path destination = temp.resolve("downloads/runtime.tar.gz");
            Path result = new RuntimeDownloader().download(uriOf(server), payload.length, destination);

            assertEquals(destination, result);
            assertArrayEquals(payload, Files.readAllBytes(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesAnErrorStatusAndNamesTheHost(@TempDir Path temp) throws Exception {
        HttpServer server = serving(503, new byte[0]);
        try {
            Path destination = temp.resolve("downloads/runtime.tar.gz");
            IOException refused = assertThrows(IOException.class,
                    () -> new RuntimeDownloader().download(uriOf(server), 10, destination));

            assertTrue(refused.getMessage().contains("127.0.0.1"),
                    "the message must name the host, was: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("503"),
                    "the message must name the status, was: " + refused.getMessage());
            assertNothingDownloaded(destination);
        } finally {
            server.stop(0);
        }
    }

    /**
     * A body shorter than the pin is refused, and no partial file survives.
     *
     * <p>The surviving-<code>.part</code> case is the one that matters: a later run that trusted a
     * leftover would hash half an archive and report a digest mismatch instead of an interrupted
     * download, which sends the reader looking for the wrong problem.
     */
    @Test
    void refusesAShortBodyAndLeavesNoPartial(@TempDir Path temp) throws Exception {
        HttpServer server = serving(200, "short".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            Path destination = temp.resolve("downloads/runtime.tar.gz");
            IOException refused = assertThrows(IOException.class,
                    () -> new RuntimeDownloader().download(uriOf(server), 5000, destination));

            assertTrue(refused.getMessage().contains("5"),
                    "the message must report how many bytes arrived, was: " + refused.getMessage());
            assertNothingDownloaded(destination);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesABodyLargerThanThePinAllows(@TempDir Path temp) throws Exception {
        byte[] payload = new byte[64];
        HttpServer server = serving(200, payload);
        try {
            Path destination = temp.resolve("downloads/runtime.tar.gz");
            assertThrows(IOException.class,
                    () -> new RuntimeDownloader().download(uriOf(server), 1, destination));

            assertNothingDownloaded(destination);
        } finally {
            server.stop(0);
        }
    }

    /** Neither the destination nor a {@code .part} sibling may exist after a refusal. */
    private static void assertNothingDownloaded(Path destination) throws IOException {
        assertFalse(Files.exists(destination), "the destination must not exist after a refusal");
        assertFalse(Files.exists(destination.resolveSibling(destination.getFileName() + ".part")),
                "no partial file may survive a refusal");
        Path directory = destination.getParent();
        if (Files.exists(directory)) {
            try (Stream<Path> entries = Files.list(directory)) {
                assertTrue(entries.findAny().isEmpty(),
                        "the download directory must be empty after a refusal");
            }
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "downloaded length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i);
        }
    }
}
