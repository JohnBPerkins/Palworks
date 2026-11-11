package dev.palworks.client;

import dev.architectury.registry.menu.MenuRegistry;
import dev.palworks.client.gui.PokemonInteractHandler;
import dev.palworks.client.gui.WorkSkillsOverlay;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventoryScreen;

/**
 * Sets up Palworks's client side, the same on both loaders: each loader's client entry point
 * calls {@link #init} once, as the client starts.
 *
 * <p>The party HUD icons and the debug overlay are still hooked up by each loader: the icons have
 * to draw after Cobblemon's HUD, and the overlay at the "after entities" stage of the world
 * render, and Architectury's events can't say either.
 */
public final class PalworksClient {
    private PalworksClient() {}

    public static void init() {
        // Once the menu type is registered (on NeoForge, Architectury registers the screen in its event)
        ModMenuTypes.WORKER_INVENTORY.listen(
                type -> MenuRegistry.registerScreenFactory(type, WorkerInventoryScreen::new));

        // The work toggle (and inventory button) on Cobblemon's interact wheel
        PokemonInteractHandler.init();

        // Work skills on Cobblemon's PC and summary screens
        WorkSkillsOverlay.init();
    }
}
