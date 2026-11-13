package dev.palworks.worker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.architectury.platform.Platform;
import dev.palworks.ai.WorkerAI;
import dev.palworks.config.PalworksConfig;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * What runs workers, on both loaders: called from Architectury's server events (see
 * PalworksCommon) and from each Pokémon's tick (PokemonEntityTickMixin).
 */
public final class WorkerTicking {
    private WorkerTicking() {}

    /** As the server starts: read the settings in {@code palworks.json} in the config folder. */
    public static void onServerStarting() {
        PalworksConfig.load(Platform.getConfigFolder());
    }

    /**
     * Once per server tick, after entities have ticked: each level's JobManager scans for jobs
     * and hands them out (its workers have already reported in this tick, see {@link #onPokemonTick}).
     */
    public static void onServerTick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            JobManager.getInstance(level).tick(level);
        }
    }

    /**
     * After each Pokémon entity's tick: an owned Pokémon out in the world reports in as a worker
     * and does its job. Run from each worker's own tick rather than by looping over every entity
     * in the level, so ones in non-ticking chunks stop reporting in and JobManager unregisters them.
     */
    public static void onPokemonTick(PokemonEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return; // Only on the server
        }
        Pokemon pokemon = entity.getPokemon();
        if (pokemon == null || pokemon.getOwnerUUID() == null || pokemon.getEntity() == null) {
            return; // Only owned Pokémon that are out in the world
        }
        // No work it can do, and never a worker: not one. (One that was, and lost its last work
        // skill, carries on as one whose work is off: it lets its job go and wakes up.)
        if (!WorkerEligibility.canWork(pokemon) && !WorkerStateManager.hasWorkerState(pokemon)) {
            return;
        }
        JobManager.getInstance(level).registerWorker(pokemon, entity, level);
        WorkerAI.tick(pokemon, entity, level);
    }

    /** Clear per-dimension state on shutdown so it doesn't leak across world reloads. */
    public static void onServerStopped() {
        JobManager.clearAll();
        WorkerInventoryManager.clearCache();
        WorkerStateManager.clearCache();
    }
}
