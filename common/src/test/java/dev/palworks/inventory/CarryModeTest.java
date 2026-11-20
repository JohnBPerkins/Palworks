package dev.palworks.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.palworks.suitability.Suitability;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CarryModeTest {

    @Test
    void transportingGetsTheFullInventory() {
        assertEquals(CarryMode.FULL, CarryMode.forSuitabilities(EnumSet.of(Suitability.TRANSPORTING)));
        // Transporting wins even when the Pokémon can also plant (e.g. Tropius)
        assertEquals(
                CarryMode.FULL,
                CarryMode.forSuitabilities(
                        EnumSet.of(Suitability.TRANSPORTING, Suitability.PLANTING, Suitability.GATHERING)));
    }

    @Test
    void plantersGetASeedPouch() {
        assertEquals(
                CarryMode.SEED_POUCH,
                CarryMode.forSuitabilities(EnumSet.of(Suitability.PLANTING, Suitability.GATHERING)));
        // Nether (Ghost types) plants nether wart
        assertEquals(CarryMode.SEED_POUCH, CarryMode.forSuitabilities(EnumSet.of(Suitability.NETHER)));
    }

    @Test
    void everyoneElseCarriesNothing() {
        assertEquals(
                CarryMode.NONE,
                CarryMode.forSuitabilities(
                        EnumSet.of(Suitability.KINDLING, Suitability.LUMBERING, Suitability.MINING)));
        assertEquals(CarryMode.NONE, CarryMode.forSuitabilities(Set.of()));
    }

    @Test
    void slotsUsedByEachMode() {
        for (int capacity = 1; capacity <= WorkerInventory.INVENTORY_SIZE; capacity++) {
            for (int slot = 0; slot < WorkerInventory.INVENTORY_SIZE; slot++) {
                assertEquals(
                        slot < capacity, CarryMode.FULL.usesSlot(slot, capacity), "slot " + slot + " of " + capacity);
                // A seed pouch is one slot, whatever its capacity
                assertEquals(slot == CarryMode.POUCH_SLOT, CarryMode.SEED_POUCH.usesSlot(slot, capacity));
                assertFalse(CarryMode.NONE.usesSlot(slot, capacity));
            }
            assertFalse(CarryMode.FULL.usesSlot(-1, capacity));
        }
        assertFalse(CarryMode.FULL.usesSlot(WorkerInventory.INVENTORY_SIZE, 9), "never past the storage");
    }

    @Test
    void aTransporterCarriesASlotALevel() {
        for (int level = 1; level <= 5; level++) {
            assertEquals(level, CarryMode.slotsForLevel(level));
        }
        assertEquals(1, CarryMode.slotsForLevel(0));
        assertEquals(5, CarryMode.slotsForLevel(7));
    }

    @Test
    @SuppressWarnings("EnumOrdinal") // tests the ordinal lookup itself
    void fromOrdinalFallsBackToNone() {
        for (CarryMode mode : CarryMode.values()) {
            assertEquals(mode, CarryMode.fromOrdinal(mode.ordinal()));
        }
        assertEquals(CarryMode.NONE, CarryMode.fromOrdinal(-1));
        assertEquals(CarryMode.NONE, CarryMode.fromOrdinal(99));
    }
}
