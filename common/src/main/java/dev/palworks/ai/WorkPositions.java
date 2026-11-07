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

        // Non-directional (crops, ore, logs, brewing stand, ...): the nearest ring, from `distance`
        // out, that has a tile the worker fits on; in that ring, the tile nearest the worker. Rings
        // are square (cardinal and diagonal tiles), and each tile is also tried one block up and
        // down so slopes work. Real trees need the outer rings: an oak's lowest leaves hang at head
        // height out to 2 blocks from the trunk, so nothing at distance 2 has room to stand.
        for (int d = distance; d <= distance + EXTRA_RINGS; d++) {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos candidate : ring(targetPos, d)) {
                for (int dy : SLOPE_STEPS) {
                    BlockPos spot = candidate.above(dy);
                    if (!fits(level, spot, entity)) {
                        continue;
                    }
                    // Ties keep ring order (a fixed direction order), so the choice doesn't flip
                    // between equally near tiles as the worker walks (this is recomputed each tick)
                    double distSq = entity.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                    if (distSq < bestDistSq - 1e-6) {
                        best = spot;
                        bestDistSq = distSq;
                    }
                    break; // one height per tile: the first that works, level ground first
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** How many rings beyond the reach distance to look for a place to stand. */
    private static final int EXTRA_RINGS = 3;
    /** Heights tried for each tile: level first, then one up and one down (slopes). */
    private static final int[] SLOPE_STEPS = {0, 1, -1};

    /** The tiles at Chebyshev distance {@code d} around {@code center}, cardinals first, on its Y. */
    private static java.util.List<BlockPos> ring(BlockPos center, int d) {
        java.util.List<BlockPos> tiles = new java.util.ArrayList<>(8 * d);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            tiles.add(center.relative(dir, d));
        }
        for (int dx = -d; dx <= d; dx++) {
            for (int dz = -d; dz <= d; dz++) {
                boolean onRing = Math.max(Math.abs(dx), Math.abs(dz)) == d;
                boolean cardinal = (dx == 0) != (dz == 0);
                if (onRing && !cardinal) {
                    tiles.add(center.offset(dx, 0, dz));
                }
            }
        }
        return tiles;
    }

    /** Blocks of clear space the worker needs above its feet: its hitbox height, rounded up. */
    public static int headroomFor(Mob entity) {
        return Math.max(1, (int) Math.ceil(entity.getBbHeight() - 1e-3));
    }

    /**
     * Whether the worker fits standing on this tile: every column its hitbox covers when centred
     * on the tile is clear for its whole height, and there's floor under its centre. A wide
     * Pokémon (Conkeldurr is 1.5 blocks wide, Hariyama nearly 3) also takes up the columns next to
     * the tile; leaves or a trunk there used to leave it unable to stand where it was sent.
     */
    public static boolean fits(Level level, BlockPos pos, Mob entity) {
        if (level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
            return false;
        }
        double half = entity.getBbWidth() / 2.0;
        int minX = (int) Math.floor(pos.getX() + 0.5 - half);
        int maxX = (int) Math.floor(pos.getX() + 0.5 + half - 1e-3);
        int minZ = (int) Math.floor(pos.getZ() + 0.5 - half);
        int maxZ = (int) Math.floor(pos.getZ() + 0.5 + half - 1e-3);
        int headroom = headroomFor(entity);
        BlockPos.MutableBlockPos space = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = 0; y < headroom; y++) {
                    space.set(x, pos.getY() + y, z);
                    if (!level.getBlockState(space).getCollisionShape(level, space).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
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

    /**
     * Whether the job's block is a directional station (furnace, chest, ...): the worker has to be in
     * front of it. Anything else (a crop, a tree, ore) can be worked from anywhere near it.
     */
    public static boolean isDirectional(Level level, BlockPos targetPos) {
        return stationFacing(level, targetPos) != null;
    }

    /**
     * Whether the worker is near enough to a non-directional job to do it from where it stands: its
     * reach distance plus two blocks across, and two blocks up or down. Used once its walk has ended
     * short of its stand tile, which happens to wide Pokémon among trees: the pathfinder plans a
     * route, but their body gets caught on the canopy before the tile.
     */
    public static boolean withinWorkReach(Mob entity, BlockPos targetPos) {
        double dx = entity.getX() - (targetPos.getX() + 0.5);
        double dz = entity.getZ() - (targetPos.getZ() + 0.5);
        double reach = reachDistanceFor(entity) + 2.0;
        return dx * dx + dz * dz <= reach * reach && Math.abs(entity.getY() - targetPos.getY()) <= 2.0;
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

    /** A tile is standable if feet + head space (two blocks) are clear and there's a solid floor below. */
    public static boolean isStandable(Level level, BlockPos pos) {
        return isStandable(level, pos, 2);
    }

    /** A tile is standable if {@code headroom} blocks from the feet up are clear and the floor is solid. */
    public static boolean isStandable(Level level, BlockPos pos, int headroom) {
        if (level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
            return false;
        }
        for (int i = 0; i < headroom; i++) {
            BlockPos space = pos.above(i);
            if (!level.getBlockState(space).getCollisionShape(level, space).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
