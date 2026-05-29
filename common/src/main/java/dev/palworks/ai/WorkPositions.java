package dev.palworks.ai;

import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the exact tile a worker should stand on to perform a job, separate from the block the
 * job acts on. For a directional station (furnace, smoker, blast furnace, chest) that's the tile in
 * front of its facing — so the worker is correctly positioned for interaction animations/effects.
 * For non-directional targets (crops, ore, logs) it's the nearest standable adjacent tile.
 *
 * <p>Stateless: callers (JobManager at assignment, WorkerAI each tick) recompute the same position
 * from the world + the entity, so nothing is persisted.
 */
public final class WorkPositions {
    private WorkPositions() {}

    /**
     * The tile the worker should stand on for this job, or {@code null} if there's no valid spot.
     * Whether that tile is actually reachable is confirmed separately by the caller's path check.
     */
    @Nullable
    public static BlockPos standingPosFor(Level level, BlockPos targetPos, JobType jobType, Mob entity) {
        int distance = reachDistanceFor(entity);

        Direction facing = stationFacing(level, targetPos);
        if (facing != null) {
            // Directional station: prefer standing a gap back from the face (not flush against it),
            // but fall back closer if that's blocked. Try the ideal distance down to 2, requiring a
            // clear lane to the block and a standable tile; if none work, use the tile directly in
            // front (distance 1) and let the caller's path check validate it.
            for (int d = distance; d >= 2; d--) {
                boolean laneClear = true;
                for (int i = 1; i < d; i++) {
                    if (!isPassable(level, targetPos.relative(facing, i))) {
                        laneClear = false;
                        break;
                    }
                }
                if (laneClear && isStandable(level, targetPos.relative(facing, d))) {
                    return targetPos.relative(facing, d);
                }
            }
            return targetPos.relative(facing, 1);
        }

        // Non-directional (crops, ore, logs, brewing stand, ...): first standable tile at `distance`
        // in a cardinal direction. Orientation doesn't matter for these (yet).
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = targetPos.relative(dir, distance);
            if (isStandable(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * How many blocks in front of the worked block the Cobblemon should stand.
     *
     * <p>Rough first cut — <b>tune in-game</b>. Scales with the entity's (already size-scaled)
     * hitbox width, clamped to [2, 4], so a larger Cobblemon stands further back and its animation
     * lines up with the block; a small one stays at 2 (a one-block gap, not flush against it).
     */
    public static int reachDistanceFor(Mob entity) {
        // Base of 2 leaves a one-block gap in front of the station (not flush); scales further for
        // larger Cobblemon so their animation lines up. Clamped to [2, 4].
        int d = 2 + (int) Math.floor(entity.getBbWidth() / 2.0);
        return Math.max(2, Math.min(4, d));
    }

    /** The front-facing direction of a directional station block, or null if it isn't directional. */
    @Nullable
    private static Direction stationFacing(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    /** Feet + head space clear (no floor requirement) - i.e. not a wall blocking the lane. */
    private static boolean isPassable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    /** A tile is standable if feet + head space are clear and there's a solid floor below. */
    public static boolean isStandable(Level level, BlockPos pos) {
        boolean feetClear = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        boolean headClear = level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
        boolean solidFloor = !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
        return feetClear && headClear && solidFloor;
    }
}
