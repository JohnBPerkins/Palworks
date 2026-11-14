package dev.palworks.mood;

import dev.palworks.hunger.HungerMath;

/**
 * The arithmetic of a worker's mood, with no Minecraft or Cobblemon types, so it's unit-tested (see
 * {@link Mood}). Mood runs from 0 to {@code WorkerState.MAX_MOOD}.
 */
public final class MoodMath {
    private MoodMath() {}

    /**
     * How much a worker's mood changes in one tick: down for each need that isn't met (Hungry, Tired),
     * or, with both met and it not at work, back up. Rates are points a Minecraft day.
     */
    public static double changePerTick(
            boolean hungry,
            boolean tired,
            boolean atWork,
            double hungryDrainPerDay,
            double tiredDrainPerDay,
            double recoveryPerDay) {
        double perDay = 0;
        if (hungry) {
            perDay -= hungryDrainPerDay;
        }
        if (tired) {
            perDay -= tiredDrainPerDay;
        }
        if (!hungry && !tired && !atWork) {
            perDay += recoveryPerDay;
        }
        return perDay / HungerMath.TICKS_PER_DAY;
    }

    /** The mood {@code units} of work (as hunger counts them) wear off. */
    public static double workDrain(int units, int workPerPoint) {
        return units <= 0 ? 0 : (double) units / workPerPoint;
    }

    /**
     * Whether a worker is on strike once its mood is {@code mood}: it goes on strike when its mood
     * reaches 0, and stays on it until its mood is back to {@code strikeUntil}.
     */
    public static boolean striking(boolean wasStriking, double mood, int strikeUntil) {
        return mood <= 0 || (wasStriking && mood < strikeUntil);
    }

    /** Whether a worker in this mood is unhappy: on its way to a strike. */
    public static boolean unhappy(double mood, int unhappyBelow) {
        return mood < unhappyBelow;
    }
}
