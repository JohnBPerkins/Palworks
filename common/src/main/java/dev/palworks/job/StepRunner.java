package dev.palworks.job;

import dev.palworks.worker.WorkerState;

import java.util.List;
import java.util.function.Predicate;

/**
 * Runs a job's timed steps, one tick at a time, keeping its progress in the worker's
 * {@link WorkerState} (the step reached, ticks into it). Generic over the context the steps act on,
 * so the rules can be unit-tested without a world:
 * <ul>
 *   <li>a step's {@code start} runs on its first tick (where its animation goes);</li>
 *   <li>once it has lasted {@code ticks()} ticks, its {@code finish} runs (its effect), and the job
 *       moves on to the next step; a {@code finish} returning false ends the job there;</li>
 *   <li>after the last step, the job repeats if {@code repeat} says so, otherwise it's finished;</li>
 *   <li>progress beyond the job's steps (e.g. its steps changed in an update) counts as finished.</li>
 * </ul>
 */
public final class StepRunner {
    private StepRunner() {}

    /** One timed step of a job. */
    public interface TimedStep<C> {
        /** How many ticks the worker spends on this step before its effect happens. */
        int ticks();
        /** Runs on the step's first tick. */
        void start(C ctx);
        /** The step's effect, when it's over. Return false to end the job here. */
        boolean finish(C ctx);
    }

    public enum Outcome {
        /** The job isn't done yet. */
        WORKING,
        /** The job is done. */
        FINISHED,
        /** The last step is done and the job starts over (progress reset to its first step). */
        REPEATING
    }

    /** Advance the job by one tick. */
    public static <C> Outcome tick(List<? extends TimedStep<C>> steps, WorkerState progress, C ctx,
                                   Predicate<C> repeat) {
        if (progress.getJobStep() >= steps.size()) {
            progress.resetJobProgress();
            return Outcome.FINISHED;
        }

        TimedStep<C> step = steps.get(progress.getJobStep());
        if (progress.getStepTicks() == 0) {
            step.start(ctx);
        }
        progress.addStepTick();
        if (progress.getStepTicks() < step.ticks()) {
            return Outcome.WORKING;
        }

        // The step is done: its effect happens now
        boolean keepGoing = step.finish(ctx);
        progress.nextStep();
        if (keepGoing && progress.getJobStep() < steps.size()) {
            return Outcome.WORKING;
        }

        // Last step done (or a step ended the job early)
        progress.resetJobProgress();
        return keepGoing && repeat.test(ctx) ? Outcome.REPEATING : Outcome.FINISHED;
    }
}
