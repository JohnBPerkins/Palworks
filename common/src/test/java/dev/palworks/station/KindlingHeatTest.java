package dev.palworks.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KindlingHeatTest {

    @Test
    void eachLevelsEmberSmeltsTwiceAsManyItems() {
        int[] items = {1, 2, 4, 8, 16};
        for (int level = 1; level <= 5; level++) {
            assertEquals(items[level - 1], KindlingHeat.itemsPerEmber(level));
            assertEquals(200 * items[level - 1] + 1, KindlingHeat.burnTicks(level), "level " + level);
        }
        // Level 1's is the burn a kindler has always given: one item's smelt, and a tick over
        assertEquals(FurnaceHelper.getBurnDuration(), KindlingHeat.burnTicks(1));
        assertEquals(16, KindlingHeat.itemsPerEmber(9));
        assertEquals(1, KindlingHeat.itemsPerEmber(0));
    }

    @Test
    void onlyALevelOneKindlerStaysAtItsFurnace() {
        assertTrue(KindlingHeat.staysAtItsFurnace(1));
        for (int level = 2; level <= 5; level++) {
            assertFalse(KindlingHeat.staysAtItsFurnace(level), "level " + level);
        }
    }
}
