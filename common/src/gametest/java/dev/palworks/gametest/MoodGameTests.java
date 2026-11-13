package dev.palworks.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.block.ModBlocks;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.config.PalworksConfig;
import dev.palworks.job.JobType;
import dev.palworks.mood.Mood;
import dev.palworks.net.packets.WorkerMoodPacket;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkLevelSync;
import dev.palworks.worker.WorkSpeed;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatus;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Worker mood: work, hunger and no rest wear it down; fed and rested it comes back up while not at
 * work; unhappy it works slower, and at 0 it goes on strike until it's back up a way.
 */
public class MoodGameTests {
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final BlockPos TROUGH = new BlockPos(5, 1, 5);
    private static final BlockPos CROP = new BlockPos(5, 1, 2);
    private static final String WORKER = "bulbasaur level=20";
    private static final long MORNING = 1_000;

    private static WorkerState stateOf(Pokemon pokemon) {
        return WorkerStateManager.getOrCreate(pokemon);
    }

    private static void ripeWheat(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos.below(), Blocks.FARMLAND);
        helper.setBlock(pos, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
    }

    private static boolean isRipeWheat(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockState(pos).is(Blocks.WHEAT)
                && ((CropBlock) Blocks.WHEAT).isMaxAge(helper.getBlockState(pos));
    }

    // ---- Down and back up ----

    @GameTest(template = Templates.EMPTY, batch = "mood_work")
    public void workWearsItDown(GameTestHelper helper) {
        // A party worker: it has a mood too
        Workers.isolate(helper);
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        WorkerState state = stateOf(pokemon);
        helper.assertTrue(state.getMood() == WorkerState.MAX_MOOD, "a new worker is in the best of moods");

        Mood.didWork(pokemon, state, 20);
        helper.assertTrue(Math.abs(state.getMood() - 90) < 0.1, "20 crops' worth: 10 points, not " + state.getMood());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_recover", timeoutTicks = 600)
    public void aFedRestedIdleWorkerCheersUp(GameTestHelper helper) {
        Workers.isolate(helper);
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        WorkerState state = stateOf(pokemon);
        state.setMood(40);
        // 100 a day: a point every 240 ticks
        helper.succeedWhen(() -> helper.assertTrue(state.getMood() > 41, "cheering up: " + state.getMood()));
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_hungry", timeoutTicks = 400)
    public void aHungryWorkerDoesntCheerUpAndGetsGlummer(GameTestHelper helper) {
        Workers.isolate(helper);
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        pokemon.setCurrentFullness(0); // and no trough to eat from
        WorkerState state = stateOf(pokemon);
        state.setMood(40);
        helper.runAfterDelay(300, () -> {
            helper.assertTrue(state.getMood() < 40, "glummer while Hungry: " + state.getMood());
            helper.succeed();
        });
    }

    @AfterBatch(batch = "mood_tired")
    public void afterTired(ServerLevel level) {
        level.setDayTime(MORNING);
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_tired")
    public void aTiredPasturedWorkerGetsGlummer(GameTestHelper helper) {
        Workers.isolate(helper);
        helper.getLevel().setDayTime(MORNING); // not its bedtime: it's awake, and Tired
        Pokemon pokemon = Workers.sendOutPastured(helper, WORKER, START).getPokemon();
        WorkerState state = stateOf(pokemon);
        state.setTired(true);
        state.setMood(60);
        // A tenth of a day: 25 a day Tired is 2.5 off
        for (int i = 0; i < 2_400; i++) {
            Mood.tick(pokemon, state);
        }
        helper.assertTrue(Math.abs(state.getMood() - 57.5) < 0.01, "2.5 off for being Tired: " + state.getMood());

        // Rested again, it comes back up (100 a day: 10 in a tenth)
        state.setTired(false);
        for (int i = 0; i < 2_400; i++) {
            Mood.tick(pokemon, state);
        }
        helper.assertTrue(Math.abs(state.getMood() - 67.5) < 0.01, "10 back once rested: " + state.getMood());
        helper.succeed();
    }

    // ---- Unhappy, and on strike ----

    @GameTest(template = Templates.EMPTY, batch = "mood_unhappy", timeoutTicks = 200)
    public void anUnhappyWorkerIsSlowerAndShowsIt(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        WorkerState state = stateOf(pokemon);
        helper.assertTrue(WorkSpeed.of(pokemon, state) == 1.0, "happy: full speed");

        state.setMood(40);
        helper.assertTrue(WorkSpeed.of(pokemon, state) == 0.75, "unhappy: three quarters");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        worker.getAspects().contains(WorkerStatus.UNHAPPY.aspect()), "unhappy over its head"))
                .thenExecute(() -> {
                    state.setMood(0);
                    state.setStriking(true);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            worker.getAspects().contains(WorkerStatus.STRIKING.aspect()), "on strike over its head");
                    helper.assertFalse(
                            worker.getAspects().contains(WorkerStatus.UNHAPPY.aspect()), "instead of unhappy");
                })
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_strike", timeoutTicks = 800)
    public void aWorkerOnStrikeEatsButDoesNoWorkUntilItsBackUp(GameTestHelper helper) {
        Workers.isolate(helper);
        ripeWheat(helper, CROP);
        helper.setBlock(TROUGH, ModBlocks.trough("oak").get());
        TroughBlockEntity trough = (TroughBlockEntity) helper.getBlockEntity(TROUGH);
        trough.addFood(new ItemStack(
                BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", "oran_berry")), 9));
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        pokemon.setCurrentFullness(0);
        WorkerState state = stateOf(pokemon);
        state.setMood(0);
        state.setStriking(true);

        helper.startSequence()
                // It still eats: that's how it comes back round
                .thenWaitUntil(() -> helper.assertTrue(pokemon.getCurrentFullness() > 0, "it ate"))
                .thenIdle(200)
                .thenExecute(() -> {
                    helper.assertTrue(isRipeWheat(helper, CROP), "but harvests nothing");
                    helper.assertTrue(state.isStriking(), "still on strike: " + state.getMood());
                    // Back up to where the strike ends
                    state.setMood(30);
                })
                .thenWaitUntil(() -> helper.assertFalse(isRipeWheat(helper, CROP), "back at work: harvested"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_strike_furnace", timeoutTicks = 600)
    public void aRepeatingJobStopsAtTheStrike(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos furnacePos = new BlockPos(5, 1, 5);
        helper.setBlock(furnacePos, Blocks.FURNACE);
        var furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(furnacePos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64)); // smelting for a long while
        Pokemon charmander =
                Workers.sendOut(helper, "charmander level=20", START).getPokemon();
        WorkerState state = stateOf(charmander);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    ReservationId job = state.getCurrentReservation();
                    helper.assertTrue(
                            job != null && job.getJobType() == JobType.FUEL_FURNACE && state.isWorking(),
                            "lighting the furnace");
                })
                .thenExecute(() -> {
                    state.setMood(0);
                    state.setStriking(true);
                })
                .thenWaitUntil(() -> helper.assertTrue(state.getCurrentReservation() == null, "it downs tools"))
                .thenIdle(100)
                .thenExecute(() -> helper.assertTrue(state.getCurrentReservation() == null, "and doesn't go back"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_switched_off", timeoutTicks = 400)
    public void aSwitchedOffWorkersMoodHolds(GameTestHelper helper) {
        Workers.isolate(helper);
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        WorkerStateManager.setEnabled(pokemon, false);
        WorkerState state = stateOf(pokemon);
        state.setMood(40);
        Mood.didWork(pokemon, state, 20);
        helper.assertTrue(state.getMood() == 40, "its work doesn't count");
        helper.assertTrue(WorkSpeed.of(pokemon, state) == 1.0, "not slowed");
        helper.runAfterDelay(300, () -> {
            helper.assertTrue(state.getMood() == 40, "it neither cheers up nor glums: " + state.getMood());
            helper.succeed();
        });
    }

    // ---- To the client ----

    @GameTest(template = Templates.EMPTY)
    public void moodsSurviveThePacket(GameTestHelper helper) {
        Map<UUID, WorkerMoodPacket.Shown> moods = Map.of(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), new WorkerMoodPacket.Shown(72, false, false),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), new WorkerMoodPacket.Shown(12, true, true));
        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        new WorkerMoodPacket(moods, 30).encode(buffer);
        WorkerMoodPacket decoded = WorkerMoodPacket.decode(buffer);
        helper.assertTrue(decoded.getMoods().equals(moods), "decoded " + decoded.getMoods());
        helper.assertTrue(decoded.getStrikeUntil() == 30, "and where a strike ends");
        RegistryFriendlyByteBuf off =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        WorkerMoodPacket.off().encode(off);
        WorkerMoodPacket decodedOff = WorkerMoodPacket.decode(off);
        helper.assertFalse(decodedOff.isEnabled(), "off stays off");
        helper.assertTrue(decoded.isEnabled(), "and on, on");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "mood_sync")
    public void aPartysMoodsAreSentAndSentAgainWhenTheyChange(GameTestHelper helper) {
        // In the party, not sent out: its mood is sent either way
        ServerPlayer owner = Workers.owner(helper, START);
        Pokemon bulbasaur = PokemonProperties.Companion.parse(WORKER).create();
        Cobblemon.INSTANCE.getStorage().getParty(owner).add(bulbasaur);
        UUID player = owner.getUUID();
        UUID pokemon = bulbasaur.getUuid();
        PalworksConfig loaded = PalworksConfig.current();
        try {
            WorkLevelSync.syncParty(owner);
            Optional<WorkerMoodPacket.Shown> sent = WorkLevelSync.lastSentMood(player, pokemon);
            helper.assertTrue(sent.equals(Optional.of(new WorkerMoodPacket.Shown(100, false, false))), "sent " + sent);

            stateOf(bulbasaur).setMood(31.6);
            WorkLevelSync.syncParty(owner);
            sent = WorkLevelSync.lastSentMood(player, pokemon);
            helper.assertTrue(
                    sent.equals(Optional.of(new WorkerMoodPacket.Shown(32, true, false))), "then sent " + sent);

            // Mood turned off (as /palworks config can): the player is told to drop what it was sent
            PalworksConfig.setCurrent(PalworksConfig.DEFAULTS.withMood(
                    new PalworksConfig.MoodConfig(false, 2, 50, 25, 100, 50, 0.75, 30)));
            WorkLevelSync.syncParty(owner);
            helper.assertTrue(WorkLevelSync.lastSentMood(player, pokemon).isEmpty(), "dropped: mood is off");
        } finally {
            PalworksConfig.setCurrent(loaded);
            WorkLevelSync.forget(player);
        }
        helper.succeed();
    }
}
