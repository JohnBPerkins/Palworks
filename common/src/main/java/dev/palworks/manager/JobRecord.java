package dev.palworks.manager;

import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.util.Collections;
import java.util.EnumSet;
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
    private final Set<Suitability> requiredSuitabilities; // a worker needs all of them
    private final boolean followUp;

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

    private JobRecord(
            JobType type, BlockPos position, long discoveryTime, Set<Suitability> required, boolean followUp) {
        this.type = type;
        this.position = position.immutable();
        this.discoveryTime = discoveryTime;
        this.requiredSuitabilities = Collections.unmodifiableSet(EnumSet.copyOf(required));
        this.followUp = followUp;
    }

    /** A job found at {@code position}, needing its type's suitability. */
    public static JobRecord of(JobType type, BlockPos position, long gameTime) {
        return of(type, position, gameTime, EnumSet.of(type.getRequiredSuitability()));
    }

    /** A job found at {@code position} that needs other suitabilities than its type's (Nether work). */
    public static JobRecord of(JobType type, BlockPos position, long gameTime, Set<Suitability> required) {
        return new JobRecord(type, position, gameTime, required, false);
    }

    /**
     * A job that follows another (picking up a harvest's drops), offered first to
     * {@code suggestedWorker} if given. Follow-up jobs don't come from the chunk scan, so a rescan
     * never removes them.
     */
    public static JobRecord followUp(JobType type, BlockPos position, long gameTime, @Nullable UUID suggestedWorker) {
        JobRecord job = new JobRecord(type, position, gameTime, EnumSet.of(type.getRequiredSuitability()), true);
        job.reservedBy = suggestedWorker;
        return job;
    }

    public BlockPos position() {
        return position;
    }

    public JobType type() {
        return type;
    }

    public Set<Suitability> requiredSuitabilities() {
        return requiredSuitabilities;
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
