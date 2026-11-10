package dev.palworks.inventory;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.palworks.Palworks;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * Palworks's menu types, registered on both loaders through Architectury's
 * {@link DeferredRegister}, and opening them.
 */
public final class ModMenuTypes {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Palworks.MOD_ID, Registries.MENU);

    /** A worker's inventory. Opening it sends the Pokémon's UUID, so the client knows whose it is. */
    public static final RegistrySupplier<MenuType<WorkerInventoryMenu>> WORKER_INVENTORY = MENUS.register(
            "worker_inventory",
            () -> MenuRegistry.ofExtended((containerId, playerInventory, buf) -> new WorkerInventoryMenu(
                    ModMenuTypes.WORKER_INVENTORY.get(), containerId, playerInventory, buf.readUUID())));

    private ModMenuTypes() {}

    /** Register the menu types. Called once, from {@link dev.palworks.PalworksCommon#init}. */
    public static void register() {
        MENUS.register();
    }

    /** Open a worker's inventory for a player. */
    public static void openWorkerInventory(ServerPlayer player, Pokemon pokemon) {
        UUID pokemonUUID = pokemon.getUuid();
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        String pokemonName = pokemon.getSpecies().getName();
        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal(pokemonName + "'s Inventory");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                return new WorkerInventoryMenu(
                        WORKER_INVENTORY.get(), containerId, playerInventory, inventory, pokemonUUID);
            }
        };
        MenuRegistry.openExtendedMenu(player, provider, buf -> buf.writeUUID(pokemonUUID));
    }
}
