package dev.palworks.client.gui;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side copy of whether a Pokemon is working, filled by {@code WorkerStatusPacket} just
 * before its interact wheel opens and read once when the wheel is built. Only used to draw the
 * toggle; the server never trusts it. Has no client-only imports, so it is safe to load on a
 * dedicated server (where Cobblemon still constructs the client packet handlers).
 */
public final class WorkerStatusCache {
    private static final Map<UUID, Boolean> STATUS = new ConcurrentHashMap<>();

    private WorkerStatusCache() {}

    public static void put(UUID entityUUID, boolean enabled) {
        STATUS.put(entityUUID, enabled);
    }

    /**
     * The status sent for this entity, removing it so a later wheel never reuses a stale value.
     * Null when the server sent none (the Pokemon can't do any work).
     */
    @Nullable
    public static Boolean take(UUID entityUUID) {
        return STATUS.remove(entityUUID);
    }
}
