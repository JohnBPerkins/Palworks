package dev.palworks.inventory;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.architectury.utils.GameInstance;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.PersistentData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
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
            // Suitabilities and levels can change (e.g. evolution)
            cached.setMode(carryModeFor(pokemon));
            cached.setCapacity(capacityFor(pokemon));
            // Saved into the Pokémon it's asked for with now: Cobblemon makes a new Pokemon object
            // for the same Pokémon when its owner logs back in, and one still out (in a pasture,
            // its chunk kept loaded) keeps this inventory, which went on saving into the old one
            cached.setOnChanged(() -> save(pokemon, cached));
            return cached;
        }

        // Load from NBT or create new
        CompoundTag persistentData = pokemon.getPersistentData();

        final WorkerInventory inventory;
        if (persistentData.contains(NBT_KEY)) {
            CompoundTag inventoryTag = persistentData.getCompound(NBT_KEY);
            inventory = WorkerInventory.fromNBT(uuid, inventoryTag, registries());
        } else {
            inventory = new WorkerInventory(uuid);
        }

        inventory.setMode(carryModeFor(pokemon));
        inventory.setCapacity(capacityFor(pokemon));

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

    /** How many slots this Pokémon carries as a transporter: one a Transporting level. */
    public static int capacityFor(Pokemon pokemon) {
        return CarryMode.slotsForLevel(
                dev.palworks.suitability.SuitabilityDetector.getSuitabilityLevel(pokemon, Suitability.TRANSPORTING));
    }

    /**
     * Save a worker inventory to the Pokemon's persistent data.
     */
    public static void save(Pokemon pokemon, WorkerInventory inventory) {
        PersistentData.put(pokemon, NBT_KEY, inventory.serializeNBT(registries()));
    }

    /**
     * The running server's registries, which items' data-driven parts (enchantments...) are saved and
     * loaded by. Inventories are only loaded and saved on the server.
     */
    private static HolderLookup.Provider registries() {
        return GameInstance.getServer().registryAccess();
    }

    /**
     * Whether {@code inventory} is still what this Pokémon carries, out in the world: what an open
     * inventory screen asks each tick. Once it's recalled, faints or stops ticking, its inventory
     * leaves the cache, and it's read from its data again (a new object) when it's back. A screen
     * left open on the old one would hand out items the worker still has too.
     */
    public static boolean isCarriedBy(Pokemon pokemon, WorkerInventory inventory) {
        PokemonEntity entity = pokemon.getEntity();
        // That very object: an inventory equals only itself
        return entity != null && entity.isAlive() && inventory.equals(inventoryCache.get(pokemon.getUuid()));
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
     * Get the total item count in a Pokemon's worker inventory.
     */
    public static int getTotalItemCount(Pokemon pokemon) {
        WorkerInventory inventory = getOrCreate(pokemon);
        return inventory.getTotalItemCount();
    }
}
