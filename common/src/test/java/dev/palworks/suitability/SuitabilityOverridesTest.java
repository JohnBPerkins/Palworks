package dev.palworks.suitability;

import static dev.palworks.suitability.Suitability.PLANTING;
import static dev.palworks.suitability.Suitability.WATERING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.palworks.suitability.SuitabilityData.Form;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests admins' in-game suitability changes: put over the datapacks, saved, loaded and reset. */
class SuitabilityOverridesTest {
    private static final String BULBASAUR = "cobblemon:bulbasaur";
    private static final List<Form> GRASS = List.of(new Form(List.of("grass", "poison"), 318));

    @BeforeEach
    void datapack() {
        SuitabilityOverrides.setDatapack(SuitabilityData.fromFiles(
                Map.of("palworks:planting", JsonParser.parseString("{\"types\": {\"grass\": 1}}")), warning -> {}));
    }

    @AfterEach
    void forget() {
        SuitabilityOverrides.clear();
        SuitabilityOverrides.setDatapack(SuitabilityData.EMPTY);
    }

    private static Map<Suitability, Integer> bulbasaur() {
        return SuitabilityData.current().levels(BULBASAUR, GRASS);
    }

    @Test
    void aChangeIsUsedAndSavedAndTheDatapackIsNot(@TempDir Path world) throws IOException {
        SuitabilityData datapack = SuitabilityData.current();
        SuitabilityOverrides.load(world);
        SuitabilityOverrides.set(BULBASAUR, WATERING, 3);

        assertEquals(Map.of(PLANTING, 1, WATERING, 3), bulbasaur());
        assertEquals(Map.of(PLANTING, 1), datapack.levels(BULBASAUR, GRASS));
        assertEquals(
                Map.of(BULBASAUR, Map.of(WATERING, 3)),
                SuitabilityOverrides.parse(
                        Files.readString(world.resolve(SuitabilityOverrides.FILE_NAME)), message -> {}));
    }

    @Test
    void changesAreLoadedWithTheWorldAndPutBackAfterAReload(@TempDir Path world) throws IOException {
        SuitabilityOverrides.load(world);
        SuitabilityOverrides.set(BULBASAUR, PLANTING, 0);
        SuitabilityOverrides.clear();
        assertEquals(Map.of(PLANTING, 1), bulbasaur(), "with the world closed, just the datapack");

        SuitabilityOverrides.load(world);
        assertEquals(Map.of(), bulbasaur(), "loaded with the world");
        datapack(); // a /reload
        assertEquals(Map.of(), bulbasaur(), "still there after a reload");
    }

    @Test
    void resettingBringsBackTheDatapacksLevels(@TempDir Path world) throws IOException {
        SuitabilityOverrides.load(world);
        SuitabilityOverrides.set(BULBASAUR, WATERING, 3);
        SuitabilityOverrides.set(BULBASAUR, PLANTING, 4);

        assertTrue(SuitabilityOverrides.reset(BULBASAUR, WATERING));
        assertEquals(Map.of(PLANTING, 4), bulbasaur());
        assertFalse(SuitabilityOverrides.reset(BULBASAUR, WATERING), "already reset");

        assertTrue(SuitabilityOverrides.reset(BULBASAUR, null));
        assertEquals(Map.of(PLANTING, 1), bulbasaur());
        assertEquals(Map.of(), SuitabilityOverrides.all());
        assertFalse(SuitabilityOverrides.reset(BULBASAUR, null), "has none left");
    }

    @Test
    void resettingAll(@TempDir Path world) throws IOException {
        SuitabilityOverrides.load(world);
        SuitabilityOverrides.set(BULBASAUR, WATERING, 3);
        SuitabilityOverrides.set("cobblemon:oddish", WATERING, 2);

        assertEquals(2, SuitabilityOverrides.resetAll());
        assertEquals(Map.of(PLANTING, 1), bulbasaur());
        assertEquals(0, SuitabilityOverrides.resetAll());
        SuitabilityOverrides.load(world);
        assertEquals(Map.of(), SuitabilityOverrides.all(), "the reset was saved");
    }

    @Test
    void theFileRoundTrips() {
        Map<String, Map<Suitability, Integer>> overrides =
                Map.of(BULBASAUR, Map.of(WATERING, 3, PLANTING, 0), "cobblemon:oddish", Map.of(WATERING, 1));
        List<String> warnings = new ArrayList<>();
        assertEquals(overrides, SuitabilityOverrides.parse(SuitabilityOverrides.toJson(overrides), warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadEntryIsSkippedAndTheRestKept() {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<Suitability, Integer>> overrides = SuitabilityOverrides.parse("""
                {"species": {
                  "Bulbasaur": {"watering": 3, "swimming": 2, "planting": 9},
                  "oddish": {"planting": 1.5},
                  "cobblemon:ivysaur": "lots"
                }}""", warnings::add);
        assertEquals(Map.of(BULBASAUR, Map.of(WATERING, 3)), overrides);
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void aBrokenFileIsIgnored(@TempDir Path world) throws IOException {
        Files.writeString(world.resolve(SuitabilityOverrides.FILE_NAME), "{ not json", StandardCharsets.UTF_8);
        SuitabilityOverrides.load(world);
        assertEquals(Map.of(), SuitabilityOverrides.all());
        assertEquals(Map.of(PLANTING, 1), bulbasaur());
    }
}
