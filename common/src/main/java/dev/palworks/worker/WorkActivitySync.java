package dev.palworks.worker;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.net.packets.WorkActivityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each worker's owner told what it's doing ({@link WorkActivity}), for the party HUD. Called
 * every worker tick; sends only when the activity changes, plus a refresh every
 * {@link #REFRESH_TICKS} so an owner who rejoins or changes dimension catches up.
 */
public final class WorkActivitySync {
    static final int REFRESH_TICKS = 100;

    private record Sent(WorkActivity activity, long gameTime) {}

    private static final Map<UUID, Sent> LAST_SENT = new ConcurrentHashMap<>();

    private WorkActivitySync() {}

    public static void update(Pokemon pokemon, Mob entity, WorkerState state, long gameTime) {
        WorkActivity activity = WorkActivity.of(
                state.isEnabled(),
                state.getCurrentReservation() == null ? null : state.getCurrentReservation().getJobType(),
                WorkerEligibility.isBusy(entity),
                state.isWorking());

        UUID uuid = pokemon.getUuid();
        Sent last = LAST_SENT.get(uuid);
        if (last != null && last.activity().equals(activity) && gameTime - last.gameTime() < REFRESH_TICKS) {
            return;
        }
        ServerPlayer owner = pokemon.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        new WorkActivityPacket(uuid, activity).sendToPlayer(owner);
        LAST_SENT.put(uuid, new Sent(activity, gameTime));
    }

    /** Forget a worker that's gone (recalled, unloaded), so it's sent afresh when it's back. */
    public static void forget(UUID pokemonUUID) {
        LAST_SENT.remove(pokemonUUID);
    }

    /** Drop everything (server stop). */
    public static void clear() {
        LAST_SENT.clear();
    }
}
