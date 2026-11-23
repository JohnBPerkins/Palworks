package dev.palworks.boulder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BoulderPatternTest {
    private static final String TOP = "top";
    private static final String BOTTOM = "bottom";
    private static final Random RANDOM = new Random(3);

    private static Map<String, Integer> stone(Object... blocksAndWeights) {
        Map<String, Integer> stone = new LinkedHashMap<>();
        for (int i = 0; i < blocksAndWeights.length; i += 2) {
            stone.put((String) blocksAndWeights[i], (Integer) blocksAndWeights[i + 1]);
        }
        return stone;
    }

    /** A block of a boulder 4 high, its noise and speckle rolls fresh each time. */
    private static BoulderPattern.Spot at(int x, int y, int z, boolean openToSky, double fromMiddle) {
        return new BoulderPattern.Spot(
                new BoulderShape.Cell(x, y, z),
                openToSky,
                4,
                fromMiddle,
                new SmoothNoise(RANDOM.nextLong()),
                0,
                0,
                0,
                RANDOM.nextDouble(),
                RANDOM.nextDouble());
    }

    /** The share of many blocks at this spot (fresh noise each time) the pattern makes {@code block}. */
    private static double share(
            BoulderPattern pattern,
            Map<String, Integer> stone,
            String block,
            int y,
            boolean openToSky,
            double fromMiddle) {
        int hits = 0;
        int tries = 2000;
        for (int i = 0; i < tries; i++) {
            hits += block.equals(pattern.pick(stone, at(i % 5, y, i / 5 % 5, openToSky, fromMiddle))) ? 1 : 0;
        }
        return (double) hits / tries;
    }

    @Test
    void someBlocksAreAnyStoneAtRandomWhateverThePattern() {
        // A graded boulder's top is still sometimes its bottom stone, and the other way round
        Map<String, Integer> stone = stone(BOTTOM, 1, TOP, 1);
        double bottomOnTop = share(BoulderPattern.GRADED, stone, BOTTOM, 3, true, 1);
        assertTrue(bottomOnTop > 0.04 && bottomOnTop < 0.25, "bottom stone on top: " + bottomOnTop);
    }

    @Test
    void gradedIsMostlyTheFirstAtTheBottomAndTheLastAtTheTop() {
        Map<String, Integer> stone = stone(BOTTOM, 1, TOP, 1);
        assertTrue(share(BoulderPattern.GRADED, stone, BOTTOM, 0, false, 0.5) > 0.75, "at the bottom");
        assertTrue(share(BoulderPattern.GRADED, stone, TOP, 3, true, 0.5) > 0.75, "at the top");
    }

    @Test
    void strataAreMostlyBandsAsThickAsTheirWeights() {
        Map<String, Integer> stone = stone("a", 2, "b", 1);
        assertTrue(share(BoulderPattern.STRATA, stone, "a", 0, false, 0.5) > 0.5, "a at the bottom");
        assertTrue(share(BoulderPattern.STRATA, stone, "b", 2, false, 0.5) > 0.4, "b two up");
    }

    @Test
    void aShellIsMostlyItsOuterStoneOutsideAndInnerStoneInTheMiddle() {
        Map<String, Integer> stone = stone("outer", 1, "inner", 1);
        assertTrue(share(BoulderPattern.SHELL, stone, "outer", 1, true, 0.95) > 0.75, "at the edge");
        assertTrue(share(BoulderPattern.SHELL, stone, "inner", 0, false, 0.1) > 0.75, "in the middle");
    }

    @Test
    void aCapIsOnlyEverOnWhatsOpenToTheSky() {
        Map<String, Integer> stone = stone("moss", 1, "cobble", 3, "stone", 2);
        assertTrue(share(BoulderPattern.CAPPED, stone, "moss", 2, true, 0.5) > 0.75, "on top");
        assertEquals(0.0, share(BoulderPattern.CAPPED, stone, "moss", 0, false, 0.5), "below");
    }

    @Test
    void patchesComeOutNearTheirWeightsAndClump() {
        Map<String, Integer> stone = stone("a", 3, "b", 1);
        SmoothNoise noise = new SmoothNoise(7);
        Random random = new Random(11);
        int a = 0;
        int same = 0;
        int pairs = 0;
        for (int x = 0; x < 40; x++) {
            String previous = null;
            for (int z = 0; z < 40; z++) {
                String block = BoulderPattern.PATCHES.pick(
                        stone,
                        new BoulderPattern.Spot(
                                new BoulderShape.Cell(x, 1, z),
                                false,
                                4,
                                0.5,
                                noise,
                                0,
                                0,
                                0,
                                random.nextDouble(),
                                random.nextDouble()));
                a += "a".equals(block) ? 1 : 0;
                if (previous != null) {
                    pairs++;
                    same += block.equals(previous) ? 1 : 0;
                }
                previous = block;
            }
        }
        double share = a / 1600.0;
        assertTrue(share > 0.6 && share < 0.9, "a's share " + share);
        // Neighbours mostly match, speckles and all: patches, not salt and pepper (62.5% if random)
        assertTrue(same > pairs * 0.68, same + " of " + pairs + " neighbours the same");
    }

    @Test
    void patternsAreNamedAsInTheFiles() {
        assertEquals(BoulderPattern.STRATA, BoulderPattern.fromId("strata"));
        assertNull(BoulderPattern.fromId("swirls"));
    }
}
