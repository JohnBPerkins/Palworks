package dev.palworks.fabric;

import dev.palworks.Palworks;
import dev.palworks.client.PalworksClient;
import dev.palworks.client.debug.WorkerDebugRenderer;
import dev.palworks.client.hud.WorkStatusHud;
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

        // Work icons on the party HUD's cards. Cobblemon draws the HUD from the same event, in the
        // default phase; this phase runs after it, so the icons go on top.
        ResourceLocation afterCobblemon = Palworks.id("after_cobblemon_hud");
        HudRenderCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, afterCobblemon);
        HudRenderCallback.EVENT.register(afterCobblemon, (graphics, deltaTracker) -> WorkStatusHud.render(graphics));

        // The /palworks debug overlay: job icons above workers' heads, their paths and job blocks
        WorldRenderEvents.AFTER_ENTITIES.register(context -> WorkerDebugRenderer.render(
                context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(false)));
    }
}
