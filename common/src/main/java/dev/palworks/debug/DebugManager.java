package dev.palworks.debug;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages debug state and provides debug information.
 * Tracks which debug overlays are enabled and caches debug data.
 */
public class DebugManager {
    // Debug toggle flags (per player)
    private static final Map<UUID, DebugFlags> playerDebugFlags = new HashMap<>();

    // Cached debug info (updated per tick for performance)
    private static final Map<UUID, DebugInfo.WorkerDebugInfo> cachedWorkerInfo = new HashMap<>();
    private static final Map<BlockPos, DebugInfo.StationDebugInfo> cachedStationInfo = new HashMap<>();
    private static final Map<BlockPos, DebugInfo.BaseDebugInfo> cachedBaseInfo = new HashMap<>();

    /**
     * Get or create debug flags for a player.
     */
    public static DebugFlags getFlags(UUID playerUUID) {
        return playerDebugFlags.computeIfAbsent(playerUUID, k -> new DebugFlags());
    }

    /**
     * Toggle a debug flag for a player.
     */
    public static void toggle(UUID playerUUID, DebugFlag flag) {
        DebugFlags flags = getFlags(playerUUID);
        switch (flag) {
            case WORKER_INFO -> flags.showWorkerInfo = !flags.showWorkerInfo;
            case STATION_INFO -> flags.showStationInfo = !flags.showStationInfo;
            case BASE_INFO -> flags.showBaseInfo = !flags.showBaseInfo;
            case BASE_RADIUS -> flags.showBaseRadius = !flags.showBaseRadius;
            case WORKER_PATHS -> flags.showWorkerPaths = !flags.showWorkerPaths;
            case JOB_TARGETS -> flags.showJobTargets = !flags.showJobTargets;
            case ALL -> {
                boolean newState = !flags.isAnyEnabled();
                flags.showWorkerInfo = newState;
                flags.showStationInfo = newState;
                flags.showBaseInfo = newState;
                flags.showBaseRadius = newState;
                flags.showWorkerPaths = newState;
                flags.showJobTargets = newState;
            }
        }
    }

    /**
     * Update cached debug info for a worker.
     */
    public static void updateWorkerInfo(UUID workerUUID, Pokemon pokemon, @Nullable Mob entity) {
        DebugInfo.WorkerDebugInfo info = DebugInfo.getWorkerInfo(pokemon, entity);
        if (info != null) {
            cachedWorkerInfo.put(workerUUID, info);
        } else {
            cachedWorkerInfo.remove(workerUUID);
        }
    }

    /**
     * Update cached debug info for a station.
     */
    public static void updateStationInfo(Level level, BlockPos pos) {
        DebugInfo.StationDebugInfo info = DebugInfo.getStationInfo(level, pos);
        if (info != null) {
            cachedStationInfo.put(pos, info);
        } else {
            cachedStationInfo.remove(pos);
        }
    }

    /**
     * Update cached debug info for a base.
     */
    public static void updateBaseInfo(Level level, BlockPos pos) {
        DebugInfo.BaseDebugInfo info = DebugInfo.getBaseInfo(level, pos);
        if (info != null) {
            cachedBaseInfo.put(pos, info);
        } else {
            cachedBaseInfo.remove(pos);
        }
    }

    /**
     * Get cached worker debug info.
     */
    @Nullable
    public static DebugInfo.WorkerDebugInfo getWorkerInfo(UUID workerUUID) {
        return cachedWorkerInfo.get(workerUUID);
    }

    /**
     * Get cached station debug info.
     */
    @Nullable
    public static DebugInfo.StationDebugInfo getStationInfo(BlockPos pos) {
        return cachedStationInfo.get(pos);
    }

    /**
     * Get cached base debug info.
     */
    @Nullable
    public static DebugInfo.BaseDebugInfo getBaseInfo(BlockPos pos) {
        return cachedBaseInfo.get(pos);
    }

    /**
     * Get all cached worker info.
     */
    public static Map<UUID, DebugInfo.WorkerDebugInfo> getAllWorkerInfo() {
        return new HashMap<>(cachedWorkerInfo);
    }

    /**
     * Get all cached station info.
     */
    public static Map<BlockPos, DebugInfo.StationDebugInfo> getAllStationInfo() {
        return new HashMap<>(cachedStationInfo);
    }

    /**
     * Get all cached base info.
     */
    public static Map<BlockPos, DebugInfo.BaseDebugInfo> getAllBaseInfo() {
        return new HashMap<>(cachedBaseInfo);
    }

    /**
     * Clear all cached debug info.
     */
    public static void clearCache() {
        cachedWorkerInfo.clear();
        cachedStationInfo.clear();
        cachedBaseInfo.clear();
    }

    /**
     * Clear cached info for a specific worker.
     */
    public static void clearWorkerCache(UUID workerUUID) {
        cachedWorkerInfo.remove(workerUUID);
    }

    /**
     * Debug flags for a player.
     */
    public static class DebugFlags {
        public boolean showWorkerInfo = false;
        public boolean showStationInfo = false;
        public boolean showBaseInfo = false;
        public boolean showBaseRadius = false;
        public boolean showWorkerPaths = false;
        public boolean showJobTargets = false;

        public boolean isAnyEnabled() {
            return showWorkerInfo || showStationInfo || showBaseInfo ||
                   showBaseRadius || showWorkerPaths || showJobTargets;
        }

        public String getStatusString() {
            if (!isAnyEnabled()) {
                return "Debug: OFF";
            }

            StringBuilder sb = new StringBuilder("Debug: ");
            if (showWorkerInfo) sb.append("Workers ");
            if (showStationInfo) sb.append("Stations ");
            if (showBaseInfo) sb.append("Bases ");
            if (showBaseRadius) sb.append("Radius ");
            if (showWorkerPaths) sb.append("Paths ");
            if (showJobTargets) sb.append("Targets ");
            return sb.toString().trim();
        }
    }

    /**
     * Debug flag types.
     */
    public enum DebugFlag {
        WORKER_INFO,
        STATION_INFO,
        BASE_INFO,
        BASE_RADIUS,
        WORKER_PATHS,
        JOB_TARGETS,
        ALL
    }
}
