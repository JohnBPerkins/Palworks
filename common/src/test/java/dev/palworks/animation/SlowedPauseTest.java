package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.worker.WorkerState;
import org.junit.jupiter.api.Test;

/** A slowed worker saves up the time its moves save it, and pauses for it before a move, in one go. */
class SlowedPauseTest {
    private static final int SHORTEST = SlowedPause.SHORTEST_PAUSE_TICKS;

    /** Ticks a worker at {@code pace} holds before its next move (it's starting a new step). */
    private static int pauseBeforeAMove(WorkerState state, double pace) {
        SlowedPause.update(state); // a new step: its time is 0
        int held = 0;
        while (SlowedPause.holds(state, pace)) {
            held++;
        }
        return held;
    }

    @Test
    void aSlowedWorkerPausesStraightAwayThenWorksItOff() {
        WorkerState state = new WorkerState();
        assertEquals(SHORTEST, pauseBeforeAMove(state, 0.75), "its first move: it pauses first");
        // A 38-tick step at three quarters would take 51: 13 saved a move, so 7 pay for the pause
        for (int move = 1; move <= 7; move++) {
            SlowedPause.owe(state, 38, 0.75);
            int held = pauseBeforeAMove(state, 0.75);
            assertEquals(move == 7 ? SHORTEST : 0, held, "before move " + (move + 1));
        }
    }

    @Test
    void aVerySlowWorkerPausesForAllOfItInOneGo() {
        WorkerState state = new WorkerState();
        pauseBeforeAMove(state, 0.1); // paused ahead: it's owed 90
        SlowedPause.owe(state, 38, 0.1); // 380 for a 38-tick step: 342 saved, so 252 owed
        assertEquals(252, pauseBeforeAMove(state, 0.1), "one pause for all of it");
        assertEquals(0, state.getSlowedTicksOwed());
        SlowedPause.owe(state, 38, 0.1);
        assertEquals(342, pauseBeforeAMove(state, 0.1), "and so on: one pause a move");
    }

    @Test
    void zsRiseAllThroughAPauseButAreGoneByItsMove() {
        assertTrue(SlowedPause.zsDue(0, SHORTEST), "at the start of the shortest pause");
        assertFalse(SlowedPause.zsDue(SlowedPause.ZS_EVERY_TICKS, SHORTEST - SlowedPause.ZS_EVERY_TICKS));
        // A 252-tick pause: a round every 2s, the last one with time to fade before the move
        int rounds = 0;
        for (int into = 0; into < 252; into++) {
            if (SlowedPause.zsDue(into, 252 - into)) {
                rounds++;
                assertTrue(252 - into >= SHORTEST, "gone by its move: " + into);
            }
        }
        assertEquals(5, rounds);
    }

    @Test
    void atFullPaceNothingsOwedOrPausedFor() {
        WorkerState state = new WorkerState();
        SlowedPause.owe(state, 38, 1.0);
        assertFalse(SlowedPause.holds(state, 1.0));
        pauseBeforeAMove(state, 0.5);
        assertTrue(state.getSlowedTicksOwed() < 0, "paused ahead");
        SlowedPause.update(state);
        assertFalse(SlowedPause.holds(state, 1.0), "fed again");
        assertEquals(0, state.getSlowedTicksOwed(), "and what it paused ahead for is forgotten");
    }

    @Test
    void aPauseCutShortIsOwedAgain() {
        WorkerState state = new WorkerState();
        for (int tick = 0; tick < 30; tick++) {
            assertTrue(SlowedPause.holds(state, 0.5));
        }
        SlowedPause.update(state); // its step's time is 0: it started over
        assertEquals(0, state.getPauseTicksLeft());
        assertEquals(-30, state.getSlowedTicksOwed(), "only the 30 ticks it paused count");
        assertEquals(0, pauseBeforeAMove(state, 0.5), "it's still paused ahead, by those 30");
    }
}
