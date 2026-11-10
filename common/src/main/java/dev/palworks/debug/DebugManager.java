package dev.palworks.debug;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tracks which players have worker debugging on ({@code /palworks debug}), off by default. It shows
 * them:
 * <ul>
 *   <li>job activity (assignments, completions, etc.) in chat;</li>
 *   <li>an overlay in the world: each nearby worker's job icon above its head, its path and its
 *       job's block (sent by {@link WorkerDebugSync}).</li>
 * </ul>
 */
public class DebugManager {
    private static final Set<UUID> debugging = ConcurrentHashMap.newKeySet();

    /** Whether this player has worker debugging on. */
    public static boolean isDebugging(UUID playerUUID) {
        return debugging.contains(playerUUID);
    }

    /** Toggle worker debugging for a player; returns the new state. */
    public static boolean toggleDebugging(UUID playerUUID) {
        if (debugging.remove(playerUUID)) {
            return false;
        }
        debugging.add(playerUUID);
        return true;
    }

    /** Send a worker-activity line to every player in the level who has opted in. */
    public static void broadcastActivity(ServerLevel level, String message) {
        Component line = Component.literal("§7[Palworks] §f" + message);
        for (ServerPlayer player : level.players()) {
            if (isDebugging(player.getUUID())) {
                player.sendSystemMessage(line);
            }
        }
    }
}
