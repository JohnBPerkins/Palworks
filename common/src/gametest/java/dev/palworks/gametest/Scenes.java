package dev.palworks.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Builds small test scenes inside a GameTest's 8x8x8 area, in relative coordinates (y=0 is the
 * area's floor layer). Tests pass absolute positions (helper.absolutePos) to Palworks code.
 */
public final class Scenes {
    private Scenes() {}

    /** Fill the floor layer (y=0) of the whole area. */
    static void floor(GameTestHelper helper, Block block) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 0, z, block);
            }
        }
    }

    /** Natural leaves (not player placed), close enough to a log that they don't decay mid-test. */
    public static BlockState naturalLeaves(Block leaves) {
        return leaves.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
    }

    /** Player-placed leaves. */
    static BlockState placedLeaves(Block leaves) {
        return leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true).setValue(LeavesBlock.DISTANCE, 1);
    }

    /**
     * A simple tree: a trunk of {@code log} from y=1 to y=height at (x, z), with a canopy of
     * {@code leaves} around its top two logs and on top. The ground under it isn't touched.
     */
    public static void tree(GameTestHelper helper, int x, int z, int height, Block log, BlockState leaves) {
        for (int y = 1; y <= height; y++) {
            helper.setBlock(x, y, z, log);
        }
        for (int y = height - 1; y <= height + 1; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean trunk = dx == 0 && dz == 0 && y <= height;
                    if (!trunk) {
                        helper.setBlock(x + dx, y, z + dz, leaves);
                    }
                }
            }
        }
    }

    static BlockPos abs(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /**
     * Where a test's dropped items are looked for: its area and a few blocks round it. Dropped
     * items scatter and slide (a crop at the edge has lost one past a 1-block margin), and tests
     * are spaced far enough apart that this never reaches a neighbour's (see
     * StructureGridSpawnerMixin).
     */
    private static AABB itemArea(GameTestHelper helper) {
        return helper.getBounds().inflate(4);
    }

    /** Total count of an item lying on the ground anywhere in the test area. */
    static int itemsOnGround(GameTestHelper helper, Item item) {
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, itemArea(helper))) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    /** Remove every item lying on the ground in the test area. */
    static void clearItems(GameTestHelper helper) {
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, itemArea(helper)).forEach(ItemEntity::discard);
    }

    static void air(GameTestHelper helper, int x, int y, int z) {
        helper.setBlock(x, y, z, Blocks.AIR);
    }

    /**
     * Keep the chunks covering this box (relative to the test) loaded and ticking, as they are round
     * a player in game: the test world only loads the chunks round the tests themselves. Returns
     * what lets them go again.
     */
    static Runnable keepLoaded(GameTestHelper helper, BlockPos fromRel, BlockPos toRel) {
        var level = helper.getLevel();
        BlockPos a = helper.absolutePos(fromRel);
        BlockPos b = helper.absolutePos(toRel);
        int minX = Math.min(a.getX(), b.getX()) >> 4;
        int maxX = Math.max(a.getX(), b.getX()) >> 4;
        int minZ = Math.min(a.getZ(), b.getZ()) >> 4;
        int maxZ = Math.max(a.getZ(), b.getZ()) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        return () -> {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    level.setChunkForced(x, z, false);
                }
            }
        };
    }
}
