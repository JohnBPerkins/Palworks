package dev.palworks.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Turning to face the work: a little each tick, the short way round. */
class FacingTest {

    @Test
    void yawsAreMinecrafts() {
        assertEquals(0.0f, Facing.yawTowards(0, 1), 1e-4, "south");
        assertEquals(90.0f, Math.floorMod(Math.round(Facing.yawTowards(-1, 0)), 360), "west");
        assertEquals(180.0f, Math.floorMod(Math.round(Facing.yawTowards(0, -1)), 360), "north");
        assertEquals(270.0f, Math.floorMod(Math.round(Facing.yawTowards(1, 0)), 360), "east");
    }

    @Test
    void itTurnsALittleEachTick() {
        assertEquals(15.0f, Facing.turn(0, 90, 15), 1e-4);
        assertEquals(-15.0f, Facing.turn(0, -90, 15), 1e-4);
    }

    @Test
    void itTurnsTheShortWayRound() {
        // From 170 to -170 is 20 degrees on, through 180, not 340 back
        assertEquals(185.0f, Facing.turn(170, -170, 15), 1e-4);
    }

    @Test
    void itStopsOnceItsFacingTheWay() {
        assertEquals(90.0f, Facing.turn(80, 90, 15), 1e-4);
        assertEquals(90.0f, Facing.turn(90, 90, 15), 1e-4);
    }

    @Test
    void halfATurnTakesUnderSixTenthsOfASecond() {
        float yaw = 0;
        int ticks = 0;
        while (!Facing.faces(yaw, 180)) {
            yaw = Facing.turn(yaw, 180, Facing.TURN_DEGREES_PER_TICK);
            ticks++;
        }
        assertTrue(ticks <= 12, "took " + ticks + " ticks");
    }

    @Test
    void nearlyFacingCounts() {
        assertTrue(Facing.faces(355, 0), "5 degrees off, across 0");
        assertFalse(Facing.faces(20, 0));
    }
}
