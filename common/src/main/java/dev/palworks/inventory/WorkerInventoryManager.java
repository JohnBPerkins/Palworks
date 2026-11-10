package dev.palworks.inventory;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;

/**
 * Manages worker inventories, storing them in the Pokemon's persistent data.
 * Inventories are loaded on demand and cached for performance.
 */
public class WorkerInventoryManager {
    private static final String NBT_KEY = "palworks:worker_inventory";

    // Cache of loaded inventories (cleared when Pokemon unloads)
    private static final Map<UUID, WorkerInventory> inventoryCache = new ConcurrentHashMap<>();

    /**
     * Get or create a worker inventory for a Pokemon.
     */
    public static WorkerInventory getOrCreate(Pokemon pokemon) {
        UUID uuid = pokemon.getUuid();

        // Check cache first
        WorkerInventory cached = inventoryCache.get(uuid);
        if (cached != null) {
            cached.setMode(carryModeFor(pokemon)); // suitabilities can change (e.g. evolution)
            return cached;
        }

        // Load from NBT or create new
        CompoundTag persistentData = pokemon.getPersistentData();

        // Check for old single-slot inventory data to migrate first
        if (!persistentData.contains(NBT_KEY)) {
            WorkerState oldState = WorkerStateManager.get(pokemon);
            if (oldState != null) {
                migrateFromOldSystem(pokemon, oldState);
            }
        }

        // Now load or create the inventory
        final WorkerInventory inventory;
        if (persistentData.contains(NBT_KEY)) {
            CompoundTag inventoryTag = persistentData.getCompound(NBT_KEY);
            inventory = WorkerInventory.fromNBT(uuid, inventoryTag);
        } else {
            inventory = new WorkerInventory(uuid);
        }

        inventory.setMode(carryModeFor(pokemon));

        // Set up auto-save on change
        inventory.setOnChanged(() -> save(pokemon, inventory));

        // Cache it
        inventoryCache.put(uuid, inventory);

        return inventory;
    }

    /** What this Pokemon may carry, from its suitabilities (its species and types as they are now). */
    public static CarryMode carryModeFor(Pokemon pokemon) {
        return CarryMode.forSuitabilities(dev.palworks.suitability.SuitabilityDetector.getSuitabilities(pokemon));
    }

    /**
     * Save a worker inventory to the Pokemon's persistent data.
     */
    public static void save(Pokemon pokemon, WorkerInventory inventory) {
        CompoundTag persistentData = pokemon.getPersistentData();
        persistentData.put(NBT_KEY, inventory.serializeNBT());
    }

    /**
     * Remove inventory from cache (call when Pokemon unloads).
     */
    public static void unload(UUID pokemonUUID) {
        inventoryCache.remove(pokemonUUID);
    }

    /**
     * Clear all cached inventories.
     */
    public static void clearCache() {
        inventoryCache.clear();
    }

    /**
     * Migrate from old single-slot workInventory to new multi-slot system.
     * Call this when loading a worker that might have old data.
     */
    public static void migrateFromOldSystem(Pokemon pokemon, WorkerState oldState) {
        // Check if old workInventory has items
        net.minecraft.world.item.ItemStack oldInventory = oldState.getWorkInventory();
        if (!oldInventory.isEmpty()) {
            Palworks.LOGGER.info("Migrating old work inventory to new system for Pokemon {}", pokemon.getUuid());

            WorkerInventory newInventory = getOrCreate(pokemon);
            int leftover = newInventory.addItem(oldInventory);

            if (leftover > 0) {
                Palworks.LOGGER.warn("Could not migrate {} items - new inventory full!", leftover);
            }

            // Clear old inventory
            oldState.setWorkInventory(net.minecraft.world.item.ItemStack.EMPTY);
            WorkerStateManager.save(pokemon, oldState);
            save(pokemon, newInventory);
        }
    }

    /**
     * Check if a Pokemon has any items in their worker inventory.
     */
    public static boolean hasItems(Pokemon pokemon) {
        WorkerInventory inventory = getOrCreate(pokemon);
        return !inventory.isEmpty();
    }

    /**
     * Get the total item count in a Pokemon's worker inventory.
     */
    public static int getTotalItemCount(Pokemon pokemon) {
        WorkerInventory inventory = getOrCreate(pokemon);
        return inventory.getTotalItemCount();
    }
}
