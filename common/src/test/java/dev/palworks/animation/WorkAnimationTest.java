package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** How a step with an animation is timed: the worker settles, then it plays, landing at the end. */
class WorkAnimationTest {

    private static final WorkAnimation EMBER = WorkAnimation.move("ember", 35, 0.125);

    @Test
    void aStepIsLongEnoughToSettleAndForTheAnimationToLand() {
        assertEquals(WorkAnimation.SETTLE_TICKS + 35, EMBER.stepTicks(0));
        assertEquals(WorkAnimation.SETTLE_TICKS + 35, EMBER.stepTicks(20), "a shorter step is lengthened");
        assertEquals(60, EMBER.stepTicks(60), "a longer step keeps its length");
    }

    @Test
    void itPlaysSoItLandsOnTheStepsLastTick() {
        int ticks = EMBER.stepTicks(60);
        assertEquals(60 - 35, EMBER.playAt(ticks), "lands as the step's effect happens");
        assertEquals(WorkAnimation.SETTLE_TICKS, EMBER.playAt(EMBER.stepTicks(0)), "right after settling");
    }

    @Test
    void itNeverPlaysBeforeTheWorkerHasSettled() {
        // A step too short for it (built by hand rather than with stepTicks) still waits to settle
        assertEquals(WorkAnimation.SETTLE_TICKS, EMBER.playAt(10));
    }

    @Test
    void itsMoveIsACobblemonActionEffect() {
        assertEquals("cobblemon:ember", EMBER.moveEffect().toString());
    }

    @Test
    void anImpactCannotBeBeforeItStarts() {
        assertThrows(IllegalArgumentException.class, () -> WorkAnimation.move("ember", -1, 0));
    }

    @Test
    void afterItLandsItPlaysOutTheRestOfItsBodyAnimation() {
        // Ember lands 35 ticks in; a move's body animation lasts 3s
        assertEquals(WorkAnimation.MOVE_DURATION_TICKS, EMBER.durationTicks());
        assertEquals(WorkAnimation.MOVE_DURATION_TICKS - 35, EMBER.playOutTicks());
    }

    @Test
    void itCannotEndBeforeItLands() {
        assertThrows(IllegalArgumentException.class, () -> new WorkAnimation(EMBER.moveEffect(), 35, 30, EMBER.aimY()));
    }
}
