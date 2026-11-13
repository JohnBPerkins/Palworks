package dev.palworks.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests naming, reading and changing palworks.json's settings for /palworks config. */
class ConfigKeysTest {
    private static final PalworksConfig DEFAULTS = PalworksConfig.DEFAULTS;
    private static final String WORK_RANGE = "work_range_horizontal";
    private static final String HUNGER_ENABLED = "hunger.enabled";

    private static PalworksConfig set(PalworksConfig config, String name, String value) {
        DataResult<PalworksConfig> result = ConfigKeys.set(config, name, value);
        assertTrue(
                result.error().isEmpty(),
                () -> name + " = " + value + ": " + result.error().get().message());
        return result.getOrThrow();
    }

    private static void assertRefused(String name, String value) {
        assertTrue(ConfigKeys.set(DEFAULTS, name, value).error().isPresent(), name + " = " + value);
    }

    @Test
    void everySettingInTheFileHasAName() {
        List<String> names = ConfigKeys.names();
        assertTrue(names.contains(WORK_RANGE), names.toString());
        assertTrue(names.contains("fertilize_chance_per_level"), names.toString());
        assertTrue(names.contains(HUNGER_ENABLED), names.toString());
        assertTrue(names.contains("sleep.tired_work_speed"), names.toString());
        assertFalse(names.contains("hunger"), "objects aren't settings themselves");
        assertEquals(21, names.size(), names.toString());
    }

    @Test
    void readsASetting() {
        assertEquals(new JsonPrimitive(32), ConfigKeys.get(DEFAULTS, WORK_RANGE));
        assertEquals(new JsonPrimitive(true), ConfigKeys.get(DEFAULTS, HUNGER_ENABLED));
        assertNull(ConfigKeys.get(DEFAULTS, "hunger"));
        assertNull(ConfigKeys.get(DEFAULTS, "hunger.nope"));
        assertNull(ConfigKeys.get(DEFAULTS, "nope.enabled"));
    }

    @Test
    void changesASettingAndLeavesTheRest() {
        PalworksConfig changed = set(DEFAULTS, WORK_RANGE, "48");
        assertEquals(48, changed.workRangeHorizontal());
        assertEquals(DEFAULTS.workRangeVertical(), changed.workRangeVertical());
        assertEquals(DEFAULTS.hunger(), changed.hunger());

        changed = set(changed, HUNGER_ENABLED, "false");
        assertFalse(changed.hunger().enabled());
        assertEquals(48, changed.workRangeHorizontal(), "the earlier change is kept");

        assertEquals(0.5, set(DEFAULTS, "sleep.tired_work_speed", "0.5").sleep().tiredWorkSpeed());
        assertEquals(4.0, set(DEFAULTS, "pickup_radius", "4").pickupRadius(), "a whole number for a decimal one");
    }

    @Test
    void refusesWhatTheFileWouldNotTake() {
        assertRefused(WORK_RANGE, "100"); // range 8-64
        assertRefused(WORK_RANGE, "2.5");
        assertRefused(WORK_RANGE, "lots");
        assertRefused(HUNGER_ENABLED, "yes");
        assertRefused("sleep.tired_work_speed", "NaN");
        assertRefused("hunger", "1");
        assertRefused("nope", "1");
    }

    @Test
    void resetsASettingToItsDefault() {
        PalworksConfig changed = set(set(DEFAULTS, "owner_range", "12"), "hunger.work_per_fullness", "40");
        PalworksConfig reset = ConfigKeys.reset(changed, "owner_range").getOrThrow();
        assertEquals(DEFAULTS.ownerRange(), reset.ownerRange());
        assertEquals(40, reset.hunger().workPerFullness(), "the other change is kept");
        assertTrue(ConfigKeys.reset(changed, "nope").error().isPresent());
    }
}
