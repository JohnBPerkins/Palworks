package dev.palworks.job.handler;

import dev.palworks.ai.WorkStance;
import dev.palworks.animation.WorkAnimation;
import dev.palworks.job.JobType;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Strategy for executing one {@link JobType} at the work site: a list of timed {@link Step}s.
 * Add a job type by giving it a handler in {@link JobHandlers#forJob} rather than editing WorkerAI.
 */
@FunctionalInterface
public interface JobHandler {
    /** The job's steps, in order. The shared code walks the worker there and runs them. */
    List<Step> steps();
    /** If true when the last step is done, start the steps again instead of finishing. */
    default boolean repeatAfterComplete(JobContext ctx) {
        return false;
    }
    /**
     * Where a worker of this species stands for the whole job: the stance its animation is
     * played from (the first, if it has more than one), or {@link WorkStance#NEAR} if it has
     * none.
     */
    default WorkStance stance(@Nullable String speciesId) {
        for (Step step : steps()) {
            WorkAnimation animation = step.animationFor(speciesId);
            if (animation != null) {
                return animation.stance();
            }
        }
        return WorkStance.NEAR;
    }
}
