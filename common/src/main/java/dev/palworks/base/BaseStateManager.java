package dev.palworks.base;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages BaseState storage and retrieval for Palworks bases.
 * Stores bases in a world-level map keyed by base position.
 */
public class BaseStateManager {
    // World dimension -> Base position -> BaseState
    private static final Map<String, Map<BlockPos, BaseState>> worldBases = new HashMap<>();

    /**
     * Get or create a BaseState at the given position.
     */
    public static BaseState getOrCreate(Level level, BlockPos pos) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = worldBases.computeIfAbsent(worldKey, k -> new HashMap<>());

        return bases.computeIfAbsent(pos.immutable(), k -> {
            BaseState state = new BaseState();
            state.setBasePos(pos.immutable());
            return state;
        });
    }

    /**
     * Get a BaseState at the given position if it exists.
     */
    @Nullable
    public static BaseState get(Level level, BlockPos pos) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = worldBases.get(worldKey);
        return bases != null ? bases.get(pos) : null;
    }

    /**
     * Save a BaseState at the given position.
     */
    public static void save(Level level, BlockPos pos, BaseState state) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = worldBases.computeIfAbsent(worldKey, k -> new HashMap<>());
        bases.put(pos.immutable(), state);
    }

    /**
     * Remove a BaseState at the given position.
     */
    public static void remove(Level level, BlockPos pos) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = worldBases.get(worldKey);
        if (bases != null) {
            bases.remove(pos);
        }
    }

    /**
     * Check if a base exists at the given position.
     */
    public static boolean hasBase(Level level, BlockPos pos) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = worldBases.get(worldKey);
        return bases != null && bases.containsKey(pos);
    }

    /**
     * Get all bases in a world.
     */
    public static Map<BlockPos, BaseState> getAllBases(Level level) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = worldBases.get(worldKey);
        return bases != null ? new HashMap<>(bases) : new HashMap<>();
    }

    /**
     * Find the nearest base to a position within a given radius.
     */
    @Nullable
    public static BaseState findNearestBase(Level level, BlockPos pos, int maxRadius) {
        Map<BlockPos, BaseState> bases = getAllBases(level);

        BaseState nearest = null;
        double nearestDistSq = maxRadius * maxRadius;

        for (Map.Entry<BlockPos, BaseState> entry : bases.entrySet()) {
            BlockPos basePos = entry.getKey();
            double distSq = basePos.distSqr(pos);

            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry.getValue();
            }
        }

        return nearest;
    }

    /**
     * Update a BaseState using a callback.
     */
    public static void update(Level level, BlockPos pos, java.util.function.Consumer<BaseState> updater) {
        BaseState state = getOrCreate(level, pos);
        updater.accept(state);
        save(level, pos, state);
    }

    /**
     * Clear all bases for a world (call on world unload).
     */
    public static void clearWorld(Level level) {
        String worldKey = getWorldKey(level);
        worldBases.remove(worldKey);
    }

    /**
     * Serialize all bases for a world to NBT (for save data).
     */
    public static CompoundTag serializeWorld(Level level) {
        CompoundTag worldTag = new CompoundTag();
        Map<BlockPos, BaseState> bases = getAllBases(level);

        for (Map.Entry<BlockPos, BaseState> entry : bases.entrySet()) {
            BlockPos pos = entry.getKey();
            BaseState state = entry.getValue();

            String key = pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
            worldTag.put(key, state.serializeNBT());
        }

        return worldTag;
    }

    /**
     * Deserialize all bases for a world from NBT (for load data).
     */
    public static void deserializeWorld(Level level, CompoundTag worldTag) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, BaseState> bases = new HashMap<>();

        for (String key : worldTag.getAllKeys()) {
            String[] parts = key.split("_");
            if (parts.length == 3) {
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);

                    BlockPos pos = new BlockPos(x, y, z);
                    BaseState state = BaseState.fromNBT(worldTag.getCompound(key));
                    bases.put(pos, state);
                } catch (NumberFormatException e) {
                    // Skip invalid entries
                }
            }
        }

        worldBases.put(worldKey, bases);
    }

    private static String getWorldKey(Level level) {
        return level.dimension().location().toString();
    }
}
