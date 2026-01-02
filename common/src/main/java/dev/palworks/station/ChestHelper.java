package dev.palworks.station;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Helper for interacting with chests and other containers.
 */
public class ChestHelper {

    /**
     * Check if a container has a specific item.
     */
    public static boolean hasItem(Level level, BlockPos pos, Item item) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return false;
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract items from a container.
     *
     * @param level The world
     * @param pos The container position
     * @param item The item type to extract
     * @param maxCount Maximum number of items to extract
     * @return ItemStack containing the extracted items, or EMPTY if none found
     */
    @Nullable
    public static ItemStack extractItems(Level level, BlockPos pos, Item item, int maxCount) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return ItemStack.EMPTY;
        }

        ItemStack result = ItemStack.EMPTY;
        int remaining = maxCount;

        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                int toExtract = Math.min(remaining, stack.getCount());

                if (result.isEmpty()) {
                    result = stack.copy();
                    result.setCount(toExtract);
                } else {
                    result.grow(toExtract);
                }

                stack.shrink(toExtract);
                if (stack.isEmpty()) {
                    container.setItem(i, ItemStack.EMPTY);
                }

                remaining -= toExtract;
            }
        }

        if (!result.isEmpty()) {
            container.setChanged();
        }

        return result;
    }

    /**
     * Check if a container can accept items.
     */
    public static boolean canAcceptItems(Level level, BlockPos pos, ItemStack stack) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return false;
        }

        // Simple check: look for empty slot or matching stack with space
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slotStack = container.getItem(i);
            if (slotStack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(slotStack, stack) &&
                slotStack.getCount() < slotStack.getMaxStackSize()) {
                return true;
            }
        }

        return false;
    }
}
