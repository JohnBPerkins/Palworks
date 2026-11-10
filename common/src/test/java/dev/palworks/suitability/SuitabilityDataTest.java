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

/** Tests how the per-job suitability files are parsed and combined, and the files the mod ships. */
class SuitabilityDataTest {

    private static SuitabilityData load(Map<String, String> files, List<String> warnings) {
        Map<String, JsonElement> parsed = new HashMap<>();
        files.forEach((id, text) -> parsed.put(id, JsonParser.parseString(text)));
        return SuitabilityData.fromFiles(parsed, warnings::add);
    }

    private static SuitabilityData load(Map<String, String> files) {
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = load(files, warnings);
        assertEquals(List.of(), warnings, "unexpected warnings");
        return data;
    }

    @Test
    void typesGiveTheJob() {
        SuitabilityData data = load(Map.of("palworks:kindling", "{\"types\": {\"fire\": 1}}"));
        assertEquals(Map.of(KINDLING, 1), data.levels(List.of("fire"), "cobblemon:charmander"));
        assertEquals(Map.of(), data.levels(List.of("water"), "cobblemon:squirtle"));
    }

    @Test
    void bothTypesGivingAJobAddsOne() {
        SuitabilityData data = load(Map.of(
                "palworks:transporting", "{\"types\": {\"flying\": 1, \"psychic\": 1}}",
                "palworks:medicine_production", "{\"types\": {\"psychic\": 1}}"));
        assertEquals(Map.of(TRANSPORTING, 2, MEDICINE_PRODUCTION, 1),
                data.levels(List.of("psychic", "flying"), "cobblemon:xatu"));
    }

    @Test
    void speciesAddJobsAndRaiseLevels() {
        SuitabilityData data = load(Map.of(
                "palworks:lumbering", "{\"types\": {\"steel\": 1}, \"species\": {\"cobblemon:scizor\": 3}}",
                "palworks:handiwork", "{\"species\": {\"cobblemon:scizor\": 1}}"));
        assertEquals(Map.of(LUMBERING, 3, HANDIWORK, 1), data.levels(List.of("bug", "steel"), "cobblemon:scizor"));
    }

    @Test
    void speciesWithoutANamespaceAreCobblemons() {
        SuitabilityData data = load(Map.of("palworks:lumbering", "{\"species\": {\"Scizor\": 3}}"));
        assertEquals(Map.of(LUMBERING, 3), data.levels(List.of(), "cobblemon:scizor"));
    }

    @Test
    void speciesNeverLowerATypeLevel() {
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 4}, \"species\": {\"cobblemon:charmander\": 2}}"));
        assertEquals(Map.of(KINDLING, 4), data.levels(List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void speciesLevelZeroTakesTheJobAway() {
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 1}, \"species\": {\"cobblemon:charmander\": 0}}"));
        assertEquals(Map.of(), data.levels(List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void filesForTheSameJobFromDifferentNamespacesMerge() {
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 1}, \"species\": {\"cobblemon:magmar\": 2}}",
                "someaddon:kindling", "{\"types\": {\"fire\": 3, \"dragon\": 1}, \"species\": {\"someaddon:sparky\": 2, \"cobblemon:magmar\": 1}}"));
        assertEquals(Map.of(KINDLING, 3), data.levels(List.of("fire"), "cobblemon:charmander"));
        assertEquals(Map.of(KINDLING, 1), data.levels(List.of("dragon"), "cobblemon:dratini"));
        assertEquals(Map.of(KINDLING, 2), data.levels(List.of(), "someaddon:sparky"));
        assertEquals(Map.of(KINDLING, 2), data.levels(List.of(), "cobblemon:magmar"), "the higher level wins");
    }

    @Test
    void aSpeciesZeroFromAnyFileWins() {
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 1}, \"species\": {\"cobblemon:magmar\": 3}}",
                "modpack:kindling", "{\"species\": {\"cobblemon:magmar\": 0}}"));
        assertEquals(Map.of(), data.levels(List.of("fire"), "cobblemon:magmar"));
    }

    @Test
    void replaceIgnoresTheOtherNamespacesFiles() {
        SuitabilityData data = load(Map.of(
                "palworks:lumbering", "{\"types\": {\"fighting\": 1, \"steel\": 1}}",
                "modpack:lumbering", "{\"replace\": true, \"types\": {\"steel\": 2}}",
                "palworks:kindling", "{\"types\": {\"fire\": 1}}"));
        assertEquals(Map.of(), data.levels(List.of("fighting"), "x:y"));
        assertEquals(Map.of(LUMBERING, 2), data.levels(List.of("steel"), "x:y"));
        assertEquals(Map.of(KINDLING, 1), data.levels(List.of("fire"), "x:y"), "other jobs are untouched");
    }

    @Test
    void levelsAreCappedAtTheMaximum() {
        SuitabilityData data = load(Map.of("palworks:kindling", "{\"types\": {\"fire\": 5, \"ground\": 5}}"));
        assertEquals(Map.of(KINDLING, SuitabilityData.MAX_LEVEL), data.levels(List.of("fire", "ground"), "x:y"));
    }

    @Test
    void badFilesAndEntriesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 9, \"water\": \"lots\"}, \"pokemon\": {}}",
                "palworks:juggling", "{\"types\": {\"normal\": 1}}",
                "palworks:cooling", "[1, 2]",
                "palworks:watering", "{\"types\": [\"water\"]}",
                "palworks:mining", "{\"types\": {\"rock\": 0}}"), warnings);

        assertEquals(Map.of(KINDLING, SuitabilityData.MAX_LEVEL), data.levels(List.of("fire"), "x:y"));
        assertEquals(Map.of(), data.levels(List.of("water"), "x:y"));
        assertEquals(Map.of(), data.levels(List.of("normal"), "x:y"));
        // 0 only means "take it away" for species; for a type it's raised to 1.
        assertEquals(Map.of(MINING, 1), data.levels(List.of("rock"), "x:y"));
        // fire 9, water "lots", "pokemon", juggling, cooling's array, watering's types array, rock 0
        assertEquals(7, warnings.size(), String.join("\n", warnings));
    }

    // --- The files shipped in src/main/resources: same table as the old hard-coded providers ---

    private static SuitabilityData shipped() throws IOException {
        Path root = Path.of("src/main/resources/data");
        Map<String, JsonElement> files = new HashMap<>();
        try (Stream<Path> namespaces = Files.list(root)) {
            for (Path namespace : namespaces.toList()) {
                Path dir = namespace.resolve("palworks/suitability");
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> jobFiles = Files.list(dir)) {
                    for (Path file : jobFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
                        String name = file.getFileName().toString();
                        files.put(namespace.getFileName() + ":" + name.substring(0, name.length() - ".json".length()),
                                JsonParser.parseString(Files.readString(file)));
                    }
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = SuitabilityData.fromFiles(files, warnings::add);
        assertEquals(List.of(), warnings, "the shipped files should load without warnings");
        return data;
    }

    @Test
    void oneShippedFilePerJob() throws IOException {
        assertEquals(Suitability.values().length, shipped().jobCount());
    }

    @Test
    void shippedTypesMatchTheOldTable() throws IOException {
        SuitabilityData data = shipped();
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
        // Fighting gives Lumbering 1; Machamp's species entry adds Handiwork 3.
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
