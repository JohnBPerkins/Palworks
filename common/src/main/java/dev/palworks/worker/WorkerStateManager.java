package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Manages WorkerState storage and retrieval for Pokemon entities.
 * Uses Cobblemon's persistentData NBT system for cross-platform compatibility.
 */
public class WorkerStateManager {
    private static final String WORKER_STATE_KEY = "palworks:worker_state";

    /**
     * Get the WorkerState for a Pokemon, creating a new one if it doesn't exist.
     */
    public static WorkerState getOrCreate(Pokemon pokemon) {
        CompoundTag persistentData = pokemon.getPersistentData();

        if (persistentData.contains(WORKER_STATE_KEY)) {
            CompoundTag stateTag = persistentData.getCompound(WORKER_STATE_KEY);
            WorkerState state = WorkerState.fromNBT(stateTag);

            // Debug: Log when loading existing state with reservation
            if (state.getCurrentReservation() != null) {
                Palworks.LOGGER.debug("Loaded state for {} with reservation: {}",
                    pokemon.getSpecies().getName(), state.getCurrentReservation().getJobType());
            }

            return state;
        } else {
            Palworks.LOGGER.debug("Creating new WorkerState for {}", pokemon.getSpecies().getName());
            WorkerState state = new WorkerState();
            // Initialize suitabilities on first creation
            state.initializeSuitabilities(pokemon);
            save(pokemon, state);
            return state;
        }
    }

    /**
     * Get the WorkerState for a Pokemon if it exists, null otherwise.
     */
    @Nullable
    public static WorkerState get(Pokemon pokemon) {
        CompoundTag persistentData = pokemon.getPersistentData();

        if (persistentData.contains(WORKER_STATE_KEY)) {
            CompoundTag stateTag = persistentData.getCompound(WORKER_STATE_KEY);
            return WorkerState.fromNBT(stateTag);
        }

        return null;
    }

    /**
     * Save WorkerState to a Pokemon's persistent data.
     */
    public static void save(Pokemon pokemon, WorkerState state) {
        CompoundTag persistentData = pokemon.getPersistentData();
        CompoundTag stateTag = state.serializeNBT();
        persistentData.put(WORKER_STATE_KEY, stateTag);

        // Removed excessive save logging - only log important state changes elsewhere
    }

    /**
     * Check if a Pokemon has WorkerState data.
     */
    public static boolean hasWorkerState(Pokemon pokemon) {
        return pokemon.getPersistentData().contains(WORKER_STATE_KEY);
    }

    /**
     * Remove WorkerState from a Pokemon.
     */
    public static void remove(Pokemon pokemon) {
        pokemon.getPersistentData().remove(WORKER_STATE_KEY);
    }

    /**
     * Update the WorkerState for a Pokemon using a callback.
     * Handles loading, modification, and saving automatically.
     */
    public static void update(Pokemon pokemon, java.util.function.Consumer<WorkerState> updater) {
        WorkerState state = getOrCreate(pokemon);
        updater.accept(state);
        save(pokemon, state);
    }
}
