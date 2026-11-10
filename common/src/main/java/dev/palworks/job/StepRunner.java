package dev.palworks.job;

import dev.palworks.worker.WorkerState;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a job's timed steps, one tick at a time, keeping its progress in the worker's
 * {@link WorkerState} (the step reached, ticks into it). Generic over the context the steps act on,
 * so the rules can be unit-tested without a world:
 * <ul>
 *   <li>a step that isn't {@code needed} when it's reached is skipped, taking no time;</li>
 *   <li>a step's {@code start} runs on its {@code startTick} (its first tick unless it says
 *       otherwise), which is where its animation goes;</li>
 *   <li>once it has lasted {@code ticks} ticks, its {@code finish} runs (its effect), and the job
 *       moves on to the next step; a {@code finish} returning false ends the job there;</li>
 *   <li>after the last step, the job repeats if {@code repeat} says so, otherwise it's finished;</li>
 *   <li>a step whose job has gone ({@code stillThere} says not: the crop was harvested, the
 *       furnace emptied), or that its worker can't do any more ({@code canDo} says not: its seeds
 *       were taken), ends the job there, without its effect ({@link Outcome#GONE} and
 *       {@link Outcome#UNABLE}). Both are asked when the step is reached, when its {@code start}
 *       runs, every {@link #RECHECK_TICKS} ticks while it goes on, and right before its
 *       {@code finish}, so a worker neither carries on for nothing nor has an effect on a job that
 *       isn't there, or that it can't do, any more;</li>
 *   <li>progress beyond the job's steps (e.g. its steps changed in an update) counts as finished.</li>
 * </ul>
 */
public final class StepRunner {
    private StepRunner() {}

    /** How often (ticks into a step) its job is checked as still there while the step goes on. */
    public static final int RECHECK_TICKS = 10;

    /**
     * One timed step of a job. Its timing can depend on the context (a species with an animation of
     * its own takes as long as that does), but should stay the same while the step goes on.
     */
    public interface TimedStep<C> {
        /** How many ticks the worker spends on this step before its effect happens. */
        int ticks(C ctx);
        /** Runs once the step has lasted {@link #startTick} ticks (where its animation goes). */
        void start(C ctx);
        /**
         * How many ticks into the step {@code start} runs: 0, its first tick, unless it waits (an
         * animation that has to land on the step's last tick). Less than {@link #ticks}.
         */
        default int startTick(C ctx) {
            return 0;
        }
        /** The step's effect, when it's over. Return false to end the job here. */
        boolean finish(C ctx);
        /**
         * Whether there's anything for this step to do, asked when it's reached (before its first
         * tick). If not, it's skipped straight away, taking no time and having no effect.
         */
        default boolean needed(C ctx) {
            return true;
        }
        /**
         * Whether the job is still there for this step to do (the crop still ripe, the furnace still
         * holding something to smelt). If not, the job ends ({@link Outcome#GONE}) without this
         * step's effect. Unlike {@link #needed}, which skips just this step, this is about the job.
         */
        default boolean stillThere(C ctx) {
            return true;
        }
        /**
         * Whether the worker can do this step (it carries a seed that fits the soil, it has room for
         * what it picks up). If not, the job ends ({@link Outcome#UNABLE}) without this step's
         * effect, for another worker to take on. The worker's side, as {@link #stillThere} is the
         * world's.
         */
        default boolean canDo(C ctx) {
            return true;
        }
    }

    public enum Outcome {
        /** The job isn't done yet. */
        WORKING,
        /** The job is done. */
        FINISHED,
        /** The last step is done and the job starts over (progress reset to its first step). */
        REPEATING,
        /**
         * The job went away (see {@link TimedStep#stillThere}): it ended there, without the step's
         * effect (progress reset).
         */
        GONE,
        /**
         * The worker can't do the job any more (see {@link TimedStep#canDo}): it ended there,
         * without the step's effect (progress reset), and is for another worker to take on.
         */
        UNABLE
    }

    /** Advance the job by one tick. */
    public static <C> Outcome tick(
            List<? extends TimedStep<C>> steps, WorkerState progress, C ctx, Predicate<C> repeat) {
        skipUnneeded(steps, progress, ctx);
        if (progress.getJobStep() >= steps.size()) {
            progress.resetJobProgress();
            return Outcome.FINISHED;
        }

        TimedStep<C> step = steps.get(progress.getJobStep());
        int ticksIn = progress.getStepTicks();
        boolean starting = ticksIn == step.startTick(ctx);
        // Checked when the step is reached (0 ticks in), as it starts, and every so often after
        boolean checked = starting || ticksIn % RECHECK_TICKS == 0;
        Outcome stopped = checked ? stopReason(step, ctx) : null;
        if (stopped != null) {
            progress.resetJobProgress();
            return stopped;
        }
        if (starting) {
            step.start(ctx);
        }
        progress.addStepTick();
        if (progress.getStepTicks() < step.ticks(ctx)) {
            return Outcome.WORKING;
        }

        // The step is done: its effect happens now, if its job is still there for it (not asked
        // again if it just was, this tick)
        stopped = checked ? null : stopReason(step, ctx);
        if (stopped != null) {
            progress.resetJobProgress();
            return stopped;
        }
        boolean keepGoing = step.finish(ctx);
        progress.nextStep();
        if (keepGoing) {
            skipUnneeded(steps, progress, ctx);
        }
        if (keepGoing && progress.getJobStep() < steps.size()) {
            return Outcome.WORKING;
        }

        // Last step done (or a step ended the job early)
        progress.resetJobProgress();
        return keepGoing && repeat.test(ctx) ? Outcome.REPEATING : Outcome.FINISHED;
    }

    /**
     * Why the job can't go on at the step it's reached: {@link Outcome#GONE} (see
     * {@link TimedStep#stillThere}), {@link Outcome#UNABLE} (see {@link TimedStep#canDo}), or null
     * if it can. What {@link #tick} asks, for a worker on its way to the job, say. Progress beyond
     * the job's steps can go on: there's nothing left to check, and {@link #tick} finishes it.
     */
    @Nullable
    public static <C> Outcome stopReason(List<? extends TimedStep<C>> steps, WorkerState progress, C ctx) {
        int index = progress.getJobStep();
        return index >= steps.size() ? null : stopReason(steps.get(index), ctx);
    }

    /** A job gone is gone for every worker, so that's asked first. */
    @Nullable
    private static <C> Outcome stopReason(TimedStep<C> step, C ctx) {
        if (!step.stillThere(ctx)) {
            return Outcome.GONE;
        }
        return step.canDo(ctx) ? null : Outcome.UNABLE;
    }

    /**
     * Move past the steps with nothing to do, from the one reached. Only a step that hasn't started
     * is skipped: one already under way runs to its end.
     */
    private static <C> void skipUnneeded(List<? extends TimedStep<C>> steps, WorkerState progress, C ctx) {
        while (progress.getJobStep() < steps.size()
                && progress.getStepTicks() == 0
                && !steps.get(progress.getJobStep()).needed(ctx)) {
            progress.nextStep();
        }
    }
}
