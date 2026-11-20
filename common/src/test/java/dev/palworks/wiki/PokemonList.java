package dev.palworks.wiki;

import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.wiki.CobblemonSpecies.Entry;
import dev.palworks.wiki.CobblemonSpecies.Listed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A CSV of every Cobblemon species and form (implemented or not, battle-only or not), with the work
 * skill level the shipped suitability files give each, and empty columns for proposed levels and
 * notes, filled in by hand. Run by {@code :common:generatePokemonList} into the reference library, to
 * check the suitability files against every Pokémon. Regenerating it refreshes everything but the
 * hand-kept columns ({@code proposed_<skill>} and {@code notes}), which stay with their row (by
 * species and form); it refuses to drop a row that has any.
 */
final class PokemonList {
    static final String PROPOSED = "proposed_";
    static final String NOTES = "notes";
    private static final List<String> FIXED =
            List.of("dex", "species", "name", "form", "implemented", "battle_only", "types", "base_stat_total");

    private static final char QUOTE = '"';
    private static final char COMMA = ',';
    private static final char CR = '\r';
    private static final char LF = '\n';

    private PokemonList() {}

    /** Arguments: the Cobblemon jar, the CSV to write (and to keep the hand-kept columns from). */
    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path csv = Path.of(args[1]);
        Map<String, Map<String, String>> kept =
                Files.exists(csv) ? kept(parse(Files.readString(csv, StandardCharsets.UTF_8))) : Map.of();
        List<List<String>> rows = rows(
                CobblemonSpecies.all(CobblemonSpecies.readJar(jar)),
                WikiGenerator.dataFrom(WikiGenerator.shippedFiles()),
                kept);
        Files.createDirectories(csv.toAbsolutePath().getParent());
        Files.writeString(csv, format(rows), StandardCharsets.UTF_8);
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

    /**
     * The header and a row for each of these, its current levels from {@code data} (blank where it
     * has none), its hand-kept columns from {@code kept} (by {@link #key}).
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
        List<String> lost = new ArrayList<>();
        kept.forEach((key, hand) -> {
            if (!keys.contains(key) && hand.values().stream().anyMatch(v -> !v.isBlank())) {
                lost.add(key);
            }
        });
        if (!lost.isEmpty()) {
            throw new IllegalStateException(
                    "These rows have proposals or notes but aren't in Cobblemon now; move them first: " + lost);
        }
        return rows;
    }

    /** A row's key: its species id and form name ({@code cobblemon:typhlosion|Hisui}, or {@code ...|}). */
    static String key(String species, String form) {
        return species + "|" + (form == null ? "" : form);
    }

    /** The hand-kept columns of a parsed CSV (header first), by row key. */
    static Map<String, Map<String, String>> kept(List<List<String>> csv) {
        Map<String, Map<String, String>> kept = new HashMap<>();
        if (csv.isEmpty()) {
            return kept;
        }
        List<String> header = csv.get(0);
        int species = header.indexOf("species");
        int form = header.indexOf("form");
        if (species < 0 || form < 0) {
            throw new IllegalStateException("No species or form column in the old list: " + header);
        }
        for (List<String> row : csv.subList(1, csv.size())) {
            if (row.size() <= Math.max(species, form)) {
                continue; // a blank line a spreadsheet left
            }
            Map<String, String> hand = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < row.size(); i++) {
                String column = header.get(i);
                if (column.startsWith(PROPOSED) || NOTES.equals(column)) {
                    hand.put(column, row.get(i));
                }
            }
            kept.put(key(row.get(species), row.get(form).isEmpty() ? null : row.get(form)), hand);
        }
        return kept;
    }

    /** Rows as CSV: comma-separated, a field quoted when it has a comma, quote or line break. */
    static String format(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                String field = row.get(i);
                if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
                    out.append('"').append(field.replace("\"", "\"\"")).append('"');
                } else {
                    out.append(field);
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** CSV as rows, as {@link #format} writes it or a spreadsheet saves it (quoted fields, CRLF). */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            int step = 1;
            if (quoted) {
                if (c == QUOTE && next == QUOTE) {
                    field.append(QUOTE); // "" inside quotes
                    step = 2;
                } else if (c == QUOTE) {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == QUOTE) {
                quoted = true;
            } else if (c == COMMA) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == LF || c == CR) {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                step = c == CR && next == LF ? 2 : 1;
            } else {
                field.append(c);
            }
            i += step;
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
