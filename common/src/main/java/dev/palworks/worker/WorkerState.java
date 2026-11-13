package dev.palworks.worker;

import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent data for a Pokémon worker.
 * Stores: enabled state, assigned job and its reservation, progress through it, and path-failure
 * tracking.
 */
public class WorkerState {
    // Path failures further apart than this start the count again
    private static final long PATH_FAILURE_RESET_MS = 5000;

    /** The best mood a worker can be in, which a new one starts at. */
    public static final double MAX_MOOD = 100;

    private boolean enabled;
    private JobType assignedJob;
    private ReservationId currentReservation;

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

    // Fullness used up but not yet taken off (see dev.palworks.hunger.Hunger), in points: whole
    // points come off as they build up. Saved, so work done just before a reload still counts.
    private double fullnessOwed;
    // Game time until which an empty worker rests after a job before taking another (not saved: a
    // reload just ends its rest), so it gets through work at its slower speed
    private long restUntil;

    // Sleep (see dev.palworks.sleep.Sleep). Saved, so a reload in the night doesn't wake it, or lose
    // how it slept. Whether it's asleep (in a bed or not), and how long it has slept in each this time
    private boolean asleep;
    private long bedSleepTicks;
    private long groundSleepTicks;
    // It slept without a bed: slower until it next sleeps
    private boolean tired;
    // Whether it's asleep in a bed (its SLEEP job), so it knows it's lost its bed if the job goes
    private boolean inBed;
    // Game time until which it stays awake, after something woke it in the night (hurt, its bed
    // broken, recalled). Saved, so a recall and send-out doesn't put it straight back to sleep.
    private long wokenUntil;
    // Ticks it's been idle in its bedtime without being put to bed (not saved)
    private int bedtimeIdleTicks;
    // The end of the leg it's walking on its way home, to a trough or bed it couldn't get to from
    // where it was, and when it set off on it (see JobAssigner.headHome). Not saved: a reload just
    // has it look again.
    @Nullable
    private BlockPos homeward;

    private long homewardSince;

    // Mood (see dev.palworks.mood.Mood), 0 to MAX_MOOD, and whether it's on strike: it reached 0 and
    // hasn't come back up enough yet. Saved, so neither is lost in the PC or a reload.
    private double mood = MAX_MOOD;
    private boolean striking;

    public WorkerState() {
        this.enabled = true; // Owned Pokemon work by default; only an explicit disable turns this off
        this.assignedJob = null;
        this.currentReservation = null;
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

    /** Fullness used up but not yet taken off its Pokémon, in points (see Hunger). */
    public double getFullnessOwed() {
        return fullnessOwed;
    }

    public void setFullnessOwed(double fullnessOwed) {
        this.fullnessOwed = Math.max(0, fullnessOwed);
    }

    /** Game time an empty worker rests until before taking another job (0 for none). */
    public long getRestUntil() {
        return restUntil;
    }

    public void setRestUntil(long restUntil) {
        this.restUntil = restUntil;
    }

    /** Whether it's asleep (in a bed, or wherever it is). */
    public boolean isAsleep() {
        return asleep;
    }

    public void setAsleep(boolean asleep) {
        this.asleep = asleep;
    }

    /** Ticks it has slept in a bed, this time. */
    public long getBedSleepTicks() {
        return bedSleepTicks;
    }

    /** Ticks it has slept without one, this time. */
    public long getGroundSleepTicks() {
        return groundSleepTicks;
    }

    /** It slept another tick, in a bed or not. */
    public void addSleepTick(boolean inBed) {
        if (inBed) {
            bedSleepTicks++;
        } else {
            groundSleepTicks++;
        }
    }

    /** Forget how it slept (it's woken up). */
    public void resetSleepTicks() {
        bedSleepTicks = 0;
        groundSleepTicks = 0;
    }

    /** Whether it's Tired: it last slept without a bed. */
    public boolean isTired() {
        return tired;
    }

    public void setTired(boolean tired) {
        this.tired = tired;
    }

    /** Its mood, 0 to {@link #MAX_MOOD}. */
    public double getMood() {
        return mood;
    }

    public void setMood(double mood) {
        this.mood = Math.max(0, Math.min(MAX_MOOD, mood));
    }

    /** Whether it's on strike: its mood reached 0, and it won't work until it's back up a way. */
    public boolean isStriking() {
        return striking;
    }

    public void setStriking(boolean striking) {
        this.striking = striking;
    }

    /** Whether it's asleep in a bed (its SLEEP job). */
    public boolean isInBed() {
        return inBed;
    }

    public void setInBed(boolean inBed) {
        this.inBed = inBed;
    }

    /** Game time until which it stays awake, after being woken in the night (0 if it wasn't). */
    public long getWokenUntil() {
        return wokenUntil;
    }

    public void setWokenUntil(long wokenUntil) {
        this.wokenUntil = wokenUntil;
    }

    /** Ticks it's been idle in its bedtime without going to bed. */
    public int getBedtimeIdleTicks() {
        return bedtimeIdleTicks;
    }

    public void addBedtimeIdleTick() {
        bedtimeIdleTicks++;
    }

    /** The end of the leg it's walking on its way home, or null if it isn't heading home. */
    @Nullable
    public BlockPos getHomeward() {
        return homeward;
    }

    /** When it set off on its leg home. */
    public long getHomewardSince() {
        return homewardSince;
    }

    /** Set off home on a leg ending at {@code leg} (null: it isn't heading home), at game time {@code now}. */
    public void setHomeward(@Nullable BlockPos leg, long now) {
        this.homeward = leg;
        this.homewardSince = now;
    }

    public void resetBedtimeIdleTicks() {
        bedtimeIdleTicks = 0;
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

        tag.putInt("pathFailCount", pathFailCount);
        tag.putLong("lastPathFailTime", lastPathFailTime);
        if (fullnessOwed > 0) {
            tag.putDouble("fullnessOwed", fullnessOwed);
        }
        if (asleep) {
            tag.putBoolean("asleep", true);
        }
        if (bedSleepTicks > 0) {
            tag.putLong("bedSleepTicks", bedSleepTicks);
        }
        if (groundSleepTicks > 0) {
            tag.putLong("groundSleepTicks", groundSleepTicks);
        }
        if (tired) {
            tag.putBoolean("tired", true);
        }
        if (inBed) {
            tag.putBoolean("inBed", true);
        }
        if (wokenUntil > 0) {
            tag.putLong("wokenUntil", wokenUntil);
        }
        if (mood < MAX_MOOD) {
            tag.putDouble("mood", mood);
        }
        if (striking) {
            tag.putBoolean("striking", true);
        }

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

        pathFailCount = tag.getInt("pathFailCount");
        lastPathFailTime = tag.getLong("lastPathFailTime");
        // Missing: none owed (saved before hunger, or nothing was)
        fullnessOwed = Math.max(0, tag.getDouble("fullnessOwed"));
        // Missing: awake and rested (saved before sleep, or nothing to save)
        asleep = tag.getBoolean("asleep");
        bedSleepTicks = Math.max(0, tag.getLong("bedSleepTicks"));
        groundSleepTicks = Math.max(0, tag.getLong("groundSleepTicks"));
        tired = tag.getBoolean("tired");
        inBed = tag.getBoolean("inBed");
        wokenUntil = Math.max(0, tag.getLong("wokenUntil"));
        // Missing: in the best of moods (saved before mood, or nothing to save)
        setMood(tag.contains("mood") ? tag.getDouble("mood") : MAX_MOOD);
        striking = tag.getBoolean("striking");

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
