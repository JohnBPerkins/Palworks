package dev.palworks.inventory;

import dev.palworks.suitability.Suitability;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A worker Pokemon's carried items. Always 3 slots of storage, but how many it may use, and for
 * what, depends on its {@link CarryMode}: all 3 for Transporting, one seeds-only slot for planters,
 * none for everyone else. Adding items ({@link #addItem}, the GUI) respects the mode.
 */
public class WorkerInventory implements Container {
    public static final int INVENTORY_SIZE = 3;

    private final NonNullList<ItemStack> items;
    private final UUID ownerUUID;
    private Runnable onChanged;
    private CarryMode mode = CarryMode.FULL;

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

    public CarryMode getMode() {
        return mode;
    }

    /** Set from the worker's suitabilities (see WorkerInventoryManager.getOrCreate). */
    public void setMode(CarryMode mode) {
        this.mode = mode;
    }

    /** Whether this inventory's mode allows the item at all (in some slot). */
    public boolean accepts(ItemStack stack) {
        return accepts(mode, stack);
    }

    /** Whether a carry mode allows an item: anything for FULL, only plantables for a seed pouch. */
    public static boolean accepts(CarryMode mode, ItemStack stack) {
        return switch (mode) {
            case FULL -> true;
            case SEED_POUCH -> dev.palworks.crop.CropAdapter.isSeed(stack.getItem());
            case NONE -> false;
        };
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return mode.usesSlot(slot) && accepts(stack);
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

        if (!accepts(stack)) {
            return stack.getCount();
        }

        int remaining = stack.getCount();

        // First, try to merge with existing stacks
        for (int i = 0; i < INVENTORY_SIZE && remaining > 0; i++) {
            if (!mode.usesSlot(i)) {
                continue;
            }
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
            if (mode.usesSlot(i) && items.get(i).isEmpty()) {
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
     * How many of this stack would fit: into stacks of the exact same item first, then empty slots.
     * Same rules as {@link #addItem}, without adding anything.
     */
    public int roomFor(ItemStack stack) {
        if (stack.isEmpty() || !accepts(stack)) {
            return 0;
        }
        int room = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (!mode.usesSlot(i)) {
                continue;
            }
            ItemStack slotStack = items.get(i);
            if (slotStack.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(slotStack, stack)) {
                room += Math.max(0, slotStack.getMaxStackSize() - slotStack.getCount());
            }
        }
        return Math.min(room, stack.getCount());
    }

    /**
     * Whether a carried stack is cargo, to be delivered to a chest: everything except seeds (and
     * other plantables) for a worker that plants, which it keeps to plant with.
     */
    public static boolean isCargo(ItemStack stack, Set<Suitability> suitabilities) {
        if (stack.isEmpty()) {
            return false;
        }
        boolean plants = suitabilities.contains(Suitability.PLANTING) || suitabilities.contains(Suitability.NETHER);
        return !(plants && dev.palworks.crop.CropAdapter.isSeed(stack.getItem()));
    }

    /** The first carried stack that's cargo (see {@link #isCargo}), or empty if none. */
    public ItemStack firstCargo(Set<Suitability> suitabilities) {
        for (ItemStack stack : items) {
            if (isCargo(stack, suitabilities)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Whether it carries any cargo (see {@link #isCargo}). */
    public boolean hasCargo(Set<Suitability> suitabilities) {
        return !firstCargo(suitabilities).isEmpty();
    }

    /**
     * Check if there's space for at least one item.
     */
    public boolean hasSpace() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = items.get(i);
            if (mode.usesSlot(i) && (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /** Whether it carries any seed or other plantable item. */
    public boolean hasAnySeed() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && dev.palworks.crop.CropAdapter.isSeed(stack.getItem())) {
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
     * Serialize to NBT. Items' data-driven parts (enchantments, say) are saved by registry id, so it
     * needs the server's registries.
     */
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag itemsTag = new ListTag();

        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                itemsTag.add(stack.save(registries, slotTag));
            }
        }

        tag.put("Items", itemsTag);
        return tag;
    }

    /**
     * Deserialize from NBT, with the server's registries (see {@link #serializeNBT}).
     */
    public void deserializeNBT(CompoundTag tag, HolderLookup.Provider registries) {
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
                    items.set(slot, ItemStack.parse(registries, slotTag).orElse(ItemStack.EMPTY));
                }
            }
        }
    }

    /**
     * Create from NBT.
     */
    public static WorkerInventory fromNBT(UUID ownerUUID, CompoundTag tag, HolderLookup.Provider registries) {
        WorkerInventory inventory = new WorkerInventory(ownerUUID);
        inventory.deserializeNBT(tag, registries);
        return inventory;
    }
}
