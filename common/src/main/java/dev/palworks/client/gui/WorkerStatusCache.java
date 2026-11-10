package dev.palworks.client.gui;

import dev.palworks.inventory.CarryMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side copy of a Pokemon's work status (working or not, and what it can carry), filled by
 * {@code WorkerStatusPacket} just before its interact wheel opens and read once when the wheel is
 * built. Only used to draw the wheel's options; the server never trusts it. Has no client-only imports, so it is safe to load on a
 * dedicated server (where Cobblemon still constructs the client packet handlers).
 */
public final class WorkerStatusCache {
    /** What the server said about one Pokemon. */
    public record Status(boolean enabled, CarryMode carryMode) {}

    private static final Map<UUID, Status> STATUS = new ConcurrentHashMap<>();

    private WorkerStatusCache() {}

    public static void put(UUID entityUUID, Status status) {
        STATUS.put(entityUUID, status);
    }

    /**
     * The status sent for this entity, removing it so a later wheel never reuses a stale value.
     * Null when the server sent none (the Pokemon can't do any work).
     */
    @Nullable
    public static Status take(UUID entityUUID) {
        return STATUS.remove(entityUUID);
    }
}
