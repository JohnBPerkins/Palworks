package dev.palworks.inventory;

import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu (Container) for the worker inventory GUI.
 * Handles server-side slot management and item transfer.
 */
public final class WorkerInventoryMenu extends AbstractContainerMenu {
    private final UUID pokemonUUID;
    // Whether the screen stays open for this player (server side; always true on the client)
    private final Predicate<Player> staysOpenFor;
    // What the worker carried when the screen opened, sent to the client with it, so it shows the
    // slots the worker uses (one a Transporting level, or the single seed-pouch slot) where they
    // belong. If either changes, the server closes the screen (see ModMenuTypes.menuFor).
    private final CarryMode carryMode;
    private final int capacity;

    // Slot indices
    private static final int WORKER_SLOTS_START = 0;
    private static final int WORKER_SLOTS_END = WorkerInventory.INVENTORY_SIZE;
    private static final int PLAYER_INVENTORY_START = WORKER_SLOTS_END;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 27;
    private static final int PLAYER_HOTBAR_START = PLAYER_INVENTORY_END;
    private static final int PLAYER_HOTBAR_END = PLAYER_HOTBAR_START + 9;

    /**
     * Server-side constructor. The screen closes once {@code staysOpenFor} is false.
     */
    public WorkerInventoryMenu(
            MenuType<?> menuType,
            int containerId,
            Inventory playerInventory,
            Container workerInventory,
            UUID pokemonUUID,
            CarryMode carryMode,
            int capacity,
            Predicate<Player> staysOpenFor) {
        super(menuType, containerId);
        this.pokemonUUID = pokemonUUID;
        this.staysOpenFor = staysOpenFor;
        this.carryMode = carryMode;
        this.capacity = capacity;

        checkContainerSize(workerInventory, WorkerInventory.INVENTORY_SIZE);

        // Worker inventory slots, the ones it uses centred in the row (the others are hidden)
        for (int i = 0; i < WorkerInventory.INVENTORY_SIZE; i++) {
            addSlot(new WorkerSlot(workerInventory, i, slotX(i, carryMode, capacity), 20));
        }

        // Player inventory (3 rows of 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
            }
        }

        // Player hotbar (1 row of 9)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 109));
        }
    }

    /**
     * Client-side constructor (creates empty container that will be synced).
     */
    public WorkerInventoryMenu(
            MenuType<?> menuType,
            int containerId,
            Inventory playerInventory,
            UUID pokemonUUID,
            CarryMode carryMode,
            int capacity) {
        this(
                menuType,
                containerId,
                playerInventory,
                new SimpleContainer(WorkerInventory.INVENTORY_SIZE),
                pokemonUUID,
                carryMode,
                capacity,
                player -> true);
    }

    /**
     * Where a worker slot goes across the screen: the ones its mode uses side by side from slot 0,
     * centred (a lone slot at x=80, the middle of the 176-wide screen).
     */
    static int slotX(int slot, CarryMode mode, int capacity) {
        int shown = mode == CarryMode.SEED_POUCH ? 1 : Math.clamp(capacity, 1, WorkerInventory.INVENTORY_SIZE);
        return 80 - 9 * (shown - 1) + 18 * slot;
    }

    public UUID getPokemonUUID() {
        return pokemonUUID;
    }

    public CarryMode getCarryMode() {
        return carryMode;
    }

    public int getCapacity() {
        return capacity;
    }

    /** Whether the worker uses this slot (the rest are hidden). */
    boolean usesSlot(int slot) {
        return carryMode.usesSlot(slot, capacity);
    }

    /** A worker inventory slot: hidden unless the worker's carry mode uses it, and filtered by it. */
    private class WorkerSlot extends Slot {
        WorkerSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return usesSlot(getContainerSlot());
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && WorkerInventory.accepts(getCarryMode(), stack);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return staysOpenFor.test(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);

        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            result = slotStack.copy();

            // Moving from worker inventory
            if (slotIndex < WORKER_SLOTS_END) {
                // Try to move to player inventory (prefer hotbar first)
                if (!moveItemStackTo(slotStack, PLAYER_HOTBAR_START, PLAYER_HOTBAR_END, false)) {
                    if (!moveItemStackTo(slotStack, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }
            // Moving from player inventory
            else if (slotIndex < PLAYER_INVENTORY_END) {
                // Try to move to worker inventory
                if (!moveItemStackTo(slotStack, WORKER_SLOTS_START, WORKER_SLOTS_END, false)) {
                    // If can't go to worker, try hotbar
                    if (!moveItemStackTo(slotStack, PLAYER_HOTBAR_START, PLAYER_HOTBAR_END, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }
            // Moving from hotbar
            else if (slotIndex < PLAYER_HOTBAR_END) {
                // Try to move to worker inventory
                if (!moveItemStackTo(slotStack, WORKER_SLOTS_START, WORKER_SLOTS_END, false)) {
                    // If can't go to worker, try main inventory
                    if (!moveItemStackTo(slotStack, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }

            if (slotStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (slotStack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, slotStack);
        }

        return result;
    }
}
