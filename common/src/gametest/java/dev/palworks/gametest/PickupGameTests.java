package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * How transporters pick items up: what fits is taken and the rest left on the ground, they fill
 * up before delivering, and once every slot is used they only take items matching what they carry.
 */
public class PickupGameTests {
    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);
    private static final String PIDGEY = "pidgey level=20"; // Flying: Transporting

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
}
