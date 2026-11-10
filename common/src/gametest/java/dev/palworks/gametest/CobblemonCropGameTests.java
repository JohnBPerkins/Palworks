package dev.palworks.gametest;

import static dev.palworks.gametest.Scenes.abs;

import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.crop.CropAdapter;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobType;
import dev.palworks.manager.JobManager;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Cobblemon's field crops (mints, vivichoke, revival herb, medicinal leek, hearty grains):
 * harvested in place with their real loot, and planted from their seeds like vanilla crops.
 */
public class CobblemonCropGameTests {

    private static final BlockPos CROP = new BlockPos(3, 1, 3);

    /**
     * Red mint, by id: Cobblemon 1.7.0 has no @JvmField on CobblemonBlocks.RED_MINT (only a Kotlin
     * getter) and 1.8.4 does (no getter), so neither spelling compiles against both. Looked up when
     * a test runs, not when the class loads, which can be before Cobblemon registers its blocks.
     */
    private static Block redMint() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.parse("cobblemon:red_mint"));
    }

    private static final BlockPos START = new BlockPos(3, 1, 1);

    private static BlockState ripe(Block crop) {
        CropBlock cropBlock = (CropBlock) crop;
        return cropBlock.getStateForAge(cropBlock.getMaxAge());
    }

    private static int age(BlockState state) {
        return Integer.parseInt(valueOf(state, "age"));
    }

    /** {@code state} with the named property set to the named value (for Cobblemon's own enums). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, String property, String value) {
        Property p = state.getBlock().getStateDefinition().getProperty(property);
        return state.setValue(p, (Comparable) p.getValue(value).orElseThrow());
    }

    private static String valueOf(BlockState state, String property) {
        Property<?> p = state.getBlock().getStateDefinition().getProperty(property);
        return state.getValue(p).toString().toLowerCase();
    }

    /** Harvest a ripe crop at {@code pos}: it drops {@code produce} and is back to age 0, in place. */
    private static void assertHarvestsInPlace(
            GameTestHelper helper, BlockPos pos, Block soil, Block crop, Item produce) {
        helper.setBlock(pos.below(), soil);
        helper.setBlock(pos, ripe(crop));
        var level = helper.getLevel();
        helper.assertTrue(CropAdapter.isFullyGrown(level, helper.absolutePos(pos)), crop + " is ripe");
        helper.assertTrue(CropAdapter.harvestCrop(level, helper.absolutePos(pos)), crop + " harvests");
        BlockState after = helper.getBlockState(pos);
        helper.assertTrue(after.is(crop) && age(after) == 0, crop + " is reset in place, got " + after);
        helper.assertItemEntityPresent(produce, pos, 2.0);
    }

    // ---- Harvesting ----

    @GameTest(template = Templates.EMPTY)
    public void mintsGiveTheirLeaves(GameTestHelper helper) {
        List<Block> mints = List.of(
                redMint(),
                CobblemonBlocks.BLUE_MINT,
                CobblemonBlocks.CYAN_MINT,
                CobblemonBlocks.PINK_MINT,
                CobblemonBlocks.GREEN_MINT,
                CobblemonBlocks.WHITE_MINT);
        List<Item> leaves = List.of(
                CobblemonItems.RED_MINT_LEAF,
                CobblemonItems.BLUE_MINT_LEAF,
                CobblemonItems.CYAN_MINT_LEAF,
                CobblemonItems.PINK_MINT_LEAF,
                CobblemonItems.GREEN_MINT_LEAF,
                CobblemonItems.WHITE_MINT_LEAF);
        for (int i = 0; i < mints.size(); i++) {
            assertHarvestsInPlace(
                    helper,
                    new BlockPos(1 + i % 3 * 3, 1, 1 + i / 3 * 4),
                    Blocks.FARMLAND,
                    mints.get(i),
                    leaves.get(i));
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void vivichokesGiveVivichokes(GameTestHelper helper) {
        assertHarvestsInPlace(helper, CROP, Blocks.FARMLAND, CobblemonBlocks.VIVICHOKE_SEEDS, CobblemonItems.VIVICHOKE);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void revivalHerbsGiveHerbsAndPepUpFlowers(GameTestHelper helper) {
        assertHarvestsInPlace(helper, CROP, Blocks.FARMLAND, CobblemonBlocks.REVIVAL_HERB, CobblemonItems.REVIVAL_HERB);
        helper.assertItemEntityPresent(CobblemonItems.PEP_UP_FLOWER, CROP, 2.0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void medicinalLeeksAreHarvestedOnTheirWater(GameTestHelper helper) {
        assertHarvestsInPlace(
                helper, CROP, Blocks.WATER, CobblemonBlocks.MEDICINAL_LEEK, CobblemonItems.MEDICINAL_LEEK);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aMutatedRevivalHerbGivesItsHerbOnceThenGrowsBackPlain(GameTestHelper helper) {
        // Mulch can mutate a revival herb into one that drops a power (or mental, mirror, white)
        // herb. Reset in place still mutated, it gave one every time it regrew; replanted by hand,
        // it's a plain herb again.
        helper.setBlock(CROP.below(), Blocks.FARMLAND);
        helper.setBlock(CROP, with(ripe(CobblemonBlocks.REVIVAL_HERB), "mutation", "power"));
        var level = helper.getLevel();

        helper.assertTrue(CropAdapter.harvestCrop(level, helper.absolutePos(CROP)), "it harvests");
        helper.assertItemEntityPresent(CobblemonItems.POWER_HERB, CROP, 2.0);
        BlockState after = helper.getBlockState(CROP);
        helper.assertTrue(after.is(CobblemonBlocks.REVIVAL_HERB) && age(after) == 0, "reset in place, got " + after);
        helper.assertTrue(valueOf(after, "mutation").equals("none"), "no longer mutated, got " + after);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void wildMintStaysWild(GameTestHelper helper) {
        // Wild mint grows on grass, which only wild mint can: reset as farmed mint it would pop off
        helper.setBlock(CROP.below(), Blocks.GRASS_BLOCK);
        helper.setBlock(CROP, with(ripe(redMint()), "is_wild", "true"));

        helper.assertTrue(CropAdapter.harvestCrop(helper.getLevel(), helper.absolutePos(CROP)), "it harvests");
        BlockState after = helper.getBlockState(CROP);
        helper.assertTrue(
                after.is(redMint()) && valueOf(after, "is_wild").equals("true"), "still wild mint, got " + after);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void heartyGrainsAreCutAtTheTop(GameTestHelper helper) {
        // Two blocks tall when grown. Harvested by cutting the top half (its grains), leaving the
        // lower half at its last one-block stage to grow it again, as Cobblemon has it by hand.
        BlockState lower = ripe(CobblemonBlocks.HEARTY_GRAINS);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        helper.setBlock(CROP.below(), Blocks.FARMLAND);
        helper.setBlock(CROP, lower);
        helper.setBlock(CROP.above(), upper);
        var level = helper.getLevel();

        helper.assertTrue(CropAdapter.isFullyGrown(level, helper.absolutePos(CROP)), "the lower half is ripe");
        // The top used to be a harvest job of its own: harvested first, it was cut to a stub on a
        // still-ripe base
        helper.assertFalse(
                CropAdapter.isFullyGrown(level, helper.absolutePos(CROP.above())), "the top isn't a crop of its own");
        helper.assertFalse(
                JobManager.isJobStillThere(level, JobType.HARVEST_CROPS, helper.absolutePos(CROP.above())),
                "no harvest job on the top");

        helper.assertTrue(CropAdapter.harvestCrop(level, helper.absolutePos(CROP)), "it harvests");
        helper.assertBlockNotPresent(CobblemonBlocks.HEARTY_GRAINS, CROP.above());
        BlockState after = helper.getBlockState(CROP);
        helper.assertTrue(
                after.is(CobblemonBlocks.HEARTY_GRAINS)
                        && age(after) == 3
                        && after.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER,
                "the lower half is back to its last one-block stage, got " + after);
        // The top's own loot: 2-3 grains (it used to be a made-up 1-3)
        helper.assertTrue(
                Scenes.itemsOnGround(helper, CobblemonItems.HEARTY_GRAINS) >= 2,
                "the top's grains, got " + Scenes.itemsOnGround(helper, CobblemonItems.HEARTY_GRAINS));
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void galaricaNutsGiveWhatPickingThemDoes(GameTestHelper helper) {
        // NutBushBlock.useWithoutItem: 1-2 nuts, back to age 1. Ours used to give 1-3.
        helper.setBlock(CROP.below(), Blocks.GRASS_BLOCK);
        BlockState ripe = with(CobblemonBlocks.GALARICA_NUT_BUSH.defaultBlockState(), "age", "3");
        CropGameTests.assertEachHarvestGives(helper, ripe, CobblemonItems.GALARICA_NUTS, 1, 2);
        helper.assertTrue(age(helper.getBlockState(CROP)) == 1, "back to age 1");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aBerryBushWithNoBerriesGivesNothing(GameTestHelper helper) {
        // A bush's berries are worked out as it grows. One set straight to ripe (a command, another
        // mod) has none, and picking it by hand gives nothing. Ours used to make up 1-3 berries.
        helper.setBlock(CROP.below(), Blocks.FARMLAND);
        helper.setBlock(CROP, with(CobblemonBlocks.ORAN_BERRY.defaultBlockState(), "age", "5"));
        var level = helper.getLevel();
        helper.assertTrue(CropAdapter.isFullyGrown(level, helper.absolutePos(CROP)), "it looks ripe");

        helper.assertTrue(CropAdapter.harvestCrop(level, helper.absolutePos(CROP)), "it harvests");
        helper.assertItemEntityNotPresent(CobblemonItems.ORAN_BERRY, CROP, 3.0);
        helper.assertTrue(
                helper.getBlockState(CROP).is(CobblemonBlocks.ORAN_BERRY) && age(helper.getBlockState(CROP)) == 3,
                "back to growing (age 3), got " + helper.getBlockState(CROP));
        helper.succeed();
    }

    // ---- Planting ----

    @GameTest(template = Templates.EMPTY)
    public void cobblemonSeedsArePlantedOnFarmland(GameTestHelper helper) {
        helper.setBlock(1, 0, 1, Blocks.FARMLAND);
        helper.setBlock(3, 0, 3, Blocks.SOUL_SAND);
        var level = helper.getLevel();
        BlockPos farmland = abs(helper, 1, 0, 1);
        BlockPos soulSand = abs(helper, 3, 0, 3);

        for (Item seed : List.of(
                CobblemonItems.RED_MINT_SEEDS,
                CobblemonItems.WHITE_MINT_SEEDS,
                CobblemonItems.VIVICHOKE_SEEDS,
                CobblemonItems.REVIVAL_HERB,
                CobblemonItems.HEARTY_GRAINS)) {
            helper.assertTrue(CropAdapter.isSeed(seed), seed + " is a seed (goes in a seed pouch)");
            helper.assertTrue(CropAdapter.canPlantSeed(seed, level, farmland), seed + " on farmland");
            helper.assertFalse(CropAdapter.canPlantSeed(seed, level, soulSand), seed + " not on soul sand");
        }
        // Grows in water, not on farmland
        helper.assertFalse(
                CropAdapter.canPlantSeed(CobblemonItems.MEDICINAL_LEEK, level, farmland), "no leeks on farmland");
        // Not seeds at all
        helper.assertFalse(CropAdapter.isSeed(CobblemonItems.RED_MINT_LEAF), "mint leaves aren't seeds");
        helper.assertFalse(CropAdapter.isSeed(CobblemonItems.VIVICHOKE), "a vivichoke isn't a seed");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void plantingGivesAYoungFarmedPlant(GameTestHelper helper) {
        helper.setBlock(CROP.below(), Blocks.FARMLAND);
        var level = helper.getLevel();

        helper.assertTrue(
                CropAdapter.plantSeed(CobblemonItems.REVIVAL_HERB, level, helper.absolutePos(CROP.below())),
                "it plants");
        BlockState planted = helper.getBlockState(CROP);
        helper.assertTrue(
                planted.is(CobblemonBlocks.REVIVAL_HERB)
                        && age(planted) == 0
                        && valueOf(planted, "mutation").equals("none")
                        && valueOf(planted, "is_wild").equals("false"),
                "a young, plain, farmed revival herb, got " + planted);
        helper.succeed();
    }

    // ---- Real workers ----

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_mint", timeoutTicks = 800)
    public void planterPlantsMintFromItsSeedPouch(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", START);
        var pouch = WorkerInventoryManager.getOrCreate(worker.getPokemon());
        pouch.addItem(new ItemStack(CobblemonItems.RED_MINT_SEEDS, 16));

        helper.succeedWhen(() -> {
            for (int x = 2; x <= 4; x++) {
                helper.assertBlockPresent(redMint(), new BlockPos(x, 1, 5));
            }
            helper.assertTrue(
                    pouch.getTotalItemCount() == 13, "one seed per tile, got " + pouch.getTotalItemCount() + " left");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_chest_cobblemon", timeoutTicks = 1000)
    public void planterTakesTheSeedsItCanPlantFromAChest(GameTestHelper helper) {
        // The chest's first stack is leeks, which grow in water. Carried, they'd count as the seeds
        // it has, so it would never go back for the vivichoke seeds the farmland can take.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        BlockPos chestPos = new BlockPos(6, 1, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos));
        chest.setItem(0, new ItemStack(CobblemonItems.MEDICINAL_LEEK, 8));
        chest.setItem(1, new ItemStack(CobblemonItems.VIVICHOKE_SEEDS, 8));
        Workers.sendOut(helper, "bulbasaur level=20", START);

        helper.succeedWhen(() -> {
            for (int x = 2; x <= 4; x++) {
                helper.assertBlockPresent(CobblemonBlocks.VIVICHOKE_SEEDS, new BlockPos(x, 1, 5));
            }
            helper.assertTrue(
                    chest.getItem(0).is(CobblemonItems.MEDICINAL_LEEK)
                            && chest.getItem(0).getCount() == 8,
                    "the leeks are left in the chest, got " + chest.getItem(0));
        });
    }
}
