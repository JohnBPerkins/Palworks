package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.manager.JobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;

/**
 * How transporters pick items up: what fits is taken and the rest left on the ground, they fill
 * up before delivering, and once every slot is used they only take items matching what they carry.
 */
public class PickupGameTests {
    private static final BlockPos TRANSPORTER = new BlockPos(1, 1, 1);

    /** Drop {@code count} of an item at a spot (not moving) and give it a pickup job. */
    private static void dropForPickup(GameTestHelper helper, BlockPos rel, Item item, int count) {
        BlockPos abs = helper.absolutePos(rel);
        ItemEntity entity = new ItemEntity(helper.getLevel(), abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5,
                new ItemStack(item, count), 0, 0, 0);
        helper.getLevel().addFreshEntity(entity);
        JobManager.getInstance(helper.getLevel()).queuePickup(abs, null, helper.getLevel().getGameTime());
    }

    /** How many of an item are lying on the ground round a spot. */
    private static int onGround(GameTestHelper helper, BlockPos rel, Item item) {
        int count = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(rel)).inflate(1.5))) {
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
        PokemonEntity pidgey = Workers.sendOut(helper, "pidgey level=20", TRANSPORTER); // Flying: Transporting
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
        Workers.sendOut(helper, "pidgey level=20", TRANSPORTER);
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
            helper.assertValueEqual(firstDelivery.size(), 3,
                    "kinds of item in its first delivery (it should pick all three up before going to the chest)");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pickup_matching", timeoutTicks = 600)
    public void aFullTransporterOnlyTakesPickupsMatchingWhatItCarries(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity pidgey = Workers.sendOut(helper, "pidgey level=20", TRANSPORTER);
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
}
