package dev.palworks.ai.path;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Where a worker's body fits and how it can move, tested with its real hitbox against the blocks'
 * real collision shapes, and kept out of the blocks that hurt it ({@link #hurtsIn}): the geometry
 * {@link WorkerPathfinder} plans with. One per search, as it caches what it has worked out about
 * the world.
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

    /**
     * How many nodes back a mob following a path may leave its line for the next node. It counts a
     * node as reached once it's within some way of it ({@link #reachedWithin}): half its width, or
     * for one under 0.75 wide, 0.75 less half its width. That's under a block for a body up to two
     * wide, so it turns before the node it's coming to; a wider one can turn before the node before
     * that.
     */
    final int turnsEarlyBy;

    /** See {@link #boxOf}. Weak, for shapes made afresh each time (a shulker box opening). */
    private static final Map<VoxelShape, OneBox> ONE_BOXES = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Just over half a block. A tile's floor is no lower than half a block under the tile, so
     * anything whose bottom is less than the body's height above that is in the body's way.
     */
    private static final double OVER_HALF = 0.5 + 4 * EPS;

    /** The highest floor and lowest ceiling {@link #scanPlain} has found for the tile it's on. */
    private double plainFloor;

    private double plainCeiling;

    /** What blocks' shapes are for this mob (see {@link #scanPlain}); made when first needed. */
    @Nullable
    private CollisionContext context;

    /** Whether {@link #scanPlain} found a block that hurts in the row under the tile it's on. */
    private boolean plainHurtsBelow;

    /** What blocks are to walk through, as Minecraft's own pathfinding has them; made when first needed. */
    @Nullable
    private PathfindingContext pathTypes;

    /** Whether each block asked about so far hurts the mob ({@link #hurtsAt}), by block. */
    private final Long2BooleanMap hurts = new Long2BooleanOpenHashMap();

    /** {@link #nearHurt} so far, by node. */
    private final Long2BooleanMap nearHurt = new Long2BooleanOpenHashMap();

    private final Long2BooleanMap fitsAt = new Long2BooleanOpenHashMap();

    // Nothing but blocks can be in the body's way where the search goes: see searchIn
    private boolean onlyBlocksInTheWay;

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
        this.turnsEarlyBy = Math.max(1, (int) Math.ceil(reachedWithin(mob)));
    }

    /**
     * How near a node a mob following a path has to get, across, for the node to count as reached:
     * Minecraft's path follower's rule, and Cobblemon's.
     */
    static double reachedWithin(Mob mob) {
        return mob.getBbWidth() > 0.75F ? mob.getBbWidth() / 2.0 : 0.75 - mob.getBbWidth() / 2.0;
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

    /**
     * Whether the body can be in this space: nothing solid in it (blocks, the world border, boats
     * and the like), and no block that would hurt it ({@link #hurtsIn}).
     */
    boolean clear(AABB space) {
        if (hurtsIn(space)) {
            return false;
        }
        return onlyBlocksInTheWay ? level.noBlockCollision(mob, space) : level.noCollision(mob, space);
    }

    /**
     * Told the space a search is about to try the body in, all of it. {@code level.noCollision}
     * looks up the entities round every box it's asked about, and a search asks about thousands,
     * nearly always to find none that count: only a solid one does (a boat, a shulker). So they're
     * looked for once, over the whole space, and if there are none {@link #clear} asks about blocks
     * only from here on. Not by the world border, which {@code noCollision} counts too: it looks
     * at the border for an entity within twice the box's size of it, and no box tried is more than
     * the body and two blocks across.
     */
    void searchIn(AABB space) {
        onlyBlocksInTheWay = level.getWorldBorder().getDistanceToBorder(mob) >= 2.0 * (mob.getBbWidth() + 2.0)
                && level.getEntities(mob, space, EntitySelector.NO_SPECTATORS.and(mob::canCollideWith))
                        .isEmpty();
    }

    /**
     * Whether a body in this space would be in a block that hurts it by touch: a cactus, a sweet
     * berry bush, fire, a lit campfire. The whole of the block's space counts, whatever its shape,
     * as it does to the game: a body brushing the corner of a bush's block is in the bush, and one
     * pressed against a cactus is in the cactus. Blocks the mob is in already don't count: it has
     * to be let out of them.
     */
    boolean hurtsIn(AABB space) {
        AABB here = mob.getBoundingBox();
        for (int x = Mth.floor(space.minX); x <= Mth.floor(space.maxX); x++) {
            for (int y = Mth.floor(space.minY); y <= Mth.floor(space.maxY); y++) {
                for (int z = Mth.floor(space.minZ); z <= Mth.floor(space.maxZ); z++) {
                    if (hurtsAt(x, y, z) && !here.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether a block that hurts is near enough a body standing at this node to be brushed as it
     * turns there: within the nodes round it (or the two round it, for a body that
     * {@link #turnsEarlyBy} two). Cached.
     */
    boolean nearHurt(int x, int y, int z, double floorY) {
        long key = BlockPos.asLong(x, y, z);
        if (nearHurt.containsKey(key)) {
            return nearHurt.get(key);
        }
        boolean near = hurtsIn(box(centre(x), floorY, centre(z)).inflate(turnsEarlyBy, 0.0, turnsEarlyBy));
        nearHurt.put(key, near);
        return near;
    }

    /**
     * Whether the block here hurts the mob when it's in it ({@link HurtingBlocks#hurts}). Cached.
     * (Not loaded: no, like its collision.)
     */
    private boolean hurtsAt(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (hurts.containsKey(key)) {
            return hurts.get(key);
        }
        boolean hurtsHere = level.hasChunk(x >> 4, z >> 4) && HurtingBlocks.hurts(pathTypeAt(x, y, z), mob);
        hurts.put(key, hurtsHere);
        return hurtsHere;
    }

    /**
     * {@link #hurtsAt} for a block whose state is in hand, without asking the level anything: the
     * test of a tile to stand on is made too often for that ({@link #scanPlain}). What a state is
     * to Minecraft's pathfinding is the state's alone to say, so it's worked out once for each.
     */
    private boolean hurts(BlockState state, BlockGetter chunk, BlockPos pos) {
        PathType type = STATE_TYPES.get(state);
        if (type == null) {
            type = StateTypes.of(chunk, pos);
            STATE_TYPES.put(state, type);
        }
        return HurtingBlocks.hurts(type, mob);
    }

    /** What each block state met by {@link #hurts(BlockState, BlockGetter, BlockPos)} is to walk through. Server thread only. */
    private static final Reference2ObjectMap<BlockState, PathType> STATE_TYPES = new Reference2ObjectOpenHashMap<>();

    /** Minecraft's own reading of a block for pathfinding, which it keeps to its node evaluators. */
    private static final class StateTypes extends WalkNodeEvaluator {
        static PathType of(BlockGetter level, BlockPos pos) {
            return getPathTypeFromState(level, pos);
        }
    }

    /** What the block here is to walk through, on its own (whatever is under or next to it). */
    private PathType pathTypeAt(int x, int y, int z) {
        if (pathTypes == null) {
            pathTypes = new PathfindingContext(level, mob);
        }
        return pathTypes.getPathTypeFromState(x, y, z);
    }

    /** Whether the body fits standing on {@code floorY} with its middle at (cx, cz). */
    boolean fits(double cx, double floorY, double cz) {
        return clear(box(cx, floorY, cz));
    }

    /**
     * {@link #fits} at a node, on the floor {@link #floorAtNode} found there. Remembered: a search
     * comes to a node from each of the eight round it, and to one the body doesn't fit at it would
     * try the body again every time.
     */
    boolean fitsAtNode(int x, int y, int z, double floorY) {
        long key = BlockPos.asLong(x, y, z);
        if (fitsAt.containsKey(key)) {
            return fitsAt.get(key);
        }
        boolean fits = fits(centre(x), floorY, centre(z));
        fitsAt.put(key, fits);
        return fits;
    }

    /**
     * Whether the body fits standing in this tile (the block its feet go in), its middle at the
     * tile's, on whatever floor is in the tile's row, among the blocks there, and in none that
     * hurts it.
     */
    boolean fitsOnTile(BlockPos tile) {
        PlainFit plain = fitsOnPlainTile(tile);
        if (plain != PlainFit.NOT_PLAIN) {
            return plain == PlainFit.FITS;
        }
        double cx = tile.getX() + 0.5;
        double cz = tile.getZ() + 0.5;
        double floor = floorAt(cx, tile.getY(), cz);
        if (Double.isNaN(floor)) {
            return false;
        }
        AABB body = box(cx, floor, cz);
        return level.noBlockCollision(mob, body) && !hurtsIn(body);
    }

    /** What {@link #fitsOnPlainTile} makes of a tile. */
    private enum PlainFit {
        FITS,
        DOESNT,
        NOT_PLAIN
    }

    /**
     * {@link #fitsOnTile} for a tile with nothing but plain blocks about it, worked out from the
     * blocks themselves: under the body and where it goes, only blocks whose shape is a single box
     * (a cube, farmland, a slab, a carpet, a chest, leaves), or nothing. That's nearly every tile,
     * and it gives the same answer as asking the level for the collision shapes there, at a
     * fraction of the cost: stand tiles are tested for every tile round a job (and the rows above
     * and below it), every tick, by every worker, and the collision queries alone nearly doubled a
     * tick with 200 workers. A shape of several boxes about (stairs, a fence joined to another, a
     * cauldron) is {@code NOT_PLAIN}: the level is asked. A block that hurts ({@link #hurts})
     * in a row the body is in is {@code DOESNT}, wherever in its block the body would be.
     *
     * <p>Each box under or in the body is one of three things, by its top: below the tile's row,
     * and nothing to it; in the row, and somewhere to stand (the highest is its floor: see
     * {@link #floorAt}); or above the row, and over it, in its way if its bottom is lower than the
     * body is tall.
     */
    private PlainFit fitsOnPlainTile(BlockPos tile) {
        double cx = tile.getX() + 0.5;
        double cz = tile.getZ() + 0.5;
        int y = tile.getY();
        if (context == null) {
            context = CollisionContext.of(mob);
        }
        plainFloor = Double.NaN;
        plainCeiling = Double.POSITIVE_INFINITY;
        plainHurtsBelow = false;
        // Two rows down: a block over a block tall (a fence post) reaches up into the tile's row
        int topRow = Mth.floor(y + height - EPS);
        PlainFit scanned = scanPlain(cx, cz, y, y - 2, topRow);
        if (scanned != PlainFit.FITS) {
            return scanned;
        }
        if (Double.isNaN(plainFloor)) {
            return PlainFit.DOESNT;
        }
        // A floor in the tile's own block (a carpet, snow) puts its head in a row not looked at yet
        int reaches = Mth.floor(plainFloor + height - EPS);
        if (reaches > topRow) {
            scanned = scanPlain(cx, cz, y, topRow + 1, reaches);
            if (scanned != PlainFit.FITS) {
                return scanned;
            }
        }
        // On a floor in the lower half of the row (a slab, farmland), its feet are in the row below
        if (plainHurtsBelow && plainFloor + EPS < y) {
            return PlainFit.DOESNT;
        }
        return plainFloor + height - EPS <= plainCeiling ? PlainFit.FITS : PlainFit.DOESNT;
    }

    /**
     * {@link #fitsOnPlainTile}'s look at the blocks in these rows, under and in a body with its
     * middle at (cx, cz) on a tile in row {@code y}: the floor and the ceiling they make go in
     * {@link #plainFloor} and {@link #plainCeiling}. {@code FITS} if nothing so far says otherwise.
     */
    private PlainFit scanPlain(double cx, double cz, int y, int fromRow, int toRow) {
        double minX = cx - halfWidth + EPS;
        double maxX = cx + halfWidth - EPS;
        double minZ = cz - halfWidth + EPS;
        double maxZ = cz + halfWidth - EPS;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = Mth.floor(minX); bx <= Mth.floor(maxX); bx++) {
            for (int bz = Mth.floor(minZ); bz <= Mth.floor(maxZ); bz++) {
                // (Not loaded: no collision there, as the level's own queries have it)
                BlockGetter chunk = level.getChunkForCollisions(bx >> 4, bz >> 4);
                if (chunk == null) {
                    continue;
                }
                // From the top down: under a whole block below the tile, nothing matters
                for (int row = toRow; row >= fromRow; row--) {
                    BlockState state = chunk.getBlockState(pos.set(bx, row, bz));
                    if (state.isAir()) {
                        continue;
                    }
                    boolean cube = state.isCollisionShapeFullBlock(level, pos);
                    // (A whole block is never one the body is in)
                    if (!cube && row >= y - 1 && hurts(state, chunk, pos)) {
                        if (row >= y) {
                            return PlainFit.DOESNT;
                        }
                        plainHurtsBelow = true;
                    }
                    double bottom = row;
                    double top = row + 1.0;
                    if (!cube) {
                        VoxelShape shape = state.getCollisionShape(level, pos, context);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        AABB box = boxOf(shape).box();
                        if (box == null) {
                            return PlainFit.NOT_PLAIN;
                        }
                        if (bx + box.maxX <= minX
                                || bx + box.minX >= maxX
                                || bz + box.maxZ <= minZ
                                || bz + box.minZ >= maxZ) {
                            continue; // beside the body, not under or in it (a post, a chest's edge)
                        }
                        bottom = row + box.minY;
                        top = row + box.maxY;
                    }
                    if (top >= y + 0.5) {
                        // Over the row. So low that wherever in the row its floor is, the body
                        // reaches it (a block in the ground, the job's own block): no need to go on
                        if (bottom < y - OVER_HALF + height) {
                            return PlainFit.DOESNT;
                        }
                        plainCeiling = Math.min(plainCeiling, bottom);
                    } else if (top >= y - 0.5 - EPS && (Double.isNaN(plainFloor) || top > plainFloor)) {
                        plainFloor = top;
                    }
                    if (cube && row < y) {
                        break;
                    }
                }
            }
        }
        return PlainFit.FITS;
    }

    /** A block's shape as its one box (where in the block it is, 0 to 1), or null if it's several. */
    private record OneBox(@Nullable AABB box) {}

    /**
     * What a shape (not an empty one) is as a {@link OneBox}. Kept by shape: blocks share a few
     * shapes between them, and working one out takes its boxes apart.
     */
    private static OneBox boxOf(VoxelShape shape) {
        return ONE_BOXES.computeIfAbsent(shape, BodyClearance::computeOneBox);
    }

    private static OneBox computeOneBox(VoxelShape shape) {
        List<AABB> boxes = shape.toAabbs();
        return new OneBox(boxes.size() == 1 ? boxes.get(0) : null);
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
