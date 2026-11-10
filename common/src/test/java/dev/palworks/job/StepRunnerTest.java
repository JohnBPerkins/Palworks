package dev.palworks.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.StepRunner.Outcome;
import dev.palworks.worker.WorkerState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The rules every job's steps run by: timing, order, early end, repeat, resume. */
// Test data reads better inline than as constants
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class StepRunnerTest {

    @BeforeAll
    static void bootstrap() {
        // WorkerState holds an ItemStack, which needs the vanilla registries
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Records what happens to it, so tests can check the order of starts and effects. */
    private static final class FakeStep implements StepRunner.TimedStep<List<String>> {
        final String name;
        final int ticks;
        final boolean keepGoing;
        boolean needed = true;
        boolean there = true;
        boolean canDo = true;
        int checks;
        int startTick;

        FakeStep(String name, int ticks, boolean keepGoing) {
            this.name = name;
            this.ticks = ticks;
            this.keepGoing = keepGoing;
        }

        FakeStep notNeeded() {
            needed = false;
            return this;
        }

        FakeStep startingAt(int tick) {
            startTick = tick;
            return this;
        }

        @Override
        public int startTick(List<String> log) {
            return startTick;
        }

        FakeStep(String name, int ticks) {
            this(name, ticks, true);
        }

        @Override
        public int ticks(List<String> log) {
            return ticks;
        }

        @Override
        public void start(List<String> log) {
            log.add("start " + name);
        }

        @Override
        public boolean finish(List<String> log) {
            log.add("finish " + name);
            return keepGoing;
        }

        @Override
        public boolean needed(List<String> log) {
            return needed;
        }

        @Override
        public boolean stillThere(List<String> log) {
            checks++;
            return there;
        }

        @Override
        public boolean canDo(List<String> log) {
            return canDo;
        }
    }

    private WorkerState state;
    private List<String> log;

    @BeforeEach
    void setUp() {
        state = new WorkerState();
        log = new ArrayList<>();
    }

    private Outcome tick(List<FakeStep> steps) {
        return StepRunner.tick(steps, state, log, ctx -> false);
    }

    @Test
    void aStepStartsOnItsFirstTickAndTakesEffectAfterItsTicks() {
        List<FakeStep> steps = List.of(new FakeStep("chop", 3));

        assertEquals(Outcome.WORKING, tick(steps));
        assertEquals(List.of("start chop"), log, "start runs on the first tick, the effect hasn't happened");
        assertEquals(Outcome.WORKING, tick(steps));
        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start chop", "finish chop"), log, "start runs once; the effect after 3 ticks");
    }

    @Test
    void stepsRunInOrderAndTheJobFinishesAfterTheLast() {
        List<FakeStep> steps = List.of(new FakeStep("chop", 2), new FakeStep("replant", 1));

        assertEquals(Outcome.WORKING, tick(steps));
        assertEquals(Outcome.WORKING, tick(steps)); // chop done, on to replant
        assertEquals(1, state.getJobStep());
        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start chop", "finish chop", "start replant", "finish replant"), log);
    }

    @Test
    void progressResetsWhenTheJobFinishes() {
        List<FakeStep> steps = List.of(new FakeStep("a", 1), new FakeStep("b", 1));
        tick(steps);
        tick(steps);

        assertEquals(0, state.getJobStep());
        assertEquals(0, state.getStepTicks());
        assertFalse(state.isWorking());
    }

    @Test
    void aStepCanEndTheJobEarly() {
        // e.g. a chop that finds no valid tree: there's nothing to replant
        List<FakeStep> steps = List.of(new FakeStep("chop", 1, false), new FakeStep("replant", 1));

        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start chop", "finish chop"), log, "the later step never runs");
    }

    @Test
    void aRepeatingJobStartsOverAfterItsLastStep() {
        List<FakeStep> steps = List.of(new FakeStep("ignite", 1), new FakeStep("smelt", 1));

        StepRunner.tick(steps, state, log, ctx -> true);
        assertEquals(Outcome.REPEATING, StepRunner.tick(steps, state, log, ctx -> true));
        assertEquals(0, state.getJobStep(), "it goes back to its first step");
        StepRunner.tick(steps, state, log, ctx -> true);
        assertEquals("start ignite", log.get(log.size() - 2), "the first step starts again");
    }

    @Test
    void aJobThatEndsEarlyDoesNotRepeat() {
        List<FakeStep> steps = List.of(new FakeStep("look", 1, false));

        assertEquals(Outcome.FINISHED, StepRunner.tick(steps, state, log, ctx -> true));
    }

    @Test
    void restartingAStepRunsItsStartAgainButKeepsEarlierSteps() {
        // A worker moved off its spot mid-step (pushed, paused for a battle) redoes that step only
        List<FakeStep> steps = List.of(new FakeStep("chop", 1), new FakeStep("replant", 3));
        tick(steps); // chop done
        tick(steps); // replant started
        state.restartStep();

        tick(steps);
        assertEquals(
                List.of("start chop", "finish chop", "start replant", "start replant"),
                log,
                "replant starts over (its animation replays); the chop isn't redone");
        assertEquals(1, state.getJobStep());
    }

    @Test
    void aHandedOverJobCarriesOnFromItsStep() {
        // Another worker took the job after the trunk came down: it only replants
        List<FakeStep> steps = List.of(new FakeStep("chop", 1), new FakeStep("replant", 1));
        state.startJob(1, null);

        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start replant", "finish replant"), log);
    }

    @Test
    void progressPastTheLastStepCountsAsFinished() {
        // e.g. a job's steps changed in an update and a saved step no longer exists
        List<FakeStep> steps = List.of(new FakeStep("only", 1));
        state.startJob(5, null);

        assertEquals(Outcome.FINISHED, tick(steps));
        assertTrue(log.isEmpty(), "no step runs");
        assertEquals(0, state.getJobStep());
    }

    @Test
    void aStepWithNothingToDoIsSkippedWithoutTakingTime() {
        // e.g. no leaves in the way: the lumberjack goes straight to chopping
        List<FakeStep> steps = List.of(new FakeStep("clear", 20).notNeeded(), new FakeStep("chop", 2));

        assertEquals(Outcome.WORKING, tick(steps));
        assertEquals(List.of("start chop"), log, "the chop starts on the first tick; clear never runs");
        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start chop", "finish chop"), log);
    }

    @Test
    void aStepIsOnlyAskedWhetherItsNeededWhenReached() {
        // Once it has started it runs to its end, even if there's nothing left for it by then
        FakeStep clear = new FakeStep("clear", 2);
        List<FakeStep> steps = List.of(clear, new FakeStep("chop", 1));
        tick(steps); // clear started
        clear.needed = false;

        assertEquals(Outcome.WORKING, tick(steps));
        assertEquals(List.of("start clear", "finish clear"), log);
    }

    @Test
    void skippingEveryRemainingStepFinishesTheJob() {
        List<FakeStep> steps = List.of(new FakeStep("chop", 1), new FakeStep("tidy", 5).notNeeded());

        assertEquals(Outcome.FINISHED, tick(steps), "chop done; tidy has nothing to do");
        assertEquals(List.of("start chop", "finish chop"), log);
        assertEquals(0, state.getJobStep(), "progress reset for the next job");
    }

    @Test
    void aStepCanStartPartWayThroughSoItsAnimationLandsAsItsEffectHappens() {
        // e.g. Ember plays 8 ticks in (once the worker has settled), and the furnace lights at its end
        List<FakeStep> steps = List.of(new FakeStep("ember", 4).startingAt(2));

        tick(steps);
        tick(steps);
        assertTrue(log.isEmpty(), "nothing has started for the first two ticks");
        tick(steps);
        assertEquals(List.of("start ember"), log, "starts on its third tick");
        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start ember", "finish ember"), log, "the step still takes its 4 ticks");
    }

    @Test
    void restartingADelayedStepWaitsAgainBeforeItStarts() {
        List<FakeStep> steps = List.of(new FakeStep("ember", 4).startingAt(2));
        tick(steps);
        tick(steps);
        tick(steps); // started
        state.restartStep();

        tick(steps);
        tick(steps);
        assertEquals(List.of("start ember"), log, "not started again yet: the worker settles first");
        tick(steps);
        assertEquals(List.of("start ember", "start ember"), log);
    }

    // ---- The job going away ----

    @Test
    void aJobThatsGoneWhenItsStepIsReachedEndsWithoutStarting() {
        // e.g. the crop was harvested by someone else just as the worker got there
        FakeStep harvest = new FakeStep("harvest", 3);
        harvest.there = false;

        assertEquals(Outcome.GONE, tick(List.of(harvest)));
        assertTrue(log.isEmpty(), "its animation never starts, and there's no effect");
        assertFalse(state.isWorking(), "progress reset");
    }

    @Test
    void aJobThatGoesWhileItsStepGoesOnEndsWithinARecheck() {
        // e.g. a furnace emptied during its smelt: the worker doesn't wait out the rest of it
        FakeStep smelt = new FakeStep("smelt", 100);
        List<FakeStep> steps = List.of(smelt);
        tick(steps);
        smelt.there = false;

        int ticks = 0;
        Outcome outcome;
        do {
            outcome = tick(steps);
            ticks++;
        } while (outcome == Outcome.WORKING);
        assertEquals(Outcome.GONE, outcome);
        assertTrue(ticks <= StepRunner.RECHECK_TICKS, "noticed within a recheck, took " + ticks);
        assertEquals(List.of("start smelt"), log, "no effect");
        assertEquals(0, state.getJobStep());
    }

    @Test
    void aJobIsCheckedRightBeforeItsEffect() {
        // Gone between rechecks, on the step's last tick: the effect mustn't happen on nothing
        FakeStep plant = new FakeStep("plant", StepRunner.RECHECK_TICKS + 2);
        List<FakeStep> steps = List.of(plant);
        for (int i = 0; i < StepRunner.RECHECK_TICKS + 1; i++) {
            assertEquals(Outcome.WORKING, tick(steps));
        }
        plant.there = false;

        assertEquals(Outcome.GONE, tick(steps));
        assertEquals(List.of("start plant"), log);
    }

    @Test
    void aStepThatsThereTakesEffectAndIsCheckedOncePerCheckpoint() {
        // Reached + started (one check, same tick), one recheck at 10 ticks in, and before its effect
        FakeStep chop = new FakeStep("chop", StepRunner.RECHECK_TICKS + 2);
        List<FakeStep> steps = List.of(chop);
        Outcome outcome;
        do {
            outcome = tick(steps);
        } while (outcome == Outcome.WORKING);

        assertEquals(Outcome.FINISHED, outcome);
        assertEquals(List.of("start chop", "finish chop"), log);
        assertEquals(3, chop.checks);
    }

    @Test
    void aOneTickStepIsOnlyCheckedOnce() {
        FakeStep pickUp = new FakeStep("pick up", 1);

        assertEquals(Outcome.FINISHED, tick(List.of(pickUp)));
        assertEquals(1, pickUp.checks, "reached and finishing on the same tick: one check covers both");
    }

    @Test
    void aDelayedStepIsCheckedAgainAsItStarts() {
        // Ember waits for the worker to settle: the furnace may have been emptied meanwhile
        FakeStep ember = new FakeStep("ember", 6).startingAt(3);
        List<FakeStep> steps = List.of(ember);
        tick(steps);
        tick(steps);
        tick(steps);
        ember.there = false;

        assertEquals(Outcome.GONE, tick(steps));
        assertTrue(log.isEmpty(), "its animation doesn't start on a job that's gone");
    }

    @Test
    void onlyTheStepReachedIsAsked() {
        // A chopped tree's replant doesn't need the tree: once there, the gone trunk doesn't matter
        FakeStep chop = new FakeStep("chop", 1);
        FakeStep replant = new FakeStep("replant", 1);
        List<FakeStep> steps = List.of(chop, replant);
        tick(steps);
        chop.there = false;

        assertNull(StepRunner.stopReason(steps, state, log), "the replant is still there");
        assertEquals(Outcome.FINISHED, tick(steps));
        assertEquals(List.of("start chop", "finish chop", "start replant", "finish replant"), log);
    }

    @Test
    void aJobOnItsWayIsCheckedForTheStepItsReached() {
        FakeStep chop = new FakeStep("chop", 1);
        List<FakeStep> steps = List.of(chop, new FakeStep("replant", 1));
        assertNull(StepRunner.stopReason(steps, state, log));
        chop.there = false;

        assertEquals(Outcome.GONE, StepRunner.stopReason(steps, state, log), "the tree it's on its way to has gone");
        assertTrue(log.isEmpty(), "asking doesn't run anything");
    }

    // ---- The worker not being able to do the job ----

    @Test
    void aWorkerThatCantDoItsJobStopsWithoutItsEffect() {
        // e.g. a planter whose seeds were taken out of its pouch as it got there
        FakeStep plant = new FakeStep("plant", 3);
        plant.canDo = false;

        assertEquals(Outcome.UNABLE, tick(List.of(plant)));
        assertTrue(log.isEmpty(), "its animation never starts, and nothing's planted");
        assertFalse(state.isWorking(), "progress reset");
    }

    @Test
    void aWorkerIsCheckedRightBeforeItsEffect() {
        FakeStep plant = new FakeStep("plant", StepRunner.RECHECK_TICKS + 2);
        List<FakeStep> steps = List.of(plant);
        for (int i = 0; i < StepRunner.RECHECK_TICKS + 1; i++) {
            assertEquals(Outcome.WORKING, tick(steps));
        }
        plant.canDo = false;

        assertEquals(Outcome.UNABLE, tick(steps));
        assertEquals(List.of("start plant"), log);
    }

    @Test
    void aJobThatsGoneIsGoneEvenIfTheWorkerCouldntDoItAnyway() {
        // Gone is gone for every worker: it's dropped, not given back for another to try
        FakeStep plant = new FakeStep("plant", 1);
        plant.there = false;
        plant.canDo = false;

        assertEquals(Outcome.GONE, StepRunner.stopReason(List.of(plant), state, log));
        assertEquals(Outcome.GONE, tick(List.of(plant)));
    }

    @Test
    void aWorkerOnItsWayIsCheckedForTheStepItsReached() {
        FakeStep chop = new FakeStep("chop", 1);
        FakeStep replant = new FakeStep("replant", 1);
        List<FakeStep> steps = List.of(chop, replant);
        replant.canDo = false;
        assertNull(StepRunner.stopReason(steps, state, log), "it can chop");
        tick(steps);

        assertEquals(Outcome.UNABLE, StepRunner.stopReason(steps, state, log), "it's reached the replant");
    }
}
