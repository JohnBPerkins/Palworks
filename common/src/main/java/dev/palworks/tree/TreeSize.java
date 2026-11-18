package dev.palworks.tree;

/**
 * How big a tree a lumberjack will chop: the higher its Lumbering level, the more logs (and
 * roots) the tree may have. Pure logic (no Minecraft types) so it can be unit-tested.
 */
public final class TreeSize {
    /**
     * The most logs a tree may have for a lumberjack of level 1, 2, ... The last is the most any
     * tree has ({@code TreeDetector.MAX_TREE_BLOCKS}): something bigger isn't taken for a tree.
     */
    private static final int[] MAX_LOGS = {12, 32, 64, 128, 256};

    private TreeSize() {}

    /** The most logs a tree may have for a lumberjack of this level to chop it. */
    public static int maxLogs(int level) {
        return MAX_LOGS[Math.clamp(level, 1, MAX_LOGS.length) - 1];
    }

    /** The lowest Lumbering level that chops a tree of this many logs. */
    public static int levelNeeded(int logs) {
        for (int level = 1; level < MAX_LOGS.length; level++) {
            if (logs <= maxLogs(level)) {
                return level;
            }
        }
        return MAX_LOGS.length;
    }
}
