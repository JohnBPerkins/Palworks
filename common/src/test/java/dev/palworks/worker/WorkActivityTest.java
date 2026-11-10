package dev.palworks.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.palworks.job.JobType;
import org.junit.jupiter.api.Test;

/** What a worker shows as doing, from its state (the party HUD's work icons). */
class WorkActivityTest {

    @Test
    void switchedOffWinsOverEverything() {
        assertEquals(WorkActivity.OFF, WorkActivity.of(false, JobType.CHOP_TREE, true, true));
        assertEquals(WorkActivity.OFF, WorkActivity.of(false, null, false, false));
    }

    @Test
    void noJobIsIdle() {
        assertEquals(WorkActivity.IDLE, WorkActivity.of(true, null, false, false));
        assertEquals(WorkActivity.IDLE, WorkActivity.of(true, null, true, false));
    }

    @Test
    void walksThenWorks() {
        assertEquals(
                new WorkActivity(WorkActivity.Phase.WALKING, JobType.HARVEST_CROPS),
                WorkActivity.of(true, JobType.HARVEST_CROPS, false, false));
        assertEquals(
                new WorkActivity(WorkActivity.Phase.WORKING, JobType.HARVEST_CROPS),
                WorkActivity.of(true, JobType.HARVEST_CROPS, false, true));
    }

    @Test
    void battleOrEvolutionPausesItsJob() {
        assertEquals(
                new WorkActivity(WorkActivity.Phase.PAUSED, JobType.FUEL_FURNACE),
                WorkActivity.of(true, JobType.FUEL_FURNACE, true, true));
    }

    @Test
    @SuppressWarnings("EnumOrdinal") // tests the ordinal lookup itself
    void phaseOrdinalLookupIsSafe() {
        for (WorkActivity.Phase phase : WorkActivity.Phase.values()) {
            assertEquals(phase, WorkActivity.Phase.fromOrdinal(phase.ordinal()));
        }
        assertEquals(WorkActivity.Phase.IDLE, WorkActivity.Phase.fromOrdinal(99));
        assertEquals(WorkActivity.Phase.IDLE, WorkActivity.Phase.fromOrdinal(-1));
    }
}
