package dev.palworks.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Every wood's trough and every bed has its blockstate, models, recipe, loot table and name. */
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
        // Straw, and each of the 16 wools, in 3 sizes
        assertEquals(17 * WorkerBedBlock.MAX_SIZE, ModBlocks.bedIds().size());
        for (String bed : ModBlocks.bedIds()) {
            assertJson("assets/palworks/models/block/" + bed.replaceFirst("^(large|huge)_", "") + "_floor");
            assertJson("assets/palworks/blockstates/" + bed);
            assertJson("assets/palworks/models/item/" + bed);
            assertJson("data/palworks/recipe/" + bed);
            assertJson("data/palworks/loot_table/blocks/" + bed);
            assertTrue(lang.has("block.palworks." + bed), "no name for the " + bed);
            if (bed.contains("_wool_")) {
                assertJson("data/palworks/recipe/dye_" + bed); // dyed from another colour's
            }
        }
        for (int level = 1; level <= TroughFeed.MAX_FILL; level++) {
            assertJson("assets/palworks/models/block/trough_fill_" + level);
        }
    }
}
