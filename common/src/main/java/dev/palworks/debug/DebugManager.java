package dev.palworks.debug;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players have opted in to worker debugging, both off by default:
 * <ul>
 *   <li>{@code /palworks debug}: job activity (assignments, completions, etc.) in chat;</li>
 *   <li>{@code /palworks debug overlay}: each nearby worker's job icon above its head and its path
 *       (sent by {@link WorkerDebugSync}).</li>
 * </ul>
 *
 */
public class DebugManager {
    private static final Map<UUID, Boolean> workerActivityChat = new HashMap<>();
    private static final Set<UUID> workerOverlay = ConcurrentHashMap.newKeySet();

    /** Whether this player sees the in-world worker overlay (job icons above heads, paths). */
    public static boolean showsWorkerOverlay(UUID playerUUID) {
        return workerOverlay.contains(playerUUID);
    }

    /** Toggle the in-world worker overlay for a player; returns the new state. */
    public static boolean toggleWorkerOverlay(UUID playerUUID) {
        if (workerOverlay.remove(playerUUID)) {
            return false;
        }
        workerOverlay.add(playerUUID);
        return true;
    }

    /** Players with the overlay on. */
    public static Set<UUID> workerOverlayPlayers() {
        return workerOverlay;
    }

    /** Whether this player currently sees worker activity in chat. */
    public static boolean showsWorkerActivity(UUID playerUUID) {
        return workerActivityChat.getOrDefault(playerUUID, false);
    }

    /** Toggle worker-activity chat for a player; returns the new state. */
    public static boolean toggleWorkerActivity(UUID playerUUID) {
        boolean next = !showsWorkerActivity(playerUUID);
        workerActivityChat.put(playerUUID, next);
        return next;
    }

    /** Send a worker-activity line to every player in the level who has opted in. */
    public static void broadcastActivity(ServerLevel level, String message) {
        Component line = Component.literal("§7[Palworks] §f" + message);
        for (ServerPlayer player : level.players()) {
            if (showsWorkerActivity(player.getUUID())) {
                player.sendSystemMessage(line);
            }
        }
    }
}
