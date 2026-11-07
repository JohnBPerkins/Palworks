package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.CarryMode;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Real Cobblemon Pokémon doing real jobs, end to end: finding the job, walking there, doing it.
 * Each test has its own batch so it runs alone: workers look for jobs 32 blocks around them, and
 * would otherwise wander into neighbouring tests' scenes.
 */
public class WorkerGameTests implements FabricGameTest {

    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final BlockPos START = new BlockPos(3, 1, 1);

    /** Three ripe wheat in a row at z=5, on farmland. */
    private static void ripeWheatRow(GameTestHelper helper) {
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
            helper.setBlock(x, 1, 5, WHEAT.getStateForAge(7));
        }
    }

    private static void assertWheatRow(GameTestHelper helper, int age, String what) {
        for (int x = 2; x <= 4; x++) {
            helper.assertTrue(helper.getBlockState(new BlockPos(x, 1, 5)).equals(WHEAT.getStateForAge(age)),
                "wheat at x=" + x + " should be " + what);
        }
    }

    // ---- Each kind of job ----

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_harvest", timeoutTicks = 600)
    public void grassTypeHarvestsRipeWheat(GameTestHelper helper) {
        // Also covers a just-sent-out worker taking the jobs right next to it (it used to mark them
        // unreachable while it was still dropping to the ground)
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        Workers.sendOut(helper, "bulbasaur level=20", START);

        helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested and reset"));
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_chop", timeoutTicks = 800)
    public void lumberjackChopsAndReplants(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 5, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        Workers.sendOut(helper, "machop level=20", START); // Fighting: Lumbering

        helper.succeedWhen(() -> {
            for (int y = 1; y <= 4; y++) {
                helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(3, y, 5));
            }
            helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(3, 1, 5));
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_furnace", timeoutTicks = 800)
    public void fireTypeLightsAFurnace(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        BlockPos furnacePos = new BlockPos(3, 1, 6);
        helper.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
        var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(furnacePos));
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        Workers.sendOut(helper, "charmander level=20", START); // Fire: Kindling

        helper.succeedWhen(() -> helper.assertTrue(
            helper.getBlockState(furnacePos).getValue(AbstractFurnaceBlock.LIT), "the furnace should be lit"));
    }

    // ---- Planting ----

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_plant_pouch", timeoutTicks = 800)
    public void planterPlantsFromItsSeedPouch(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", START);
        var pouch = WorkerInventoryManager.getOrCreate(worker.getPokemon());
        helper.assertTrue(pouch.getMode() == CarryMode.SEED_POUCH, "a Grass type carries a seed pouch");
        pouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 16));

        helper.succeedWhen(() -> {
            assertWheatRow(helper, 0, "planted");
            helper.assertTrue(pouch.getTotalItemCount() == 13, "one seed per tile, got " + pouch.getTotalItemCount() + " left");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_plant_chest", timeoutTicks = 1000)
    public void planterFetchesSeedsFromAChest(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        BlockPos chestPos = new BlockPos(6, 1, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos)))
            .setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
        Workers.sendOut(helper, "bulbasaur level=20", START);

        helper.succeedWhen(() -> assertWheatRow(helper, 0, "planted with seeds from the chest"));
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_plant_none", timeoutTicks = 400)
    public void planterWithNoSeedsLeavesFarmlandAlone(GameTestHelper helper) {
        // It used to walk to empty farmland with no seeds, plant nothing, and repeat
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", START);

        helper.runAfterDelay(200, () -> {
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            helper.assertTrue(state == null || state.getCurrentReservation() == null,
                "with no seeds anywhere it takes no planting job, has " + (state == null ? null : state.getCurrentReservation()));
            helper.succeed();
        });
    }

    // ---- Transporting ----

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_transport", timeoutTicks = 1600)
    public void harvesterAndTransporterStoreTheHarvest(GameTestHelper helper) {
        // The Grass type harvests (it carries no inventory); the Flying type picks the wheat up
        // and puts it in the chest
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        BlockPos chestPos = new BlockPos(6, 1, 1);
        helper.setBlock(chestPos, Blocks.CHEST);
        Workers.sendOut(helper, "bulbasaur level=20", START);
        Workers.sendOut(helper, "pidgey level=20", new BlockPos(1, 1, 1)); // Flying: Transporting

        helper.succeedWhen(() -> {
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos));
            int wheat = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).is(Items.WHEAT)) {
                    wheat += chest.getItem(i).getCount();
                }
            }
            helper.assertTrue(wheat > 0, "the harvested wheat should end up in the chest");
        });
    }

    // ---- When workers shouldn't work ----

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_disabled", timeoutTicks = 400)
    public void aDisabledWorkerDoesNothing(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", START);
        WorkerState state = WorkerStateManager.getOrCreate(worker.getPokemon());
        state.setEnabled(false);
        WorkerStateManager.save(worker.getPokemon(), state);

        helper.runAfterDelay(200, () -> {
            assertWheatRow(helper, 7, "left ripe by a disabled worker");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_busy", timeoutTicks = 1000)
    public void aBusyWorkerPausesThenCarriesOn(GameTestHelper helper) {
        // Busy is what a battle (or evolving) sets on the Pokémon; it should stop working until free
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", START);
        worker.getBusyLocks().add("palworks-test");

        helper.runAfterDelay(200, () -> {
            assertWheatRow(helper, 7, "left alone while the worker is busy");
            worker.getBusyLocks().remove("palworks-test");
        });
        helper.runAfterDelay(201, () -> helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested once it's free")));
    }

    // ---- Reachability ----

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_unreachable", timeoutTicks = 1200)
    public void aBoxedInWorkerHarvestsAsSoonAsTheWayOpens(GameTestHelper helper) {
        // The worker can't get out of a glass box, so it can't reach the wheat; breaking one wall
        // should make it try again straight away (block change hook), not after the 30s backstop
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        BlockPos inside = new BlockPos(1, 1, 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 1; y <= 3; y++) {
                    boolean interior = dx == 0 && dz == 0 && y <= 2;
                    if (!interior) {
                        helper.setBlock(inside.getX() + dx, y, inside.getZ() + dz, Blocks.GLASS);
                    }
                }
            }
        }
        Workers.sendOut(helper, "bulbasaur level=20", inside);

        helper.runAfterDelay(150, () -> {
            assertWheatRow(helper, 7, "out of reach while the worker is boxed in");
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(inside.getX(), y, inside.getZ() + 1, Blocks.AIR); // open the side facing the wheat
            }
        });
        // Well under the 600-tick memory backstop, so this only passes if opening the box was noticed
        helper.runAfterDelay(151, () -> helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested once reachable")));
    }

    // ---- Nether trees need Lumbering and Nether ----

    private static void crimsonFungus(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.CRIMSON_NYLIUM);
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(3, y, 5, Blocks.CRIMSON_STEM);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 3; y <= 5; y++) {
                    if (!(dx == 0 && dz == 0 && y <= 4)) {
                        helper.setBlock(3 + dx, y, 5 + dz, Blocks.NETHER_WART_BLOCK);
                    }
                }
            }
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_nether_no", timeoutTicks = 400)
    public void lumberjacksWithoutNetherLeaveNetherTreesAlone(GameTestHelper helper) {
        crimsonFungus(helper);
        Workers.sendOut(helper, "machop level=20", START); // Fighting: Lumbering only

        helper.runAfterDelay(250, () -> {
            helper.assertBlockPresent(Blocks.CRIMSON_STEM, new BlockPos(3, 1, 5));
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_nether_yes", timeoutTicks = 1000)
    public void ghostLumberjacksChopNetherTreesAndTheirCanopy(GameTestHelper helper) {
        crimsonFungus(helper);
        Workers.sendOut(helper, "honedge level=20", START); // Steel/Ghost: Lumbering + Nether

        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.CRIMSON_STEM, new BlockPos(3, 2, 5));
            helper.assertBlockNotPresent(Blocks.NETHER_WART_BLOCK, new BlockPos(3, 5, 5));
            helper.assertBlockPresent(Blocks.CRIMSON_FUNGUS, new BlockPos(3, 1, 5));
        });
    }

    // ---- Handing a job over ----

    @GameTest(template = EMPTY_STRUCTURE, batch = "worker_handover", timeoutTicks = 1600)
    public void aRecalledLumberjacksReplantIsFinishedByAnother(GameTestHelper helper) {
        // The first lumberjack is recalled between the chop and the replant. The job, with "still
        // needs replanting", goes to the second one, which plants the sapling.
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 5, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        PokemonEntity first = Workers.sendOut(helper, "machop level=20", START);
        boolean[] recalled = {false};

        helper.onEachTick(() -> {
            if (!recalled[0] && helper.getBlockState(new BlockPos(3, 2, 5)).isAir()) {
                recalled[0] = true; // the trunk just came down: recall before it replants
                first.getPokemon().recall();
                Workers.sendOut(helper, "machop level=20", new BlockPos(1, 1, 1));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(recalled[0], "the first lumberjack chopped the tree");
            helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(3, 1, 5));
        });
    }
}
