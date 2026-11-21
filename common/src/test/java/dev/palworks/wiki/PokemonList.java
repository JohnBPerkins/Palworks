package dev.palworks.wiki;

import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.wiki.CobblemonSpecies.Entry;
import dev.palworks.wiki.CobblemonSpecies.Listed;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A CSV of every Cobblemon species and form (implemented or not, battle-only or not), with the work
 * skill level the shipped suitability files give each, and empty columns for proposed levels and
 * notes, filled in by hand. Run by {@code :common:generatePokemonList} into the reference library, to
 * check the suitability files against every Pokémon. Regenerating it refreshes everything but the
 * hand-filled columns ({@code proposed_<skill>} and {@code notes}), which stay with their row (by
 * species and form; see ReferenceCsv).
 */
final class PokemonList {
    static final String PROPOSED = "proposed_";
    static final String NOTES = "notes";
    private static final List<String> FIXED =
            List.of("dex", "species", "name", "form", "implemented", "battle_only", "types", "base_stat_total");
    private static final List<String> KEY = List.of("species", "form");

    private PokemonList() {}

    /** Arguments: the Cobblemon jar, the CSV to write (and to keep the hand-filled columns from). */
    public static void main(String[] args) throws IOException {
        Path csv = Path.of(args[1]);
        ReferenceCsv.write(
                csv,
                rows(
                        CobblemonSpecies.all(CobblemonSpecies.readJar(Path.of(args[0]))),
                        WikiGenerator.dataFrom(WikiGenerator.shippedFiles()),
                        ReferenceCsv.read(csv, KEY, PokemonList::isHandFilled)));
    }

    /** The header: the fixed columns, each skill's current level, each skill's proposed level, notes. */
    static List<String> header() {
        List<String> header = new ArrayList<>(FIXED);
        for (Suitability job : Suitability.values()) {
            header.add(job.getId());
        }
        for (Suitability job : Suitability.values()) {
            header.add(PROPOSED + job.getId());
        }
        header.add(NOTES);
        return header;
    }

    private static boolean isHandFilled(String column) {
        return column.startsWith(PROPOSED) || NOTES.equals(column);
    }

    /**
     * The header and a row for each of these, its current levels from {@code data} (blank where it
     * has none), its hand-filled columns from {@code kept} (by {@link #key}).
     *
     * @throws IllegalStateException if a kept row with anything in it has no species or form now
     */
    static List<List<String>> rows(List<Listed> all, SuitabilityData data, Map<String, Map<String, String>> kept) {
        List<String> header = header();
        List<List<String>> rows = new ArrayList<>();
        rows.add(header);
        Set<String> keys = new TreeSet<>();
        for (Listed listed : all) {
            Entry entry = listed.entry();
            String key = key(entry.id(), entry.form());
            if (!keys.add(key)) {
                throw new IllegalStateException("Two rows for " + key);
            }
            Map<Suitability, Integer> levels = data.levels(entry.id(), entry.line());
            Map<String, String> hand = kept.getOrDefault(key, Map.of());
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(entry.dex()));
            row.add(entry.id());
            row.add(entry.name());
            row.add(entry.form() == null ? "" : entry.form());
            row.add(String.valueOf(listed.implemented()));
            row.add(String.valueOf(listed.battleOnly()));
            row.add(String.join("/", entry.types()));
            row.add(String.valueOf(entry.baseStatTotal()));
            for (Suitability job : Suitability.values()) {
                Integer level = levels.get(job);
                row.add(level == null || level == 0 ? "" : level.toString());
            }
            for (int i = row.size(); i < header.size(); i++) {
                row.add(hand.getOrDefault(header.get(i), ""));
            }
            rows.add(row);
        }
        ReferenceCsv.checkNoneLost(kept, keys, "in Cobblemon");
        return rows;
    }

    /** A row's key: its species id and form name ({@code cobblemon:typhlosion|Hisui}, or {@code ...|}). */
    static String key(String species, String form) {
        return ReferenceCsv.key(species, form);
    }

    /** The hand-filled columns of a parsed list (header first), by row key. */
    static Map<String, Map<String, String>> kept(List<List<String>> csv) {
        return ReferenceCsv.kept(csv, KEY, PokemonList::isHandFilled);
    }
}
