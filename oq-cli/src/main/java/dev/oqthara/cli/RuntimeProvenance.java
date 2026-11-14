package dev.oqthara.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * The SHA-256 of every runtime archive {@code oq} is willing to install (ADR-0048 D3).
 *
 * <p>D3's wording is precise about where the digest comes from: it is <em>compiled into</em>
 * {@code oq}, not fetched from beside the archive. A checksum published next to the download
 * detects corruption — the bytes arrived intact — but not substitution, because whoever can
 * replace the archive can replace the file that vouches for it. A digest in the binary is not
 * fetched at install time at all, so an attacker who controls the mirror and the network still
 * has nothing to swap in.
 *
 * <p>That distinction is the whole reason this table is code rather than data on disk. Encoding
 * the digests as a resource would look the same and mean the opposite: a resource is a file in
 * the installation directory, which an attacker who can write there has already won. The table is
 * therefore a compile-time constant and the file that holds it is the file that is signed.
 *
 * <p>Every entry was taken from the Eclipse Adoptium API and then checked against the vendor's
 * own {@code .sha256.txt} beside the GitHub release asset, so a single wrong field in one
 * response could not silently become the compiled truth. {@code RuntimeProvenanceTest} pins the
 * release the table describes, and {@code scripts/verify-runtime-digests.py} re-fetches and
 * re-compares all five entries against the vendor, which is what makes the table maintainable
 * rather than a snapshot nobody can check.
 */
final class RuntimeProvenance {

    /**
     * The Temurin release this release of {@code oq} pins.
     *
     * <p>ADR-0048's open question names one pinned LTS per {@code oq} release as the default and
     * user-selectable LTS as deferred. Pinning in the source rather than resolving "latest" at
     * install time is what makes the compiled digest meaningful: a digest for a moving target
     * would be stale by the next release, and "latest" is a choice the vendor makes, not this
     * program.
     */
    static final String PINNED_RELEASE = "jdk-21.0.13+9";

    /** The vendor whose archives this table describes. Named so a diagnostic can say it. */
    static final String PINNED_VENDOR = "Eclipse Adoptium (Temurin)";

    /**
     * One artefact: what to fetch, and the digest that fetch must produce.
     *
     * <p>{@code size} is carried because D8 and the installer both need to bound a download
     * before starting it, and a size published by the vendor is a better bound than a guess made
     * here. It is not a security control — the digest is — so a size mismatch is reported as a
     * truncated transfer rather than as tampering.
     */
    record Archive(String url, String sha256, long size) {
        Archive {
            // The scheme is checked as well as the digest, because D3 requires the transfer to be
            // protected by TLS. An `http://` entry would silently discard that half of the
            // requirement while the digest still made the archive look verified.
            if (url == null || !url.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "a pinned runtime archive must be fetched over https: " + url);
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "a provenance digest must be 64 lowercase hex characters: " + sha256);
            }
            // A size of zero is refused rather than treated as "unknown": the downloader compares
            // the transferred length against it, so a zero would refuse every real transfer and
            // an unknown would mean there was no bound at all.
            if (size <= 0) {
                throw new IllegalArgumentException(
                        "a pinned runtime archive must have a positive size: " + size);
            }
        }
    }

    /**
     * The five supported platforms, each mapped to the artefact ADR-0048 D2 names for it.
     *
     * <p>A {@link HashMap} keyed by {@link PlatformSupport.Platform} rather than five fields,
     * because the lookup is by detected platform and a field name cannot be looked up with a
     * runtime value. Keys come from {@link PlatformSupport}, so an entry for a platform the
     * manager cannot serve would be unreachable rather than merely unused.
     */
    private static final Map<PlatformSupport.Platform, Archive> PINNED = pinned();

    private static Map<PlatformSupport.Platform, Archive> pinned() {
        String base = "https://github.com/adoptium/temurin21-binaries/releases/download/"
                + "jdk-21.0.13%2B9/";
        Map<PlatformSupport.Platform, Archive> table = new HashMap<>();
        table.put(new PlatformSupport.Platform("linux", "x86_64"), new Archive(
                base + "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
                "d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c",
                207858352L));
        table.put(new PlatformSupport.Platform("linux", "aarch64"), new Archive(
                base + "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.13_9.tar.gz",
                "e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578",
                205995218L));
        table.put(new PlatformSupport.Platform("macos", "x86_64"), new Archive(
                base + "OpenJDK21U-jdk_x64_mac_hotspot_21.0.13_9.tar.gz",
                "9aa1d1c17ae5467a022679e7e0af5c00797d94ac462ed1a46f4db07a8a34a6dd",
                193957782L));
        table.put(new PlatformSupport.Platform("macos", "aarch64"), new Archive(
                base + "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz",
                "bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a",
                199754598L));
        table.put(new PlatformSupport.Platform("windows", "x86_64"), new Archive(
                base + "OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip",
                "a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9",
                205421281L));
        return Map.copyOf(table);
    }

    private RuntimeProvenance() {
    }

    /** The archive pinned for {@code platform}, or empty when no artefact is published for it. */
    static Optional<Archive> pinned(PlatformSupport.Platform platform) {
        return Optional.ofNullable(PINNED.get(platform));
    }

    /**
     * Every pinned platform, so a test can assert the table covers exactly the supported set.
     *
     * <p>Returned as a copy because the table is a compile-time constant and a caller mutating
     * the view would be mutating the control.
     */
    static Map<PlatformSupport.Platform, Archive> pinnedArchives() {
        return Map.copyOf(PINNED);
    }

    /**
     * Refuses {@code archive} unless its bytes hash to the pinned digest.
     *
     * <p>The comparison is against the digest for the platform being installed, never against
     * "some digest in the table": an archive built for another platform hashes correctly for that
     * platform and is still the wrong artefact here, which is why the caller passes the platform
     * rather than a digest to match. Refusal is the only failure mode; a mismatch never proceeds
     * to extraction, so a substituted archive is rejected while it is still an opaque file.
     *
     * <p>Both digests appear in the message because a mismatch has two plausible causes that look
     * identical otherwise — a corrupt or substituted download, and a table that has fallen behind
     * a new vendor release. Naming both lets the reader tell them apart without guessing, and the
     * message says which one is compiled in.
     *
     * @throws RuntimeInstaller.InstallRefusedException when the digest does not match
     */
    static void verify(Path archive, PlatformSupport.Platform platform)
            throws IOException, RuntimeInstaller.InstallRefusedException {
        if (!Files.isRegularFile(archive)) {
            throw new RuntimeInstaller.InstallRefusedException(
                    "the runtime archive does not exist: " + archive);
        }
        Optional<Archive> expected = pinned(platform);
        if (expected.isEmpty()) {
            throw new RuntimeInstaller.InstallRefusedException(
                    "no runtime archive is pinned for " + platform.label()
                            + ", so this archive's provenance cannot be checked");
        }
        String actual = sha256(archive);
        String wanted = expected.get().sha256();
        if (!actual.equals(wanted)) {
            throw new RuntimeInstaller.InstallRefusedException(
                    "the runtime archive does not match the digest compiled into oq for "
                            + platform.label() + ".\n  expected " + wanted
                            + "\n  actual   " + actual
                            + "\nThe download was refused before extraction. If this file came "
                            + "from " + PINNED_VENDOR + " " + PINNED_RELEASE
                            + ", then this oq predates that release; otherwise the file is not "
                            + "the one oq expects.");
        }
    }

    /**
     * The SHA-256 of a file, lowercase hex.
     *
     * <p>Streamed rather than read whole: a JDK archive is around 200 MB, and a check that needs
     * the file in memory would fail on exactly the small machines a managed runtime is for.
     */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256, so this cannot happen on a
            // conformant JVM. Reporting it as a plain IOException keeps the caller's handling
            // uniform rather than adding a checked exception nobody can act on.
            throw new IOException("SHA-256 is unavailable in this JVM", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Every platform a pinned archive exists for, so a caller can enumerate the claim. */
    static java.util.Set<PlatformSupport.Platform> pinnedPlatforms() {
        return PINNED.keySet();
    }

    /** Diagnostic text naming the pinned release, for a refusal or an install report. */
    static String describePin() {
        return PINNED_VENDOR + " " + PINNED_RELEASE + ", five platform archives, digests compiled in";
    }
}
