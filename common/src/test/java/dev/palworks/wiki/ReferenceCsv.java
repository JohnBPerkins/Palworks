package dev.palworks.wiki;

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
import java.util.function.Predicate;

/**
 * The reference library's generated lists (PokemonList, JobList): CSV a spreadsheet opens, some of
 * whose columns are filled in by hand. Regenerating one keeps those cells with their row (found by
 * its key columns, whatever order the columns are in), and refuses to drop a row that has any.
 */
final class ReferenceCsv {
    private static final char QUOTE = '"';
    private static final char COMMA = ',';
    private static final char CR = '\r';
    private static final char LF = '\n';

    private ReferenceCsv() {}

    /** A row's key: its key columns' values, joined ({@code cobblemon:typhlosion|Hisui}); a null is blank. */
    static String key(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            parts.add(value == null ? "" : value);
        }
        return String.join("|", parts);
    }

    /** The hand-filled cells of the list at {@code csv} (none if it isn't there yet): see {@link #kept(List, List, Predicate)}. */
    static Map<String, Map<String, String>> read(Path csv, List<String> keyColumns, Predicate<String> handFilled)
            throws IOException {
        return Files.exists(csv)
                ? kept(parse(Files.readString(csv, StandardCharsets.UTF_8)), keyColumns, handFilled)
                : Map.of();
    }

    /** Write rows (header first) to {@code csv}, making its folder if needed. */
    static void write(Path csv, List<List<String>> rows) throws IOException {
        Files.createDirectories(csv.toAbsolutePath().getParent());
        Files.writeString(csv, format(rows), StandardCharsets.UTF_8);
    }

    /**
     * The cells of the {@code handFilled} columns of a parsed CSV (header first), by each row's key
     * ({@link #key} of its {@code keyColumns}).
     */
    static Map<String, Map<String, String>> kept(
            List<List<String>> csv, List<String> keyColumns, Predicate<String> handFilled) {
        Map<String, Map<String, String>> kept = new HashMap<>();
        if (csv.isEmpty()) {
            return kept;
        }
        List<String> header = csv.get(0);
        int[] keyAt = new int[keyColumns.size()];
        int widest = 0;
        for (int k = 0; k < keyAt.length; k++) {
            keyAt[k] = header.indexOf(keyColumns.get(k));
            if (keyAt[k] < 0) {
                throw new IllegalStateException("No " + keyColumns.get(k) + " column in the old list: " + header);
            }
            widest = Math.max(widest, keyAt[k]);
        }
        for (List<String> row : csv.subList(1, csv.size())) {
            if (row.size() <= widest) {
                continue; // a blank line a spreadsheet left
            }
            Map<String, String> hand = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < row.size(); i++) {
                if (handFilled.test(header.get(i))) {
                    hand.put(header.get(i), row.get(i));
                }
            }
            String[] values = new String[keyAt.length];
            for (int k = 0; k < keyAt.length; k++) {
                values[k] = row.get(keyAt[k]);
            }
            kept.put(key(values), hand);
        }
        return kept;
    }

    /**
     * Fails if a kept row with anything filled in isn't among {@code keys} (the rows written now):
     * its cells would be lost.
     */
    static void checkNoneLost(Map<String, Map<String, String>> kept, Set<String> keys, String what) {
        List<String> lost = new ArrayList<>();
        kept.forEach((key, hand) -> {
            if (!keys.contains(key) && hand.values().stream().anyMatch(v -> !v.isBlank())) {
                lost.add(key);
            }
        });
        if (!lost.isEmpty()) {
            throw new IllegalStateException(
                    "These rows have cells filled in but aren't " + what + " now; move or clear them first: " + lost);
        }
    }

    /** Rows as CSV: comma-separated, a field quoted when it has a comma, quote or line break. */
    static String format(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(COMMA);
                }
                String field = row.get(i);
                if (field.indexOf(COMMA) >= 0
                        || field.indexOf(QUOTE) >= 0
                        || field.indexOf(LF) >= 0
                        || field.indexOf(CR) >= 0) {
                    out.append(QUOTE).append(field.replace("\"", "\"\"")).append(QUOTE);
                } else {
                    out.append(field);
                }
            }
            out.append(LF);
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
