package dev.palworks.hunger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** How trough food values are read from datapack files, and the file the mod ships. */
class TroughFoodTest {
    private static final Path DATA = Path.of("src/main/resources/data");

    private static TroughFood load(Map<String, String> files, List<String> warnings) {
        Map<String, JsonElement> parsed = new HashMap<>();
        files.forEach((id, text) -> parsed.put(id, JsonParser.parseString(text)));
        return TroughFood.fromFiles(parsed, warnings::add);
    }

    @Test
    void itemsAndTagsAreRead() {
        List<String> warnings = new ArrayList<>();
        TroughFood food = load(
                Map.of(
                        "a:food",
                        "{\"values\": [{\"item\": \"cobblemon:sitrus_berry\", \"fullness\": 4},"
                                + " {\"tag\": \"#cobblemon:berries\", \"fullness\": 2}]}"),
                warnings);
        assertEquals(List.of(), warnings);
        assertEquals(4, food.valueForItem(ResourceLocation.parse("cobblemon:sitrus_berry")));
        assertEquals(List.of(ResourceLocation.parse("cobblemon:berries")), food.tagOrder());
    }

    @Test
    void tagsAreTriedInFileOrderAndTheFirstItemValueWins() {
        List<String> warnings = new ArrayList<>();
        TroughFood food = load(
                Map.of(
                        "b:food",
                                "{\"values\": [{\"tag\": \"b:snacks\", \"fullness\": 3}, {\"item\": \"x:y\", \"fullness\": 3}]}",
                        "a:food",
                                "{\"values\": [{\"tag\": \"a:snacks\", \"fullness\": 2}, {\"item\": \"x:y\", \"fullness\": 2}]}"),
                warnings);
        assertEquals(List.of(ResourceLocation.parse("a:snacks"), ResourceLocation.parse("b:snacks")), food.tagOrder());
        assertEquals(2, food.valueForItem(ResourceLocation.parse("x:y")));
    }

    @Test
    void badEntriesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        TroughFood food = load(
                Map.of(
                        "a:food",
                        "{\"values\": [{\"item\": \"x:none\"}, {\"item\": \"x:zero\", \"fullness\": 0},"
                                + " {\"item\": \"x:both\", \"tag\": \"x:t\", \"fullness\": 1},"
                                + " {\"item\": \"Not An Id\", \"fullness\": 1}, 5,"
                                + " {\"item\": \"x:good\", \"fullness\": 1}]}",
                        "b:food",
                        "[1, 2]"),
                warnings);
        assertEquals(6, warnings.size(), warnings.toString());
        assertEquals(1, food.valueForItem(ResourceLocation.parse("x:good")));
        assertNull(food.valueForItem(ResourceLocation.parse("x:zero")));
        assertEquals(1, food.itemCount());
    }

    @Test
    void theShippedFileLoadsCleanly() throws IOException {
        Path file = DATA.resolve("palworks/palworks/trough_food/cobblemon.json");
        List<String> warnings = new ArrayList<>();
        TroughFood food = load(Map.of("palworks:cobblemon", Files.readString(file)), warnings);
        assertEquals(List.of(), warnings);
        // Berries and Aprijuice are a point each, as when hand-fed
        assertEquals(
                List.of(ResourceLocation.parse("cobblemon:berries"), ResourceLocation.parse("cobblemon:aprijuices")),
                food.tagOrder());
    }
}
