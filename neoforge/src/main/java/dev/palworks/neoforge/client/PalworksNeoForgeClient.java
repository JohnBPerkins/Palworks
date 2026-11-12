package dev.palworks.neoforge.client;

import dev.palworks.Palworks;
import dev.palworks.client.PalworksClient;
import dev.palworks.client.debug.WorkerDebugRenderer;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.client.render.StatusIconRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge's client entry point: common client setup, plus the HUD and world render hooks. */
@Mod(value = Palworks.MOD_ID, dist = Dist.CLIENT)
public class PalworksNeoForgeClient {
    public PalworksNeoForgeClient() {
        PalworksClient.init();

        // Work icons on the party HUD's cards. Cobblemon draws the HUD from the same event at normal
        // priority; lowest runs after it, so the icons go on top.
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST,
                false,
                RenderGuiEvent.Post.class,
                renderEvent -> WorkStatusHud.render(renderEvent.getGuiGraphics()));

        // The /palworks debug overlay (job icons above workers' heads, their paths and job blocks),
        // and what's holding workers back (hungry...), over their heads
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, stageEvent -> {
            if (RenderLevelStageEvent.Stage.AFTER_ENTITIES.equals(stageEvent.getStage())) {
                float partialTick = stageEvent.getPartialTick().getGameTimeDeltaPartialTick(false);
                WorkerDebugRenderer.render(stageEvent.getCamera(), partialTick);
                StatusIconRenderer.render(stageEvent.getCamera(), partialTick);
            }
        });
    }
}
