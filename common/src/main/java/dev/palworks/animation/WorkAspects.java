package dev.palworks.animation;

import dev.palworks.job.JobType;
import dev.palworks.worker.WorkActivity;
import java.util.Locale;
import java.util.Set;

/**
 * The aspects that tell Cobblemon's posers what a worker is doing: while it's at a job, doing it,
 * it has {@value #WORKING} and one for the job ({@code palworks-harvest-crops},
 * {@code palworks-chop-tree}, ...). A resource pack can then animate the species it ships work
 * animations for straight from their poser JSON, with a pose condition such as
 * {@code "condition": "q.has_aspect('palworks-harvest-crops')"}; the rest go on with just the
 * placeholder move effects (see {@link WorkAnimation}). Pure logic, so it's unit-tested;
 * {@link WorkAspectSync} gives them to Cobblemon.
 *
 * <p>A worker that stops working keeps them until it has stopped for {@link #OFF_DELAY_TICKS}, so
 * the gap between one job and the next, or between furnace cycles, doesn't flicker its pose.
 */
public final class WorkAspects {
    /** A worker at a job, doing it. */
    public static final String WORKING = "palworks-working";
    /** How long a worker has to have stopped working before its aspects go. */
    public static final int OFF_DELAY_TICKS = 10;

    private WorkAspects() {}

    /** The aspects of a worker doing this job. */
    public static Set<String> forJob(JobType job) {
        return Set.of(WORKING, "palworks-" + job.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /**
     * The aspects a worker should show now.
     *
     * @param shown       what it shows now
     * @param lastWorking the game time it was last working (only used when it isn't now)
     * @param activity    what it's doing now
     * @param gameTime    the game time now
     */
    public static Set<String> wanted(Set<String> shown, long lastWorking, WorkActivity activity, long gameTime) {
        if (activity.phase() == WorkActivity.Phase.WORKING && activity.job() != null) {
            return forJob(activity.job());
        }
        return gameTime - lastWorking < OFF_DELAY_TICKS ? shown : Set.of();
    }
}
