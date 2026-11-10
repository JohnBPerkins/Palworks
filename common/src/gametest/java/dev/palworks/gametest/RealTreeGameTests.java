package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.ai.WorkPositions;
import dev.palworks.tree.TreeDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
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

    /** The trunk's columns at its base (relative): TREE, and the rest of a 2x2 trunk. */
    static List<BlockPos> trunkAtBase(GameTestHelper helper) {
        List<BlockPos> trunk = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = TREE.offset(dx, 0, dz);
                if (TreeDetector.isLog(helper.getBlockState(pos))) {
                    trunk.add(pos);
                }
            }
        }
        return trunk;
    }

    /**
     * The gap between the worker's hitbox and the nearest trunk column, in blocks across (the
     * larger of the x and z gaps): 0 when it's touching the trunk.
     */
    static double gapToTrunk(GameTestHelper helper, PokemonEntity worker, List<BlockPos> trunk) {
        var at = helper.relativeVec(worker.position());
        double half = worker.getBbWidth() / 2.0;
        double best = Double.MAX_VALUE;
        for (BlockPos column : trunk) {
            double gapX = Math.max(0, Math.max(column.getX() - (at.x + half), (at.x - half) - (column.getX() + 1)));
            double gapZ = Math.max(0, Math.max(column.getZ() - (at.z + half), (at.z - half) - (column.getZ() + 1)));
            best = Math.min(best, Math.max(gapX, gapZ));
        }
        return best;
    }

    /**
     * How far from the trunk a lumberjack may chop: from the nearest tile round the trunk its
     * hitbox fits on (the tile touching the trunk for most; one further out for Pokémon over a
     * block wide), within 0.35 of that tile's middle, give or take a little. A tile further out
     * than that is a whole block more.
     */
    static double maxChopGap(PokemonEntity worker) {
        double half = worker.getBbWidth() / 2.0;
        return Math.max(0, WorkPositions.closestChopRing(worker) - 0.5 - half) + 0.35 + 0.05;
    }

    /**
     * Grow a real tree, send a lumberjack 6 blocks away, and expect the whole tree chopped from
     * right by the trunk (clearing low leaves to get there) and a sapling replanted, well within the
     * time a chop should take (walk + clearing + 60-tick swing + 20-tick replant). A lumberjack
     * that reaches the tree but never starts chopping, or chops from a few blocks away, fails this.
     */
    private static void chopRealTree(GameTestHelper helper, ResourceKey<ConfiguredFeature<?, ?>> tree,
                                     long seed, String lumberjack, Block sapling) {
        chopRealTree(helper, tree, seed, lumberjack, sapling, () -> {});
    }

    /** {@link #chopRealTree}, with {@code dressTree} run once the tree has grown. */
    private static void chopRealTree(GameTestHelper helper, ResourceKey<ConfiguredFeature<?, ?>> tree,
                                     long seed, String lumberjack, Block sapling, Runnable dressTree) {
        chopRealTree(helper, tree, seed, lumberjack, sapling, Blocks.GRASS_BLOCK, dressTree);
    }

    /** {@link #chopRealTree}, on a floor of {@code floor} (mud for mangroves). */
    private static void chopRealTree(GameTestHelper helper, ResourceKey<ConfiguredFeature<?, ?>> tree,
                                     long seed, String lumberjack, Block sapling, Block floor, Runnable dressTree) {
        Workers.isolate(helper);
        arenaFloor(helper, floor);
        grow(helper, tree, seed);
        dressTree.run();
        helper.assertTrue(!treeBlocks(helper).isEmpty(), "the tree grew");
        List<BlockPos> trunk = trunkAtBase(helper);
        PokemonEntity worker = Workers.sendOut(helper, lumberjack + " level=30", new BlockPos(8, 1, 3));

        // Where it was standing on the last tick the tree was still up: where it chopped from
        double[] lastGap = {-1};
        double[] chopGap = {-1};
        helper.onEachTick(() -> {
            if (!treeBlocks(helper).isEmpty()) {
                lastGap[0] = gapToTrunk(helper, worker, trunk);
            } else if (chopGap[0] < 0) {
                chopGap[0] = lastGap[0];
            }
        });

        helper.succeedWhen(() -> {
            List<BlockPos> left = treeBlocks(helper);
            helper.assertTrue(left.isEmpty(), "tree should be fully chopped, " + left.size() + " logs left");
            helper.assertTrue(chopGap[0] >= 0, "the chop was seen");
            // (A mangrove has only roots at its base, so no trunk there to measure from)
            helper.assertTrue(trunk.isEmpty() || chopGap[0] <= maxChopGap(worker), String.format(
                "%s should chop right by the trunk (at most %.2f away), but chopped from %.2f blocks away",
                lumberjack, maxChopGap(worker), chopGap[0]));
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

    @GameTest(template = Templates.ARENA, batch = "real_jungle_cocoa", timeoutTicks = 300)
    public void machopChopsARealJungleTreeWithCocoaPods(GameTestHelper helper) {
        // Cocoa pods grow on jungle trunks and are solid, which used to get the tree skipped. One
        // at head height on the side facing the worker (it can't stand there), one above it.
        chopRealTree(helper, TreeFeatures.JUNGLE_TREE, 8, "machop", Blocks.JUNGLE_SAPLING, () -> {
            helper.setBlock(TREE.offset(0, 1, -1),
                Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, Direction.SOUTH).setValue(CocoaBlock.AGE, 2));
            helper.setBlock(TREE.offset(1, 2, 0),
                Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, Direction.WEST).setValue(CocoaBlock.AGE, 1));
            helper.assertBlockPresent(Blocks.COCOA, TREE.offset(0, 1, -1));
        });
    }

    @GameTest(template = Templates.ARENA, batch = "real_mangrove", timeoutTicks = 300)
    public void machopChopsARealMangroveAndReplantsItUnderItsTrunk(GameTestHelper helper) {
        // Its lowest block is a root off to one side; the propagule goes back under the trunk,
        // where the tree grew from
        chopRealTree(helper, TreeFeatures.MANGROVE, 9, "machop", Blocks.MANGROVE_PROPAGULE, Blocks.MUD, () -> {});
    }

    @GameTest(template = Templates.ARENA, batch = "real_mangrove_detect")
    public void aRealMangroveIsOneJobReplantedUnderItsTrunk(GameTestHelper helper) {
        arenaFloor(helper, Blocks.MUD);
        grow(helper, TreeFeatures.MANGROVE, 9);
        var level = helper.getLevel();
        BlockPos chopAt = null;
        int bases = 0;
        // From every block the scan could find it by (each root or log standing on ground), it's
        // the same tree, with its job at the same spot
        for (BlockPos rel : treeBlocks(helper)) {
            BlockPos pos = helper.absolutePos(rel);
            if (!TreeDetector.standsOnGround(level, pos, level.getBlockState(pos))) {
                continue;
            }
            bases++;
            TreeDetector.TreeValidationResult tree = TreeDetector.validateTree(level, pos);
            helper.assertTrue(tree.isValid(), "a real mangrove is a tree: " + tree.getReason());
            BlockPos at = TreeDetector.chopPosition(level, tree);
            if (chopAt == null) {
                chopAt = at;
            }
            helper.assertTrue(at.equals(chopAt), "one job spot whichever base it's found from: " + at + " and " + chopAt);
            helper.assertTrue(TreeDetector.getSaplingForTree(level, tree) == Items.MANGROVE_PROPAGULE, "a propagule");
            List<BlockPos> spots = TreeDetector.saplingPositions(level, tree.getLogPositions(), tree.getBasePosition());
            helper.assertTrue(spots.equals(List.of(helper.absolutePos(TREE))),
                "replanted where it grew from, " + helper.absolutePos(TREE) + ", got " + spots);
        }
        helper.assertTrue(bases > 1, "the test mangrove should stand on several roots, got " + bases);
        helper.succeed();
    }

    @GameTest(template = Templates.ARENA, batch = "real_azalea")
    public void aRealAzaleaIsReplantedWithAnAzalea(GameTestHelper helper) {
        // Azalea trees have oak logs, which name an oak sapling
        arenaFloor(helper, Blocks.GRASS_BLOCK);
        grow(helper, TreeFeatures.AZALEA_TREE, 10);
        var level = helper.getLevel();
        TreeDetector.TreeValidationResult tree = TreeDetector.validateTree(level, helper.absolutePos(TREE));
        helper.assertTrue(tree.isValid(), "a real azalea is a tree: " + tree.getReason());
        Item sapling = TreeDetector.getSaplingForTree(level, tree);
        helper.assertTrue(sapling == Items.AZALEA || sapling == Items.FLOWERING_AZALEA,
            "an azalea is replanted with an azalea, got " + sapling);
        helper.succeed();
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

    // ---- Too hemmed in to chop up close: left alone, not chopped from a distance ----

    /**
     * Grow an oak and fence it in 2 blocks out (so the fence doesn't touch the trunk, which would
     * stop it counting as a tree), 2 high, with {@code fence}. The lumberjack can't get right up to
     * the trunk, and can't clear its way in either (only natural leaves are cleared), so it must
     * leave the tree alone. It used to chop it from outside the fence, 3 or more blocks away.
     */
    private static void fencedInTreeIsLeftAlone(GameTestHelper helper, BlockState fence) {
        Workers.isolate(helper);
        arenaFloor(helper, Blocks.GRASS_BLOCK);
        grow(helper, TreeFeatures.OAK, 1);
        List<BlockPos> fenceAt = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                    for (int y = 0; y < 2; y++) {
                        BlockPos pos = TREE.offset(dx, y, dz);
                        helper.setBlock(pos, fence);
                        fenceAt.add(pos);
                    }
                }
            }
        }
        int logs = treeBlocks(helper).size();
        Workers.sendOut(helper, "machop level=30", new BlockPos(8, 1, 3));

        helper.runAfterDelay(250, () -> {
            helper.assertTrue(treeBlocks(helper).size() == logs,
                "the tree should be left alone, but " + (logs - treeBlocks(helper).size()) + " of " + logs + " logs are gone");
            for (BlockPos pos : fenceAt) {
                // (leaves work out their own distance property, so compare the block, and persistence)
                BlockState now = helper.getBlockState(pos);
                helper.assertTrue(now.is(fence.getBlock()) && (!now.hasProperty(LeavesBlock.PERSISTENT)
                    || now.getValue(LeavesBlock.PERSISTENT)), "the fence at " + pos + " should be untouched, but it's " + now);
            }
            helper.succeed();
        });
    }

    @GameTest(template = Templates.ARENA, batch = "real_oak_walled", timeoutTicks = 300)
    public void aTreeWalledInByStoneIsLeftAlone(GameTestHelper helper) {
        fencedInTreeIsLeftAlone(helper, Blocks.COBBLESTONE.defaultBlockState());
    }

    @GameTest(template = Templates.ARENA, batch = "real_oak_hedged", timeoutTicks = 300)
    public void playerPlacedLeavesAreNeverClearedToGetToATree(GameTestHelper helper) {
        fencedInTreeIsLeftAlone(helper, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
    }
}
