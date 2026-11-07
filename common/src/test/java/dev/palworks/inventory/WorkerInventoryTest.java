package dev.palworks.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each kind of worker can carry, with real items (see CarryMode). */
class WorkerInventoryTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static WorkerInventory inventory(CarryMode mode) {
        WorkerInventory inventory = new WorkerInventory(UUID.randomUUID());
        inventory.setMode(mode);
        return inventory;
    }

    @Test
    void fullInventoryTakesAnythingInAllThreeSlots() {
        WorkerInventory inventory = inventory(CarryMode.FULL);

        assertEquals(0, inventory.addItem(new ItemStack(Items.WHEAT, 64)));
        assertEquals(0, inventory.addItem(new ItemStack(Items.OAK_LOG, 64)));
        assertEquals(0, inventory.addItem(new ItemStack(Items.WHEAT_SEEDS, 64)));
        assertFalse(inventory.hasSpace());
        assertEquals(10, inventory.addItem(new ItemStack(Items.COBBLESTONE, 10)), "a 4th kind doesn't fit");
    }

    @Test
    void fullInventoryMergesIntoMatchingStacksFirst() {
        WorkerInventory inventory = inventory(CarryMode.FULL);
        inventory.addItem(new ItemStack(Items.WHEAT, 40));

        assertEquals(0, inventory.addItem(new ItemStack(Items.WHEAT, 40)));
        assertEquals(80, inventory.getTotalItemCount());
        assertEquals(64, inventory.getItem(0).getCount(), "the first stack is filled before a new one starts");
    }

    @Test
    void seedPouchTakesSeedsInItsOneSlot() {
        WorkerInventory inventory = inventory(CarryMode.SEED_POUCH);

        assertEquals(0, inventory.addItem(new ItemStack(Items.WHEAT_SEEDS, 32)));
        assertEquals(32, inventory.getItem(CarryMode.POUCH_SLOT).getCount(), "seeds go in the pouch slot");
        assertTrue(inventory.getItem(0).isEmpty());
        assertTrue(inventory.getItem(2).isEmpty());
    }

    @Test
    void seedPouchHoldsOnlyOneStack() {
        WorkerInventory inventory = inventory(CarryMode.SEED_POUCH);

        assertEquals(6, inventory.addItem(new ItemStack(Items.WHEAT_SEEDS, 70)), "64 fit, 6 are left over");
        assertFalse(inventory.hasSpace());
        assertEquals(5, inventory.addItem(new ItemStack(Items.CARROT, 5)), "a second kind of seed doesn't fit");
    }

    @Test
    void seedPouchRefusesThingsThatArentPlantable() {
        WorkerInventory inventory = inventory(CarryMode.SEED_POUCH);

        assertEquals(10, inventory.addItem(new ItemStack(Items.WHEAT, 10)), "wheat isn't a seed");
        assertEquals(1, inventory.addItem(new ItemStack(Items.OAK_LOG, 1)));
        assertTrue(inventory.isEmpty());
        assertFalse(inventory.canPlaceItem(CarryMode.POUCH_SLOT, new ItemStack(Items.WHEAT)));
        assertTrue(inventory.canPlaceItem(CarryMode.POUCH_SLOT, new ItemStack(Items.BEETROOT_SEEDS)));
        assertFalse(inventory.canPlaceItem(0, new ItemStack(Items.BEETROOT_SEEDS)), "only the pouch slot is used");
    }

    @Test
    void everyVanillaCropSeedFitsTheSeedPouch() {
        for (var seed : new net.minecraft.world.item.Item[] {Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS,
                Items.CARROT, Items.POTATO, Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.NETHER_WART}) {
            assertTrue(WorkerInventory.accepts(CarryMode.SEED_POUCH, new ItemStack(seed)), seed + " should fit");
        }
    }

    @Test
    void workersThatCarryNothingTakeNothing() {
        WorkerInventory inventory = inventory(CarryMode.NONE);

        assertEquals(5, inventory.addItem(new ItemStack(Items.WHEAT_SEEDS, 5)));
        assertFalse(inventory.hasSpace());
        assertTrue(inventory.isEmpty());
    }

    @Test
    void hasAnySeedSpotsSeedsAmongOtherItems() {
        WorkerInventory inventory = inventory(CarryMode.FULL);
        inventory.addItem(new ItemStack(Items.WHEAT, 3));
        assertFalse(inventory.hasAnySeed());

        inventory.addItem(new ItemStack(Items.POTATO, 1));
        assertTrue(inventory.hasAnySeed(), "a potato plants potatoes");
    }

    @Test
    void addItemDoesNotChangeTheStackPassedIn() {
        WorkerInventory inventory = inventory(CarryMode.FULL);
        ItemStack stack = new ItemStack(Items.WHEAT, 10);

        inventory.addItem(stack);

        assertEquals(10, stack.getCount(), "callers decide what to do with the leftover count");
    }
}
