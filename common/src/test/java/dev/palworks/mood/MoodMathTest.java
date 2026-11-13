package dev.palworks.mood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.hunger.HungerMath;
import org.junit.jupiter.api.Test;

/** How a worker's mood goes down, back up, and when it strikes. */
class MoodMathTest {
    private static final double DELTA = 1e-9;
    private static final int DAY = HungerMath.TICKS_PER_DAY;

    private static double perDay(boolean hungry, boolean tired, boolean atWork) {
        return MoodMath.changePerTick(hungry, tired, atWork, 50, 25, 100) * DAY;
    }

    @Test
    void fedAndRestedItComesBackUpWhenNotAtWork() {
        assertEquals(100, perDay(false, false, false), DELTA);
        // At work, it doesn't (the work itself wears it down: see workDrain)
        assertEquals(0, perDay(false, false, true), DELTA);
    }

    @Test
    void hungryOrTiredItGoesDownAndDoesntComeBack() {
        assertEquals(-50, perDay(true, false, false), DELTA);
        assertEquals(-25, perDay(false, true, false), DELTA);
        assertEquals(-75, perDay(true, true, false), DELTA);
        assertEquals(-75, perDay(true, true, true), DELTA);
    }

    @Test
    void workWearsItDownByTheUnit() {
        assertEquals(1.5, MoodMath.workDrain(3, 2), DELTA);
        assertEquals(0, MoodMath.workDrain(0, 2), DELTA);
    }

    @Test
    void aStrikeStartsAtNothingAndLastsUntilItsBackUp() {
        assertTrue(MoodMath.striking(false, 0, 30));
        assertFalse(MoodMath.striking(false, 0.5, 30));
        // On strike, coming back up: not yet at 29.9, over at 30
        assertTrue(MoodMath.striking(true, 29.9, 30));
        assertFalse(MoodMath.striking(true, 30, 30));
        // Not on strike, a mood under 30 doesn't start one
        assertFalse(MoodMath.striking(false, 10, 30));
    }

    @Test
    void unhappyBelowTheLine() {
        assertTrue(MoodMath.unhappy(49.9, 50));
        assertFalse(MoodMath.unhappy(50, 50));
    }
}
