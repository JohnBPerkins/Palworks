package dev.palworks.worker;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.aspect.AspectProvider;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import dev.palworks.mood.Mood;
import dev.palworks.sleep.Sleep;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Gives each worker's {@link WorkerStatus}es to Cobblemon as aspects, through an
 * {@link AspectProvider}, so they reach every client nearby, which shows them over its head. Worked
 * out after each worker tick; Cobblemon is only asked to recalculate (and sync) its aspects when
 * they change, so a steady worker costs nothing.
 *
 * <p>Calculated aspects, never saved with the Pokémon: a reload can't leave one stuck on.
 */
public final class WorkerStatusSync {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<UUID, Set<String>> SHOWN = new ConcurrentHashMap<>();

    private WorkerStatusSync() {}

    /** Add the status aspects to every Pokémon's. Call at mod init; once is enough. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            AspectProvider.Companion.register(new Provider());
        }
    }

    /**
     * What's wrong with this worker now: only while Palworks' hunger or sleep applies to it (its work
     * is on; sleep only for a pastured one).
     */
    public static Set<WorkerStatus> statusesOf(Pokemon pokemon, WorkerState state) {
        Set<WorkerStatus> statuses = EnumSet.noneOf(WorkerStatus.class);
        if (Hunger.isEmpty(pokemon, state)) {
            statuses.add(WorkerStatus.HUNGRY);
        }
        if (state.isTired() && Sleep.appliesTo(pokemon, state)) {
            statuses.add(WorkerStatus.TIRED);
        }
        if (Mood.isStriking(pokemon, state)) {
            statuses.add(WorkerStatus.STRIKING);
        } else if (Mood.isUnhappy(pokemon, state)) {
            statuses.add(WorkerStatus.UNHAPPY);
        }
        return statuses;
    }

    /** After each worker tick: show its statuses, if they've changed. */
    public static void update(Pokemon pokemon, WorkerState state) {
        Set<String> wanted = new HashSet<>();
        for (WorkerStatus status : statusesOf(pokemon, state)) {
            wanted.add(status.aspect());
        }
        UUID id = pokemon.getUuid();
        Set<String> shown = SHOWN.getOrDefault(id, Set.of());
        if (wanted.equals(shown)) {
            return;
        }
        if (wanted.isEmpty()) {
            SHOWN.remove(id);
        } else {
            SHOWN.put(id, Set.copyOf(wanted));
        }
        pokemon.updateAspects(); // recalculates them (asking the Provider below) and syncs them
    }

    /** Drop everything (server stop). Calculated aspects aren't saved, so none outlive this. */
    public static void clear() {
        SHOWN.clear();
    }

    /** What Cobblemon asks when it recalculates a Pokémon's aspects. */
    private static final class Provider implements AspectProvider {
        @Override
        public Set<String> provide(Pokemon pokemon) {
            return SHOWN.getOrDefault(pokemon.getUuid(), Set.of());
        }

        @Override
        public Set<String> provide(@Nullable PokemonProperties properties) {
            return Set.of(); // only a live worker has them
        }
    }
}
