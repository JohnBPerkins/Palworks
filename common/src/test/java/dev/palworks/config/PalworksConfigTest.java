package dev.palworks.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PalworksConfigTest {

    @AfterEach
    void restoreDefaults() {
        PalworksConfig.setCurrent(PalworksConfig.DEFAULTS);
    }

    private static PalworksConfig parse(String json, List<String> warnings) {
        return PalworksConfig.parse(json, warnings::add);
    }

    @Test
    void anEmptyFileIsAllDefaults() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{}", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void fieldsLeftOutKeepTheirDefaults() {
        List<String> warnings = new ArrayList<>();
        PalworksConfig config = parse("{\"work_range_horizontal\": 48, \"pickup_radius\": 2.5}", warnings);
        assertEquals(
                new PalworksConfig(
                        48,
                        PalworksConfig.DEFAULTS.workRangeVertical(),
                        PalworksConfig.DEFAULTS.ownerRange(),
                        2.5,
                        PalworksConfig.DEFAULTS.fertilizeChancePerLevel(),
                        PalworksConfig.DEFAULTS.wateringCooldownSeconds()),
                config);
        assertEquals(List.of(), warnings);
    }

    @Test
    void wateringSettingsAreRead() {
        List<String> warnings = new ArrayList<>();
        PalworksConfig config =
                parse("{\"fertilize_chance_per_level\": 0.05, \"watering_cooldown_seconds\": 30}", warnings);
        assertEquals(0.05, config.fertilizeChancePerLevel());
        assertEquals(30, config.wateringCooldownSeconds());
        assertEquals(List.of(), warnings);
    }

    @Test
    void hungerSettingsAreReadFromTheirOwnObject() {
        List<String> warnings = new ArrayList<>();
        PalworksConfig config = parse(
                "{\"hunger\": {\"work_per_fullness\": 40, \"empty_work_speed\": 0.5, \"trough_fill_fraction\": 0}}",
                warnings);
        assertEquals(List.of(), warnings);
        assertEquals(
                new PalworksConfig.HungerConfig(
                        true, 40, PalworksConfig.HungerConfig.DEFAULTS.idleDrainPerDay(), 0.0, 0.5),
                config.hunger());
        assertEquals(PalworksConfig.DEFAULTS.withHunger(config.hunger()), config);
    }

    @Test
    void hungerCanBeTurnedOff() {
        List<String> warnings = new ArrayList<>();
        assertFalse(
                parse("{\"hunger\": {\"enabled\": false}}", warnings).hunger().enabled());
        assertEquals(List.of(), warnings);
    }

    @Test
    void theHungerDefaultsAreAQuarterADayAndThreeQuartersSpeed() {
        PalworksConfig.HungerConfig hunger = PalworksConfig.DEFAULTS.hunger();
        assertTrue(hunger.enabled());
        assertEquals(20, hunger.workPerFullness());
        assertEquals(0.25, hunger.idleDrainPerDay());
        assertEquals(0.5, hunger.troughFillFraction());
        assertEquals(0.75, hunger.emptyWorkSpeed());
    }

    @Test
    void sleepSettingsAreReadFromTheirOwnObject() {
        List<String> warnings = new ArrayList<>();
        PalworksConfig config = parse("{\"sleep\": {\"enabled\": false, \"tired_work_speed\": 0.5}}", warnings);
        assertEquals(List.of(), warnings);
        assertEquals(new PalworksConfig.SleepConfig(false, 0.5), config.sleep());
        assertEquals(PalworksConfig.DEFAULTS.withSleep(config.sleep()), config);
    }

    @Test
    void theSleepDefaultsAreOnAndThreeQuartersSpeedWhenTired() {
        assertTrue(PalworksConfig.DEFAULTS.sleep().enabled());
        assertEquals(0.75, PalworksConfig.DEFAULTS.sleep().tiredWorkSpeed());
    }

    @Test
    void moodSettingsAreReadFromTheirOwnObject() {
        List<String> warnings = new ArrayList<>();
        PalworksConfig config = parse(
                "{\"mood\": {\"enabled\": false, \"work_per_point\": 4, \"hungry_drain_per_day\": 80,"
                        + " \"tired_drain_per_day\": 10, \"recovery_per_day\": 60, \"unhappy_below\": 40,"
                        + " \"strike_until\": 20}}",
                warnings);
        assertEquals(List.of(), warnings);
        assertEquals(new PalworksConfig.MoodConfig(false, 4, 80, 10, 60, 40, 20), config.mood());
        assertEquals(PalworksConfig.DEFAULTS.withMood(config.mood()), config);
    }

    @Test
    void theMoodDefaults() {
        assertEquals(new PalworksConfig.MoodConfig(true, 2, 50, 25, 100, 50, 30), PalworksConfig.DEFAULTS.mood());
    }

    @Test
    void anOldUnhappySpeedIsIgnored() {
        // Unhappy workers used to work slower; a config from then still loads, the setting dropped
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"mood\": {\"unhappy_work_speed\": 0.5}}", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEmptySpeedOfNothingIsRefused() {
        // A worker that never works would be stuck: at least a tenth
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"hunger\": {\"empty_work_speed\": 0}}", warnings));
        assertEquals(1, warnings.size());
    }

    @Test
    void aFertilizeChanceOverCertainAtLevelFiveIsRefused() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"fertilize_chance_per_level\": 0.5}", warnings));
        assertEquals(1, warnings.size());
    }

    @Test
    void aValueOutOfRangeMeansTheDefaultsWithAWarning() {
        List<String> warnings = new ArrayList<>();
        // Past Cobblemon's teleport-back distance
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"owner_range\": 40}", warnings));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("40"), warnings.get(0));
    }

    @Test
    void theWrongTypeOrBrokenJsonMeansTheDefaultsWithAWarning() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"work_range_vertical\": \"far\"}", warnings));
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"work_range_vertical\": ", warnings));
        assertEquals(PalworksConfig.DEFAULTS, parse("[32]", warnings));
        assertEquals(3, warnings.size(), String.join("\n", warnings));
    }

    @Test
    void theWrittenDefaultsReadBackAsTheDefaults() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse(PalworksConfig.toJson(PalworksConfig.DEFAULTS), warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void loadingWritesTheDefaultsWhenThereIsNoFile(@TempDir Path configDir) throws IOException {
        PalworksConfig.load(configDir);
        Path file = configDir.resolve("palworks.json");
        assertTrue(Files.exists(file));
        assertEquals(PalworksConfig.DEFAULTS, PalworksConfig.current());
        assertTrue(Files.readString(file).contains("\"work_range_horizontal\": 32"), Files.readString(file));
    }

    @Test
    void loadingReadsTheFile(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve("palworks.json"), "{\"owner_range\": 16}");
        PalworksConfig.load(configDir);
        assertEquals(16, PalworksConfig.current().ownerRange());
    }

    @Test
    void savingUsesTheSettingsAndWritesTheFileItWasLoadedFrom(@TempDir Path configDir) throws IOException {
        PalworksConfig.load(configDir);
        PalworksConfig changed =
                PalworksConfig.DEFAULTS.withHunger(new PalworksConfig.HungerConfig(false, 20, 0.25, 0.5, 0.25));
        PalworksConfig.save(changed);
        assertEquals(changed, PalworksConfig.current());

        PalworksConfig.setCurrent(PalworksConfig.DEFAULTS);
        PalworksConfig.load(configDir);
        assertEquals(changed, PalworksConfig.current(), "read back from the file");
    }
}
