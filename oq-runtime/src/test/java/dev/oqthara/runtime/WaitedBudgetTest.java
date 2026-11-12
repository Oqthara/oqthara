package dev.oqthara.runtime;

import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaitedBudgetTest {
    private final Span span = Span.synthetic();

    @BeforeEach
    void setUp() {
        WaitedBudget.reset();
    }

    @Test
    void waitingUpToTheLimitIsAllowed() {
        assertDoesNotThrow(() -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS, span));
    }

    @Test
    void waitingPastTheLimitIsRefused() {
        WaitedBudget.charge(WaitedBudget.MAX_MILLIS, span);
        OqError error = assertThrows(OqError.class, () -> WaitedBudget.charge(1, span));
        assertEquals("the program waited for too long", error.getMessage());
    }

    @Test
    void theLimitIsOnTheTotalNotOneCall() {
        // The per-call cap in Stdlib bounds one sleep at ten seconds. Without a total, a loop
        // of ten-second sleeps could keep a program waiting forever.
        for (int i = 0; i < 6; i++) {
            WaitedBudget.charge(10_000, span);
        }
        assertThrows(OqError.class, () -> WaitedBudget.charge(10_000, span));
    }

    @Test
    void aFinishedTaskChargesAlmostNothingWhateverItsTimeout() {
        // Found by an example: eight awaits of instant work with a 10-second limit reported
        // "waited for too long" in microseconds, because the timeout itself was billed as
        // though the program had spent it. The timeout is a ceiling, not a cost.
        //
        // The ceiling must therefore bill nothing worth speaking of, and the bound asserted is
        // five seconds of slack across twenty awaits rather than zero. Zero was the original
        // claim and it was wrong: a single millisecond of scheduler noise in any one of the
        // twenty `get()` calls spent the whole remaining budget, so the test passed on an idle
        // laptop and failed under CI load. Five seconds is far above the real cost of twenty
        // already-completed futures and far below the 200 seconds that billing the ceiling
        // would charge, so it separates the two behaviours without measuring the machine.
        WaitedBudget.reset();
        CompletableFuture<Object> done = CompletableFuture.completedFuture(1);
        for (int i = 0; i < 20; i++) {
            assertEquals(1, new OqTask(done).awaitWithin(10_000, span));
        }
        assertDoesNotThrow(() -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS - 5_000, span));
    }

    @Test
    void aBoundedAwaitIsBilledForTheTimeItWaited() {
        // A 10-second ceiling on a task that takes milliseconds must charge milliseconds. A
        // full-limit charge afterwards can only succeed if the real wait was billed.
        WaitedBudget.reset();
        OqTask slow = slowTask(200);
        assertEquals(1, slow.awaitWithin(10_000, span));
        assertThrows(OqError.class, () -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS, span));
    }

    @Test
    void anUnboundedAwaitIsBilledForTheTimeItWaited() {
        // The same example: await(task) with no limit charged nothing at all, so a loop could
        // wait forever by never passing a timeout.
        WaitedBudget.reset();
        assertEquals(1, slowTask(200).await(span));
        assertThrows(OqError.class, () -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS, span));
    }

    private static OqTask slowTask(long millis) {
        return new OqTask(CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }));
    }

    @Test
    void resetStartsTheBudgetOver() {
        assertThrows(OqError.class, () -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS + 1, span));
        WaitedBudget.reset();
        assertDoesNotThrow(() -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS, span));
    }
}
