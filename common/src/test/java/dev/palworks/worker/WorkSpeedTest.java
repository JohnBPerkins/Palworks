package dev.palworks.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkSpeedTest {
    @Test
    void nothingWrongIsFullSpeed() {
        assertEquals(1.0, WorkSpeed.combined(1, 1));
    }

    @Test
    void oneSlowdownIsItsOwnSpeed() {
        assertEquals(0.75, WorkSpeed.combined(0.75, 1)); // no fullness left
        assertEquals(0.75, WorkSpeed.combined(1, 0.75)); // Tired
    }

    @Test
    void slowdownsAddUp() {
        // Out of food and Tired: a quarter off for each, so half speed (not 0.75 x 0.75)
        assertEquals(0.5, WorkSpeed.combined(0.75, 0.75));
        assertEquals(0.7, WorkSpeed.combined(0.9, 0.8), 1e-9);
    }

    @Test
    void oneSlowdownCanGoAsLowAsItsSet() {
        // No floor above the config's own: a worker set to a tenth's speed out of food goes at a tenth
        assertEquals(0.1, WorkSpeed.combined(0.1, 1), 1e-9);
        assertEquals(0.3, WorkSpeed.combined(0.3, 1), 1e-9);
        assertEquals(0.3, WorkSpeed.combined(0.65, 0.65), 1e-9);
    }

    @Test
    void togetherTheyNeverGoBelowTheSlowestEitherCanBe() {
        assertEquals(WorkSpeed.SLOWEST, WorkSpeed.combined(0.3, 0.3));
        assertEquals(WorkSpeed.SLOWEST, WorkSpeed.combined(0.1, 0.75));
        assertEquals(0.1, WorkSpeed.SLOWEST);
    }
}
