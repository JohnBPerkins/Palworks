package dev.palworks.station;

import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects job types from vanilla and custom blocks.
 * Maps block types to their associated JobType.
 */
public class StationDetector {
    // Block -> JobType mappings
    private static final Map<Block, JobType> BLOCK_JOB_MAP = new HashMap<>();

    static {
        // Kindling - Furnaces and cooking blocks
        BLOCK_JOB_MAP.put(Blocks.FURNACE, JobType.FUEL_FURNACE);
        BLOCK_JOB_MAP.put(Blocks.BLAST_FURNACE, JobType.FUEL_FURNACE);
        BLOCK_JOB_MAP.put(Blocks.SMOKER, JobType.FUEL_FURNACE);
        BLOCK_JOB_MAP.put(Blocks.CAMPFIRE, JobType.COOK_FOOD);

        // Handiwork - Crafting
        BLOCK_JOB_MAP.put(Blocks.CRAFTING_TABLE, JobType.CRAFT_ITEM);

        // Medicine Production - Brewing
        BLOCK_JOB_MAP.put(Blocks.BREWING_STAND, JobType.BREW_POTION);

        // Note: Other job types (planting, harvesting, mining, lumbering, etc.)
        // are typically location-based rather than block-based, so they're
        // detected differently (e.g., crop blocks, trees, ores)
    }

    /**
     * Get the JobType associated with a block.
     * Returns null if the block is not a recognized work station.
     */
    @Nullable
    public static JobType getJobType(Block block) {
        return BLOCK_JOB_MAP.get(block);
    }

    /**
     * Get the JobType associated with a BlockState.
     * Returns null if the block is not a recognized work station.
     */
    @Nullable
    public static JobType getJobType(BlockState state) {
        return getJobType(state.getBlock());
    }

    /**
     * Get the JobType associated with a block at a position in a world.
     * Returns null if the block is not a recognized work station.
     */
    @Nullable
    public static JobType getJobType(Level level, BlockPos pos) {
        return getJobType(level.getBlockState(pos));
    }

    /**
     * Check if a block is a recognized work station.
     */
    public static boolean isWorkStation(Block block) {
        return BLOCK_JOB_MAP.containsKey(block);
    }

    /**
     * Check if a block at a position is a recognized work station.
     */
    public static boolean isWorkStation(Level level, BlockPos pos) {
        return isWorkStation(level.getBlockState(pos).getBlock());
    }

    /**
     * Register a custom block as a work station with a specific job type.
     * Useful for mod integrations.
     */
    public static void registerWorkStation(Block block, JobType jobType) {
        BLOCK_JOB_MAP.put(block, jobType);
    }

    /**
     * Unregister a work station block.
     */
    public static void unregisterWorkStation(Block block) {
        BLOCK_JOB_MAP.remove(block);
    }

    /**
     * Get all registered work station blocks.
     */
    public static Map<Block, JobType> getAllWorkStations() {
        return new HashMap<>(BLOCK_JOB_MAP);
    }
}
