package dev.palworks.gametest;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.CarryMode;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Real Cobblemon Pokémon doing real jobs, end to end: finding the job, walking there, doing it.
 * Each test has its own batch so it runs alone: workers look for jobs 32 blocks around them, and
 * would otherwise wander into neighbouring tests' scenes.
 */
public class WorkerGameTests {
    // A Grass type (Planting, Gathering) and a Fighting type (Lumbering)
    private static final String BULBASAUR = "bulbasaur level=20";
    private static final String MACHOP = "machop level=20";

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
            BlockPos crop = new BlockPos(x, 1, 5);
            helper.assertTrue(
                    helper.getBlockState(crop).equals(WHEAT.getStateForAge(age)),
                    "wheat at x=" + x + " should be " + what + ", but there's " + helper.getBlockState(crop) + " on "
                            + helper.getBlockState(crop.below()));
        }
    }

    // ---- Each kind of job ----

    @GameTest(template = Templates.EMPTY, batch = "worker_harvest", timeoutTicks = 600)
    public void grassTypeHarvestsRipeWheat(GameTestHelper helper) {
        Workers.isolate(helper);
        // Also covers a just-sent-out worker taking the jobs right next to it (it used to mark them
        // unreachable while it was still dropping to the ground)
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        Workers.sendOut(helper, BULBASAUR, START);

        helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested and reset"));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_chop", timeoutTicks = 800)
    public void lumberjackChopsAndReplants(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 5, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        Workers.sendOut(helper, MACHOP, START); // Fighting: Lumbering

        helper.succeedWhen(() -> {
            for (int y = 1; y <= 4; y++) {
                helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(3, y, 5));
            }
            helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(3, 1, 5));
        });
    }

    @GameTest(template = Templates.ARENA, batch = "worker_chop_nearest", timeoutTicks = 1200)
    public void lumberjackChopsTheNearestTreeFirst(GameTestHelper helper) {
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        // From where it starts, and then from each tree in turn, the next is the nearest one left
        java.util.List<BlockPos> nearestFirst =
                java.util.List.of(new BlockPos(8, 1, 4), new BlockPos(12, 1, 9), new BlockPos(3, 1, 13));
        for (BlockPos trunk : nearestFirst) {
            Scenes.tree(helper, trunk.getX(), trunk.getZ(), 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        }
        Workers.sendOut(helper, MACHOP, new BlockPos(8, 1, 1));

        java.util.List<BlockPos> chopped = new java.util.ArrayList<>();
        helper.onEachTick(() -> {
            for (BlockPos trunk : nearestFirst) {
                if (!chopped.contains(trunk)
                        && !helper.getBlockState(trunk.above()).is(Blocks.OAK_LOG)) {
                    chopped.add(trunk);
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chopped.size() == nearestFirst.size(), "all chopped so far: " + chopped);
            helper.assertTrue(
                    chopped.equals(nearestFirst), "nearest first: expected " + nearestFirst + ", got " + chopped);
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_furnace", timeoutTicks = 800)
    public void fireTypeLightsAFurnace(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        BlockPos furnacePos = new BlockPos(3, 1, 6);
        helper.setBlock(
                furnacePos, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
        var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(furnacePos));
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        Workers.sendOut(helper, "charmander level=20", START); // Fire: Kindling

        helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(furnacePos).getValue(AbstractFurnaceBlock.LIT), "the furnace should be lit"));
    }

    // ---- Planting ----

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_pouch", timeoutTicks = 800)
    public void planterPlantsFromItsSeedPouch(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        var pouch = WorkerInventoryManager.getOrCreate(worker.getPokemon());
        helper.assertTrue(pouch.getMode() == CarryMode.SEED_POUCH, "a Grass type carries a seed pouch");
        pouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 16));

        helper.succeedWhen(() -> {
            assertWheatRow(helper, 0, "planted");
            helper.assertTrue(
                    pouch.getTotalItemCount() == 13, "one seed per tile, got " + pouch.getTotalItemCount() + " left");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_chest", timeoutTicks = 1000)
    public void planterFetchesSeedsFromAChest(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        BlockPos chestPos = new BlockPos(6, 1, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        ((ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos)))
                .setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
        Workers.sendOut(helper, BULBASAUR, START);

        helper.succeedWhen(() -> assertWheatRow(helper, 0, "planted with seeds from the chest"));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_none", timeoutTicks = 400)
    public void planterWithNoSeedsLeavesFarmlandAlone(GameTestHelper helper) {
        Workers.isolate(helper);
        // It used to walk to empty farmland with no seeds, plant nothing, and repeat
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
        }
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);

        helper.runAfterDelay(200, () -> {
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            helper.assertTrue(
                    state == null || state.getCurrentReservation() == null,
                    "with no seeds anywhere it takes no planting job, has "
                            + (state == null ? null : state.getCurrentReservation()));
            helper.succeed();
        });
    }

    // ---- Transporting ----

    @GameTest(template = Templates.EMPTY, batch = "worker_transport", timeoutTicks = 1600)
    public void harvesterAndTransporterStoreTheHarvest(GameTestHelper helper) {
        Workers.isolate(helper);
        // The Grass type harvests (it carries no inventory); the Flying type picks the wheat up
        // and puts it in the chest
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        BlockPos chestPos = new BlockPos(6, 1, 1);
        helper.setBlock(chestPos, Blocks.CHEST);
        Workers.sendOut(helper, BULBASAUR, START);
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

    @GameTest(template = Templates.EMPTY, batch = "worker_disabled", timeoutTicks = 400)
    public void aDisabledWorkerDoesNothing(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        WorkerState state = WorkerStateManager.getOrCreate(worker.getPokemon());
        state.setEnabled(false);
        WorkerStateManager.save(worker.getPokemon(), state);

        helper.runAfterDelay(200, () -> {
            assertWheatRow(helper, 7, "left ripe by a disabled worker");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_paused", timeoutTicks = 600)
    public void pausingTheGameDoesntCostAWorkerItsJob(GameTestHelper helper) {
        Workers.isolate(helper);
        // A paused single-player game stops ticking but the clock runs on. Reservations used to be
        // timed by the clock, so a pause of over 30 seconds made every walking worker give up its
        // job on resume. Blocking the server thread is that pause.
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        long[] pausedAt = {-1};
        long[] heldSince = {0};

        helper.onEachTick(() -> {
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            var held = state == null ? null : state.getCurrentReservation();
            if (pausedAt[0] < 0 && held != null && !state.isWorking()) {
                pausedAt[0] = helper.getTick();
                heldSince[0] = held.getTimestamp();
                try {
                    Thread.sleep(31_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (pausedAt[0] >= 0 && helper.getTick() <= pausedAt[0] + 5) {
                helper.assertTrue(
                        held != null && held.getTimestamp() == heldSince[0],
                        "it should still have the job it had before the pause, has " + held);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(pausedAt[0] >= 0, "it took a job");
            assertWheatRow(helper, 0, "harvested after the pause");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_busy", timeoutTicks = 1000)
    public void aBusyWorkerPausesThenCarriesOn(GameTestHelper helper) {
        Workers.isolate(helper);
        // Busy is what a battle (or evolving) sets on the Pokémon; it should stop working until free
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        worker.getBusyLocks().add("palworks-test");

        helper.runAfterDelay(200, () -> {
            assertWheatRow(helper, 7, "left alone while the worker is busy");
            worker.getBusyLocks().remove("palworks-test");
        });
        helper.runAfterDelay(
                201, () -> helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested once it's free")));
    }

    // ---- Evolving ----

    @GameTest(template = Templates.EMPTY, batch = "worker_evolve_gain", timeoutTicks = 800)
    public void anEvolvedWorkerTakesTheJobsOfItsNewType(GameTestHelper helper) {
        Workers.isolate(helper);
        // Eevee (Normal) can't harvest; Leafeon (Grass) can. Its jobs used to be worked out once
        // and saved, so it kept Eevee's forever. (setSpecies stands in for a real evolution.)
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, "eevee level=20", START);

        helper.runAfterDelay(100, () -> {
            assertWheatRow(helper, 7, "left alone by an Eevee");
            worker.getPokemon().setSpecies(PokemonSpecies.INSTANCE.getByName("leafeon"));
        });
        helper.runAfterDelay(
                101, () -> helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested once it's a Leafeon")));
    }

    // ---- Reachability ----

    @GameTest(template = Templates.EMPTY, batch = "worker_unreachable", timeoutTicks = 1200)
    public void aBoxedInWorkerHarvestsAsSoonAsTheWayOpens(GameTestHelper helper) {
        Workers.isolate(helper);
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
        Workers.sendOut(helper, BULBASAUR, inside);

        helper.runAfterDelay(150, () -> {
            assertWheatRow(helper, 7, "out of reach while the worker is boxed in");
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(inside.getX(), y, inside.getZ() + 1, Blocks.AIR); // open the side facing the wheat
            }
        });
        // Well under the 600-tick memory backstop, so this only passes if opening the box was noticed
        helper.runAfterDelay(
                151, () -> helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested once reachable")));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_party_owner", timeoutTicks = 1000)
    public void aPartyWorkerOnlyWorksNearItsOwner(GameTestHelper helper) {
        Workers.isolate(helper);
        // A party Pokémon whose owner is too far from the jobs round it doesn't take them; once
        // they're back, it does. The owner is out of range straight up: off to the side, it would
        // follow them (Cobblemon) out of the test's loaded area. Within 32 blocks, so Cobblemon
        // doesn't teleport it up to them either.
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", START);
        ServerPlayer owner = worker.getPokemon().getOwnerPlayer();
        BlockPos home = helper.absolutePos(START);
        owner.moveTo(home.getX() + 0.5, home.getY() + 27, home.getZ() + 0.5);

        helper.runAfterDelay(200, () -> {
            assertWheatRow(helper, 7, "left alone while its owner is out of range");
            owner.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
        });
        helper.runAfterDelay(
                201, () -> helper.succeedWhen(() -> assertWheatRow(helper, 0, "harvested once its owner is back")));
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

    @GameTest(template = Templates.EMPTY, batch = "worker_nether_no", timeoutTicks = 400)
    public void lumberjacksWithoutNetherLeaveNetherTreesAlone(GameTestHelper helper) {
        Workers.isolate(helper);
        crimsonFungus(helper);
        Workers.sendOut(helper, MACHOP, START); // Fighting: Lumbering only

        helper.runAfterDelay(250, () -> {
            helper.assertBlockPresent(Blocks.CRIMSON_STEM, new BlockPos(3, 1, 5));
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_nether_yes", timeoutTicks = 1000)
    public void ghostLumberjacksChopNetherTreesAndTheirCanopy(GameTestHelper helper) {
        Workers.isolate(helper);
        crimsonFungus(helper);
        Workers.sendOut(helper, "honedge level=20", START); // Steel/Ghost: Lumbering + Nether

        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.CRIMSON_STEM, new BlockPos(3, 2, 5));
            helper.assertBlockNotPresent(Blocks.NETHER_WART_BLOCK, new BlockPos(3, 5, 5));
            helper.assertBlockPresent(Blocks.CRIMSON_FUNGUS, new BlockPos(3, 1, 5));
        });
    }

    // ---- Nether wart is Nether work, not Gathering ----

    private static final BlockPos WART = new BlockPos(3, 1, 5);

    private static void ripeWart(GameTestHelper helper) {
        Scenes.floor(helper, Blocks.STONE);
        helper.setBlock(WART.below(), Blocks.SOUL_SAND);
        helper.setBlock(WART, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_no", timeoutTicks = 400)
    public void grassTypesLeaveNetherWartAlone(GameTestHelper helper) {
        Workers.isolate(helper);
        ripeWart(helper);
        Workers.sendOut(helper, "ivysaur level=20", START); // Grass/Poison: Gathering, no Nether

        helper.runAfterDelay(300, () -> {
            helper.assertTrue(
                    helper.getBlockState(WART).getValue(NetherWartBlock.AGE) == 3,
                    "a Grass type shouldn't harvest nether wart");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_yes", timeoutTicks = 800)
    public void ghostTypesHarvestNetherWart(GameTestHelper helper) {
        Workers.isolate(helper);
        ripeWart(helper);
        Workers.sendOut(helper, "gastly level=20", START); // Ghost/Poison: Nether

        helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(WART).getValue(NetherWartBlock.AGE) == 0, "harvested and reset"));
    }

    // ---- Handing a job over ----

    @GameTest(template = Templates.EMPTY, batch = "worker_handover", timeoutTicks = 1600)
    public void aRecalledLumberjacksReplantIsFinishedByAnother(GameTestHelper helper) {
        Workers.isolate(helper);
        // The first lumberjack is recalled between the chop and the replant. The job, with "still
        // needs replanting", goes to the second one, which plants the sapling.
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 5, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        PokemonEntity first = Workers.sendOut(helper, MACHOP, START);
        boolean[] recalled = {false};

        helper.onEachTick(() -> {
            if (!recalled[0] && helper.getBlockState(new BlockPos(3, 2, 5)).isAir()) {
                recalled[0] = true; // the trunk just came down: recall before it replants
                first.getPokemon().recall();
                Workers.sendOut(helper, MACHOP, new BlockPos(1, 1, 1));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(recalled[0], "the first lumberjack chopped the tree");
            helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(3, 1, 5));
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_faint_handover", timeoutTicks = 1600)
    public void aFaintedLumberjacksReplantIsFinishedByAnother(GameTestHelper helper) {
        // Fainting (e.g. in a battle) hands the job over, with "still needs replanting"
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        Scenes.tree(helper, 3, 5, 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        PokemonEntity first = Workers.sendOut(helper, MACHOP, START);
        boolean[] fainted = {false};

        helper.onEachTick(() -> {
            if (!fainted[0] && helper.getBlockState(new BlockPos(3, 2, 5)).isAir()) {
                fainted[0] = true; // the trunk just came down: faint before it replants
                first.getPokemon().setCurrentHealth(0);
                Workers.sendOut(helper, MACHOP, new BlockPos(1, 1, 1));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(fainted[0], "the first lumberjack chopped the tree");
            helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(3, 1, 5));
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_unreachable_chest", timeoutTicks = 1200)
    public void aTransporterSkipsAChestItCantReach(GameTestHelper helper) {
        // The nearest chest is sealed in glass: it must deliver to the further one, not keep
        // walking at the sealed one
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos sealed = new BlockPos(3, 1, 3);
        helper.setBlock(sealed, Blocks.CHEST);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 1; y <= 2; y++) {
                    if (dx != 0 || dz != 0 || y == 2) {
                        helper.setBlock(sealed.getX() + dx, y, sealed.getZ() + dz, Blocks.GLASS);
                    }
                }
            }
        }
        BlockPos open = new BlockPos(6, 1, 7);
        helper.setBlock(open, Blocks.CHEST);
        PokemonEntity worker =
                Workers.sendOut(helper, "drowzee level=20", new BlockPos(1, 1, 6)); // Psychic: Transporting
        WorkerInventoryManager.getOrCreate(worker.getPokemon()).addItem(new ItemStack(Items.WHEAT, 20));

        helper.succeedWhen(() -> {
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(open));
            helper.assertTrue(
                    chest.countItem(Items.WHEAT) == 20,
                    "all 20 wheat in the reachable chest, got " + chest.countItem(Items.WHEAT));
        });
    }

    @GameTest(template = Templates.ARENA, batch = "worker_turn_back", timeoutTicks = 600)
    public void aWorkerTurnsBackWhenItsCropIsHarvestedFirst(GameTestHelper helper) {
        // Someone else harvests the crop while the worker is on its way: it should drop the job
        // within a second, not finish the walk for nothing
        Workers.isolate(helper);
        RealTreeGameTests.arenaFloor(helper, Blocks.GRASS_BLOCK);
        BlockPos crop = new BlockPos(8, 1, 14);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, WHEAT.getStateForAge(7));
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, new BlockPos(8, 1, 1));
        long[] harvestedAt = {-1};

        helper.onEachTick(() -> {
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            boolean onItsWay = state != null && state.getCurrentReservation() != null && !state.isWorking();
            if (harvestedAt[0] < 0
                    && onItsWay
                    && worker.blockPosition().getZ()
                                    - helper.absolutePos(BlockPos.ZERO).getZ()
                            >= 4) {
                harvestedAt[0] = helper.getTick();
                helper.setBlock(crop, WHEAT.getStateForAge(0)); // a player got there first
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(harvestedAt[0] >= 0, "the worker set off for the crop");
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            helper.assertTrue(state.getCurrentReservation() == null, "it should have dropped the job");
            helper.assertTrue(
                    helper.getTick() - harvestedAt[0] <= 30,
                    "within a second and a half of the crop going, took " + (helper.getTick() - harvestedAt[0])
                            + " ticks");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_two_harvesters", timeoutTicks = 800)
    public void twoHarvestersNeverTakeTheSameCrop(GameTestHelper helper) {
        // Six ripe wheat, two Bulbasaur: every crop harvested exactly once (one wheat each)
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 1; x <= 6; x++) {
            helper.setBlock(x, 0, 5, Blocks.FARMLAND);
            helper.setBlock(x, 1, 5, WHEAT.getStateForAge(7));
        }
        Workers.sendOut(helper, BULBASAUR, new BlockPos(1, 1, 1));
        Workers.sendOut(helper, BULBASAUR, new BlockPos(6, 1, 1));

        helper.succeedWhen(() -> {
            for (int x = 1; x <= 6; x++) {
                helper.assertTrue(
                        helper.getBlockState(new BlockPos(x, 1, 5)).equals(WHEAT.getStateForAge(0)),
                        "wheat at x=" + x + " should be harvested");
            }
            helper.assertTrue(
                    Scenes.itemsOnGround(helper, Items.WHEAT) == 6,
                    "each crop harvested exactly once: 6 wheat, got " + Scenes.itemsOnGround(helper, Items.WHEAT));
        });
    }
}
