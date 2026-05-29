package dev.palworks.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * A multi-slot inventory for worker Pokemon.
 * Provides 3 slots for storing items the worker collects or uses.
 */
public class WorkerInventory implements Container {
    public static final int INVENTORY_SIZE = 3;

    private final NonNullList<ItemStack> items;
    private final UUID ownerUUID;
    private Runnable onChanged;

    public WorkerInventory(UUID ownerUUID) {
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        this.ownerUUID = ownerUUID;
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public void setOnChanged(Runnable callback) {
        this.onChanged = callback;
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return ItemStack.EMPTY;
        }
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            setChanged();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return;
        }
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public void setChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    /**
     * Try to add an item to the inventory.
     * Returns the number of items that couldn't fit.
     */
    public int addItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        int remaining = stack.getCount();

        // First, try to merge with existing stacks
        for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
            ItemStack slotStack = items.get(i);
            if (ItemStack.isSameItemSameComponents(slotStack, stack)) {
                int space = slotStack.getMaxStackSize() - slotStack.getCount();
                int toAdd = Math.min(space, remaining);
                if (toAdd > 0) {
                    slotStack.grow(toAdd);
                    remaining -= toAdd;
                }
            }
        }

        // Then, try to add to empty slots
        for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
            if (items.get(i).isEmpty()) {
                int toAdd = Math.min(remaining, stack.getMaxStackSize());
                ItemStack newStack = stack.copy();
                newStack.setCount(toAdd);
                items.set(i, newStack);
                remaining -= toAdd;
            }
        }

        if (remaining < stack.getCount()) {
            setChanged();
        }

        return remaining;
    }

    /**
     * Check if there's space for at least one item.
     */
    public boolean hasSpace() {
        for (ItemStack stack : items) {
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the first item stack in the inventory (for compatibility with old single-slot system).
     */
    public ItemStack getFirstItem() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Get total item count across all slots.
     */
    public int getTotalItemCount() {
        int total = 0;
        for (ItemStack stack : items) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * Serialize to NBT.
     */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag itemsTag = new ListTag();

        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                itemsTag.add(stack.save(net.minecraft.core.RegistryAccess.EMPTY, slotTag));
            }
        }

        tag.put("Items", itemsTag);
        return tag;
    }

    /**
     * Deserialize from NBT.
     */
    public void deserializeNBT(CompoundTag tag) {
        items.clear();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            items.set(i, ItemStack.EMPTY);
        }

        if (tag.contains("Items")) {
            ListTag itemsTag = tag.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < itemsTag.size(); i++) {
                CompoundTag slotTag = itemsTag.getCompound(i);
                int slot = slotTag.getByte("Slot") & 255;
                if (slot < INVENTORY_SIZE) {
                    items.set(slot, ItemStack.parse(net.minecraft.core.RegistryAccess.EMPTY, slotTag).orElse(ItemStack.EMPTY));
                }
            }
        }
    }

    /**
     * Create from NBT.
     */
    public static WorkerInventory fromNBT(UUID ownerUUID, CompoundTag tag) {
        WorkerInventory inventory = new WorkerInventory(ownerUUID);
        inventory.deserializeNBT(tag);
        return inventory;
    }
}
