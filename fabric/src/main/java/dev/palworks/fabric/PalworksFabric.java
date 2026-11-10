package dev.palworks.fabric;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.command.PalworksCommands;
import dev.palworks.fabric.data.FabricSuitabilityReloadListener;
import dev.palworks.fabric.data.FabricWorkAnimationReloadListener;
import dev.palworks.fabric.inventory.FabricMenuOpener;
import dev.palworks.fabric.inventory.FabricModMenuTypes;
import dev.palworks.inventory.MenuOpener;
import dev.palworks.worker.WorkerTicking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;

public class PalworksFabric implements ModInitializer {
    private static final Logger LOGGER = Palworks.LOGGER;

    @Override
    public void onInitialize() {
        LOGGER.info("Palworks Fabric initializing...");

        // Register menu types (Fabric-specific)
        FabricModMenuTypes.register();

        // Set up menu opener
        MenuOpener.Holder.setInstance(new FabricMenuOpener());

        // Register commands
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> dispatcher.register(PalworksCommands.build()));

        // Load work suitabilities from datapacks (data/<namespace>/palworks/suitability)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new FabricSuitabilityReloadListener());
        // And species' own work animations (data/<namespace>/palworks/work_animations)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new FabricWorkAnimationReloadListener());

        // Run workers: each from its own entity tick (PokemonEntityMixin), JobManager once a tick
        ServerTickEvents.END_SERVER_TICK.register(WorkerTicking::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> WorkerTicking.onServerStopped());

        // Show what workers are doing as aspects, for posers to animate from
        WorkAspectSync.register();

        LOGGER.info("Palworks Fabric initialized!");
    }
}
