package dev.palworks.gametest;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.ai.sensors.DrowsySensor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.block.ModBlocks;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Beds;
import dev.palworks.sleep.NonWorkerSleep;
import dev.palworks.sleep.Sleep;
import dev.palworks.worker.WorkSpeed;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Worker sleep: pastured workers sleep in their species' hours, in a straw bed if there's one free,
 * or where they are and wake Tired; party and switched-off workers don't; Cobblemon's own naps are
 * left alone while a worker is idle. The time of day is the whole server's, so each test that sets
 * it has its own batch, and the time is put back to morning after.
 */
public class SleepGameTests {
    private static final long MORNING = 1_000;
    private static final long MIDNIGHT = 18_000;
    private static final BlockPos BED = new BlockPos(5, 1, 5);
    private static final String SQUIRTLE = "squirtle level=20";
    private static final String WOKEN = "sleep_woken";
    private static final String ASLEEP_IN_BED = "asleep in the bed";

    private static void setTime(GameTestHelper helper, long time) {
        helper.getLevel().setDayTime(time);
    }

    private static WorkerState stateOf(PokemonEntity worker) {
        return WorkerStateManager.getOrCreate(worker.getPokemon());
    }

    private static boolean inBed(PokemonEntity worker) {
        ReservationId job = stateOf(worker).getCurrentReservation();
        return stateOf(worker).isAsleep() && job != null && job.getJobType() == JobType.SLEEP;
    }

    private static boolean posedAsleep(PokemonEntity worker) {
        return worker.getEntityData().get(PokemonEntity.getPOSE_TYPE()) == PoseType.SLEEP;
    }

    private static void morningAfter(ServerLevel level) {
        level.setDayTime(MORNING);
    }

    // ---- Bed or Tired ----

    @AfterBatch(batch = "sleep_bed")
    public void afterBed(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_bed", timeoutTicks = 800)
    public void oneSleepsInTheBedTheOtherWithoutAndWakesTired(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        PokemonEntity rattata = Workers.sendOutPastured(helper, "rattata level=20", new BlockPos(1, 1, 6));
        setTime(helper, MIDNIGHT);

        PokemonEntity[] sleepers = {squirtle, rattata};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            stateOf(squirtle).isAsleep() && stateOf(rattata).isAsleep(), "both asleep");
                    helper.assertTrue(inBed(squirtle) != inBed(rattata), "one in the bed, the other not");
                    for (PokemonEntity sleeper : sleepers) {
                        helper.assertTrue(
                                posedAsleep(sleeper), sleeper.getPokemon().getSpecies() + " lies asleep");
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> setTime(helper, MORNING))
                .thenWaitUntil(() -> {
                    for (PokemonEntity sleeper : sleepers) {
                        helper.assertFalse(stateOf(sleeper).isAsleep(), "awake in the morning");
                        helper.assertFalse(posedAsleep(sleeper), "up and about");
                    }
                })
                .thenExecute(() -> {
                    boolean squirtleTired = stateOf(squirtle).isTired();
                    PokemonEntity bedSleeper = squirtleTired ? rattata : squirtle;
                    PokemonEntity groundSleeper = squirtleTired ? squirtle : rattata;
                    helper.assertFalse(stateOf(bedSleeper).isTired(), "the one in the bed is rested");
                    helper.assertTrue(stateOf(groundSleeper).isTired(), "the other is Tired");
                    helper.assertTrue(
                            WorkSpeed.of(groundSleeper.getPokemon(), stateOf(groundSleeper)) < 1, "and slower");
                })
                .thenWaitUntil(() -> {
                    PokemonEntity groundSleeper = stateOf(squirtle).isTired() ? squirtle : rattata;
                    helper.assertTrue(groundSleeper.getAspects().contains(WorkerStatus.TIRED.aspect()), "showing it");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = "sleep_saved")
    public void afterSaved(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_saved", timeoutTicks = 600)
    public void sleepingWithoutABedIsSavedAsItSleeps(GameTestHelper helper) {
        // Its state lives in a cache, flushed to its data now and then; the cache goes when its
        // chunk unloads or the server stops. Asleep on the ground, it used to be flushed only once it
        // woke, so an unload in the night lost that it was asleep and how long it had slept.
        Workers.isolate(helper);
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        setTime(helper, MIDNIGHT);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(stateOf(squirtle).isAsleep(), "asleep, with no bed"))
                .thenIdle(40) // past a periodic save
                .thenExecute(() -> {
                    Pokemon pokemon = squirtle.getPokemon();
                    WorkerStateManager.unload(pokemon.getUuid()); // as its chunk unloading does
                    WorkerState saved = WorkerStateManager.get(pokemon); // read back from its data
                    helper.assertTrue(saved != null && saved.isAsleep(), "saved asleep");
                    helper.assertTrue(saved.getGroundSleepTicks() > 0, "with how long it has slept");
                })
                .thenSucceed();
    }

    // ---- Non-workers in beds ----

    private static final String NIDORAN = "nidoranf level=20"; // Poison: no work skills, and it sleeps

    /**
     * Have Cobblemon make it drowsy now, by its own drowsiness check (a small chance each time, in
     * its resting hours), run until it is; and again whenever it isn't, until it has a bed to go to
     * (on the way it isn't drowsy: see NonWorkerSleep) or it's asleep.
     */
    private static void drowsyUntilAsleep(GameTestHelper helper, PokemonEntity pokemon) {
        JobManager jobs = JobManager.getInstance(helper.getLevel());
        helper.onEachTick(() -> {
            for (int i = 0;
                    i < 100_000
                            && !Sleep.cobblemonHasItAsleep(pokemon)
                            && !drowsy(pokemon)
                            && jobs.nonWorkerBed(pokemon.getPokemon().getUuid()) == null;
                    i++) {
                DrowsySensor.Companion.drowsyLogic(pokemon);
            }
        });
    }

    private static boolean drowsy(PokemonEntity pokemon) {
        return NonWorkerSleep.drowsy(pokemon);
    }

    /**
     * Whether it's asleep in the middle of the straw bed at {@code bedRel}: lying in it (its bed, as
     * a non-worker's), in its sleep pose if its species has one.
     */
    private static boolean asleepInStrawBed(GameTestHelper helper, PokemonEntity pokemon, BlockPos bedRel) {
        Vec3 middle = ((WorkerBedBlock) ModBlocks.STRAW_BED.get()).center(helper.absolutePos(bedRel));
        double dx = pokemon.getX() - middle.x;
        double dz = pokemon.getZ() - middle.z;
        JobManager.NonWorkerBed bed = JobManager.getInstance(helper.getLevel())
                .nonWorkerBed(pokemon.getPokemon().getUuid());
        return bed != null
                && bed.inBed()
                && bed.pos().equals(helper.absolutePos(bedRel))
                && dx * dx + dz * dz < 0.35 * 0.35
                && (!Sleep.hasSleepPose(pokemon) || posedAsleep(pokemon));
    }

    @AfterBatch(batch = "nonworker_bed")
    public void afterNonWorkerBed(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "nonworker_bed", timeoutTicks = 800)
    public void aDrowsyNonWorkerSleepsInAFreeBed(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity nidoran = Workers.sendOutPastured(helper, NIDORAN, new BlockPos(1, 1, 1));
        Workers.tetherTo(helper, nidoran, new BlockPos(1, 1, 3)); // in a pasture, as in play
        setTime(helper, MIDNIGHT);
        drowsyUntilAsleep(helper, nidoran);

        helper.succeedWhen(() -> helper.assertTrue(
                asleepInStrawBed(helper, nidoran, BED),
                "asleep in the bed, as Cobblemon has it sleep: " + nonWorkerSleep(helper, nidoran, BED)));
    }

    /** Where a non-worker is in going to bed, for a failure message. */
    private static String nonWorkerSleep(GameTestHelper helper, PokemonEntity pokemon, BlockPos bedRel) {
        BlockPos bed = helper.absolutePos(bedRel);
        BlockPos stand = Beds.standTile(bed, (WorkerBedBlock) ModBlocks.STRAW_BED.get());
        var brain = pokemon.getBrain();
        return "bed "
                + JobManager.getInstance(helper.getLevel())
                        .nonWorkerBed(pokemon.getPokemon().getUuid())
                + ", fits " + Beds.fits(helper.getLevel(), bed, (WorkerBedBlock) ModBlocks.STRAW_BED.get(), pokemon)
                + ", Cobblemon would let it sleep there " + pokemon.canSleepAt(stand.below())
                + ", drowsy " + NonWorkerSleep.drowsy(pokemon)
                + ", asleep " + Sleep.cobblemonHasItAsleep(pokemon)
                + ", walking to "
                + brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                        .map(t -> t.getTarget().currentBlockPosition().toShortString())
                + ", at " + pokemon.position() + " (bed " + bed.toShortString() + ")";
    }

    @AfterBatch(batch = "nonworker_bed_off")
    public void afterWorkOffBed(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "nonworker_bed_off", timeoutTicks = 800)
    public void aPokemonWithItsWorkOffSleepsInAFreeBedToo(GameTestHelper helper) {
        // It could work, but it's switched off: it isn't working, so it's as one with no work skills
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        WorkerStateManager.setEnabled(squirtle.getPokemon(), false);
        setTime(helper, MIDNIGHT);
        drowsyUntilAsleep(helper, squirtle);

        helper.succeedWhen(() -> helper.assertTrue(
                asleepInStrawBed(helper, squirtle, BED),
                "asleep in the bed, as Cobblemon has it sleep: " + nonWorkerSleep(helper, squirtle, BED)));
    }

    @AfterBatch(batch = "nonworker_bed_no_pose")
    public void afterNoPoseBed(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "nonworker_bed_no_pose", timeoutTicks = 800)
    public void oneCobblemonNeverLetsSleepLiesInAFreeBedAtItsBedtime(GameTestHelper helper) {
        // Cobblemon never makes it drowsy (no sleep animation), so it goes to bed at its bedtime
        // (its Cobblemon hours, any time: night), and lies there still till it's over
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity trubbish = Workers.sendOutPastured(helper, "trubbish level=20", new BlockPos(1, 1, 1));
        helper.assertFalse(Sleep.hasSleepPose(trubbish), "Cobblemon doesn't let a Trubbish sleep");
        setTime(helper, MIDNIGHT);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        asleepInStrawBed(helper, trubbish, BED),
                        "lying in the bed: " + nonWorkerSleep(helper, trubbish, BED)))
                // With no sleep animation, it shows it's asleep over its head
                .thenWaitUntil(() -> helper.assertTrue(
                        trubbish.getAspects().contains(WorkerStatus.ASLEEP.aspect()), "showing it's asleep"))
                .thenExecute(() -> setTime(helper, MORNING))
                .thenWaitUntil(() -> helper.assertTrue(
                        JobManager.getInstance(helper.getLevel())
                                        .nonWorkerBed(trubbish.getPokemon().getUuid())
                                == null,
                        "up in the morning"))
                .thenWaitUntil(() -> helper.assertFalse(
                        trubbish.getAspects().contains(WorkerStatus.ASLEEP.aspect()), "no longer showing it"))
                .thenSucceed();
    }

    @AfterBatch(batch = "sleep_command")
    public void afterSleepCommand(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_command", timeoutTicks = 800)
    public void palworksSleepMakesTheNearestPokemonDrowsy(GameTestHelper helper) {
        // /palworks sleep, from its owner: drowsy now, by Cobblemon's own check, so off to the bed
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity nidoran = Workers.sendOutPastured(helper, NIDORAN, new BlockPos(1, 1, 1));
        setTime(helper, MIDNIGHT);
        ServerPlayer owner = nidoran.getPokemon().getOwnerPlayer();
        helper.getLevel()
                .getServer()
                .getCommands()
                .performPrefixedCommand(
                        owner.createCommandSourceStack().withPermission(2).withSuppressedOutput(), "palworks sleep");

        helper.succeedWhen(() -> helper.assertTrue(
                asleepInStrawBed(helper, nidoran, BED), "asleep in the bed: " + nonWorkerSleep(helper, nidoran, BED)));
    }

    @AfterBatch(batch = "nonworker_bed_kept")
    public void afterNonWorkerBedKept(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "nonworker_bed_kept", timeoutTicks = 1200)
    public void aWorkerIsntGivenTheBedANonWorkerSleepsIn(GameTestHelper helper) {
        // Workers get beds first, but one a non-worker has stays its own: it isn't turned out
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity nidoran = Workers.sendOutPastured(helper, NIDORAN, new BlockPos(1, 1, 1));
        setTime(helper, MIDNIGHT);
        drowsyUntilAsleep(helper, nidoran);
        PokemonEntity[] squirtle = {null};

        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(asleepInStrawBed(helper, nidoran, BED), "the non-worker's in bed"))
                .thenExecute(() -> squirtle[0] = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 6)))
                .thenWaitUntil(() -> helper.assertTrue(stateOf(squirtle[0]).isAsleep(), "the worker's asleep"))
                .thenExecute(() -> {
                    helper.assertFalse(inBed(squirtle[0]), "not in the bed");
                    helper.assertTrue(asleepInStrawBed(helper, nidoran, BED), "the non-worker still is");
                })
                .thenSucceed();
    }

    @AfterBatch(batch = "nonworker_bed_worker_first")
    public void afterWorkerFirst(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "nonworker_bed_worker_first", timeoutTicks = 1000)
    public void aNonWorkerDoesntTakeTheBedAWorkerSleepsIn(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        setTime(helper, MIDNIGHT);
        PokemonEntity[] nidoran = {null};

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), "the worker's in bed"))
                .thenExecute(() -> {
                    nidoran[0] = Workers.sendOutPastured(helper, NIDORAN, new BlockPos(1, 1, 6));
                    drowsyUntilAsleep(helper, nidoran[0]);
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    helper.assertTrue(
                            JobManager.getInstance(helper.getLevel())
                                            .nonWorkerBed(
                                                    nidoran[0].getPokemon().getUuid())
                                    == null,
                            "the non-worker has no bed");
                    helper.assertFalse(asleepInStrawBed(helper, nidoran[0], BED), "and isn't in it");
                    helper.assertTrue(inBed(squirtle), "the worker still is");
                })
                .thenSucceed();
    }

    // ---- In the bed, and woken from it ----

    @AfterBatch(batch = WOKEN)
    public void afterWoken(ServerLevel level) {
        morningAfter(level);
    }

    /** A pastured Squirtle asleep in the bed at {@link #BED}, at midnight. */
    private static PokemonEntity asleepInBed(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        setTime(helper, MIDNIGHT);
        return squirtle;
    }

    /** It's awake after being woken, and stays up (not even asleep where it is). */
    private static void assertWokenAndUp(GameTestHelper helper, PokemonEntity worker) {
        helper.assertFalse(stateOf(worker).isAsleep(), "awake");
        helper.assertFalse(posedAsleep(worker), "up");
        helper.assertTrue(Sleep.isWoken(worker, stateOf(worker)), "and staying up a while");
        ReservationId job = stateOf(worker).getCurrentReservation();
        helper.assertTrue(job == null || job.getJobType() != JobType.SLEEP, "out of its bed");
    }

    @GameTest(template = Templates.EMPTY, batch = WOKEN, timeoutTicks = 600)
    public void aSleeperLiesInTheMiddleOfItsBedOnTopOfIt(GameTestHelper helper) {
        PokemonEntity squirtle = asleepInBed(helper);
        BlockPos abs = helper.absolutePos(BED);
        double top = helper.getBlockState(BED)
                .getCollisionShape(helper.getLevel(), abs)
                .max(net.minecraft.core.Direction.Axis.Y);
        helper.assertTrue(top == WorkerBedBlock.HEIGHT, "the bed is solid to its top: " + top);
        helper.succeedWhen(() -> {
            helper.assertTrue(inBed(squirtle), ASLEEP_IN_BED);
            helper.assertTrue(
                    Math.abs(squirtle.getX() - (abs.getX() + 0.5)) < 0.01
                            && Math.abs(squirtle.getZ() - (abs.getZ() + 0.5)) < 0.01,
                    "in its very middle: " + squirtle.position());
            helper.assertTrue(
                    Math.abs(squirtle.getY() - (abs.getY() + WorkerBedBlock.HEIGHT)) < 0.01,
                    "lying on top of it: " + squirtle.getY());
        });
    }

    @GameTest(template = Templates.EMPTY, batch = WOKEN, timeoutTicks = 800)
    public void beingHurtWakesASleeperWhichGoesBackToBedLater(GameTestHelper helper) {
        PokemonEntity squirtle = asleepInBed(helper);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), ASLEEP_IN_BED))
                .thenExecute(
                        () -> squirtle.hurt(helper.getLevel().damageSources().magic(), 1))
                .thenWaitUntil(() -> assertWokenAndUp(helper, squirtle))
                .thenIdle(100)
                .thenExecute(() -> assertWokenAndUp(helper, squirtle))
                // 15 seconds on, and still night: back to bed by itself
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), "back in its bed"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = WOKEN, timeoutTicks = 600)
    public void breakingItsBedWakesASleeper(GameTestHelper helper) {
        PokemonEntity squirtle = asleepInBed(helper);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), ASLEEP_IN_BED))
                .thenExecute(() -> helper.destroyBlock(BED))
                .thenWaitUntil(() -> assertWokenAndUp(helper, squirtle))
                // Not asleep on the floor instead
                .thenIdle(100)
                .thenExecute(() -> assertWokenAndUp(helper, squirtle))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = WOKEN, timeoutTicks = 600)
    public void recallingASleeperWakesIt(GameTestHelper helper) {
        PokemonEntity squirtle = asleepInBed(helper);
        Pokemon pokemon = squirtle.getPokemon();
        PokemonEntity[] again = new PokemonEntity[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), ASLEEP_IN_BED))
                .thenExecute(pokemon::recall)
                .thenIdle(20)
                .thenExecute(() -> {
                    again[0] = pokemon.sendOut(
                            helper.getLevel(),
                            net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))),
                            null,
                            e -> kotlin.Unit.INSTANCE);
                    helper.assertTrue(again[0] != null, "sent back out");
                })
                .thenIdle(100)
                .thenExecute(() -> assertWokenAndUp(helper, again[0]))
                .thenSucceed();
    }

    @AfterBatch(batch = "sleep_home")
    public void afterHome(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_home", timeoutTicks = 1200)
    public void aPasturedWorkerFarFromItsBedFindsItByItsPasture(GameTestHelper helper) {
        Workers.isolate(helper);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos bed = new BlockPos(2, 1, 2);
        helper.setBlock(bed, ModBlocks.STRAW_BED.get());
        // Loaded all the way to where it looks round itself, as round a player in game, and scanned
        // before night falls
        Runnable unload = Scenes.keepLoaded(helper, new BlockPos(-40, 0, -40), new BlockPos(80, 0, 40));
        // 36 off, past the 32 it looks round itself, but its pasture is right by the bed
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(38, 1, 2));
        Workers.tetherTo(helper, squirtle, new BlockPos(2, 1, 4));
        helper.startSequence()
                .thenIdle(100)
                .thenExecute(() -> setTime(helper, MIDNIGHT))
                .thenWaitUntil(() -> {
                    helper.assertTrue(inBed(squirtle), "asleep in the bed by its pasture");
                    helper.assertTrue(
                            stateOf(squirtle)
                                    .getCurrentReservation()
                                    .getStationPos()
                                    .equals(helper.absolutePos(bed)),
                            "that one");
                })
                .thenExecute(unload)
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_head_home", timeoutTicks = 1200)
    public void aWorkerThatCantGetToItsBedHeadsHomeToSleep(GameTestHelper helper) {
        // Its bed is by its pasture but glassed in: at bedtime it heads home, and sleeps there,
        // not out where it was working
        Workers.isolate(helper);
        Runnable unload = Scenes.keepLoaded(helper, new BlockPos(-40, 0, -40), new BlockPos(80, 0, 40));
        BlockPos bed = new BlockPos(2, 1, 4);
        helper.setBlock(bed, ModBlocks.STRAW_BED.get());
        HungerGameTests.glassIn(helper, bed, 1, false); // walled in, but a bed it'd fit, if it could get there
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(38, 1, 6));
        Workers.tetherTo(helper, squirtle, new BlockPos(4, 1, 6));
        BlockPos bedAbs = helper.absolutePos(bed);
        helper.startSequence()
                .thenIdle(100) // the chunks scanned before night falls, as round a player in game
                .thenExecute(() -> setTime(helper, MIDNIGHT))
                .thenWaitUntil(() -> {
                    helper.assertTrue(stateOf(squirtle).isAsleep(), "asleep");
                    helper.assertTrue(
                            squirtle.blockPosition().closerThan(bedAbs, 10),
                            "by its pasture: " + helper.relativePos(squirtle.blockPosition()));
                })
                .thenExecute(unload)
                .thenSucceed();
    }

    @AfterBatch(batch = "sleep_head_home")
    public void afterHeadHome(ServerLevel level) {
        morningAfter(level);
    }

    // ---- Bed sizes ----

    /** A bed of this block, its corner at {@code origin}, every block of it in place. */
    private static void placeBed(GameTestHelper helper, WorkerBedBlock bed, BlockPos origin) {
        for (int x = 0; x < bed.size(); x++) {
            for (int z = 0; z < bed.size(); z++) {
                helper.setBlock(
                        origin.offset(x, 0, z),
                        bed.defaultBlockState()
                                .setValue(WorkerBedBlock.PART_X, x)
                                .setValue(WorkerBedBlock.PART_Z, z));
            }
        }
    }

    @GameTest(template = Templates.EMPTY)
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel: vanilla's only mock player in 1.21.1
    public void aLargeBedIsPlacedWholeAndBrokenWhole(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModBlocks.LARGE_STRAW_BED_ITEM.get()));
        BlockPos floor = new BlockPos(4, 0, 3);
        Scenes.floor(helper, Blocks.STONE); // something solid to place it on
        BlockPos abs = helper.absolutePos(floor);
        helper.useBlock(
                floor,
                player,
                new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(abs).add(0, 0.5, 0),
                        net.minecraft.core.Direction.UP,
                        abs,
                        false));

        // All four blocks, from the one placed at, forward and to the right
        WorkerBedBlock large = (WorkerBedBlock) ModBlocks.LARGE_STRAW_BED.get();
        java.util.List<BlockPos> bed = new java.util.ArrayList<>();
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                if (helper.getBlockState(new BlockPos(x, 1, z)).is(large)) {
                    bed.add(new BlockPos(x, 1, z));
                }
            }
        }
        helper.assertTrue(bed.size() == 4, "four blocks: " + bed);
        BlockPos origin = WorkerBedBlock.origin(helper.absolutePos(bed.get(0)), helper.getBlockState(bed.get(0)));
        for (BlockPos part : bed) {
            helper.assertTrue(
                    WorkerBedBlock.origin(helper.absolutePos(part), helper.getBlockState(part))
                            .equals(origin),
                    "all one bed");
        }

        // Breaking a block that isn't its corner breaks it all, and drops the bed once
        BlockPos notCorner = bed.stream()
                .filter(part -> !WorkerBedBlock.isOrigin(helper.getBlockState(part)))
                .findFirst()
                .orElseThrow();
        breakAs(helper, notCorner, GameType.SURVIVAL);
        helper.succeedWhen(() -> {
            for (BlockPos part : bed) {
                helper.assertBlockNotPresent(large, part);
            }
            helper.assertTrue(
                    Scenes.itemsOnGround(helper, ModBlocks.LARGE_STRAW_BED_ITEM.get()) == 1, "one bed dropped");
        });
    }

    /**
     * Break the block at {@code pos} as a player in this game mode does (as ServerPlayerGameMode does,
     * for a block that drops nothing itself). Not with vanilla's mock server player: that's always in
     * creative, whatever its game mode is set to.
     */
    private static void breakAs(GameTestHelper helper, BlockPos pos, GameType mode) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(pos);
        BlockState state = level.getBlockState(abs);
        state.getBlock().playerWillDestroy(level, abs, state, helper.makeMockPlayer(mode));
        level.removeBlock(abs, false);
    }

    @GameTest(template = Templates.EMPTY)
    public void aBedBrokenInCreativeDropsNothing(GameTestHelper helper) {
        WorkerBedBlock huge =
                (WorkerBedBlock) ModBlocks.woolBed(DyeColor.RED, 3).get();
        BlockPos origin = new BlockPos(2, 1, 2);
        placeBed(helper, huge, origin);

        // Its middle block: not its corner, which is the one that drops it
        breakAs(helper, origin.offset(1, 0, 1), GameType.CREATIVE);
        helper.succeedWhen(() -> {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    helper.assertBlockNotPresent(huge, origin.offset(x, 0, z));
                }
            }
            helper.assertTrue(
                    Scenes.itemsOnGround(
                                    helper,
                                    ModBlocks.woolBedItem(DyeColor.RED, 3).get())
                            == 0,
                    "nothing dropped");
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void aWoolBedIsDyedAnyOtherColour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int size = 1; size <= WorkerBedBlock.MAX_SIZE; size++) {
            for (DyeColor colour : ModBlocks.WOOL_COLORS) {
                ItemStack dye = new ItemStack(net.minecraft.world.item.DyeItem.byColor(colour));
                DyeColor other = colour == DyeColor.WHITE ? DyeColor.BLACK : DyeColor.WHITE;
                ItemStack made = craft(
                        level,
                        dye,
                        new ItemStack(ModBlocks.woolBedItem(other, size).get()));
                helper.assertTrue(
                        made.is(ModBlocks.woolBedItem(colour, size).get()),
                        "a " + other + " bed of size " + size + " dyed " + colour + " is " + colour + ": " + made);
            }
            // Not into a straw one, nor dyed the colour it already is
            ItemStack redDye = new ItemStack(Items.RED_DYE);
            ItemStack straw = new ItemStack((size == 1
                            ? ModBlocks.STRAW_BED_ITEM
                            : size == 2 ? ModBlocks.LARGE_STRAW_BED_ITEM : ModBlocks.HUGE_STRAW_BED_ITEM)
                    .get());
            helper.assertTrue(craft(level, redDye, straw).isEmpty(), "a straw bed isn't dyed");
            helper.assertTrue(
                    craft(
                                    level,
                                    redDye,
                                    new ItemStack(ModBlocks.woolBedItem(DyeColor.RED, size)
                                            .get()))
                            .isEmpty(),
                    "a red bed isn't dyed red");
        }
        helper.succeed();
    }

    /** What these two items make, side by side in a crafting grid: nothing if no recipe takes them. */
    private static ItemStack craft(ServerLevel level, ItemStack a, ItemStack b) {
        var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, java.util.List.of(a, b));
        return level.getRecipeManager()
                .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input, level.registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    @AfterBatch(batch = "sleep_sizes")
    public void afterSizes(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_sizes", timeoutTicks = 800)
    public void aBigPokemonSleepsInABigBedAndASmallOneInASmallOne(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos small = new BlockPos(1, 1, 6);
        BlockPos huge = new BlockPos(4, 1, 1); // 4-6 by 1-3
        helper.setBlock(small, ModBlocks.STRAW_BED.get());
        // A wool one, the nicer kind, which sleeps as a straw one does
        placeBed(helper, (WorkerBedBlock) ModBlocks.woolBed(DyeColor.RED, 3).get(), huge);
        PokemonEntity snorlax = Workers.sendOutPastured(helper, "snorlax level=30", new BlockPos(1, 1, 1));
        PokemonEntity rattata = Workers.sendOutPastured(helper, "rattata level=20", new BlockPos(3, 1, 6));
        setTime(helper, MIDNIGHT);

        helper.succeedWhen(() -> {
            helper.assertTrue(inBed(snorlax), "Snorlax asleep in a bed");
            helper.assertTrue(inBed(rattata), "Rattata asleep in a bed");
            helper.assertTrue(
                    stateOf(snorlax).getCurrentReservation().getStationPos().equals(helper.absolutePos(huge)),
                    "Snorlax (2.3 wide) in the huge one");
            helper.assertTrue(
                    stateOf(rattata).getCurrentReservation().getStationPos().equals(helper.absolutePos(small)),
                    "Rattata in the small one, leaving the huge one for Snorlax");
            // In the middle of the huge bed
            helper.assertTrue(
                    snorlax.position()
                                    .distanceToSqr(helper.absoluteVec(
                                            new net.minecraft.world.phys.Vec3(5.5, 1 + WorkerBedBlock.HEIGHT, 2.5)))
                            < 1e-4,
                    "lying in its very middle, on top: " + snorlax.position());
        });
    }

    // ---- Who sleeps ----

    @AfterBatch(batch = "sleep_party")
    public void afterParty(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_party", timeoutTicks = 300)
    public void partyAndSwitchedOffWorkersDontSleep(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity party = Workers.sendOut(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        PokemonEntity off = Workers.sendOutPastured(helper, "rattata level=20", new BlockPos(6, 1, 6));
        WorkerStateManager.setEnabled(off.getPokemon(), false);
        setTime(helper, MIDNIGHT);
        helper.runAfterDelay(200, () -> {
            helper.assertFalse(stateOf(party).isAsleep(), "a party worker stays awake with its trainer");
            helper.assertFalse(Sleep.appliesTo(party.getPokemon(), stateOf(party)), "it has no schedule");
            helper.assertFalse(stateOf(off).isAsleep(), "work off: its sleep is Cobblemon's");
            helper.assertFalse(Sleep.blocksCobblemonDrowsiness(off), "and Cobblemon's to start");
            helper.succeed();
        });
    }

    @AfterBatch(batch = "sleep_hours")
    public void afterHours(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_hours")
    public void eachSpeciesSleepsInItsOwnHours(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        PokemonEntity oddish = Workers.sendOutPastured(helper, "oddish level=20", new BlockPos(3, 1, 1));
        PokemonEntity vulpix = Workers.sendOutPastured(helper, "vulpix level=20", new BlockPos(5, 1, 1));
        PokemonEntity zubat = Workers.sendOutPastured(helper, "zubat level=20", new BlockPos(1, 1, 5));

        setTime(helper, MIDNIGHT);
        helper.assertTrue(Sleep.isBedtime(squirtle), "a night sleeper at midnight");
        helper.assertFalse(Sleep.isBedtime(oddish), "a nocturnal one works the night");
        helper.assertTrue(Sleep.isBedtime(vulpix), "custom hours: 13702-22299");
        helper.assertTrue(Sleep.isBedtime(zubat), "any time: at night");

        setTime(helper, 6_000);
        helper.assertFalse(Sleep.isBedtime(squirtle), "a night sleeper works by day");
        helper.assertTrue(Sleep.isBedtime(oddish), "a nocturnal one sleeps by day");
        helper.assertTrue(Sleep.isBedtime(vulpix), "custom hours: 167-11833");
        helper.assertFalse(Sleep.isBedtime(zubat), "any time: not by day");

        setTime(helper, 12_500);
        helper.assertFalse(Sleep.isBedtime(vulpix), "custom hours: awake between 11833 and 13702");
        helper.succeed();
    }

    @AfterBatch(batch = "sleep_no_pose")
    public void afterNoPose(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_no_pose", timeoutTicks = 400)
    public void aSpeciesWithNoSleepAnimationStillSleeps(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity eevee = Workers.sendOutPastured(helper, "eevee level=20", new BlockPos(1, 1, 1));
        helper.assertFalse(Sleep.hasSleepPose(eevee), "Cobblemon has Eevee never sleep");
        setTime(helper, MIDNIGHT);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(stateOf(eevee).isAsleep(), "asleep all the same"))
                .thenExecute(() -> helper.assertFalse(posedAsleep(eevee), "in its normal pose"))
                .thenIdle(10)
                .thenExecute(() -> setTime(helper, MORNING))
                .thenWaitUntil(() -> helper.assertFalse(stateOf(eevee).isAsleep(), "awake"))
                .thenExecute(() -> helper.assertTrue(stateOf(eevee).isTired(), "and Tired: no bed"))
                // By day it's asked whether Cobblemon has it napping: Eevee's brain hasn't even the
                // memory (Cobblemon never lets it sleep), and asking used to crash the server
                .thenIdle(60)
                .thenExecute(() -> helper.assertFalse(Sleep.cobblemonHasItAsleep(eevee), "not napping"))
                .thenSucceed();
    }

    // ---- Alongside Cobblemon's own sleep ----

    @AfterBatch(batch = "sleep_nap")
    public void afterNap(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_nap", timeoutTicks = 600)
    public void aNappingWorkerIsLeftToNap(GameTestHelper helper) {
        Workers.isolate(helper);
        setTime(helper, 2_000); // a working hour for Bulbasaur, which sleeps at night and at noon
        BlockPos crop = new BlockPos(5, 1, 5);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        PokemonEntity bulbasaur = Workers.sendOutPastured(helper, "bulbasaur level=20", new BlockPos(1, 1, 1));
        Pokemon pokemon = bulbasaur.getPokemon();

        // Idle in its working hours: Cobblemon may make it drowsy. Napping, it's given no work
        helper.assertFalse(Sleep.blocksCobblemonDrowsiness(bulbasaur), "idle: Cobblemon's to nap");
        bulbasaur.getBrain().setMemory(CobblemonMemories.POKEMON_SLEEPING, true);
        helper.startSequence()
                .thenIdle(100)
                .thenExecute(() -> helper.assertTrue(
                        WorkerStateManager.getOrCreate(pokemon).getCurrentReservation() == null,
                        "no work while it naps"))
                .thenExecute(() -> bulbasaur.getBrain().eraseMemory(CobblemonMemories.POKEMON_SLEEPING))
                .thenWaitUntil(() -> helper.assertTrue(
                        WorkerStateManager.getOrCreate(pokemon).getCurrentReservation() != null, "awake: it works"))
                // With a job it's kept from dozing off
                .thenExecute(() -> helper.assertTrue(Sleep.blocksCobblemonDrowsiness(bulbasaur), "not drowsy mid-job"))
                .thenSucceed();
    }

    @AfterBatch(batch = "sleep_furnace")
    public void afterFurnace(ServerLevel level) {
        morningAfter(level);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_furnace", timeoutTicks = 1200)
    public void aRepeatingJobStopsAtBedtime(GameTestHelper helper) {
        Workers.isolate(helper);
        setTime(helper, MORNING);
        BlockPos furnacePos = new BlockPos(5, 1, 5);
        helper.setBlock(furnacePos, Blocks.FURNACE);
        var furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(furnacePos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64)); // smelting for a long while
        PokemonEntity charmander = Workers.sendOutPastured(helper, "charmander level=20", new BlockPos(1, 1, 1));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    ReservationId job = stateOf(charmander).getCurrentReservation();
                    helper.assertTrue(
                            job != null
                                    && job.getJobType() == JobType.FUEL_FURNACE
                                    && stateOf(charmander).isWorking(),
                            "lighting the furnace");
                })
                .thenExecute(() -> setTime(helper, MIDNIGHT))
                .thenWaitUntil(() -> helper.assertTrue(stateOf(charmander).isAsleep(), "off to sleep"))
                .thenExecute(() -> helper.assertTrue(
                        stateOf(charmander).getCurrentReservation() == null, "the furnace left till morning"))
                .thenSucceed();
    }

    // ---- Naps: a Tired worker sleeps it off in a bed in the day ----

    /** A pastured Squirtle, Tired, in the morning, with a free bed at {@link #BED}. */
    private static PokemonEntity tiredInTheMorning(GameTestHelper helper) {
        Workers.isolate(helper);
        setTime(helper, MORNING);
        helper.setBlock(BED, ModBlocks.STRAW_BED.get());
        PokemonEntity squirtle = Workers.sendOutPastured(helper, SQUIRTLE, new BlockPos(1, 1, 1));
        stateOf(squirtle).setTired(true);
        return squirtle;
    }

    /** Whether it's up and out of its bed: no SLEEP job, not asleep. */
    private static boolean upAndOut(PokemonEntity worker) {
        ReservationId job = stateOf(worker).getCurrentReservation();
        return !stateOf(worker).isAsleep() && (job == null || job.getJobType() != JobType.SLEEP);
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_nap_rested", timeoutTicks = 2000)
    public void aTiredWorkerNapsInABedInTheDayAndWakesRested(GameTestHelper helper) {
        PokemonEntity squirtle = tiredInTheMorning(helper);
        long[] asleepAt = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), "it takes the bed for a nap"))
                .thenExecute(() -> {
                    asleepAt[0] = helper.getTick();
                    helper.assertTrue(stateOf(squirtle).isTired(), "still Tired while it naps");
                })
                .thenWaitUntil(() -> helper.assertTrue(upAndOut(squirtle), "up once it's slept it off"))
                .thenExecute(() -> {
                    long slept = helper.getTick() - asleepAt[0];
                    helper.assertTrue(slept >= Sleep.NAP_TICKS, "it napped " + slept + " ticks, a whole nap");
                    helper.assertFalse(stateOf(squirtle).isTired(), "rested");
                })
                // Rested, it doesn't go back for another
                .thenIdle(100)
                .thenExecute(() -> helper.assertTrue(upAndOut(squirtle), "and stays up"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_nap_woken", timeoutTicks = 1200)
    public void aWorkerWokenFromItsNapIsStillTired(GameTestHelper helper) {
        PokemonEntity squirtle = tiredInTheMorning(helper);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), "napping"))
                .thenIdle(20)
                .thenExecute(
                        () -> squirtle.hurt(helper.getLevel().damageSources().magic(), 1))
                .thenWaitUntil(() -> assertWokenAndUp(helper, squirtle))
                .thenExecute(
                        () -> helper.assertTrue(stateOf(squirtle).isTired(), "still Tired: it hadn't slept it off"))
                .thenIdle(100)
                .thenExecute(() -> assertWokenAndUp(helper, squirtle))
                // 15 seconds on, still Tired: it goes back for a nap
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), "back in the bed for another"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_nap_eats_first", timeoutTicks = 1200)
    public void aHungryTiredWorkerEatsBeforeItNaps(GameTestHelper helper) {
        PokemonEntity squirtle = tiredInTheMorning(helper);
        BlockPos troughPos = new BlockPos(5, 1, 9);
        helper.setBlock(troughPos, ModBlocks.trough("oak").get());
        ((TroughBlockEntity) helper.getBlockEntity(troughPos))
                .addFood(new ItemStack(
                        BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", "oran_berry")),
                        9));
        squirtle.getPokemon().setCurrentFullness(0);
        JobType[] firstJob = {null};
        helper.onEachTick(() -> {
            ReservationId job = stateOf(squirtle).getCurrentReservation();
            if (firstJob[0] == null && job != null) {
                firstJob[0] = job.getJobType();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(inBed(squirtle), "it naps in the end");
            helper.assertTrue(firstJob[0] == JobType.EAT_FOOD, "but it ate first, not " + firstJob[0]);
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "sleep_nap_strike", timeoutTicks = 2400)
    public void aFedWorkerOnStrikeNapsThenCheersUp(GameTestHelper helper) {
        // Fed but Tired, its mood would only go down until it next went to bed at night
        PokemonEntity squirtle = tiredInTheMorning(helper);
        WorkerState state = stateOf(squirtle);
        state.setMood(0);
        state.setStriking(true);
        double[] rested = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(inBed(squirtle), "on strike, it still naps"))
                .thenWaitUntil(() -> helper.assertTrue(upAndOut(squirtle) && !state.isTired(), "up and rested"))
                .thenExecute(() -> rested[0] = state.getMood())
                .thenWaitUntil(() -> helper.assertTrue(
                        state.getMood() > rested[0], "fed, rested and idle, it cheers up: " + state.getMood()))
                .thenSucceed();
    }
}
