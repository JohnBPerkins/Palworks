package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages WorkerState storage and retrieval for Pokemon entities.
 * Uses Cobblemon's persistentData NBT system for cross-platform compatibility.
 *
 * <p>An in-memory cache (keyed by the Pokemon's persistent UUID) holds the live WorkerState so it
 * isn't deserialized from NBT on every tick and per-tick mutations (e.g. work progress) accumulate
 * without a full NBT write each time. {@link #save} flushes a state to NBT; callers flush on
 * meaningful transitions and on a periodic heartbeat rather than every tick. Evict with
 * {@link #unload} when an entity despawns and {@link #clearCache} on server stop.
 */
public class WorkerStateManager {
    private static final String WORKER_STATE_KEY = "palworks:worker_state";

    private static final Map<UUID, WorkerState> CACHE = new ConcurrentHashMap<>();

    /**
     * Get the WorkerState for a Pokemon, creating a new one if it doesn't exist.
     */
    public static WorkerState getOrCreate(Pokemon pokemon) {
        WorkerState cached = CACHE.get(pokemon.getUuid());
        if (cached != null) {
            return cached;
        }

        CompoundTag persistentData = pokemon.getPersistentData();
        if (persistentData.contains(WORKER_STATE_KEY)) {
            WorkerState state = WorkerState.fromNBT(persistentData.getCompound(WORKER_STATE_KEY));

            if (state.getCurrentReservation() != null) {
                Palworks.LOGGER.debug("Loaded state for {} with reservation: {}",
                    pokemon.getSpecies().getName(), state.getCurrentReservation().getJobType());
            }

            CACHE.put(pokemon.getUuid(), state);
            return state;
        }

        Palworks.LOGGER.debug("Creating new WorkerState for {}", pokemon.getSpecies().getName());
        WorkerState state = new WorkerState();
        save(pokemon, state); // writes NBT + caches
        return state;
    }

    /**
     * Get the WorkerState for a Pokemon if it exists, null otherwise.
     */
    @Nullable
    public static WorkerState get(Pokemon pokemon) {
        WorkerState cached = CACHE.get(pokemon.getUuid());
        if (cached != null) {
            return cached;
        }

        CompoundTag persistentData = pokemon.getPersistentData();
        if (persistentData.contains(WORKER_STATE_KEY)) {
            WorkerState state = WorkerState.fromNBT(persistentData.getCompound(WORKER_STATE_KEY));
            CACHE.put(pokemon.getUuid(), state);
            return state;
        }

        return null;
    }

    /**
     * Flush a WorkerState to a Pokemon's persistent data (and keep it cached).
     */
    public static void save(Pokemon pokemon, WorkerState state) {
        CACHE.put(pokemon.getUuid(), state);
        pokemon.getPersistentData().put(WORKER_STATE_KEY, state.serializeNBT());
    }

    /**
     * Turn a Pokemon's work on or off and save it. Turning it off drops its current job (with any
     * progress); JobManager then frees the job for other workers on its next registration pass.
     * Used by both {@code /palworks worker enable|disable} and the interact wheel toggle.
     */
    public static void setEnabled(Pokemon pokemon, boolean enabled) {
        WorkerState state = getOrCreate(pokemon);
        state.setEnabled(enabled);
        if (!enabled) {
            state.clearReservation();
        }
        save(pokemon, state);
    }

    /**
     * Check if a Pokemon has WorkerState data (cached or persisted).
     */
    public static boolean hasWorkerState(Pokemon pokemon) {
        return CACHE.containsKey(pokemon.getUuid())
            || pokemon.getPersistentData().contains(WORKER_STATE_KEY);
    }

    /**
     * Remove WorkerState from a Pokemon (cache + persistent data).
     */
    public static void remove(Pokemon pokemon) {
        CACHE.remove(pokemon.getUuid());
        pokemon.getPersistentData().remove(WORKER_STATE_KEY);
    }

    /**
     * Evict a single worker's cached state. Call when the entity unloads/despawns; the last
     * flushed NBT remains and is reloaded on demand.
     */
    public static void unload(UUID pokemonUUID) {
        CACHE.remove(pokemonUUID);
    }

    /**
     * Drop all cached states. Call on server stop so state doesn't leak across world reloads.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Update the WorkerState for a Pokemon using a callback.
     * Handles loading, modification, and saving automatically.
     */
    public static void update(Pokemon pokemon, Consumer<WorkerState> updater) {
        WorkerState state = getOrCreate(pokemon);
        updater.accept(state);
        save(pokemon, state);
    }
}
