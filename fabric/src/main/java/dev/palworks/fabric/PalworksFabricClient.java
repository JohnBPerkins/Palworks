package dev.palworks.fabric;

import dev.palworks.client.gui.PokemonInteractHandler;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.fabric.inventory.FabricModMenuTypes;
import dev.palworks.inventory.WorkerInventoryScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.screens.MenuScreens;

@Environment(EnvType.CLIENT)
public class PalworksFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PalworksFabric.LOGGER.info("Palworks Fabric Client initializing...");

        // Register screens using vanilla API
        MenuScreens.register(FabricModMenuTypes.WORKER_INVENTORY, WorkerInventoryScreen::new);

        // Register Pokemon interact wheel handler
        PokemonInteractHandler.init();

        // Work icons beside the party HUD (Cobblemon draws the HUD from the same event)
        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> WorkStatusHud.render(graphics));

        PalworksFabric.LOGGER.info("Palworks Fabric Client initialized!");
    }
}
