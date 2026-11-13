package dev.palworks.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each player's display settings, from config/palworks-client.json. */
class PalworksClientConfigTest {

    @Test
    void statusIconsShowByDefault() {
        List<String> warnings = new ArrayList<>();
        assertTrue(PalworksClientConfig.parse("{}", warnings::add).showStatusIcons());
        assertEquals(List.of(), warnings);
    }

    @Test
    void statusIconsCanBeTurnedOff() {
        List<String> warnings = new ArrayList<>();
        assertFalse(PalworksClientConfig.parse("{\"show_status_icons\": false}", warnings::add)
                .showStatusIcons());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadFileMeansTheDefaultsWithAWarning() {
        List<String> warnings = new ArrayList<>();
        assertEquals(
                PalworksClientConfig.DEFAULTS, PalworksClientConfig.parse("{\"show_status_icons\": 3}", warnings::add));
        assertEquals(PalworksClientConfig.DEFAULTS, PalworksClientConfig.parse("{", warnings::add));
        assertEquals(2, warnings.size());
    }

    @Test
    void theWrittenFileReadsBackTheSame() {
        assertEquals(
                PalworksClientConfig.DEFAULTS,
                PalworksClientConfig.parse(PalworksClientConfig.toJson(PalworksClientConfig.DEFAULTS), message -> {}));
    }
}
