package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Workers that can fly fly to their jobs (see WorkerFlight): a far one, or one there's no walking
 * to, under open sky. Near ones, and ones under a roof, they walk to.
 */
public class FlightGameTests {
    private static final String PIDGEY = "pidgey level=20"; // Normal/Flying: Transporting, flies
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final String WHEAT_CARRIED = "wheat carried";

    private static int carried(PokemonEntity worker, Item item) {
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(worker.getPokemon());
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                count += inventory.getItem(i).getCount();
            }
        }
        return count;
    }

    /** Whether the worker was ever flying, and the highest it got above the floor, kept each tick. */
    private static double[] watchFlight(GameTestHelper helper, PokemonEntity worker) {
        double[] flight = {0, 0}; // {ever flying (1), highest above the floor}
        double floor = helper.absolutePos(START).getY();
        helper.onEachTick(() -> {
            if (worker.isFlying()) {
                flight[0] = 1;
            }
            flight[1] = Math.max(flight[1], worker.getY() - floor);
        });
        return flight;
    }

    @GameTest(template = Templates.ARENA, batch = "flight_far", timeoutTicks = 800)
    public void aWorkerThatFliesFliesToAFarJob(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, START);
        Scenes.dropToPickUp(helper, new BlockPos(14, 1, 14), new ItemStack(Items.WHEAT, 4));
        double[] flight = watchFlight(helper, pidgey);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 4, WHEAT_CARRIED);
            helper.assertTrue(flight[0] == 1 && flight[1] >= 2, "it flew there (highest " + flight[1] + ")");
            helper.assertTrue(!pidgey.isFlying(), "it's landed");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "flight_up", timeoutTicks = 600)
    public void aWorkerThatFliesFliesUpToAJobItCantWalkTo(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, START);
        // Wheat on top of a pillar, near: no walking up there, so it flies
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(4, y, 4), Blocks.STONE);
        }
        Scenes.dropToPickUp(helper, new BlockPos(4, 4, 4), new ItemStack(Items.WHEAT, 4));

        helper.succeedWhen(() -> helper.assertValueEqual(carried(pidgey, Items.WHEAT), 4, WHEAT_CARRIED));
    }

    @GameTest(template = Templates.EMPTY, batch = "flight_near", timeoutTicks = 400)
    public void aWorkerThatFliesWalksToANearJob(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, START);
        Scenes.dropToPickUp(helper, new BlockPos(5, 1, 4), new ItemStack(Items.WHEAT, 4));
        double[] flight = watchFlight(helper, pidgey);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 4, WHEAT_CARRIED);
            helper.assertTrue(flight[0] == 0, "it flew a few blocks");
        });
    }

    @GameTest(template = Templates.ARENA, batch = "flight_roof", timeoutTicks = 800)
    public void aWorkerThatFliesWalksUnderARoof(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // A roof over the whole way: it can't go up, so it walks
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.GLASS);
            }
        }
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, START);
        Scenes.dropToPickUp(helper, new BlockPos(14, 1, 14), new ItemStack(Items.WHEAT, 4));
        double[] flight = watchFlight(helper, pidgey);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 4, WHEAT_CARRIED);
            helper.assertTrue(flight[0] == 0, "it flew under the roof");
        });
    }
}
