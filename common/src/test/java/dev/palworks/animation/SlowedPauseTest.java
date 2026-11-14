package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.worker.WorkerState;
import org.junit.jupiter.api.Test;

/** A slowed worker saves up the time its moves save it, and pauses for it before a move. */
class SlowedPauseTest {
    private static final int PAUSE = SlowedPause.PAUSE_TICKS;

    /** Ticks a worker at {@code pace} holds before its next move. */
    private static int pauseBeforeAMove(WorkerState state, double pace) {
        int held = 0;
        while (SlowedPause.holds(state, pace)) {
            held++;
        }
        return held;
    }

    @Test
    void aSlowedWorkerPausesStraightAwayThenWorksItOff() {
        WorkerState state = new WorkerState();
        assertEquals(PAUSE, pauseBeforeAMove(state, 0.75), "its first move: it pauses first");
        // A 38-tick step at three quarters would take 51: 13 saved a move, so 7 pay for the pause
        for (int move = 1; move <= 7; move++) {
            SlowedPause.owe(state, 38, 0.75);
            int held = pauseBeforeAMove(state, 0.75);
            assertEquals(move == 7 ? PAUSE : 0, held, "before move " + (move + 1));
        }
    }

    @Test
    void aVerySlowWorkerPausesAgainAndAgain() {
        WorkerState state = new WorkerState();
        pauseBeforeAMove(state, 0.1); // paused ahead: it's owed 90
        SlowedPause.owe(state, 38, 0.1); // 380 for a 38-tick step: 342 saved, so 252 owed
        assertEquals(3 * PAUSE, pauseBeforeAMove(state, 0.1), "three pauses running");
    }

    @Test
    void atFullPaceNothingsOwedOrPausedFor() {
        WorkerState state = new WorkerState();
        SlowedPause.owe(state, 38, 1.0);
        assertFalse(SlowedPause.holds(state, 1.0));
        pauseBeforeAMove(state, 0.5);
        assertTrue(state.getSlowedTicksOwed() < 0, "paused ahead");
        assertFalse(SlowedPause.holds(state, 1.0), "fed again");
        assertEquals(0, state.getSlowedTicksOwed(), "and what it paused ahead for is forgotten");
    }

    @Test
    void aPauseEndsIfItsStepStartsOver() {
        WorkerState state = new WorkerState();
        assertTrue(SlowedPause.holds(state, 0.5));
        assertTrue(state.getPauseTicksLeft() > 0);
        SlowedPause.update(state); // its step's time is 0: it started over
        assertEquals(0, state.getPauseTicksLeft());
    }
}
