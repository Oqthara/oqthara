package dev.oqthara.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers ADR-0048 D6: concurrent installers serialise, and a killed holder does not block. */
class InstallLockTest {

    @Test
    void acquiresAndCreatesTheLockFile(@TempDir Path temp) throws Exception {
        try (InstallLock lock = InstallLock.acquire(temp, Duration.ofSeconds(5))) {
            assertTrue(Files.isRegularFile(lock.file()));
        }
    }

    @Test
    void serialisesTwoThreadsOfOneProcess(@TempDir Path temp) throws Exception {
        try (InstallLock first = InstallLock.acquire(temp, Duration.ofSeconds(5))) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread second = new Thread(() -> {
                started.countDown();
                // The timeout is short on purpose: if the semaphore were absent this returns
                // immediately with IllegalStateException, not after the release below.
                try (InstallLock stolen = InstallLock.acquire(temp, Duration.ofMillis(150))) {
                    failure.set(new AssertionError(
                            "the second thread took a held lock: " + stolen.file()));
                } catch (IllegalStateException expected) {
                    // Correct: it waited out its timeout while the first lock was held.
                } catch (Throwable other) {
                    failure.set(other);
                } finally {
                    done.countDown();
                }
            });
            second.start();
            started.await(1, TimeUnit.SECONDS);
            assertTrue(done.await(5, TimeUnit.SECONDS), "the second thread never finished");
            second.join(5_000);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            assertTrue(first.file().toFile().exists(), "the first lock should still be held");
        }
    }

    @Test
    void aLaterHolderSucceedsAfterTheFirstReleases(@TempDir Path temp) throws Exception {
        try (InstallLock first = InstallLock.acquire(temp, Duration.ofSeconds(5))) {
            assertEquals(temp.resolve("runtime.lock").toAbsolutePath(), first.file());
        }
        try (InstallLock second = InstallLock.acquire(temp, Duration.ofSeconds(5))) {
            assertTrue(second.file().toFile().exists());
        }
    }

    @Test
    void closingTwiceIsHarmless(@TempDir Path temp) throws Exception {
        InstallLock lock = InstallLock.acquire(temp, Duration.ofSeconds(5));
        lock.close();
        lock.close();
    }

    @Test
    void aStaleLookingLockFileDoesNotBlock(@TempDir Path temp) throws Exception {
        // A note left by a process that is long gone. Nothing reads it to decide whether to
        // wait, so its presence must change nothing whatsoever.
        Path file = temp.resolve("runtime.lock");
        Files.writeString(temp.resolve("runtime.lock.holder"),
                "pid=999999999 since=1999-01-01T00:00:00Z\n", StandardCharsets.UTF_8);
        try (InstallLock lock = InstallLock.acquire(temp, Duration.ofSeconds(2))) {
            assertEquals(file.toAbsolutePath(), lock.file());
        }
    }

    @Test
    void createsTheHomeDirectoryWhenAbsent(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("nested").resolve("home");
        assertFalse(Files.exists(home));
        try (InstallLock lock = InstallLock.acquire(home, Duration.ofSeconds(5))) {
            assertTrue(Files.isDirectory(home));
            assertTrue(Files.isRegularFile(lock.file()));
        }
    }

    @Test
    void namesTheHolderInTheTimeoutMessage(@TempDir Path temp) throws Exception {
        try (InstallLock first = InstallLock.acquire(temp, Duration.ofSeconds(5))) {
            long pid = ProcessHandle.current().pid();
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> InstallLock.acquire(temp, Duration.ofMillis(120)));
            assertTrue(refused.getMessage().contains("pid=" + pid),
                    "message should name the holder: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("120"),
                    "message should name the timeout: " + refused.getMessage());
            assertTrue(first.file().toFile().exists());
        }
    }

    /**
     * The decisive test. A killed process releases the lock because the operating system owns it,
     * so the next installer proceeds without waiting for a timeout or cleaning anything up.
     */
    @Test
    @Timeout(120)
    void aKilledHolderDoesNotBlockTheNextInstaller(@TempDir Path temp) throws Exception {
        Process child = spawnHolder(temp);
        try {
            String line = readLine(child);
            assertTrue(line.startsWith("ACQUIRED "),
                    "child did not report acquiring the lock: " + line);

            // While the child lives, the lock is genuinely held: this process cannot take it.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> InstallLock.acquire(temp, Duration.ofMillis(250)));
            assertTrue(refused.getMessage().contains("another installation is in progress"),
                    refused.getMessage());

            // The holder is a different process, so this can only be known if the note is
            // readable while the lock is held. Windows takes mandatory locks and refuses that
            // read of the lock file, so the note must not live there.
            assertTrue(refused.getMessage().contains("pid=" + child.pid()),
                    "message should name the holding process: " + refused.getMessage());

            child.destroyForcibly();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child did not die");

            // No cleanup ran in the child. The lock must still be free.
            try (InstallLock recovered = InstallLock.acquire(temp, Duration.ofSeconds(10))) {
                assertTrue(recovered.file().toFile().exists());
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static Process spawnHolder(Path home) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        LockHolderMain.class.getName(), home.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static String readLine(Process child) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) {
            throw new AssertionError("the child process printed nothing");
        }
        return line;
    }
}
