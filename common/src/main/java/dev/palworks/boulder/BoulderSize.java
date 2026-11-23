package dev.palworks.boulder;

/**
 * How big a boulder an Unearthing worker of a level raises, and how much ore one with ore holds:
 * more blocks, a wider footprint, and bigger ore clusters the higher its level. Only about one
 * boulder in ten ({@link #ORE_BOULDER_CHANCE}, at every level) has ore at all; the rest are stone.
 * What the blocks are comes from its kind (see {@link BoulderKind}); its shape is rolled anew each
 * time (see {@link BoulderShape}).
 *
 * @param minBlocks the fewest blocks it's made of
 * @param maxBlocks the most
 * @param footprint the widest it is, either way across the ground (it fits a square this size)
 * @param minHeight the lowest its top is, in blocks
 * @param maxHeight the highest
 * @param minClusters the fewest clusters of ore in a boulder with ore
 * @param maxClusters the most
 * @param minClusterSize the fewest ore blocks in a cluster
 * @param maxClusterSize the most
 */
public record BoulderSize(
        int minBlocks,
        int maxBlocks,
        int footprint,
        int minHeight,
        int maxHeight,
        int minClusters,
        int maxClusters,
        int minClusterSize,
        int maxClusterSize) {

    /** The highest a boulder ever is: a miner on the ground, or on the boulder, reaches the top. */
    public static final int MAX_HEIGHT = 4;

    /** The chance a boulder has ore in it, whatever its level (the user's choice: about one in ten). */
    public static final double ORE_BOULDER_CHANCE = 0.1;

    private static final BoulderSize[] BY_LEVEL = {
        new BoulderSize(5, 8, 3, 2, 3, 1, 1, 1, 2),
        new BoulderSize(10, 15, 4, 2, 3, 1, 1, 2, 3),
        new BoulderSize(17, 24, 4, 2, 4, 1, 2, 2, 3),
        new BoulderSize(28, 38, 5, 3, 4, 2, 2, 2, 4),
        new BoulderSize(42, 56, 6, 3, 4, 2, 3, 3, 4),
    };

    /** The size for an Unearthing level (1 to 5; anything outside is taken as the nearest). */
    public static BoulderSize forLevel(int level) {
        return BY_LEVEL[Math.clamp(level, 1, BY_LEVEL.length) - 1];
    }

    /** The fewest ore blocks in a boulder with ore. */
    public int minOre() {
        return minClusters * minClusterSize;
    }

    /** The most ore blocks in a boulder with ore. */
    public int maxOre() {
        return maxClusters * maxClusterSize;
    }
}
