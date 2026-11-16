package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A Pokémon's animation clock runs at its pace, and its sounds and particles are timed on it. */
class PacedClockTest {
    private static final float EPS = 1.0e-4f;

    @Test
    void itStartsFromTheAgeItsFirstAskedAt() {
        PacedClock clock = new PacedClock();
        clock.advanceTo(100, 0.25f);
        assertEquals(5f, clock.secondsNow(), EPS);
        assertEquals(5f, clock.secondsBeforeLastStep(), EPS, "no time has passed on it yet");
    }

    @Test
    void itGoesUpByThePaceEachTick() {
        PacedClock clock = new PacedClock();
        clock.advanceTo(100, 1f);
        clock.advanceTo(120, 0.25f);
        assertEquals(5.25f, clock.secondsNow(), EPS, "a second at quarter pace is a quarter of one");
        assertEquals(5.25f + 0.5f * 0.25f / 20f, clock.seconds(0.5f, 0.25f), EPS, "half way through a tick");
        clock.advanceTo(140, 1f);
        assertEquals(6.25f, clock.secondsNow(), EPS, "back at full pace it carries on from where it was");
    }

    @Test
    void askedTwiceInATickItStaysPut() {
        PacedClock clock = new PacedClock();
        clock.advanceTo(100, 1f);
        clock.advanceTo(101, 0.5f);
        clock.advanceTo(101, 0.5f);
        assertEquals(100.5f / 20f, clock.secondsNow(), EPS);
        assertEquals(5f, clock.secondsBeforeLastStep(), EPS, "the tick's stretch is still the whole tick");
    }

    @Test
    void itStartsAgainWhenTheAgeGoesBack() {
        PacedClock clock = new PacedClock();
        clock.advanceTo(100, 1f);
        clock.advanceTo(3, 1f);
        assertEquals(3f / 20f, clock.secondsNow(), EPS);
    }

    @Test
    void neverSlowedItsTicksCoverWhatTheAgesDo() {
        // As Cobblemon has it without the clock: each tick covers its age over 20 to the next
        PacedClock clock = new PacedClock();
        clock.advanceTo(100, 1f);
        clock.advanceTo(101, 1f);
        assertEquals(100f / 20f, clock.secondsBeforeLastStep(), EPS);
        assertEquals(101f / 20f, clock.secondsNow(), EPS);
    }

    @Test
    void anAnimationStartedAfterItWasSlowedStillGetsItsSoundsAndParticles() {
        // Slowed for 100 ticks, the clock is 75 ticks behind the Pokémon's age
        PacedClock clock = new PacedClock();
        clock.advanceTo(100, 1f);
        clock.advanceTo(200, 0.25f);
        // A cry starts part way through the tick: Cobblemon notes the clock's time as its start,
        // and plays it at full pace
        float started = clock.seconds(0.5f, 1f);
        float sound = 0f; // its sound is at its very start
        float particle = 0.5f; // and something half a second in

        boolean soundPlayed = false;
        boolean particlePlayed = false;
        float lastEnd = clock.secondsNow();
        for (int age = 201; age <= 220; age++) {
            clock.advanceTo(age, 1f);
            float from = clock.secondsBeforeLastStep() - started;
            float to = clock.secondsNow() - started;
            assertEquals(lastEnd, clock.secondsBeforeLastStep(), EPS, "each tick starts where the last ended");
            lastEnd = clock.secondsNow();
            soundPlayed |= from <= sound && sound <= to;
            particlePlayed |= from <= particle && particle <= to;
            // By the Pokémon's age instead, the animation is already 3.75s in as it starts
            assertTrue((age - 1) / 20f - started > 3.7f, "the age is far ahead of the clock");
        }
        assertTrue(soundPlayed, "the sound at its start");
        assertTrue(particlePlayed, "the particle half a second in");
    }
}
