package dev.palworks.fabric;

import dev.palworks.Palworks;
import dev.palworks.client.PalworksClient;
import dev.palworks.client.debug.WorkerDebugRenderer;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.client.render.StatusIconRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.ResourceLocation;

/** Fabric's client entry point: common client setup, plus the HUD and world render hooks. */
@Environment(EnvType.CLIENT)
public class PalworksFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PalworksClient.init();

        // Work icons on the party HUD's cards. Cobblemon draws the party HUD earlier in the frame
        // (its mixin at the head of Gui.renderCameraOverlays), so anything drawn from this event
        // goes on top. The phase puts them after what's drawn in the default one too, Cobblemon's
        // Pokédex scanner among it.
        ResourceLocation afterCobblemon = Palworks.id("after_cobblemon_hud");
        HudRenderCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, afterCobblemon);
        HudRenderCallback.EVENT.register(afterCobblemon, (graphics, deltaTracker) -> WorkStatusHud.render(graphics));

        // The /palworks debug overlay: job icons above workers' heads, their paths and job blocks
        WorldRenderEvents.AFTER_ENTITIES.register(context -> WorkerDebugRenderer.render(
                context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(false)));

        // What's holding workers back (hungry...), over their heads
        WorldRenderEvents.AFTER_ENTITIES.register(context -> StatusIconRenderer.render(
                context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(false)));
    }
}
