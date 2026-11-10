package dev.palworks.station;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
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

    /** The different items in a container that match {@code filter}, in slot order. */
    public static Set<Item> itemsMatching(Level level, BlockPos pos, Predicate<Item> filter) {
        Set<Item> items = new LinkedHashSet<>();
        if (level.getBlockEntity(pos) instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && filter.test(stack.getItem())) {
                    items.add(stack.getItem());
                }
            }
        }
        return items;
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
            if (ItemStack.isSameItemSameComponents(slotStack, stack)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add as much of a stack to a chest as fits: first onto matching stacks, then into empty slots.
     * Doesn't modify {@code stack}. Returns how many items didn't fit (the whole count if the block
     * isn't a container).
     */
    public static int insertItems(Level level, BlockPos chestPos, ItemStack stack) {
        BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof Container container)) {
            return stack.getCount();
        }

        int remaining = stack.getCount();
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slotStack = container.getItem(i);
            if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(slotStack, stack)) {
                int toAdd = Math.min(slotStack.getMaxStackSize() - slotStack.getCount(), remaining);
                if (toAdd > 0) {
                    slotStack.grow(toAdd);
                    remaining -= toAdd;
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (container.getItem(i).isEmpty()) {
                int toAdd = Math.min(remaining, stack.getMaxStackSize());
                container.setItem(i, stack.copyWithCount(toAdd));
                remaining -= toAdd;
            }
        }

        if (remaining < stack.getCount()) {
            container.setChanged();
        }
        return remaining;
    }

    /**
     * Put back the part of an extracted stack the worker couldn't carry (it used to be deleted),
     * dropping it next to the chest if the chest has since filled up.
     */
    public static void returnLeftover(Level level, BlockPos chestPos, ItemStack extracted, int leftover) {
        if (leftover <= 0) {
            return;
        }
        int stillLeft = insertItems(level, chestPos, extracted.copyWithCount(leftover));
        if (stillLeft > 0) {
            net.minecraft.world.level.block.Block.popResource(
                    level, chestPos.above(), extracted.copyWithCount(stillLeft));
        }
    }
}
