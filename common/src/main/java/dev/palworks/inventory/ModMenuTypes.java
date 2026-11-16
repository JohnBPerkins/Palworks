package dev.palworks.inventory;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
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

    // How far past a player's reach a worker's inventory screen stays open: vanilla's own figure
    // for a screen opened on an entity (a horse's, a chest boat's)
    private static final double REACH_MARGIN = 4.0;

    /**
     * The server's side of a worker's inventory screen: on the inventory the worker itself uses,
     * and open only while it stays that (see {@link WorkerInventoryManager#isCarriedBy}) and the
     * player is near enough, as for a horse's or a chest boat's: the wheel that opens it needs
     * the worker in reach, and a screen left open as either walks off shouldn't reach across a
     * base.
     */
    public static WorkerInventoryMenu menuFor(int containerId, Inventory playerInventory, Pokemon pokemon) {
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        return new WorkerInventoryMenu(
                WORKER_INVENTORY.get(), containerId, playerInventory, inventory, pokemon.getUuid(), player -> {
                    PokemonEntity entity = pokemon.getEntity();
                    return entity != null
                            && WorkerInventoryManager.isCarriedBy(pokemon, inventory)
                            && player.canInteractWithEntity(entity, REACH_MARGIN);
                });
    }

    /** Open a worker's inventory for a player. */
    public static void openWorkerInventory(ServerPlayer player, Pokemon pokemon) {
        UUID pokemonUUID = pokemon.getUuid();
        String pokemonName = pokemon.getSpecies().getName();
        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal(pokemonName + "'s Inventory");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                return menuFor(containerId, playerInventory, pokemon);
            }
        };
        MenuRegistry.openExtendedMenu(player, provider, buf -> buf.writeUUID(pokemonUUID));
    }
}
