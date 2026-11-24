package dev.palworks.gametest;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.advancement.WorkerAdvancements;
import dev.palworks.mood.Mood;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;

/** The Palworks advancement tab: every advancement loads, and workers earn their owners them. */
public class AdvancementGameTests {
    private static final String ROOT = "root";
    private static final String SQUIRTLE = "squirtle level=20";
    private static final BlockPos START = new BlockPos(1, 1, 1);

    /** Every advancement in data/palworks/advancement/workers. */
    private static final List<String> ALL = List.of(
            ROOT,
            "day_one",
            "fired_up",
            "brewmaster",
            "rain_dance",
            "sow_and_tell",
            "reap_what_you_sow",
            "tall_order",
            "wart_and_all",
            "timber",
            "old_growth",
            "special_delivery",
            "room_service",
            "rock_collector",
            "gem_collection",
            "ground_breaking",
            "rough_diamonds",
            "jack_of_all_trades",
            "chow_time",
            "sweet_dreams",
            "power_nap",
            "rude_awakening",
            "walkout",
            "back_on_the_clock",
            "small_business",
            "workforce");

    private static AdvancementHolder advancement(GameTestHelper helper, String name) {
        return helper.getLevel().getServer().getAdvancements().get(Palworks.id("workers/" + name));
    }

    private static boolean has(GameTestHelper helper, ServerPlayer player, String name) {
        AdvancementHolder holder = advancement(helper, name);
        return holder != null
                && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    @GameTest(template = Templates.EMPTY)
    public void everyAdvancementLoads(GameTestHelper helper) {
        // One that fails to decode (a renamed job type, a block that isn't there) is left out with a
        // log line, so look for each by name, and for no others
        for (String name : ALL) {
            AdvancementHolder holder = advancement(helper, name);
            helper.assertTrue(holder != null, "palworks:workers/" + name + " didn't load");
            // Every one says so when it's earned, the root too (vanilla's roots don't)
            DisplayInfo display = holder.value().display().orElseThrow();
            helper.assertTrue(
                    display.shouldShowToast() && display.shouldAnnounceChat(),
                    name + " should show a toast and a chat message when it's earned");
        }
        long loaded = helper.getLevel().getServer().getAdvancements().getAllAdvancements().stream()
                .filter(holder -> Palworks.MOD_ID.equals(holder.id().getNamespace())
                        && holder.id().getPath().startsWith("workers/"))
                .count();
        helper.assertTrue(loaded == ALL.size(), loaded + " loaded, but this test knows of " + ALL.size());
        // Ours don't push anyone else's out: vanilla's tabs and Cobblemon's load as well
        ServerAdvancementManager all = helper.getLevel().getServer().getAdvancements();
        for (String other : List.of(
                "minecraft:story/root",
                "minecraft:adventure/root",
                "minecraft:husbandry/root",
                "minecraft:nether/root",
                "minecraft:end/root",
                "cobblemon:catching/root_catching")) {
            helper.assertTrue(all.get(ResourceLocation.parse(other)) != null, other + " didn't load");
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "advancement_join", timeoutTicks = 200)
    public void workersAlreadyWorkingAreCountedWhenTheirOwnerJoins(GameTestHelper helper) {
        // A pasture's workers start working as the world loads, before their owner has joined, and
        // what they earn then is let go: joining counts them
        Workers.isolate(helper);
        ServerPlayer owner = Workers.owner(helper, START);
        Workers.sendOut(helper, owner, SQUIRTLE, START);
        AdvancementHolder root = advancement(helper, ROOT);

        helper.runAfterDelay(20, () -> {
            for (String criterion :
                    owner.getAdvancements().getOrStartProgress(root).getCompletedCriteria()) {
                owner.getAdvancements().revoke(root, criterion);
            }
            helper.assertFalse(
                    has(helper, owner, ROOT), "taken away, as if it had started working while they were away");
            WorkerAdvancements.onPlayerJoin(owner);
            helper.assertTrue(has(helper, owner, ROOT), "joining counts the worker already out");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "advancement_water", timeoutTicks = 600)
    public void aWorkerWateringACropEarnsItsOwnerItsFirstJob(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos crop = new BlockPos(4, 1, 4);
        helper.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
        helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(0));
        ServerPlayer owner = Workers.owner(helper, START);
        Workers.sendOut(helper, owner, SQUIRTLE, START);

        helper.succeedWhen(() -> {
            helper.assertTrue(has(helper, owner, ROOT), "a Pokémon at work earns the tab's root");
            helper.assertTrue(has(helper, owner, "day_one"), "its first job: Day One");
            helper.assertTrue(has(helper, owner, "rain_dance"), "watering: Rain Dance");
            helper.assertFalse(has(helper, owner, "timber"), "it hasn't felled a tree");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "advancement_workers", timeoutTicks = 200)
    public void fivePokemonWorkingAtOnceAreASmallBusiness(GameTestHelper helper) {
        Workers.isolate(helper);
        ServerPlayer owner = Workers.owner(helper, START);
        for (int i = 0; i < 5; i++) {
            Workers.sendOut(helper, owner, SQUIRTLE, new BlockPos(1 + i, 1, 1));
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(has(helper, owner, "small_business"), "5 working at once");
            helper.assertFalse(has(helper, owner, "workforce"), "but not 15");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "advancement_strike", timeoutTicks = 600)
    public void aStrikeAndTheEndOfItEachEarnOne(GameTestHelper helper) {
        Workers.isolate(helper);
        ServerPlayer owner = Workers.owner(helper, START);
        Pokemon pokemon = Workers.sendOut(helper, owner, SQUIRTLE, START).getPokemon();
        WorkerState state = WorkerStateManager.get(pokemon);
        helper.assertTrue(state != null, "a worker state");

        state.setMood(1);
        Mood.didWork(pokemon, state, 10);
        helper.assertTrue(state.isStriking(), "worked down to 0, it strikes");
        helper.assertTrue(has(helper, owner, "walkout"), "a strike: Walkout");
        helper.assertFalse(has(helper, owner, "back_on_the_clock"), "it's still on strike");

        // Just under the mood it works again at: fed and idle, it cheers up past it
        state.setMood(29.99);
        helper.succeedWhen(() -> {
            helper.assertFalse(state.isStriking(), "its mood is back up");
            helper.assertTrue(has(helper, owner, "back_on_the_clock"), "back at work: Back on the Clock");
        });
    }
}
