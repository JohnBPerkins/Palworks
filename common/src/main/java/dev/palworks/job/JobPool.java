package dev.palworks.job;

import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** A level's jobs: the ones waiting for a worker (available), and the ones a worker has (reserved). */
final class JobPool {
    private final Map<JobKey, JobRecord> available = new ConcurrentHashMap<>();
    // The same available jobs, grouped by the suitabilities they need, so a worker only goes
    // through the groups it has all of (availableFor): a lumberjack never looks at every crop on the
    // farm. Kept in step with `available` by the methods below; nothing else changes either.
    private final Map<Set<Suitability>, Map<JobKey, JobRecord>> availableByNeeds = new ConcurrentHashMap<>();
    private final Map<JobKey, JobRecord> reserved = new ConcurrentHashMap<>();

    /** Every job waiting for a worker (read only: change them through the pool's methods). */
    Collection<JobRecord> available() {
        return Collections.unmodifiableCollection(available.values());
    }

    /** The jobs waiting for a worker that one with these suitabilities can do, in groups. */
    List<Collection<JobRecord>> availableFor(Set<Suitability> suitabilities) {
        List<Collection<JobRecord>> groups = new ArrayList<>();
        availableByNeeds.forEach((needs, jobs) -> {
            if (!jobs.isEmpty() && suitabilities.containsAll(needs)) {
                groups.add(jobs.values());
            }
        });
        return groups;
    }

    private void putAvailable(JobKey key, JobRecord job) {
        JobRecord replaced = available.put(key, job);
        if (replaced != null && replaced != job) {
            unindex(key, replaced);
        }
        availableByNeeds
                .computeIfAbsent(job.requiredSuitabilities(), needs -> new ConcurrentHashMap<>())
                .put(key, job);
    }

    private void unindex(JobKey key, JobRecord job) {
        Map<JobKey, JobRecord> group = availableByNeeds.get(job.requiredSuitabilities());
        if (group != null) {
            group.remove(key, job);
        }
    }

    private void removeAvailable(JobKey key) {
        JobRecord removed = available.remove(key);
        if (removed != null) {
            unindex(key, removed);
        }
    }

    private void removeAvailableIf(Predicate<JobRecord> condition) {
        available.entrySet().removeIf(entry -> {
            if (!condition.test(entry.getValue())) {
                return false;
            }
            unindex(entry.getKey(), entry.getValue());
            return true;
        });
    }

    Collection<JobRecord> reserved() {
        return reserved.values();
    }

    boolean isReserved(JobKey key) {
        return reserved.containsKey(key);
    }

    /** Put a job in the pool for any worker (replacing one with the same key). */
    void offer(JobRecord job) {
        putAvailable(JobKey.of(job), job);
    }

    /** Take a job out of the pool, so no other worker gets it too. Does nothing for one not in it. */
    void take(JobRecord job) {
        JobKey key = JobKey.of(job);
        if (available.remove(key, job)) {
            unindex(key, job);
        }
    }

    /** Record that a worker has the job. */
    void reserve(JobRecord job) {
        reserved.put(JobKey.of(job), job);
    }

    /**
     * Release a job reservation (make it available again). A delivery is dropped instead: it's
     * only meaningful to the worker carrying the items, and back in the pool (at the top priority)
     * it would send other transporters to deliver nothing.
     */
    void release(JobKey key, long gameTime) {
        JobRecord job = reserved.remove(key);
        if (job == null || job.type() == JobType.DELIVER_ITEMS) {
            return;
        }
        job.release(gameTime);
        putAvailable(key, job);
    }

    /** Remove a job entirely, reserved or not (it's done, or gone). */
    void remove(JobKey key) {
        reserved.remove(key);
        removeAvailable(key);
    }

    /**
     * Remove an available job that came from a chunk scan. Reserved jobs are left to their worker,
     * and follow-up jobs (e.g. picking up drops after a harvest) don't come from the scan, so a
     * rescan not finding them says nothing about them.
     */
    void removeScanned(JobKey key) {
        JobRecord job = available.get(key);
        if (job != null && !job.isFollowUp() && !job.isHandedOver()) {
            removeAvailable(key);
        }
    }

    /**
     * If the worker has done part of its job (past its first step), store that progress on the job,
     * so whoever takes it next carries on from there instead of starting over. Call before the
     * worker's reservation (and with it, its progress) is cleared.
     */
    void captureProgress(WorkerState state, JobKey key) {
        JobRecord job = reserved.get(key);
        if (job != null && state.hasJobProgress()) {
            job.saveProgress(state.getJobStep(), state.getJobData());
        }
    }

    /**
     * Follow-up jobs (e.g. picking up a harvest's drops) don't come from the chunk scan, so a
     * rescan never removes them: drop the ones that are gone (the items were picked up or
     * despawned), or transporters keep being sent to them. Handed-over jobs are left alone: their
     * original target (a chopped tree) being gone is expected.
     */
    void dropGoneFollowUps(Level level) {
        removeAvailableIf(job -> job.isFollowUp()
                && !job.isHandedOver()
                && !JobChecks.isJobStillThere(level, job.type(), job.position()));
    }

    /**
     * Remove jobs that have been available longer than {@code maxAge} (game time, so a paused game
     * doesn't count: that used to drop every job, handed-over ones included, after a 5-minute pause).
     */
    void expire(long gameTime, long maxAge) {
        removeAvailableIf(job -> (gameTime - job.discoveryTime()) > maxAge);
    }

    /** Reserved jobs whose worker is no longer registered. */
    List<JobRecord> orphaned(WorkerRegistry workers) {
        List<JobRecord> orphans = new ArrayList<>();
        for (JobRecord job : reserved.values()) {
            if (job.reservedBy() != null && !workers.contains(job.reservedBy())) {
                orphans.add(job);
            }
        }
        return orphans;
    }

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
}
