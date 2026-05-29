package dev.palworks.debug;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which players have opted in (via {@code /palworks debug}) to seeing worker job activity
 * — assignments, completions, etc. — broadcast in chat. Off by default.
 *
 * <p>This is the only live debug state. The old per-player render flags, the worker/station info
 * caches, and the geometry renderer were never wired to anything and have been removed.
 */
public class DebugManager {
    private static final Map<UUID, Boolean> workerActivityChat = new HashMap<>();

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
