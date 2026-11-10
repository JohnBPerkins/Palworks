package dev.palworks.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Tests that ReservationId identity is the (station, job) pair and ignores the timestamp,
 * while the timestamp is still retained as freshness metadata.
 */
class ReservationIdTest {

    private static final BlockPos POS = new BlockPos(10, 64, -20);

    @Test
    void equalityIgnoresTimestamp() {
        ReservationId a = new ReservationId(POS, JobType.FUEL_FURNACE, 1_000L);
        ReservationId b = new ReservationId(POS, JobType.FUEL_FURNACE, 2_000L);
        assertEquals(a, b, "same station+job should be equal regardless of timestamp");
        assertEquals(a.hashCode(), b.hashCode(), "equal reservations must share a hashCode");
    }

    @Test
    void differsByPosition() {
        ReservationId a = new ReservationId(POS, JobType.FUEL_FURNACE, 1_000L);
        ReservationId other = new ReservationId(new BlockPos(11, 64, -20), JobType.FUEL_FURNACE, 1_000L);
        assertNotEquals(a, other);
    }

    @Test
    void differsByJobType() {
        ReservationId a = new ReservationId(POS, JobType.FUEL_FURNACE, 1_000L);
        ReservationId other = new ReservationId(POS, JobType.HARVEST_CROPS, 1_000L);
        assertNotEquals(a, other);
    }

    @Test
    void timestampIsRetained() {
        ReservationId a = new ReservationId(POS, JobType.MINE_RESOURCE, 12_345L);
        assertEquals(12_345L, a.getTimestamp());
    }

    @Test
    void expiresOnlyOnceMoreThanTheTimeoutHasPassedInGameTime() {
        ReservationId a = new ReservationId(POS, JobType.HARVEST_CROPS, 1_000L);
        assertFalse(a.isExpired(1_000L, 600), "just taken");
        assertFalse(a.isExpired(1_600L, 600), "exactly the timeout: not yet");
        assertTrue(a.isExpired(1_601L, 600), "past the timeout");
    }
}
