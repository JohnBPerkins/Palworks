package dev.palworks.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Pure-logic tests for the JobType enum and its suitability mapping (no running game needed). */
class JobTypeTest {

    @Test
    void everyJobMapsToASuitability() {
        for (JobType type : JobType.values()) {
            assertNotNull(type.getRequiredSuitability(), type + " must declare a required suitability");
        }
    }

    @Test
    void idIsLowercaseName() {
        assertEquals("fuel_furnace", JobType.FUEL_FURNACE.getId());
        assertEquals("harvest_crops", JobType.HARVEST_CROPS.getId());
    }
}
