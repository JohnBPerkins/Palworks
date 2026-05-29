package dev.palworks.reservation;

import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * Identifies a reservation for a job at a station.
 * Used to prevent multiple workers from claiming the same task.
 */
public class ReservationId {
    private final BlockPos stationPos;
    private final JobType jobType;
    private final long timestamp;

    public ReservationId(BlockPos stationPos, JobType jobType, long timestamp) {
        this.stationPos = stationPos;
        this.jobType = jobType;
        this.timestamp = timestamp;
    }

    public ReservationId(BlockPos stationPos, JobType jobType) {
        this(stationPos, jobType, System.currentTimeMillis());
    }

    public BlockPos getStationPos() {
        return stationPos;
    }

    public JobType getJobType() {
        return jobType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Check if this reservation has expired.
     * @param timeoutMillis How long before a reservation expires
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - timestamp > timeoutMillis;
    }

    // Identity is the (station, job) pair. The timestamp is deliberately excluded: it's
    // freshness metadata (for isExpired / refresh), not part of what makes two reservations
    // "the same job". Including it made every refreshed reservation compare unequal.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReservationId that = (ReservationId) o;
        return Objects.equals(stationPos, that.stationPos) &&
                jobType == that.jobType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(stationPos, jobType);
    }

    @Override
    public String toString() {
        return "ReservationId{" +
                "station=" + stationPos +
                ", job=" + jobType +
                ", time=" + timestamp +
                '}';
    }
}
