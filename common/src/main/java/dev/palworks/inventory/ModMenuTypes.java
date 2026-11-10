package dev.palworks.inventory;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

/**
 * Registry for menu types used by Palworks.
 * Platform-specific registration is handled by each platform's implementation.
 */
public class ModMenuTypes {
    private static Supplier<MenuType<WorkerInventoryMenu>> workerInventorySupplier;

    /**
     * Set the worker inventory menu type supplier.
     * Called by platform-specific initialization.
     */
    public static void setWorkerInventorySupplier(Supplier<MenuType<WorkerInventoryMenu>> supplier) {
        workerInventorySupplier = supplier;
    }

    /**
     * Get the worker inventory menu type.
     */
    public static MenuType<WorkerInventoryMenu> getWorkerInventory() {
        if (workerInventorySupplier == null) {
            throw new IllegalStateException("Worker inventory menu type not registered!");
        }
        return workerInventorySupplier.get();
    }

    /**
     * Convenience supplier that matches the old API.
     */
    public static final Supplier<MenuType<WorkerInventoryMenu>> WORKER_INVENTORY = ModMenuTypes::getWorkerInventory;

    /**
     * Open the worker inventory menu for a player.
     * Uses the platform-specific MenuOpener.
     */
    public static void openWorkerInventory(ServerPlayer player, Pokemon pokemon) {
        MenuOpener opener = MenuOpener.Holder.getInstance();
        if (opener == null) {
            Palworks.LOGGER.error("MenuOpener not initialized!");
            return;
        }
        opener.openWorkerInventory(player, pokemon);
    }
}
