package dev.palworks.boulder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Where a boulder may be raised: open, flat ground, with room round it. Nothing is ever replaced or
 * broken to make room, not even grass or a flower (it may be someone's garden). A site is the
 * boulder's footprint and a {@link #MARGIN}-block border round it, all of it solid ground at one
 * height:
 * <ul>
 *   <li>the footprint with nothing at all above the ground, up to a block over the boulder's top,
 *       and nobody standing in it;</li>
 *   <li>the border with nothing solid above the ground (a plant with nothing to bump into is all
 *       right: nothing's done to it, and a miner can stand there).</li>
 * </ul>
 */
public final class BoulderSites {
    /** How much room is left round a boulder, each way, in blocks. */
    public static final int MARGIN = 2;

    // How far up and down from where the search starts it looks for the ground
    private static final int GROUND_SEARCH_UP = 3;
    private static final int GROUND_SEARCH_DOWN = 4;

    private BoulderSites() {}

    /** What one column of a site is like. */
    public enum Column {
        /** Solid, flat ground with nothing above it: a boulder can go on it. */
        OPEN,
        /** Solid, flat ground with something above it that's nothing to bump into (a plant): room beside a boulder. */
        CLEAR,
        /** Anything else: not ground (a hole, water, a slab), or something solid on it. */
        BLOCKED
    }

    /** What the column at a spot of a site is, its site's ground at one height. */
    @FunctionalInterface
    public interface Columns {
        Column at(int x, int z);
    }

    /**
     * Whether a boulder {@code width} by {@code depth} fits with its footprint's corner at (0, 0):
     * its own columns open, those of the border round it at least clear. Pure, so it's unit-tested.
     */
    public static boolean fits(int width, int depth, Columns columns) {
        for (int x = -MARGIN; x < width + MARGIN; x++) {
            for (int z = -MARGIN; z < depth + MARGIN; z++) {
                boolean footprint = x >= 0 && z >= 0 && x < width && z < depth;
                Column column = columns.at(x, z);
                if (column == Column.BLOCKED || (footprint && column != Column.OPEN)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Sites for a boulder of this shape round {@code around}, its footprint's middle up to
     * {@code radius} blocks off: the corners of their footprints, just above the ground (where its
     * lowest blocks go), at most {@code most}, nearest first (give or take a few blocks, so a worker's
     * boulders aren't always in the same place). Only where {@code allowed} has the footprint's
     * corners and middle (the worker's work range). Every spot is looked at, not a few at random:
     * the ground round {@code around} is read once, then each spot is checked against it.
     */
    public static List<BlockPos> find(
            ServerLevel level,
            BoulderShape shape,
            BlockPos around,
            int radius,
            Predicate<BlockPos> allowed,
            RandomGenerator random,
            int most) {
        int w = shape.width();
        int d = shape.depth();
        int need = shape.height() + 1;
        // The ground of every column any site could use: corners from radius off the middle, and
        // the margin round them
        int minX = around.getX() - radius - w / 2 - MARGIN;
        int minZ = around.getZ() - radius - d / 2 - MARGIN;
        int sizeX = 2 * radius + w + 2 * MARGIN + 1;
        int sizeZ = 2 * radius + d + 2 * MARGIN + 1;
        Ground ground = Ground.read(level, minX, minZ, sizeX, sizeZ, around.getY(), need);
        // Every corner, nearest first by its middle (each a little nudged at random)
        List<double[]> corners = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                corners.add(new double[] {dx, dz, Math.hypot(dx, dz) + random.nextDouble() * NUDGE});
            }
        }
        corners.sort(java.util.Comparator.comparingDouble(corner -> corner[2]));
        List<BlockPos> sites = new ArrayList<>();
        for (double[] corner : corners) {
            int x = around.getX() + (int) corner[0] - w / 2;
            int z = around.getZ() + (int) corner[1] - d / 2;
            int y = ground.heightAt(x - minX, z - minZ);
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            boolean fitsHere = fits(w, d, (cx, cz) -> ground.column(x + cx - minX, z + cz - minZ, y));
            BlockPos site = new BlockPos(x, y + 1, z);
            if (fitsHere && inside(site, shape, allowed) && isOpen(level, site, shape)) {
                sites.add(site);
                if (sites.size() >= most) {
                    break;
                }
            }
        }
        return sites;
    }

    // How far, in blocks, a spot's distance is nudged at random when sites are put nearest first
    private static final double NUDGE = 4;

    /**
     * The ground of a rectangle of columns, read once for a search: each column's ground height
     * (the highest flat, solid block with room on it, near the search's height) and how many
     * blocks above it are air, and how many have nothing solid, up to the {@code need} a site needs.
     */
    private static final class Ground {
        private final int sizeZ;
        private final int need;
        private final int[] heights;
        private final int[] air;
        private final int[] clear;

        private Ground(int sizeX, int sizeZ, int need) {
            this.sizeZ = sizeZ;
            this.need = need;
            this.heights = new int[sizeX * sizeZ];
            this.air = new int[heights.length];
            this.clear = new int[heights.length];
        }

        static Ground read(ServerLevel level, int minX, int minZ, int sizeX, int sizeZ, int y, int need) {
            Ground ground = new Ground(sizeX, sizeZ, need);
            int[] heights = ground.heights;
            int[] air = ground.air;
            int[] clear = ground.clear;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    int i = x * sizeZ + z;
                    Integer found = groundNear(level, minX + x, y, minZ + z);
                    heights[i] = found == null ? Integer.MIN_VALUE : found;
                    if (found == null) {
                        continue;
                    }
                    boolean allAir = true;
                    for (int up = 1; up <= need; up++) {
                        BlockState state = level.getBlockState(pos.set(minX + x, found + up, minZ + z));
                        if (!state.getCollisionShape(level, pos).isEmpty()
                                || !state.getFluidState().isEmpty()) {
                            break;
                        }
                        allAir &= state.isAir();
                        clear[i] = up;
                        air[i] = allAir ? up : air[i];
                    }
                }
            }
            return ground;
        }

        int heightAt(int x, int z) {
            return heights[x * sizeZ + z];
        }

        /** A column of the site, its ground at {@code y}, with room for {@code need} above. */
        Column column(int x, int z, int y) {
            int i = x * sizeZ + z;
            if (heights[i] != y) {
                return Column.BLOCKED;
            }
            if (air[i] >= need) {
                return Column.OPEN;
            }
            return clear[i] >= need ? Column.CLEAR : Column.BLOCKED;
        }
    }

    /**
     * Whether a boulder of this shape still has room with its footprint's corner at {@code corner}:
     * the site as {@link #find} looks for it.
     */
    public static boolean isOpen(ServerLevel level, BlockPos corner, BoulderShape shape) {
        int ground = corner.getY() - 1;
        int height = shape.height();
        if (!fits(
                shape.width(),
                shape.depth(),
                (x, z) -> column(level, corner.getX() + x, ground, corner.getZ() + z, height))) {
            return false;
        }
        AABB footprint = new AABB(
                corner.getX(),
                corner.getY(),
                corner.getZ(),
                corner.getX() + shape.width(),
                corner.getY() + height + 1,
                corner.getZ() + shape.depth());
        return level.getEntitiesOfClass(LivingEntity.class, footprint).isEmpty();
    }

    private static boolean inside(BlockPos corner, BoulderShape shape, Predicate<BlockPos> allowed) {
        BlockPos far = corner.offset(shape.width() - 1, 0, shape.depth() - 1);
        return allowed.test(corner)
                && allowed.test(far)
                && allowed.test(new BlockPos(corner.getX(), corner.getY(), far.getZ()))
                && allowed.test(new BlockPos(far.getX(), corner.getY(), corner.getZ()))
                && allowed.test(corner.offset(shape.width() / 2, 0, shape.depth() / 2));
    }

    /**
     * The height of the ground in this column near {@code y}: the highest solid, flat block with
     * nothing solid on it (a plant is all right).
     */
    @Nullable
    private static Integer groundNear(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ground = y + GROUND_SEARCH_UP; ground >= y - GROUND_SEARCH_DOWN; ground--) {
            pos.set(x, ground, z);
            BlockState above = level.getBlockState(pos.above());
            if (isGround(level, pos)
                    && above.getCollisionShape(level, pos.above()).isEmpty()
                    && above.getFluidState().isEmpty()) {
                return ground;
            }
        }
        return null;
    }

    /**
     * One column of a site, its ground at {@code ground}, and room needed up to {@code height} blocks
     * above it and one more.
     */
    private static Column column(ServerLevel level, int x, int ground, int z, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, ground, z);
        if (!isGround(level, pos)) {
            return Column.BLOCKED;
        }
        Column column = Column.OPEN;
        for (int y = ground + 1; y <= ground + height + 1; y++) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (!state.getCollisionShape(level, pos).isEmpty()
                    || !state.getFluidState().isEmpty()) {
                return Column.BLOCKED;
            }
            column = Column.CLEAR;
        }
        return column;
    }

    /** Solid, flat ground: a full block with a sturdy top and no water in it, not part of a boulder. */
    private static boolean isGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isCollisionShapeFullBlock(level, pos)
                && state.isFaceSturdy(level, pos, Direction.UP)
                && state.getFluidState().isEmpty()
                && Boulders.get(level).boulderAt(pos) == null;
    }
}
