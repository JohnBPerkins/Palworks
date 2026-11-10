package dev.palworks.manager;

import dev.palworks.job.JobType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** What the last scan of one chunk found, so it isn't rescanned every tick. */
final class ChunkJobCache {
    private final long scanTime;
    private final Map<JobType, List<BlockPos>> jobs = new EnumMap<>(JobType.class);
    private final List<BlockPos> chests = new ArrayList<>(); // delivery targets
    private boolean dirty;

    ChunkJobCache(long scanTime) {
        this.scanTime = scanTime;
    }

    /** Game time of the scan. */
    long scanTime() {
        return scanTime;
    }

    /** Job positions found, by type. */
    Map<JobType, List<BlockPos>> jobs() {
        return jobs;
    }

    void addJob(JobType type, BlockPos pos) {
        jobs.computeIfAbsent(type, k -> new ArrayList<>()).add(pos);
    }

    /** Chests seen in the scan. */
    List<BlockPos> chests() {
        return chests;
    }

    void addChest(BlockPos pos) {
        chests.add(pos);
    }

    /** Whether something in the chunk changed since, so it needs scanning again. */
    boolean isDirty() {
        return dirty;
    }

    void markDirty() {
        dirty = true;
    }
}
