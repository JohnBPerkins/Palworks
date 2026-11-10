package dev.palworks.fabric;

import dev.palworks.client.debug.WorkerDebugRenderer;
import dev.palworks.client.gui.PokemonInteractHandler;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.fabric.inventory.FabricModMenuTypes;
import dev.palworks.inventory.WorkerInventoryScreen;
import dev.palworks.net.PalworksResource;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.ResourceLocation;

@Environment(EnvType.CLIENT)
public class PalworksFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PalworksFabric.LOGGER.info("Palworks Fabric Client initializing...");

        // Register screens using vanilla API
        MenuScreens.register(FabricModMenuTypes.WORKER_INVENTORY, WorkerInventoryScreen::new);

        // Register Pokemon interact wheel handler
        PokemonInteractHandler.init();

        // Work icons on the party HUD's cards. Cobblemon draws the HUD from the same event, in the
        // default phase; this phase runs after it, so the icons go on top.
        ResourceLocation afterCobblemon = PalworksResource.of("after_cobblemon_hud");
        HudRenderCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, afterCobblemon);
        HudRenderCallback.EVENT.register(afterCobblemon, (graphics, deltaTracker) -> WorkStatusHud.render(graphics));

        // The /palworks debug overlay: job icons above workers' heads, their paths and job blocks
        WorldRenderEvents.AFTER_ENTITIES.register(context -> WorkerDebugRenderer.render(
                context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(false)));

        PalworksFabric.LOGGER.info("Palworks Fabric Client initialized!");
    }
}
