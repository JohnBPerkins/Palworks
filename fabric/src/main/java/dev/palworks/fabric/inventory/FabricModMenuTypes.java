package dev.palworks.fabric.inventory;

import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventoryMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

import java.util.UUID;

/**
 * Fabric-specific menu type registration.
 */
public class FabricModMenuTypes {
    public static MenuType<WorkerInventoryMenu> WORKER_INVENTORY;

    public static void register() {
        WORKER_INVENTORY = Registry.register(
                BuiltInRegistries.MENU,
                ResourceLocation.fromNamespaceAndPath("palworks", "worker_inventory"),
                new ExtendedScreenHandlerType<>(
                        (containerId, playerInventory, pokemonUUID) -> new WorkerInventoryMenu(
                                WORKER_INVENTORY,
                                containerId,
                                playerInventory,
                                pokemonUUID
                        ),
                        UUIDUtil.STREAM_CODEC
                )
        );

        // Set the supplier in the common ModMenuTypes
        ModMenuTypes.setWorkerInventorySupplier(() -> WORKER_INVENTORY);
    }
}
