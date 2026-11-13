package dev.palworks;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import dev.palworks.animation.WorkAnimationReloadListener;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.block.ModBlocks;
import dev.palworks.block.TroughBlock;
import dev.palworks.command.PalworksCommands;
import dev.palworks.hunger.TroughFoodReloadListener;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.suitability.SuitabilityReloadListener;
import dev.palworks.worker.WorkLevelSync;
import dev.palworks.worker.WorkerStatusSync;
import dev.palworks.worker.WorkerTicking;
import net.minecraft.server.packs.PackType;

/**
 * Sets Palworks up, the same on both loaders: each loader's entry point calls {@link #init} once,
 * and everything else goes through Architectury API.
 */
public final class PalworksCommon {
    private PalworksCommon() {}

    public static void init() {
        Palworks.LOGGER.info("Palworks initializing...");

        ModBlocks.register();
        ModMenuTypes.register();

        // Work suitabilities (data/<namespace>/palworks/suitability) and species' own work
        // animations (data/<namespace>/palworks/work_animations), from datapacks
        ReloadListenerRegistry.register(
                PackType.SERVER_DATA, new SuitabilityReloadListener(), SuitabilityReloadListener.ID);
        ReloadListenerRegistry.register(
                PackType.SERVER_DATA, new WorkAnimationReloadListener(), WorkAnimationReloadListener.ID);
        // How much fullness each trough food gives (data/<namespace>/palworks/trough_food)
        ReloadListenerRegistry.register(
                PackType.SERVER_DATA, new TroughFoodReloadListener(), TroughFoodReloadListener.ID);

        CommandRegistrationEvent.EVENT.register(
                (dispatcher, registryAccess, selection) -> dispatcher.register(PalworksCommands.build()));

        // Shift-right-clicking a trough with food puts the whole stack in
        InteractionEvent.RIGHT_CLICK_BLOCK.register(TroughBlock::onShiftRightClick);

        // Run workers: each from its own entity tick (PokemonEntityTickMixin), JobManager once a tick
        LifecycleEvent.SERVER_STARTING.register(server -> WorkerTicking.onServerStarting());
        TickEvent.SERVER_POST.register(WorkerTicking::onServerTick);
        LifecycleEvent.SERVER_STOPPED.register(server -> WorkerTicking.onServerStopped());

        // Work levels for the PC and summary screens: each player's party's as they change
        TickEvent.SERVER_POST.register(WorkLevelSync::onServerTick);
        PlayerEvent.PLAYER_QUIT.register(player -> WorkLevelSync.forget(player.getUUID()));
        LifecycleEvent.SERVER_STOPPED.register(server -> WorkLevelSync.clear());

        // Show what workers are doing as aspects, for posers to animate from
        WorkAspectSync.register();
        // And what's holding them back (hungry), for clients to show over their heads
        WorkerStatusSync.register();
        // Hit or hurt in its sleep, a worker wakes
        dev.palworks.sleep.SleepDisturbances.register();

        // CI's server boot test looks for this line (.github/scripts/boot_server.sh)
        Palworks.LOGGER.info("Palworks initialized!");
    }
}
