package dev.palworks.neoforge.inventory;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.inventory.MenuOpener;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.inventory.WorkerInventoryMenu;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * NeoForge implementation of MenuOpener.
 */
public class NeoForgeMenuOpener implements MenuOpener {

    @Override
    public void openWorkerInventory(ServerPlayer player, Pokemon pokemon) {
        UUID pokemonUUID = pokemon.getUuid();
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        String pokemonName = pokemon.getSpecies().getName();

        player.openMenu(
                new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return Component.literal(pokemonName + "'s Inventory");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                        return new WorkerInventoryMenu(
                                ModMenuTypes.WORKER_INVENTORY.get(),
                                containerId,
                                playerInventory,
                                inventory,
                                pokemonUUID);
                    }
                },
                buf -> buf.writeUUID(pokemonUUID));
    }
}
