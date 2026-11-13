package dev.palworks.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the worker enabled flag: owned Pokemon work by default, and an explicit disable must
 * survive a save/load round trip instead of being silently turned back on.
 */
class WorkerStateTest {

    @BeforeAll
    static void bootstrap() {
        // WorkerState holds an ItemStack, which needs the vanilla registries to be initialised.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void newStateIsEnabled() {
        assertTrue(new WorkerState().isEnabled());
    }

    @Test
    void disabledSurvivesSaveAndLoad() {
        WorkerState state = new WorkerState();
        state.setEnabled(false);

        WorkerState loaded = WorkerState.fromNBT(state.serializeNBT());

        assertFalse(loaded.isEnabled(), "a disabled worker must stay disabled after reload");
    }

    @Test
    void enabledSurvivesSaveAndLoad() {
        WorkerState loaded = WorkerState.fromNBT(new WorkerState().serializeNBT());

        assertTrue(loaded.isEnabled());
    }

    @Test
    void fullnessOwedSurvivesSaveAndLoad() {
        WorkerState state = new WorkerState();
        state.setFullnessOwed(0.35);
        assertEquals(0.35, WorkerState.fromNBT(state.serializeNBT()).getFullnessOwed(), 1e-9);
        // Saved before hunger existed: nothing owed
        assertEquals(0, WorkerState.fromNBT(new CompoundTag()).getFullnessOwed());
    }

    @Test
    void beingInBedAndWokenSurviveSaveAndLoad() {
        // A recall and send-out in the night doesn't put it straight back to sleep
        WorkerState state = new WorkerState();
        state.setInBed(true);
        state.setWokenUntil(12_345);
        WorkerState loaded = WorkerState.fromNBT(state.serializeNBT());
        assertTrue(loaded.isInBed());
        assertEquals(12_345, loaded.getWokenUntil());

        WorkerState fresh = WorkerState.fromNBT(new CompoundTag());
        assertFalse(fresh.isInBed());
        assertEquals(0, fresh.getWokenUntil());
    }

    @Test
    void howItSleptSurvivesSaveAndLoad() {
        // A reload in the night doesn't wake it, or forget how it slept
        WorkerState state = new WorkerState();
        state.setAsleep(true);
        state.addSleepTick(true);
        state.addSleepTick(false);
        state.addSleepTick(false);
        state.setTired(true);
        WorkerState loaded = WorkerState.fromNBT(state.serializeNBT());
        assertTrue(loaded.isAsleep());
        assertEquals(1, loaded.getBedSleepTicks());
        assertEquals(2, loaded.getGroundSleepTicks());
        assertTrue(loaded.isTired());

        WorkerState fresh = WorkerState.fromNBT(new CompoundTag());
        assertFalse(fresh.isAsleep());
        assertFalse(fresh.isTired());
    }

    @Test
    void itsMoodSurvivesSaveAndLoad() {
        WorkerState state = new WorkerState();
        assertEquals(WorkerState.MAX_MOOD, state.getMood()); // a new worker is in the best of moods
        state.setMood(12.5);
        state.setStriking(true);
        WorkerState loaded = WorkerState.fromNBT(state.serializeNBT());
        assertEquals(12.5, loaded.getMood());
        assertTrue(loaded.isStriking());

        WorkerState fresh = WorkerState.fromNBT(new CompoundTag()); // saved before mood
        assertEquals(WorkerState.MAX_MOOD, fresh.getMood());
        assertFalse(fresh.isStriking());
    }

    @Test
    void moodStaysBetweenNoneAndTheMost() {
        WorkerState state = new WorkerState();
        state.setMood(-5);
        assertEquals(0, state.getMood());
        state.setMood(250);
        assertEquals(WorkerState.MAX_MOOD, state.getMood());
    }

    @Test
    void stateWithoutEnabledKeyLoadsAsEnabled() {
        assertTrue(
                WorkerState.fromNBT(new CompoundTag()).isEnabled(),
                "data saved before the enabled key existed should default to enabled");
    }

    // ---- Job progress (steps) ----

    @Test
    void jobStepAndDataSurviveSaveAndLoad() {
        WorkerState state = new WorkerState();
        CompoundTag data = new CompoundTag();
        data.putString("sapling", "minecraft:oak_sapling");
        state.startJob(1, data);

        WorkerState loaded = WorkerState.fromNBT(state.serializeNBT());

        assertEquals(1, loaded.getJobStep(), "a reloaded job carries on from the step it reached");
        assertEquals("minecraft:oak_sapling", loaded.getJobData().getString("sapling"));
    }

    @Test
    void ticksWithinAStepAreNotSaved() {
        // After a reload the step it was on starts over (with its animation), not mid-way
        WorkerState state = new WorkerState();
        state.startJob(1, null);
        state.addStepTick();
        state.addStepTick();

        assertEquals(0, WorkerState.fromNBT(state.serializeNBT()).getStepTicks());
    }

    @Test
    void stateSavedBeforeJobStepsLoadsAtTheStartOfTheJob() {
        WorkerState loaded = WorkerState.fromNBT(new CompoundTag());

        assertEquals(0, loaded.getJobStep());
        assertNotNull(loaded.getJobData());
    }

    @Test
    void startJobCopiesTheHandedOverData() {
        CompoundTag data = new CompoundTag();
        data.putInt("x", 1);
        WorkerState state = new WorkerState();
        state.startJob(1, data);

        data.putInt("x", 2); // the job record the data came from changes later
        assertEquals(1, state.getJobData().getInt("x"), "the worker has its own copy");
    }

    @Test
    void clearingTheReservationClearsJobProgress() {
        WorkerState state = new WorkerState();
        state.setCurrentReservation(new ReservationId(BlockPos.ZERO, JobType.CHOP_TREE, 0L));
        CompoundTag data = new CompoundTag();
        data.putString("sapling", "minecraft:oak_sapling");
        state.startJob(1, data);
        state.addStepTick();

        state.clearReservation();

        assertNull(state.getCurrentReservation());
        assertEquals(0, state.getJobStep());
        assertEquals(0, state.getStepTicks());
        assertTrue(state.getJobData().isEmpty());
    }

    @Test
    void progressFlagsDistinguishStartedFromUnstartedJobs() {
        WorkerState state = new WorkerState();
        assertFalse(state.isWorking());
        assertFalse(state.hasJobProgress());

        state.addStepTick(); // partway through the first step: working, but nothing done yet
        assertTrue(state.isWorking());
        assertFalse(state.hasJobProgress());

        state.nextStep(); // first step done
        assertTrue(state.hasJobProgress());
    }

    @Test
    void pathFailuresOnTicksInARowCountOnce() {
        // A walking worker checks every tick: a hiccup a few ticks long isn't being stuck
        WorkerState state = new WorkerState();
        for (int tick = 0; tick < 5; tick++) {
            state.recordPathFailure();
        }
        assertEquals(1, state.getPathFailCount());
        assertFalse(state.isStuck());
    }
}
