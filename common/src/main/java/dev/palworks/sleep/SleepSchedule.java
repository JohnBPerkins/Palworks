package dev.palworks.sleep;

import java.util.function.IntPredicate;

/**
 * When a pastured worker sleeps, and whether a night's sleep left it rested, with no Minecraft or
 * Cobblemon types, so it's unit-tested. See {@link Sleep}.
 */
public final class SleepSchedule {
    /** Ticks in a Minecraft day. */
    public static final int DAY_TICKS = 24_000;

    /** How often through the day hours are sampled to tell whether they cover all of it. */
    private static final int SAMPLE_TICKS = 1_000;

    private SleepSchedule() {}

    /**
     * Whether it's bedtime at {@code dayTime} for a species that sleeps in {@code hours} (time of day,
     * 0 to 23999): its own hours, or {@code night}'s if they're the whole day ("any"), since a worker
     * has to work some time.
     */
    public static boolean isBedtime(long dayTime, IntPredicate hours, IntPredicate night) {
        int time = (int) Math.floorMod(dayTime, (long) DAY_TICKS);
        return (coversWholeDay(hours) ? night : hours).test(time);
    }

    /** Whether these hours take up the whole day (Cobblemon's "any"), as far as samples through it tell. */
    public static boolean coversWholeDay(IntPredicate hours) {
        for (int time = 0; time < DAY_TICKS; time += SAMPLE_TICKS) {
            if (!hours.test(time)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a worker that slept {@code bedTicks} in a bed and {@code groundTicks} without one wakes
     * Tired: if under half of its sleep was in a bed.
     */
    public static boolean wakesTired(long bedTicks, long groundTicks) {
        long total = bedTicks + groundTicks;
        return total > 0 && bedTicks * 2 < total;
    }
}
