package dev.palworks.worker;

import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent data for a Pokémon worker.
 * Stores: enabled state, assigned job and its reservation, progress through it, cooldowns and
 * path-failure tracking.
 */
public class WorkerState {
    // Path failures further apart than this start the count again
    private static final long PATH_FAILURE_RESET_MS = 5000;

    private boolean enabled;
    private JobType assignedJob;
    private ReservationId currentReservation;

    // Cooldowns per suitability type
    private final Map<Suitability, Long> cooldowns;

    // Path failure tracking for stuck detection
    private int pathFailCount;
    private long lastPathFailTime;

    // Progress through the current job's steps (see WorkerAI's JobHandler.steps). The step index
    // and its data are saved, so a paused or reloaded job carries on from the step it was on; ticks
    // within the step aren't, so that step restarts (with its animation) when the worker resumes.
    private int jobStep;
    private int stepTicks;
    private CompoundTag jobData;
    // Whether it has started walking to its current job (not saved). A walk that ends short of the
    // stand tile but within reach of a crop or tree still lets it work; before any walk it mustn't.
    private boolean walkedToJob;
    // Ticks spent stepping the last bit onto its stand tile after its walk ended just short (not saved)
    private int stepInTicks;

    public WorkerState() {
        this.enabled = true; // Owned Pokemon work by default; only an explicit disable turns this off
        this.assignedJob = null;
        this.currentReservation = null;
        this.cooldowns = new EnumMap<>(Suitability.class);
        this.pathFailCount = 0;
        this.lastPathFailTime = 0;
        this.jobStep = 0;
        this.stepTicks = 0;
        this.jobData = new CompoundTag();
    }

    // Enabled state
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Job assignment
    @Nullable
    public JobType getAssignedJob() {
        return assignedJob;
    }

    public void setAssignedJob(@Nullable JobType job) {
        this.assignedJob = job;
    }

    public boolean hasJob() {
        return assignedJob != null;
    }

    // Reservation
    @Nullable
    public ReservationId getCurrentReservation() {
        return currentReservation;
    }

    public void setCurrentReservation(@Nullable ReservationId reservation) {
        this.currentReservation = reservation;
    }

    /** Drop the current job, and any progress through it (capture that first if it's handed over). */
    public void clearReservation() {
        this.currentReservation = null;
        resetJobProgress();
    }

    // Cooldowns
    public void setCooldown(Suitability suitability, long cooldownTicks) {
        cooldowns.put(suitability, System.currentTimeMillis() + (cooldownTicks * 50)); // Convert ticks to ms
    }

    public boolean isOnCooldown(Suitability suitability) {
        Long cooldownEnd = cooldowns.get(suitability);
        if (cooldownEnd == null) return false;

        if (System.currentTimeMillis() < cooldownEnd) {
            return true;
        } else {
            cooldowns.remove(suitability);
            return false;
        }
    }

    public long getRemainingCooldown(Suitability suitability) {
        Long cooldownEnd = cooldowns.get(suitability);
        if (cooldownEnd == null) return 0;

        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining / 50); // Convert ms to ticks
    }

    // Path failure tracking
    public void recordPathFailure() {
        long now = System.currentTimeMillis();
        // Walking workers re-check this every tick, so one hiccup (bumping into a vine, a path
        // that briefly ends short) used to count as a failure on each of a few ticks in a row, and
        // three ticks was enough to give the job up. Count at most one failure per second, so it
        // takes a couple of seconds of really not getting anywhere.
        if (lastPathFailTime != 0 && now - lastPathFailTime < PATH_FAILURE_SPACING_MS) {
            return;
        }
        if (now - lastPathFailTime > PATH_FAILURE_RESET_MS) {
            pathFailCount = 0;
        }
        pathFailCount++;
        lastPathFailTime = now;
    }

    public void resetPathFailures() {
        pathFailCount = 0;
        lastPathFailTime = 0;
    }

    public int getPathFailCount() {
        return pathFailCount;
    }

    /**
     * Path failures that still count towards giving up: 0 once 5 seconds have passed since the last
     * one (the count itself is only reset at the next failure).
     */
    public int getRecentPathFailCount() {
        return System.currentTimeMillis() - lastPathFailTime > 5000 ? 0 : pathFailCount;
    }

    /** Path failures within 5 seconds of each other after which a worker gives its job up. */
    public static final int STUCK_AFTER_PATH_FAILURES = 3;
    /** Failures closer together than this count once (see recordPathFailure). */
    static final long PATH_FAILURE_SPACING_MS = 1000;

    public boolean isStuck() {
        return pathFailCount >= STUCK_AFTER_PATH_FAILURES;
    }

    // Job progress (steps)

    /** Start a job: from its first step, or from where another worker left it (handed over). */
    public void startJob(int step, @Nullable CompoundTag data) {
        this.jobStep = Math.max(0, step);
        this.stepTicks = 0;
        this.jobData = data != null ? data.copy() : new CompoundTag();
        this.walkedToJob = false;
        this.stepInTicks = 0;
    }

    public boolean hasWalkedToJob() {
        return walkedToJob;
    }

    public void setWalkedToJob(boolean walkedToJob) {
        this.walkedToJob = walkedToJob;
    }

    public int getStepInTicks() {
        return stepInTicks;
    }

    public void addStepInTick() {
        stepInTicks++;
    }

    public void resetStepIn() {
        stepInTicks = 0;
    }

    public void resetJobProgress() {
        startJob(0, null);
    }

    /** Index of the step the job is on (0 = the first). */
    public int getJobStep() {
        return jobStep;
    }

    /** Ticks spent on the current step so far. */
    public int getStepTicks() {
        return stepTicks;
    }

    public void addStepTick() {
        stepTicks++;
    }

    /** Restart the current step (e.g. the worker left its spot): its effect hasn't happened yet. */
    public void restartStep() {
        stepTicks = 0;
    }

    public void nextStep() {
        jobStep++;
        stepTicks = 0;
    }

    /** Whether the job is past its first step, i.e. something has already been done. */
    public boolean hasJobProgress() {
        return jobStep > 0;
    }

    /** Whether the worker is partway through the job, including the current step. */
    public boolean isWorking() {
        return jobStep > 0 || stepTicks > 0;
    }

    /** Data the job's steps leave for later steps (e.g. where to replant after a chop). */
    public CompoundTag getJobData() {
        return jobData;
    }

    // NBT Serialization
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        tag.putBoolean("enabled", enabled);
        if (assignedJob != null) {
            tag.putString("assignedJob", assignedJob.getId());
        }
        tag.putInt("jobStep", jobStep);
        tag.put("jobData", jobData.copy());

        // Serialize cooldowns
        CompoundTag cooldownsTag = new CompoundTag();
        for (Map.Entry<Suitability, Long> entry : cooldowns.entrySet()) {
            cooldownsTag.putLong(entry.getKey().getId(), entry.getValue());
        }
        tag.put("cooldowns", cooldownsTag);

        tag.putInt("pathFailCount", pathFailCount);
        tag.putLong("lastPathFailTime", lastPathFailTime);

        // Serialize current reservation
        if (currentReservation != null) {
            CompoundTag reservationTag = new CompoundTag();
            BlockPos pos = currentReservation.getStationPos();
            reservationTag.putInt("posX", pos.getX());
            reservationTag.putInt("posY", pos.getY());
            reservationTag.putInt("posZ", pos.getZ());
            reservationTag.putString("jobType", currentReservation.getJobType().getId());
            reservationTag.putLong("timestamp", currentReservation.getTimestamp());
            tag.put("currentReservation", reservationTag);
        }

        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        // Missing key: never toggled, so enabled (the default).
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled");

        if (tag.contains("assignedJob")) {
            String jobId = tag.getString("assignedJob");
            try {
                assignedJob = JobType.valueOf(jobId.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                assignedJob = null;
            }
        }

        // Deserialize cooldowns
        cooldowns.clear();
        if (tag.contains("cooldowns")) {
            CompoundTag cooldownsTag = tag.getCompound("cooldowns");
            for (String key : cooldownsTag.getAllKeys()) {
                Suitability suitability = Suitability.fromId(key);
                if (suitability != null) {
                    cooldowns.put(suitability, cooldownsTag.getLong(key));
                }
            }
        }

        pathFailCount = tag.getInt("pathFailCount");
        lastPathFailTime = tag.getLong("lastPathFailTime");

        // Deserialize current reservation
        if (tag.contains("currentReservation")) {
            CompoundTag reservationTag = tag.getCompound("currentReservation");

            BlockPos stationPos = new BlockPos(
                    reservationTag.getInt("posX"), reservationTag.getInt("posY"), reservationTag.getInt("posZ"));

            String jobTypeId = reservationTag.getString("jobType");
            long timestamp = reservationTag.getLong("timestamp");

            try {
                JobType jobType = JobType.valueOf(jobTypeId.toUpperCase(Locale.ROOT));
                currentReservation = new ReservationId(stationPos, jobType, timestamp);
            } catch (IllegalArgumentException e) {
                currentReservation = null;
            }
        } else {
            currentReservation = null;
        }

        // Job progress
        jobStep = Math.max(0, tag.getInt("jobStep"));
        jobData = tag.contains("jobData") ? tag.getCompound("jobData").copy() : new CompoundTag();
        stepTicks = 0;
    }

    public static WorkerState fromNBT(CompoundTag tag) {
        WorkerState state = new WorkerState();
        state.deserializeNBT(tag);
        return state;
    }

    /**
     * Get a debug status string for rendering above the Pokemon.
     */
    public String getDebugStatusString() {
        if (!enabled) {
            return "§7DISABLED";
        }

        if (currentReservation != null) {
            BlockPos pos = currentReservation.getStationPos();
            return String.format(
                    "§e%s §7[%d, %d, %d]", currentReservation.getJobType().name(), pos.getX(), pos.getY(), pos.getZ());
        }

        return "§aIDLE";
    }
}
