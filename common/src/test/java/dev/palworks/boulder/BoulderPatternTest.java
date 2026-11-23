package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoulderPatternTest {
    private static final SmoothNoise NOISE = new SmoothNoise(7);

    private static Map<String, Integer> stone(Object... blocksAndWeights) {
        Map<String, Integer> stone = new LinkedHashMap<>();
        for (int i = 0; i < blocksAndWeights.length; i += 2) {
            stone.put((String) blocksAndWeights[i], (Integer) blocksAndWeights[i + 1]);
        }
        return stone;
    }

    private static BoulderPattern.Spot at(int x, int y, int z, int depth, boolean openToSky) {
        return new BoulderPattern.Spot(new BoulderShape.Cell(x, y, z), depth, openToSky, 4, NOISE, 0, 0, 0);
    }

    @Test
    void strataAreLevelBandsAsThickAsTheirWeights() {
        Map<String, Integer> stone = stone("a", 2, "b", 1);
        // Untilted: a, a, b, a, a, b... up, the same all the way across
        for (int x = 0; x < 4; x++) {
            assertEquals("a", BoulderPattern.STRATA.pick(stone, at(x, 0, 2, 1, false)));
            assertEquals("a", BoulderPattern.STRATA.pick(stone, at(x, 1, 2, 1, false)));
            assertEquals("b", BoulderPattern.STRATA.pick(stone, at(x, 2, 2, 1, false)));
            assertEquals("a", BoulderPattern.STRATA.pick(stone, at(x, 3, 2, 1, false)));
        }
    }

    @Test
    void aShellIsLayersFromTheOutsideIn() {
        Map<String, Integer> stone = stone("outer", 1, "middle", 1, "core", 1);
        assertEquals("outer", BoulderPattern.SHELL.pick(stone, at(0, 0, 0, 1, false)));
        assertEquals("middle", BoulderPattern.SHELL.pick(stone, at(0, 0, 0, 2, false)));
        assertEquals("core", BoulderPattern.SHELL.pick(stone, at(0, 0, 0, 3, false)));
        assertEquals("core", BoulderPattern.SHELL.pick(stone, at(0, 0, 0, 5, false)));
    }

    @Test
    void aCapIsOnWhatsOpenToTheSkyOnly() {
        Map<String, Integer> stone = stone("moss", 1, "cobble", 3, "stone", 2);
        assertEquals("moss", BoulderPattern.CAPPED.pick(stone, at(1, 2, 1, 1, true)));
        for (int x = 0; x < 6; x++) {
            String below = BoulderPattern.CAPPED.pick(stone, at(x, 0, x, 1, false));
            assertTrue(!"moss".equals(below), below);
        }
    }

    @Test
    void gradedGoesFromTheFirstAtTheBottomToTheLastAtTheTop() {
        Map<String, Integer> stone = stone("bottom", 1, "top", 1);
        int bottomLow = 0;
        int topHigh = 0;
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                bottomLow += "bottom".equals(BoulderPattern.GRADED.pick(stone, at(x, 0, z, 1, false))) ? 1 : 0;
                topHigh += "top".equals(BoulderPattern.GRADED.pick(stone, at(x, 3, z, 1, true))) ? 1 : 0;
            }
        }
        assertEquals(36, bottomLow, "the bottom layer");
        assertEquals(36, topHigh, "the top layer");
    }

    @Test
    void patchesComeOutNearTheirWeightsAndClump() {
        Map<String, Integer> stone = stone("a", 3, "b", 1);
        Map<String, Integer> counts = new HashMap<>();
        int same = 0;
        int pairs = 0;
        String previous = null;
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                String block = BoulderPattern.PATCHES.pick(stone, at(x, 1, z, 1, false));
                counts.merge(block, 1, Integer::sum);
                if (previous != null) {
                    pairs++;
                    same += block.equals(previous) ? 1 : 0;
                }
                previous = block;
            }
            previous = null;
        }
        double share = counts.getOrDefault("a", 0) / 1600.0;
        assertTrue(share > 0.6 && share < 0.9, "a's share " + share);
        // Neighbours mostly match: patches, not salt and pepper (random would match 62.5% of the time)
        assertTrue(same > pairs * 0.72, same + " of " + pairs + " neighbours the same");
    }

    @Test
    void patternsAreNamedAsInTheFiles() {
        assertEquals(BoulderPattern.STRATA, BoulderPattern.fromId("strata"));
        assertNull(BoulderPattern.fromId("swirls"));
    }
}
