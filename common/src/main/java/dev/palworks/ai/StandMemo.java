package dev.palworks.ai;

import dev.palworks.job.JobType;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A worker's stand tile for the job it's on, remembered from one tick to the next while nothing
 * it was worked out from has changed: the job, the worker's stance and size, where the worker is
 * (the nearest tile it fits on is the one), and the blocks round the job. A worker at its work
 * stands still, so all but a few of the ticks it's there ask {@link WorkPositions#standingPosFor}
 * for the answer it gave the tick before, and working that out again each time (every tile round
 * the job tested for the worker's whole body) was a fifth to a third of what Palworks cost the
 * server in the stress test.
 *
 * <p>Only for the worker's own tick (WorkerAI): assigning a job asks {@link WorkPositions} itself.
 */
public final class StandMemo {
    private StandMemo() {}

    /**
     * How long a tile is remembered, in ticks, before it's worked out again whatever has happened:
     * for what no block change tells of (a shulker box's lid opening, a datapack reload changing
     * its stance's aim).
     */
    public static final int KEPT_TICKS = 20;

    /**
     * What a stand tile was worked out from, and the tile ({@code null}: nowhere to stand).
     *
     * @param at     where the worker was
     * @param reach  how far from {@code target}, across, a block can be for the tile to depend on it
     */
    private record Memo(
            Level level,
            BlockPos target,
            JobType jobType,
            WorkStance stance,
            Vec3 at,
            float width,
            float height,
            long madeAt,
            int reach,
            @Nullable BlockPos stand) {

        @SuppressWarnings("ReferenceEquality") // the same level, not one like it
        boolean isFor(Level level, BlockPos target, JobType jobType, Mob entity, WorkStance stance, long now) {
            return level == this.level
                    && jobType == this.jobType
                    && now >= madeAt
                    && now - madeAt < KEPT_TICKS
                    && entity.getBbWidth() == width
                    && entity.getBbHeight() == height
                    && target.equals(this.target)
                    && entity.position().equals(at)
                    && stance.equals(this.stance);
        }

        /** Whether the tile may depend on the block here, at any height. */
        @SuppressWarnings("ReferenceEquality") // the same level, not one like it
        boolean isNear(Level level, BlockPos pos) {
            return level == this.level
                    && Math.abs(pos.getX() - target.getX()) <= reach
                    && Math.abs(pos.getZ() - target.getZ()) <= reach;
        }
    }

    // Each worker's last. Block changes can come from other threads (see JobManager.onBlockStateChange)
    private static final Map<Mob, Memo> MEMOS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * {@link WorkPositions#standingPosFor}, from memory if nothing it depends on has changed since
     * this worker last asked. A pickup's is always worked out: it goes by the items lying there,
     * which come and go without a block changing.
     */
    @Nullable
    public static BlockPos standingPosFor(
            Level level, BlockPos targetPos, JobType jobType, Mob entity, WorkStance stance) {
        if (jobType == JobType.COLLECT_DROPS) {
            return WorkPositions.standingPosFor(level, targetPos, jobType, entity, stance);
        }
        long now = level.getGameTime();
        Memo memo = MEMOS.get(entity);
        if (memo != null && memo.isFor(level, targetPos, jobType, entity, stance, now)) {
            return memo.stand();
        }
        BlockPos stand = WorkPositions.standingPosFor(level, targetPos, jobType, entity, stance);
        MEMOS.put(
                entity,
                new Memo(
                        level,
                        targetPos.immutable(),
                        jobType,
                        stance,
                        entity.position(),
                        entity.getBbWidth(),
                        entity.getBbHeight(),
                        now,
                        WorkPositions.standReach(entity, stance),
                        stand));
        return stand;
    }

    /** A block changed: the stand tiles that may have depended on it are forgotten. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        MEMOS.values().removeIf(memo -> memo.isNear(level, pos));
    }
}
