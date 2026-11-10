package dev.palworks.manager;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** A level's active workers, and their entities (by Pokémon UUID), as they last reported in. */
final class WorkerRegistry {
    private final Map<UUID, WorkerInfo> workers = new ConcurrentHashMap<>();

    // Live worker entities (Pokémon UUID -> entity), kept current by JobManager.registerWorker, so
    // UUID lookups are O(1) instead of scanning all entities.
    private final Map<UUID, PokemonEntity> entities = new HashMap<>();

    Collection<WorkerInfo> all() {
        return workers.values();
    }

    int size() {
        return workers.size();
    }

    boolean contains(UUID pokemonUUID) {
        return workers.containsKey(pokemonUUID);
    }

    @Nullable
    WorkerInfo get(UUID pokemonUUID) {
        return workers.get(pokemonUUID);
    }

    /** The worker's info, added (at {@code position}) if it isn't registered yet. */
    WorkerInfo getOrAdd(UUID pokemonUUID, BlockPos position) {
        return workers.computeIfAbsent(pokemonUUID, k -> new WorkerInfo(pokemonUUID, position));
    }

    @Nullable
    WorkerInfo remove(UUID pokemonUUID) {
        return workers.remove(pokemonUUID);
    }

    /** It finished or lost its job: ready for another. */
    void markIdle(UUID pokemonUUID) {
        WorkerInfo worker = workers.get(pokemonUUID);
        if (worker != null) {
            worker.idle = true;
            worker.currentReservation = null;
        }
    }

    void putEntity(UUID pokemonUUID, PokemonEntity entity) {
        entities.put(pokemonUUID, entity);
    }

    void forgetEntity(UUID pokemonUUID) {
        entities.remove(pokemonUUID);
    }

    /** The worker's entity as last seen, alive or not. */
    @Nullable
    PokemonEntity lastEntity(UUID pokemonUUID) {
        return entities.get(pokemonUUID);
    }

    /** The worker's entity, if it's still alive. */
    @Nullable
    PokemonEntity entity(UUID pokemonUUID) {
        PokemonEntity cached = entities.get(pokemonUUID);
        return cached != null && cached.isAlive() ? cached : null;
    }

    /** The worker's Pokémon, if its entity is still alive. */
    @Nullable
    Pokemon pokemon(UUID pokemonUUID) {
        PokemonEntity entity = entity(pokemonUUID);
        return entity != null ? entity.getPokemon() : null;
    }
}
