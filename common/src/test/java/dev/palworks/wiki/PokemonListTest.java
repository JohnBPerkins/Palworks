package dev.palworks.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.suitability.SuitabilityData;
import dev.palworks.wiki.CobblemonSpecies.Listed;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reference library's list of every Pokémon and form, its work skill levels and kept proposals. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // column names read better inline
class PokemonListTest {
    private static final SuitabilityData DATA = WikiGenerator.dataFrom(WikiGenerator.shippedFiles());

    private static List<String> row(List<List<String>> rows, String species, String form) {
        List<String> header = rows.get(0);
        return rows.stream()
                .filter(r -> r.get(header.indexOf("species")).equals(species)
                        && r.get(header.indexOf("form")).equals(form))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + species + " " + form));
    }

    private static String cell(List<List<String>> rows, List<String> row, String column) {
        return row.get(rows.get(0).indexOf(column));
    }

    @Test
    void everySpeciesAndFormIsListedUnimplementedAndBattleOnlyIncluded() {
        List<Listed> all = CobblemonSpecies.all(WikiGeneratorTest.typhlosionLine());
        assertEquals(
                List.of("MissingNo", "Cyndaquil", "Typhlosion", "Typhlosion (Hisui)", "Typhlosion (Mega)"),
                all.stream().map(l -> l.entry().displayName()).toList());
        assertFalse(all.get(0).implemented(), "MissingNo isn't in the game");
        assertTrue(all.get(4).battleOnly(), "a Mega is battle-only");
        assertFalse(all.get(3).battleOnly());
    }

    @Test
    void eachRowHasItsCurrentLevelsAndBlankProposals() {
        List<List<String>> rows =
                PokemonList.rows(CobblemonSpecies.all(WikiGeneratorTest.typhlosionLine()), DATA, Map.of());
        assertEquals(PokemonList.header(), rows.get(0));
        assertEquals(6, rows.size(), "the header and five Pokémon");
        List<String> hisui = row(rows, "cobblemon:typhlosion", "Hisui");
        assertEquals("fire/ghost", cell(rows, hisui, "types"));
        assertEquals("534", cell(rows, hisui, "base_stat_total"));
        // Fire gives Kindling and Ghost Nether work; nothing gives it Watering
        assertFalse(cell(rows, hisui, "kindling").isEmpty());
        assertFalse(cell(rows, hisui, "nether").isEmpty());
        assertEquals("", cell(rows, hisui, "watering"));
        assertEquals("", cell(rows, hisui, "proposed_kindling"));
        assertEquals("", cell(rows, hisui, "notes"));
        assertEquals("true", cell(rows, row(rows, "cobblemon:typhlosion", "Mega"), "battle_only"));
    }

    @Test
    void proposalsAndNotesStayWithTheirRowWhenItsRegenerated() {
        Map<String, Map<String, String>> kept = Map.of(
                PokemonList.key("cobblemon:typhlosion", "Hisui"),
                Map.of("proposed_kindling", "3", "notes", "a ghost, \"spooky\""),
                PokemonList.key("cobblemon:cyndaquil", null),
                Map.of("proposed_watering", ""));
        List<List<String>> rows =
                PokemonList.rows(CobblemonSpecies.all(WikiGeneratorTest.typhlosionLine()), DATA, kept);
        List<String> hisui = row(rows, "cobblemon:typhlosion", "Hisui");
        assertEquals("3", cell(rows, hisui, "proposed_kindling"));
        assertEquals("a ghost, \"spooky\"", cell(rows, hisui, "notes"));
        // Written out and read back, as the next run reads it
        Map<String, String> readBack = PokemonList.kept(PokemonList.parse(PokemonList.format(rows)))
                .get(PokemonList.key("cobblemon:typhlosion", "Hisui"));
        assertEquals("3", readBack.get("proposed_kindling"));
        assertEquals("a ghost, \"spooky\"", readBack.get("notes"));
        assertEquals("", readBack.get("proposed_watering"));
    }

    @Test
    void aRowWithProposalsIsNeverDroppedSilently() {
        Map<String, Map<String, String>> kept =
                Map.of(PokemonList.key("cobblemon:gone", null), Map.of("notes", "keep me"));
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> PokemonList.rows(CobblemonSpecies.all(WikiGeneratorTest.typhlosionLine()), DATA, kept));
        assertTrue(error.getMessage().contains("cobblemon:gone|"), error.getMessage());
        // One with nothing in it just goes
        PokemonList.rows(
                CobblemonSpecies.all(WikiGeneratorTest.typhlosionLine()),
                DATA,
                Map.of(PokemonList.key("cobblemon:gone", null), Map.of("notes", "")));
    }

    @Test
    void csvAsASpreadsheetSavesItReadsBack() {
        assertEquals(
                List.of(List.of("a", "b,c", "d\"e"), List.of("line\nbreak", "", "x")),
                PokemonList.parse("a,\"b,c\",\"d\"\"e\"\r\n\"line\nbreak\",,x\r\n"));
        assertEquals(
                List.of(List.of("", "a\r\nb", "c")),
                PokemonList.parse("\"\",\"a\r\nb\",c"),
                "an empty quoted field, and a line break kept inside quotes");
        List<List<String>> rows = List.of(List.of("a", "b,c", "d\"e"), List.of("line\nbreak", "", "x"));
        assertEquals(rows, PokemonList.parse(PokemonList.format(rows)));
    }
}
