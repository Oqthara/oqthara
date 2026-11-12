package dev.oqthara.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A cross-process lock that serialises installation into a managed runtime home.
 *
 * <p>The authority is an operating-system advisory lock ({@link FileLock}), not a file's
 * presence and not a recorded process id. That choice is what makes a killed process
 * harmless: the operating system releases the lock when the process exits, whether it
 * exits cleanly or is killed outright, so no reader ever waits on a lock whose owner is
 * gone. A lock <em>file</em> that outlives its holder is therefore expected, and is not
 * stale — the file is the thing being locked, not the claim of a lock.
 *
 * <p>Two levels are needed because {@link FileLock} is held by a JVM and not by a thread.
 * A second thread in the same process asking for the same region does not block; it throws
 * {@link OverlappingFileLockException}. The per-path {@link Semaphore} below serialises
 * threads inside this process, and the file lock serialises processes.
 *
 * <p>The lock file's contents name the holding process. That is written for a human reading
 * a failure message, and nothing waits on it: a wrong or absent pid changes no decision.
 * Deleting the file on release would also be wrong on Windows, where an open lock prevents
 * deletion and a racing delete fails intermittently.
 *
 * <p><b>Limit.</b> Advisory locking is unreliable over some network filesystems, NFS in
 * particular. A managed home on such a mount gets no guarantee from this class.
 */
final class InstallLock implements AutoCloseable {

    /** One semaphore per canonical lock path, so threads in this process wait rather than throw. */
    private static final Map<Path, Semaphore> IN_PROCESS = new ConcurrentHashMap<>();


    private static final long POLL_MILLIS = 25;

    private final Path file;
    private final Semaphore inProcess;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private InstallLock(Path file, Semaphore inProcess, FileChannel channel, FileLock lock) {
        this.file = file;
        this.inProcess = inProcess;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Blocks until the lock is held or {@code timeout} elapses.
     *
     * @throws IOException if the lock file cannot be created or the lock cannot be polled
     * @throws InterruptedException if the thread is interrupted while waiting; the lock is
     *     not left held in that case
     * @throws IllegalStateException if the lock is not acquired within {@code timeout}
     */
    static InstallLock acquire(Path home, Duration timeout) throws IOException, InterruptedException {
        Path file = home.resolve("runtime.lock").toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());

        long deadline = System.nanoTime() + timeout.toNanos();
        Semaphore inProcess = IN_PROCESS.computeIfAbsent(file, key -> new Semaphore(1));
        if (!inProcess.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
            throw new IllegalStateException(describeTimeout(file, timeout));
        }

        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = poll(channel, file, deadline, timeout);
            InstallLock held = new InstallLock(file, inProcess, channel, lock);
            held.announceHolder();
            return held;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (channel != null) {
                closeQuietly(channel);
            }
            inProcess.release();
            throw e;
        }
    }

    /** Polls {@code tryLock} until it succeeds or the deadline passes. */
    private static FileLock poll(FileChannel channel, Path file, long deadline, Duration timeout)
            throws IOException, InterruptedException {
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // The per-path semaphore should make this unreachable. Reaching it means the
                // in-process registry and the channel state disagree, which is a bug here and
                // not a condition a caller can act on, so it is reported rather than retried.
                throw new IOException("the installation lock is held by another thread of this"
                        + " process but not registered: " + file, e);
            }
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(describeTimeout(file, timeout));
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    /**
     * Explains a timeout, naming the holder when this platform lets it be known.
     *
     * <p>The holder is named from the sibling note file, which any process can read on any
     * platform. When no note is there the message says only that another installation is in
     * progress, which is true and is all that is known.
     */
    private static String describeTimeout(Path file, Duration timeout) {
        String holder = readHolderQuietly(file);
        String since = holder == null ? "" : " (held by " + holder + ")";
        return "another installation is in progress" + since + "; gave up after "
                + timeout.toMillis() + " ms waiting for " + file;
    }

    /**
     * Records the holding process for a human reading a failure message. Never parsed to make
     * a decision, so an absent or stale value changes nothing.
     *
     * <p>Written to a sibling file rather than into the lock file, because the lock file is
     * exactly the file another process cannot read. Windows locks are mandatory, so a holder's
     * exclusive lock refuses a second handle's read and the holder would be invisible on the
     * platform where a stuck installer most needs explaining. The sibling is never locked.
     */
    private void announceHolder() {
        String note = "pid=" + ProcessHandle.current().pid()
                + " since=" + java.time.Instant.now() + "\n";
        try {
            Files.writeString(holderNote(file), note, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Diagnostics only. Holding the lock is what matters, and it is already held.
        }
    }

    /** Where {@link #announceHolder()} leaves its note. Deliberately not the lock file. */
    private static Path holderNote(Path file) {
        return file.resolveSibling(file.getFileName() + ".holder");
    }

    private static String readHolderQuietly(Path file) {
        try {
            String text = Files.readString(holderNote(file), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    /** The lock file this instance guards. */
    Path file() {
        return file;
    }

    /** Releases the lock. Calling this twice is harmless. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Closing the channel below releases the lock regardless.
        } finally {
            closeQuietly(channel);
            inProcess.release();
        }
        try {
            Files.deleteIfExists(holderNote(file));
        } catch (IOException ignored) {
            // A stale note names a pid that is no longer holding anything. Harmless.
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing further can be done, and the caller's outcome is already decided.
        }
    }
}
