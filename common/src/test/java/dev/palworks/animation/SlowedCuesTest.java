package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When a slowed worker shows what's slowing it: while it waits at its job for its move. */
class SlowedCuesTest {
    private static final int SETTLED = WorkAnimation.SETTLE_TICKS;

    @Test
    void onceItsSettledThenEveryFewSeconds() {
        assertFalse(SlowedCues.dueAt(0, 200), "not on its first tick, before it's settled");
        assertTrue(SlowedCues.dueAt(SETTLED, 200), "as soon as it's settled");
        assertFalse(SlowedCues.dueAt(SETTLED + 1, 200));
        assertTrue(SlowedCues.dueAt(SETTLED + SlowedCues.EVERY_TICKS, 200));
        assertTrue(SlowedCues.dueAt(SETTLED + 2 * SlowedCues.EVERY_TICKS, 200));
    }

    @Test
    void notOnceItsMoveHasStarted() {
        assertFalse(SlowedCues.dueAt(SETTLED + SlowedCues.EVERY_TICKS, SETTLED + SlowedCues.EVERY_TICKS));
        assertFalse(SlowedCues.dueAt(SETTLED, SETTLED), "a move that starts as soon as it's settled: no wait");
    }
}
