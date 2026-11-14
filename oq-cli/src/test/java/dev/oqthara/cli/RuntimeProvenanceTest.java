package dev.oqthara.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provenance verification before extraction (ADR-0048 D3).
 *
 * <p>The property under test is the negative one: an archive that does not match the compiled
 * digest must be refused, and refused while it is still an opaque file. A test that only shows a
 * good archive is accepted would pass on a verifier that returned the constant "ok".
 */
class RuntimeProvenanceTest {

    private static String digestOf(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Exactly the platforms {@link PlatformSupport} supports, and no others.
     *
     * <p>Two tables that must agree are a drift hazard: a platform added to one and not the
     * other would install a runtime no digest covers, or pin an archive nothing can request. This
     * asserts the sets are equal rather than that both have five entries, so adding one to either
     * fails here until it is added to both.
     */
    @Test
    void pinsExactlyTheSupportedPlatforms() {
        Set<String> pinned = new HashSet<>();
        for (PlatformSupport.Platform platform : RuntimeProvenance.pinnedPlatforms()) {
            pinned.add(platform.label());
        }
        Set<String> supported = new HashSet<>();
        for (PlatformSupport.Platform platform : PlatformSupport.supportedPlatforms()) {
            supported.add(platform.label());
        }
        assertEquals(supported, pinned);
        assertEquals(5, pinned.size(), "ADR-0048 D2 names five platforms");
    }

    /** Every pinned archive is a URL, a 64-character lowercase digest and a positive size. */
    @Test
    void everyPinIsWellFormed() {
        for (PlatformSupport.Platform platform : RuntimeProvenance.pinnedPlatforms()) {
            RuntimeProvenance.Archive archive = RuntimeProvenance.pinned(platform).orElseThrow();
            assertTrue(archive.url().startsWith("https://"),
                    platform.label() + " must be fetched over TLS");
            // The URL is percent-encoded, so it carries the version as `21.0.13` and the build
            // as `_9` rather than the human spelling `21.0.13+9`.
            assertTrue(archive.url().contains("21.0.13_9"),
                    platform.label() + " must name the pinned release: " + archive.url());
            assertTrue(archive.size() > 0, platform.label() + " must carry a size");
            assertEquals(64, archive.sha256().length());
            assertEquals(archive.sha256().toLowerCase(java.util.Locale.ROOT), archive.sha256(),
                    platform.label() + " digest must be lowercase so comparison is exact");
        }
    }

    /**
     * The digest of a file is what the table says it is.
     *
     * <p>A known vector rather than a round-trip: computing the expected value with the same
     * method under test would assert that the method equals itself.
     */
    @Test
    void hashesAFileWithSha256(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("file");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RuntimeProvenance.sha256(file));
    }

    /** A file that matches nothing is refused, and the message names both digests. */
    @Test
    void refusesAnArchiveThatDoesNotMatchThePin(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("archive");
        Files.writeString(file, "not a JDK", StandardCharsets.UTF_8);
        PlatformSupport.Platform platform = new PlatformSupport.Platform("macos", "aarch64");

        RuntimeInstaller.InstallRefusedException refused = assertThrows(
                RuntimeInstaller.InstallRefusedException.class,
                () -> RuntimeProvenance.verify(file, platform));

        assertTrue(refused.getMessage().contains("does not match"),
                "the refusal must say what went wrong: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(RuntimeProvenance.pinned(platform).orElseThrow()
                .sha256()), "the refusal must name the expected digest");
        assertTrue(refused.getMessage().contains(digestOf("not a JDK".getBytes(StandardCharsets.UTF_8))),
                "the refusal must name the digest that was found");
        assertTrue(Files.exists(file), "verification must not delete the file it refused");
    }

    /**
     * A platform outside the five is refused rather than treated as a wildcard.
     *
     * <p>The failure mode this prevents is an unpinned platform silently skipping verification,
     * which would make D3 apply only where someone remembered to add a row.
     */
    @Test
    void refusesAPlatformWithNoPin(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("archive");
        Files.writeString(file, "anything", StandardCharsets.UTF_8);
        PlatformSupport.Platform unsupported = new PlatformSupport.Platform("linux", "riscv64");

        RuntimeInstaller.InstallRefusedException refused = assertThrows(
                RuntimeInstaller.InstallRefusedException.class,
                () -> RuntimeProvenance.verify(file, unsupported));

        assertTrue(refused.getMessage().contains("linux/riscv64"),
                "the refusal must name the platform: " + refused.getMessage());
        assertTrue(RuntimeProvenance.pinned(unsupported).isEmpty());
    }

    /** An archive that is not a file is refused, and says so rather than failing on a read. */
    @Test
    void refusesAMissingArchive(@TempDir Path temp) {
        RuntimeInstaller.InstallRefusedException refused = assertThrows(
                RuntimeInstaller.InstallRefusedException.class,
                () -> RuntimeProvenance.verify(temp.resolve("absent"),
                        new PlatformSupport.Platform("linux", "x86_64")));
        assertTrue(refused.getMessage().contains("does not exist"));
    }

    /**
     * A digest that is not 64 lowercase hex characters cannot be constructed.
     *
     * <p>The check is in the record's constructor rather than in a test-only helper because a
     * malformed digest would never match anything: it would turn every download into a refusal
     * that looked like an attack. Failing where the table is written names the real defect.
     */
    @Test
    void rejectsAMalformedDigest() {
        String valid = "a".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProvenance.Archive("https://example.invalid/a", valid.toUpperCase(
                        java.util.Locale.ROOT), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProvenance.Archive("https://example.invalid/a", "abc", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProvenance.Archive("https://example.invalid/a", valid, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProvenance.Archive("http://example.invalid/a", valid, 1));
    }

    /** The description names the vendor and the release, so a report can be acted on. */
    @Test
    void describesThePin() {
        String description = RuntimeProvenance.describePin();
        assertTrue(description.contains(RuntimeProvenance.PINNED_RELEASE), description);
        assertTrue(description.contains("Temurin"), description);
    }

    /** The archive suffix follows the artefact, and disagrees with the pinned name if it is wrong. */
    @Test
    void archiveSuffixMatchesThePinnedArtefact() {
        for (PlatformSupport.Platform platform : RuntimeProvenance.pinnedPlatforms()) {
            String url = RuntimeProvenance.pinned(platform).orElseThrow().url();
            assertTrue(url.endsWith(PlatformSupport.archiveSuffix(platform)),
                    platform.label() + " pin " + url + " should end with "
                            + PlatformSupport.archiveSuffix(platform));
        }
    }

    /** A directory is not an archive, whatever its name. */
    @Test
    void refusesADirectory(@TempDir Path temp) throws IOException {
        Path directory = Files.createDirectory(temp.resolve("archive"));
        assertFalse(Files.isRegularFile(directory));
        assertThrows(RuntimeInstaller.InstallRefusedException.class,
                () -> RuntimeProvenance.verify(directory,
                        new PlatformSupport.Platform("linux", "x86_64")));
    }
}
