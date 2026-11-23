package dev.palworks.boulder;

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
     * A site for a boulder of this shape, round {@code around} (up to {@code radius} blocks off), at
     * most {@code tries} spots tried at random: the corner of its footprint, just above the ground
     * (where its lowest blocks go). Only where {@code allowed} has the footprint's corners and
     * middle (the worker's work range). Null if none of the spots tried will do.
     */
    @Nullable
    public static BlockPos find(
            ServerLevel level,
            BoulderShape shape,
            BlockPos around,
            int radius,
            Predicate<BlockPos> allowed,
            RandomGenerator random,
            int tries) {
        for (int i = 0; i < tries; i++) {
            int x = around.getX() + random.nextInt(-radius, radius + 1) - shape.width() / 2;
            int z = around.getZ() + random.nextInt(-radius, radius + 1) - shape.depth() / 2;
            Integer ground = groundNear(level, x, around.getY(), z);
            if (ground == null) {
                continue;
            }
            BlockPos corner = new BlockPos(x, ground + 1, z);
            if (inside(corner, shape, allowed) && isOpen(level, corner, shape)) {
                return corner;
            }
        }
        return null;
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

    /** The height of the ground in this column near {@code y}: the highest solid, flat block with air on it. */
    @Nullable
    private static Integer groundNear(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ground = y + GROUND_SEARCH_UP; ground >= y - GROUND_SEARCH_DOWN; ground--) {
            pos.set(x, ground, z);
            if (isGround(level, pos) && level.getBlockState(pos.above()).isAir()) {
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
