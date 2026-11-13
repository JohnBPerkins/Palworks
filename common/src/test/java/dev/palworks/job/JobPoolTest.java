package dev.palworks.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobPool.JobKey;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The pool's jobs grouped by the suitabilities they need: a worker is offered exactly the ones it can do. */
class JobPoolTest {
    private static final Set<Suitability> FARMER = EnumSet.of(Suitability.GATHERING, Suitability.PLANTING);
    private static final Set<Suitability> LUMBERJACK = EnumSet.of(Suitability.LUMBERING);
    private static final Set<Suitability> NETHER_LUMBERJACK = EnumSet.of(Suitability.LUMBERING, Suitability.NETHER);
    private static final Set<Suitability> EVERYTHING = EnumSet.allOf(Suitability.class);

    private static final BlockPos CROP = new BlockPos(1, 64, 1);
    private static final BlockPos FARMLAND = new BlockPos(2, 64, 1);
    private static final BlockPos TREE = new BlockPos(8, 64, 8);
    private static final BlockPos NETHER_TREE = new BlockPos(9, 64, 8);

    private static Set<JobRecord> offeredTo(JobPool pool, Set<Suitability> suitabilities) {
        Set<JobRecord> jobs = new HashSet<>();
        for (Collection<JobRecord> group : pool.availableFor(suitabilities)) {
            jobs.addAll(group);
        }
        return jobs;
    }

    /** What the grouping must always match: every available job the worker has the suitabilities for. */
    private static Set<JobRecord> canDo(JobPool pool, Set<Suitability> suitabilities) {
        Set<JobRecord> jobs = new HashSet<>();
        for (JobRecord job : pool.available()) {
            if (job.canBeDoneWith(suitabilities)) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private static void assertGroupsMatch(JobPool pool) {
        for (Set<Suitability> worker :
                Set.of(FARMER, LUMBERJACK, NETHER_LUMBERJACK, EVERYTHING, Set.<Suitability>of())) {
            assertEquals(canDo(pool, worker), offeredTo(pool, worker), "jobs offered to a worker with " + worker);
        }
    }

    private static JobPool farmAndForest() {
        JobPool pool = new JobPool();
        pool.offer(JobRecord.of(JobType.HARVEST_CROPS, CROP, 0L));
        pool.offer(JobRecord.of(JobType.PLANT_SEEDS, FARMLAND, 0L));
        pool.offer(JobRecord.of(JobType.CHOP_TREE, TREE, 0L));
        pool.offer(JobRecord.of(JobType.CHOP_TREE, NETHER_TREE, 0L, NETHER_LUMBERJACK));
        return pool;
    }

    @Test
    void aWorkerIsOfferedOnlyTheJobsItHasEverySuitabilityFor() {
        JobPool pool = farmAndForest();
        assertEquals(2, offeredTo(pool, FARMER).size());
        assertEquals(1, offeredTo(pool, LUMBERJACK).size(), "not the nether tree: that needs Nether too");
        assertEquals(2, offeredTo(pool, NETHER_LUMBERJACK).size());
        assertEquals(4, offeredTo(pool, EVERYTHING).size());
        assertTrue(offeredTo(pool, Set.of()).isEmpty());
        assertGroupsMatch(pool);
    }

    @Test
    void aJobTakenOrRemovedIsNoLongerOffered() {
        JobPool pool = farmAndForest();
        JobRecord tree = pool.available().stream()
                .filter(j -> j.position().equals(TREE))
                .findFirst()
                .orElseThrow();
        pool.take(tree);
        pool.reserve(tree);
        assertTrue(offeredTo(pool, LUMBERJACK).isEmpty());
        assertGroupsMatch(pool);

        // Given back: offered again
        pool.release(JobKey.of(tree), 10L);
        assertEquals(1, offeredTo(pool, LUMBERJACK).size());
        assertGroupsMatch(pool);

        pool.remove(new JobKey(CROP, JobType.HARVEST_CROPS));
        pool.removeScanned(new JobKey(FARMLAND, JobType.PLANT_SEEDS));
        assertTrue(offeredTo(pool, FARMER).isEmpty());
        assertGroupsMatch(pool);
    }

    @Test
    void aJobReplacedByOneNeedingOtherSuitabilitiesMovesGroup() {
        // Same block and job type, found again needing Nether as well (the same key)
        JobPool pool = farmAndForest();
        pool.offer(JobRecord.of(JobType.CHOP_TREE, TREE, 5L, NETHER_LUMBERJACK));
        assertEquals(4, pool.available().size());
        assertTrue(offeredTo(pool, LUMBERJACK).isEmpty(), "the old one, offered to any lumberjack, is gone");
        assertEquals(2, offeredTo(pool, NETHER_LUMBERJACK).size());
        assertGroupsMatch(pool);
    }

    @Test
    void oldFollowUpsExpireButScannedJobsStay() {
        // Scanned jobs are the scanner's index's to take out; a pickup nobody took goes after a while
        JobPool pool = farmAndForest();
        pool.offer(JobRecord.followUp(JobType.COLLECT_DROPS, new BlockPos(3, 64, 1), 0L, null));
        pool.offer(JobRecord.followUp(JobType.COLLECT_DROPS, new BlockPos(4, 64, 1), 1000L, null));
        pool.expire(1000L, 500L);
        assertEquals(5, pool.available().size(), "the four scanned jobs and the young pickup");
        assertTrue(pool.available().stream().noneMatch(job -> job.position().equals(new BlockPos(3, 64, 1))));
        assertGroupsMatch(pool);
    }

    @Test
    void aSavedReservationIsClaimedForItsWorkerAndTakenOutOfTheAvailableJobs() {
        JobPool pool = farmAndForest(); // after a restart, the scan has found the crop again
        UUID worker = UUID.randomUUID();
        ReservationId saved = new ReservationId(CROP, JobType.HARVEST_CROPS, 0L);

        assertTrue(pool.adopt(saved, worker, 10L));
        assertTrue(pool.isReserved(JobKey.of(saved)));
        assertFalse(
                offeredTo(pool, FARMER).stream().anyMatch(job -> job.position().equals(CROP)), "not offered");
        assertGroupsMatch(pool);
        assertTrue(pool.adopt(saved, worker, 20L), "already its own");
    }

    @Test
    void aSavedReservationNotInThePoolYetIsClaimedToo() {
        JobPool pool = new JobPool(); // its worker reports in before the scan finds the job
        UUID worker = UUID.randomUUID();
        ReservationId saved = new ReservationId(TREE, JobType.CHOP_TREE, 0L);

        assertTrue(pool.adopt(saved, worker, 10L));
        assertTrue(pool.isReserved(JobKey.of(saved)));
    }

    @Test
    void anotherWorkersJobIsntClaimed() {
        JobPool pool = new JobPool();
        ReservationId saved = new ReservationId(CROP, JobType.HARVEST_CROPS, 0L);
        UUID first = UUID.randomUUID();
        assertTrue(pool.adopt(saved, first, 10L));

        assertFalse(pool.adopt(saved, UUID.randomUUID(), 10L), "the first keeps it");
        assertTrue(pool.reserved().iterator().next().isReservedBy(first));
    }
}
