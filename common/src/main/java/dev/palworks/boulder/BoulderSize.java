package dev.palworks.boulder;

/**
 * How big a boulder an Unearthing worker of a level raises, and how much of it is ore: more
 * blocks, a wider footprint, and a bigger share of ore the higher its level. What the blocks are
 * comes from its kind (see {@link BoulderKind}); its shape is rolled anew each time (see
 * {@link BoulderShape}).
 *
 * @param minBlocks the fewest blocks it's made of
 * @param maxBlocks the most
 * @param footprint the widest it is, either way across the ground (it fits a square this size)
 * @param minHeight the lowest its top is, in blocks
 * @param maxHeight the highest
 * @param oreChance the chance each block is ore (0 to 1), rather than stone
 */
public record BoulderSize(int minBlocks, int maxBlocks, int footprint, int minHeight, int maxHeight, double oreChance) {

    /** The highest a boulder ever is: a miner on the ground, or on the boulder, reaches the top. */
    public static final int MAX_HEIGHT = 4;

    private static final BoulderSize[] BY_LEVEL = {
        new BoulderSize(5, 8, 3, 2, 3, 0.10),
        new BoulderSize(10, 15, 4, 2, 3, 0.15),
        new BoulderSize(17, 24, 4, 2, 4, 0.20),
        new BoulderSize(28, 38, 5, 3, 4, 0.25),
        new BoulderSize(42, 56, 6, 3, 4, 0.30),
    };

    /** The size for an Unearthing level (1 to 5; anything outside is taken as the nearest). */
    public static BoulderSize forLevel(int level) {
        return BY_LEVEL[Math.clamp(level, 1, BY_LEVEL.length) - 1];
    }
}
