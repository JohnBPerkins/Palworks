package dev.palworks.hunger;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonGainedEvent;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.function.Consumer;

/**
 * A Pokémon starts as full as troughs fill it ({@link Hunger#troughCap}, half its max by default).
 * Cobblemon starts every Pokémon empty, so one put straight to work would work slowly until it ate.
 *
 * <p>It's topped up the first time a player gets it: caught, chosen as a starter, hatched, traded,
 * given by a command. Cobblemon's "gained" event also fires each time it moves between party and PC,
 * and when it evolves, so a marker in its persistent data (which goes with it to a new owner) keeps
 * it to once. With hunger off in the config, fullness is left to Cobblemon and nothing is marked.
 */
public final class StartingFullness {
    /** Set in the Pokémon's persistent data once it's had its starting fullness. */
    static final String MARKER = "palworks:started_fullness";

    private StartingFullness() {}

    public static void register() {
        Consumer<PokemonGainedEvent> handler = event -> onGained(event.getPokemon());
        CobblemonEvents.POKEMON_GAINED.subscribe(Priority.NORMAL, handler);
    }

    /** A player has got this Pokémon: top it up to its starting fullness, if it never has been. */
    public static void onGained(Pokemon pokemon) {
        if (!Hunger.enabled() || pokemon.getPersistentData().getBoolean(MARKER)) {
            return;
        }
        pokemon.getPersistentData().putBoolean(MARKER, true);
        int start = Hunger.troughCap(pokemon);
        if (pokemon.getCurrentFullness() < start) {
            pokemon.setCurrentFullness(start);
        }
    }
}
