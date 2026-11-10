package dev.palworks.reservation;

import dev.palworks.job.JobType;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * Identifies a reservation for a job at a station.
 * Used to prevent multiple workers from claiming the same task.
 */
public class ReservationId {
    private final BlockPos stationPos;
    private final JobType jobType;
    // Game time (ticks) when it was taken or last refreshed. Game time, not the clock: the game
    // doesn't tick while paused, so a pause doesn't use up a reservation's time.
    private final long timestamp;

    /** @param gameTime the level's game time now ({@code level.getGameTime()}) */
    public ReservationId(BlockPos stationPos, JobType jobType, long gameTime) {
        this.stationPos = stationPos;
        this.jobType = jobType;
        this.timestamp = gameTime;
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
     * Whether more than {@code timeoutTicks} of game time have passed since it was taken.
     * @param gameTime the level's game time now
     */
    public boolean isExpired(long gameTime, long timeoutTicks) {
        return gameTime - timestamp > timeoutTicks;
    }

    /**
     * Whether its time is from later than now. Only a reservation saved by an older version, which
     * timed them by the clock (milliseconds since 1970, far beyond any world's game time), is.
     */
    public boolean isFromTheFuture(long gameTime) {
        return timestamp > gameTime;
    }

    // Identity is the (station, job) pair. The timestamp is deliberately excluded: it's
    // freshness metadata (for isExpired / refresh), not part of what makes two reservations
    // "the same job". Including it made every refreshed reservation compare unequal.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReservationId that = (ReservationId) o;
        return Objects.equals(stationPos, that.stationPos) && jobType == that.jobType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(stationPos, jobType);
    }

    @Override
    public String toString() {
        return "ReservationId{" + "station=" + stationPos + ", job=" + jobType + ", time=" + timestamp + '}';
    }
}
