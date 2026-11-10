package dev.palworks.neoforge.event;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkerAI;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.manager.JobManager;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Handles ticking for Pokémon workers on NeoForge.
 * Uses server tick event to manually tick all pokemon entities.
 */
@EventBusSubscriber(modid = "palworks")
public class WorkerTickHandler {
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Get all server levels
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Scan jobs, assign work. Workers have already ticked this tick (see onEntityTick).
            JobManager.getInstance(level).tick(level);
        }
    }

    /**
     * Run each worker from its own entity tick, like the Fabric mixin does, instead of looping
     * over every entity in the level. Entities in non-ticking chunks don't tick, so they stop
     * reporting in and JobManager unregisters them.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof PokemonEntity pokemonEntity)
                || !(pokemonEntity.level() instanceof ServerLevel level)) {
            return;
        }

        Pokemon pokemon = pokemonEntity.getPokemon();
        if (pokemon == null) return;

        // ONLY process owned Pokemon
        if (pokemon.getOwnerUUID() == null) {
            return; // Skip wild Pokemon
        }

        // ONLY process Pokemon that are currently out in the world
        if (pokemon.getEntity() == null) {
            return; // Skip Pokemon not in world
        }

        // Register worker with JobManager
        JobManager.getInstance(level).registerWorker(pokemon, pokemonEntity, level);

        // Execute assigned work
        WorkerAI.tick(pokemon, pokemonEntity, level);
    }

    /**
     * Clear per-dimension state on shutdown so it doesn't leak across world reloads.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        JobManager.clearAll();
        WorkerInventoryManager.clearCache();
        WorkerStateManager.clearCache();
    }
}
