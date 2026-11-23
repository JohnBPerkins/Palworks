package dev.palworks.boulder;

/**
 * How big a boulder an Unearthing worker of a level raises, and how much ore one with ore holds:
 * more blocks, a wider footprint, and more ore the higher its level. Only about one
 * boulder in eight ({@link #ORE_BOULDER_CHANCE}, at every level) has ore at all; the rest are stone.
 * What the blocks are comes from its kind (see {@link BoulderKind}); its shape is rolled anew each
 * time (see {@link BoulderShape}).
 *
 * @param minBlocks the fewest blocks it's made of
 * @param maxBlocks the most
 * @param footprint how wide its main lumps are, either way across the ground; a ridge or a rock
 *     cluster can reach a little further (see {@link #widest})
 * @param minHeight the lowest its top is, in blocks
 * @param maxHeight the highest
 * @param minOre the fewest ore blocks in a boulder with ore, each anywhere in it
 * @param maxOre the most
 */
public record BoulderSize(
        int minBlocks, int maxBlocks, int footprint, int minHeight, int maxHeight, int minOre, int maxOre) {

    /**
     * The highest a boulder ever is. Every column two or more high has one beside it at most a block
     * lower (see BoulderShape), so a miner climbs it to reach the top.
     */
    public static final int MAX_HEIGHT = 6;

    /** The chance a boulder has ore in it, whatever its level (the user's choice: one in eight). */
    public static final double ORE_BOULDER_CHANCE = 0.125;

    private static final BoulderSize[] BY_LEVEL = {
        new BoulderSize(5, 8, 3, 2, 3, 1, 2),
        new BoulderSize(10, 15, 4, 2, 3, 2, 3),
        new BoulderSize(17, 24, 4, 2, 4, 2, 6),
        new BoulderSize(28, 38, 5, 3, 5, 4, 8),
        new BoulderSize(42, 56, 6, 3, 6, 6, 12),
    };

    /** The widest a boulder of this size is, either way: it fits a square this size. */
    public int widest() {
        return footprint + 2;
    }

    /** The size for an Unearthing level (1 to 5; anything outside is taken as the nearest). */
    public static BoulderSize forLevel(int level) {
        return BY_LEVEL[Math.clamp(level, 1, BY_LEVEL.length) - 1];
    }
}
