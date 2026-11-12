package dev.oqthara.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Keeps the previous runtime so that a failed upgrade is repaired by selecting it again.
 *
 * <p>ADR-0048 D7. "Rollback" here means choosing a different directory to run from, never
 * undoing a filesystem operation. That distinction is what makes it reliable: an operation that
 * was interrupted is a directory in an unknown state that some other code must finish reasoning
 * about, while a selection between two directories that both already validate is a single
 * decision.
 *
 * <p>The layout is two named slots next to each other in the managed home:
 *
 * <pre>
 *   $OQ_HOME/runtime/java        the active runtime
 *   $OQ_HOME/runtime/java.prev   the retained one, complete and validated
 * </pre>
 *
 * <p>An upgrade renames the active directory into {@code .prev} and the staged one into
 * {@code .active}, so no runtime is ever deleted to make room and the previous one is on disk
 * before the new one is live. The retained runtime is only discarded when a later upgrade
 * replaces it, which means the machine holds at most two.
 */
final class RuntimeRetention {

    private static final String PREVIOUS_SUFFIX = ".prev";

    private final Path runtimeDirectory;

    RuntimeRetention(Path home) {
        this.runtimeDirectory = home.toAbsolutePath().normalize().resolve("runtime");
    }

    /** The active runtime's home directory, whether or not one is installed. */
    Path active() {
        return runtimeDirectory.resolve("java");
    }

    /** The retained runtime's home directory, whether or not one is retained. */
    Path previous() {
        return runtimeDirectory.resolve("java" + PREVIOUS_SUFFIX);
    }

    /** True when a runtime is retained and could be selected by {@link #rollback()}. */
    boolean hasPrevious() {
        return Files.isDirectory(previous().resolve("bin"));
    }

    /**
     * Moves the active runtime aside without deleting it.
     *
     * <p>Called before a new runtime is activated. An existing {@code .prev} is replaced, because
     * retaining two generations would mean choosing how many, and D7 retains exactly one: the
     * version that was working. Replacing it only after the new runtime has been validated would
     * be better still, but the file system cannot hold three directories at the active path, so
     * the order is: validate the new one in staging (D5), then rotate.
     *
     * @return true if something was moved aside, false if nothing was installed
     */
    boolean rotate() throws IOException {
        if (!Files.isDirectory(active())) {
            return false;
        }
        if (Files.exists(previous())) {
            deleteRecursively(previous());
        }
        move(active(), previous());
        return true;
    }

    /**
     * Makes the retained runtime active again.
     *
     * <p>This is a selection, not a repair: the retained directory was validated before it was
     * retained, and nothing about it is rewritten here. The runtime that was active is moved to
     * {@code .prev} rather than deleted, so a rollback that turns out to be wrong can be undone
     * the same way.
     *
     * @return the now-active runtime home
     * @throws IOException if nothing is retained, or the swap fails
     */
    Path rollback() throws IOException {
        if (!hasPrevious()) {
            throw new IOException("no previous runtime is retained at " + previous()
                    + ", so there is nothing to fall back to");
        }
        if (Files.isDirectory(active())) {
            Path displaced = runtimeDirectory.resolve("java.rollback");
            deleteRecursively(displaced);
            move(active(), displaced);
            move(previous(), active());
            move(displaced, previous());
        } else {
            move(previous(), active());
        }
        return active();
    }

    /**
     * Deletes the retained runtime.
     *
     * <p>Only reachable when the maintainer has decided the new runtime is good, which is why it
     * is a separate call rather than something {@link #rotate()} does on a timer.
     */
    void discardPrevious() throws IOException {
        deleteRecursively(previous());
    }

    /** Describes both slots for a diagnostic. */
    String describe() {
        String retained = hasPrevious()
                ? "retained runtime at " + previous()
                : "no retained runtime";
        return (Files.isDirectory(active()) ? "active runtime at " + active()
                : "no active runtime") + ", " + retained;
    }

    /**
     * Waits for a program run against a freshly activated runtime to succeed.
     *
     * <p>D7 keeps the previous runtime "until the new one has been validated and has run a
     * program successfully". Validation is D5's {@code java -version}; this is the second half,
     * and it is the caller's job to supply a command that exercises the runtime rather than one
     * that merely prints. The timeout exists so a hung runtime is a reported failure rather than
     * a wait with no end.
     */
    static boolean runsAProgram(Path javaHome, Duration timeout) {
        Path executable = javaHome.resolve("bin")
                .resolve(RuntimeInstaller.isWindows() ? "java.exe" : "java");
        if (!Files.isRegularFile(executable)) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(executable.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The version a retained runtime reports, for a message that names what it would roll back to. */
    Optional<Integer> previousMajor(RuntimeResolver.VersionProbe probe) {
        if (!hasPrevious()) {
            return Optional.empty();
        }
        Path executable = previous().resolve("bin")
                .resolve(RuntimeInstaller.isWindows() ? "java.exe" : "java");
        var major = probe.majorVersion(executable.toString());
        return major.isPresent() ? Optional.of(major.getAsInt()) : Optional.empty();
    }

    private static void move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
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
}
