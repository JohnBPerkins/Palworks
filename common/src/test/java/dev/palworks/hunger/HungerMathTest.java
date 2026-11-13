package dev.palworks.hunger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The arithmetic of worker hunger: trough caps, drain rates, tree sizes and rests. */
class HungerMathTest {

    @Test
    void troughsFillHalfwayRoundedUp() {
        assertEquals(1, HungerMath.troughCap(2, 0.5)); // Sunkern, the lightest
        assertEquals(2, HungerMath.troughCap(4, 0.5));
        assertEquals(3, HungerMath.troughCap(5, 0.5));
        assertEquals(4, HungerMath.troughCap(7, 0.5)); // Snorlax, the heaviest
    }

    @Test
    void troughsCanBeTurnedOffOrFillAllTheWay() {
        assertEquals(0, HungerMath.troughCap(4, 0));
        assertEquals(4, HungerMath.troughCap(4, 1));
        // Never more than the max, and at least a point if it eats at all
        assertEquals(1, HungerMath.troughCap(4, 0.01));
    }

    @Test
    void aQuarterOfTheBarADay() {
        // Max 4 at a quarter a day: one point a day
        assertEquals(1.0, HungerMath.idleDrainPerTick(4, 0.25) * HungerMath.TICKS_PER_DAY, 1e-9);
        assertEquals(1.75, HungerMath.idleDrainPerTick(7, 0.25) * HungerMath.TICKS_PER_DAY, 1e-9);
        assertEquals(0, HungerMath.idleDrainPerTick(7, 0));
    }

    @Test
    void workDrainsByTheUnit() {
        assertEquals(0.05, HungerMath.workDrain(1, 20), 1e-9);
        assertEquals(1.0, HungerMath.workDrain(20, 20), 1e-9);
        assertEquals(0, HungerMath.workDrain(0, 20));
    }

    @Test
    void treesCountBySizeButOnlySoFar() {
        assertEquals(0, HungerMath.treeUnits(0));
        assertEquals(1, HungerMath.treeUnits(5)); // an oak
        assertEquals(1, HungerMath.treeUnits(8));
        assertEquals(2, HungerMath.treeUnits(9));
        assertEquals(4, HungerMath.treeUnits(30)); // a 2x2 spruce
        assertEquals(5, HungerMath.treeUnits(400)); // a giant jungle tree: no more than 5
    }

    @Test
    void wholePointsComeOffAndTheRestIsOwed() {
        HungerMath.Drained drained = HungerMath.drain(0.9, 0.25, 3);
        assertEquals(1, drained.points());
        assertEquals(0.15, drained.owed(), 1e-9);

        drained = HungerMath.drain(0, 0.5, 3);
        assertEquals(0, drained.points());
        assertEquals(0.5, drained.owed(), 1e-9);

        drained = HungerMath.drain(0.5, 2.0, 3);
        assertEquals(2, drained.points());
        assertEquals(0.5, drained.owed(), 1e-9);
    }

    @Test
    void anEmptyWorkerBuildsUpNoDebt() {
        // At 0 there's nothing to take, and nothing is kept owed for later
        HungerMath.Drained drained = HungerMath.drain(0.5, 3.0, 0);
        assertEquals(0, drained.points());
        assertEquals(0, drained.owed());
        // Taking its last point: the leftover is dropped too
        drained = HungerMath.drain(0.5, 1.75, 1);
        assertEquals(1, drained.points());
        assertEquals(0, drained.owed());
    }

    @Test
    void anEmptyWorkerRestsSoItWorksAtItsSpeed() {
        assertEquals(60, HungerMath.restTicks(20, 0.25)); // a quarter speed: 4x as long all told
        assertEquals(20, HungerMath.restTicks(20, 0.5));
        assertEquals(0, HungerMath.restTicks(20, 1));
        assertEquals(0, HungerMath.restTicks(0, 0.25));
    }
}
