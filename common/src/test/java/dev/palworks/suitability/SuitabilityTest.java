package dev.palworks.suitability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure-logic tests for the Suitability enum and its id round-trip (no running game needed). */
class SuitabilityTest {

    @Test
    void idRoundTripsForEveryValue() {
        for (Suitability s : Suitability.values()) {
            assertEquals(s, Suitability.fromId(s.getId()), "fromId(getId()) should round-trip for " + s);
        }
    }

    @Test
    void idsAreLowercase() {
        for (Suitability s : Suitability.values()) {
            assertEquals(s.getId().toLowerCase(), s.getId(), s + " id should be lowercase");
        }
    }

    @Test
    void unknownIdReturnsNull() {
        assertNull(Suitability.fromId("not_a_real_suitability"));
    }
}
