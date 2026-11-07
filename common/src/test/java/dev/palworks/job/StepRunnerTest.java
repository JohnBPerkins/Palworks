package dev.palworks.job;

import dev.palworks.job.StepRunner.Outcome;
import dev.palworks.worker.WorkerState;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules every job's steps run by: timing, order, early end, repeat, resume. */
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

        FakeStep(String name, int ticks, boolean keepGoing) {
            this.name = name;
            this.ticks = ticks;
            this.keepGoing = keepGoing;
        }

        FakeStep(String name, int ticks) {
            this(name, ticks, true);
        }

        @Override public int ticks() { return ticks; }
        @Override public void start(List<String> log) { log.add("start " + name); }
        @Override public boolean finish(List<String> log) {
            log.add("finish " + name);
            return keepGoing;
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
        assertEquals(List.of("start chop", "finish chop", "start replant", "start replant"), log,
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
}
