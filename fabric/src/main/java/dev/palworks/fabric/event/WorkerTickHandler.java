package dev.palworks.fabric.event;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkerAI;
import dev.palworks.fabric.PalworksFabric;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.manager.JobManager;
import dev.palworks.worker.WorkerStateManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * Handles ticking for Pokémon workers on Fabric.
 * Hooks into Cobblemon entity tick events.
 */
public class WorkerTickHandler {
    private static final boolean DEBUG = false;  // Reduced debug spam
    private static long lastDebugLog = 0;

    public static void register() {
        // Register server tick event to tick JobManager once per tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Tick JobManager for each world
            for (ServerLevel level : server.getAllLevels()) {
                JobManager.getInstance(level).tick(level);
            }
        });

        // Register server entity load event
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            // Entities are loaded, we'll tick them via the tick event
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

        // Debug log every 5 seconds
        if (DEBUG && System.currentTimeMillis() - lastDebugLog > 5000) {
            PalworksFabric.LOGGER.debug("Fabric tick handler called for {}", pokemon.getSpecies().getName());
            lastDebugLog = System.currentTimeMillis();
        }

        // Cast to Mob for navigation
        if (pokemonEntity instanceof Mob mob && pokemonEntity.level() instanceof ServerLevel serverLevel) {
            // Register worker with JobManager
            JobManager.getInstance(serverLevel).registerWorker(pokemon, mob, serverLevel);

            // Execute assigned work
            WorkerAI.tick(pokemon, mob, serverLevel);
        } else {
            if (DEBUG && System.currentTimeMillis() - lastDebugLog > 5000) {
                PalworksFabric.LOGGER.warn("PokemonEntity is not a Mob! Class: {}", pokemonEntity.getClass().getName());
            }
        }
    }
}
