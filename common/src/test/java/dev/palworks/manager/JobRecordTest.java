package dev.palworks.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class JobRecordTest {

    private static final BlockPos POS = new BlockPos(4, 64, -9);
    private static final UUID WORKER = UUID.randomUUID();

    @Test
    void aFoundJobNeedsItsTypesSuitabilityAndHasItsPriority() {
        JobRecord job = JobRecord.of(JobType.HARVEST_CROPS, POS, 100L);
        assertEquals(Set.of(Suitability.GATHERING), job.requiredSuitabilities());
        assertEquals(JobType.HARVEST_CROPS.priority(), job.priority());
        assertEquals(100L, job.discoveryTime());
        assertFalse(job.isFollowUp());
        assertNull(job.reservedBy());
    }

    @Test
    void netherWorkCanNeedOtherSuitabilities() {
        JobRecord job = JobRecord.of(JobType.CHOP_TREE, POS, 0L, EnumSet.of(Suitability.LUMBERING, Suitability.NETHER));
        assertEquals(Set.of(Suitability.LUMBERING, Suitability.NETHER), job.requiredSuitabilities());
        assertThrows(
                UnsupportedOperationException.class,
                () -> job.requiredSuitabilities().add(Suitability.MINING));
    }

    @Test
    void positionIsCopiedSoAMutableScanPositionCantMoveIt() {
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(1, 2, 3);
        JobRecord job = JobRecord.of(JobType.GET_SEEDS, scan, 0L);
        scan.set(9, 9, 9);
        assertEquals(new BlockPos(1, 2, 3), job.position());
    }

    @Test
    void aFollowUpIsOfferedToItsSuggestedWorkerFirst() {
        JobRecord job = JobRecord.followUp(JobType.COLLECT_DROPS, POS, 0L, WORKER);
        assertTrue(job.isFollowUp());
        assertTrue(job.isReservedBy(WORKER));
        assertFalse(job.isReservedBy(UUID.randomUUID()));
    }

    @Test
    void releasingAFreshJobKeepsItsDiscoveryTime() {
        JobRecord job = JobRecord.of(JobType.CHOP_TREE, POS, 100L);
        job.reserveFor(WORKER);
        job.release(500L);
        assertNull(job.reservedBy());
        assertEquals(100L, job.discoveryTime());
    }

    @Test
    void releasingAHandedOverJobCountsItAsFoundNow() {
        JobRecord job = JobRecord.of(JobType.CHOP_TREE, POS, 100L);
        job.reserveFor(WORKER);
        CompoundTag data = new CompoundTag();
        data.putString("sapling", "minecraft:oak_sapling");
        job.saveProgress(2, data);
        data.putString("sapling", "changed afterwards");
        job.release(500L);

        assertTrue(job.isHandedOver());
        assertEquals(2, job.resumeStep());
        assertEquals("minecraft:oak_sapling", job.resumeData().getString("sapling"));
        assertEquals(500L, job.discoveryTime());
    }
}
