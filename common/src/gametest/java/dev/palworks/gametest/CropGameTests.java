package dev.palworks.gametest;

import dev.palworks.crop.CropAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import static dev.palworks.gametest.Scenes.abs;

/** Harvesting (real loot, minus one seed, reset in place), gourds, berries, and planting. */
public class CropGameTests {

    private static final BlockPos CROP = new BlockPos(3, 1, 3);

    private static void crop(GameTestHelper helper, Block soil, BlockState crop) {
        helper.setBlock(CROP.below(), soil);
        helper.setBlock(CROP, crop);
    }

    private static boolean harvest(GameTestHelper helper) {
        return CropAdapter.harvestCrop(helper.getLevel(), helper.absolutePos(CROP));
    }

    /** Harvest a mature crop: it drops its crop item and is reset to age 0, in place. */
    private static void assertHarvestsInPlace(GameTestHelper helper, Block soil, BlockState mature,
                                              Item drop, BlockState reset) {
        crop(helper, soil, mature);
        helper.assertTrue(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(CROP)), mature + " is ripe");

        helper.assertTrue(harvest(helper), "it harvests");
        helper.assertTrue(helper.getBlockState(CROP).equals(reset),
            "it's reset in place to " + reset + ", got " + helper.getBlockState(CROP));
        helper.assertItemEntityPresent(drop, CROP, 2.0);
    }

    // ---- Harvesting ----

    @GameTest(template = Templates.EMPTY)
    public void wheatDropsWheatAndResets(GameTestHelper helper) {
        // Wheat used to drop only seeds (block.asItem() of wheat is wheat seeds)
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        assertHarvestsInPlace(helper, Blocks.FARMLAND, wheat.getStateForAge(7), Items.WHEAT, wheat.getStateForAge(0));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void carrotsResetAndDropCarrots(GameTestHelper helper) {
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        assertHarvestsInPlace(helper, Blocks.FARMLAND, carrots.getStateForAge(7), Items.CARROT, carrots.getStateForAge(0));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void potatoesResetAndDropPotatoes(GameTestHelper helper) {
        CropBlock potatoes = (CropBlock) Blocks.POTATOES;
        assertHarvestsInPlace(helper, Blocks.FARMLAND, potatoes.getStateForAge(7), Items.POTATO, potatoes.getStateForAge(0));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void beetrootDropsBeetrootAndResets(GameTestHelper helper) {
        CropBlock beetroot = (CropBlock) Blocks.BEETROOTS;
        assertHarvestsInPlace(helper, Blocks.FARMLAND, beetroot.getStateForAge(beetroot.getMaxAge()),
            Items.BEETROOT, beetroot.getStateForAge(0));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void netherWartResetsOnSoulSand(GameTestHelper helper) {
        BlockState wart = Blocks.NETHER_WART.defaultBlockState();
        assertHarvestsInPlace(helper, Blocks.SOUL_SAND, wart.setValue(NetherWartBlock.AGE, 3),
            Items.NETHER_WART, wart.setValue(NetherWartBlock.AGE, 0));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void cocoaResetsOnItsLog(GameTestHelper helper) {
        // Cocoa hangs on the side of a jungle log
        helper.setBlock(3, 1, 4, Blocks.JUNGLE_LOG);
        BlockState pod = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, Direction.SOUTH);
        helper.setBlock(CROP, pod.setValue(CocoaBlock.AGE, 2));
        helper.assertTrue(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(CROP)), "the pod is ripe");

        helper.assertTrue(harvest(helper), "it harvests");
        helper.assertTrue(helper.getBlockState(CROP).equals(pod.setValue(CocoaBlock.AGE, 0)), "reset, still on its log");
        helper.assertItemEntityPresent(Items.COCOA_BEANS, CROP, 2.0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void torchflowersArePickedNotReplanted(GameTestHelper helper) {
        // A grown torchflower crop is a plain torchflower. It drops no seed back, so it's picked
        // and the farmland is left empty for the planters
        crop(helper, Blocks.FARMLAND, Blocks.TORCHFLOWER.defaultBlockState());
        helper.assertTrue(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(CROP)), "a grown torchflower");
        helper.assertTrue(harvest(helper), "it harvests");
        helper.assertBlockPresent(Blocks.AIR, CROP);
        helper.assertItemEntityPresent(Items.TORCHFLOWER, CROP, 2.0);

        // One in a flower bed is decoration, not a crop
        helper.setBlock(new BlockPos(5, 0, 5), Blocks.GRASS_BLOCK);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.TORCHFLOWER);
        helper.assertFalse(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 5))),
            "a torchflower on grass isn't harvested");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void pitcherPlantsArePickedNotReplanted(GameTestHelper helper) {
        BlockState lower = Blocks.PITCHER_CROP.defaultBlockState().setValue(PitcherCropBlock.AGE, PitcherCropBlock.MAX_AGE);
        crop(helper, Blocks.FARMLAND, lower);
        helper.setBlock(CROP.above(), lower.setValue(PitcherCropBlock.HALF, DoubleBlockHalf.UPPER));
        var level = helper.getLevel();
        helper.assertTrue(CropAdapter.isFullyGrown(level, helper.absolutePos(CROP)), "a grown pitcher's lower half");
        helper.assertFalse(CropAdapter.isFullyGrown(level, helper.absolutePos(CROP.above())), "not its upper half too");

        helper.assertTrue(harvest(helper), "it harvests");
        helper.assertBlockPresent(Blocks.AIR, CROP);
        helper.assertBlockPresent(Blocks.AIR, CROP.above());
        helper.assertItemEntityPresent(Items.PITCHER_PLANT, CROP, 2.0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void unripeCropsAreLeftAlone(GameTestHelper helper) {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        crop(helper, Blocks.FARMLAND, wheat.getStateForAge(3));

        helper.assertFalse(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(CROP)), "not ripe");
        helper.assertFalse(harvest(helper), "not harvested");
        helper.assertTrue(helper.getBlockState(CROP).equals(wheat.getStateForAge(3)), "untouched");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void eachHarvestPaysOneSeedForTheReplant(GameTestHelper helper) {
        // Mature wheat drops 1 wheat and 1-4 seeds (avg ~2.71) by hand. Harvesting in place takes one
        // seed to pay for the replant, so it averages ~1.71 seeds. Over 200 harvests the totals are
        // far apart (about 343 vs 543, standard deviation ~10), so this tells the two apart reliably.
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        crop(helper, Blocks.FARMLAND, wheat.getStateForAge(7));
        int harvests = 200;
        for (int i = 0; i < harvests; i++) {
            helper.setBlock(CROP, wheat.getStateForAge(7));
            harvest(helper);
        }

        int wheatDropped = Scenes.itemsOnGround(helper, Items.WHEAT);
        int seedsDropped = Scenes.itemsOnGround(helper, Items.WHEAT_SEEDS);
        helper.assertTrue(wheatDropped == harvests, "one wheat per harvest, got " + wheatDropped);
        helper.assertTrue(seedsDropped >= 250 && seedsDropped <= 440,
            "about 1.71 seeds per harvest (one fewer than by hand), got " + seedsDropped + " from " + harvests);
        helper.succeed();
    }

    // ---- Gourds ----

    @GameTest(template = Templates.EMPTY)
    public void pumpkinsAreHarvestedNotTheirStems(GameTestHelper helper) {
        // A stem that has grown its pumpkin is "attached" to it
        helper.setBlock(3, 0, 3, Blocks.FARMLAND);
        helper.setBlock(4, 0, 3, Blocks.DIRT);
        helper.setBlock(CROP, Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
            .setValue(AttachedStemBlock.FACING, Direction.EAST));
        BlockPos pumpkin = new BlockPos(4, 1, 3);
        helper.setBlock(pumpkin, Blocks.PUMPKIN);
        var level = helper.getLevel();

        helper.assertFalse(CropAdapter.isFullyGrown(level, helper.absolutePos(CROP)), "the stem itself isn't harvested");
        helper.assertTrue(helper.absolutePos(pumpkin).equals(CropAdapter.attachedFruitPos(
            helper.getBlockState(CROP), helper.absolutePos(CROP))), "the stem points at its pumpkin");
        helper.assertTrue(CropAdapter.isAttachedFruit(level, helper.absolutePos(pumpkin)), "the pumpkin is its fruit");

        helper.assertTrue(CropAdapter.harvestCrop(level, helper.absolutePos(pumpkin)), "harvesting the pumpkin works");
        helper.assertBlockNotPresent(Blocks.PUMPKIN, pumpkin);
        helper.assertItemEntityPresent(Items.PUMPKIN, pumpkin, 2.0);
        helper.assertBlockPresent(Blocks.PUMPKIN_STEM, CROP); // detached, ready to grow another
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void melonsDropSlices(GameTestHelper helper) {
        helper.setBlock(3, 0, 3, Blocks.FARMLAND);
        helper.setBlock(3, 0, 4, Blocks.DIRT);
        helper.setBlock(CROP, Blocks.ATTACHED_MELON_STEM.defaultBlockState()
            .setValue(AttachedStemBlock.FACING, Direction.SOUTH));
        BlockPos melon = new BlockPos(3, 1, 4);
        helper.setBlock(melon, Blocks.MELON);

        helper.assertTrue(CropAdapter.harvestCrop(helper.getLevel(), helper.absolutePos(melon)), "harvests the melon");
        helper.assertItemEntityPresent(Items.MELON_SLICE, melon, 2.0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aFullyGrownStemWithNoFruitIsLeftAlone(GameTestHelper helper) {
        crop(helper, Blocks.FARMLAND, Blocks.PUMPKIN_STEM.defaultBlockState().setValue(StemBlock.AGE, StemBlock.MAX_AGE));

        helper.assertFalse(CropAdapter.isFullyGrown(helper.getLevel(), helper.absolutePos(CROP)), "nothing to pick yet");
        helper.assertTrue(CropAdapter.attachedFruitPos(helper.getBlockState(CROP), helper.absolutePos(CROP)) == null,
            "no fruit");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aPumpkinWithNoStemIsNotACrop(GameTestHelper helper) {
        helper.setBlock(CROP, Blocks.PUMPKIN);
        helper.assertFalse(CropAdapter.isAttachedFruit(helper.getLevel(), helper.absolutePos(CROP)), "a placed pumpkin");
        helper.succeed();
    }

    // ---- Berries ----

    @GameTest(template = Templates.EMPTY)
    public void sweetBerryBushesGoBackToAgeOne(GameTestHelper helper) {
        BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        crop(helper, Blocks.GRASS_BLOCK, bush.setValue(SweetBerryBushBlock.AGE, 3));

        helper.assertTrue(harvest(helper), "it harvests");
        helper.assertTrue(helper.getBlockState(CROP).getValue(SweetBerryBushBlock.AGE) == 1, "back to age 1");
        helper.assertItemEntityPresent(Items.SWEET_BERRIES, CROP, 2.0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void sweetBerriesGiveWhatPickingThemDoes(GameTestHelper helper) {
        // SweetBerryBushBlock.useWithoutItem on a ripe bush: 1 + 0-1, plus 1 for being ripe. Ours
        // used to give 1-2.
        BlockState ripe = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
        helper.setBlock(CROP.below(), Blocks.GRASS_BLOCK);
        assertEachHarvestGives(helper, ripe, Items.SWEET_BERRIES, 2, 3);
        helper.assertTrue(helper.getBlockState(CROP).getValue(SweetBerryBushBlock.AGE) == 1, "back to age 1");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void glowBerriesGiveOneAndLeaveTheVineBare(GameTestHelper helper) {
        helper.setBlock(CROP.above(), Blocks.STONE);
        BlockState ripe = Blocks.CAVE_VINES.defaultBlockState().setValue(net.minecraft.world.level.block.CaveVines.BERRIES, true);
        assertEachHarvestGives(helper, ripe, Items.GLOW_BERRIES, 1, 1);
        helper.assertFalse(helper.getBlockState(CROP).getValue(net.minecraft.world.level.block.CaveVines.BERRIES), "no berries left");
        helper.succeed();
    }

    /**
     * Harvest {@code ripe} at CROP many times: every harvest gives between {@code min} and
     * {@code max} of {@code item}, and over the run both ends turn up.
     */
    static void assertEachHarvestGives(GameTestHelper helper, BlockState ripe, Item item, int min, int max) {
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 200; i++) {
            Scenes.clearItems(helper);
            helper.setBlock(CROP, ripe);
            helper.assertTrue(harvest(helper), "it harvests");
            int got = Scenes.itemsOnGround(helper, item);
            helper.assertTrue(got >= min && got <= max, "each harvest gives " + min + "-" + max + " " + item + ", got " + got);
            sawMin |= got == min;
            sawMax |= got == max;
        }
        helper.assertTrue(sawMin && sawMax, "both " + min + " and " + max + " turn up over 200 harvests");
    }

    // ---- Planting ----

    @GameTest(template = Templates.EMPTY)
    public void seedsOnlyGoOnTheRightSoil(GameTestHelper helper) {
        helper.setBlock(1, 0, 1, Blocks.FARMLAND);
        helper.setBlock(2, 0, 2, Blocks.DIRT);
        helper.setBlock(3, 0, 3, Blocks.SOUL_SAND);
        var level = helper.getLevel();

        helper.assertTrue(CropAdapter.canPlantSeed(Items.WHEAT_SEEDS, level, abs(helper, 1, 0, 1)), "seeds on farmland");
        helper.assertTrue(CropAdapter.canPlantSeed(Items.CARROT, level, abs(helper, 1, 0, 1)), "carrots on farmland");
        helper.assertFalse(CropAdapter.canPlantSeed(Items.WHEAT_SEEDS, level, abs(helper, 2, 0, 2)), "not on dirt");
        helper.assertTrue(CropAdapter.canPlantSeed(Items.NETHER_WART, level, abs(helper, 3, 0, 3)), "wart on soul sand");
        helper.assertFalse(CropAdapter.canPlantSeed(Items.NETHER_WART, level, abs(helper, 1, 0, 1)), "wart not on farmland");
        helper.assertFalse(CropAdapter.canPlantSeed(Items.WHEAT_SEEDS, level, abs(helper, 3, 0, 3)), "seeds not on soul sand");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void plantingPutsAYoungCropOnTheFarmland(GameTestHelper helper) {
        helper.setBlock(CROP.below(), Blocks.FARMLAND);

        helper.assertTrue(CropAdapter.plantSeed(Items.WHEAT_SEEDS, helper.getLevel(), helper.absolutePos(CROP.below())),
            "it plants");
        helper.assertTrue(helper.getBlockState(CROP).equals(((CropBlock) Blocks.WHEAT).getStateForAge(0)), "a young wheat");
        helper.assertFalse(CropAdapter.plantSeed(Items.WHEAT_SEEDS, helper.getLevel(), helper.absolutePos(CROP.below())),
            "not again on top of it");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void farmlandIsAPlantingSurface(GameTestHelper helper) {
        helper.setBlock(1, 0, 1, Blocks.FARMLAND);
        helper.setBlock(2, 0, 2, Blocks.SOUL_SAND);
        helper.setBlock(3, 0, 3, Blocks.DIRT);
        var level = helper.getLevel();

        helper.assertTrue(CropAdapter.isPlantableSurface(level, abs(helper, 1, 0, 1)), "farmland");
        helper.assertTrue(CropAdapter.isPlantableSurface(level, abs(helper, 2, 0, 2)), "soul sand");
        helper.assertFalse(CropAdapter.isPlantableSurface(level, abs(helper, 3, 0, 3)), "not dirt");
        helper.succeed();
    }
}
