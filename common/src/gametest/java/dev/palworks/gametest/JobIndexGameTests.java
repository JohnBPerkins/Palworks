package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.block.ModBlocks;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The scanner's index of each chunk (see JobScanner): scanned once, then kept up to date as
 * things change, not by scanning again. Each change shows up within a couple of ticks; the full
 * rescan is only every 5 minutes, so it can't be what found it.
 *
 * <p>A Squirtle stands by to have the chunk indexed: it only waters, so it takes none of the jobs
 * looked for. In a batch of their own, so no other test's workers take them either.
 */
public class JobIndexGameTests {
    private static final String BATCH = "job_index";
    private static final BlockPos WATCHER = new BlockPos(1, 1, 1);
    private static final String SQUIRTLE = "squirtle level=20";
    private static final String INDEXED = "the chunk is indexed";
    private static final BlockPos SPOT = new BlockPos(5, 1, 5);
    // Ticks a change may take to show: the scanner looks at what changed on the next job tick
    private static final int SHOWS_WITHIN = 3;

    private static JobManager jobs(GameTestHelper helper) {
        return JobManager.getInstance(helper.getLevel());
    }

    private static boolean available(GameTestHelper helper, JobType type, BlockPos rel) {
        return jobs(helper).isAvailable(type, helper.absolutePos(rel));
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 200)
    public void aCropThatRipensIsAJobAtOnceAndGoesWhenHarvested(GameTestHelper helper) {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        Workers.isolate(helper);
        Workers.sendOut(helper, SQUIRTLE, WATCHER);
        helper.setBlock(SPOT.below(), Blocks.FARMLAND);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jobs(helper).isIndexed(helper.absolutePos(SPOT)), INDEXED))
                .thenExecute(() -> helper.setBlock(SPOT, wheat.getStateForAge(7)))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertTrue(available(helper, JobType.HARVEST_CROPS, SPOT), "ripe: a job"))
                .thenExecute(() -> helper.setBlock(SPOT, wheat.getStateForAge(0)))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(
                        () -> helper.assertFalse(available(helper, JobType.HARVEST_CROPS, SPOT), "harvested: no job"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 200)
    public void farmlandPutDownIsAPlantingJobAtOnce(GameTestHelper helper) {
        Workers.isolate(helper);
        Workers.sendOut(helper, SQUIRTLE, WATCHER);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jobs(helper).isIndexed(helper.absolutePos(SPOT)), INDEXED))
                .thenExecute(() -> helper.setBlock(SPOT.below(), Blocks.FARMLAND))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertTrue(available(helper, JobType.PLANT_SEEDS, SPOT), "a planting job"))
                // Something put on it: nothing to plant
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.STONE))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertFalse(available(helper, JobType.PLANT_SEEDS, SPOT), "covered: none"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 200)
    public void aChestPutDownIsKnownAndBrokenIsForgotten(GameTestHelper helper) {
        Workers.isolate(helper);
        Workers.sendOut(helper, SQUIRTLE, WATCHER);
        BlockPos abs = helper.absolutePos(SPOT);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jobs(helper).isIndexed(abs), INDEXED))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.CHEST))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertTrue(jobs(helper).isKnownPlace(abs), "the chest is known"))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.AIR))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertFalse(jobs(helper).isKnownPlace(abs), "and forgotten"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 200)
    public void seedsPutInAChestAreAJobAtOnce(GameTestHelper helper) {
        Workers.isolate(helper);
        Workers.sendOut(helper, SQUIRTLE, WATCHER);
        helper.setBlock(SPOT, Blocks.CHEST);
        helper.startSequence()
                .thenWaitUntil(() ->
                        helper.assertTrue(jobs(helper).isKnownPlace(helper.absolutePos(SPOT)), "the chest is known"))
                // As a player's click or a hopper does: the item, then setChanged
                .thenExecute(() -> {
                    ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(SPOT);
                    chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
                    chest.setChanged();
                })
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertTrue(available(helper, JobType.GET_SEEDS, SPOT), "seeds to fetch"))
                .thenExecute(() -> {
                    ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(SPOT);
                    chest.setItem(0, ItemStack.EMPTY);
                    chest.setChanged();
                })
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertFalse(available(helper, JobType.GET_SEEDS, SPOT), "taken out: none"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 200)
    public void aFurnacePutDownAndLoadedIsAJobAtOnce(GameTestHelper helper) {
        Workers.isolate(helper);
        Workers.sendOut(helper, SQUIRTLE, WATCHER);
        BlockPos abs = helper.absolutePos(SPOT);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jobs(helper).isIndexed(abs), INDEXED))
                .thenExecute(() -> helper.setBlock(SPOT, Blocks.FURNACE))
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> {
                    helper.assertTrue(jobs(helper).isKnownPlace(abs), "the furnace is known");
                    helper.assertFalse(available(helper, JobType.FUEL_FURNACE, SPOT), "empty: nothing to light");
                    var furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(SPOT);
                    furnace.setItem(0, new ItemStack(Items.RAW_IRON, 8));
                    furnace.setChanged();
                })
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertTrue(available(helper, JobType.FUEL_FURNACE, SPOT), "loaded: light it"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 200)
    public void aSaplingThatGrowsIsATreeAtOnce(GameTestHelper helper) {
        Workers.isolate(helper);
        Workers.sendOut(helper, SQUIRTLE, WATCHER);
        helper.setBlock(SPOT.below(), Blocks.DIRT);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jobs(helper).isIndexed(helper.absolutePos(SPOT)), INDEXED))
                .thenExecute(() -> {
                    helper.setBlock(SPOT, Blocks.OAK_SAPLING);
                    BlockPos abs = helper.absolutePos(SPOT);
                    SaplingBlock sapling = (SaplingBlock) Blocks.OAK_SAPLING;
                    // A sapling grows in two stages
                    for (int i = 0; i < 2; i++) {
                        sapling.advanceTree(
                                helper.getLevel(),
                                abs,
                                helper.getLevel().getBlockState(abs),
                                helper.getLevel().getRandom());
                    }
                    helper.assertTrue(helper.getLevel().getBlockState(abs).is(Blocks.OAK_LOG), "it grew");
                })
                .thenIdle(SHOWS_WITHIN)
                .thenExecute(() -> helper.assertTrue(
                        jobs(helper).availablePositions(JobType.CHOP_TREE).stream()
                                .anyMatch(pos -> pos.closerThan(helper.absolutePos(SPOT), 2)),
                        "a tree to chop"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = BATCH, timeoutTicks = 400)
    public void aTroughIsStillKnownWithNoWorkerNear(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.setBlock(SPOT, ModBlocks.trough("oak").get());
        PokemonEntity squirtle = Workers.sendOut(helper, SQUIRTLE, WATCHER);
        BlockPos abs = helper.absolutePos(SPOT);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jobs(helper).isKnownPlace(abs), "the trough is known"))
                // Its only worker gone: the chunk's index stays while it's loaded
                .thenExecute(() -> squirtle.getPokemon().recall())
                .thenIdle(300)
                .thenExecute(() -> helper.assertTrue(jobs(helper).isKnownPlace(abs), "still known"))
                .thenSucceed();
    }
}
