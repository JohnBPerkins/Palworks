package dev.palworks.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Pure-logic tests for the JobType enum and its suitability mapping (no running game needed). */
class JobTypeTest {

    @Test
    void everyJobButEatingAndSleepingMapsToASuitability() {
        for (JobType type : JobType.values()) {
            if (type == JobType.EAT_FOOD || type == JobType.SLEEP) {
                continue; // what any worker does, whatever its skills
            }
            assertNotNull(type.getRequiredSuitability(), type + " must declare a required suitability");
            assertEquals(java.util.EnumSet.of(type.getRequiredSuitability()), type.requiredSuitabilities());
        }
    }

    @Test
    void eatingNeedsNoSkill() {
        // Any worker eats, whatever it's good at
        assertNull(JobType.EAT_FOOD.getRequiredSuitability());
        assertTrue(JobType.EAT_FOOD.requiredSuitabilities().isEmpty());
        assertTrue(JobRecord.of(JobType.EAT_FOOD, BlockPos.ZERO, 0)
                .canBeDoneWith(java.util.EnumSet.noneOf(dev.palworks.suitability.Suitability.class)));
    }

    @Test
    void idIsLowercaseName() {
        assertEquals("fuel_furnace", JobType.FUEL_FURNACE.getId());
        assertEquals("harvest_crops", JobType.HARVEST_CROPS.getId());
    }
}
