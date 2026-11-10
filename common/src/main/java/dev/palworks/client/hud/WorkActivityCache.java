package dev.palworks.client.hud;

import dev.palworks.worker.WorkActivity;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side copy of what the player's workers are doing, filled by {@code WorkActivityPacket}
 * and read by {@link WorkStatusHud}. Keyed by the Pokemon's UUID (the party's). Has no client-only
 * imports, so it is safe to load on a dedicated server (where Cobblemon still constructs the client
 * packet handlers).
 */
public final class WorkActivityCache {
    private static final Map<UUID, WorkActivity> ACTIVITY = new ConcurrentHashMap<>();

    private WorkActivityCache() {}

    public static void put(UUID pokemonUUID, WorkActivity activity) {
        ACTIVITY.put(pokemonUUID, activity);
    }

    @Nullable
    public static WorkActivity get(UUID pokemonUUID) {
        return ACTIVITY.get(pokemonUUID);
    }

    public static void clear() {
        ACTIVITY.clear();
    }
}
