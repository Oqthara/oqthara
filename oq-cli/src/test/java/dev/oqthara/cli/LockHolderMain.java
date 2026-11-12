package dev.oqthara.cli;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Child process for {@link InstallLockTest}: takes the lock, says so, then waits to be killed.
 *
 * <p>Exists so a test can prove that the operating system releases the lock when a process dies
 * without running any cleanup of its own. A shutdown hook in the test process could not prove
 * that, because the hook would be the thing doing the releasing.
 */
public final class LockHolderMain {

    private LockHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        try (InstallLock lock = InstallLock.acquire(home, Duration.ofSeconds(10))) {
            System.out.println("ACQUIRED " + lock.file());
            System.out.flush();
            Thread.sleep(Duration.ofMinutes(10));
        }
    }
}
