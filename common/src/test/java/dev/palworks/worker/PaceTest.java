package dev.palworks.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A worker's pace: rounded so the server and clients agree, and told to clients as an aspect. */
class PaceTest {
    private static final double DELTA = 1e-9;

    @Test
    void roundedToFivePercentBetweenOneStepAndFull() {
        assertEquals(0.25, Pace.rounded(0.25), DELTA);
        assertEquals(0.2, Pace.rounded(0.1875), DELTA);
        assertEquals(0.75, Pace.rounded(0.74), DELTA);
        assertEquals(0.05, Pace.rounded(0.001), DELTA, "never stopped dead");
        assertEquals(Pace.FULL, Pace.rounded(1.3), DELTA);
    }

    @Test
    void anAspectOnlyWhileSlowed() {
        assertEquals("palworks-pace-25", Pace.aspect(0.25));
        assertEquals("palworks-pace-75", Pace.aspect(0.75));
        assertNull(Pace.aspect(1.0));
        assertNull(Pace.aspect(0.999), "rounds to full: no aspect");
    }

    @Test
    void theAspectReadsBackAsItsPace() {
        assertEquals(0.25, Pace.fromAspects(Set.of("shiny", Pace.aspect(0.25))), DELTA);
        assertEquals(0.75, Pace.fromAspects(Set.of(Pace.aspect(0.75))), DELTA);
        assertEquals(Pace.FULL, Pace.fromAspects(Set.of("shiny")), DELTA);
        assertEquals(Pace.FULL, Pace.fromAspects(Set.of("palworks-pace-lots")), DELTA, "not a pace: full");
    }
}
