package dev.palworks.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkStance;
import dev.palworks.block.ModBlocks;
import dev.palworks.block.TroughBlock;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.block.TroughFeed;
import dev.palworks.hunger.EatingDisplay;
import dev.palworks.hunger.Hunger;
import dev.palworks.hunger.HungerMath;
import dev.palworks.hunger.StartingFullness;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobType;
import dev.palworks.job.handler.JobHandlerRegistry;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Worker hunger: troughs (what they take, how they join, what breaking one drops), workers eating
 * from them, Palworks' drain of their Cobblemon fullness standing in for Cobblemon's own while their
 * work is on, and a switched-off worker being left entirely to Cobblemon.
 */
public class HungerGameTests {
    private static final BlockPos TROUGH = new BlockPos(5, 1, 5);
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final String WORKER = "bulbasaur level=20";
    private static final String OAK = "oak";

    private static Item oranBerry() {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", "oran_berry"));
    }

    private static TroughBlockEntity trough(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, ModBlocks.trough(OAK).get());
        return (TroughBlockEntity) helper.getBlockEntity(pos);
    }

    /** The feed in this trough and those joined to it. */
    private static int feedIn(TroughBlockEntity trough) {
        return trough.stock().feed();
    }

    private static ItemStack berries(int count) {
        return new ItemStack(oranBerry(), count);
    }

    // ---- The trough ----

    @GameTest(template = Templates.EMPTY)
    public void troughsSideBySideJoin(GameTestHelper helper) {
        BlockPos west = new BlockPos(2, 1, 2);
        BlockPos east = west.east();
        helper.setBlock(west, ModBlocks.trough(OAK).get());
        helper.setBlock(east, ModBlocks.trough(OAK).get());
        helper.assertBlockProperty(west, TroughBlock.EAST, true);
        helper.assertBlockProperty(east, TroughBlock.WEST, true);
        helper.assertBlockProperty(west, TroughBlock.NORTH, false);

        helper.setBlock(east, Blocks.AIR);
        helper.assertBlockProperty(west, TroughBlock.EAST, false);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void troughsOfDifferentWoodsJoinToo(GameTestHelper helper) {
        BlockPos oak = new BlockPos(2, 1, 2);
        BlockPos cherry = oak.south();
        helper.setBlock(oak, ModBlocks.trough(OAK).get());
        helper.setBlock(cherry, ModBlocks.trough("cherry").get());
        helper.assertBlockProperty(oak, TroughBlock.SOUTH, true);
        helper.assertBlockProperty(cherry, TroughBlock.NORTH, true);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void everyWoodsTroughIsCraftedFromItsSlabs(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager();
        for (String wood : ModBlocks.WOODS) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("palworks", wood + "_trough");
            var recipe = recipes.byKey(id);
            helper.assertTrue(recipe.isPresent(), "a recipe for the " + wood + " trough");
            ItemStack made =
                    recipe.get().value().getResultItem(helper.getLevel().registryAccess());
            helper.assertTrue(made.is(ModBlocks.troughItem(wood).get()), "it makes the " + wood + " trough");
            Item slab = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(wood + "_slab"));
            helper.assertTrue(
                    recipe.get().value().getIngredients().stream().anyMatch(i -> i.test(new ItemStack(slab))),
                    "from " + wood + " slabs");
            var drops = net.minecraft.world.level.block.Block.getDrops(
                    ModBlocks.trough(wood).get().defaultBlockState(),
                    helper.getLevel(),
                    helper.absolutePos(TROUGH),
                    null);
            helper.assertTrue(
                    drops.size() == 1
                            && drops.get(0).is(ModBlocks.troughItem(wood).get()),
                    "the " + wood + " trough drops itself: " + drops);
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void joinedTroughsShareTheirFeed(GameTestHelper helper) {
        BlockPos west = new BlockPos(2, 1, 3);
        BlockPos east = west.east();
        TroughBlockEntity a = trough(helper, west);
        TroughBlockEntity b = trough(helper, east);
        helper.assertTrue(a.addFood(berries(10)) == 0, "all 10 berries went in");
        helper.assertTrue(a.feed() == 5 && b.feed() == 5, "shared evenly: " + a.feed() + " and " + b.feed());
        helper.assertBlockProperty(west, TroughBlock.FILL, 1);
        helper.assertBlockProperty(east, TroughBlock.FILL, 1);

        // A third placed on the end takes its share
        BlockPos third = east.east();
        TroughBlockEntity c = trough(helper, third);
        helper.succeedWhen(() -> {
            helper.assertTrue(c.feed() > 0, "the new trough has some");
            helper.assertTrue(
                    a.feed() + b.feed() + c.feed() == 10 && Math.abs(a.feed() - c.feed()) <= 1,
                    "still 10, shared: " + a.feed() + ", " + b.feed() + ", " + c.feed());
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void aTroughShowsHowFullItIs(GameTestHelper helper) {
        TroughBlockEntity trough = trough(helper, TROUGH);
        helper.assertBlockProperty(TROUGH, TroughBlock.FILL, 0); // empty: the bare wooden bottom

        trough.addFood(berries(1));
        helper.assertBlockProperty(TROUGH, TroughBlock.FILL, 1); // anything at all shows

        trough.addFood(berries(63));
        helper.assertBlockProperty(TROUGH, TroughBlock.FILL, TroughFeed.MAX_FILL); // full to the rim

        // As it's eaten it goes down, and back to bare when it's gone
        while (trough.takeBite()) {
            // eat it all
        }
        helper.assertBlockProperty(TROUGH, TroughBlock.FILL, 0);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aTroughOnlyTakesFood(GameTestHelper helper) {
        TroughBlockEntity trough = trough(helper, TROUGH);
        helper.assertTrue(trough.canPlaceItem(0, berries(1)), "a berry goes in");
        helper.assertFalse(trough.canPlaceItem(0, new ItemStack(Items.DIRT)), "dirt doesn't");
        helper.assertFalse(trough.canPlaceItem(0, new ItemStack(Items.BREAD)), "nor does bread: Cobblemon food only");

        // A hopper above it feeds it the berry, and keeps the dirt
        helper.setBlock(TROUGH.above(), Blocks.HOPPER);
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(TROUGH.above());
        hopper.setItem(0, new ItemStack(Items.DIRT));
        hopper.setItem(1, berries(1));
        helper.succeedWhen(() -> {
            helper.assertTrue(feedIn(trough) == 1, "the berry went in, as a point of feed");
            helper.assertTrue(trough.lastFood().is(oranBerry()), "the berry is what's shown being eaten");
            helper.assertTrue(hopper.getItem(0).is(Items.DIRT), "the dirt stayed in the hopper");
        });
    }

    @GameTest(template = Templates.EMPTY)
    public void foodADatapackValuesMoreIsWorthMoreFeed(GameTestHelper helper) {
        TroughBlockEntity trough = trough(helper, TROUGH);
        dev.palworks.hunger.TroughFood before = dev.palworks.hunger.TroughFood.current();
        try {
            dev.palworks.hunger.TroughFood.setCurrent(dev.palworks.hunger.TroughFood.fromFiles(
                    java.util.Map.of(
                            "test:food",
                            com.google.gson.JsonParser.parseString(
                                    "{\"values\": [{\"item\": \"cobblemon:oran_berry\", \"fullness\": 3}]}")),
                    message -> helper.fail(message)));
            trough.addFood(berries(2));
            helper.assertTrue(feedIn(trough) == 6, "2 berries worth 3 each: " + feedIn(trough));
        } finally {
            dev.palworks.hunger.TroughFood.setCurrent(before);
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aBrokenTroughDropsOnlyItself(GameTestHelper helper) {
        TroughBlockEntity trough = trough(helper, TROUGH);
        trough.addFood(berries(5));
        helper.getLevel().destroyBlock(helper.absolutePos(TROUGH), true);
        helper.succeedWhen(() -> {
            helper.assertTrue(
                    Scenes.itemsOnGround(helper, ModBlocks.troughItem(OAK).get()) == 1, "the trough drops");
            // Feed isn't items: its share is lost, as a composter's compost is
            helper.assertTrue(Scenes.itemsOnGround(helper, oranBerry()) == 0, "but no berries");
        });
    }

    @GameTest(template = Templates.EMPTY)
    @SuppressWarnings("removal") // makeMockPlayer: vanilla's only mock player in 1.21.1
    public void rightClickingPutsOneInAndShiftRightClickingTheStack(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 1, 3);
        TroughBlockEntity a = trough(helper, first);
        trough(helper, first.east()); // room for 2 x 64 along the line
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        // Right-click: one
        player.setItemInHand(hand, berries(64));
        helper.useBlock(first, player);
        helper.assertTrue(player.getMainHandItem().getCount() == 63, "one went in");
        helper.assertTrue(feedIn(a) == 1, "a point of feed: " + feedIn(a));

        // Shift-right-click: the rest of the stack, along the line (through the interaction event:
        // Minecraft doesn't ask the block while its player sneaks with something in hand)
        player.setShiftKeyDown(true);
        var result = TroughBlock.onShiftRightClick(player, hand, helper.absolutePos(first), Direction.UP);
        helper.assertTrue(result.interruptsFurtherEvaluation(), "it handled the click");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the whole stack went in");
        helper.assertTrue(feedIn(a) == 64, "64 in all: " + feedIn(a));

        // Not sneaking: left to the block's own right-click
        player.setShiftKeyDown(false);
        player.setItemInHand(hand, berries(10));
        helper.assertFalse(
                TroughBlock.onShiftRightClick(player, hand, helper.absolutePos(first), Direction.UP)
                        .interruptsFurtherEvaluation(),
                "a plain right-click isn't the event's");

        // Full: what doesn't fit stays in hand
        player.setShiftKeyDown(true);
        player.setItemInHand(hand, berries(64));
        TroughBlock.onShiftRightClick(player, hand, helper.absolutePos(first), Direction.UP);
        player.setItemInHand(hand, berries(64));
        TroughBlock.onShiftRightClick(player, hand, helper.absolutePos(first), Direction.UP);
        helper.assertTrue(feedIn(a) == 2 * TroughFeed.CAPACITY, "full: " + feedIn(a));
        helper.assertTrue(player.getMainHandItem().getCount() == 64, "no room, so none went in");

        // Not food: nothing goes in
        player.setShiftKeyDown(false);
        player.setItemInHand(hand, new ItemStack(Items.DIRT, 5));
        helper.useBlock(first, player);
        helper.assertTrue(player.getMainHandItem().getCount() == 5, "dirt stays in hand");
        helper.succeed();
    }

    // ---- Keeping troughs stocked ----

    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);

    private static int inChest(GameTestHelper helper, BlockPos chest, Item item) {
        var container = (net.minecraft.world.Container) helper.getBlockEntity(chest);
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_deliver", timeoutTicks = 800)
    public void aTransporterTakesFoodToATroughBeforeAChest(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos chest = new BlockPos(6, 1, 1);
        helper.setBlock(chest, Blocks.CHEST);
        TroughBlockEntity trough = trough(helper, TROUGH);
        PokemonEntity pidgey = Workers.sendOut(helper, "pidgey level=20", TRANSPORTER); // Flying: Transporting
        var inventory = dev.palworks.inventory.WorkerInventoryManager.getOrCreate(pidgey.getPokemon());
        inventory.setItem(0, new ItemStack(oranBerry(), 20));
        inventory.setItem(1, new ItemStack(Items.WHEAT, 10));

        helper.succeedWhen(() -> {
            helper.assertTrue(feedIn(trough) == 20, "the berries went in the trough: " + feedIn(trough));
            helper.assertTrue(inChest(helper, chest, Items.WHEAT) == 10, "the wheat went in the chest");
            helper.assertTrue(inChest(helper, chest, oranBerry()) == 0, "no berries in the chest");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_restock", timeoutTicks = 800)
    public void aTransporterRestocksATroughRunningLow(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos chest = new BlockPos(6, 1, 1);
        helper.setBlock(chest, Blocks.CHEST);
        ((net.minecraft.world.Container) helper.getBlockEntity(chest)).setItem(0, new ItemStack(oranBerry(), 64));
        TroughBlockEntity trough = trough(helper, TROUGH);
        trough.addFood(berries(3)); // running low
        Workers.sendOut(helper, "pidgey level=20", TRANSPORTER);

        // It fetches a stack, fills the trough, and takes what didn't fit back to the chest
        helper.succeedWhen(() -> {
            helper.assertTrue(feedIn(trough) == TroughFeed.CAPACITY, "filled up: " + feedIn(trough));
            helper.assertTrue(inChest(helper, chest, oranBerry()) == 3, "the 3 over back in the chest");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_no_restock", timeoutTicks = 400)
    public void aWellStockedTroughIsLeftAlone(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos chest = new BlockPos(6, 1, 1);
        helper.setBlock(chest, Blocks.CHEST);
        ((net.minecraft.world.Container) helper.getBlockEntity(chest)).setItem(0, new ItemStack(oranBerry(), 64));
        TroughBlockEntity trough = trough(helper, TROUGH);
        trough.addFood(berries(32));
        Workers.sendOut(helper, "pidgey level=20", TRANSPORTER);

        helper.runAfterDelay(300, () -> {
            helper.assertTrue(inChest(helper, chest, oranBerry()) == 64, "the chest's berries stay put");
            helper.assertTrue(feedIn(trough) == 32, "and the trough isn't topped up");
            helper.succeed();
        });
    }

    // ---- Eating ----

    @GameTest(template = Templates.EMPTY, batch = "hunger_eat", timeoutTicks = 800)
    public void anEmptyWorkerEatsFromATroughUntilItsHalfFull(GameTestHelper helper) {
        Workers.isolate(helper);
        TroughBlockEntity trough = trough(helper, TROUGH);
        trough.addFood(berries(9));
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        pokemon.setCurrentFullness(0);
        int cap = Hunger.troughCap(pokemon);
        helper.assertTrue(cap > 0 && cap < pokemon.getMaxFullness(), "troughs fill it partway: " + cap);
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);

        helper.startSequence()
                // It eats right up against the trough, square to one of its sides: its head over the food
                .thenWaitUntil(() -> helper.assertFalse(
                        EatingDisplay.eating(helper.getLevel(), state).isEmpty(), "it's eating"))
                .thenExecute(() -> assertEatsAgainst(helper, worker, TROUGH))
                .thenWaitUntil(() -> helper.assertTrue(
                        pokemon.getCurrentFullness() == cap,
                        "fullness " + pokemon.getCurrentFullness() + ", troughs fill to " + cap))
                // It stops there: the rest is left for hand-feeding
                .thenIdle(120)
                .thenExecute(() -> {
                    helper.assertTrue(
                            pokemon.getCurrentFullness() == cap, "still " + cap + ": " + pokemon.getCurrentFullness());
                    helper.assertTrue(
                            feedIn(trough) == 9 - cap, "a point of feed a point: " + feedIn(trough) + " left");
                })
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_home", timeoutTicks = 1200)
    public void aPasturedWorkerFarFromItsTroughFindsItByItsPasture(GameTestHelper helper) {
        Workers.isolate(helper);
        // A floor reaching well past the test, 36 blocks out from the trough, loaded all the way to
        // where it looks round itself (only the chunks round the tests are, in the test world)
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos trough = new BlockPos(2, 1, 2);
        trough(helper, trough).addFood(berries(9));
        // Out of reach of where it is (36 off, past the 32 it looks round itself), but its pasture
        // is right by the trough
        PokemonEntity worker = Workers.sendOutPastured(helper, WORKER, new BlockPos(38, 1, 2));
        Workers.tetherTo(helper, worker, new BlockPos(2, 1, 4));
        Pokemon pokemon = worker.getPokemon();
        pokemon.setCurrentFullness(0);
        Runnable unload = Scenes.keepLoaded(helper, new BlockPos(-40, 0, -40), new BlockPos(80, 0, 40));
        // How far off it was when it set off to eat: from out there, not once it had wandered back
        double[] setOffFrom = {-1};
        BlockPos troughAbs = helper.absolutePos(trough);
        helper.onEachTick(() -> {
            ReservationId job = WorkerStateManager.getOrCreate(pokemon).getCurrentReservation();
            if (setOffFrom[0] < 0 && job != null && job.getJobType() == JobType.EAT_FOOD) {
                setOffFrom[0] = Math.max(
                        Math.abs(worker.getX() - troughAbs.getX()), Math.abs(worker.getZ() - troughAbs.getZ()));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(pokemon.getCurrentFullness() > 0, "it found the trough and ate"))
                .thenExecute(() -> helper.assertTrue(
                        setOffFrom[0] > 32, "it set off for it from out past 32 blocks: " + setOffFrom[0]))
                .thenExecute(unload)
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_far_trip", timeoutTicks = 1200)
    public void aHungryWorkerGoesRoundAWallToItsTrough(GameTestHelper helper) {
        // The trough 28 blocks off, behind a wall further across than an ordinary search looks round
        Workers.isolate(helper);
        Runnable unload = Scenes.keepLoaded(helper, new BlockPos(-40, 0, -40), new BlockPos(60, 0, 50));
        for (int z = -6; z <= 26; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
            }
        }
        BlockPos trough = new BlockPos(30, 1, 10);
        trough(helper, trough).addFood(berries(9));
        PokemonEntity worker = Workers.sendOutPastured(helper, WORKER, new BlockPos(2, 1, 10));
        Pokemon pokemon = worker.getPokemon();
        pokemon.setCurrentFullness(0);
        // Which side of the wall it set off for the trough from: its own, not after wandering round
        double[] setOffAtX = {Double.NaN};
        double wallX = helper.absolutePos(new BlockPos(16, 1, 10)).getX();
        boolean startSideLow = worker.getX() < wallX;
        helper.onEachTick(() -> {
            ReservationId job = WorkerStateManager.getOrCreate(pokemon).getCurrentReservation();
            if (Double.isNaN(setOffAtX[0]) && job != null && job.getJobType() == JobType.EAT_FOOD) {
                setOffAtX[0] = worker.getX();
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(pokemon.getCurrentFullness() > 0, "it got round and ate"))
                .thenExecute(() -> helper.assertTrue(
                        (setOffAtX[0] < wallX) == startSideLow, "it set off from its own side of the wall"))
                .thenExecute(unload)
                .thenSucceed();
    }

    /**
     * A glass wall {@code radius} blocks round {@code center} (a ring, 3 high: too high to jump), so
     * nothing outside can walk in; with a glass roof over it all too if {@code roof}.
     */
    static void glassIn(GameTestHelper helper, BlockPos center, int radius, boolean roof) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                boolean wall = Math.max(Math.abs(x), Math.abs(z)) == radius;
                for (int y = 0; y <= 2; y++) {
                    if (wall) {
                        helper.setBlock(center.offset(x, y, z), Blocks.GLASS);
                    }
                }
                if (roof) {
                    helper.setBlock(center.offset(x, 3, z), Blocks.GLASS);
                }
            }
        }
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_head_home", timeoutTicks = 1200)
    public void aHungryWorkerThatCantGetToItsTroughHeadsHome(GameTestHelper helper) {
        // Its trough is by its pasture but glassed in: from out where it's working it heads home,
        // rather than carry on with the ripe wheat there
        Workers.isolate(helper);
        Runnable unload = Scenes.keepLoaded(helper, new BlockPos(-40, 0, -40), new BlockPos(80, 0, 40));
        BlockPos trough = new BlockPos(2, 1, 4);
        trough(helper, trough).addFood(berries(9));
        glassIn(helper, trough, 3, true); // a room round it: nowhere outside to eat from
        for (int x = 36; x <= 40; x++) {
            helper.setBlock(new BlockPos(x, 0, 8), Blocks.FARMLAND);
            helper.setBlock(new BlockPos(x, 1, 8), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        }
        PokemonEntity worker = Workers.sendOutPastured(helper, WORKER, new BlockPos(38, 1, 6));
        Workers.tetherTo(helper, worker, new BlockPos(4, 1, 6));
        worker.getPokemon().setCurrentFullness(0);
        BlockPos troughAbs = helper.absolutePos(trough);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        worker.blockPosition().closerThan(troughAbs, 10),
                        "it headed home to its trough: " + helper.relativePos(worker.blockPosition())))
                .thenExecute(unload)
                .thenSucceed();
    }

    /**
     * The worker's body is up against the trough (no more space between them than a lumberjack has
     * at its trunk) and in line with it, not off at a corner.
     */
    private static void assertEatsAgainst(GameTestHelper helper, PokemonEntity worker, BlockPos trough) {
        BlockPos abs = helper.absolutePos(trough);
        double half = worker.getBbWidth() / 2.0;
        double gapX =
                Math.max(0, Math.max(abs.getX() - (worker.getX() + half), (worker.getX() - half) - (abs.getX() + 1)));
        double gapZ =
                Math.max(0, Math.max(abs.getZ() - (worker.getZ() + half), (worker.getZ() - half) - (abs.getZ() + 1)));
        double gap = Math.max(gapX, gapZ);
        helper.assertTrue(
                WorkStance.AGAINST.reaches(half, gap), "it eats up against the trough, not " + gap + " blocks off");
        // Square to a side: its middle is within the trough's width along one axis
        boolean inLineX = worker.getX() > abs.getX() - 0.05 && worker.getX() < abs.getX() + 1.05;
        boolean inLineZ = worker.getZ() > abs.getZ() - 0.05 && worker.getZ() < abs.getZ() + 1.05;
        helper.assertTrue(inLineX || inLineZ, "it eats from a side, not a corner: at " + worker.position());
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_status", timeoutTicks = 200)
    public void anEmptyWorkerShowsItsHungerOverItsHead(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        String hungry = dev.palworks.worker.WorkerStatus.HUNGRY.aspect();
        helper.assertFalse(worker.getAspects().contains(hungry), "fed: nothing to show");

        pokemon.setCurrentFullness(0);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(worker.getAspects().contains(hungry), "empty: hungry"))
                .thenExecute(() -> pokemon.setCurrentFullness(pokemon.getMaxFullness()))
                .thenWaitUntil(() -> helper.assertFalse(worker.getAspects().contains(hungry), "fed again: gone"))
                // Switched off, its hunger is Cobblemon's: never shown
                .thenExecute(() -> {
                    pokemon.setCurrentFullness(0);
                    WorkerStateManager.setEnabled(pokemon, false);
                })
                .thenIdle(20)
                .thenExecute(() -> helper.assertFalse(worker.getAspects().contains(hungry), "work off: not shown"))
                .thenSucceed();
    }

    // ---- Draining ----

    /**
     * Run Cobblemon's metabolism for long enough that it takes a point off: once a second, as the
     * party does it, until a point's worth (its rate is in ticks, 20 a call).
     */
    private static void runCobblemonMetabolism(Pokemon pokemon) {
        for (int i = 0; i <= pokemon.getMetabolismRate() / 20; i++) {
            pokemon.tickMetabolism(20);
        }
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_metabolism")
    public void cobblemonsDrainIsPausedWhileItsWorkIsOn(GameTestHelper helper) {
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        int full = pokemon.getMaxFullness();

        runCobblemonMetabolism(pokemon);
        helper.assertTrue(pokemon.getCurrentFullness() == full, "its work is on: Cobblemon doesn't drain it");

        WorkerStateManager.setEnabled(pokemon, false);
        runCobblemonMetabolism(pokemon);
        helper.assertTrue(
                pokemon.getCurrentFullness() == full - 1,
                "its work is off: Cobblemon drains it again (" + pokemon.getCurrentFullness() + "/" + full + ")");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_work_units")
    public void workUsesUpFullness(GameTestHelper helper) {
        Workers.isolate(helper);
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        int full = pokemon.getMaxFullness();

        Hunger.didWork(pokemon, state, 20);
        helper.assertTrue(pokemon.getCurrentFullness() == full - 1, "20 crops' worth: a point");
        Hunger.didWork(pokemon, state, 10);
        helper.assertTrue(pokemon.getCurrentFullness() == full - 1, "half a point is owed, not taken yet");
        Hunger.didWork(pokemon, state, 10);
        helper.assertTrue(pokemon.getCurrentFullness() == full - 2, "and the next half makes it a point");

        // However big the tree, a quarter of a point at most
        Hunger.didWork(pokemon, state, HungerMath.treeUnits(400));
        helper.assertTrue(pokemon.getCurrentFullness() == full - 2, "a giant tree isn't a point");
        helper.assertTrue(Math.abs(state.getFullnessOwed() - 0.25) < 1e-9, "it's a quarter");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_switched_off", timeoutTicks = 400)
    public void aSwitchedOffWorkerIsLeftToCobblemon(GameTestHelper helper) {
        Workers.isolate(helper);
        TroughBlockEntity trough = trough(helper, TROUGH);
        trough.addFood(berries(9));
        Pokemon pokemon = Workers.sendOut(helper, WORKER, START).getPokemon();
        WorkerStateManager.setEnabled(pokemon, false);
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);

        // No Palworks drain for its work
        int full = pokemon.getMaxFullness();
        Hunger.didWork(pokemon, state, 100);
        helper.assertTrue(pokemon.getCurrentFullness() == full, "work off: its work doesn't drain it");

        // Empty, it isn't slowed, and doesn't go to the trough
        pokemon.setCurrentFullness(0);
        helper.assertFalse(Hunger.isEmpty(pokemon, state), "its hunger is Cobblemon's");
        helper.assertTrue(Hunger.workSpeed(pokemon, state) == 1.0, "not slowed");
        helper.runAfterDelay(300, () -> {
            helper.assertTrue(feedIn(trough) == 9, "it didn't eat: " + feedIn(trough) + " feed left");
            helper.assertTrue(pokemon.getCurrentFullness() == 0, "still empty");
            helper.succeed();
        });
    }

    /** How many ripe crops {@link #timeTheHarvest} has a worker harvest: as many as one pause of its pays for. */
    private static final int CROPS = 7;

    /**
     * How long a worker with this much fullness spends at the harvest of a row of ripe crops (their
     * steps, from when it starts on each until its job's done), against how long they take at full
     * speed. A slowed one pauses for the time its moves save it (see SlowedPause): an empty one's
     * first pause pays for {@link #CROPS} harvests, so they take about 4/3 as long.
     */
    private static void timeTheHarvest(GameTestHelper helper, boolean empty, double from, double to) {
        Workers.isolate(helper);
        List<BlockPos> crops = new ArrayList<>();
        for (int x = 1; x <= CROPS; x++) {
            BlockPos crop = new BlockPos(x, 1, 5);
            helper.setBlock(crop.below(), Blocks.FARMLAND);
            helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
            crops.add(crop);
        }
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        if (empty) {
            pokemon.setCurrentFullness(0);
        }
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        int[] working = {0};
        helper.onEachTick(() -> {
            ReservationId job = state.getCurrentReservation();
            if (job != null && job.getJobType() == JobType.HARVEST_CROPS && state.isWorking()) {
                working[0]++;
            }
        });
        int fullSpeed = CROPS * JobHandlerRegistry.jobTicks(JobType.HARVEST_CROPS, worker);
        helper.succeedWhen(() -> {
            for (BlockPos crop : crops) {
                helper.assertFalse(
                        helper.getBlockState(crop).is(Blocks.WHEAT)
                                && ((CropBlock) Blocks.WHEAT).isMaxAge(helper.getBlockState(crop)),
                        "the wheat at " + crop + " is harvested");
            }
            double times = (double) working[0] / fullSpeed;
            helper.assertTrue(
                    times >= from && times <= to,
                    "the harvests took " + working[0] + " ticks, " + times + "x their " + fullSpeed + " at full speed");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_pace_moving", timeoutTicks = 200)
    public void anEmptyWorkerMovesAtThreeQuartersSpeedWhoeverMovesIt(GameTestHelper helper) {
        // On its movement speed itself, which Cobblemon's wandering and following go by as well (the root
        // of its pace on foot, which covers ground at its pace: see Pace.attributeShare)
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        double full = worker.getAttributeValue(Attributes.MOVEMENT_SPEED);
        helper.startSequence()
                .thenExecute(() -> pokemon.setCurrentFullness(0))
                .thenWaitUntil(() -> helper.assertTrue(
                        Math.abs(worker.getAttributeValue(Attributes.MOVEMENT_SPEED) - full * Math.sqrt(0.75)) < 1e-6,
                        "empty: the root of three quarters (on foot) of " + full + ", not "
                                + worker.getAttributeValue(Attributes.MOVEMENT_SPEED)))
                .thenExecute(() -> pokemon.setCurrentFullness(pokemon.getMaxFullness()))
                .thenWaitUntil(() -> helper.assertTrue(
                        Math.abs(worker.getAttributeValue(Attributes.MOVEMENT_SPEED) - full) < 1e-6, "fed: full speed"))
                // Empty again, then its work switched off: its speed is its own again
                .thenExecute(() -> pokemon.setCurrentFullness(0))
                .thenWaitUntil(() ->
                        helper.assertTrue(worker.getAttributeValue(Attributes.MOVEMENT_SPEED) < full, "slowed again"))
                .thenExecute(() -> WorkerStateManager.setEnabled(pokemon, false))
                .thenWaitUntil(() -> helper.assertTrue(
                        Math.abs(worker.getAttributeValue(Attributes.MOVEMENT_SPEED) - full) < 1e-6,
                        "work off: full speed"))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_pace_to_eat", timeoutTicks = 400)
    public void anEmptyWorkerIsSlowedOnItsWayToEatToo(GameTestHelper helper) {
        Workers.isolate(helper);
        trough(helper, TROUGH).addFood(berries(9));
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        double full = worker.getAttributeValue(Attributes.MOVEMENT_SPEED);
        helper.startSequence()
                .thenExecute(() -> pokemon.setCurrentFullness(0))
                .thenWaitUntil(() -> {
                    ReservationId job = state.getCurrentReservation();
                    helper.assertTrue(job != null && job.getJobType() == JobType.EAT_FOOD, "it sets off to eat");
                })
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(
                        Math.abs(worker.getAttributeValue(Attributes.MOVEMENT_SPEED) - full * Math.sqrt(0.75)) < 1e-6,
                        "on its way: the root of three quarters (on foot) of " + full + ", not "
                                + worker.getAttributeValue(Attributes.MOVEMENT_SPEED)))
                .thenSucceed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_pace_empty", timeoutTicks = 1600)
    public void anEmptyWorkerHarvestsAtThreeQuartersSpeed(GameTestHelper helper) {
        // Its harvests take a third longer than a fed one's (below), the extra in a pause at them,
        // not a rest after
        timeTheHarvest(helper, true, 1.3, 1.7);
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_pace_fed", timeoutTicks = 1200)
    public void aFedWorkerHarvestsAtFullSpeed(GameTestHelper helper) {
        // A little over: crops side by side, it waits at the next for its last move to play out
        timeTheHarvest(helper, false, 0.95, 1.3);
    }

    // ---- Starting fullness ----

    @GameTest(template = Templates.EMPTY)
    public void aNewPokemonStartsAsFullAsTroughsFillIt(GameTestHelper helper) {
        var party = Cobblemon.INSTANCE.getStorage().getParty(Workers.owner(helper, START));
        Pokemon pokemon = PokemonProperties.Companion.parse("snorlax").create();
        helper.assertTrue(pokemon.getCurrentFullness() == 0, "Cobblemon starts it empty");

        party.add(pokemon);
        int start = Hunger.troughCap(pokemon);
        helper.assertTrue(start > 0 && start < pokemon.getMaxFullness(), "part full: " + start);
        helper.assertTrue(
                pokemon.getCurrentFullness() == start,
                "it starts at " + pokemon.getCurrentFullness() + "/" + pokemon.getMaxFullness() + ", not " + start);

        // Only the first time: put back in after emptying, it isn't topped up again
        pokemon.setCurrentFullness(0);
        party.remove(pokemon);
        party.add(pokemon);
        helper.assertTrue(pokemon.getCurrentFullness() == 0, "not topped up again: " + pokemon.getCurrentFullness());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aNewPokemonThatsFullerKeepsItsFullness(GameTestHelper helper) {
        Pokemon pokemon = PokemonProperties.Companion.parse("snorlax").create();
        pokemon.setCurrentFullness(pokemon.getMaxFullness());
        Cobblemon.INSTANCE.getStorage().getParty(Workers.owner(helper, START)).add(pokemon);
        helper.assertTrue(pokemon.getCurrentFullness() == pokemon.getMaxFullness(), "still full");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aFullPokemonsStartIsStillAChangeToSave(GameTestHelper helper) {
        // Full already, so getting its start changes nothing but the marker that it's had it.
        // Cobblemon only writes out a PC it's told changed: unsaved, the marker is gone after
        // a restart, and the Pokémon is topped up again when it next changes hands.
        Pokemon pokemon = PokemonProperties.Companion.parse("snorlax").create();
        pokemon.setCurrentFullness(pokemon.getMaxFullness());
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(Workers.owner(helper, START));
        pc.add(pokemon);
        pokemon.getPersistentData().remove(StartingFullness.MARKER); // as before it was got
        AtomicInteger changes = new AtomicInteger();
        pc.getAnyChangeObservable().subscribe(unit -> changes.incrementAndGet());

        StartingFullness.onGained(pokemon);
        helper.assertTrue(changes.get() == 1, "marking that it's had its start is a change: " + changes.get());
        StartingFullness.onGained(pokemon);
        helper.assertTrue(changes.get() == 1, "got again, already marked, isn't: " + changes.get());
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_restock_unreachable", timeoutTicks = 500)
    public void aTransporterDoesntFetchFoodForATroughItCantGetTo(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos chest = new BlockPos(6, 1, 1);
        helper.setBlock(chest, Blocks.CHEST);
        ((net.minecraft.world.Container) helper.getBlockEntity(chest)).setItem(0, new ItemStack(oranBerry(), 64));
        // Running low, up on a pillar: nowhere to stand by it (Doduo transports, but can't fly)
        BlockPos high = new BlockPos(5, 5, 5);
        for (int y = 1; y < high.getY(); y++) {
            helper.setBlock(new BlockPos(high.getX(), y, high.getZ()), Blocks.STONE);
        }
        trough(helper, high).addFood(berries(3));
        PokemonEntity doduo = Workers.sendOut(helper, "doduo level=20", TRANSPORTER);
        boolean[] fetched = {false};
        helper.onEachTick(() -> {
            if (!WorkerInventoryManager.getOrCreate(doduo.getPokemon()).isEmpty()) {
                fetched[0] = true;
            }
        });

        helper.runAfterDelay(400, () -> {
            helper.assertFalse(fetched[0], "it fetched food it had nowhere to take");
            helper.assertTrue(inChest(helper, chest, oranBerry()) == 64, "the chest keeps its berries");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "hunger_eat_carpet", timeoutTicks = 800)
    public void anEmptyWorkerEatsFromATroughOnACarpetedFloor(GameTestHelper helper) {
        Workers.isolate(helper);
        // Carpet all round the trough: it stands on the carpet, in the carpet's own block, not the
        // one above it
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 1, z, Blocks.WHITE_CARPET);
            }
        }
        TroughBlockEntity trough = trough(helper, TROUGH);
        trough.addFood(berries(9));
        PokemonEntity worker = Workers.sendOut(helper, WORKER, START);
        Pokemon pokemon = worker.getPokemon();
        pokemon.setCurrentFullness(0);

        helper.succeedWhen(() -> helper.assertTrue(pokemon.getCurrentFullness() > 0, "it's eaten"));
    }
}
