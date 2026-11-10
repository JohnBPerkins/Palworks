package dev.palworks.debug;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends players with {@code /palworks debug overlay} on what every worker near them is doing
 * ({@link WorkerDebugPacket}), for the in-world overlay. Debug only: nothing is sent to anyone else.
 * Ticked by {@code JobManager.tick} for each level.
 */
public final class WorkerDebugSync {
    private static final int INTERVAL_TICKS = 5;
    private static final double RADIUS = 48;
    private static final int MAX_PATH_NODES = 64;

    /** Players who had the overlay on at the last send, so turning it off clears theirs once. */
    private static final Set<UUID> SENT_TO = ConcurrentHashMap.newKeySet();

    private WorkerDebugSync() {}

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            if (DebugManager.showsWorkerOverlay(uuid)) {
                new WorkerDebugPacket(entriesIn(level, player.getBoundingBox().inflate(RADIUS))).sendToPlayer(player);
                SENT_TO.add(uuid);
            } else if (SENT_TO.remove(uuid)) {
                new WorkerDebugPacket(List.of()).sendToPlayer(player);
            }
        }
    }

    /** What the overlay shows for every worker in this area. */
    public static List<WorkerDebugPacket.Entry> entriesIn(ServerLevel level, AABB area) {
        List<WorkerDebugPacket.Entry> entries = new ArrayList<>();
        for (PokemonEntity entity : level.getEntitiesOfClass(PokemonEntity.class, area)) {
            Pokemon pokemon = entity.getPokemon();
            if (pokemon == null || pokemon.getOwnerUUID() == null) {
                continue; // wild Pokemon don't work
            }
            WorkerState state = WorkerStateManager.get(pokemon);
            if (state == null) {
                continue; // hasn't ticked as a worker yet
            }
            ReservationId reservation = state.getCurrentReservation();
            BlockPos target = reservation == null ? null : reservation.getStationPos();
            entries.add(new WorkerDebugPacket.Entry(entity.getId(),
                    WorkActivitySync.activityOf(entity, state), target, remainingPath(entity)));
        }
        return entries;
    }

    private static List<BlockPos> remainingPath(PokemonEntity entity) {
        Path path = entity.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return List.of();
        }
        List<BlockPos> nodes = new ArrayList<>();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && nodes.size() < MAX_PATH_NODES; i++) {
            nodes.add(path.getNodePos(i));
        }
        return nodes;
    }

    /** Drop everything (server stop). */
    public static void clear() {
        SENT_TO.clear();
    }
}
