package dev.palworks.ai;

import dev.palworks.crop.CropAdapter;
import dev.palworks.job.JobType;
import dev.palworks.tree.TreeDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
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
        if (jobType == JobType.CHOP_TREE) {
            TreeStand stand = treeStandFor(level, targetPos, entity);
            return stand == null ? null : stand.stand();
        }
        int distance = reachDistanceFor(entity);

        if (CropAdapter.isHangingFruit(level.getBlockState(targetPos))) {
            return hangingFruitStand(level, targetPos, entity, distance);
        }

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

        // Non-directional (crops, ore, brewing stand, ...; trees are handled above): the nearest
        // ring, from `distance` out, that has a tile the worker fits on; in that ring, the tile
        // nearest the worker. Rings are square (cardinal and diagonal tiles), and each tile is also
        // tried one block up and down so slopes work.
        Predicate<BlockPos> fitsThere = spot -> fits(level, spot, entity);
        for (int d = distance; d <= distance + EXTRA_RINGS; d++) {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos candidate : ring(targetPos, d)) {
                // One height per tile: the first that works, level ground first
                BlockPos spot = firstHeight(candidate, SLOPE_STEPS, fitsThere);
                if (spot == null) {
                    continue;
                }
                // Ties keep ring order (a fixed direction order), so the choice doesn't flip
                // between equally near tiles as the worker walks (this is recomputed each tick)
                double distSq = entity.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                if (distSq < bestDistSq - 1e-6) {
                    best = spot;
                    bestDistSq = distSq;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * How far below a hanging fruit a worker may stand to pick it. Cocoa grows on a jungle trunk's
     * lowest three logs, so up to 2 blocks above the ground; apricorns hang 2 or 3 blocks up.
     */
    private static final int HANGING_FRUIT_REACH_DOWN = 3;

    /** The heights tried under a hanging fruit, lowest first: see {@link #hangingFruitStand}. */
    private static final int[] HANGING_FRUIT_STEPS =
            IntStream.rangeClosed(-HANGING_FRUIT_REACH_DOWN, 1).toArray();

    /**
     * Where to stand to pick a hanging fruit (a cocoa pod, an apricorn): on the ground beneath it,
     * a ring or more out, like any crop. Each tile is tried from {@link #HANGING_FRUIT_REACH_DOWN}
     * below the fruit up to one above, and the lowest spot the worker fits on is the one: the fruit
     * is usually above head height, so a spot level with it is in the air, or up on the leaves.
     * Never on top of leaves (the canopy an apricorn hangs in): workers can't get up there.
     */
    @Nullable
    private static BlockPos hangingFruitStand(Level level, BlockPos fruit, Mob entity, int distance) {
        Predicate<BlockPos> fitsThere = spot -> fits(level, spot, entity);
        for (int d = distance; d <= distance + EXTRA_RINGS; d++) {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos tile : ring(fruit, d)) {
                // One height per tile: the lowest it fits on
                BlockPos spot = firstHeight(tile, HANGING_FRUIT_STEPS, fitsThere);
                if (spot == null || level.getBlockState(spot.below()).is(BlockTags.LEAVES)) {
                    continue;
                }
                double distSq = distSqTo(entity, spot);
                if (distSq < bestDistSq - 1e-6) {
                    best = spot;
                    bestDistSq = distSq;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    // ============================================================
    // Trees: chop right up against the trunk, clearing leaves to get there
    // ============================================================

    /**
     * Where a lumberjack stands, and the leaves it has to clear first (none if it can go straight
     * to the trunk).
     *
     * @param stand         where to walk to: a chopping spot by the trunk, or, when leaves are in
     *                      the way, the spot it clears them from
     * @param leavesToClear the natural leaves between that spot and the trunk, cleared before chopping
     */
    public record TreeStand(BlockPos stand, List<BlockPos> leavesToClear) {
        public boolean needsClearing() {
            return !leavesToClear.isEmpty();
        }
    }

    /**
     * Where a lumberjack should stand to chop the tree at {@code treePos}: right up against the
     * trunk. Real trees' lowest leaves often hang at head height all around the trunk, so where
     * they're in the way it clears them first: it stands just outside them and clears a way in. A
     * tile one further out is the fallback when nothing right by the trunk works even with leaves
     * cleared (a slope, a branch, a wall). Null when even that's blocked by more than leaves: the
     * job isn't for this worker (it used to chop from 4 or 5 blocks away instead).
     *
     * <p>Only natural leaves are cleared, never ones a player placed. In each ring, as-is spots come
     * before ones that need clearing, and the spot nearest the worker wins.
     */
    @Nullable
    public static TreeStand treeStandFor(Level level, BlockPos treePos, Mob entity) {
        List<BlockPos> trunk = trunkColumns(level, treePos);
        int closest = closestChopRing(entity);
        Predicate<BlockPos> fitsThere = spot -> fits(level, spot, entity);
        Predicate<BlockPos> fitsIfCleared = spot -> fitsIfLeavesCleared(level, spot, entity);
        for (int d = closest; d <= closest + 1; d++) {
            List<BlockPos> ring = ringAround(trunk, d);

            // Somewhere it can go straight to
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos tile : ring) {
                BlockPos spot = firstHeight(tile, SLOPE_STEPS, fitsThere);
                if (spot == null) {
                    continue;
                }
                double distSq = distSqTo(entity, spot);
                if (distSq < bestDistSq - 1e-6) {
                    best = spot;
                    bestDistSq = distSq;
                }
            }
            if (best != null) {
                return new TreeStand(best, List.of());
            }

            // Somewhere it can get to by clearing leaves
            TreeStand cleared = null;
            bestDistSq = Double.MAX_VALUE;
            for (BlockPos tile : ring) {
                BlockPos spot = firstHeight(tile, SLOPE_STEPS, fitsIfCleared);
                if (spot == null) {
                    continue;
                }
                for (Direction out : outwardDirections(trunk, spot)) {
                    TreeStand way = wayIn(level, spot, out, entity);
                    if (way != null) {
                        double distSq = distSqTo(entity, way.stand());
                        if (distSq < bestDistSq - 1e-6) {
                            cleared = way;
                            bestDistSq = distSq;
                        }
                    }
                }
            }
            if (cleared != null) {
                return cleared;
            }
        }
        return null;
    }

    /**
     * Whether the worker is right up against the tree, close enough to chop it from where it
     * stands: in the nearest ring round the trunk its hitbox fits in, level with the base give or
     * take a block.
     */
    public static boolean withinChoppingReach(Level level, Mob entity, BlockPos treePos) {
        List<BlockPos> trunk = trunkColumns(level, treePos);
        BlockPos at = entity.blockPosition();
        return Math.abs(at.getY() - treePos.getY()) <= 1
                && chebyshevToTrunk(trunk, at.getX(), at.getZ()) <= closestChopRing(entity);
    }

    /**
     * The nearest ring around the trunk a worker's hitbox fits in without overlapping it: 1 (the
     * tiles touching the trunk) for most, 2 for one wider than about a block (Conkeldurr, Hariyama).
     */
    public static int closestChopRing(Mob entity) {
        return Math.max(1, (int) Math.ceil(entity.getBbWidth() / 2.0 + 0.5 - 1e-3));
    }

    /**
     * The trunk's columns at its base: the tree block there and any next to it (a 2x2 trunk), so
     * rings go round the whole trunk. Just {@code treePos} once it's been chopped (replanting).
     */
    private static List<BlockPos> trunkColumns(Level level, BlockPos treePos) {
        List<BlockPos> trunk = new ArrayList<>(4);
        trunk.add(treePos);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = treePos.offset(dx, 0, dz);
                if ((dx != 0 || dz != 0) && TreeDetector.isLog(level.getBlockState(pos))) {
                    trunk.add(pos);
                }
            }
        }
        return trunk;
    }

    /** The tiles exactly {@code d} away (Chebyshev) from the nearest trunk column, at the base's Y. */
    private static List<BlockPos> ringAround(List<BlockPos> trunk, int d) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos column : trunk) {
            minX = Math.min(minX, column.getX());
            maxX = Math.max(maxX, column.getX());
            minZ = Math.min(minZ, column.getZ());
            maxZ = Math.max(maxZ, column.getZ());
        }
        int y = trunk.get(0).getY();
        List<BlockPos> tiles = new ArrayList<>();
        for (int x = minX - d; x <= maxX + d; x++) {
            for (int z = minZ - d; z <= maxZ + d; z++) {
                if (chebyshevToTrunk(trunk, x, z) == d) {
                    tiles.add(new BlockPos(x, y, z));
                }
            }
        }
        return tiles;
    }

    private static int chebyshevToTrunk(List<BlockPos> trunk, int x, int z) {
        int best = Integer.MAX_VALUE;
        for (BlockPos column : trunk) {
            best = Math.min(best, Math.max(Math.abs(x - column.getX()), Math.abs(z - column.getZ())));
        }
        return best;
    }

    /**
     * The straight (cardinal) ways out from a spot by the trunk, away from it: one for a spot
     * beside the trunk, two for one at its corner. Cardinal only, so the cleared way is one the
     * pathfinder can walk (it won't cut a diagonal corner past leaves).
     */
    private static List<Direction> outwardDirections(List<BlockPos> trunk, BlockPos spot) {
        BlockPos nearest = trunk.get(0);
        int nearestDist = Integer.MAX_VALUE;
        for (BlockPos column : trunk) {
            int dist = Math.max(Math.abs(spot.getX() - column.getX()), Math.abs(spot.getZ() - column.getZ()));
            if (dist < nearestDist) {
                nearest = column;
                nearestDist = dist;
            }
        }
        List<Direction> out = new ArrayList<>(2);
        int dx = Integer.signum(spot.getX() - nearest.getX());
        int dz = Integer.signum(spot.getZ() - nearest.getZ());
        if (dx != 0) {
            out.add(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (dz != 0) {
            out.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        return out;
    }

    /**
     * A way in to {@code target} (a spot by the trunk that only leaves block) from outside the
     * leaves, going out from it in {@code out}: the first tile along that line the worker can
     * already stand on, with every tile between clearable. Null if there's more than leaves in the
     * way, or no spot within {@link #EXTRA_RINGS} tiles.
     */
    @Nullable
    private static TreeStand wayIn(Level level, BlockPos target, Direction out, Mob entity) {
        List<BlockPos> way = new ArrayList<>();
        way.add(target);
        for (int k = 1; k <= EXTRA_RINGS; k++) {
            BlockPos tile = target.relative(out, k);
            for (int dy : SLOPE_STEPS) {
                BlockPos spot = tile.above(dy);
                if (fits(level, spot, entity)) {
                    List<BlockPos> leaves = new ArrayList<>();
                    for (BlockPos step : way) {
                        leaves.addAll(leavesIn(level, step, entity));
                    }
                    return new TreeStand(spot, leaves);
                }
            }
            if (!fitsIfLeavesCleared(level, tile, entity)) {
                return null;
            }
            way.add(tile);
        }
        return null;
    }

    /** Whether the worker would fit on this tile once the natural leaves in its space are gone. */
    private static boolean fitsIfLeavesCleared(Level level, BlockPos pos, Mob entity) {
        if (!hasFloor(level, pos)) {
            return false;
        }
        BlockPos.MutableBlockPos space = new BlockPos.MutableBlockPos();
        int[] box = footprint(pos, entity);
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                for (int y = 0; y < headroomFor(entity); y++) {
                    space.set(x, pos.getY() + y, z);
                    BlockState state = level.getBlockState(space);
                    if (!state.getCollisionShape(level, space).isEmpty() && !TreeDetector.isNaturalLeaf(state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The natural leaves in the space the worker would take up on this tile. */
    private static List<BlockPos> leavesIn(Level level, BlockPos pos, Mob entity) {
        List<BlockPos> leaves = new ArrayList<>();
        int[] box = footprint(pos, entity);
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                for (int y = 0; y < headroomFor(entity); y++) {
                    BlockPos space = new BlockPos(x, pos.getY() + y, z);
                    if (TreeDetector.isNaturalLeaf(level.getBlockState(space))) {
                        leaves.add(space);
                    }
                }
            }
        }
        return leaves;
    }

    private static double distSqTo(Mob entity, BlockPos spot) {
        return entity.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
    }

    /** How many rings beyond the reach distance to look for a place to stand. */
    private static final int EXTRA_RINGS = 3;
    /** Heights tried for each tile: level first, then one up and one down (slopes). */
    private static final int[] SLOPE_STEPS = {0, 1, -1};

    /** The first of {@code tile}'s heights, in {@code dys} order, where {@code fits}; null if none. */
    @Nullable
    private static BlockPos firstHeight(BlockPos tile, int[] dys, Predicate<BlockPos> fits) {
        for (int dy : dys) {
            BlockPos spot = tile.above(dy);
            if (fits.test(spot)) {
                return spot;
            }
        }
        return null;
    }

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
        if (!hasFloor(level, pos)) {
            return false;
        }
        int[] box = footprint(pos, entity);
        int headroom = headroomFor(entity);
        BlockPos.MutableBlockPos space = new BlockPos.MutableBlockPos();
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                for (int y = 0; y < headroom; y++) {
                    space.set(x, pos.getY() + y, z);
                    if (!level.getBlockState(space)
                            .getCollisionShape(level, space)
                            .isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasFloor(Level level, BlockPos pos) {
        return !level.getBlockState(pos.below())
                .getCollisionShape(level, pos.below())
                .isEmpty();
    }

    /** The columns the worker's hitbox covers centred on this tile: {minX, maxX, minZ, maxZ}. */
    private static int[] footprint(BlockPos pos, Mob entity) {
        double half = entity.getBbWidth() / 2.0;
        return new int[] {
            (int) Math.floor(pos.getX() + 0.5 - half),
            (int) Math.floor(pos.getX() + 0.5 + half - 1e-3),
            (int) Math.floor(pos.getZ() + 0.5 - half),
            (int) Math.floor(pos.getZ() + 0.5 + half - 1e-3)
        };
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
     * short of its stand tile (the pathfinder planned a route, but a wide body got caught on the
     * way). Trees have their own, tighter rule: {@link #withinChoppingReach}.
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
        // Cocoa pods and apricorns face what they hang from (the log, the leaves): "in front" of
        // one is inside that block. Treated as stations, workers were sent to stand in the log.
        if (CropAdapter.isHangingFruit(state)) {
            return null;
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    /** Feet + head space clear (no floor requirement) - i.e. not a wall blocking the lane. */
    private static boolean isPassable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above())
                        .getCollisionShape(level, pos.above())
                        .isEmpty();
    }

    /** A tile is standable if feet + head space (two blocks) are clear and there's a solid floor below. */
    public static boolean isStandable(Level level, BlockPos pos) {
        return isStandable(level, pos, 2);
    }

    /** A tile is standable if {@code headroom} blocks from the feet up are clear and the floor is solid. */
    public static boolean isStandable(Level level, BlockPos pos, int headroom) {
        if (level.getBlockState(pos.below())
                .getCollisionShape(level, pos.below())
                .isEmpty()) {
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
