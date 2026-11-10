package dev.palworks.job.handler;

import dev.palworks.animation.WorkAnimation;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.job.JobChecks;
import dev.palworks.job.StepRunner;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;

/**
 * One timed step of a job. The worker spends {@code ticks} on it at the job, and {@code finish}
 * runs when it's over: the step's effect. Effects only happen in {@code finish}, so a step is
 * all-or-nothing: if the worker leaves (pushed away, paused, the game reloads) the step simply
 * restarts, its animation with it. The step reached is saved, so the job carries on from it,
 * even with a different worker (handed over). A step that isn't {@code isNeeded} when it's
 * reached (nothing for it to do) is skipped.
 *
 * <p>A step with an {@code animation} plays it aimed at the job's block, timed so it lands as
 * {@code finish} runs; it takes at least long enough for that (see
 * {@link WorkAnimation#stepTicks}). A species can have its own version of the animation, with
 * its own timing (see {@link WorkAnimations#forSpecies}), so how long a step takes, its
 * {@code length}, is by species. Build one with {@link #animated}.
 *
 * <p>Every step checks its job is still there ({@code isThere}): when it's reached, while it goes
 * on, and right before its effect (see {@link StepRunner}); the worker also checks it on its way.
 * By default that's {@link JobChecks#isJobStillThere}, the same check that found the job. A step
 * that comes after its job's target is gone by design (replanting a chopped tree) doesn't (see
 * {@link #outlivesItsTarget}). At the same points it checks the worker can do it
 * ({@link #canDo}): {@link JobChecks#canWorkerDo}, the check jobs are handed out by.
 */
public record Step(
        String name,
        ToIntFunction<String> length,
        @Nullable WorkAnimation animation,
        Finish onFinish,
        java.util.function.Predicate<JobContext> isNeeded,
        java.util.function.Predicate<JobContext> isThere)
        implements StepRunner.TimedStep<JobContext> {

    /** This step's animation as a worker of this species plays it, or null if it has none. */
    @Nullable
    WorkAnimation animationFor(@Nullable String speciesId) {
        // Through the accessor: PMD's NonThreadSafeSingleton crashes on a null check of a
        // record component read directly (PMD 7.28.0)
        return animation() == null ? null : WorkAnimations.forSpecies(animation(), speciesId);
    }

    /** How long this step takes a worker of this species. */
    int ticksFor(@Nullable String speciesId) {
        return length.applyAsInt(speciesId);
    }

    @Override
    public int ticks(JobContext ctx) {
        return ticksFor(ctx.species());
    }

    @Override
    public int startTick(JobContext ctx) {
        WorkAnimation played = animationFor(ctx.species());
        return played == null ? 0 : played.playAt(ticks(ctx));
    }

    @Override
    public void start(JobContext ctx) {
        WorkAnimation played = animationFor(ctx.species());
        if (played != null) {
            WorkAnimator.play(ctx.entity(), played, ctx.pos());
        }
    }

    @Override
    public boolean finish(JobContext ctx) {
        // The step's work happens now, as its animation lands: let the animation play out
        WorkAnimator.release(ctx.entity());
        return onFinish.run(ctx);
    }

    @Override
    public boolean needed(JobContext ctx) {
        return isNeeded.test(ctx);
    }

    @Override
    public boolean stillThere(JobContext ctx) {
        return isThere.test(ctx);
    }

    /**
     * The same check its job was handed out by, so a worker that gives a job back isn't handed
     * it again until it can do it. A job type that needs something of its worker adds it to
     * {@link JobChecks#canWorkerDo}, not here.
     */
    @Override
    public boolean canDo(JobContext ctx) {
        return JobChecks.canWorkerDo(
                ctx.level(), ctx.pokemon(), ctx.reservation().getJobType(), ctx.pos());
    }

    /** Whether the job a step is for is still there, as the scan that found it sees it. */
    private static boolean jobInTheWorld(JobContext ctx) {
        return JobChecks.isJobStillThere(ctx.level(), ctx.reservation().getJobType(), ctx.pos());
    }

    /** A step that takes time and then has its effect, with no animation. */
    static Step of(String name, int ticks, Finish finish) {
        return new Step(name, species -> ticks, null, finish, ctx -> true, Step::jobInTheWorld);
    }

    /**
     * A step that plays an animation and has its effect as it lands. It takes {@code minTicks},
     * or longer if the worker needs it to settle and its animation to land.
     */
    static Step animated(String name, int minTicks, WorkAnimation animation, Finish finish) {
        return new Step(
                name,
                species -> WorkAnimations.forSpecies(animation, species).stepTicks(minTicks),
                animation,
                finish,
                ctx -> true,
                Step::jobInTheWorld);
    }

    /**
     * A step that only takes time (e.g. a pause), with no effect. It still ends the job if that
     * goes meanwhile: a furnace emptied during its smelt isn't waited out.
     */
    static Step pause(String name, ToIntFunction<String> length) {
        return new Step(name, length, null, ctx -> true, ctx -> true, Step::jobInTheWorld);
    }

    /** This step, skipped when it's reached if there's nothing for it to do. */
    Step onlyIf(java.util.function.Predicate<JobContext> needed) {
        return new Step(name, length, animation, onFinish, needed, isThere);
    }

    /**
     * This step, done even though its job's target is gone: it comes after a step that does
     * away with it (a chopped tree's replant). Its effect has to cope with what it finds.
     */
    Step outlivesItsTarget() {
        return new Step(name, length, animation, onFinish, isNeeded, ctx -> true);
    }

    /** A step's effect. Return false to end the job here (e.g. there turned out to be nothing to do). */
    @FunctionalInterface
    public interface Finish {
        boolean run(JobContext ctx);
    }

    /** Wrap a void effect as a step finish that always lets the job continue. */
    static Finish effect(java.util.function.Consumer<JobContext> action) {
        return ctx -> {
            action.accept(ctx);
            return true;
        };
    }
}
