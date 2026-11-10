package dev.palworks.animation;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.aspect.AspectProvider;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.worker.WorkActivity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Gives Cobblemon each worker's {@link WorkAspects}, through an {@link AspectProvider}, and has it
 * recalculate a worker's aspects (which syncs them to clients) when they change.
 *
 * <p>These are calculated aspects, not forced ones, so they're never saved with the Pokémon: a
 * reload can't leave one stuck on. They change at most when a worker starts or stops working.
 */
public final class WorkAspectSync {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    /** The aspects shown for a worker, and when it was last working. */
    private static final class Shown {
        Set<String> aspects;
        long lastWorking;

        Shown(Set<String> aspects, long lastWorking) {
            this.aspects = aspects;
            this.lastWorking = lastWorking;
        }
    }

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private WorkAspectSync() {}

    /** Add the work aspects to every Pokémon's. Call at mod init; once is enough. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            AspectProvider.Companion.register(new Provider());
        }
    }

    /** After each worker tick: update its aspects if what it's doing has changed. */
    public static void update(Pokemon pokemon, WorkActivity activity, long gameTime) {
        UUID id = pokemon.getUuid();
        Shown shown = SHOWN.get(id);
        boolean working = activity.phase() == WorkActivity.Phase.WORKING;
        if (shown == null) {
            if (!working) {
                return; // nothing shown, nothing to show: the usual case for an idle worker
            }
            shown = new Shown(Set.of(), gameTime);
            SHOWN.put(id, shown);
        }
        Set<String> wanted = WorkAspects.wanted(shown.aspects, shown.lastWorking, activity, gameTime);
        if (working) {
            shown.lastWorking = gameTime;
        }
        if (!wanted.equals(shown.aspects)) {
            shown.aspects = wanted;
            pokemon.updateAspects(); // recalculates them (asking the Provider below) and syncs them
        }
        // Kept while shown, even if the worker goes (recalled mid-job): the next time it ticks,
        // they're taken off it
        if (wanted.isEmpty()) {
            SHOWN.remove(id);
        }
    }

    /** Drop everything (server stop). Calculated aspects aren't saved, so none outlive this. */
    public static void clear() {
        SHOWN.clear();
    }

    /** What Cobblemon asks when it recalculates a Pokémon's aspects. */
    private static final class Provider implements AspectProvider {
        @Override
        public Set<String> provide(Pokemon pokemon) {
            Shown shown = SHOWN.get(pokemon.getUuid());
            return shown == null ? Set.of() : shown.aspects;
        }

        @Override
        public Set<String> provide(@Nullable PokemonProperties properties) {
            return Set.of(); // only a live worker has them
        }
    }
}
