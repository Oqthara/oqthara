package dev.oqthara.cli;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Installs a runtime archive so that an interruption can never leave a half-installed runtime.
 *
 * <p>ADR-0048 D5. The sequence is: take the installation lock, extract into a staging directory
 * beside the final location, validate the extracted {@code java} by running it, then move the
 * staging directory into place with one rename. Every step before the rename is reversible by
 * deleting the staging directory, and the rename itself is atomic on a single filesystem, which
 * is why the staging directory is created next to the destination rather than in the system
 * temporary directory.
 *
 * <p>A runtime is only ever visible at its final path when it has already been validated. That
 * ordering is the whole point: validation is a question about the bytes that will be activated,
 * so it has to ask before activation and about the same bytes. There is deliberately no fallback
 * for a filesystem that refuses an atomic rename — copying into place instead would reintroduce
 * exactly the partial state this class exists to prevent, and the caller is told instead.
 */
final class RuntimeInstaller {

    /** The directory name used for staging inside the managed home. */
    private static final String STAGING_PREFIX = ".staging-";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Path home;
    private final Duration lockTimeout;
    private final RuntimeResolver.VersionProbe probe;
    private final Path compatibilityJar;
    private final Activation activation;

    RuntimeInstaller(Path home) {
        this(home, DEFAULT_TIMEOUT, RuntimeResolver.processProbe(), null);
    }

    RuntimeInstaller(Path home, Duration lockTimeout, RuntimeResolver.VersionProbe probe) {
        this(home, lockTimeout, probe, null);
    }

    /**
     * @param compatibilityJar the Oqthara jar to run on a candidate JVM (ADR-0048 D9), or
     *     {@code null} to validate only the reported version. {@code null} is what a test wants:
     *     the bounded compatibility check spawns real JVMs and belongs to the install path, not
     *     to every unit-test assertion about ordering.
     */
    RuntimeInstaller(Path home, Duration lockTimeout, RuntimeResolver.VersionProbe probe,
            Path compatibilityJar) {
        this(home, lockTimeout, probe, compatibilityJar, RuntimeInstaller::activateAtomically);
    }

    /**
     * @param activation the rename that makes a runtime live. Overridable only so a test can make
     *     activation fail after rotation has already happened; ADR-0048 D7 promises the working
     *     runtime survives that case, and a promise about an unreachable state is an untested one.
     */
    RuntimeInstaller(Path home, Duration lockTimeout, RuntimeResolver.VersionProbe probe,
            Path compatibilityJar, Activation activation) {
        this.home = home.toAbsolutePath().normalize();
        this.lockTimeout = lockTimeout;
        this.probe = probe;
        this.compatibilityJar = compatibilityJar;
        this.activation = activation;
    }

    /** The rename that publishes a validated runtime at the live path. */
    interface Activation {
        void activate(Path extracted, Path target) throws IOException;
    }

    /** Where a validated runtime ends up. */
    Path destination() {
        return home.resolve("runtime").resolve("java");
    }

    /** Why an installation was refused, as opposed to failing. */
    static final class InstallRefusedException extends Exception {
        private static final long serialVersionUID = 1L;

        InstallRefusedException(String message) {
            super(message);
        }

        InstallRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Removes staging directories left by an interrupted run.
     *
     * <p>They are identifiable by name and never contain an active runtime, so deleting them is
     * safe by construction. Called before extracting so an interrupted run does not accumulate
     * disk usage, and never called on a directory this run created.
     *
     * @return the number of abandoned directories removed
     */
    int discardAbandonedStaging() throws IOException {
        if (!Files.isDirectory(home)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(home,
                STAGING_PREFIX + "*")) {
            for (Path staged : entries) {
                deleteRecursively(staged);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Installs {@code archive} as the managed runtime.
     *
     * @param archive a runtime archive, already verified by the caller
     * @param minimumMajor the lowest major version that may be activated
     * @return the path of the activated {@code java}
     * @throws InstallRefusedException if the archive extracts but the result must not be activated
     * @throws IllegalStateException if another installation holds the lock for too long
     * @throws IOException if extraction, validation or activation fails
     * @throws InterruptedException if the thread is interrupted; nothing is left behind
     */
    Path install(Path archive, int minimumMajor)
            throws IOException, InterruptedException, InstallRefusedException {
        return installWith(archive, minimumMajor, null);
    }

    /**
     * Installs {@code archive} over an existing runtime, retaining the one being replaced.
     *
     * <p>ADR-0048 D7. The difference from {@link #install} is only the rotation: the active
     * runtime is renamed to {@code .prev} instead of the rename being refused, so an upgrade
     * leaves a complete previous runtime on disk rather than deleting one to make room. The D5
     * ordering is unchanged — staging is still validated before anything is touched — which means
     * a corrupt archive fails with the existing runtime still active.
     *
     * @return the activated runtime home
     */
    Path upgrade(Path archive, int minimumMajor)
            throws IOException, InterruptedException, InstallRefusedException {
        return installWith(archive, minimumMajor, new RuntimeRetention(home));
    }

    /**
     * Installs, rotating first when a retention policy is supplied.
     *
     * <p>Rotation happens after validation and immediately before activation, so a refusal never
     * displaces a working runtime. A {@code null} policy installs without rotating, which is what
     * a first install wants: there is nothing to keep.
     *
     * <p>Activation can still fail after the rotation has moved the working runtime aside — the
     * filesystem may refuse the atomic rename (ADR-0048 D5) or the live path may be unrenamable.
     * ADR-0048 D7 promises that a refused upgrade leaves the working runtime active, so that case
     * is undone explicitly: the retained runtime is restored and the caller sees the original
     * failure. Without this the failure mode would be the one D7 says cannot happen, an upgrade
     * that destroys the only working runtime and leaves it one directory away under
     * {@code java.prev}, where {@link RuntimeResolver} does not look and nothing reaches
     * {@link RuntimeRetention#rollback()}.
     */
    private Path installWith(Path archive, int minimumMajor, RuntimeRetention retention)
            throws IOException, InterruptedException, InstallRefusedException {
        if (!Files.isRegularFile(archive)) {
            throw new InstallRefusedException("the runtime archive does not exist: " + archive);
        }
        Files.createDirectories(home);

        try (InstallLock lock = InstallLock.acquire(home, lockTimeout)) {
            assert lock.file().getParent().equals(home);
            discardAbandonedStaging();

            Path staging = home.resolve(STAGING_PREFIX + UUID.randomUUID());
            Files.createDirectory(staging);
            boolean rotated = false;
            try {
                SafeArchive.extract(archive, staging);
                Path extracted = locate(staging);
                validate(extracted.resolve("bin").resolve(executableName()), minimumMajor);
                if (retention != null) {
                    rotated = retention.rotate();
                }
                activation.activate(extracted, destination());
                return destination();
            } catch (IOException | InstallRefusedException | RuntimeException e) {
                deleteRecursively(staging);
                if (rotated) {
                    restoreAfterFailedActivation(retention, e);
                }
                throw e;
            }
        }
    }

    /**
     * Puts the runtime that rotation moved aside back at the live path.
     *
     * <p>The activation failure is what the caller needs to see, so a failure here is reported on
     * the exception that is about to be thrown rather than replacing it. If this also fails the
     * state is genuinely ambiguous — the retained runtime is still on disk under
     * {@code java.prev} — so the message says where it is and how to restore it by hand.
     */
    private void restoreAfterFailedActivation(RuntimeRetention retention, Exception cause) {
        try {
            retention.rollback();
        } catch (IOException | RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
            cause.addSuppressed(new IOException(
                    "the runtime being replaced is still retained at " + retention.previous()
                            + "; restore it by renaming it to " + retention.active()));
        }
    }

    /**
     * Finds the {@code java} executable the archive produced.
     *
     * <p>JDK archives wrap their contents in a single top-level directory whose name carries the
     * version, so the entry is located rather than assumed. A macOS JDK bundle puts it under
     * {@code Contents/Home/bin}, which is why the search is by suffix and not by depth.
     */
    private static Path locate(Path extracted) throws InstallRefusedException {
        Path direct = extracted.resolve("bin").resolve(executableName());
        if (Files.isRegularFile(direct)) {
            return extracted;
        }
        try (Stream<Path> children = Files.list(extracted)) {
            for (Path child : children.sorted().toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (Files.isRegularFile(child.resolve("bin").resolve(executableName()))) {
                    return child;
                }
                Path macHome = child.resolve("Contents").resolve("Home");
                if (Files.isRegularFile(macHome.resolve("bin").resolve(executableName()))) {
                    return macHome;
                }
            }
        } catch (IOException e) {
            // Reported below as a refusal; the caller's outcome does not depend on the cause.
        }
        throw new InstallRefusedException("the archive does not contain bin/"
                + executableName() + ", so it is not a Java runtime");
    }

    private static String executableName() {
        return isWindows() ? "java.exe" : "java";
    }

    /**
     * Runs the extracted {@code java -version} and refuses anything below the floor.
     *
     * <p>A corrupt or wrong-architecture binary fails here rather than after activation, which is
     * the requirement: a runtime that cannot report its own version must never become the one the
     * resolver hands to every later command.
     */
    private void validate(Path java, int minimumMajor) throws InstallRefusedException {
        // A JDK archive records 0755 on its launcher, and SafeArchive restores it. Not every tool
        // that builds a ZIP records a Unix mode at all, though, and an archive with no mode
        // arrives as a plain file. The installer is about to execute this exact path, so it makes
        // that possible rather than refusing an otherwise usable runtime for a metadata gap.
        // Only the owner's execute bit is added; nothing is ever made setuid.
        if (!Files.isExecutable(java) && !java.toFile().setExecutable(true, true)) {
            throw new InstallRefusedException("the extracted " + java.getFileName()
                    + " is not executable and the permission could not be changed");
        }
        OptionalInt major = probe.majorVersion(java.toString());
        if (major.isEmpty()) {
            throw new InstallRefusedException("the extracted java did not report a version;"
                    + " the archive is corrupt or built for another platform");
        }
        if (major.getAsInt() < minimumMajor) {
            throw new InstallRefusedException("the archive contains Java " + major.getAsInt()
                    + ", below the required " + minimumMajor);
        }
        if (compatibilityJar != null) {
            // ADR-0048 D9. A version number says what the JVM claims, not what it can run.
            RuntimeCompatibility.Verdict verdict;
            try {
                verdict = new RuntimeCompatibility(compatibilityJar).validate(java);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InstallRefusedException("the compatibility check was interrupted");
            } catch (IOException e) {
                throw new InstallRefusedException("the compatibility check could not run: "
                        + e.getMessage());
            }
            if (!verdict.compatible()) {
                throw new InstallRefusedException("the archive contains a Java " + major.getAsInt()
                        + " that cannot run Oqthara: " + verdict.summary());
            }
        }
    }

    /**
     * Moves the validated runtime into place with one rename.
     *
     * <p>No copy fallback. A filesystem that will not rename atomically is reported, because the
     * alternative is a window in which a partial runtime sits at the live path.
     */
    private static void activateAtomically(Path extracted, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(extracted, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("the filesystem holding " + target.getParent()
                    + " does not support an atomic rename, so a runtime cannot be installed"
                    + " without a window in which it may be partially present", e);
        } catch (FileSystemException e) {
            throw new IOException("could not activate the runtime at " + target + ": "
                    + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** True when case-insensitive path comparison and {@code .exe} suffixes apply. */
    static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /** Diagnostic text naming where the runtime would be installed. */
    String describe() {
        return "managed runtime home " + home + ", activated at " + destination();
    }
}
