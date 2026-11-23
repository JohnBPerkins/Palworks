package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals") // block ids and file ids read better inline
class BoulderKindsTest {
    private static final Path DATA = Path.of("src/main/resources/data/palworks/palworks");

    private static BoulderKinds kinds(Map<String, String> files, List<String> warnings) {
        Map<String, JsonElement> json = new HashMap<>();
        files.forEach((id, text) -> json.put(id, JsonParser.parseString(text)));
        return BoulderKinds.fromFiles(json, id -> !id.contains("nonsense"), warnings::add);
    }

    @Test
    void aKindIsReadWithItsWeightsInOrder() {
        List<String> warnings = new ArrayList<>();
        BoulderKinds kinds = kinds(
                Map.of(
                        "palworks:badlands",
                        "{\"types\": [\"Ground\"], \"species\": [\"groudon\"],"
                                + " \"stone\": {\"terracotta\": 4, \"minecraft:granite\": 2},"
                                + " \"ores\": {\"gold_ore\": 4}}"),
                warnings);
        BoulderKind kind = kinds.get("badlands");
        assertEquals(List.of(), warnings);
        assertEquals(java.util.Set.of("ground"), kind.types());
        assertEquals(java.util.Set.of("cobblemon:groudon"), kind.species());
        assertEquals(
                List.of("minecraft:terracotta", "minecraft:granite"),
                List.copyOf(kind.stone().keySet()));
        assertEquals(Map.of("minecraft:gold_ore", 4), kind.ores());
    }

    @Test
    void badEntriesAreWarnedAboutAndLeftOut() {
        List<String> warnings = new ArrayList<>();
        BoulderKinds kinds = kinds(
                Map.of(
                        "palworks:a",
                        "{\"types\": \"rock\", \"stone\": {\"stone\": 1.5, \"nonsense\": 2, \"tuff\": -1, \"granite\": 1},"
                                + " \"colour\": 3}",
                        "palworks:b",
                        "{\"stone\": {}}",
                        "palworks:c",
                        "[1]"),
                warnings);
        // a: types not a list, stone 1.5, nonsense, tuff -1, colour; b: no stone; c: not an object
        assertEquals(7, warnings.size(), String.join("\n", warnings));
        assertEquals(Map.of("minecraft:granite", 1), kinds.get("a").stone());
        assertNull(kinds.get("b"));
        assertNull(kinds.get("c"));
    }

    @Test
    void theSameKindFromSeveralNamespacesMerges() {
        List<String> warnings = new ArrayList<>();
        BoulderKinds kinds = kinds(
                Map.of(
                        "palworks:stone",
                        "{\"types\": [\"rock\"], \"stone\": {\"stone\": 5, \"tuff\": 1}, \"ores\": {\"coal_ore\": 4}}",
                        "other:stone",
                        "{\"types\": [\"normal\"], \"stone\": {\"stone\": 2, \"tuff\": 0, \"basalt\": 1}}"),
                warnings);
        BoulderKind stone = kinds.get("stone");
        assertEquals(java.util.Set.of("rock", "normal"), stone.types());
        // The higher weight; 0 takes a block out
        assertEquals(Map.of("minecraft:stone", 5, "minecraft:basalt", 1), stone.stone());
        assertEquals(Map.of("minecraft:coal_ore", 4), stone.ores());

        BoulderKinds replaced = kinds(
                Map.of(
                        "palworks:stone",
                        "{\"types\": [\"rock\"], \"stone\": {\"stone\": 5}}",
                        "other:stone",
                        "{\"types\": [\"rock\"], \"stone\": {\"basalt\": 1}, \"replace\": true}"),
                warnings);
        assertEquals(Map.of("minecraft:basalt", 1), replaced.get("stone").stone());
    }

    @Test
    void aWorkersKindGoesBySpeciesThenItsOtherTypeThenItsFirst() throws IOException {
        BoulderKinds kinds = shippedKinds();
        assertEquals(
                "badlands",
                kinds.kindFor("cobblemon:geodude", List.of("rock", "ground")).id());
        assertEquals(
                "badlands",
                kinds.kindFor("cobblemon:onix", List.of("ground", "rock")).id());
        assertEquals(
                "copper_vein",
                kinds.kindFor("cobblemon:geodude", List.of("rock", "electric")).id());
        assertEquals(
                "stone", kinds.kindFor("cobblemon:roggenrola", List.of("rock")).id());
        // A species listed goes over its types
        assertEquals("salt", kinds.kindFor("cobblemon:nacli", List.of("rock")).id());
        assertEquals("volcanic", kinds.kindFor("groudon", List.of("ground")).id());
        assertEquals(
                "iron_vein",
                kinds.kindFor("cobblemon:nosepass", List.of("rock")).id());
        // A type no kind is for falls back to stone
        assertEquals("stone", kinds.kindFor("x:y", List.of("normal")).id());
        assertNull(BoulderKinds.EMPTY.kindFor("x:y", List.of("rock")));
    }

    @Test
    void everyTypeARockTypeCanHaveHasItsOwnKind() throws IOException {
        BoulderKinds kinds = shippedKinds();
        List<String> types = List.of(
                "ground",
                "fire",
                "water",
                "flying",
                "bug",
                "steel",
                "electric",
                "ice",
                "psychic",
                "grass",
                "dragon",
                "poison",
                "dark",
                "fairy");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String type : types) {
            BoulderKind kind = kinds.kindFor("x:y", List.of("rock", type));
            assertTrue(!"stone".equals(kind.id()), type + " has a kind of its own");
            ids.add(kind.id());
        }
        assertEquals(types.size(), ids.size(), "one kind per type");
        assertEquals(16, kinds.all().size());
    }

    @Test
    void oreLevelsAreOneTable() throws IOException {
        List<String> warnings = new ArrayList<>();
        BoulderOreLevels levels = BoulderOreLevels.fromFiles(
                Map.of(
                        "palworks:ore_levels",
                                JsonParser.parseString("{\"gold_ore\": 3, \"iron_ore\": 9, \"x\": \"y\"}"),
                        "other:more", JsonParser.parseString("{\"minecraft:gold_ore\": 2}")),
                id -> true,
                warnings::add);
        assertEquals(3, levels.levelOf("minecraft:gold_ore"), "the highest of two files");
        assertEquals(5, levels.levelOf("minecraft:iron_ore"), "kept within 1 to 5");
        assertEquals(1, levels.levelOf("minecraft:coal_ore"), "not listed: level 1");
        assertEquals(1, warnings.size());

        BoulderOreLevels shipped = shippedOreLevels();
        assertEquals(1, shipped.levelOf("minecraft:coal_ore"));
        assertEquals(2, shipped.levelOf("minecraft:iron_ore"));
        assertEquals(3, shipped.levelOf("minecraft:gold_ore"));
        assertEquals(3, shipped.levelOf("minecraft:lapis_ore"));
        assertEquals(3, shipped.levelOf("minecraft:redstone_ore"));
        assertEquals(3, shipped.levelOf("minecraft:deepslate_gold_ore"), "a deepslate ore takes its ore's level");
        assertEquals(4, shipped.levelOf("minecraft:diamond_ore"));
        assertEquals(5, shipped.levelOf("minecraft:ancient_debris"));
    }

    @Test
    void everyOreInAShippedKindHasALevel() throws IOException {
        BoulderOreLevels levels = shippedOreLevels();
        for (BoulderKind kind : shippedKinds().all()) {
            for (String ore : kind.ores().keySet()) {
                assertTrue(levels.all().containsKey(ore), ore + " in " + kind.id() + " has no level");
            }
        }
    }

    @Test
    void anOreIsPickedOnlyFromThoseTheLevelHasUnlocked() throws IOException {
        BoulderOreLevels levels = shippedOreLevels();
        BoulderKind gem = shippedKinds().get("gem");
        Random random = new Random(2);
        for (int i = 0; i < 2000; i++) {
            String block = gem.pickOre(3, levels, random);
            assertTrue(!"minecraft:diamond_ore".equals(block) && !"minecraft:emerald_ore".equals(block), block);
        }
        BoulderKind iron = new BoulderKind(
                "test",
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of("minecraft:stone", 1),
                BoulderPattern.PATCHES,
                Map.of("minecraft:iron_ore", 1));
        assertNull(iron.pickOre(1, levels, random), "nothing unlocked at 1");
        assertEquals("minecraft:iron_ore", iron.pickOre(2, levels, random));
    }

    @Test
    void aKindsPatternIsReadAndPatchesIfNotGiven() throws IOException {
        List<String> warnings = new ArrayList<>();
        BoulderKinds kinds = kinds(
                Map.of(
                        "palworks:a", "{\"stone\": {\"stone\": 1}, \"pattern\": \"strata\"}",
                        "palworks:b", "{\"stone\": {\"stone\": 1}}",
                        "palworks:c", "{\"stone\": {\"stone\": 1}, \"pattern\": \"swirls\"}"),
                warnings);
        assertEquals(BoulderPattern.STRATA, kinds.get("a").pattern());
        assertEquals(BoulderPattern.PATCHES, kinds.get("b").pattern());
        assertEquals(BoulderPattern.PATCHES, kinds.get("c").pattern());
        assertEquals(1, warnings.size(), String.join("\n", warnings));
        assertEquals(BoulderPattern.STRATA, shippedKinds().get("badlands").pattern());
        assertEquals(BoulderPattern.SHELL, shippedKinds().get("geode").pattern());
    }

    private static BoulderKinds shippedKinds() throws IOException {
        return BoulderKinds.fromFiles(read(DATA.resolve("boulders")), id -> true, message -> {
            throw new AssertionError(message);
        });
    }

    private static BoulderOreLevels shippedOreLevels() throws IOException {
        return BoulderOreLevels.fromFiles(read(DATA.resolve("boulder_ores")), id -> true, message -> {
            throw new AssertionError(message);
        });
    }

    private static Map<String, JsonElement> read(Path directory) throws IOException {
        Map<String, JsonElement> files = new HashMap<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString().replace(".json", "");
                files.put("palworks:" + name, JsonParser.parseString(Files.readString(path)));
            }
        }
        return files;
    }
}
