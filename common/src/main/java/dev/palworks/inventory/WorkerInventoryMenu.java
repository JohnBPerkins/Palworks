package dev.palworks.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Menu (Container) for the worker inventory GUI.
 * Handles server-side slot management and item transfer.
 */
public class WorkerInventoryMenu extends AbstractContainerMenu {
    private final Container workerInventory;
    private final UUID pokemonUUID;

    // Slot indices
    private static final int WORKER_SLOTS_START = 0;
    private static final int WORKER_SLOTS_END = WorkerInventory.INVENTORY_SIZE;
    private static final int PLAYER_INVENTORY_START = WORKER_SLOTS_END;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 27;
    private static final int PLAYER_HOTBAR_START = PLAYER_INVENTORY_END;
    private static final int PLAYER_HOTBAR_END = PLAYER_HOTBAR_START + 9;

    /**
     * Server-side constructor.
     */
    public WorkerInventoryMenu(MenuType<?> menuType, int containerId, Inventory playerInventory, Container workerInventory, UUID pokemonUUID) {
        super(menuType, containerId);
        this.workerInventory = workerInventory;
        this.pokemonUUID = pokemonUUID;

        checkContainerSize(workerInventory, WorkerInventory.INVENTORY_SIZE);

        // Worker inventory slots (3 slots, centered)
        // To center 3 slots (54px total) in 176px width: start at x=62
        int workerSlotStartX = 62;
        for (int i = 0; i < WorkerInventory.INVENTORY_SIZE; i++) {
            addSlot(new Slot(workerInventory, i, workerSlotStartX + i * 18, 20));
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
    public WorkerInventoryMenu(MenuType<?> menuType, int containerId, Inventory playerInventory, UUID pokemonUUID) {
        this(menuType, containerId, playerInventory, new SimpleContainer(WorkerInventory.INVENTORY_SIZE), pokemonUUID);
    }

    public UUID getPokemonUUID() {
        return pokemonUUID;
    }

    @Override
    public boolean stillValid(Player player) {
        return workerInventory.stillValid(player);
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
