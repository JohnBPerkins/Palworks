package dev.palworks.fabric.event;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkerAI;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.manager.JobManager;
import dev.palworks.worker.WorkerStateManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

/**
 * Handles ticking for Pokémon workers on Fabric. Each worker runs from its own entity tick (see
 * PokemonEntityMixin), and JobManager once per server tick.
 */
public class WorkerTickHandler {
    public static void register() {
        // Register server tick event to tick JobManager once per tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Tick JobManager for each world
            for (ServerLevel level : server.getAllLevels()) {
                JobManager.getInstance(level).tick(level);
            }
        });

        // Clear per-dimension state on shutdown so it doesn't leak across world reloads.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            JobManager.clearAll();
            WorkerInventoryManager.clearCache();
            WorkerStateManager.clearCache();
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
        if (pokemon == null || pokemon.getOwnerUUID() == null) {
            return; // Only process owned Pokemon
        }

        if (pokemonEntity.level() instanceof ServerLevel serverLevel) {
            // Register worker with JobManager
            JobManager.getInstance(serverLevel).registerWorker(pokemon, pokemonEntity, serverLevel);

            // Execute assigned work
            WorkerAI.tick(pokemon, pokemonEntity, serverLevel);
        }
    }
}
