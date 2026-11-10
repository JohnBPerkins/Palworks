package dev.palworks.inventory;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.level.ServerPlayer;

/**
 * Platform-agnostic interface for opening menus.
 * Each platform (Fabric/NeoForge) provides its own implementation.
 */
@FunctionalInterface
public interface MenuOpener {
    /**
     * The current platform's menu opener instance.
     * Set during mod initialization.
     */
    MenuOpener INSTANCE = null;

    /**
     * Open the worker inventory menu for a player.
     */
    void openWorkerInventory(ServerPlayer player, Pokemon pokemon);

    /**
     * Holder class to allow setting the instance.
     */
    class Holder {
        private static MenuOpener instance;

        public static void setInstance(MenuOpener opener) {
            instance = opener;
        }

        public static MenuOpener getInstance() {
            return instance;
        }
    }
}
