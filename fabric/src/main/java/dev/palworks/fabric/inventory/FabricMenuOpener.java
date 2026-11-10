package dev.palworks.fabric.inventory;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.inventory.MenuOpener;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.inventory.WorkerInventoryMenu;
import java.util.UUID;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Fabric implementation of MenuOpener using Fabric API's ExtendedScreenHandlerFactory.
 */
public class FabricMenuOpener implements MenuOpener {

    @Override
    public void openWorkerInventory(ServerPlayer player, Pokemon pokemon) {
        UUID pokemonUUID = pokemon.getUuid();
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        String pokemonName = pokemon.getSpecies().getName();

        player.openMenu(new ExtendedScreenHandlerFactory<UUID>() {
            @Override
            public UUID getScreenOpeningData(ServerPlayer player) {
                return pokemonUUID;
            }

            @Override
            public Component getDisplayName() {
                return Component.literal(pokemonName + "'s Inventory");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                return new WorkerInventoryMenu(
                        ModMenuTypes.WORKER_INVENTORY.get(), containerId, playerInventory, inventory, pokemonUUID);
            }
        });
    }
}
