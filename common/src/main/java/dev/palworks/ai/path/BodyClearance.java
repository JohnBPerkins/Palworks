package dev.palworks.ai.path;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Where a worker's body fits and how it can move, tested with its real hitbox against the blocks'
 * real collision shapes: the geometry {@link WorkerPathfinder} plans with. One per search, as it
 * caches what it has worked out about the world.
 *
 * <p>Positions are path nodes in the convention Minecraft's path follower steers by (see
 * {@code Path.getEntityPosAtNode}): a node's x and z are where the hitbox's corner block is, and the
 * mob is steered to put its middle {@code (int) (width + 1) / 2} blocks on from there. That's a
 * block's middle for a mob narrower than a block (or 2 to 3 wide), but a block's corner for one 1 to
 * 2 blocks wide. Planning in any other convention would have wide Pokémon walk half a block off the
 * line that was checked. A node's y is the block its feet are in, from its floor:
 * {@code floor(floorY + 0.5)}, so standing on a slab is the slab's block row plus one, like
 * vanilla's.
 */
final class BodyClearance {
    /** Shaved off the hitbox so a body resting on a floor or flush against a wall isn't "colliding". */
    private static final double EPS = 1.0e-3;

    /** Highest jump: Cobblemon's own pathfinder's (1.125), or the mob's step height if more. */
    static final double MIN_JUMP = 1.125;

    final Level level;
    final Mob mob;
    final double halfWidth;
    final double height;
    /** From a node's x/z to where the mob's middle is steered: see the class doc. */
    final double centreOffset;

    final double maxJump;
    final int maxDrop;

    /** See {@link #flatTop}. Weak, for shapes made afresh each time (a shulker box opening). */
    private static final Map<VoxelShape, Double> FLAT_TOPS = Collections.synchronizedMap(new WeakHashMap<>());

    /** What blocks' shapes are for this mob (see {@link #shapeAt}); made when first needed. */
    @Nullable
    private CollisionContext context;

    /** Floors worked out so far, by node: NaN where there's none in that node's row. */
    private final Long2DoubleMap floors = new Long2DoubleOpenHashMap();
    /** How bad standing at a node is (its worst path-type malus), by node: negative for "never". */
    private final Long2FloatMap maluses = new Long2FloatOpenHashMap();

    BodyClearance(Mob mob) {
        this.level = mob.level();
        this.mob = mob;
        this.halfWidth = mob.getBbWidth() / 2.0;
        this.height = mob.getBbHeight();
        this.centreOffset = ((int) (mob.getBbWidth() + 1.0F)) * 0.5;
        this.maxJump = Math.max(MIN_JUMP, mob.maxUpStep());
        this.maxDrop = Math.max(1, mob.getMaxFallDistance());
    }

    /** Where the mob's middle is steered to for a node's x (or z). */
    double centre(int node) {
        return node + centreOffset;
    }

    /** The node whose middle is nearest this x (or z) of the mob's middle. */
    int node(double centre) {
        return Mth.floor(centre - centreOffset + 0.5);
    }

    /** The node row a body standing on this floor is in. */
    static int row(double floorY) {
        return Mth.floor(floorY + 0.5);
    }

    /** The mob's hitbox with its middle at (cx, cz), standing on {@code floorY}. */
    AABB box(double cx, double floorY, double cz) {
        return new AABB(
                cx - halfWidth + EPS,
                floorY + EPS,
                cz - halfWidth + EPS,
                cx + halfWidth - EPS,
                floorY + height - EPS,
                cz + halfWidth - EPS);
    }

    /** Whether nothing solid is in this space (blocks, the world border, boats and the like). */
    boolean clear(AABB space) {
        return level.noCollision(mob, space);
    }

    /** Whether the body fits standing on {@code floorY} with its middle at (cx, cz). */
    boolean fits(double cx, double floorY, double cz) {
        return clear(box(cx, floorY, cz));
    }

    /**
     * Whether the body fits standing in this tile (the block its feet go in), its middle at the
     * tile's, on whatever floor is in the tile's row, among the blocks there.
     */
    boolean fitsOnTile(BlockPos tile) {
        PlainFit plain = fitsOnPlainTile(tile);
        if (plain != PlainFit.NOT_PLAIN) {
            return plain == PlainFit.FITS;
        }
        double cx = tile.getX() + 0.5;
        double cz = tile.getZ() + 0.5;
        double floor = floorAt(cx, tile.getY(), cz);
        return !Double.isNaN(floor) && level.noBlockCollision(mob, box(cx, floor, cz));
    }

    /** What {@link #fitsOnPlainTile} makes of a tile. */
    private enum PlainFit {
        FITS,
        DOESNT,
        NOT_PLAIN
    }

    /**
     * {@link #fitsOnTile} for a tile with nothing but plain blocks about it, worked out from the
     * blocks themselves: under the body only blocks that are one box over their whole block (a
     * cube, farmland, a slab), and nothing at all where the body goes. That's nearly every tile,
     * and it gives the same answer as asking the level for the collision shapes there, at a
     * fraction of the cost: stand tiles are tested for every tile round a job, every tick, by
     * every worker, and the collision queries alone nearly doubled a tick with 200 workers.
     * Anything else about (a carpet, a fence, stairs, leaves overhead) is {@code NOT_PLAIN}: the
     * level is asked.
     */
    private PlainFit fitsOnPlainTile(BlockPos tile) {
        double cx = tile.getX() + 0.5;
        double cz = tile.getZ() + 0.5;
        int y = tile.getY();
        int minX = Mth.floor(cx - halfWidth + EPS);
        int maxX = Mth.floor(cx + halfWidth - EPS);
        int minZ = Mth.floor(cz - halfWidth + EPS);
        int maxZ = Mth.floor(cz + halfWidth - EPS);
        int topRow = Mth.floor(y + height - EPS); // its floor is no higher than the tile's own
        if (context == null) {
            context = CollisionContext.of(mob);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double floor = Double.NaN;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                // (Not loaded: no collision there, as the level's own queries have it)
                BlockGetter chunk = level.getChunkForCollisions(bx >> 4, bz >> 4);
                if (chunk == null) {
                    continue;
                }
                // A block two down that's over a block tall (a fence, a wall) reaches the floor's row
                VoxelShape twoDown = shapeAt(chunk, pos.set(bx, y - 2, bz));
                if (!twoDown.isEmpty() && twoDown.max(Direction.Axis.Y) > 1.0) {
                    return PlainFit.NOT_PLAIN;
                }
                VoxelShape under = shapeAt(chunk, pos.set(bx, y - 1, bz));
                if (!under.isEmpty()) {
                    double top = flatTop(under);
                    if (Double.isNaN(top) || top > 1.0) {
                        return PlainFit.NOT_PLAIN;
                    }
                    // In the tile's row (see floorAt), and the highest there
                    top += y - 1;
                    if (top >= y - 0.5 - EPS && (Double.isNaN(floor) || top > floor)) {
                        floor = top;
                    }
                }
                for (int by = y; by <= topRow; by++) {
                    if (!shapeAt(chunk, pos.set(bx, by, bz)).isEmpty()) {
                        return PlainFit.NOT_PLAIN;
                    }
                }
            }
        }
        return Double.isNaN(floor) ? PlainFit.DOESNT : PlainFit.FITS;
    }

    /** A block's collision shape for the mob, as the level's collision queries see it. */
    private VoxelShape shapeAt(BlockGetter chunk, BlockPos pos) {
        return chunk.getBlockState(pos).getCollisionShape(level, pos, context);
    }

    /**
     * The top (0 to 1, within its block) of a shape (not an empty one) that's a single box over its whole block, so
     * the same height wherever on it something stands; NaN for any other shape. Kept by shape:
     * blocks share a few shapes between them, and working one out takes its boxes apart.
     */
    private static double flatTop(VoxelShape shape) {
        return FLAT_TOPS.computeIfAbsent(shape, BodyClearance::computeFlatTop);
    }

    private static Double computeFlatTop(VoxelShape shape) {
        List<AABB> boxes = shape.toAabbs();
        AABB box = boxes.get(0); // (it isn't empty)
        boolean wholeBlock = boxes.size() == 1
                && box.minX <= EPS
                && box.maxX >= 1.0 - EPS
                && box.minZ <= EPS
                && box.maxZ >= 1.0 - EPS;
        return wholeBlock ? box.maxY : Double.NaN;
    }

    /**
     * The floor under a body with its middle at (cx, cz) and its feet in node row {@code y}: the
     * highest collision top under any part of it within that row ({@code [y - 0.5, y + 0.5)}), so a
     * wide Pokémon half over a ledge stands on the ledge. NaN if there's nothing to stand on in that
     * row (or the chunk isn't loaded: unloaded blocks have no collision, so read as empty).
     */
    double floorAt(double cx, int y, double cz) {
        double bottom = y - 0.5 - EPS;
        double top = y + 0.5;
        AABB probe = new AABB(
                cx - halfWidth + EPS, bottom, cz - halfWidth + EPS, cx + halfWidth - EPS, top, cz + halfWidth - EPS);
        double best = Double.NaN;
        for (VoxelShape shape : level.getBlockCollisions(mob, probe)) {
            for (AABB part : shape.toAabbs()) {
                if (part.maxX <= probe.minX
                        || part.minX >= probe.maxX
                        || part.maxZ <= probe.minZ
                        || part.minZ >= probe.maxZ) {
                    continue; // another part of the block's shape (stairs, a fence's arms)
                }
                double partTop = part.maxY;
                if (partTop >= bottom && partTop < top && (Double.isNaN(best) || partTop > best)) {
                    best = partTop;
                }
            }
        }
        return best;
    }

    /** {@link #floorAt} for a node, cached. */
    double floorAtNode(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (floors.containsKey(key)) {
            return floors.get(key);
        }
        double floor = floorAt(centre(x), y, centre(z));
        floors.put(key, floor);
        return floor;
    }

    /**
     * How much the body should avoid standing at this node: the worst malus the mob has for any
     * block its feet are in there (fire, berry bushes, wading water, ...), from vanilla's path types
     * and the mob's own maluses (which Cobblemon sets). Negative where it mustn't stand at all,
     * including deep water (this plans walking; swimmers are left to Cobblemon) and unloaded
     * chunks. Cached.
     */
    float malusAt(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (maluses.containsKey(key)) {
            return maluses.get(key);
        }
        float malus = computeMalus(x, y, z);
        maluses.put(key, malus);
        return malus;
    }

    /** Whether a malus means the mob mustn't stand there at all (Minecraft's convention: negative). */
    static boolean forbidden(float malus) {
        return malus < 0.0F;
    }

    private float computeMalus(int x, int y, int z) {
        double cx = centre(x);
        double cz = centre(z);
        int minX = Mth.floor(cx - halfWidth + EPS);
        int maxX = Mth.floor(cx + halfWidth - EPS);
        int minZ = Mth.floor(cz - halfWidth + EPS);
        int maxZ = Mth.floor(cz + halfWidth - EPS);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        float worst = 0.0F;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                pos.set(bx, y, bz);
                if (!level.isLoaded(pos)) {
                    return -1.0F;
                }
                PathType type = WalkNodeEvaluator.getPathTypeStatic(mob, pos);
                float malus = mob.getPathfindingMalus(type);
                if (forbidden(malus)) {
                    return malus;
                }
                worst = Math.max(worst, malus);
            }
        }
        // Wading is fine, but water over its head isn't walking
        pos.set(Mth.floor(cx), y + 1, Mth.floor(cz));
        if (level.getFluidState(pos).is(FluidTags.WATER)
                && level.getFluidState(pos.below()).is(FluidTags.WATER)) {
            return -1.0F;
        }
        return worst;
    }

    /**
     * Whether the body can get from standing at one place to standing at another next to it:
     * <ul>
     *   <li>up (a step, a slab, a block): no higher than it can jump, with room over where it takes
     *       off to rise to the new height, then room to move across at that height;</li>
     *   <li>level or down: room to move across at its height, then room to drop, no further than it
     *       can fall.</li>
     * </ul>
     * Moving across checks the whole space the body sweeps through, so a diagonal can't cut a
     * corner past a block, however wide the Pokémon is.
     */
    boolean canMove(double fromX, double fromFloor, double fromZ, double toX, double toFloor, double toZ) {
        AABB start = box(fromX, fromFloor, fromZ);
        if (toFloor > fromFloor + EPS) {
            if (toFloor - fromFloor > maxJump + EPS) {
                return false;
            }
            AABB raised = box(fromX, toFloor, fromZ);
            AABB arrived = box(toX, toFloor, toZ);
            return clear(start.minmax(raised)) && clear(raised.minmax(arrived));
        }
        if (fromFloor - toFloor > maxDrop + EPS) {
            return false;
        }
        AABB across = box(toX, fromFloor, toZ);
        if (!clear(start.minmax(across))) {
            return false;
        }
        return toFloor >= fromFloor - EPS || clear(across.minmax(box(toX, toFloor, toZ)));
    }
}
