package dev.palworks.hunger;

/**
 * The arithmetic of worker hunger, with no Minecraft or Cobblemon types, so it's unit-tested. See
 * {@link Hunger} for what it's used for.
 */
public final class HungerMath {
    /** Ticks in a Minecraft day. */
    public static final int TICKS_PER_DAY = 24_000;

    /** Logs of a tree that make one unit of work. */
    static final int LOGS_PER_UNIT = 8;

    /** The most units of work one tree counts as, however big: a giant jungle tree doesn't empty a worker. */
    static final int MAX_TREE_UNITS = 5;

    private HungerMath() {}

    /**
     * The fullness a trough fills a worker up to: {@code fraction} of its max, rounded up, so a
     * worker that can be fed at all eats at least one point's worth. 0 if troughs never feed.
     */
    public static int troughCap(int maxFullness, double fraction) {
        if (fraction <= 0 || maxFullness <= 0) {
            return 0;
        }
        return Math.min(maxFullness, (int) Math.ceil(maxFullness * fraction - 1e-9));
    }

    /** The fullness a worker loses each tick its work is on: {@code perDay} of its max over a day. */
    public static double idleDrainPerTick(int maxFullness, double perDay) {
        return perDay <= 0 ? 0 : perDay * maxFullness / TICKS_PER_DAY;
    }

    /** The fullness {@code units} of work use up. */
    public static double workDrain(int units, int workPerFullness) {
        return units <= 0 ? 0 : (double) units / Math.max(1, workPerFullness);
    }

    /**
     * The units of work a tree of {@code logs} logs counts as: one per {@link #LOGS_PER_UNIT}
     * (rounded up), at most {@link #MAX_TREE_UNITS}. An oak is 1, a 2x2 spruce about 4.
     */
    public static int treeUnits(int logs) {
        if (logs <= 0) {
            return 0;
        }
        return Math.min(MAX_TREE_UNITS, (logs + LOGS_PER_UNIT - 1) / LOGS_PER_UNIT);
    }

    /**
     * How much longer than a job took an empty worker rests after it, so it gets through work at
     * {@code speed} of its usual rate: {@code jobTicks} at half speed, three times that at a quarter.
     */
    public static long restTicks(long jobTicks, double speed) {
        if (jobTicks <= 0 || speed >= 1) {
            return 0;
        }
        return Math.round(jobTicks * (1 / Math.max(speed, 0.01) - 1));
    }

    /**
     * Fullness owed (used up, not yet taken off) after adding {@code drain} to {@code owed}: whole
     * points come off the Pokémon ({@link Drained#points}), at most what it has, and the fraction
     * left over is owed. Once it's at 0, nothing more is owed: it doesn't build up a debt to eat off.
     */
    public static Drained drain(double owed, double drain, int fullness) {
        double total = Math.max(0, owed) + Math.max(0, drain);
        int whole = (int) Math.floor(total);
        int points = Math.min(whole, Math.max(0, fullness));
        double left = fullness - points <= 0 ? 0 : total - whole;
        return new Drained(points, left);
    }

    /**
     * What a drain comes to.
     *
     * @param points whole points of fullness to take off now
     * @param owed   the fraction of a point still owed
     */
    public record Drained(int points, double owed) {}
}
