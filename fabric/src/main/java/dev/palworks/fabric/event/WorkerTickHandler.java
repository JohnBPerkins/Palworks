package dev.palworks.fabric.event;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkerAI;
import dev.palworks.fabric.PalworksFabric;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Mob;

/**
 * Handles ticking for Pokémon workers on Fabric.
 * Hooks into Cobblemon entity tick events.
 */
public class WorkerTickHandler {
    private static final boolean DEBUG = true;
    private static long lastDebugLog = 0;

    public static void register() {
        // Register server entity load event to ensure we catch all Pokemon entities
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            // Entities are loaded, we'll tick them via the tick event
        });
    }

    /**
     * Tick a Pokemon entity and run worker AI if applicable.
     * Call this from entity tick events.
     */
    public static void tickPokemonWorker(PokemonEntity pokemonEntity) {
        if (pokemonEntity.level().isClientSide) {
            return; // Only process on server
        }

        Pokemon pokemon = pokemonEntity.getPokemon();
        if (pokemon == null) {
            return;
        }

        // Debug log every 5 seconds
        if (DEBUG && System.currentTimeMillis() - lastDebugLog > 5000) {
            PalworksFabric.LOGGER.debug("Fabric tick handler called for {}", pokemon.getSpecies().getName());
            lastDebugLog = System.currentTimeMillis();
        }

        // Cast to Mob for navigation
        if (pokemonEntity instanceof Mob mob) {
            WorkerAI.tick(pokemon, mob, pokemonEntity.level());
        } else {
            if (DEBUG && System.currentTimeMillis() - lastDebugLog > 5000) {
                PalworksFabric.LOGGER.warn("PokemonEntity is not a Mob! Class: {}", pokemonEntity.getClass().getName());
            }
        }
    }
}
