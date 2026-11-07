package dev.palworks.gametest;

import dev.palworks.tree.TreeDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Trees grown by Minecraft's own worldgen tree features (real shapes: branches, low leaves,
 * diagonal trunks, big canopies), instead of the tidy hand-built trees in TreeGameTests, with a
 * real lumberjack sent at them. Uses the 16x24x16 arena so full-size trees fit.
 */
public class RealTreeGameTests {

    /** Where trees grow in the arena (relative), on its grass floor. */
    static final BlockPos TREE = new BlockPos(8, 1, 9);

    /** Grow a worldgen tree at TREE. Fails the test if the feature couldn't be placed. */
    static void grow(GameTestHelper helper, ResourceKey<ConfiguredFeature<?, ?>> tree, long seed) {
        var level = helper.getLevel();
        var feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolderOrThrow(tree).value();
        boolean placed = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed),
            helper.absolutePos(TREE));
        if (!placed) {
            helper.fail("couldn't grow " + tree.location() + " (seed " + seed + ")");
        }
    }

    /** All the tree blocks (logs, roots) in the arena, relative. */
    static List<BlockPos> treeBlocks(GameTestHelper helper) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 1; y < 24; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (TreeDetector.isTreeBlock(helper.getBlockState(pos))) {
                        blocks.add(pos);
                    }
                }
            }
        }
        return blocks;
    }

    static void arenaFloor(GameTestHelper helper, Block block) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(x, 0, z, block);
            }
        }
    }

    /**
     * Grow a real tree, send a lumberjack 6 blocks away, and expect the whole tree chopped and a
     * sapling replanted well within the time a chop should take (walk + 60-tick swing + 20-tick
     * replant). A lumberjack that reaches the tree but never starts chopping fails this.
     */
    private static void chopRealTree(GameTestHelper helper, ResourceKey<ConfiguredFeature<?, ?>> tree,
                                     long seed, String lumberjack, Block sapling) {
        Workers.isolate(helper);
        arenaFloor(helper, Blocks.GRASS_BLOCK);
        grow(helper, tree, seed);
        helper.assertTrue(!treeBlocks(helper).isEmpty(), "the tree grew");
        Workers.sendOut(helper, lumberjack + " level=30", new BlockPos(8, 1, 3));

        helper.succeedWhen(() -> {
            List<BlockPos> left = treeBlocks(helper);
            helper.assertTrue(left.isEmpty(), "tree should be fully chopped, " + left.size() + " logs left");
            helper.assertBlockPresent(sapling, TREE);
        });
    }

    // Timeout: a chop should be done in about 100-150 ticks. 300 leaves room for a walk around the
    // canopy, but not for waiting out the 30s reservation expiry (thousands of test ticks).

    @GameTest(template = Templates.ARENA, batch = "real_oak", timeoutTicks = 300)
    public void machopChopsARealOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.OAK, 1, "machop", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_fancy_oak", timeoutTicks = 300)
    public void machopChopsARealBigOak(GameTestHelper helper) {
        // Big oaks' branches join the trunk diagonally
        chopRealTree(helper, TreeFeatures.FANCY_OAK, 2, "machop", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_birch", timeoutTicks = 300)
    public void machopChopsARealBirch(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.BIRCH, 3, "machop", Blocks.BIRCH_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_spruce", timeoutTicks = 300)
    public void machopChopsARealSpruce(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.SPRUCE, 4, "machop", Blocks.SPRUCE_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_acacia", timeoutTicks = 300)
    public void machopChopsARealAcacia(GameTestHelper helper) {
        // Acacia trunks bend diagonally
        chopRealTree(helper, TreeFeatures.ACACIA, 5, "machop", Blocks.ACACIA_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_cherry", timeoutTicks = 300)
    public void machopChopsARealCherry(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.CHERRY, 6, "machop", Blocks.CHERRY_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_dark_oak", timeoutTicks = 300)
    public void machopChopsARealDarkOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.DARK_OAK, 7, "machop", Blocks.DARK_OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_oak_scyther", timeoutTicks = 300)
    public void scytherChopsARealOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.OAK, 1, "scyther", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_oak_pinsir", timeoutTicks = 300)
    public void pinsirChopsARealOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.OAK, 1, "pinsir", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_oak_heracross", timeoutTicks = 300)
    public void heracrossChopsARealOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.OAK, 1, "heracross", Blocks.OAK_SAPLING);
    }

    // Big lumberjacks: they can't stand on every open tile (a wide hitbox needs room around it),
    // so their walk can end beside the spot picked for them.

    @GameTest(template = Templates.ARENA, batch = "real_oak_hariyama", timeoutTicks = 300)
    public void hariyamaChopsARealOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.OAK, 1, "hariyama", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_fancy_oak_hariyama", timeoutTicks = 300)
    public void hariyamaChopsARealBigOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.FANCY_OAK, 2, "hariyama", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_oak_machamp", timeoutTicks = 300)
    public void machampChopsARealOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.OAK, 1, "machamp", Blocks.OAK_SAPLING);
    }

    @GameTest(template = Templates.ARENA, batch = "real_dark_oak_conkeldurr", timeoutTicks = 300)
    public void conkeldurrChopsARealDarkOak(GameTestHelper helper) {
        chopRealTree(helper, TreeFeatures.DARK_OAK, 7, "conkeldurr", Blocks.DARK_OAK_SAPLING);
    }
}
