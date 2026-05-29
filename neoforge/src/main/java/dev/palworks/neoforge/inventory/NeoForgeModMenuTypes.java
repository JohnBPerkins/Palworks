package dev.palworks.neoforge.inventory;

import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventoryMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * NeoForge-specific menu type registration.
 */
public class NeoForgeModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, "palworks");

    public static final Supplier<MenuType<WorkerInventoryMenu>> WORKER_INVENTORY =
            MENU_TYPES.register("worker_inventory", () ->
                    IMenuTypeExtension.create((containerId, playerInventory, buf) -> {
                        UUID pokemonUUID = buf.readUUID();
                        return new WorkerInventoryMenu(
                                NeoForgeModMenuTypes.WORKER_INVENTORY.get(),
                                containerId,
                                playerInventory,
                                pokemonUUID
                        );
                    })
            );

    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);

        // Set the supplier in the common ModMenuTypes
        ModMenuTypes.setWorkerInventorySupplier(WORKER_INVENTORY);
    }
}
