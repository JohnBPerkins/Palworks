package dev.palworks.suitability;

import static dev.palworks.suitability.Suitability.COOLING;
import static dev.palworks.suitability.Suitability.FARMING;
import static dev.palworks.suitability.Suitability.GATHERING;
import static dev.palworks.suitability.Suitability.GENERATING_ELECTRICITY;
import static dev.palworks.suitability.Suitability.HANDIWORK;
import static dev.palworks.suitability.Suitability.KINDLING;
import static dev.palworks.suitability.Suitability.LUMBERING;
import static dev.palworks.suitability.Suitability.MEDICINE_PRODUCTION;
import static dev.palworks.suitability.Suitability.MINING;
import static dev.palworks.suitability.Suitability.NETHER;
import static dev.palworks.suitability.Suitability.PLANTING;
import static dev.palworks.suitability.Suitability.TRANSPORTING;
import static dev.palworks.suitability.Suitability.WATERING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.palworks.suitability.SuitabilityData.Form;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Tests how the per-job suitability files are parsed and combined, and the files the mod ships. */
// Test data reads better inline than as constants
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SuitabilityDataTest {

    private static SuitabilityData load(Map<String, String> files, List<String> warnings) {
        Map<String, JsonElement> parsed = new HashMap<>();
        files.forEach((id, text) -> parsed.put(id, JsonParser.parseString(text)));
        return SuitabilityData.fromFiles(parsed, warnings::add);
    }

    /** A first form's levels, with the lowest power level (base stats under 350): just the files' levels. */
    private static Map<Suitability, Integer> levels(SuitabilityData data, List<String> types, String species) {
        return data.levels(species, List.of(new Form(types, 300)));
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
        assertEquals(Map.of(KINDLING, 1), levels(data, List.of("fire"), "cobblemon:charmander"));
        assertEquals(Map.of(), levels(data, List.of("water"), "cobblemon:squirtle"));
    }

    @Test
    void bothTypesGivingAJobGiveTheBetterLevel() {
        SuitabilityData data = load(Map.of(
                "palworks:transporting", "{\"types\": {\"flying\": 1, \"psychic\": 2}}",
                "palworks:medicine_production", "{\"types\": {\"psychic\": 1}}"));
        assertEquals(
                Map.of(TRANSPORTING, 2, MEDICINE_PRODUCTION, 1),
                levels(data, List.of("psychic", "flying"), "cobblemon:xatu"));
    }

    @Test
    void speciesAddJobsAndRaiseLevels() {
        SuitabilityData data = load(Map.of(
                "palworks:lumbering", "{\"types\": {\"steel\": 1}, \"species\": {\"cobblemon:scizor\": 3}}",
                "palworks:handiwork", "{\"species\": {\"cobblemon:scizor\": 1}}"));
        assertEquals(Map.of(LUMBERING, 3, HANDIWORK, 1), levels(data, List.of("bug", "steel"), "cobblemon:scizor"));
    }

    @Test
    void speciesWithoutANamespaceAreCobblemons() {
        SuitabilityData data = load(Map.of("palworks:lumbering", "{\"species\": {\"Scizor\": 3}}"));
        assertEquals(Map.of(LUMBERING, 3), levels(data, List.of(), "cobblemon:scizor"));
    }

    @Test
    void aSpeciesNumberAddsToItsPowerLevel() {
        SuitabilityData data = load(Map.of(
                "palworks:lumbering",
                "{\"species\": {\"cobblemon:kingambit\": 1, \"cobblemon:pawniard\": 1, \"cobblemon:kartana\": 2}}"));
        // 1 is the usual: the level its stats give (Kingambit 550: 4, Pawniard 340: 1)
        assertEquals(
                Map.of(LUMBERING, 4),
                data.levels("cobblemon:kingambit", List.of(new Form(List.of("dark", "steel"), 550))));
        assertEquals(
                Map.of(LUMBERING, 1),
                data.levels("cobblemon:pawniard", List.of(new Form(List.of("dark", "steel"), 340))));
        // 2 is one more (Kartana 570: 4, so 5)
        assertEquals(
                Map.of(LUMBERING, 5),
                data.levels("cobblemon:kartana", List.of(new Form(List.of("grass", "steel"), 570))));
    }

    @Test
    void aSpeciesEntryIsUsedInsteadOfItsTypesForThatJob() {
        SuitabilityData data = load(
                Map.of("palworks:kindling", "{\"types\": {\"fire\": 4}, \"species\": {\"cobblemon:charmander\": 2}}"));
        assertEquals(Map.of(KINDLING, 2), levels(data, List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void aSpeciesEntryHasNoEvolutionLinePenalty() {
        // Bibarel's own stats (410) give 2, whatever Bidoof was: the line only matters for types
        SuitabilityData data = load(Map.of("palworks:lumbering", "{\"species\": {\"cobblemon:bibarel\": 1}}"));
        assertEquals(
                Map.of(LUMBERING, 2),
                data.levels(
                        "cobblemon:bibarel",
                        List.of(new Form(List.of("normal", "water"), 410), new Form(List.of("normal"), 250))));
    }

    @Test
    void speciesLevelZeroTakesTheJobAway() {
        SuitabilityData data = load(
                Map.of("palworks:kindling", "{\"types\": {\"fire\": 1}, \"species\": {\"cobblemon:charmander\": 0}}"));
        assertEquals(Map.of(), levels(data, List.of("fire"), "cobblemon:charmander"));
    }

    @Test
    void filesForTheSameJobFromDifferentNamespacesMerge() {
        SuitabilityData data = load(
                Map.of(
                        "palworks:kindling", "{\"types\": {\"fire\": 1}, \"species\": {\"cobblemon:magmar\": 2}}",
                        "someaddon:kindling",
                                "{\"types\": {\"fire\": 3, \"dragon\": 1}, \"species\": {\"someaddon:sparky\": 2, \"cobblemon:magmar\": 1}}"));
        assertEquals(Map.of(KINDLING, 3), levels(data, List.of("fire"), "cobblemon:charmander"));
        assertEquals(Map.of(KINDLING, 1), levels(data, List.of("dragon"), "cobblemon:dratini"));
        assertEquals(Map.of(KINDLING, 2), levels(data, List.of(), "someaddon:sparky"));
        assertEquals(Map.of(KINDLING, 2), levels(data, List.of(), "cobblemon:magmar"), "the higher level wins");
    }

    @Test
    void aSpeciesZeroFromAnyFileWins() {
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 1}, \"species\": {\"cobblemon:magmar\": 3}}",
                "modpack:kindling", "{\"species\": {\"cobblemon:magmar\": 0}}"));
        assertEquals(Map.of(), levels(data, List.of("fire"), "cobblemon:magmar"));
    }

    @Test
    void replaceIgnoresTheOtherNamespacesFiles() {
        SuitabilityData data = load(Map.of(
                "palworks:lumbering", "{\"types\": {\"fighting\": 1, \"steel\": 1}}",
                "modpack:lumbering", "{\"replace\": true, \"types\": {\"steel\": 2}}",
                "palworks:kindling", "{\"types\": {\"fire\": 1}}"));
        assertEquals(Map.of(), levels(data, List.of("fighting"), "x:y"));
        assertEquals(Map.of(LUMBERING, 2), levels(data, List.of("steel"), "x:y"));
        assertEquals(Map.of(KINDLING, 1), levels(data, List.of("fire"), "x:y"), "other jobs are untouched");
    }

    @Test
    void levelsAreCappedAtTheMaximum() {
        SuitabilityData data = load(Map.of("palworks:kindling", "{\"types\": {\"fire\": 5}}"));
        assertEquals(
                Map.of(KINDLING, SuitabilityData.MAX_LEVEL),
                data.levels("cobblemon:reshiram", List.of(new Form(List.of("dragon", "fire"), 680))));
    }

    // --- Work levels: from base stats and the evolution line ---

    @Test
    void powerLevelsComeFromTheBaseStatTotal() {
        assertEquals(1, SuitabilityData.powerLevel(200)); // Magikarp
        assertEquals(1, SuitabilityData.powerLevel(314)); // Squirtle
        assertEquals(2, SuitabilityData.powerLevel(350));
        assertEquals(2, SuitabilityData.powerLevel(405)); // Wartortle
        assertEquals(3, SuitabilityData.powerLevel(530)); // Blastoise
        assertEquals(3, SuitabilityData.powerLevel(549));
        assertEquals(4, SuitabilityData.powerLevel(600)); // Dragonite
        assertEquals(5, SuitabilityData.powerLevel(670)); // Kyogre
        assertEquals(5, SuitabilityData.powerLevel(720)); // Arceus
    }

    private static final String WATER_TYPES = "{\"types\": {\"water\": 1}}";

    private static Form form(int baseStatTotal, String... types) {
        return new Form(List.of(types), baseStatTotal);
    }

    @Test
    void anEvolutionLineGetsBetterAsItEvolves() {
        SuitabilityData data = load(Map.of("palworks:watering", WATER_TYPES));
        Form squirtle = form(314, "water");
        Form wartortle = form(405, "water");
        Form blastoise = form(530, "water");
        assertEquals(Map.of(WATERING, 1), data.levels("cobblemon:squirtle", List.of(squirtle)));
        assertEquals(Map.of(WATERING, 2), data.levels("cobblemon:wartortle", List.of(wartortle, squirtle)));
        assertEquals(Map.of(WATERING, 3), data.levels("cobblemon:blastoise", List.of(blastoise, wartortle, squirtle)));
    }

    @Test
    void strongPokemonThatDontEvolveGetHighLevels() {
        SuitabilityData data = load(Map.of("palworks:watering", WATER_TYPES));
        assertEquals(Map.of(WATERING, 3), data.levels("cobblemon:lapras", List.of(form(535, "water", "ice"))));
        assertEquals(Map.of(WATERING, 5), data.levels("cobblemon:kyogre", List.of(form(670, "water"))));
    }

    @Test
    void aTypeGainedLateInTheLineGivesLess() {
        SuitabilityData data = load(Map.of(
                "palworks:kindling", "{\"types\": {\"fire\": 1}}",
                "palworks:nether", "{\"types\": {\"ghost\": 1}}",
                "palworks:watering", WATER_TYPES,
                "palworks:transporting", "{\"types\": {\"flying\": 1}}"));
        // Hisuian Typhlosion: Cyndaquil and Quilava were Fire, but not Ghost
        assertEquals(
                Map.of(KINDLING, 3, NETHER, 1),
                data.levels(
                        "cobblemon:typhlosion",
                        List.of(form(534, "fire", "ghost"), form(405, "fire"), form(309, "fire"))));
        // Vaporeon: Eevee was Normal
        assertEquals(
                Map.of(WATERING, 2),
                data.levels("cobblemon:vaporeon", List.of(form(525, "water"), form(325, "normal"))));
        // Gyarados: Magikarp was Water, but not Flying
        assertEquals(
                Map.of(WATERING, 3, TRANSPORTING, 2),
                data.levels("cobblemon:gyarados", List.of(form(540, "water", "flying"), form(200, "water"))));
    }

    @Test
    void aLevelIsNeverBelowOne() {
        SuitabilityData data = load(Map.of("palworks:watering", WATER_TYPES));
        // Marill (250): Azurill was Normal/Fairy
        assertEquals(
                Map.of(WATERING, 1),
                data.levels("cobblemon:marill", List.of(form(250, "water", "fairy"), form(190, "normal", "fairy"))));
    }

    @Test
    void aTypesNumberAddsToThePowerLevel() {
        SuitabilityData data = load(Map.of("palworks:watering", "{\"types\": {\"water\": 2}}"));
        assertEquals(Map.of(WATERING, 4), data.levels("cobblemon:blastoise", List.of(form(530, "water"))));
    }

    @Test
    void badFilesAndEntriesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        SuitabilityData data = load(
                Map.of(
                        "palworks:kindling", "{\"types\": {\"fire\": 9, \"water\": \"lots\"}, \"pokemon\": {}}",
                        "palworks:juggling", "{\"types\": {\"normal\": 1}}",
                        "palworks:cooling", "[1, 2]",
                        "palworks:watering", "{\"types\": [\"water\"]}",
                        "palworks:mining", "{\"types\": {\"rock\": 0}}"),
                warnings);

        assertEquals(Map.of(KINDLING, SuitabilityData.MAX_LEVEL), levels(data, List.of("fire"), "x:y"));
        assertEquals(Map.of(), levels(data, List.of("water"), "x:y"));
        assertEquals(Map.of(), levels(data, List.of("normal"), "x:y"));
        // 0 only means "take it away" for species; for a type it's raised to 1.
        assertEquals(Map.of(MINING, 1), levels(data, List.of("rock"), "x:y"));
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
                    for (Path file :
                            jobFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
                        String name = file.getFileName().toString();
                        files.put(
                                namespace.getFileName() + ":" + name.substring(0, name.length() - ".json".length()),
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
        assertEquals(Map.of(FARMING, 1), levels(data, List.of("normal"), "x:y"));
        assertEquals(Map.of(KINDLING, 1), levels(data, List.of("fire"), "x:y"));
        assertEquals(Map.of(WATERING, 1), levels(data, List.of("water"), "x:y"));
        assertEquals(Map.of(PLANTING, 1, GATHERING, 1), levels(data, List.of("grass"), "x:y"));
        assertEquals(Map.of(GENERATING_ELECTRICITY, 1), levels(data, List.of("electric"), "x:y"));
        assertEquals(Map.of(COOLING, 1), levels(data, List.of("ice"), "x:y"));
        assertEquals(Map.of(LUMBERING, 1), levels(data, List.of("fighting"), "x:y"));
        assertEquals(Map.of(MINING, 1), levels(data, List.of("rock"), "x:y"));
        assertEquals(Map.of(MINING, 1), levels(data, List.of("ground"), "x:y"));
        assertEquals(Map.of(TRANSPORTING, 1), levels(data, List.of("flying"), "x:y"));
        assertEquals(Map.of(MEDICINE_PRODUCTION, 1), levels(data, List.of("psychic"), "x:y"));
        assertEquals(Map.of(NETHER, 1), levels(data, List.of("ghost"), "x:y"));
        // Steel isn't Lumbering: most Steel types have nothing to cut with (Magnemite, Klink,
        // Bronzor); the blade users are species entries.
        for (String none : List.of("poison", "bug", "dragon", "dark", "fairy", "steel")) {
            assertEquals(Map.of(), levels(data, List.of(none), "x:y"), none);
        }
        // Rock/Ground: both types give Mining, at the same level.
        assertEquals(Map.of(MINING, 1), levels(data, List.of("rock", "ground"), "cobblemon:geodude"));
    }

    /** A Pokémon's levels from its own form alone: its types and base stat total. */
    private static Map<Suitability, Integer> levels(
            SuitabilityData data, List<String> types, String species, int baseStatTotal) {
        return data.levels(species, List.of(new Form(types, baseStatTotal)));
    }

    @Test
    void shippedSpeciesFollowTheirStats() throws IOException {
        SuitabilityData data = shipped();
        assertEquals(80, data.speciesCount());
        // Fighting gives Lumbering; Machamp's species entry adds Handiwork. Both from its stats.
        assertEquals(Map.of(LUMBERING, 3, HANDIWORK, 3), levels(data, List.of("fighting"), "cobblemon:machamp", 505));
        assertEquals(Map.of(LUMBERING, 2, HANDIWORK, 2), levels(data, List.of("fighting"), "cobblemon:machoke", 405));
        assertEquals(Map.of(LUMBERING, 1, HANDIWORK, 1), levels(data, List.of("fighting"), "cobblemon:machop", 305));
        assertEquals(Map.of(LUMBERING, 3), levels(data, List.of("bug", "steel"), "cobblemon:scizor", 500));
        assertEquals(
                Map.of(LUMBERING, 3, TRANSPORTING, 3),
                levels(data, List.of("bug", "flying"), "cobblemon:scyther", 500));
        assertEquals(Map.of(LUMBERING, 3, MINING, 3), levels(data, List.of("bug", "rock"), "cobblemon:kleavor", 500));
        assertEquals(Map.of(LUMBERING, 4), levels(data, List.of("dark", "steel"), "cobblemon:kingambit", 550));
        assertEquals(
                Map.of(LUMBERING, 3, NETHER, 3), levels(data, List.of("steel", "ghost"), "cobblemon:aegislash", 500));
        assertEquals(
                Map.of(PLANTING, 4, GATHERING, 4, LUMBERING, 4),
                levels(data, List.of("grass", "steel"), "cobblemon:kartana", 570));
        assertEquals(Map.of(LUMBERING, 3), levels(data, List.of("dragon"), "cobblemon:haxorus", 540));
        assertEquals(
                Map.of(LUMBERING, 2, WATERING, 2, FARMING, 2),
                levels(data, List.of("normal", "water"), "cobblemon:bibarel", 410));
        assertEquals(Map.of(FARMING, 3), levels(data, List.of("normal"), "cobblemon:miltank", 490));
        assertEquals(Map.of(FARMING, 3), levels(data, List.of("normal"), "cobblemon:chansey", 450));
        assertEquals(
                Map.of(PLANTING, 3, GATHERING, 3, TRANSPORTING, 3, FARMING, 3),
                levels(data, List.of("grass", "flying"), "cobblemon:tropius", 460));
        // Slaking's stats are a legendary's, so it's level 5 at what it does
        assertEquals(Map.of(FARMING, 5, HANDIWORK, 5), levels(data, List.of("normal"), "cobblemon:slaking", 670));
        // Steel alone gives nothing
        assertEquals(
                Map.of(GENERATING_ELECTRICITY, 1),
                levels(data, List.of("electric", "steel"), "cobblemon:magnemite", 325));
        assertTrue(levels(data, List.of("water"), "cobblemon:lucario").containsKey(HANDIWORK));
    }

    @Test
    void speciesOverridesGoOverTheFiles() {
        SuitabilityData files = load(Map.of(
                "palworks:planting", "{\"types\": {\"grass\": 1}}",
                "palworks:handiwork", "{\"species\": {\"machamp\": 3}}"));
        SuitabilityData data = files.withSpecies(Map.of(
                "cobblemon:bulbasaur", Map.of(WATERING, 3, PLANTING, 0),
                "cobblemon:machamp", Map.of(LUMBERING, 2)));

        // Watering added, Planting (from its type) taken away
        assertEquals(Map.of(WATERING, 3), levels(data, List.of("grass", "poison"), "cobblemon:bulbasaur"));
        // The file's species level is kept beside the override
        assertEquals(Map.of(HANDIWORK, 3, LUMBERING, 2), levels(data, List.of("fighting"), "cobblemon:machamp"));
        // Other species, and the data it was made from, are as they were
        assertEquals(Map.of(PLANTING, 1), levels(data, List.of("grass"), "cobblemon:oddish"));
        assertEquals(Map.of(PLANTING, 1), levels(files, List.of("grass", "poison"), "cobblemon:bulbasaur"));
    }

    @Test
    void anOverrideReplacesTheFilesLevelForItsJob() {
        SuitabilityData data = load(Map.of("palworks:handiwork", "{\"species\": {\"machamp\": 3}}"))
                .withSpecies(Map.of("cobblemon:machamp", Map.of(HANDIWORK, 1)));
        assertEquals(Map.of(HANDIWORK, 1), levels(data, List.of("fighting"), "cobblemon:machamp"));
    }

    @Test
    void anOverrideIsUsedAsItIsWhateverTheStats() {
        SuitabilityData data = load(Map.of(
                        "palworks:farming", "{\"types\": {\"normal\": 1}, \"species\": {\"slaking\": 1}}"))
                .withSpecies(Map.of("cobblemon:slaking", Map.of(FARMING, 2)));
        assertEquals(Map.of(FARMING, 2), levels(data, List.of("normal"), "cobblemon:slaking", 670));
    }
}
