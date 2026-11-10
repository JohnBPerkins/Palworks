package dev.palworks.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PalworksConfigTest {

    @AfterEach
    void restoreDefaults() {
        PalworksConfig.setCurrent(PalworksConfig.DEFAULTS);
    }

    private static PalworksConfig parse(String json, List<String> warnings) {
        return PalworksConfig.parse(json, warnings::add);
    }

    @Test
    void anEmptyFileIsAllDefaults() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{}", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void fieldsLeftOutKeepTheirDefaults() {
        List<String> warnings = new ArrayList<>();
        PalworksConfig config = parse("{\"work_range_horizontal\": 48, \"pickup_radius\": 2.5}", warnings);
        assertEquals(
                new PalworksConfig(
                        48, PalworksConfig.DEFAULTS.workRangeVertical(), PalworksConfig.DEFAULTS.ownerRange(), 2.5),
                config);
        assertEquals(List.of(), warnings);
    }

    @Test
    void aValueOutOfRangeMeansTheDefaultsWithAWarning() {
        List<String> warnings = new ArrayList<>();
        // Past Cobblemon's teleport-back distance
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"owner_range\": 40}", warnings));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("40"), warnings.get(0));
    }

    @Test
    void theWrongTypeOrBrokenJsonMeansTheDefaultsWithAWarning() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"work_range_vertical\": \"far\"}", warnings));
        assertEquals(PalworksConfig.DEFAULTS, parse("{\"work_range_vertical\": ", warnings));
        assertEquals(PalworksConfig.DEFAULTS, parse("[32]", warnings));
        assertEquals(3, warnings.size(), String.join("\n", warnings));
    }

    @Test
    void theWrittenDefaultsReadBackAsTheDefaults() {
        List<String> warnings = new ArrayList<>();
        assertEquals(PalworksConfig.DEFAULTS, parse(PalworksConfig.toJson(PalworksConfig.DEFAULTS), warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void loadingWritesTheDefaultsWhenThereIsNoFile(@TempDir Path configDir) throws IOException {
        PalworksConfig.load(configDir);
        Path file = configDir.resolve("palworks.json");
        assertTrue(Files.exists(file));
        assertEquals(PalworksConfig.DEFAULTS, PalworksConfig.current());
        assertTrue(Files.readString(file).contains("\"work_range_horizontal\": 32"), Files.readString(file));
    }

    @Test
    void loadingReadsTheFile(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve("palworks.json"), "{\"owner_range\": 16}");
        PalworksConfig.load(configDir);
        assertEquals(16, PalworksConfig.current().ownerRange());
    }
}
