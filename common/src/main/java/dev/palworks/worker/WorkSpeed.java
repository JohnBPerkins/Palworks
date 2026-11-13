package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import dev.palworks.mood.Mood;
import dev.palworks.sleep.Sleep;
import org.jetbrains.annotations.Nullable;

/**
 * How fast a worker works and walks, as a share of its usual speed: what its needs and mood leave it,
 * one after the other (empty and Tired is both slowdowns at once). 1 for a fed, rested, happy worker.
 */
public final class WorkSpeed {
    private WorkSpeed() {}

    public static double of(Pokemon pokemon, @Nullable WorkerState state) {
        return Hunger.workSpeed(pokemon, state) * Sleep.workSpeed(pokemon, state) * Mood.workSpeed(pokemon, state);
    }
}
