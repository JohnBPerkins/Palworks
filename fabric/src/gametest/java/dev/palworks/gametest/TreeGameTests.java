package dev.palworks.gametest;

import dev.palworks.tree.TreeDetector;
import dev.palworks.tree.TreeDetector.TreeValidationResult;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.palworks.gametest.Scenes.abs;

/** Tree detection (what counts as a tree), canopy breaking, and where and what gets replanted. */
public class TreeGameTests implements FabricGameTest {

    private static TreeValidationResult validate(GameTestHelper helper, int x, int y, int z) {
        return TreeDetector.validateTree(helper.getLevel(), abs(helper, x, y, z));
    }

    // ---- What counts as a tree ----

    @GameTest(template = EMPTY_STRUCTURE)
    public void naturalOakIsATree(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));

        TreeValidationResult tree = validate(helper, 3, 1, 3);
        helper.assertTrue(tree.isValid(), "a natural oak should be a tree: " + tree.getReason());
        helper.assertTrue(tree.getLogPositions().size() == 4, "all 4 logs belong to it");
        helper.assertTrue(tree.getBasePosition().equals(abs(helper, 3, 1, 3)), "its base is the bottom log");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void detectionWorksFromAnyLog(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));

        TreeValidationResult tree = validate(helper, 3, 3, 3);
        helper.assertTrue(tree.isValid(), "starting halfway up the trunk still finds the tree");
        helper.assertTrue(tree.getBasePosition().equals(abs(helper, 3, 1, 3)), "and its base");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void playerPlacedLeavesDontMakeATree(GameTestHelper helper) {
        // A log pillar dressed with placed leaves is a build, not a tree
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.placedLeaves(Blocks.OAK_LEAVES));

        helper.assertFalse(validate(helper, 3, 1, 3).isValid(), "placed leaves don't count");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void logsTouchingABuildAreNotATree(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        helper.setBlock(4, 2, 3, Blocks.OAK_PLANKS);

        helper.assertFalse(validate(helper, 3, 1, 3).isValid(), "a log against planks is part of a build");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void plantsBesideTheTrunkDontMatter(GameTestHelper helper) {
        // Short grass and flowers often grow right next to a trunk in plains and forests
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        helper.setBlock(4, 1, 3, Blocks.SHORT_GRASS);
        helper.setBlock(2, 1, 3, Blocks.POPPY);
        helper.setBlock(3, 1, 4, Blocks.FERN);

        TreeValidationResult tree = validate(helper, 3, 1, 3);
        helper.assertTrue(tree.isValid(), "walk-through plants beside the trunk are fine: " + tree.getReason());
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void aTrunkStandingOnStoneIsNotATree(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));

        helper.assertFalse(validate(helper, 3, 1, 3).isValid(), "trees grow from soil");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void soilTheSaplingGrowsOnCountsAsGround(GameTestHelper helper) {
        // Not in the ground tags, but an oak sapling can grow on farmland, so the tree is rooted
        Scenes.floor(helper, Blocks.FARMLAND);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));

        helper.assertTrue(validate(helper, 3, 1, 3).isValid(), "farmland is soil for an oak");
        helper.assertTrue(TreeDetector.standsOnGround(helper.getLevel(), abs(helper, 3, 1, 3),
            Blocks.OAK_LOG.defaultBlockState()), "so its bottom log can start a chop job");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void tooFewLeavesIsNotATree(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(3, y, 3, Blocks.OAK_LOG);
        }
        helper.setBlock(3, 5, 3, Scenes.naturalLeaves(Blocks.OAK_LEAVES));

        helper.assertFalse(validate(helper, 3, 1, 3).isValid(), "one leaf is a pillar, not a tree");
        helper.succeed();
    }

    // ---- Block checks that need the world's tags ----

    @GameTest(template = EMPTY_STRUCTURE)
    public void logLeafAndGroundChecks(GameTestHelper helper) {
        helper.assertTrue(TreeDetector.isLog(Blocks.OAK_LOG.defaultBlockState()), "oak log is a log");
        helper.assertTrue(TreeDetector.isLog(Blocks.CRIMSON_STEM.defaultBlockState()), "stems are logs");
        helper.assertFalse(TreeDetector.isLog(Blocks.OAK_PLANKS.defaultBlockState()), "planks aren't");
        helper.assertTrue(TreeDetector.isTreeBlock(Blocks.MANGROVE_ROOTS.defaultBlockState()), "roots are tree");

        helper.assertTrue(TreeDetector.isNaturalLeaf(Scenes.naturalLeaves(Blocks.OAK_LEAVES)), "natural leaves");
        helper.assertFalse(TreeDetector.isNaturalLeaf(Scenes.placedLeaves(Blocks.OAK_LEAVES)), "placed leaves");

        for (Block ground : List.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL, Blocks.MUD,
                Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MOSS_BLOCK, Blocks.CRIMSON_NYLIUM, Blocks.NETHERRACK)) {
            helper.assertTrue(TreeDetector.isValidGroundBlock(ground.defaultBlockState()), ground + " is ground");
        }
        helper.assertFalse(TreeDetector.isValidGroundBlock(Blocks.STONE.defaultBlockState()), "stone isn't ground");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void apricornTreesAreNotLumbered(GameTestHelper helper) {
        // Cobblemon's apricorn trees are harvested for apricorns, not chopped
        Block apricornLog = block(helper, "cobblemon:apricorn_log");
        helper.assertFalse(TreeDetector.isLog(apricornLog.defaultBlockState()), "apricorn logs aren't logs here");
        helper.succeed();
    }

    // ---- Mangroves ----

    @GameTest(template = EMPTY_STRUCTURE)
    public void mangroveStandingInWaterIsATree(GameTestHelper helper) {
        // Roots arch from the mud up to the trunk, with water around them
        Scenes.floor(helper, Blocks.MUD);
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(x, 1, z, Blocks.WATER);
            }
        }
        Scenes.tree(helper, 3, 3, 6, Blocks.MANGROVE_LOG, Scenes.naturalLeaves(Blocks.MANGROVE_LEAVES));
        // tree() put logs from y=1: the bottom two are the roots, standing in the water
        helper.setBlock(3, 1, 3, Blocks.MANGROVE_ROOTS);
        helper.setBlock(3, 2, 3, Blocks.MANGROVE_ROOTS);

        TreeValidationResult tree = validate(helper, 3, 4, 3);
        helper.assertTrue(tree.isValid(), "a mangrove in water is a tree: " + tree.getReason());
        helper.assertTrue(tree.getLogPositions().contains(abs(helper, 3, 1, 3)), "its roots are chopped with it");
        helper.assertTrue(tree.getBasePosition().equals(abs(helper, 3, 1, 3)), "its base is the bottom root");
        helper.assertTrue(TreeDetector.getSaplingForTree(helper.getLevel(), tree) == Items.MANGROVE_PROPAGULE,
            "it's replanted with a propagule");
        helper.assertTrue(TreeDetector.saplingPositions(helper.getLevel(), tree.getLogPositions(),
            tree.getBasePosition()).size() == 1, "one propagule");
        helper.succeed();
    }

    // ---- Nether trees (huge fungi) ----

    /** A crimson fungus: stem from y=1 to y=height at (x, z), wart-block canopy around its top. */
    private static void crimsonFungus(GameTestHelper helper, int x, int z, int height) {
        for (int y = 1; y <= height; y++) {
            helper.setBlock(x, y, z, Blocks.CRIMSON_STEM);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = height - 1; y <= height + 1; y++) {
                    boolean stem = dx == 0 && dz == 0 && y <= height;
                    if (!stem) {
                        helper.setBlock(x + dx, y, z + dz, Blocks.NETHER_WART_BLOCK);
                    }
                }
            }
        }
        helper.setBlock(x + 1, height - 1, z + 1, Blocks.SHROOMLIGHT);
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void hugeFungusIsATreeAndItsCanopyComesDownWithIt(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.CRIMSON_NYLIUM);
        crimsonFungus(helper, 3, 3, 4);

        TreeValidationResult tree = validate(helper, 3, 1, 3);
        helper.assertTrue(tree.isValid(), "a huge fungus is a tree (its wart blocks are its canopy): " + tree.getReason());
        helper.assertTrue(TreeDetector.isNetherStem(Blocks.CRIMSON_STEM.defaultBlockState()), "needs Nether to chop");

        Set<BlockPos> canopy = TreeDetector.findNetherCanopyToBreak(
            helper.getLevel(), tree.getLogPositions(), abs(helper, 3, 1, 3));
        // Three 3x3 layers around the top, minus the two stem blocks inside them
        helper.assertTrue(canopy.size() == 27 - 2, "all 25 canopy blocks around it are broken, got " + canopy.size());
        helper.assertTrue(canopy.contains(abs(helper, 4, 3, 4)), "the shroomlight too");
        helper.assertTrue(TreeDetector.getSaplingForTree(helper.getLevel(), tree) == Items.CRIMSON_FUNGUS,
            "it's replanted with a crimson fungus");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void aNeighbouringFungusKeepsItsCanopy(GameTestHelper helper) {
        // Two fungi 3 apart: their canopies touch. Chopping one must leave the other's canopy
        Scenes.floor(helper, Blocks.CRIMSON_NYLIUM);
        crimsonFungus(helper, 2, 3, 4);
        crimsonFungus(helper, 5, 3, 4);

        TreeValidationResult tree = validate(helper, 2, 1, 3);
        Set<BlockPos> canopy = TreeDetector.findNetherCanopyToBreak(
            helper.getLevel(), tree.getLogPositions(), abs(helper, 2, 1, 3));
        helper.assertTrue(canopy.contains(abs(helper, 1, 3, 3)), "its own canopy is broken");
        helper.assertFalse(canopy.contains(abs(helper, 6, 3, 3)), "the far side of the neighbour's canopy isn't");
        helper.assertFalse(canopy.contains(abs(helper, 4, 3, 3)), "nor the side nearer the neighbour's stem");
        helper.succeed();
    }

    // ---- Where to replant ----

    @GameTest(template = EMPTY_STRUCTURE)
    public void aTwoByTwoTrunkIsReplantedInAllFourSpots(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockState leaves = Scenes.naturalLeaves(Blocks.DARK_OAK_LEAVES);
        for (int x = 3; x <= 4; x++) {
            for (int z = 3; z <= 4; z++) {
                Scenes.tree(helper, x, z, 4, Blocks.DARK_OAK_LOG, leaves);
            }
        }
        // tree() surrounded each trunk with leaves, including the other trunks' columns: restore them
        for (int x = 3; x <= 4; x++) {
            for (int z = 3; z <= 4; z++) {
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(x, y, z, Blocks.DARK_OAK_LOG);
                }
            }
        }

        TreeValidationResult tree = validate(helper, 3, 1, 3);
        helper.assertTrue(tree.isValid(), "a dark oak is a tree: " + tree.getReason());
        Set<BlockPos> spots = new HashSet<>(TreeDetector.saplingPositions(
            helper.getLevel(), tree.getLogPositions(), tree.getBasePosition()));
        Set<BlockPos> trunk = Set.of(abs(helper, 3, 1, 3), abs(helper, 4, 1, 3), abs(helper, 3, 1, 4), abs(helper, 4, 1, 4));
        helper.assertTrue(spots.equals(trunk), "dark oak needs 4 saplings where its trunk stood, got " + spots);
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void aOneByOneTrunkIsReplantedOnce(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 3, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));

        TreeValidationResult tree = validate(helper, 3, 1, 3);
        List<BlockPos> spots = TreeDetector.saplingPositions(helper.getLevel(), tree.getLogPositions(), tree.getBasePosition());
        helper.assertTrue(spots.equals(List.of(abs(helper, 3, 1, 3))), "one sapling at its base, got " + spots);
        helper.succeed();
    }

    // ---- Planting saplings ----

    @GameTest(template = EMPTY_STRUCTURE)
    public void saplingsOnlyGoWhereTheyCanGrow(GameTestHelper helper) {
        helper.setBlock(1, 0, 1, Blocks.GRASS_BLOCK);
        helper.setBlock(2, 0, 2, Blocks.STONE);
        helper.setBlock(3, 0, 3, Blocks.GRASS_BLOCK);
        helper.setBlock(3, 1, 3, Blocks.OAK_LOG); // occupied
        helper.setBlock(4, 0, 4, Blocks.CRIMSON_NYLIUM);
        var level = helper.getLevel();

        helper.assertTrue(TreeDetector.canPlantSaplingAt(level, abs(helper, 1, 1, 1), Items.OAK_SAPLING), "oak on grass");
        helper.assertFalse(TreeDetector.canPlantSaplingAt(level, abs(helper, 2, 1, 2), Items.OAK_SAPLING), "not on stone");
        helper.assertFalse(TreeDetector.canPlantSaplingAt(level, abs(helper, 3, 1, 3), Items.OAK_SAPLING), "not in a block");
        helper.assertTrue(TreeDetector.canPlantSaplingAt(level, abs(helper, 4, 1, 4), Items.CRIMSON_FUNGUS), "fungus on nylium");
        helper.assertFalse(TreeDetector.canPlantSaplingAt(level, abs(helper, 4, 1, 4), Items.OAK_SAPLING), "oak not on nylium");
        helper.assertFalse(TreeDetector.canPlantSaplingAt(level, abs(helper, 1, 1, 1), Items.STICK), "a stick isn't a sapling");

        helper.assertTrue(TreeDetector.plantSapling(level, abs(helper, 1, 1, 1), Items.OAK_SAPLING), "plants it");
        helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(1, 1, 1));
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void propagulesCanBePlantedUnderwater(GameTestHelper helper) {
        // A chopped mangrove's base is often underwater once its roots are gone
        helper.setBlock(3, 0, 3, Blocks.MUD);
        helper.setBlock(3, 1, 3, Blocks.WATER);
        BlockPos spot = abs(helper, 3, 1, 3);

        helper.assertFalse(TreeDetector.canPlantSaplingAt(helper.getLevel(), spot, Items.OAK_SAPLING),
            "an oak sapling can't go in water");
        helper.assertTrue(TreeDetector.plantSapling(helper.getLevel(), spot, Items.MANGROVE_PROPAGULE), "a propagule can");
        BlockState planted = helper.getLevel().getBlockState(spot);
        helper.assertTrue(planted.is(Blocks.MANGROVE_PROPAGULE), "it's a propagule");
        helper.assertTrue(planted.getValue(BlockStateProperties.WATERLOGGED), "and waterlogged");
        helper.succeed();
    }

    // ---- Cobblemon's trees ----

    @GameTest(template = EMPTY_STRUCTURE)
    public void saccharineTreesFindTheirSapling(GameTestHelper helper) {
        // Found by name like any modded tree: cobblemon:saccharine_log -> cobblemon:saccharine_sapling
        Block log = block(helper, "cobblemon:saccharine_log");
        Item sapling = TreeDetector.saplingFor(log);
        helper.assertTrue(sapling != null
                && BuiltInRegistries.ITEM.getKey(sapling).toString().equals("cobblemon:saccharine_sapling"),
            "saccharine log should find cobblemon:saccharine_sapling, got " + sapling);
        helper.succeed();
    }

    private static Block block(GameTestHelper helper, String id) {
        var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
        if (block.isEmpty()) {
            helper.fail(id + " isn't registered in this Cobblemon version");
        }
        return block.get();
    }
}
