package dev.oqthara.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Fetches the pinned runtime archive for this platform (ADR-0048 D3).
 *
 * <p>This is the acquisition step the row named as absent: before it existed, {@code oq} could
 * install an archive a user had already obtained and nothing could obtain one. It is deliberately
 * the smallest possible step — fetch one URL that {@link RuntimeProvenance} names, and hand the
 * bytes to {@link RuntimeInstaller} — because the security property belongs to the digest check
 * that follows, not to the code that moves bytes. Doing the check here would mean two places
 * decide whether an archive is acceptable; the downloader therefore makes no claim about what it
 * fetched.
 *
 * <p>Transport is TLS with the platform's own trust store, which is D3's wording: certificate
 * validation is left to the platform rather than reimplemented. That makes the transport guard
 * against a network attacker but says nothing about a compromised or malicious mirror, which is
 * exactly the gap the compiled digest closes.
 *
 * <p>The download is bounded in three ways, because an unbounded download from a remote host is a
 * denial of service the user invited: a connect timeout, a per-request timeout, and a hard limit
 * on bytes written that refuses a response larger than the pinned size allows. Verification is
 * not performed here; see {@link RuntimeProvenance#verify}.
 */
final class RuntimeDownloader {

    /** How long to wait for a connection before reporting the network unreachable. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * How long the whole transfer may take.
     *
     * <p>A 200 MB archive over a slow but working link must not be refused, so this is generous;
     * its purpose is to stop a stalled connection hanging an install forever, which the deadlock
     * report in this session's history found is a real failure mode.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Headroom over the pinned size before a response is refused.
     *
     * <p>The pinned size is the vendor's; a mirror that recompresses an archive could legitimately
     * differ slightly. The digest is the control, so this bound exists only to stop a hostile or
     * broken response filling the disk before the digest can reject it.
     */
    private static final long SIZE_MARGIN = 16L * 1024 * 1024;

    private final HttpClient client;
    private final Progress progress;

    /** Reports transfer progress, so a multi-minute download is not a silent wait. */
    interface Progress {
        void update(long bytesSoFar, long expected);

        /** Reports nothing when a caller does not want progress. */
        Progress NONE = (bytesSoFar, expected) -> { };
    }

    RuntimeDownloader() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), Progress.NONE);
    }

    RuntimeDownloader(HttpClient client, Progress progress) {
        this.client = client;
        this.progress = progress;
    }

    /**
     * Downloads the archive pinned for {@code platform} into {@code destination}.
     *
     * <p>The bytes are written to a temporary sibling and moved into place only after the whole
     * response has arrived, so an interrupted download leaves no file that looks complete. That
     * mirrors D5's staging rule one level earlier: the same reasoning that keeps a partial
     * extraction out of the runtime directory keeps a partial download out of the cache.
     *
     * @return the path of the complete download, which is {@code destination}
     * @throws IOException when the network fails, the response is an error, or the transfer
     *     exceeds its size bound; the message names the host and the cause
     * @throws InterruptedException when the thread is interrupted; nothing is left behind
     */
    Path download(PlatformSupport.Platform platform, Path destination)
            throws IOException, InterruptedException {
        Optional<RuntimeProvenance.Archive> pinned = RuntimeProvenance.pinned(platform);
        if (pinned.isEmpty()) {
            throw new IOException("no runtime archive is published for " + platform.label()
                    + ", so there is nothing to download");
        }
        RuntimeProvenance.Archive archive = pinned.get();
        return download(URI.create(archive.url()), archive.size(), destination);
    }

    /**
     * Transfers {@code uri} to {@code destination}, expecting {@code expectedSize} bytes.
     *
     * <p>Split out from {@link #download(PlatformSupport.Platform, Path)} so the transfer rules —
     * the size bound, the error messages, the cleanup of a partial file — can be exercised against
     * a local server without pretending a loopback address is a pinned vendor URL. The caller that
     * a user reaches still goes through the pin; this method makes no claim about what it fetched
     * either way.
     */
    Path download(URI uri, long expectedSize, Path destination)
            throws IOException, InterruptedException {
        Files.createDirectories(destination.toAbsolutePath().getParent());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "oq-runtime-manager")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IOException("could not download the runtime from " + uri.getHost()
                    + ": " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("the runtime download from " + uri.getHost()
                    + " answered HTTP " + response.statusCode()
                    + "; the pinned release is " + RuntimeProvenance.PINNED_RELEASE);
        }

        long limit = expectedSize + SIZE_MARGIN;
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        long written = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new IOException("the runtime download exceeded " + limit
                            + " bytes, which is more than the pinned archive can be; the transfer"
                            + " was stopped and nothing was installed");
                }
                out.write(buffer, 0, read);
                progress.update(written, expectedSize);
            }
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        } catch (RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (written != expectedSize) {
            Files.deleteIfExists(partial);
            throw new IOException("the runtime download ended after " + written
                    + " bytes; the pinned archive is " + expectedSize
                    + ". Nothing was installed.");
        }
        Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }
}
