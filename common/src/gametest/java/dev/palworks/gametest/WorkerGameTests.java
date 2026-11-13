package dev.palworks.gametest;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonParser;
import com.mojang.brigadier.tree.CommandNode;
import dev.palworks.ai.WorkStance;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.command.PalworksCommands;
import dev.palworks.hunger.Hunger;
import dev.palworks.inventory.CarryMode;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobType;
import dev.palworks.job.StepRunner;
import dev.palworks.job.handler.JobHandlerRegistry;
import dev.palworks.sleep.Sleep;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.suitability.SuitabilityOverrides;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

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

    @GameTest(template = Templates.EMPTY, batch = "worker_harvest_empty_section", timeoutTicks = 800)
    public void aCropPutWhereThereWasOnlyAirIsStillFound(GameTestHelper helper) {
        // The chunk scan skips a section (16 blocks high) holding nothing but air and plain terrain
        // (JobScanner), so it must notice once a job is put in one. Up in the sky, in the first
        // section wholly above the test area: all air, then a stone platform (still skipped) the
        // worker stands on while the scan goes over it, then a ripe crop put in the platform.
        Workers.isolate(helper);
        int floorY = helper.absolutePos(BlockPos.ZERO).getY();
        int bottom = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(floorY + 8) + 1);
        int platformY = bottom - floorY;
        BlockPos crop = new BlockPos(5, platformY + 1, 5);
        LevelChunkSection section = sectionAt(helper, crop);
        helper.assertTrue(section.hasOnlyAir(), "the section above the test area should start out all air");

        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, platformY, z, Blocks.STONE);
            }
        }
        Workers.sendOut(helper, BULBASAUR, new BlockPos(2, platformY + 1, 2));

        // By then the chunk is scanned and indexed: the crop is found as it's put there
        helper.runAfterDelay(100, () -> {
            helper.assertFalse(
                    section.maybeHas(state -> !JobChecks.canNeverBeWork(state)),
                    "the scan should still be skipping the section (only stone and air)");
            helper.setBlock(crop.below(), Blocks.FARMLAND);
            helper.setBlock(crop, WHEAT.getStateForAge(7));
            helper.succeedWhen(() -> helper.assertTrue(
                    helper.getBlockState(crop).equals(WHEAT.getStateForAge(0)),
                    "the wheat should be harvested and reset, but there's " + helper.getBlockState(crop)));
        });
    }

    private static LevelChunkSection sectionAt(GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        LevelChunk chunk = helper.getLevel().getChunkAt(abs);
        return chunk.getSection(chunk.getSectionIndex(abs.getY()));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_play_out", timeoutTicks = 800)
    public void workerFacesItsWorkUntilItsAnimationIsOver(GameTestHelper helper) {
        // The next crop along is close enough to start on where it stands: it still mustn't turn
        // to it while the last one's animation is playing out (it used to, straight away)
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);

        boolean[] playedOut = {false};
        helper.onEachTick(() -> {
            BlockPos target = WorkAnimator.playingOutAt(worker);
            if (target == null) {
                return;
            }
            playedOut[0] = true;
            double dx = target.getX() + 0.5 - worker.getX();
            double dz = target.getZ() + 0.5 - worker.getZ();
            float facing = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
            float off = Math.abs(Mth.wrapDegrees(worker.getYRot() - facing));
            helper.assertTrue(
                    off < 1.0f,
                    "while its animation at " + target + " plays out it faces it, but it's turned " + off
                            + " degrees away");
        });
        helper.succeedWhen(() -> {
            assertWheatRow(helper, 0, "harvested and reset");
            helper.assertTrue(playedOut[0], "its harvest animation played out");
            helper.assertTrue(WorkAnimator.playingOutAt(worker) == null, "its last animation is over");
        });
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

    @GameTest(template = Templates.EMPTY, batch = "worker_roster_stance", timeoutTicks = 800)
    public void aSpeciesWithAStanceOfItsOwnWorksFromThere(GameTestHelper helper) {
        // The test datapack gives Durant (Steel: Lumbering) a ranged chop: it stands back from the
        // trunk, on the ring a ranged move stands on, instead of right up against it
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos trunk = new BlockPos(3, 1, 5);
        Scenes.tree(helper, trunk.getX(), trunk.getZ(), 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        PokemonEntity worker = Workers.sendOut(helper, "durant level=20", START);
        helper.assertTrue(
                JobHandlerRegistry.stanceFor(JobType.CHOP_TREE, worker).equals(WorkStance.NEAR),
                "the datapack's stance for Durant is loaded");

        // Where it was on the last tick the tree was still up: where it chopped from
        double[] lastGap = {-1};
        double[] chopGap = {-1};
        helper.onEachTick(() -> {
            if (helper.getBlockState(trunk).is(Blocks.OAK_LOG)) {
                lastGap[0] = RealTreeGameTests.gapToTrunk(helper, worker, java.util.List.of(trunk));
            } else if (chopGap[0] < 0) {
                chopGap[0] = lastGap[0];
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chopGap[0] >= 0, "it chopped the tree");
            double half = worker.getBbWidth() / 2.0;
            helper.assertTrue(
                    chopGap[0] > WorkStance.AGAINST.maxGap(half) + 0.05 && WorkStance.NEAR.reaches(half, chopGap[0]),
                    String.format(
                            "it should chop from a ranged move's distance (more than %.2f, at most %.2f), but chopped from %.2f",
                            WorkStance.AGAINST.maxGap(half) + 0.05, WorkStance.NEAR.maxGap(half), chopGap[0]));
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

    @GameTest(template = Templates.EMPTY, batch = "worker_furnace_emptied", timeoutTicks = 800)
    public void kindlerStopsAsSoonAsItsFurnaceIsEmptied(GameTestHelper helper) {
        // Its items taken out mid-smelt: it used to see out the smelt (and start another) for nothing
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        BlockPos furnacePos = new BlockPos(3, 1, 6);
        helper.setBlock(
                furnacePos, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
        var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(furnacePos));
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        PokemonEntity worker = Workers.sendOut(helper, "charmander level=20", START);

        long[] emptiedAt = {-1};
        helper.onEachTick(() -> {
            if (emptiedAt[0] < 0 && helper.getBlockState(furnacePos).getValue(AbstractFurnaceBlock.LIT)) {
                furnace.setItem(0, ItemStack.EMPTY); // lit by the worker: now take its items out
                emptiedAt[0] = helper.getTick();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(emptiedAt[0] >= 0, "the worker hasn't lit the furnace yet");
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            helper.assertTrue(state == null || state.getCurrentReservation() == null, "still on its furnace job");
            long took = helper.getTick() - emptiedAt[0];
            helper.assertTrue(
                    took <= StepRunner.RECHECK_TICKS + 1, "it should drop the job within a recheck, took " + took);
        });
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

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_seeds_taken", timeoutTicks = 1000)
    public void planterGivesBackItsJobWhenItsSeedsAreTakenAndTakesItAgainOnceItHasSome(GameTestHelper helper) {
        // Its seeds taken on its way: it used to walk there and plant nothing. Given back rather
        // than dropped, and not remembered as unreachable, so it plants once it has seeds again.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos farmland = new BlockPos(3, 0, 9);
        helper.setBlock(farmland, Blocks.FARMLAND);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        var pouch = WorkerInventoryManager.getOrCreate(worker.getPokemon());
        pouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 4));

        long[] takenAt = {-1};
        long[] gaveBackAt = {-1};
        boolean[] plantedWithout = {false};
        helper.onEachTick(() -> {
            WorkerState state = WorkerStateManager.get(worker.getPokemon());
            var job = state == null ? null : state.getCurrentReservation();
            if (takenAt[0] < 0 && job != null && job.getJobType() == JobType.PLANT_SEEDS) {
                pouch.clearContent();
                takenAt[0] = helper.getTick();
            } else if (takenAt[0] >= 0 && gaveBackAt[0] < 0 && job == null) {
                gaveBackAt[0] = helper.getTick();
                plantedWithout[0] = !helper.getBlockState(farmland.above()).isAir();
                pouch.addItem(new ItemStack(Items.WHEAT_SEEDS, 4));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(gaveBackAt[0] >= 0, "it hasn't given its planting job back");
            helper.assertFalse(plantedWithout[0], "something got planted without a seed");
            // On its way, and at work, it checks every 10 ticks
            long took = gaveBackAt[0] - takenAt[0];
            helper.assertTrue(took <= 12, "it should give the job back within a recheck, took " + took);
            helper.assertTrue(
                    helper.getBlockState(farmland.above()).is(Blocks.WHEAT), "it should plant once it has seeds again");
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

    @GameTest(template = Templates.EMPTY, batch = "suitability_reload")
    public void aReloadedDatapackChangesWhatAWorkerCanDo(GameTestHelper helper) {
        // Suitabilities are cached per species and types for the data loaded
        // (DatapackSuitabilityProvider), so a /reload must still change them. The stand-in data is
        // put back within this tick, and nothing else runs in this batch, so no other test sees it.
        Pokemon bulbasaur = PokemonProperties.Companion.parse(BULBASAUR).create();
        Set<Suitability> before = SuitabilityDetector.getSuitabilities(bulbasaur);
        SuitabilityData loaded = SuitabilityData.current();
        Set<Suitability> reloaded;
        try {
            SuitabilityData.setCurrent(SuitabilityData.fromFiles(
                    Map.of("palworks:mining", JsonParser.parseString("{\"species\": {\"cobblemon:bulbasaur\": 2}}")),
                    warning -> helper.fail("the stand-in data should load cleanly: " + warning)));
            reloaded = SuitabilityDetector.getSuitabilities(bulbasaur);
        } finally {
            SuitabilityData.setCurrent(loaded);
        }
        Set<Suitability> after = SuitabilityDetector.getSuitabilities(bulbasaur);

        helper.assertTrue(before.contains(Suitability.PLANTING), "a Bulbasaur can plant, got " + before);
        helper.assertTrue(
                reloaded.equals(EnumSet.of(Suitability.MINING)), "after the reload, only Mining, got " + reloaded);
        helper.assertTrue(after.equals(before), "back to what it could do, got " + after);
        helper.succeed();
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

    /** Soul sand for nether wart along z=5, and a chest holding these, for a planting test. */
    private static ChestBlockEntity soulSandAndChest(GameTestHelper helper, ItemStack... contents) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 5, Blocks.SOUL_SAND);
        }
        BlockPos chestPos = new BlockPos(6, 1, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos));
        for (int i = 0; i < contents.length; i++) {
            chest.setItem(i, contents[i]);
        }
        return chest;
    }

    private static void assertWartPlanted(GameTestHelper helper) {
        for (int x = 2; x <= 4; x++) {
            helper.assertBlockPresent(Blocks.NETHER_WART, new BlockPos(x, 1, 5));
        }
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_mixed_chest", timeoutTicks = 1000)
    public void aGhostTypeFetchesNetherWartFromAChestWithSeedsInItToo(GameTestHelper helper) {
        // A chest with seeds and wart used to be a seed chest for planters only
        soulSandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8), new ItemStack(Items.NETHER_WART, 8));
        Workers.sendOut(helper, "gastly level=20", START); // Ghost/Poison: Nether only

        helper.succeedWhen(() -> assertWartPlanted(helper));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_planter", timeoutTicks = 1000)
    public void aPlanterThatIsAGhostTypeFetchesNetherWartToo(GameTestHelper helper) {
        // Nether work is all wart needs, whatever else it can do: a planter used to look for
        // farmland seeds in the chest instead, and take nothing
        soulSandAndChest(helper, new ItemStack(Items.NETHER_WART, 8));
        Workers.sendOut(helper, "phantump level=20", START); // Ghost/Grass: Planting, Gathering, Nether

        helper.succeedWhen(() -> assertWartPlanted(helper));
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
                Workers.sendOut(helper, "doduo level=20", new BlockPos(1, 1, 6)); // Flying: Transporting; walks
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

    @GameTest(template = Templates.EMPTY)
    public void anEnchantedItemSurvivesTheInventoryBeingSavedAndLoaded(GameTestHelper helper) {
        // Not sent out: its inventory is saved in its own data either way
        Pokemon pidgey = PokemonProperties.Companion.parse("pidgey level=20").create(); // Flying: Transporting
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        Holder<Enchantment> sharpness = helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.SHARPNESS);
        sword.enchant(sharpness, 3);
        WorkerInventoryManager.getOrCreate(pidgey).setItem(0, sword); // saved as it changes

        WorkerInventoryManager.unload(pidgey.getUuid()); // as if its chunk unloaded: read back from its data
        ItemStack loaded = WorkerInventoryManager.getOrCreate(pidgey).getItem(0);
        WorkerInventoryManager.unload(pidgey.getUuid());
        helper.assertTrue(loaded.is(Items.IRON_SWORD), "the sword is still there: " + loaded);
        helper.assertTrue(
                EnchantmentHelper.getItemEnchantmentLevel(sharpness, loaded) == 3,
                "still Sharpness 3: " + loaded.getEnchantments());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, timeoutTicks = 200)
    public void aPokemonWithNoWorkSkillsIsntAWorker(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity ekans = Workers.sendOutPastured(helper, "ekans level=20", new BlockPos(2, 1, 2)); // Poison
        helper.runAfterDelay(40, () -> {
            Pokemon pokemon = ekans.getPokemon();
            helper.assertTrue(SuitabilityDetector.getSuitabilities(pokemon).isEmpty(), "no work it can do");
            helper.assertFalse(WorkerStateManager.hasWorkerState(pokemon), "never made a worker");
            helper.assertFalse(Hunger.pausesCobblemonDrain(pokemon), "Cobblemon drains its fullness as usual");
            helper.assertFalse(Sleep.appliesTo(pokemon, null), "it sleeps as Cobblemon has it");
            helper.succeed();
        });
    }

    @AfterBatch(batch = "lost_last_skill")
    public void afterLostLastSkill(ServerLevel level) throws IOException {
        SuitabilityOverrides.resetAll();
    }

    @GameTest(template = Templates.EMPTY, batch = "lost_last_skill", timeoutTicks = 400)
    public void aWorkerThatLosesItsLastWorkSkillLetsItsJobGo(GameTestHelper helper) {
        // Changes every Bulbasaur's skills, so it's in a batch of its own, undone after it
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos crop = new BlockPos(6, 1, 5);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        PokemonEntity bulbasaur = Workers.sendOut(helper, "bulbasaur level=20", new BlockPos(1, 1, 1));
        WorkerState state = WorkerStateManager.getOrCreate(bulbasaur.getPokemon());

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(state.getCurrentReservation() != null, "it takes the harvest"))
                .thenExecute(() -> {
                    try { // Grass: Planting and Gathering, both taken away (as an admin's change does)
                        SuitabilityOverrides.set("cobblemon:bulbasaur", Suitability.PLANTING, 0);
                        SuitabilityOverrides.set("cobblemon:bulbasaur", Suitability.GATHERING, 0);
                    } catch (IOException e) {
                        helper.fail("couldn't change its skills: " + e);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(state.getCurrentReservation() == null, "it lets the job go"))
                .thenIdle(60)
                .thenExecute(() -> helper.assertTrue(
                        helper.getBlockState(crop).equals(((CropBlock) Blocks.WHEAT).getStateForAge(7)),
                        "and doesn't harvest it"))
                .thenSucceed();
    }

    private static final String WORKER = "worker"; // the /palworks worker command

    @GameTest(template = Templates.EMPTY)
    public void aReleasedModHasOnlyTheAdminCommands(GameTestHelper helper) {
        // The developer tools are only in development runs (this is one: they're all here)
        CommandNode<CommandSourceStack> released = PalworksCommands.build(false).build();
        CommandNode<CommandSourceStack> dev = PalworksCommands.build(true).build();
        for (String admin : List.of(WORKER, "suitability", "config")) {
            helper.assertTrue(released.getChild(admin) != null, "released: /palworks " + admin);
        }
        for (String tool : List.of("debug", "inspect", "testarea", "sleep", "jobs")) {
            helper.assertTrue(released.getChild(tool) == null, "not released: /palworks " + tool);
            helper.assertTrue(dev.getChild(tool) != null, "in development: /palworks " + tool);
        }
        for (String admin : List.of("enable", "disable")) {
            helper.assertTrue(released.getChild(WORKER).getChild(admin) != null, "released: worker " + admin);
        }
        for (String tool : List.of("inventory", "openinv", "clearinv", "clear")) {
            helper.assertTrue(released.getChild(WORKER).getChild(tool) == null, "not released: worker " + tool);
            helper.assertTrue(dev.getChild(WORKER).getChild(tool) != null, "in development: worker " + tool);
        }
        helper.succeed();
    }
}
