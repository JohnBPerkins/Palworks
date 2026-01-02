package dev.palworks.station;

import dev.palworks.Palworks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Helper for interacting with furnaces, blast furnaces, and smokers.
 * Handles detection of work opportunities and ignition.
 */
public class FurnaceHelper {
    private static final boolean DEBUG = true;

    /**
     * Check if a furnace has items in the input slot and needs fuel.
     * Returns true if there's work to be done (items to smelt but not currently burning).
     */
    public static boolean needsFuel(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Check if there's an item in the input slot
        boolean hasInput = !furnace.getItem(0).isEmpty();
        if (!hasInput) {
            return false;
        }

        // Check if not currently lit
        BlockState state = level.getBlockState(pos);
        boolean isLit = false;
        if (state.hasProperty(BlockStateProperties.LIT)) {
            isLit = state.getValue(BlockStateProperties.LIT);
        }

        boolean needsFuel = hasInput && !isLit;

        if (DEBUG && needsFuel) {
            Palworks.LOGGER.debug("Furnace at {} needs fuel (hasInput: {}, isLit: {})", pos, hasInput, isLit);
        }

        return needsFuel;
    }

    /**
     * Ignite a furnace by setting its burn time.
     * This simulates a Kindling worker providing heat without consuming fuel items.
     *
     * @param level The world
     * @param pos The furnace position
     * @param burnDuration How long the furnace should burn (in ticks)
     * @return true if successfully ignited
     */
    public static boolean igniteFurnace(Level level, BlockPos pos, int burnDuration) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Check if there's something to smelt
        if (furnace.getItem(0).isEmpty()) {
            return false;
        }

        try {
            // Use reflection to set burn time (fields are protected)
            java.lang.reflect.Field litTimeField = AbstractFurnaceBlockEntity.class.getDeclaredField("litTime");
            java.lang.reflect.Field litDurationField = AbstractFurnaceBlockEntity.class.getDeclaredField("litDuration");

            litTimeField.setAccessible(true);
            litDurationField.setAccessible(true);

            litTimeField.setInt(furnace, burnDuration);
            litDurationField.setInt(furnace, burnDuration);

            // Update the lit state
            BlockState state = level.getBlockState(pos);
            if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
                level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), 3);
            }

            furnace.setChanged();
            return true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Reflection failed - this needs platform-specific access widener
            // For now, just return false
            return false;
        }
    }

    /**
     * Get the burn duration for a Kindling worker.
     * Slightly more than 200 ticks to ensure complete smelting.
     *
     * @return Burn duration in ticks
     */
    public static int getBurnDuration() {
        // 201 ticks to ensure the item completes (200 ticks + small buffer)
        return 201;
    }

    /**
     * Check if a furnace is currently lit and burning.
     */
    public static boolean isLit(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.LIT)) {
            return state.getValue(BlockStateProperties.LIT);
        }

        return false;
    }

    /**
     * Get the remaining burn time for a furnace.
     * Uses reflection to access protected field.
     */
    public static int getRemainingBurnTime(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            try {
                java.lang.reflect.Field litTimeField = AbstractFurnaceBlockEntity.class.getDeclaredField("litTime");
                litTimeField.setAccessible(true);
                return litTimeField.getInt(furnace);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Check if a furnace has finished smelting (output slot is ready).
     */
    public static boolean hasOutput(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            return !furnace.getItem(2).isEmpty(); // Slot 2 is output
        }
        return false;
    }

    /**
     * Check if a furnace can accept more work.
     * Returns true if input has items and output isn't full.
     */
    public static boolean canWork(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Has input
        if (furnace.getItem(0).isEmpty()) {
            return false;
        }

        // Output not full (vanilla logic allows 64 max)
        if (!furnace.getItem(2).isEmpty() && furnace.getItem(2).getCount() >= 64) {
            return false;
        }

        return true;
    }

    /**
     * Check if a furnace has MORE items to smelt after the current one.
     * Returns true if input slot has 2+ items (meaning there's a queue).
     */
    public static boolean hasQueuedItems(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }

        // Check if input slot has MORE than 1 item (meaning items are queued)
        return furnace.getItem(0).getCount() > 1;
    }
}
