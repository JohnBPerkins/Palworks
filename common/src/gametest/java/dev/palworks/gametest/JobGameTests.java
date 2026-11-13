package dev.palworks.gametest;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.crop.CropAdapter;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobBlockTags;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.tree.TreeDetector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether each kind of job still exists in the world (JobChecks.isJobStillThere): what workers
 * re-check on their way to a job and as they work it, so they drop jobs that disappeared.
 */
public class JobGameTests {

    private static boolean there(GameTestHelper helper, JobType type, BlockPos rel) {
        return JobChecks.isJobStillThere(helper.getLevel(), type, helper.absolutePos(rel));
    }

    @GameTest(template = Templates.EMPTY)
    public void plainTerrainIsNeverWork(GameTestHelper helper) {
        // The chunk scan skips whole sections made only of these (JobChecks.canNeverBeWork), so
        // none may be anything the scan looks for: checked with the scan's own checks
        BlockPos spot = new BlockPos(3, 1, 3);
        List<Block> blocks = new ArrayList<>(JobChecks.neverWorkBlocks());
        blocks.add(Blocks.AIR);
        for (Block block : blocks) {
            helper.setBlock(spot, block);
            helper.setBlock(spot.above(), Blocks.AIR);
            BlockState state = helper.getBlockState(spot);
            BlockPos abs = helper.absolutePos(spot);
            String what = block + " ";
            helper.assertTrue(JobChecks.canNeverBeWork(state), what + "counts as never work");
            helper.assertFalse(state.is(JobBlockTags.FURNACES), what + "is a furnace");
            helper.assertFalse(state.is(JobBlockTags.ORES), what + "is an ore");
            helper.assertFalse(JobChecks.isChest(state), what + "is a chest");
            helper.assertFalse(TreeDetector.isTreeBlock(state), what + "is part of a tree");
            helper.assertTrue(CropAdapter.attachedFruitPos(state, abs) == null, what + "has a fruit");
            helper.assertFalse(CropAdapter.isFullyGrown(helper.getLevel(), abs), what + "is a ripe crop");
            helper.assertFalse(CropAdapter.isPlantableSurface(helper.getLevel(), abs), what + "can be planted in");
        }
        // And the soils seeds do go in aren't on the list
        helper.assertFalse(JobChecks.canNeverBeWork(Blocks.FARMLAND.defaultBlockState()), "farmland");
        helper.assertFalse(JobChecks.canNeverBeWork(Blocks.SOUL_SAND.defaultBlockState()), "soul sand");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void harvestJobsAreThereWhileTheCropIsRipe(GameTestHelper helper) {
        BlockPos crop = new BlockPos(3, 1, 3);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;

        helper.setBlock(crop, wheat.getStateForAge(7));
        helper.assertTrue(there(helper, JobType.HARVEST_CROPS, crop), "ripe wheat");
        helper.setBlock(crop, wheat.getStateForAge(0));
        helper.assertFalse(there(helper, JobType.HARVEST_CROPS, crop), "already harvested (reset)");
        helper.setBlock(crop, Blocks.AIR);
        helper.assertFalse(there(helper, JobType.HARVEST_CROPS, crop), "gone");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void plantingJobsAreThereWhileTheFarmlandIsEmpty(GameTestHelper helper) {
        BlockPos spot = new BlockPos(3, 1, 3);
        helper.setBlock(spot.below(), Blocks.FARMLAND);

        helper.assertTrue(there(helper, JobType.PLANT_SEEDS, spot), "empty farmland");
        helper.setBlock(spot, ((CropBlock) Blocks.WHEAT).getStateForAge(0));
        helper.assertFalse(there(helper, JobType.PLANT_SEEDS, spot), "already planted");
        helper.setBlock(spot, Blocks.AIR);
        helper.setBlock(spot.below(), Blocks.DIRT);
        helper.assertFalse(there(helper, JobType.PLANT_SEEDS, spot), "not farmland any more");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void chopJobsAreThereWhileTheLogIs(GameTestHelper helper) {
        BlockPos log = new BlockPos(3, 1, 3);
        helper.setBlock(log, Blocks.OAK_LOG);
        helper.assertTrue(there(helper, JobType.CHOP_TREE, log), "a log");
        helper.setBlock(log, Blocks.AIR);
        helper.assertFalse(there(helper, JobType.CHOP_TREE, log), "chopped by someone else");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void furnaceJobsAreThereWhileItHasSomethingToSmeltAndNoFuel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.FURNACE);
        var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));

        helper.assertFalse(there(helper, JobType.FUEL_FURNACE, pos), "empty: nothing to smelt");
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        helper.assertTrue(there(helper, JobType.FUEL_FURNACE, pos), "raw iron and no fuel");
        furnace.setItem(1, new ItemStack(Items.COAL, 1));
        helper.assertFalse(there(helper, JobType.FUEL_FURNACE, pos), "it has its own fuel");
        furnace.setItem(1, ItemStack.EMPTY);
        furnace.setItem(0, new ItemStack(Items.DIAMOND, 1));
        helper.assertFalse(there(helper, JobType.FUEL_FURNACE, pos), "diamonds don't smelt");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aFurnaceAWorkerLitIsStillItsJobUntilItsEmptied(GameTestHelper helper) {
        // The worker keeps it lit, smelt after smelt, checking it's still there as it goes: being
        // lit (by it) mustn't count as the job having gone, but being emptied must
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.FURNACE);
        var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        FurnaceHelper.igniteFurnace(helper.getLevel(), helper.absolutePos(pos), FurnaceHelper.getBurnDuration());

        helper.assertTrue(there(helper, JobType.FUEL_FURNACE, pos), "lit by a worker, raw iron left to smelt");
        furnace.setItem(0, ItemStack.EMPTY);
        helper.assertFalse(there(helper, JobType.FUEL_FURNACE, pos), "emptied: nothing left to smelt");
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        furnace.litTime = 1600; // burning a coal of its own
        helper.assertFalse(there(helper, JobType.FUEL_FURNACE, pos), "burning its own fuel: no need for a worker");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void seedJobsAreThereWhileTheChestHasSeeds(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));

        helper.assertFalse(there(helper, JobType.GET_SEEDS, pos), "an empty chest");
        chest.setItem(5, new ItemStack(Items.WHEAT_SEEDS, 10));
        helper.assertTrue(there(helper, JobType.GET_SEEDS, pos), "a chest with seeds");
        chest.setItem(5, ItemStack.EMPTY);
        chest.setItem(0, new ItemStack(Items.NETHER_WART, 3));
        helper.assertTrue(there(helper, JobType.GET_SEEDS, pos), "nether wart counts too");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void deliveryJobsAreThereWhileTheChestIs(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.CHEST);
        helper.assertTrue(there(helper, JobType.DELIVER_ITEMS, pos), "a chest");
        helper.setBlock(pos, Blocks.STONE);
        helper.assertFalse(there(helper, JobType.DELIVER_ITEMS, pos), "the chest was broken");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void pickupJobsAreThereWhileItemsAreOnTheGround(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos.below(), Blocks.STONE);
        helper.assertFalse(there(helper, JobType.COLLECT_DROPS, pos), "nothing on the ground");

        // A farm's or a mob's drops aren't for picking up: only what workers and players drop
        ItemEntity farmed = Scenes.dropNotToPickUp(helper, pos, new ItemStack(Items.WHEAT, 3));
        helper.assertFalse(there(helper, JobType.COLLECT_DROPS, pos), "a farm's wheat on the ground");
        farmed.discard();

        ItemEntity item = Scenes.dropToPickUp(helper, pos, new ItemStack(Items.WHEAT, 3));
        helper.assertTrue(there(helper, JobType.COLLECT_DROPS, pos), "a worker's wheat on the ground");

        item.discard();
        helper.assertFalse(there(helper, JobType.COLLECT_DROPS, pos), "someone picked it up");
        helper.succeed();
    }

    // ---- Whether a worker can do a job (JobChecks.canWorkerDo) ----

    @GameTest(template = Templates.EMPTY, batch = "can_worker_do")
    public void aPlanterCanPlantWhereItCarriesASeedForTheSoil(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos farmland = new BlockPos(3, 1, 3);
        BlockPos soulSand = new BlockPos(5, 1, 3);
        helper.setBlock(farmland, Blocks.FARMLAND);
        helper.setBlock(soulSand, Blocks.SOUL_SAND);
        Pokemon worker = Workers.sendOut(helper, "bulbasaur level=20", new BlockPos(1, 1, 1))
                .getPokemon();
        WorkerInventory pouch = WorkerInventoryManager.getOrCreate(worker);

        helper.assertFalse(canDo(helper, worker, JobType.PLANT_SEEDS, farmland.above()), "no seeds");
        pouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 4));
        helper.assertTrue(canDo(helper, worker, JobType.PLANT_SEEDS, farmland.above()), "wheat seeds, farmland");
        helper.assertFalse(
                canDo(helper, worker, JobType.PLANT_SEEDS, soulSand.above()), "wheat doesn't grow on soul sand");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "can_worker_do")
    public void aWorkerCanOnlyFetchSeedsWithRoomForThem(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos chest = new BlockPos(3, 1, 3);
        helper.setBlock(chest, Blocks.CHEST);
        // Only transporters take seeds out of chests (a planter's are brought it)
        Pokemon worker = Workers.sendOut(helper, "pidgey level=20", new BlockPos(1, 1, 1))
                .getPokemon();
        WorkerInventory bag = WorkerInventoryManager.getOrCreate(worker);

        helper.assertTrue(canDo(helper, worker, JobType.GET_SEEDS, chest), "an empty inventory");
        for (int i = 0; i < 64 && bag.addItem(new ItemStack(Items.WHEAT_SEEDS, 64)) == 0; i++) {
            // fill it up
        }
        helper.assertFalse(canDo(helper, worker, JobType.GET_SEEDS, chest), "a full inventory");
        helper.succeed();
    }

    private static boolean canDo(GameTestHelper helper, Pokemon worker, JobType type, BlockPos rel) {
        return JobChecks.canWorkerDo(helper.getLevel(), worker, type, helper.absolutePos(rel));
    }
}
