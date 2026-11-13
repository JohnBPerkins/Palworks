package dev.palworks.gametest;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.block.ModBlocks;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.worker.WorkSpeed;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
        PokemonEntity squirtle = Workers.sendOutPastured(helper, "squirtle level=20", new BlockPos(1, 1, 1));
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
                    snorlax.position().distanceToSqr(helper.absoluteVec(new net.minecraft.world.phys.Vec3(5.5, 1, 2.5)))
                            < 0.5,
                    "lying in its middle: " + snorlax.position());
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
        PokemonEntity party = Workers.sendOut(helper, "squirtle level=20", new BlockPos(1, 1, 1));
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
        PokemonEntity squirtle = Workers.sendOutPastured(helper, "squirtle level=20", new BlockPos(1, 1, 1));
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
}
