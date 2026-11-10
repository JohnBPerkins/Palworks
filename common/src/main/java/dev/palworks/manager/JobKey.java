package dev.palworks.manager;

import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import net.minecraft.core.BlockPos;

/**
 * What identifies a job: where it is and what it is. Keyed by position AND type, because several
 * jobs can share a block, e.g. chopping a tree and picking up its drops both happen at its base.
 * Keyed by position alone, one overwrote the other (a fainted lumberjack's half-done chop, still
 * needing its replant, was lost that way).
 */
record JobKey(BlockPos pos, JobType type) {
    static JobKey of(JobRecord job) {
        return new JobKey(job.position(), job.type());
    }

    static JobKey of(ReservationId reservation) {
        return new JobKey(reservation.getStationPos(), reservation.getJobType());
    }
}
