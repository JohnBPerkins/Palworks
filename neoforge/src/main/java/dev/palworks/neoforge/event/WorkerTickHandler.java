package dev.palworks.neoforge.event;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.ai.WorkerAI;
import dev.palworks.manager.JobManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Handles ticking for Pokémon workers on NeoForge.
 * Uses server tick event to manually tick all pokemon entities.
 */
@EventBusSubscriber(modid = "palworks")
public class WorkerTickHandler {
    private static final boolean DEBUG = false;  // Reduced debug spam
    private static long lastDebugLog = 0;
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;

        // Get all server levels
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Tick JobManager FIRST (scan jobs, assign work)
            JobManager.getInstance(level).tick(level);

            // Then tick individual workers (execute assigned jobs)
            for (Entity entity : level.getAllEntities()) {
                // Check if entity is a Cobblemon Pokemon
                if (entity instanceof PokemonEntity pokemonEntity) {
                    Pokemon pokemon = pokemonEntity.getPokemon();
                    if (pokemon == null) continue;

                    // ONLY process owned Pokemon
                    if (pokemon.getOwnerUUID() == null) {
                        continue; // Skip wild Pokemon
                    }

                    // ONLY process Pokemon that are currently out in the world
                    if (pokemon.getEntity() == null) {
                        continue; // Skip Pokemon not in world
                    }

                    // Cast to Mob for navigation
                    if (pokemonEntity instanceof Mob mob) {
                        // Register worker with JobManager
                        JobManager.getInstance(level).registerWorker(pokemon, mob, level);

                        // Execute assigned work
                        WorkerAI.tick(pokemon, mob, level);
                    }
                }
            }
        }
    }
}
