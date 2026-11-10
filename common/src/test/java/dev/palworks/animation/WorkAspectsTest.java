package dev.palworks.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobType;
import dev.palworks.worker.WorkActivity;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The aspects a worker shows its poser, and how long they last once it stops. */
class WorkAspectsTest {

    private static final WorkActivity HARVESTING = new WorkActivity(WorkActivity.Phase.WORKING, JobType.HARVEST_CROPS);
    private static final WorkActivity WALKING = new WorkActivity(WorkActivity.Phase.WALKING, JobType.HARVEST_CROPS);

    @Test
    void aWorkerDoingAJobHasTheWorkingAspectAndOneForTheJob() {
        assertEquals(Set.of("palworks-working", "palworks-harvest-crops"), WorkAspects.forJob(JobType.HARVEST_CROPS));
        assertEquals(Set.of("palworks-working", "palworks-chop-tree"), WorkAspects.forJob(JobType.CHOP_TREE));
    }

    @Test
    void theyAreShownWhileItWorks() {
        assertEquals(WorkAspects.forJob(JobType.HARVEST_CROPS), WorkAspects.wanted(Set.of(), 0, HARVESTING, 100));
    }

    @Test
    void theyStayForABriefStopSoThePoseDoesNotFlicker() {
        // e.g. the tick between furnace cycles, or between one crop and the next
        Set<String> shown = WorkAspects.forJob(JobType.HARVEST_CROPS);
        assertEquals(shown, WorkAspects.wanted(shown, 100, WALKING, 100 + WorkAspects.OFF_DELAY_TICKS - 1));
    }

    @Test
    void theyGoOnceItHasStoppedForLonger() {
        Set<String> shown = WorkAspects.forJob(JobType.HARVEST_CROPS);
        assertTrue(WorkAspects.wanted(shown, 100, WALKING, 100 + WorkAspects.OFF_DELAY_TICKS)
                .isEmpty());
        assertTrue(WorkAspects.wanted(shown, 100, WorkActivity.IDLE, 500).isEmpty());
    }

    @Test
    void aNewJobSwapsTheJobAspectStraightAway() {
        Set<String> shown = WorkAspects.forJob(JobType.HARVEST_CROPS);
        WorkActivity planting = new WorkActivity(WorkActivity.Phase.WORKING, JobType.PLANT_SEEDS);
        assertEquals(WorkAspects.forJob(JobType.PLANT_SEEDS), WorkAspects.wanted(shown, 100, planting, 101));
    }
}
