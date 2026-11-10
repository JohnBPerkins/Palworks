package dev.palworks.job;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which jobs are within a worker's work range. */
class WorkRangeTest {
    private static final BlockPos WORKER = new BlockPos(100, 64, -200);

    @Test
    void jobsUpToTheEdgeAreInRange() {
        assertTrue(WorkRange.contains(WORKER, WORKER));
        assertTrue(WorkRange.contains(WORKER, WORKER.offset(WorkRange.HORIZONTAL, 0, -WorkRange.HORIZONTAL)));
        assertTrue(WorkRange.contains(WORKER, WORKER.offset(-WorkRange.HORIZONTAL, WorkRange.VERTICAL, 0)));
        assertTrue(WorkRange.contains(WORKER, WORKER.offset(0, -WorkRange.VERTICAL, WorkRange.HORIZONTAL)));
    }

    @Test
    void jobsPastTheEdgeAreNot() {
        assertFalse(WorkRange.contains(WORKER, WORKER.offset(WorkRange.HORIZONTAL + 1, 0, 0)));
        assertFalse(WorkRange.contains(WORKER, WORKER.offset(0, 0, -WorkRange.HORIZONTAL - 1)));
        assertFalse(WorkRange.contains(WORKER, WORKER.offset(0, WorkRange.VERTICAL + 1, 0)));
        assertFalse(WorkRange.contains(WORKER, WORKER.offset(30_000_000, 0, 0)), "the other side of the world");
    }

    @Test
    void aWorkerWithNoPositionYetHasNoJobsInRange() {
        assertFalse(WorkRange.contains(null, WORKER));
    }
}
