package dev.oqthara.runtime;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintStreamSharingTest {
    // R-029 fixed line fusion by making compiled `print` issue one `println`. That only holds if
    // every caller takes the same lock, which means one shared PrintStream: two streams wrapping
    // the same System.out hold separate locks and would restore the defect without any test
    // noticing. `out()` used to be an unsynchronised check-then-act, so racing callers really did
    // create several streams. This pins the singleton under contention.

    @Test
    void concurrentFirstUseCreatesExactlyOneStream() throws Exception {
        Field field = Arithmetic.class.getDeclaredField("UTF8_OUT");
        field.setAccessible(true);
        Method out = Arithmetic.class.getDeclaredMethod("out");
        out.setAccessible(true);

        for (int trial = 0; trial < 50; trial++) {
            field.set(null, null);
            Set<PrintStream> seen = ConcurrentHashMap.newKeySet();
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            Thread[] racers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                racers[i] = new Thread(() -> {
                    try {
                        start.await();
                        seen.add((PrintStream) out.invoke(null));
                    } catch (Exception ignored) {
                        // A throw shows up as a missing stream in the assertion below.
                    }
                });
            }
            for (Thread racer : racers) {
                racer.start();
            }
            start.countDown();
            for (Thread racer : racers) {
                racer.join();
            }
            assertEquals(1, seen.size(), "trial " + trial + " produced " + seen.size()
                    + " streams; eight racers must share one so they share one lock");
        }
    }

    @Test
    void everyCallReturnsTheSameStream() throws Exception {
        Method out = Arithmetic.class.getDeclaredMethod("out");
        out.setAccessible(true);
        PrintStream first = (PrintStream) out.invoke(null);
        for (int i = 0; i < 5; i++) {
            assertTrue(first == out.invoke(null), "out() must not hand out fresh streams");
        }
    }
}
