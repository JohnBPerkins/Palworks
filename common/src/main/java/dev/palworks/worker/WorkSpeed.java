package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import dev.palworks.sleep.Sleep;
import org.jetbrains.annotations.Nullable;

/**
 * How fast a worker works and walks, as a share of its usual speed: what its needs leave it. Each
 * unmet need takes its share off (by default a quarter for no fullness left, a quarter for Tired), and
 * together they never leave it below {@link #SLOWEST}. 1 for a fed, rested worker. Its mood doesn't
 * slow it: a worker in a bad enough mood stops altogether (see Mood).
 */
public final class WorkSpeed {
    /** The slowest a worker ever goes, however many of its needs aren't met. */
    public static final double SLOWEST = 0.5;

    private WorkSpeed() {}

    public static double of(Pokemon pokemon, @Nullable WorkerState state) {
        return combined(Hunger.workSpeed(pokemon, state), Sleep.workSpeed(pokemon, state));
    }

    /** Speeds put together: each one's slowdown taken off in turn, down to {@link #SLOWEST} at most. */
    public static double combined(double... speeds) {
        double speed = 1;
        for (double each : speeds) {
            speed -= 1 - each;
        }
        return Math.max(SLOWEST, Math.min(1, speed));
    }
}
