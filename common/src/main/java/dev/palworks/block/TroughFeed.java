package dev.palworks.block;

/**
 * The arithmetic of a trough's feed, with no Minecraft types, so it's unit-tested. Food put in a
 * trough becomes feed (a berry 1 point, or what a datapack makes it worth), and troughs joined
 * together share theirs evenly, so a line of them always looks one level (see
 * {@link TroughBlockEntity}).
 */
public final class TroughFeed {
    /** The feed one trough holds: a stack of berries' worth. */
    public static final int CAPACITY = 64;

    /** The most fill levels a trough shows (see {@link TroughBlock#FILL}): its rim. */
    public static final int MAX_FILL = 5;

    private TroughFeed() {}

    /**
     * {@code total} feed shared out between {@code troughs} troughs: as evenly as it goes, the
     * first ones getting the odd point over.
     */
    public static int[] share(int total, int troughs) {
        int[] shares = new int[troughs];
        if (troughs == 0) {
            return shares;
        }
        int each = total / troughs;
        int over = total % troughs;
        for (int i = 0; i < troughs; i++) {
            shares[i] = each + (i < over ? 1 : 0);
        }
        return shares;
    }

    /**
     * How full troughs holding {@code total} feed out of {@code capacity} look: 0 (bare) when
     * there's none, 1 for any at all, a level higher each further quarter, and {@link #MAX_FILL}
     * only once they're completely full.
     */
    public static int fillLevel(long total, long capacity) {
        if (total <= 0 || capacity <= 0) {
            return 0;
        }
        return 1 + (int) (Math.min(total, capacity) * (MAX_FILL - 1) / capacity);
    }

    /** A comparator's reading of troughs this full: 0 when empty, 15 when full, as for a container. */
    public static int signal(long total, long capacity) {
        if (total <= 0 || capacity <= 0) {
            return 0;
        }
        return 1 + (int) (Math.min(total, capacity) * 14 / capacity);
    }
}
