package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * No job is ever given to two workers at once, for every kind of job workers share: two workers
 * that can both do it, starting side by side so they want the same jobs, more jobs than workers,
 * and {@link Workers#neverShareAJob} checking every tick that no two hold the same one. Each test also checks every job got done, once,
 * from the world itself (a crop's age, a furnace lit) or the workers' inventories, not from items
 * lying on the ground, which other things can take.
 */
public class SharedJobGameTests {
    private static final String BULBASAUR = "bulbasaur level=20"; // Grass: Planting, Gathering
    private static final String MACHOP = "machop level=20"; // Fighting: Lumbering
    private static final String CHARMANDER = "charmander level=20"; // Fire: Kindling
    private static final String PIDGEY = "pidgey level=20"; // Flying: Transporting

    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;

    /**
     * Where both workers start in the small tests: from the same spot they rank the jobs the same,
     * so both go for the nearest one, and only assignment keeps them apart.
     */
    private static final BlockPos SAME_SPOT = new BlockPos(3, 1, 2);

    /**
     * Count, for each crop, how many times it goes from ripe to just replanted (a harvest), every
     * tick. A crop harvested twice shows up as 2.
     */
    private static Map<BlockPos, Integer> countHarvests(GameTestHelper helper, Iterable<BlockPos> crops) {
        Map<BlockPos, Integer> harvests = new HashMap<>();
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos crop : crops) {
            harvests.put(crop, 0);
        }
        helper.onEachTick(() -> {
            for (BlockPos crop : crops) {
                BlockState now = helper.getBlockState(crop);
                BlockState was = before.put(crop, now);
                if (was != null && was.equals(WHEAT.getStateForAge(7)) && now.equals(WHEAT.getStateForAge(0))) {
                    harvests.merge(crop, 1, Integer::sum);
                }
            }
        });
        return harvests;
    }

    @GameTest(template = Templates.EMPTY, batch = "shared_harvest", timeoutTicks = 800)
    public void harvestersNeverShareACrop(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        java.util.List<BlockPos> crops = new java.util.ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
            helper.setBlock(x, 1, 5, WHEAT.getStateForAge(7));
            crops.add(new BlockPos(x, 1, 5));
        }
        Workers.neverShareAJob(
                helper, Workers.sendOut(helper, BULBASAUR, SAME_SPOT), Workers.sendOut(helper, BULBASAUR, SAME_SPOT));
        Map<BlockPos, Integer> harvests = countHarvests(helper, crops);

        helper.succeedWhen(() -> {
            for (BlockPos crop : crops) {
                helper.assertValueEqual(harvests.get(crop), 1, "harvests of the crop at " + crop);
            }
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "shared_plant", timeoutTicks = 800)
    public void plantersNeverShareASpot(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 1; x <= 6; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        PokemonEntity first = Workers.sendOut(helper, BULBASAUR, SAME_SPOT);
        PokemonEntity second = Workers.sendOut(helper, BULBASAUR, SAME_SPOT);
        WorkerInventory firstPouch = WorkerInventoryManager.getOrCreate(first.getPokemon());
        WorkerInventory secondPouch = WorkerInventoryManager.getOrCreate(second.getPokemon());
        firstPouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 10));
        secondPouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 10));
        Workers.neverShareAJob(helper, first, second);

        helper.succeedWhen(() -> {
            for (int x = 1; x <= 6; x++) {
                helper.assertTrue(
                        helper.getBlockState(new BlockPos(x, 1, 5)).equals(WHEAT.getStateForAge(0)),
                        "wheat planted at x=" + x);
            }
            // One seed per spot: a spot planted twice would have cost a seed for nothing
            int left = firstPouch.getTotalItemCount() + secondPouch.getTotalItemCount();
            helper.assertValueEqual(left, 20 - 6, "seeds left between them");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "shared_seeds", timeoutTicks = 1000)
    public void plantersNeverFetchFromTheSameChestAtOnce(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 1; x <= 6; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        BlockPos chestPos = new BlockPos(3, 1, 1);
        helper.setBlock(chestPos, Blocks.CHEST);
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos)))
                .setItem(0, new ItemStack(Items.WHEAT_SEEDS, 16));
        Workers.neverShareAJob(
                helper, Workers.sendOut(helper, BULBASAUR, SAME_SPOT), Workers.sendOut(helper, BULBASAUR, SAME_SPOT));

        helper.succeedWhen(() -> {
            for (int x = 1; x <= 6; x++) {
                helper.assertTrue(
                        helper.getBlockState(new BlockPos(x, 1, 5)).equals(WHEAT.getStateForAge(0)),
                        "wheat planted at x=" + x + " with seeds from the chest");
            }
        });
    }

    @GameTest(template = Templates.ARENA, batch = "shared_chop", timeoutTicks = 1600)
    public void lumberjacksNeverShareATree(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        int[] trees = {3, 8, 13};
        for (int x : trees) {
            Scenes.tree(helper, x, 10, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        }
        Workers.neverShareAJob(
                helper,
                Workers.sendOut(helper, MACHOP, new BlockPos(8, 1, 3)),
                Workers.sendOut(helper, MACHOP, new BlockPos(8, 1, 3)));

        helper.succeedWhen(() -> {
            for (int x : trees) {
                for (int y = 1; y <= 4; y++) {
                    helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(x, y, 10));
                }
            }
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "shared_furnace", timeoutTicks = 1200)
    public void kindlersNeverShareAFurnace(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        // One item each: a kindler keeps a furnace lit until it's smelted everything, then moves on
        BlockPos[] furnaces = {new BlockPos(1, 1, 6), new BlockPos(3, 1, 6), new BlockPos(5, 1, 6)};
        for (BlockPos pos : furnaces) {
            helper.setBlock(
                    pos, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
            ((AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos)))
                    .setItem(0, new ItemStack(Items.RAW_IRON, 1));
        }
        Workers.neverShareAJob(
                helper, Workers.sendOut(helper, CHARMANDER, SAME_SPOT), Workers.sendOut(helper, CHARMANDER, SAME_SPOT));
        // Lit at some point (a furnace goes out again once its heat runs down)
        java.util.Set<BlockPos> everLit = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            for (BlockPos pos : furnaces) {
                if (helper.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT)) {
                    everLit.add(pos);
                }
            }
        });

        helper.succeedWhen(() -> helper.assertValueEqual(everLit.size(), furnaces.length, "furnaces lit"));
    }

    @GameTest(template = Templates.EMPTY, batch = "shared_pickup", timeoutTicks = 800)
    public void transportersNeverShareAPickup(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity first = Workers.sendOut(helper, PIDGEY, SAME_SPOT);
        PokemonEntity second = Workers.sendOut(helper, PIDGEY, SAME_SPOT);
        Workers.neverShareAJob(helper, first, second);
        // Four spots, a different item at each, and no chest: what they pick up stays with them
        Item[] items = {Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT};
        BlockPos[] spots = {new BlockPos(1, 1, 6), new BlockPos(3, 1, 6), new BlockPos(5, 1, 6), new BlockPos(6, 1, 4)};
        for (int i = 0; i < items.length; i++) {
            Scenes.dropToPickUp(helper, spots[i], new ItemStack(items[i], 5));
        }
        WorkerInventory firstBag = WorkerInventoryManager.getOrCreate(first.getPokemon());
        WorkerInventory secondBag = WorkerInventoryManager.getOrCreate(second.getPokemon());

        helper.succeedWhen(() -> {
            for (Item item : items) {
                helper.assertValueEqual(
                        count(firstBag, item) + count(secondBag, item), 5, "carried between them: " + item);
            }
        });
    }

    private static int count(WorkerInventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                total += inventory.getItem(i).getCount();
            }
        }
        return total;
    }

    @GameTest(template = Templates.EMPTY, batch = "shared_restart", timeoutTicks = 800)
    public void aJobSavedWithItsWorkerIsntGivenToAnotherAfterARestart(GameTestHelper helper) {
        // A worker's job is saved with it; after a restart (or its chunk loading again) the new
        // JobManager knew nothing of it, so the scan offered the same crop again and another worker
        // took it too.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos crop = new BlockPos(6, 1, 5);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, WHEAT.getStateForAge(7));
        PokemonEntity saved = Workers.sendOut(helper, BULBASAUR, new BlockPos(1, 1, 1));
        PokemonEntity beside = Workers.sendOut(helper, BULBASAUR, new BlockPos(6, 1, 4));
        WorkerState state = WorkerStateManager.getOrCreate(saved.getPokemon());
        state.setCurrentReservation(new ReservationId(
                helper.absolutePos(crop),
                JobType.HARVEST_CROPS,
                helper.getLevel().getGameTime()));
        state.setAssignedJob(JobType.HARVEST_CROPS);
        state.startJob(0, null);
        WorkerStateManager.save(saved.getPokemon(), state);
        Workers.neverShareAJob(helper, saved, beside);
        Map<BlockPos, Integer> harvests = countHarvests(helper, java.util.List.of(crop));

        helper.succeedWhen(() -> helper.assertValueEqual(harvests.get(crop), 1, "harvests of the crop"));
    }
}
