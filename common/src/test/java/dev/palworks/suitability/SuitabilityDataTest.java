package dev.palworks.suitability;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.palworks.suitability.Suitability.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests how suitability datapack files are parsed and combined, and the files the mod ships. */
class SuitabilityDataTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static SuitabilityData load(Map<String, String> files, List<String> warnings) {
        Map<String, JsonElement> parsed = new HashMap<>();
        files.forEach((id, text) -> parsed.put(id, json(text)));
        return SuitabilityData.fromFiles(parsed, warnings::add);
    }

    private static SuitabilityData load(Map<String, String> files) {
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = load(files, warnings);
        assertEquals(List.of(), warnings, "unexpected warnings");
        return data;
    }

    @Test
    void typesGiveTheirSkills() {
        SuitabilityData data = load(Map.of("palworks:types/fire", "{\"kindling\": 1}"));
        assertEquals(Map.of(KINDLING, 1), data.levels(List.of("fire"), "cobblemon:charmander"));
        assertEquals(Map.of(), data.levels(List.of("water"), "cobblemon:squirtle"));
    }

    @Test
    void bothTypesGivingASkillAddsOne() {
        SuitabilityData data = load(Map.of(
                "palworks:types/flying", "{\"transporting\": 1}",
                "palworks:types/psychic", "{\"transporting\": 1, \"medicine_production\": 1}"));
        assertEquals(Map.of(TRANSPORTING, 2, MEDICINE_PRODUCTION, 1),
                data.levels(List.of("psychic", "flying"), "cobblemon:xatu"));
    }

    @Test
    void speciesAddsSkillsAndRaisesLevels() {
        SuitabilityData data = load(Map.of(
                "palworks:types/bug", "{}",
                "palworks:types/steel", "{\"lumbering\": 1}",
                "cobblemon:species/scizor", "{\"lumbering\": 3, \"handiwork\": 1}"));
        assertEquals(Map.of(LUMBERING, 3, HANDIWORK, 1), data.levels(List.of("bug", "steel"), "cobblemon:scizor"));
    }

    @Test
    void speciesNeverLowersATypeLevel() {
        SuitabilityData data = load(Map.of(
                "palworks:types/fire", "{\"kindling\": 4}",
                "cobblemon:species/charmander", "{\"kindling\": 2}"));
        assertEquals(Map.of(KINDLING, 4), data.levels(List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void speciesLevelZeroRemovesASkill() {
        SuitabilityData data = load(Map.of(
                "palworks:types/fire", "{\"kindling\": 1}",
                "cobblemon:species/charmander", "{\"kindling\": 0}"));
        assertEquals(Map.of(), data.levels(List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void speciesIsMatchedByNamespaceToo() {
        SuitabilityData data = load(Map.of("someaddon:species/sparky", "{\"kindling\": 2}"));
        assertEquals(Map.of(KINDLING, 2), data.levels(List.of(), "someaddon:sparky"));
        assertEquals(Map.of(), data.levels(List.of(), "cobblemon:sparky"));
    }

    @Test
    void typeFilesFromDifferentNamespacesAddTogether() {
        SuitabilityData data = load(Map.of(
                "palworks:types/fire", "{\"kindling\": 1}",
                "someaddon:types/fire", "{\"cooling\": 2, \"kindling\": 3}"));
        assertEquals(Map.of(KINDLING, 3, COOLING, 2), data.levels(List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void levelsAreCappedAtTheMaximum() {
        SuitabilityData data = load(Map.of(
                "palworks:types/fire", "{\"kindling\": 5}",
                "palworks:types/ground", "{\"kindling\": 5}"));
        assertEquals(Map.of(KINDLING, SuitabilityData.MAX_LEVEL), data.levels(List.of("fire", "ground"), "x:y"));
    }

    @Test
    void badEntriesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = load(Map.of(
                "palworks:types/fire", "{\"kindling\": 9, \"juggling\": 1, \"cooling\": \"lots\"}",
                "palworks:types/water", "[1, 2]",
                "palworks:elsewhere/thing", "{}",
                "palworks:types/ice", "{\"cooling\": 0}"), warnings);

        assertEquals(Map.of(KINDLING, SuitabilityData.MAX_LEVEL), data.levels(List.of("fire"), "x:y"));
        assertEquals(Map.of(), data.levels(List.of("water"), "x:y"));
        // 0 only means "remove" for species; for a type it's raised to 1.
        assertEquals(Map.of(COOLING, 1), data.levels(List.of("ice"), "x:y"));
        assertEquals(6, warnings.size(), String.join("\n", warnings));
    }

    // --- The files shipped in src/main/resources: same table as the old hard-coded providers ---

    private static SuitabilityData shipped() throws IOException {
        Path root = Path.of("src/main/resources/data");
        Map<String, JsonElement> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                // data/<namespace>/palworks/suitability/<rest>.json -> <namespace>:<rest>
                Path relative = root.relativize(file);
                if (relative.getNameCount() < 4 || !relative.subpath(1, 3).toString().replace('\\', '/').equals("palworks/suitability")) {
                    continue;
                }
                String rest = relative.subpath(3, relative.getNameCount()).toString().replace('\\', '/');
                String id = relative.getName(0) + ":" + rest.substring(0, rest.length() - ".json".length());
                files.put(id, JsonParser.parseString(Files.readString(file)));
            }
        }
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = SuitabilityData.fromFiles(files, warnings::add);
        assertEquals(List.of(), warnings, "the shipped files should load without warnings");
        return data;
    }

    @Test
    void shippedTypesMatchTheOldTable() throws IOException {
        SuitabilityData data = shipped();
        assertEquals(18, data.typeCount(), "one file per Cobblemon type");
        assertEquals(Map.of(FARMING, 1), data.levels(List.of("normal"), "x:y"));
        assertEquals(Map.of(KINDLING, 1), data.levels(List.of("fire"), "x:y"));
        assertEquals(Map.of(WATERING, 1), data.levels(List.of("water"), "x:y"));
        assertEquals(Map.of(PLANTING, 1, GATHERING, 1), data.levels(List.of("grass"), "x:y"));
        assertEquals(Map.of(GENERATING_ELECTRICITY, 1), data.levels(List.of("electric"), "x:y"));
        assertEquals(Map.of(COOLING, 1), data.levels(List.of("ice"), "x:y"));
        assertEquals(Map.of(LUMBERING, 1), data.levels(List.of("fighting"), "x:y"));
        assertEquals(Map.of(LUMBERING, 1), data.levels(List.of("steel"), "x:y"));
        assertEquals(Map.of(MINING, 1), data.levels(List.of("rock"), "x:y"));
        assertEquals(Map.of(MINING, 1), data.levels(List.of("ground"), "x:y"));
        assertEquals(Map.of(TRANSPORTING, 1), data.levels(List.of("flying"), "x:y"));
        assertEquals(Map.of(MEDICINE_PRODUCTION, 1, TRANSPORTING, 1), data.levels(List.of("psychic"), "x:y"));
        assertEquals(Map.of(NETHER, 1), data.levels(List.of("ghost"), "x:y"));
        for (String none : List.of("poison", "bug", "dragon", "dark", "fairy")) {
            assertEquals(Map.of(), data.levels(List.of(none), "x:y"), none);
        }
        // Rock/Ground: both types give Mining.
        assertEquals(Map.of(MINING, 2), data.levels(List.of("rock", "ground"), "cobblemon:geodude"));
    }

    @Test
    void shippedSpeciesMatchTheOldTable() throws IOException {
        SuitabilityData data = shipped();
        assertEquals(60, data.speciesCount());
        // Fighting gives Lumbering 1; Machamp's species file adds Handiwork 3.
        assertEquals(Map.of(LUMBERING, 1, HANDIWORK, 3), data.levels(List.of("fighting"), "cobblemon:machamp"));
        assertEquals(Map.of(LUMBERING, 1, HANDIWORK, 2), data.levels(List.of("fighting"), "cobblemon:machoke"));
        assertEquals(Map.of(LUMBERING, 1, HANDIWORK, 1), data.levels(List.of("fighting"), "cobblemon:machop"));
        assertEquals(Map.of(LUMBERING, 3), data.levels(List.of("bug", "steel"), "cobblemon:scizor"));
        assertEquals(Map.of(LUMBERING, 2, TRANSPORTING, 1), data.levels(List.of("bug", "flying"), "cobblemon:scyther"));
        assertEquals(Map.of(LUMBERING, 2), data.levels(List.of("bug"), "cobblemon:pinsir"));
        assertEquals(Map.of(PLANTING, 1, GATHERING, 1, LUMBERING, 3), data.levels(List.of("grass", "steel"), "cobblemon:kartana"));
        assertEquals(Map.of(FARMING, 3), data.levels(List.of("normal"), "cobblemon:miltank"));
        assertEquals(Map.of(FARMING, 2), data.levels(List.of("normal"), "cobblemon:chansey"));
        assertEquals(Map.of(PLANTING, 1, GATHERING, 3, TRANSPORTING, 1, FARMING, 1),
                data.levels(List.of("grass", "flying"), "cobblemon:tropius"));
        assertTrue(data.levels(List.of("water"), "cobblemon:lucario").containsKey(HANDIWORK));
    }
}
