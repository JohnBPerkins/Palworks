package dev.palworks.neoforge.client;

import dev.palworks.client.debug.WorkerDebugRenderer;
import dev.palworks.client.gui.PokemonInteractHandler;
import dev.palworks.client.hud.WorkStatusHud;
import dev.palworks.inventory.WorkerInventoryScreen;
import dev.palworks.neoforge.inventory.NeoForgeModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = "palworks", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class PalworksNeoForgeClient {

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        // Register screens using NeoForge API
        event.register(NeoForgeModMenuTypes.WORKER_INVENTORY.get(), WorkerInventoryScreen::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Register Pokemon interact wheel handler
            PokemonInteractHandler.init();
        });
        // Work icons on the party HUD's cards. Cobblemon draws the HUD from the same event at normal
        // priority; lowest runs after it, so the icons go on top.
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST,
                false,
                RenderGuiEvent.Post.class,
                renderEvent -> WorkStatusHud.render(renderEvent.getGuiGraphics()));
        // The /palworks debug overlay: job icons above workers' heads, their paths and job blocks
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, stageEvent -> {
            if (stageEvent.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                WorkerDebugRenderer.render(
                        stageEvent.getCamera(), stageEvent.getPartialTick().getGameTimeDeltaPartialTick(false));
            }
        });
    }
}
