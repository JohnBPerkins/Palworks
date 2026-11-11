package dev.palworks.crop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.palworks.config.PalworksConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The chance a waterer fertilizes the crop it waters, by its Watering level. */
class WateringTest {
    private static final double CLOSE = 1e-9;

    @AfterEach
    void restoreDefaults() {
        PalworksConfig.setCurrent(PalworksConfig.DEFAULTS);
    }

    private static void perLevel(double chance) {
        PalworksConfig d = PalworksConfig.DEFAULTS;
        PalworksConfig.setCurrent(new PalworksConfig(
                d.workRangeHorizontal(),
                d.workRangeVertical(),
                d.ownerRange(),
                d.pickupRadius(),
                chance,
                d.wateringCooldownSeconds()));
    }

    @Test
    void theChanceGrowsWithTheLevel() {
        // The default, 10% a level
        assertEquals(0.1, Watering.fertilizeChance(1), CLOSE);
        assertEquals(0.3, Watering.fertilizeChance(3), CLOSE);
        assertEquals(0.5, Watering.fertilizeChance(5), CLOSE);
    }

    @Test
    void theChanceFollowsTheSettingAndIsNeverMoreThanCertain() {
        perLevel(0.2);
        assertEquals(0.6, Watering.fertilizeChance(3), CLOSE);
        assertEquals(1.0, Watering.fertilizeChance(5), CLOSE);
        perLevel(0.34);
        assertEquals(1.0, Watering.fertilizeChance(3), CLOSE);
        perLevel(0.0);
        assertEquals(0.0, Watering.fertilizeChance(5), CLOSE);
    }
}
