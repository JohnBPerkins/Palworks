package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import dev.palworks.sleep.Sleep;
import org.jetbrains.annotations.Nullable;

/**
 * How fast a worker works and walks, as a share of its usual speed: what its needs leave it. Each
 * unmet need takes its share off (by default a quarter for no fullness left, a quarter for Tired), down
 * to {@link #SLOWEST}, the slowest either can be set to on its own. 1 for a fed, rested worker. Its mood doesn't
 * slow it: a worker in a bad enough mood stops altogether (see Mood).
 */
public final class WorkSpeed {
    /**
     * The slowest a worker ever goes: the slowest the config lets either need make it on its own
     * (its {@code empty_work_speed} and {@code tired_work_speed}), so two of them can't take it to
     * nothing or less.
     */
    public static final double SLOWEST = 0.1;

    private WorkSpeed() {}

    public static double of(Pokemon pokemon, @Nullable WorkerState state) {
        return combined(Hunger.workSpeed(pokemon, state), Sleep.workSpeed(pokemon, state));
    }

    /** Speeds put together: each one's slowdown taken off in turn, down to {@link #SLOWEST}. */
    public static double combined(double... speeds) {
        double speed = 1;
        for (double each : speeds) {
            speed -= 1 - each;
        }
        return Math.max(SLOWEST, Math.min(1, speed));
    }
}
