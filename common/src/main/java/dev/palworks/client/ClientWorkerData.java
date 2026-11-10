package dev.palworks.client;

import dev.palworks.inventory.CarryMode;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.worker.WorkActivity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copies of what the server sent about workers, filled by the packet handlers
 * (net.handlers) and read by what draws them:
 * <ul>
 *   <li>what the player's workers are doing (WorkActivityPacket), for the party HUD
 *       ({@code WorkStatusHud}), by Pokémon UUID;</li>
 *   <li>a Pokémon's work status (WorkerStatusPacket), sent just before its interact wheel opens
 *       and read once when the wheel is built ({@code PokemonInteractHandler}), by entity UUID.
 *       Only used to draw the wheel's options: the server never trusts it;</li>
 *   <li>the workers near the player (WorkerDebugPacket), for the debug overlay
 *       ({@code WorkerDebugRenderer}). Empty unless the player has {@code /palworks debug} on.</li>
 * </ul>
 * Has no client-only imports, so it is safe to load on a dedicated server (where Cobblemon still
 * constructs the client packet handlers).
 */
public final class ClientWorkerData {
    /** What the server said about one Pokémon's work. */
    public record Status(boolean enabled, CarryMode carryMode) {}

    private static final Map<UUID, WorkActivity> ACTIVITY = new ConcurrentHashMap<>();
    private static final Map<UUID, Status> STATUS = new ConcurrentHashMap<>();
    private static volatile List<WorkerDebugPacket.Entry> debugEntries = List.of();

    private ClientWorkerData() {}

    // --- Work activity (party HUD) ---

    public static void putActivity(UUID pokemonUUID, WorkActivity activity) {
        ACTIVITY.put(pokemonUUID, activity);
    }

    @Nullable
    public static WorkActivity activity(UUID pokemonUUID) {
        return ACTIVITY.get(pokemonUUID);
    }

    public static void clearActivity() {
        ACTIVITY.clear();
    }

    // --- Work status (interact wheel) ---

    public static void putStatus(UUID entityUUID, Status status) {
        STATUS.put(entityUUID, status);
    }

    /**
     * The status sent for this entity, removing it so a later wheel never reuses a stale value.
     * Null when the server sent none (the Pokémon can't do any work).
     */
    @Nullable
    public static Status takeStatus(UUID entityUUID) {
        return STATUS.remove(entityUUID);
    }

    // --- Nearby workers (debug overlay) ---

    public static void setDebugEntries(List<WorkerDebugPacket.Entry> entries) {
        debugEntries = List.copyOf(entries);
    }

    public static List<WorkerDebugPacket.Entry> debugEntries() {
        return debugEntries;
    }
}
