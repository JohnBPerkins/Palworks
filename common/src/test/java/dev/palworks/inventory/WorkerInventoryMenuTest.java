package dev.palworks.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Where the inventory screen puts a worker's slots ({@link WorkerInventoryMenu#slotX}). */
class WorkerInventoryMenuTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theSlotsAWorkerUsesAreCentred() {
        // Three, as every transporter had before levels: where they always were
        assertEquals(62, WorkerInventoryMenu.slotX(0, CarryMode.FULL, 3));
        assertEquals(80, WorkerInventoryMenu.slotX(1, CarryMode.FULL, 3));
        assertEquals(98, WorkerInventoryMenu.slotX(2, CarryMode.FULL, 3));
        // One in the middle; five on the hopper's own row
        assertEquals(80, WorkerInventoryMenu.slotX(0, CarryMode.FULL, 1));
        assertEquals(44, WorkerInventoryMenu.slotX(0, CarryMode.FULL, 5));
        assertEquals(116, WorkerInventoryMenu.slotX(4, CarryMode.FULL, 5));
        // A seed pouch's one slot is in the middle whatever its capacity
        assertEquals(80, WorkerInventoryMenu.slotX(CarryMode.POUCH_SLOT, CarryMode.SEED_POUCH, 1));
        assertEquals(80, WorkerInventoryMenu.slotX(CarryMode.POUCH_SLOT, CarryMode.SEED_POUCH, 5));
    }
}
