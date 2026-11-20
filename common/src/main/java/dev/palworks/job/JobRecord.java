package dev.palworks.job;

import dev.palworks.suitability.Suitability;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * A discovered job: what it is and where (fixed), plus who has it and how far it got (which
 * change as workers take, drop and hand it over). Its priority comes from its {@link JobType}.
 */
public final class JobRecord {
    private final BlockPos position;
    private final JobType type;
    // A worker needs all of them. The EnumSet itself is kept as well as the read-only view handed
    // out, so canBeDoneWith is a bit test: containsAll against the view checks them one at a time.
    private final Set<Suitability> required;
    private final Set<Suitability> requiredSuitabilities;
    private final boolean followUp;
    // The lowest work level, in its type's own skill, that can do it (a big tree: see TreeSize)
    private final int minLevel;

    private long discoveryTime; // Game time it was found (or handed over)

    // The worker that has it. For a follow-up job still in the pool, the worker it's offered to
    // first (see followUp): scoring favours that one.
    @Nullable
    private UUID reservedBy;

    // Progress left by a previous worker (handed over): the step to carry on from, and the job's
    // data (e.g. where to replant after a chop). 0 / null for a fresh job.
    private int resumeStep;

    @Nullable
    private CompoundTag resumeData;

    // The workers that found it out of reach (a pickup: see JobAssigner.assignFirstReachable). Kept
    // on the job, not in their own memory of what they couldn't reach: that's wiped whenever a
    // block near them changes, which on a busy farm is all the time.
    private final Set<UUID> outOfReachFor = new HashSet<>();

    private JobRecord(
            JobType type,
            BlockPos position,
            long discoveryTime,
            Set<Suitability> required,
            boolean followUp,
            int minLevel) {
        this.type = type;
        this.position = position.immutable();
        this.discoveryTime = discoveryTime;
        this.required = EnumSet.copyOf(required);
        this.requiredSuitabilities = Collections.unmodifiableSet(this.required);
        this.followUp = followUp;
        this.minLevel = minLevel;
    }

    /** A job found at {@code position}, needing its type's suitability. */
    public static JobRecord of(JobType type, BlockPos position, long gameTime) {
        return of(type, position, gameTime, type.requiredSuitabilities());
    }

    /** A job found at {@code position} that needs other suitabilities than its type's (Nether work). */
    public static JobRecord of(JobType type, BlockPos position, long gameTime, Set<Suitability> required) {
        return of(type, position, gameTime, required, 1);
    }

    /**
     * {@link #of(JobType, BlockPos, long, Set)}, for a job only a worker of {@code minLevel} or more
     * in its type's skill can do.
     */
    public static JobRecord of(
            JobType type, BlockPos position, long gameTime, Set<Suitability> required, int minLevel) {
        return new JobRecord(type, position, gameTime, required, false, minLevel);
    }

    /**
     * A job that follows another (picking up a harvest's drops), offered first to
     * {@code suggestedWorker} if given. Follow-up jobs don't come from the chunk scan, so a rescan
     * never removes them.
     */
    public static JobRecord followUp(JobType type, BlockPos position, long gameTime, @Nullable UUID suggestedWorker) {
        return followUp(type, position, gameTime, suggestedWorker, type.requiredSuitabilities());
    }

    /** {@link #followUp(JobType, BlockPos, long, UUID)}, needing other suitabilities than its type's. */
    public static JobRecord followUp(
            JobType type, BlockPos position, long gameTime, @Nullable UUID suggestedWorker, Set<Suitability> required) {
        JobRecord job = new JobRecord(type, position, gameTime, required, true, 1);
        job.reservedBy = suggestedWorker;
        return job;
    }

    public BlockPos position() {
        return position;
    }

    /** This worker found no way to it: it isn't offered it again. */
    void markOutOfReachFor(UUID worker) {
        outOfReachFor.add(worker);
    }

    boolean isOutOfReachFor(UUID worker) {
        return outOfReachFor.contains(worker);
    }

    public JobType type() {
        return type;
    }

    public Set<Suitability> requiredSuitabilities() {
        return requiredSuitabilities;
    }

    /** Whether a worker with these suitabilities has every one this job needs. */
    public boolean canBeDoneWith(Set<Suitability> suitabilities) {
        return suitabilities.containsAll(required);
    }

    /** The lowest work level, in its type's own skill, that can do it: 1 unless the job says more. */
    public int minLevel() {
        return minLevel;
    }

    /** Whether a worker's work levels (by skill) reach what this job asks for in its type's own skill. */
    public boolean canBeDoneAt(Map<Suitability, Integer> levels) {
        Suitability skill = type.getRequiredSuitability();
        return minLevel <= 1 || skill == null || levels.getOrDefault(skill, 0) >= minLevel;
    }

    /** 0-10, higher first. */
    public int priority() {
        return type.priority();
    }

    public boolean isFollowUp() {
        return followUp;
    }

    public long discoveryTime() {
        return discoveryTime;
    }

    @Nullable
    public UUID reservedBy() {
        return reservedBy;
    }

    /** Whether it's reserved by (or, still in the pool, offered first to) this worker. */
    public boolean isReservedBy(UUID worker) {
        return worker.equals(reservedBy);
    }

    void reserveFor(UUID worker) {
        this.reservedBy = worker;
    }

    /**
     * Back to the pool, free for anyone. A handed-over job counts as found now, so the pool's
     * expiry doesn't drop it straight away.
     */
    void release(long gameTime) {
        this.reservedBy = null;
        if (isHandedOver()) {
            this.discoveryTime = gameTime;
        }
    }

    /** Store how far a worker got, so whoever takes it next carries on from there. */
    void saveProgress(int step, CompoundTag data) {
        this.resumeStep = step;
        this.resumeData = data.copy();
    }

    /** Whether a previous worker already did part of this job. */
    public boolean isHandedOver() {
        return resumeStep > 0;
    }

    public int resumeStep() {
        return resumeStep;
    }

    @Nullable
    public CompoundTag resumeData() {
        return resumeData;
    }
}
