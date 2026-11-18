package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityOverrides;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

/**
 * How transporters pick items up: what fits is taken and the rest left on the ground, they fill
 * up before delivering, and once every slot is used they only take items matching what they carry.
 */
public class PickupGameTests {
    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);
    private static final String PIDGEY = "pidgey level=20"; // Flying: Transporting 1, so 1 slot
    private static final String PIDGEY_ID = "cobblemon:pidgey";

    /** Give every Pidgey this Transporting level (one slot a level), for a batch: undone after it. */
    static void pidgeysAtTransporting(int level) throws IOException {
        SuitabilityOverrides.set(PIDGEY_ID, Suitability.TRANSPORTING, level);
    }

    // Tests of what a transporter does with several kinds of item carry them at level 3: 3 slots

    @BeforeBatch(batch = "pickup_fill_up")
    public void beforeFillUp(ServerLevel level) throws IOException {
        pidgeysAtTransporting(3);
    }

    @AfterBatch(batch = "pickup_fill_up")
    public void afterFillUp(ServerLevel level) throws IOException {
        SuitabilityOverrides.resetAll();
    }

    @BeforeBatch(batch = "pickup_close")
    public void beforeClose(ServerLevel level) throws IOException {
        pidgeysAtTransporting(3);
    }

    @AfterBatch(batch = "pickup_close")
    public void afterClose(ServerLevel level) throws IOException {
        SuitabilityOverrides.resetAll();
    }

    @BeforeBatch(batch = "pickup_level_five")
    public void beforeLevelFive(ServerLevel level) throws IOException {
        pidgeysAtTransporting(5);
    }

    @AfterBatch(batch = "pickup_level_five")
    public void afterLevelFive(ServerLevel level) throws IOException {
        SuitabilityOverrides.resetAll();
    }

    /** Drop {@code count} of an item at a spot (not moving), for transporters to pick up. */
    private static void dropForPickup(GameTestHelper helper, BlockPos rel, Item item, int count) {
        Scenes.dropToPickUp(helper, rel, new ItemStack(item, count));
    }

    /** How many of an item are lying on the ground round a spot. */
    private static int onGround(GameTestHelper helper, BlockPos rel, Item item) {
        int count = 0;
        for (ItemEntity entity : helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(rel)).inflate(1.5))) {
            if (entity.isAlive() && entity.getItem().is(item)) {
                count += entity.getItem().getCount();
            }
        }
        return count;
    }

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

    private static int inChest(GameTestHelper helper, BlockPos rel, Item item) {
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                count += chest.getItem(i).getCount();
            }
        }
        return count;
    }

    /** Fill the transporter's three slots. */
    private static void carrying(PokemonEntity worker, ItemStack... stacks) {
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(worker.getPokemon());
        for (int i = 0; i < stacks.length; i++) {
            inventory.setItem(i, stacks[i]);
        }
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_partial", timeoutTicks = 400)
    public void aPickupThatOnlyPartlyFitsLeavesTheRestOnTheGround(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER); // Flying: Transporting
        // Every slot used; room for 4 more wheat only
        carrying(pidgey, new ItemStack(Items.WHEAT, 60), new ItemStack(Items.DIRT, 64), new ItemStack(Items.STONE, 64));
        BlockPos spot = new BlockPos(5, 1, 5);
        dropForPickup(helper, spot, Items.WHEAT, 10);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 64, "wheat carried");
            helper.assertValueEqual(onGround(helper, spot, Items.WHEAT), 6, "wheat left on the ground");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_fill_up", timeoutTicks = 1200)
    public void aTransporterFillsUpBeforeDelivering(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos chestPos = new BlockPos(3, 1, 3);
        helper.setBlock(chestPos, Blocks.CHEST);
        Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        // Three spots, a different item at each: one per slot
        dropForPickup(helper, new BlockPos(1, 1, 6), Items.WHEAT, 5);
        dropForPickup(helper, new BlockPos(6, 1, 6), Items.CARROT, 5);
        dropForPickup(helper, new BlockPos(6, 1, 1), Items.POTATO, 5);

        // What's in the chest the first time anything is: all three, if it made one trip
        Set<Item> firstDelivery = new HashSet<>();
        helper.onEachTick(() -> {
            if (!firstDelivery.isEmpty()) {
                return;
            }
            for (Item item : new Item[] {Items.WHEAT, Items.CARROT, Items.POTATO}) {
                if (inChest(helper, chestPos, item) > 0) {
                    firstDelivery.add(item);
                }
            }
        });

        helper.succeedWhen(() -> {
            helper.assertValueEqual(inChest(helper, chestPos, Items.WHEAT), 5, "wheat in the chest");
            helper.assertValueEqual(inChest(helper, chestPos, Items.CARROT), 5, "carrots in the chest");
            helper.assertValueEqual(inChest(helper, chestPos, Items.POTATO), 5, "potatoes in the chest");
            helper.assertValueEqual(
                    firstDelivery.size(),
                    3,
                    "kinds of item in its first delivery (it should pick all three up before going to the chest)");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_matching", timeoutTicks = 600)
    public void aFullTransporterOnlyTakesPickupsMatchingWhatItCarries(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        // Every slot used, wheat with room
        carrying(pidgey, new ItemStack(Items.WHEAT, 10), new ItemStack(Items.DIRT, 64), new ItemStack(Items.STONE, 64));
        BlockPos nearCarrots = new BlockPos(1, 1, 5);
        BlockPos farWheat = new BlockPos(6, 1, 6);
        dropForPickup(helper, nearCarrots, Items.CARROT, 5);
        dropForPickup(helper, farWheat, Items.WHEAT, 5);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 15, "wheat carried (the farther, matching pickup)");
            helper.assertValueEqual(onGround(helper, farWheat, Items.WHEAT), 0, "wheat left on the ground");
            helper.assertValueEqual(carried(pidgey, Items.CARROT), 0, "carrots carried (no slot for them)");
            helper.assertValueEqual(onGround(helper, nearCarrots, Items.CARROT), 5, "carrots left on the ground");
        });
    }

    // ---- Which items are picked up: workers' and players' drops, nothing else (TrackedDrops) ----

    @GameTest(template = Templates.EMPTY, batch = "pickup_not_farms", timeoutTicks = 400)
    public void aTransporterLeavesAFarmsDropsAlone(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        // A farm's (or a mob's) wheat right by it, a worker's carrots further off
        BlockPos farmed = new BlockPos(2, 1, 2);
        BlockPos dropped = new BlockPos(6, 1, 6);
        Scenes.dropNotToPickUp(helper, farmed, new ItemStack(Items.WHEAT, 5));
        dropForPickup(helper, dropped, Items.CARROT, 5);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.CARROT), 5, "carrots carried (a worker dropped them)");
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 0, "wheat carried (a farm's)");
            helper.assertValueEqual(onGround(helper, farmed, Items.WHEAT), 5, "the farm's wheat left on the ground");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_thrown", timeoutTicks = 400)
    public void aTransporterPicksUpWhatAPlayerThrows(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        ServerPlayer player = Workers.owner(helper, new BlockPos(4, 1, 2));
        // As Q does: thrown ahead, with the player as its thrower
        player.drop(new ItemStack(Items.WHEAT, 5), false, true);

        helper.succeedWhen(() -> helper.assertValueEqual(carried(pidgey, Items.WHEAT), 5, "wheat carried"));
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_mined", timeoutTicks = 400)
    public void aTransporterPicksUpWhatAPlayerMines(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        ServerPlayer player = Workers.owner(helper, new BlockPos(4, 1, 2));
        BlockPos block = new BlockPos(5, 1, 5);
        helper.setBlock(block, Blocks.DIRT);
        // As a player in survival breaking it (the test server puts players in creative, where
        // nothing drops)
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.gameMode.destroyBlock(helper.absolutePos(block));
        helper.assertBlockNotPresent(Blocks.DIRT, block);

        helper.succeedWhen(() -> helper.assertValueEqual(carried(pidgey, Items.DIRT), 1, "dirt carried"));
    }

    @GameTest(template = Templates.EMPTY)
    public void aBreakThatFailsStopsMarkingDrops(GameTestHelper helper) {
        ServerPlayer player = Workers.owner(helper, new BlockPos(4, 1, 2));
        // A break that never returns: here it throws (there's nowhere to break), as a mod's break
        // handler might, or one that ends the break its own way
        boolean threw = false;
        try {
            player.gameMode.destroyBlock(null);
        } catch (RuntimeException e) { // NOPMD - whatever it throws, the break never returned
            threw = true;
        }
        helper.assertTrue(threw, "the break should have thrown");

        // What spawns afterwards isn't the player's: nothing is being broken any more
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        ItemEntity item = new ItemEntity(
                helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(Items.WHEAT));
        helper.getLevel().addFreshEntity(item);
        helper.assertFalse(TrackedDrops.isTracked(item), "an item that spawns after a failed break isn't to pick up");
        item.discard();
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_death", timeoutTicks = 400)
    public void aTransporterLeavesAPlayersDeathDropsAlone(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        ServerPlayer player = Workers.owner(helper, new BlockPos(4, 1, 4));
        // As dying does: scattered round them, with no thrower. And something thrown, to see the
        // transporter was there to pick things up
        player.drop(new ItemStack(Items.DIAMOND, 3), true, false);
        player.drop(new ItemStack(Items.WHEAT, 5), false, true);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT), 5, "thrown wheat carried");
            helper.assertValueEqual(carried(pidgey, Items.DIAMOND), 0, "death-dropped diamonds carried");
            helper.assertValueEqual(Scenes.itemsOnGround(helper, Items.DIAMOND), 3, "diamonds left where they fell");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_reloaded", timeoutTicks = 400)
    public void anItemToPickUpComingBackIntoTheWorldGetsAPickupJob(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        // A worker's wheat saved with its chunk and loaded back in (or after a restart): the job
        // it had then is long gone, but it's still marked as one to pick up
        BlockPos abs = helper.absolutePos(new BlockPos(6, 1, 6));
        ItemEntity saved = new ItemEntity(
                helper.getLevel(),
                abs.getX() + 0.5,
                abs.getY(),
                abs.getZ() + 0.5,
                new ItemStack(Items.WHEAT, 5),
                0,
                0,
                0);
        saved.addTag(dev.palworks.drops.TrackedDrops.TAG);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        saved.save(tag);
        net.minecraft.world.entity.Entity loaded =
                net.minecraft.world.entity.EntityType.loadEntityRecursive(tag, helper.getLevel(), entity -> entity);
        helper.assertTrue(loaded != null, "the item loads");
        // Into the level as it was saved (marked, its UUID kept), with no worker or player dropping
        // it now: as an item comes back with its chunk
        helper.getLevel().addWithUUID(loaded);

        helper.succeedWhen(() -> helper.assertValueEqual(carried(pidgey, Items.WHEAT), 5, "wheat carried"));
    }

    /**
     * How far each item was from the transporter (its middle, on the furthest axis) when it went: the most, by
     * the end. Items it picks up go from within a block of its body, as a player picks them up.
     */
    private static double[] farthestPickupOnEachTick(GameTestHelper helper, PokemonEntity worker, ItemEntity... items) {
        double[] farthest = {0};
        Set<ItemEntity> gone = new HashSet<>();
        helper.onEachTick(() -> {
            for (ItemEntity item : items) {
                if (!item.isAlive() && gone.add(item)) {
                    double dx = item.getX() - worker.getX();
                    double dz = item.getZ() - worker.getZ();
                    // Its reach is a box round its body (as a player's), so on each axis
                    farthest[0] = Math.max(farthest[0], Math.max(Math.abs(dx), Math.abs(dz)));
                }
            }
        });
        return farthest;
    }

    /** From the middle of a worker, how far off an item it picks up can be, across: a block past its body. */
    private static double reachOf(PokemonEntity worker) {
        return worker.getBbWidth() / 2.0 + 1.0 + 0.25; // + 0.25: half an item's width
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_close", timeoutTicks = 600)
    public void aTransporterGoesRightUpToEachItemItPicksUp(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        // Two items 3 blocks apart, the second in reach of the first's pickup spot, as a harvest's
        // can be spread: it goes up to one, then on to the other
        ItemEntity wheat = Scenes.dropToPickUp(helper, new BlockPos(3, 1, 6), new ItemStack(Items.WHEAT, 4));
        ItemEntity carrots = Scenes.dropToPickUp(helper, new BlockPos(6, 1, 6), new ItemStack(Items.CARROT, 4));
        double[] farthest = farthestPickupOnEachTick(helper, pidgey, wheat, carrots);

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(pidgey, Items.WHEAT) + carried(pidgey, Items.CARROT), 8, "items carried");
            helper.assertTrue(
                    farthest[0] <= reachOf(pidgey),
                    "picked up from " + farthest[0] + " blocks away, more than " + reachOf(pidgey));
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_out_of_reach", timeoutTicks = 600)
    public void anItemOutOfReachDoesntHoldUpTheOnesBesideIt(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // Normal/Flying, so it transports, but it can't fly (one that can flies up: FlightGameTests)
        PokemonEntity doduo = Workers.sendOut(helper, "doduo level=20", TRANSPORTER);
        // Wheat on top of a pillar it can't climb, the nearer of the two, and carrots on the ground
        // beside it: the carrots are picked up, and the wheat waits
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(3, y, 3), Blocks.STONE);
        }
        Scenes.dropToPickUp(helper, new BlockPos(3, 4, 3), new ItemStack(Items.WHEAT, 4));
        Scenes.dropToPickUp(helper, new BlockPos(5, 1, 3), new ItemStack(Items.CARROT, 4));

        helper.succeedWhen(() -> {
            helper.assertValueEqual(carried(doduo, Items.CARROT), 4, "carrots carried");
            helper.assertValueEqual(carried(doduo, Items.WHEAT), 0, "wheat off the pillar");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "deliver_close", timeoutTicks = 600)
    public void aTransporterDeliversRightUpAgainstTheChest(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos chestPos = new BlockPos(5, 1, 5);
        helper.setBlock(chestPos, Blocks.CHEST);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        carrying(pidgey, new ItemStack(Items.WHEAT, 8));
        // Where it is when the wheat goes in: on the tile in front of the chest (less than a block
        // from it), not a tile or more further back
        double[] gapWhenDelivered = {-1};
        helper.onEachTick(() -> {
            if (gapWhenDelivered[0] < 0 && inChest(helper, chestPos, Items.WHEAT) > 0) {
                BlockPos chest = helper.absolutePos(chestPos);
                double dx = Math.abs(pidgey.getX() - (chest.getX() + 0.5)) - 0.5 - pidgey.getBbWidth() / 2.0;
                double dz = Math.abs(pidgey.getZ() - (chest.getZ() + 0.5)) - 0.5 - pidgey.getBbWidth() / 2.0;
                gapWhenDelivered[0] = Math.max(0, Math.max(dx, dz));
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(gapWhenDelivered[0] >= 0, "the wheat was delivered");
            helper.assertTrue(
                    gapWhenDelivered[0] < 1.0, "delivered from " + gapWhenDelivered[0] + " blocks from the chest");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "deliver_lid", timeoutTicks = 600)
    public void aChestIsOpenWhileATransporterDeliversToItAndClosesAfter(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // A double chest: both halves open, as they do for a player (only one of them makes the sound)
        BlockPos left = new BlockPos(5, 1, 5);
        BlockPos right = left.east();
        helper.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT));
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        carrying(pidgey, new ItemStack(Items.WHEAT, 8));
        helper.assertValueEqual(openCount(helper, left) + openCount(helper, right), 0, "open before it gets there");
        int[] openWhenDelivered = {-1, -1};
        helper.onEachTick(() -> {
            if (openWhenDelivered[0] < 0
                    && inChest(helper, left, Items.WHEAT) + inChest(helper, right, Items.WHEAT) > 0) {
                openWhenDelivered[0] = openCount(helper, left);
                openWhenDelivered[1] = openCount(helper, right);
            }
        });

        helper.succeedWhen(() -> {
            helper.assertValueEqual(openWhenDelivered[0], 1, "left half's openers as the wheat went in");
            helper.assertValueEqual(openWhenDelivered[1], 1, "right half's openers as the wheat went in");
            helper.assertValueEqual(openCount(helper, left) + openCount(helper, right), 0, "open afterwards");
        });
    }

    /** How many have the chest open (what lifts its lid, and a trapped chest's signal). */
    private static int openCount(GameTestHelper helper, BlockPos rel) {
        return ChestBlockEntity.getOpenCount(helper.getLevel(), helper.absolutePos(rel));
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_another_can_reach", timeoutTicks = 400)
    public void aPickupOneTransporterCantReachIsLeftForOneThatCan(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        // Wheat in a walled pen (too high to jump) with a transporter that's busy for now (as in a
        // battle); another outside it, which is asked first, and can't get in
        for (int i = 1; i <= 7; i++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(i, y, 1, Blocks.STONE);
                helper.setBlock(i, y, 7, Blocks.STONE);
                helper.setBlock(1, y, i, Blocks.STONE);
                helper.setBlock(7, y, i, Blocks.STONE);
            }
        }
        PokemonEntity inside = Workers.sendOut(helper, "doduo level=20", new BlockPos(3, 1, 3));
        inside.getBusyLocks().add("palworks-test");
        Workers.sendOut(helper, "doduo level=20", new BlockPos(-3, 1, 4));
        Scenes.dropToPickUp(helper, new BlockPos(5, 1, 5), new ItemStack(Items.WHEAT, 4));

        helper.runAfterDelay(100, () -> inside.getBusyLocks().remove("palworks-test"));
        helper.succeedWhen(() -> helper.assertValueEqual(
                carried(inside, Items.WHEAT), 4, "wheat carried by the one that can get to it"));
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_level_one", timeoutTicks = 1200)
    public void aLevelOneTransporterDeliversEachKindBeforeTakingTheNext(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos chestPos = new BlockPos(3, 1, 3);
        helper.setBlock(chestPos, Blocks.CHEST);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        helper.assertValueEqual(
                WorkerInventoryManager.getOrCreate(pidgey.getPokemon()).getCapacity(), 1, "a Pidgey's slots");
        dropForPickup(helper, new BlockPos(1, 1, 6), Items.WHEAT, 5);
        dropForPickup(helper, new BlockPos(6, 1, 6), Items.CARROT, 5);

        // It never carries both: one slot
        helper.onEachTick(() -> helper.assertFalse(
                carried(pidgey, Items.WHEAT) > 0 && carried(pidgey, Items.CARROT) > 0, "carrying two kinds at once"));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(inChest(helper, chestPos, Items.WHEAT), 5, "wheat in the chest");
            helper.assertValueEqual(inChest(helper, chestPos, Items.CARROT), 5, "carrots in the chest");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_level_five", timeoutTicks = 1200)
    public void aLevelFiveTransporterCarriesFiveKinds(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, PIDGEY, TRANSPORTER);
        // No chest: what it picks up stays with it
        Item[] kinds = {Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.APPLE};
        BlockPos[] spots = {
            new BlockPos(1, 1, 6),
            new BlockPos(3, 1, 6),
            new BlockPos(5, 1, 6),
            new BlockPos(6, 1, 4),
            new BlockPos(6, 1, 2)
        };
        for (int i = 0; i < kinds.length; i++) {
            dropForPickup(helper, spots[i], kinds[i], 5);
        }

        helper.succeedWhen(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(carried(pidgey, kind), 5, "carried: " + kind);
            }
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_spare_seeds", timeoutTicks = 600)
    public void aTransporterThatPlantsTakesSeedsItHasNowhereToPlantToAChest(GameTestHelper helper) {
        // Hoppip: Grass/Flying, Planting and Transporting 1. Seeds kept to plant would fill its one
        // slot for good; with no farmland round it, they go in the chest
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos chestPos = new BlockPos(3, 1, 3);
        helper.setBlock(chestPos, Blocks.CHEST);
        PokemonEntity hoppip = Workers.sendOut(helper, "hoppip level=20", TRANSPORTER);
        carrying(hoppip, new ItemStack(Items.WHEAT_SEEDS, 12));

        helper.succeedWhen(
                () -> helper.assertValueEqual(inChest(helper, chestPos, Items.WHEAT_SEEDS), 12, "seeds in the chest"));
    }
}
