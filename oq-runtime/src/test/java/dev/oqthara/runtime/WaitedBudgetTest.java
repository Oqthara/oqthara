package dev.oqthara.runtime;

import org.junit.jupiter.api.BeforeEach;
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
    void resetStartsTheBudgetOver() {
        assertThrows(OqError.class, () -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS + 1, span));
        WaitedBudget.reset();
        assertDoesNotThrow(() -> WaitedBudget.charge(WaitedBudget.MAX_MILLIS, span));
    }
}
