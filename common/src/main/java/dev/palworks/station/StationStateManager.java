package dev.palworks.station;

import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages StationState storage and retrieval for work stations.
 * Stores state in block entity NBT data or in a world-level map for blocks without BlockEntities.
 */
public class StationStateManager {
    private static final String STATION_STATE_KEY = "palworks:station_state";

    // Fallback storage for blocks that don't have BlockEntities
    private static final Map<String, Map<BlockPos, StationState>> worldStationStates = new HashMap<>();

    /**
     * Get the StationState for a block entity, creating a new one if it doesn't exist.
     * Requires a JobType for station creation.
     */
    public static StationState getOrCreate(BlockEntity blockEntity, JobType jobType) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess());

        if (tag.contains(STATION_STATE_KEY)) {
            CompoundTag stateTag = tag.getCompound(STATION_STATE_KEY);
            return StationState.fromNBT(stateTag);
        } else {
            StationState state = new StationState(blockEntity.getBlockPos(), jobType);
            save(blockEntity, state);
            return state;
        }
    }

    /**
     * Get the StationState for a block entity if it exists, null otherwise.
     */
    @Nullable
    public static StationState get(BlockEntity blockEntity) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess());

        if (tag.contains(STATION_STATE_KEY)) {
            CompoundTag stateTag = tag.getCompound(STATION_STATE_KEY);
            return StationState.fromNBT(stateTag);
        }

        return null;
    }

    /**
     * Save StationState to a block entity.
     */
    public static void save(BlockEntity blockEntity, StationState state) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess());
        CompoundTag stateTag = state.serializeNBT();
        tag.put(STATION_STATE_KEY, stateTag);
        blockEntity.loadWithComponents(tag, blockEntity.getLevel().registryAccess());
        blockEntity.setChanged();
    }

    /**
     * Check if a block entity has StationState data.
     */
    public static boolean hasStationState(BlockEntity blockEntity) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess());
        return tag.contains(STATION_STATE_KEY);
    }

    /**
     * Remove StationState from a block entity.
     */
    public static void remove(BlockEntity blockEntity) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess());
        tag.remove(STATION_STATE_KEY);
        blockEntity.loadWithComponents(tag, blockEntity.getLevel().registryAccess());
        blockEntity.setChanged();
    }

    /**
     * Update the StationState for a block entity using a callback.
     * Handles loading, modification, and saving automatically.
     * Requires a JobType for station creation if it doesn't exist.
     */
    public static void update(BlockEntity blockEntity, JobType jobType, java.util.function.Consumer<StationState> updater) {
        StationState state = getOrCreate(blockEntity, jobType);
        updater.accept(state);
        save(blockEntity, state);
    }

    // Fallback methods for blocks without BlockEntities (vanilla furnaces, crafting tables, etc.)

    /**
     * Get or create StationState for a block position in a world (fallback storage).
     * Requires a JobType for station creation.
     */
    public static StationState getOrCreate(Level level, BlockPos pos, JobType jobType) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, StationState> worldMap = worldStationStates.computeIfAbsent(worldKey, k -> new HashMap<>());
        return worldMap.computeIfAbsent(pos.immutable(), k -> new StationState(pos, jobType));
    }

    /**
     * Get StationState for a block position in a world if it exists.
     */
    @Nullable
    public static StationState get(Level level, BlockPos pos) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, StationState> worldMap = worldStationStates.get(worldKey);
        return worldMap != null ? worldMap.get(pos) : null;
    }

    /**
     * Save StationState for a block position in a world (fallback storage).
     */
    public static void save(Level level, BlockPos pos, StationState state) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, StationState> worldMap = worldStationStates.computeIfAbsent(worldKey, k -> new HashMap<>());
        worldMap.put(pos.immutable(), state);
    }

    /**
     * Remove StationState for a block position in a world.
     */
    public static void remove(Level level, BlockPos pos) {
        String worldKey = getWorldKey(level);
        Map<BlockPos, StationState> worldMap = worldStationStates.get(worldKey);
        if (worldMap != null) {
            worldMap.remove(pos);
        }
    }

    /**
     * Clear all fallback station states for a world (call on world unload).
     */
    public static void clearWorld(Level level) {
        String worldKey = getWorldKey(level);
        worldStationStates.remove(worldKey);
    }

    private static String getWorldKey(Level level) {
        // Use dimension location as unique key
        return level.dimension().location().toString();
    }
}
