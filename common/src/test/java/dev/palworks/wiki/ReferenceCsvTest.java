package dev.palworks.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Writing and reading back the reference library's lists, hand-filled cells and all. */
class ReferenceCsvTest {
    private static final String NOTES = "notes";
    private static final String HERE = "here";

    @Test
    void csvAsASpreadsheetSavesItReadsBack() {
        assertEquals(
                List.of(List.of("a", "b,c", "d\"e"), List.of("line\nbreak", "", "x")),
                ReferenceCsv.parse("a,\"b,c\",\"d\"\"e\"\r\n\"line\nbreak\",,x\r\n"));
        assertEquals(
                List.of(List.of("", "a\r\nb", "c")),
                ReferenceCsv.parse("\"\",\"a\r\nb\",c"),
                "an empty quoted field, and a line break kept inside quotes");
        List<List<String>> rows = List.of(List.of("a", "b,c", "d\"e"), List.of("line\nbreak", "", "x"));
        assertEquals(rows, ReferenceCsv.parse(ReferenceCsv.format(rows)));
    }

    @Test
    void handFilledCellsAreKeptByKeyWhereverTheirColumnsAre() {
        List<List<String>> csv = List.of(
                List.of(NOTES, "made", "id", "form"),
                List.of("hi", "x", "a", ""),
                List.of("", "y", "b", "f"),
                List.of());
        assertEquals(
                Map.of("a|", Map.of(NOTES, "hi"), "b|f", Map.of(NOTES, "")),
                ReferenceCsv.kept(csv, List.of("id", "form"), NOTES::equals),
                "only the hand-filled column, by id and form; a blank line is skipped");
    }

    @Test
    void aRowWithSomethingFilledInIsNeverDroppedSilently() {
        Map<String, Map<String, String>> kept = Map.of("gone", Map.of(NOTES, "keep me"), "empty", Map.of(NOTES, ""));
        assertThrows(IllegalStateException.class, () -> ReferenceCsv.checkNoneLost(kept, Set.of(HERE), HERE));
        ReferenceCsv.checkNoneLost(Map.of("empty", Map.of(NOTES, " ")), Set.of(HERE), HERE);
    }
}
