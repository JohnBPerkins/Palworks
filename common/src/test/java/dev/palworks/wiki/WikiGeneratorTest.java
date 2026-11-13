package dev.palworks.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData.Form;
import dev.palworks.wiki.CobblemonSpecies.Entry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests reading Cobblemon's species files and building the wiki's pages from them. */
// Species files read better inline than as constants
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WikiGeneratorTest {

    private static final String STATS_300 =
            "\"baseStats\": {\"hp\": 50, \"attack\": 50, \"defence\": 50, \"special_attack\": 50,"
                    + " \"special_defence\": 50, \"speed\": 50}";
    private static final String STATS_534 =
            "\"baseStats\": {\"hp\": 78, \"attack\": 84, \"defence\": 78, \"special_attack\": 109,"
                    + " \"special_defence\": 85, \"speed\": 100}";

    private static Map<String, JsonObject> files(Map<String, String> texts) {
        Map<String, JsonObject> files = new HashMap<>();
        texts.forEach(
                (name, text) -> files.put(name, JsonParser.parseString(text).getAsJsonObject()));
        return files;
    }

    private static Entry find(List<Entry> entries, String displayName) {
        return entries.stream()
                .filter(e -> e.displayName().equals(displayName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + displayName + " in " + entries));
    }

    /** A Cyndaquil-like line, ending in a Fire species with a Fire/Ghost "Hisui" form. */
    private static Map<String, JsonObject> typhlosionLine() {
        return files(Map.of(
                "cyndaquil",
                "{\"implemented\": true, \"nationalPokedexNumber\": 155, \"name\": \"Cyndaquil\","
                        + " \"primaryType\": \"fire\", " + STATS_300 + "}",
                "typhlosion",
                "{\"implemented\": true, \"nationalPokedexNumber\": 157, \"name\": \"Typhlosion\","
                        + " \"primaryType\": \"fire\", " + STATS_534 + ", \"preEvolution\": \"cyndaquil\","
                        + " \"forms\": [{\"name\": \"Hisui\", \"primaryType\": \"fire\", \"secondaryType\": \"ghost\","
                        + " \"aspects\": [\"hisuian\"]},"
                        + " {\"name\": \"Mega\", \"primaryType\": \"fire\", \"battleOnly\": true}]}",
                "missingno",
                "{\"implemented\": false, \"nationalPokedexNumber\": 0, \"name\": \"MissingNo\","
                        + " \"primaryType\": \"normal\", " + STATS_300 + "}"));
    }

    @Test
    void speciesAndFormsComeWithTheirStatsTypesAndLine() {
        List<Entry> entries = CobblemonSpecies.entries(typhlosionLine());
        assertEquals(
                List.of("Cyndaquil", "Typhlosion", "Typhlosion (Hisui)"),
                entries.stream().map(Entry::displayName).toList(),
                "by Pokédex number, standard form first; no unimplemented species or battle-only forms");
        Entry hisuian = find(entries, "Typhlosion (Hisui)");
        assertEquals("cobblemon:typhlosion", hisuian.id());
        assertEquals(List.of("fire", "ghost"), hisuian.types());
        assertEquals(534, hisuian.baseStatTotal(), "a form without stats of its own has its species'");
        assertEquals(
                List.of(new Form(List.of("fire", "ghost"), 534), new Form(List.of("fire"), 300)),
                hisuian.line(),
                "the form's pre-evolution is its species'");
    }

    @Test
    void aFormWithJustAPrimaryTypeHasOnlyThatType() {
        List<Entry> entries = CobblemonSpecies.entries(
                files(
                        Map.of(
                                "slowpoke",
                                "{\"implemented\": true, \"nationalPokedexNumber\": 79, \"name\": \"Slowpoke\","
                                        + " \"primaryType\": \"water\", \"secondaryType\": \"psychic\", " + STATS_300
                                        + ","
                                        + " \"forms\": [{\"name\": \"Galar\", \"primaryType\": \"psychic\", \"aspects\": [\"galarian\"]}]}")));
        assertEquals(List.of("psychic"), find(entries, "Slowpoke (Galar)").types());
    }

    @Test
    void aPreEvolutionNamingAnAspectIsThatForm() {
        List<Entry> entries = CobblemonSpecies.entries(files(Map.of(
                "growlithe",
                "{\"implemented\": true, \"nationalPokedexNumber\": 58, \"name\": \"Growlithe\","
                        + " \"primaryType\": \"fire\", " + STATS_300 + ","
                        + " \"forms\": [{\"name\": \"Hisui\", \"primaryType\": \"fire\", \"secondaryType\": \"rock\","
                        + " \"aspects\": [\"hisuian\"]}]}",
                "arcanine",
                "{\"implemented\": true, \"nationalPokedexNumber\": 59, \"name\": \"Arcanine\","
                        + " \"primaryType\": \"fire\", " + STATS_534 + ", \"preEvolution\": \"growlithe\","
                        + " \"forms\": [{\"name\": \"Hisui\", \"primaryType\": \"fire\", \"secondaryType\": \"rock\","
                        + " \"aspects\": [\"hisuian\"], \"preEvolution\": \"growlithe hisuian\"}]}")));
        assertEquals(
                List.of(new Form(List.of("fire", "rock"), 534), new Form(List.of("fire", "rock"), 300)),
                find(entries, "Arcanine (Hisui)").line());
        assertEquals(
                List.of(new Form(List.of("fire"), 534), new Form(List.of("fire"), 300)),
                find(entries, "Arcanine").line());
    }

    private static WikiGenerator generator(List<Entry> entries) {
        return new WikiGenerator(entries, WikiGenerator.shippedFiles(), WikiGenerator.shippedLang(), "9.9.9");
    }

    @Test
    void eachSkillGetsAPageListingWhoCanDoItAndHowWell() {
        Map<String, String> pages =
                generator(CobblemonSpecies.entries(typhlosionLine())).generatedPages();
        assertEquals(Suitability.values().length + 1, pages.size(), "a page per skill and one of everyone");
        String kindling = pages.get("Kindling-Workers");
        assertTrue(kindling.contains("| 155 | Cyndaquil | Fire | 300 | 1 | Fire type |"), kindling);
        assertTrue(kindling.contains("| 157 | Typhlosion | Fire | 534 | 3 | Fire type |"), kindling);
        String nether = pages.get("Nether-Workers");
        // Ghost only came with the Hisuian form, after a Cyndaquil that wasn't Ghost: one level less
        assertTrue(nether.contains("| 157 | Typhlosion (Hisui) | Fire/Ghost | 534 | 2 | Ghost type |"), nether);
        assertTrue(kindling.startsWith("<!-- Generated"), "marked as generated");
        assertTrue(pages.get("Cooling-Workers").contains("No Pokémon can do it."));
    }

    @Test
    void aFormIsOnlyListedWhenItsSkillsDiffer() {
        Map<String, String> pages = generator(CobblemonSpecies.entries(files(Map.of(
                        "pikachu",
                        "{\"implemented\": true, \"nationalPokedexNumber\": 25, \"name\": \"Pikachu\","
                                + " \"primaryType\": \"electric\", " + STATS_300 + ","
                                + " \"forms\": [{\"name\": \"Partner\", \"primaryType\": \"electric\"}]}"))))
                .generatedPages();
        String everyone = pages.get(WikiGenerator.BY_POKEMON_PAGE);
        assertTrue(everyone.contains("| 25 | Pikachu |"), everyone);
        assertFalse(everyone.contains("Partner"), everyone);
    }

    @Test
    void speciesEntriesSayWhyAndOnesNotInCobblemonYetAreNamed() {
        String lumbering =
                generator(CobblemonSpecies.entries(Map.of())).generatedPages().get("Lumbering-Workers");
        assertTrue(lumbering.contains("Kartana"), "Kartana has an entry but isn't in this Cobblemon: " + lumbering);
        assertTrue(lumbering.contains("**From types:** Fighting."), lumbering);
    }

    @Test
    void markersAreFilledFromTheShippedFiles() {
        WikiGenerator generator = generator(List.of());
        String page = generator.fill("Before\n\n<!-- generated:type-table -->\n\nAfter\n", "Test.md");
        assertTrue(page.contains("| Fire | [Kindling](Kindling-Workers) |"), page);
        assertTrue(page.contains("| Grass | [Planting](Planting-Workers), [Gathering](Gathering-Workers) |"), page);
        assertTrue(page.contains("| Steel | none |"), page);
        assertTrue(page.startsWith("Before\n\n| Type |") && page.endsWith("|\n\nAfter\n"), page);
        assertTrue(generator
                .fill("<!-- generated:skill-table -->", "Test.md")
                .contains("| Lumbering | Fighting | 32 | [Every Pokémon](Lumbering-Workers) |"));
    }

    @Test
    void anUnknownMarkerIsAnError() {
        WikiGenerator generator = generator(List.of());
        assertThrows(IllegalArgumentException.class, () -> generator.fill("<!-- generated:typo -->", "Test.md"));
    }

    @Test
    void skillPagesAreNamedFromTheirIds() {
        assertEquals("Lumbering-Workers", WikiGenerator.skillPage(Suitability.LUMBERING));
        assertEquals("Generating-Electricity-Workers", WikiGenerator.skillPage(Suitability.GENERATING_ELECTRICITY));
    }
}
