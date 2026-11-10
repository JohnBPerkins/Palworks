package dev.palworks.manager;

import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * JobManager's view of an active worker, refreshed from the worker every tick it reports in
 * ({@link JobManager#registerWorker}). Its WorkerState stays the source of truth for its job.
 */
final class WorkerInfo {
    final UUID pokemonUUID;
    BlockPos position;
    boolean fromParty; // From its owner's party (not a pasture): works near its owner

    @Nullable
    BlockPos ownerPosition; // Where its owner is, for one from a party (null if not in this level)

    Set<Suitability> suitabilities = EnumSet.noneOf(Suitability.class);

    @Nullable
    ReservationId currentReservation;

    boolean idle = true;
    long lastSeenTick; // Game tick of the worker's last registerWorker() call

    WorkerInfo(UUID pokemonUUID, BlockPos position) {
        this.pokemonUUID = pokemonUUID;
        this.position = position;
    }
}
