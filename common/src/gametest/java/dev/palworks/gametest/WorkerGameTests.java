package dev.palworks.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonParser;
import com.mojang.brigadier.tree.CommandNode;
import dev.palworks.ai.WorkStance;
import dev.palworks.ai.WorkerAI;
import dev.palworks.animation.WorkAnimation;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.animation.WorkAspects;
import dev.palworks.command.PalworksCommands;
import dev.palworks.config.PalworksConfig;
import dev.palworks.hunger.Hunger;
import dev.palworks.inventory.CarryMode;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.inventory.WorkerInventoryMenu;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import dev.palworks.job.StepRunner;
import dev.palworks.job.WorkRange;
import dev.palworks.job.handler.JobHandlerRegistry;
import dev.palworks.job.handler.Step;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.suitability.SuitabilityOverrides;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Real Cobblemon Pokémon doing real jobs, end to end: finding the job, walking there, doing it.
 * Each test has its own batch so it runs alone: workers look for jobs 32 blocks around them, and
 * would otherwise wander into neighbouring tests' scenes.
 */
public class WorkerGameTests {
    // A Grass type (Planting, Gathering) and a Fighting type (Lumbering)
    private static final String BULBASAUR = "bulbasaur level=20";
    private static final String MACHOP = "machop level=20";
    // A Flying type (Transporting): planters can't take seeds out of chests, a transporter brings them
    private static final String PIDGEY = "pidgey level=20";
    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);

    private static final CropBlock WHEAT = (CropBlock) Blocks.WHEAT;
    private static final BlockPos START = new BlockPos(3, 1, 1);
    private static final String HARVESTED = "harvested and reset";

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

        helper.succeedWhen(() -> assertWheatRow(helper, 0, HARVESTED));
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

    private static final String WIDEST_RANGE_BATCH = "worker_widest_range";
    private static PalworksConfig configBefore = PalworksConfig.DEFAULTS;

    @BeforeBatch(batch = WIDEST_RANGE_BATCH)
    public void widestRange(ServerLevel level) {
        configBefore = PalworksConfig.current();
        PalworksConfig d = PalworksConfig.DEFAULTS;
        PalworksConfig.setCurrent(new PalworksConfig(
                64,
                d.workRangeVertical(),
                d.ownerRange(),
                d.pickupRadius(),
                d.fertilizeChancePerLevel(),
                d.wateringCooldownSeconds()));
    }

    @AfterBatch(batch = WIDEST_RANGE_BATCH)
    public void afterWidestRange(ServerLevel level) {
        PalworksConfig.setCurrent(configBefore);
    }

    @GameTest(template = Templates.EMPTY, batch = WIDEST_RANGE_BATCH, timeoutTicks = 800)
    public void theScanLeavesChunksThatArentLoadedAlone(GameTestHelper helper) {
        // With the widest work range, a worker's reaches past the chunks loaded round it. The scan
        // asked the level for each of those, which loads it there and then (off the disk, or
        // made anew), and again every few seconds, as it was dropped for not being loaded.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        ServerLevel level = helper.getLevel();
        ChunkPos here = new ChunkPos(helper.absolutePos(START));
        int reach = WorkRange.chunkRadius();
        List<ChunkPos> notLoaded = new ArrayList<>();

        helper.startSequence()
                .thenIdle(40) // (clearing round the test loaded the chunks it touched: they go again)
                .thenExecute(() -> {
                    for (int dx = -reach; dx <= reach; dx++) {
                        for (int dz = -reach; dz <= reach; dz++) {
                            // (Nothing asking for it to be loaded: one that is being asked
                            // for, and is still on its way in, would turn up later by itself)
                            if (!level.hasChunk(here.x + dx, here.z + dz)) {
                                notLoaded.add(new ChunkPos(here.x + dx, here.z + dz));
                            }
                        }
                    }
                    helper.assertFalse(
                            notLoaded.isEmpty(), "the test needs a chunk in the worker's range that isn't loaded");
                    Workers.sendOut(helper, BULBASAUR, START);
                })
                .thenExecuteFor(150, () -> {
                    for (ChunkPos chunk : notLoaded) {
                        helper.assertTrue(
                                level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null,
                                "the scan loaded chunk " + chunk + " (" + notLoaded.size() + " weren't loaded)");
                    }
                })
                // And they don't keep it waiting: its range counts as scanned without them
                .thenWaitUntil(() -> assertWheatRow(helper, 0, HARVESTED))
                .thenSucceed();
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
            assertWheatRow(helper, 0, HARVESTED);
            helper.assertTrue(playedOut[0], "its harvest animation played out");
            helper.assertTrue(WorkAnimator.playingOutAt(worker) == null, "its last animation is over");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_turn_aspect", timeoutTicks = 800)
    public void aWorkerShowsItsWorkAspectWhileItTurnsToItsWork(GameTestHelper helper) {
        // The client turns a worker's body with it only while it has the work aspect (see
        // MobBodyTurnMixin), so it must have it whenever it turns at its job: from when it arrives
        // (its step's ticks start then, as its move waits to settle), not just once the move plays
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        ripeWheatRow(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);

        boolean[] turnedAtItsJob = {false};
        helper.onEachTick(() -> {
            WorkerState state = WorkerStateManager.getOrCreate(worker.getPokemon());
            boolean standing = worker.getX() == worker.xo && worker.getZ() == worker.zo;
            boolean turned = Mth.wrapDegrees(worker.getYRot() - worker.yRotO) != 0.0f;
            if (!standing || !turned || state.getCurrentReservation() == null) {
                return;
            }
            turnedAtItsJob[0] = true;
            helper.assertTrue(
                    worker.getAspects().contains(WorkAspects.WORKING),
                    "turning to its work it shows " + WorkAspects.WORKING + ", but has " + worker.getAspects());
        });
        helper.succeedWhen(() -> {
            assertWheatRow(helper, 0, HARVESTED);
            helper.assertTrue(turnedAtItsJob[0], "it turned to a crop where it stood");
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

    @BeforeBatch(batch = "kindler_level_three")
    public void beforeKindlerLevelThree(ServerLevel level) throws IOException {
        SuitabilityOverrides.set("cobblemon:charmander", Suitability.KINDLING, 3);
    }

    @AfterBatch(batch = "kindler_level_three")
    public void afterKindlerLevelThree(ServerLevel level) throws IOException {
        SuitabilityOverrides.resetAll();
    }

    @GameTest(template = Templates.EMPTY, batch = "kindler_level_three", timeoutTicks = 1600)
    public void aKindlerAboveLevelOneLightsAFurnaceAndGoesOnToTheNext(GameTestHelper helper) {
        // Kindling 3: an Ember smelts 4 items, so it lights each and moves on, and both burn at once.
        // A level 1 kindler stays at its furnace until it's done, so never has two going.
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        List<BlockPos> furnaces = List.of(new BlockPos(2, 1, 6), new BlockPos(6, 1, 6));
        for (BlockPos pos : furnaces) {
            helper.setBlock(
                    pos, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
            var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
            furnace.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        }
        Workers.sendOut(helper, "charmander level=20", START);

        boolean[] bothLit = {false};
        helper.onEachTick(() -> {
            if (furnaces.stream().allMatch(pos -> helper.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT))) {
                bothLit[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            for (BlockPos pos : furnaces) {
                var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                helper.assertValueEqual(furnace.getItem(2).getCount(), 4, "iron smelted at " + pos);
            }
            helper.assertTrue(bothLit[0], "both furnaces burned at once");
        });
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

    @GameTest(template = Templates.EMPTY, batch = "worker_plant_chest", timeoutTicks = 1600)
    public void aTransporterBringsAPlanterSeedsFromAChest(GameTestHelper helper) {
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
        Workers.sendOut(helper, PIDGEY, TRANSPORTER);

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
        WorkerStateManager.setEnabled(worker.getPokemon(), false);

        helper.runAfterDelay(200, () -> {
            assertWheatRow(helper, 7, "left ripe by a disabled worker");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void aNewPokemonsWorkStartsOffInThePartyAndOnInAPastureAsTheConfigSays(GameTestHelper helper) {
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        PalworksConfig before = PalworksConfig.current();
        try {
            PalworksConfig.setCurrent(PalworksConfig.DEFAULTS);
            Pokemon inParty = Workers.create(BULBASAUR);
            Cobblemon.INSTANCE.getStorage().getParty(owner).add(inParty);
            Pokemon pastured = Workers.create(BULBASAUR);
            Cobblemon.INSTANCE.getStorage().getPC(owner).add(pastured);
            helper.assertFalse(WorkerEligibility.works(inParty, null), "by default, none in the party");
            helper.assertFalse(WorkerStateManager.isEnabled(inParty), "its work starting off");
            helper.assertTrue(WorkerEligibility.works(pastured, null), "and a pastured one works");
            helper.assertTrue(WorkerStateManager.isEnabled(pastured), "its work starting on");

            PalworksConfig.setCurrent(PalworksConfig.DEFAULTS.withWorkOnByDefault(true, false));
            Pokemon swapped = Workers.create(BULBASAUR);
            Cobblemon.INSTANCE.getStorage().getParty(owner).add(swapped);
            helper.assertTrue(WorkerStateManager.isEnabled(swapped), "with the settings swapped, on in the party");
            helper.assertFalse(WorkerStateManager.getOrCreate(swapped).isEnabled(false), "and off in a pasture");
            helper.assertFalse(WorkerStateManager.isEnabled(inParty), "one seen before stays as it was");
        } finally {
            PalworksConfig.setCurrent(before);
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aPokemonsWorkHasASwitchForThePartyAndOneForAPasture(GameTestHelper helper) {
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        var party = Cobblemon.INSTANCE.getStorage().getParty(owner);
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(owner);
        Pokemon pokemon = Workers.create(BULBASAUR); // off in the party, on in a pasture (the defaults)
        party.add(pokemon);
        WorkerStateManager.setEnabled(pokemon, true);
        helper.assertTrue(WorkerStateManager.isEnabled(pokemon), "switched on in the party");

        party.remove(pokemon);
        pc.add(pokemon);
        WorkerStateManager.setEnabled(pokemon, false);
        helper.assertFalse(WorkerEligibility.works(pokemon, WorkerStateManager.get(pokemon)), "switched off pastured");

        pc.remove(pokemon);
        party.add(pokemon);
        helper.assertTrue(
                WorkerEligibility.works(pokemon, WorkerStateManager.get(pokemon)), "and still on back in the party");
        WorkerStateManager.unload(pokemon.getUuid());
        helper.succeed();
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
        Workers.sendOut(helper, MACHOP, START); // Fighting: Lumbering, no Nether

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

    @GameTest(template = Templates.EMPTY, batch = "worker_nether_yes", timeoutTicks = 1000)
    public void netherLumberjacksThatArentGhostTypesChopNetherTreesToo(GameTestHelper helper) {
        Workers.isolate(helper);
        crimsonFungus(helper);
        Workers.sendOut(helper, "pignite level=20", START); // Fire/Fighting: Lumbering, Nether by species

        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.CRIMSON_STEM, new BlockPos(3, 2, 5));
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
        PokemonEntity gastly = Workers.sendOut(helper, "gastly level=20", START); // Ghost/Poison: Nether

        // Nether work's move, Night Shade (the harvest's second step), not the crops' Razor Leaf
        List<Step> steps = JobHandlerRegistry.forJob(JobType.HARVEST_CROPS).steps();
        Set<WorkAnimation> played = new HashSet<>();
        helper.onEachTick(() -> {
            WorkerState state = WorkerStateManager.getOrCreate(gastly.getPokemon());
            if (state.getCurrentReservation() != null
                    && state.getCurrentReservation().getJobType() == JobType.HARVEST_CROPS
                    && state.getStepTicks() > 0) {
                played.add(steps.get(state.getJobStep()).animation());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(WART).getValue(NetherWartBlock.AGE) == 0, HARVESTED);
            helper.assertValueEqual(played, Set.of(WorkAnimations.NETHER_HARVEST), "the moves it played");
        });
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

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_mixed_chest", timeoutTicks = 1600)
    public void aGhostTypeFetchesNetherWartFromAChestWithSeedsInItToo(GameTestHelper helper) {
        // A chest with seeds and wart used to be a seed chest for planters only
        soulSandAndChest(helper, new ItemStack(Items.WHEAT_SEEDS, 8), new ItemStack(Items.NETHER_WART, 8));
        Workers.sendOut(helper, "gastly level=20", START); // Ghost/Poison: Nether only
        Workers.sendOut(helper, PIDGEY, TRANSPORTER); // brings it the wart

        helper.succeedWhen(() -> assertWartPlanted(helper));
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_planter", timeoutTicks = 1600)
    public void aPlanterThatIsAGhostTypeFetchesNetherWartToo(GameTestHelper helper) {
        // Nether work is all wart needs, whatever else it can do: a planter used to look for
        // farmland seeds in the chest instead, and take nothing
        soulSandAndChest(helper, new ItemStack(Items.NETHER_WART, 8));
        Workers.sendOut(helper, "phantump level=20", START); // Ghost/Grass: Planting, Gathering, Nether
        Workers.sendOut(helper, PIDGEY, TRANSPORTER); // brings it the wart

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

    @GameTest(template = Templates.EMPTY)
    public void savingAWorkersStateOrInventoryTellsCobblemonItChanged(GameTestHelper helper) {
        // In its owner's PC, as a pastured worker is: Cobblemon writes a PC out only once it's been
        // told one of its Pokémon changed, so a save that doesn't say so can be gone after a restart
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        Pokemon pidgey = PokemonProperties.Companion.parse(PIDGEY).create(); // Flying: Transporting
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(owner);
        pc.add(pidgey);
        WorkerState state = WorkerStateManager.getOrCreate(pidgey);
        AtomicInteger changes = new AtomicInteger();
        pc.getAnyChangeObservable().subscribe(unit -> changes.incrementAndGet());

        WorkerStateManager.setEnabled(pidgey, false);
        helper.assertTrue(changes.get() == 1, "switching its work off is a change to save: " + changes.get());
        WorkerStateManager.save(pidgey, state);
        helper.assertTrue(changes.get() == 1, "saving it again, unchanged, isn't: " + changes.get());
        WorkerInventoryManager.getOrCreate(pidgey).setItem(0, new ItemStack(Items.WHEAT, 5));
        helper.assertTrue(changes.get() == 2, "an item put in its inventory is: " + changes.get());

        WorkerInventoryManager.unload(pidgey.getUuid());
        WorkerStateManager.unload(pidgey.getUuid());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aCachedInventoryIsSavedIntoThePokemonItsAskedForWith(GameTestHelper helper) {
        Pokemon pidgey = PokemonProperties.Companion.parse(PIDGEY).create(); // Flying: Transporting
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pidgey);
        // The same Pokémon as a new object, as Cobblemon makes when its owner logs back in. One
        // still out then (pastured, its chunk kept loaded) keeps its cached inventory.
        Pokemon reloaded = pidgey.clone(false, helper.getLevel().registryAccess());
        helper.assertTrue(
                inventory.equals(WorkerInventoryManager.getOrCreate(reloaded)),
                "the inventory it had is still its own");
        inventory.setItem(0, new ItemStack(Items.WHEAT, 5));

        WorkerInventoryManager.unload(reloaded.getUuid()); // read back from the new object's data
        ItemStack loaded = WorkerInventoryManager.getOrCreate(reloaded).getItem(0);
        WorkerInventoryManager.unload(reloaded.getUuid());
        helper.assertTrue(
                loaded.is(Items.WHEAT) && loaded.getCount() == 5,
                "what it was given since is in the new object's data, not the old one's: " + loaded);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "inventory_screen")
    public void anInventoryScreenClosesOnceTheWorkerNoLongerCarriesThatInventory(GameTestHelper helper) {
        Workers.isolate(helper);
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        PokemonEntity pidgey = Workers.sendOut(helper, owner, PIDGEY, new BlockPos(4, 1, 4));
        Pokemon pokemon = pidgey.getPokemon();
        // A couple of ticks on, not as it's sent out: a test that fails the tick its Pokémon joins
        // the world took the whole test server down with it
        helper.runAfterDelay(2, () -> {
            WorkerInventoryMenu screen = ModMenuTypes.menuFor(1, owner.getInventory(), pokemon);
            helper.assertTrue(screen.stillValid(owner), "open while it's out with that inventory");

            // As when it stops ticking for a moment, or faints: dropped from the cache, and read
            // again from its data. The open screen is on the old copy, with the same items in it.
            WorkerInventoryManager.unload(pokemon.getUuid());
            WorkerInventoryManager.getOrCreate(pokemon);
            helper.assertFalse(screen.stillValid(owner), "closed: the worker carries another copy now");

            WorkerInventoryMenu reopened = ModMenuTypes.menuFor(2, owner.getInventory(), pokemon);
            helper.assertTrue(reopened.stillValid(owner), "opened again, it's on the copy the worker has");
            pokemon.recall();
            helper.assertFalse(reopened.stillValid(owner), "closed once it's recalled");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "inventory_screen_reach")
    public void anInventoryScreenClosesOnceItsPlayerIsOutOfReach(GameTestHelper helper) {
        Workers.isolate(helper);
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        PokemonEntity pidgey = Workers.sendOut(helper, owner, PIDGEY, new BlockPos(4, 1, 4));
        helper.runAfterDelay(2, () -> {
            WorkerInventoryMenu screen = ModMenuTypes.menuFor(1, owner.getInventory(), pidgey.getPokemon());
            owner.moveTo(pidgey.getX() + 1, pidgey.getY(), pidgey.getZ());
            helper.assertTrue(screen.stillValid(owner), "open beside it");
            // As a horse's or a chest boat's screen: shut once it's 4 blocks past the player's reach
            owner.moveTo(pidgey.getX() + 20, pidgey.getY(), pidgey.getZ());
            helper.assertFalse(screen.stillValid(owner), "closed 20 blocks from it");
            helper.succeed();
        });
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

    @GameTest(template = Templates.EMPTY)
    public void whatAPlanterCantPlantIsCargoNotSeeds(GameTestHelper helper) {
        // Workers plant fields (farmland, soul sand). Cocoa beans and sweet berries don't go in
        // one: kept as seeds, a worker that plants never put its harvest of them in a chest
        Set<Suitability> planter = EnumSet.of(Suitability.PLANTING, Suitability.TRANSPORTING);
        for (var item : List.of(Items.COCOA_BEANS, Items.SWEET_BERRIES)) {
            ItemStack stack = new ItemStack(item);
            helper.assertTrue(WorkerInventory.isCargo(stack, planter), item + " is cargo");
            helper.assertFalse(
                    WorkerInventory.accepts(CarryMode.SEED_POUCH, stack), item + " doesn't go in a seed pouch");
        }
        for (var item : List.of(Items.WHEAT_SEEDS, Items.CARROT, Items.NETHER_WART)) {
            ItemStack stack = new ItemStack(item);
            helper.assertFalse(WorkerInventory.isCargo(stack, planter), item + " is kept to plant");
            helper.assertTrue(WorkerInventory.accepts(CarryMode.SEED_POUCH, stack), item + " goes in a seed pouch");
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_unreachable_moved", timeoutTicks = 450)
    public void aWorkerThatsMovedLooksAgainAtWhatItCouldntReach(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // Ripe wheat in a walled pen (too high to jump), the only job there is, and a worker
        // outside it, 12 blocks off
        BlockPos crop = new BlockPos(4, 1, 4);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, WHEAT.getStateForAge(7));
        for (int i = 1; i <= 7; i++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(i, y, 1, Blocks.STONE);
                helper.setBlock(i, y, 7, Blocks.STONE);
                helper.setBlock(1, y, i, Blocks.STONE);
                helper.setBlock(7, y, i, Blocks.STONE);
            }
        }
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, new BlockPos(-8, 1, 4));
        JobManager jobs = JobManager.getInstance(helper.getLevel());
        UUID uuid = worker.getPokemon().getUuid();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(
                        jobs.unreachableFor(uuid, helper.getLevel().getGameTime())
                                .isEmpty(),
                        "it can't get to the wheat"))
                // Put down inside the pen: from here it can, long before it would give the spot
                // another try anyway (30 seconds)
                .thenExecute(() -> {
                    BlockPos inside = helper.absolutePos(new BlockPos(3, 1, 3));
                    worker.moveTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        helper.getBlockState(crop).equals(WHEAT.getStateForAge(0)), "harvested, once it looked again"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_wart_adopted", timeoutTicks = 600)
    public void aJobKeptOverARestartStillNeedsItsSkills(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.STONE);
        // A Grass type, which harvests whatever Gathering can; and a Ghost type that has ripe nether
        // wart as its job the way it has after a restart: saved with it, and not in the (new) pool
        Workers.sendOut(helper, "ivysaur level=20", START);
        PokemonEntity gastly = Workers.sendOut(helper, "gastly level=20", new BlockPos(1, 1, 1));

        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.setBlock(WART.below(), Blocks.SOUL_SAND);
                    helper.setBlock(WART, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
                    WorkerStateManager.getOrCreate(gastly.getPokemon())
                            .setCurrentReservation(new ReservationId(
                                    helper.absolutePos(WART),
                                    JobType.HARVEST_CROPS,
                                    helper.getLevel().getGameTime()));
                    JobManager.getInstance(helper.getLevel())
                            .registerWorker(gastly.getPokemon(), gastly, helper.getLevel());
                    // Then it's recalled, and its job goes back for another worker
                    gastly.discard();
                })
                .thenIdle(300)
                .thenExecute(() -> helper.assertTrue(
                        helper.getBlockState(WART).getValue(NetherWartBlock.AGE) == 3,
                        "the nether wart was harvested by a Grass type"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_tether_box", timeoutTicks = 800)
    public void aPasturedWorkerOnlyWorksWhereItsPastureLetsItRoam(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // Wheat 3 blocks from its pasture, and more 12 blocks off: within its work range, but past
        // where its pasture lets it roam (6 blocks here), where Cobblemon takes it back from
        BlockPos near = new BlockPos(3, 1, 4);
        BlockPos far = new BlockPos(3, 1, 13);
        for (BlockPos crop : List.of(near, far)) {
            helper.setBlock(crop.below(), Blocks.FARMLAND);
            helper.setBlock(crop, WHEAT.getStateForAge(7));
        }
        PokemonEntity worker = Workers.sendOutPastured(helper, BULBASAUR, START);
        Workers.tetherTo(helper, worker, START, 6);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        helper.getBlockState(near).equals(WHEAT.getStateForAge(0)),
                        "the wheat by its pasture harvested"))
                .thenIdle(300)
                .thenExecute(() -> {
                    helper.assertTrue(
                            worker.isAlive() && worker.getTethering() != null, "it's still out, by its pasture");
                    helper.assertTrue(
                            helper.getBlockState(far).equals(WHEAT.getStateForAge(7)),
                            "the wheat past its roam is left");
                })
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_off_stops", timeoutTicks = 600)
    public void aWorkerSwitchedOffStopsWalkingToItsJob(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // Wheat 14 blocks off, so it's a while on its way
        BlockPos crop = new BlockPos(3, 1, 15);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, WHEAT.getStateForAge(7));
        // Pastured: one from a party is called back to its owner's side as soon as it has no job
        PokemonEntity worker = Workers.sendOutPastured(helper, BULBASAUR, START);
        WorkerState state = WorkerStateManager.getOrCreate(worker.getPokemon());
        double startZ = helper.absolutePos(START).getZ();
        BlockPos wheat = helper.absolutePos(crop);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        state.getCurrentReservation() != null && worker.getZ() > startZ + 3, "on its way to the wheat"))
                // As /palworks worker disable does
                .thenExecute(() -> WorkerStateManager.setEnabled(worker.getPokemon(), false))
                // Asked straight away, of where it's walking to: left a while it wanders about, as
                // any Pokémon out of work does, and the wheat's way is as likely as any other
                .thenIdle(2)
                .thenExecute(() -> {
                    Path path = worker.getNavigation().getPath();
                    Node end = path == null || worker.getNavigation().isDone() ? null : path.getEndNode();
                    boolean toTheWheat = end != null && end.asBlockPos().closerThan(wheat, 4);
                    helper.assertFalse(toTheWheat, "it's still walking to the job it gave up");
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertTrue(
                        helper.getBlockState(crop).equals(WHEAT.getStateForAge(7)), "the wheat is left"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_off_forgotten", timeoutTicks = 200)
    public void aSwitchedOffWorkerIsForgottenOnceItsRecalled(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        Pokemon pokemon = worker.getPokemon();

        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> WorkerStateManager.setEnabled(pokemon, false))
                .thenIdle(10)
                .thenExecute(() -> {
                    // Something only its state in memory has (it isn't saved)
                    WorkerStateManager.getOrCreate(pokemon).setWalkedToJob(true);
                    worker.discard(); // recalled
                })
                // What's kept in memory for it goes, as a working one's does: it's read from its data
                // again the next time it's out
                .thenIdle(40)
                .thenExecute(() -> helper.assertFalse(
                        WorkerStateManager.getOrCreate(pokemon).hasWalkedToJob(),
                        "its state was still in memory after it was recalled"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "worker_slow_walk", timeoutTicks = 4000)
    public void aSlowWorkerOnALongWalkKeepsItsJob(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos crop = new BlockPos(3, 1, 21); // 20 blocks off
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, WHEAT.getStateForAge(7));
        PokemonEntity worker = Workers.sendOut(helper, BULBASAUR, START);
        // Slowed so the walk takes longer than the 30 seconds a worker may hold a job without
        // starting on it (as a long way round does)
        worker.getAttribute(Attributes.MOVEMENT_SPEED)
                .addTransientModifier(new AttributeModifier(
                        ResourceLocation.fromNamespaceAndPath("palworks_gametest", "slow"),
                        -0.72,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        WorkerState state = WorkerStateManager.getOrCreate(worker.getPokemon());
        long[] since = {-1};
        long[] walked = {-1};
        boolean[] dropped = {false};
        helper.onEachTick(() -> {
            if (walked[0] >= 0) {
                return;
            }
            boolean hasJob = state.getCurrentReservation() != null;
            if (hasJob && since[0] < 0) {
                since[0] = helper.getTick();
            }
            if (hasJob && state.isWorking()) {
                walked[0] = helper.getTick() - since[0]; // there, and at work
            }
            if (!hasJob && since[0] >= 0) {
                dropped[0] = true;
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(crop).equals(WHEAT.getStateForAge(0)), "harvested");
            if (walked[0] <= WorkerAI.RESERVATION_TIMEOUT_TICKS) {
                helper.fail("the walk took " + walked[0] + " ticks: not the 30 seconds this is about");
            }
            helper.assertFalse(dropped[0], "it gave its job up on the way");
        });
    }
}
