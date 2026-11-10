package dev.palworks.client.debug;

import dev.palworks.net.packets.WorkerDebugPacket;

import java.util.List;

/**
 * Client-side copy of the last {@link WorkerDebugPacket}: the workers near the player, for
 * {@link WorkerDebugRenderer}. Empty unless the player has {@code /palworks debug overlay} on. Has
 * no client-only imports, so it is safe to load on a dedicated server.
 */
public final class WorkerDebugCache {
    private static volatile List<WorkerDebugPacket.Entry> entries = List.of();

    private WorkerDebugCache() {}

    public static void set(List<WorkerDebugPacket.Entry> newEntries) {
        entries = List.copyOf(newEntries);
    }

    public static List<WorkerDebugPacket.Entry> get() {
        return entries;
    }
}
