package dev.palworks.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        for (int slot = 0; slot < WorkerInventory.INVENTORY_SIZE; slot++) {
            assertTrue(CarryMode.FULL.usesSlot(slot));
            assertEquals(slot == CarryMode.POUCH_SLOT, CarryMode.SEED_POUCH.usesSlot(slot));
            assertFalse(CarryMode.NONE.usesSlot(slot));
        }
        assertFalse(CarryMode.FULL.usesSlot(WorkerInventory.INVENTORY_SIZE));
        assertFalse(CarryMode.FULL.usesSlot(-1));
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
