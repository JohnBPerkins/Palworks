package dev.palworks.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Every wood's trough has its blockstate, models, recipe, loot table and name. */
class TroughFilesTest {
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** {@code <path>.json} is there. */
    private static void assertJson(String path) {
        String file = path + ".json";
        assertTrue(Files.isRegularFile(RESOURCES.resolve(file)), "missing " + file);
    }

    @Test
    void everyWoodHasItsFiles() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(RESOURCES.resolve("assets/palworks/lang/en_us.json")))
                .getAsJsonObject();
        for (String wood : ModBlocks.WOODS) {
            String trough = wood + "_trough";
            assertJson("assets/palworks/blockstates/" + trough);
            assertJson("assets/palworks/models/block/" + trough + "_base");
            assertJson("assets/palworks/models/block/" + trough + "_wall");
            assertJson("assets/palworks/models/item/" + trough);
            assertJson("data/palworks/recipe/" + trough);
            assertJson("data/palworks/loot_table/blocks/" + trough);
            assertTrue(lang.has("block.palworks." + trough), "no name for the " + wood + " trough");
        }
        for (int level = 1; level <= TroughFeed.MAX_FILL; level++) {
            assertJson("assets/palworks/models/block/trough_fill_" + level);
        }
    }
}
