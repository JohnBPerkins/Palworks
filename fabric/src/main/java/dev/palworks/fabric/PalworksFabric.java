package dev.palworks.fabric;

import dev.palworks.animation.WorkAspectSync;
import dev.palworks.fabric.command.PalworksCommands;
import dev.palworks.fabric.data.FabricSuitabilityReloadListener;
import dev.palworks.fabric.event.WorkerTickHandler;
import dev.palworks.fabric.inventory.FabricMenuOpener;
import dev.palworks.fabric.inventory.FabricModMenuTypes;
import dev.palworks.inventory.MenuOpener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PalworksFabric implements ModInitializer {
    public static final String MOD_ID = "palworks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Palworks Fabric initializing...");

        // Register menu types (Fabric-specific)
        FabricModMenuTypes.register();

        // Set up menu opener
        MenuOpener.Holder.setInstance(new FabricMenuOpener());

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PalworksCommands.register(dispatcher);
        });

        // Load work suitabilities from datapacks (data/<namespace>/palworks/suitability)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new FabricSuitabilityReloadListener());

        // Register worker tick handler
        WorkerTickHandler.register();

        // Show what workers are doing as aspects, for posers to animate from
        WorkAspectSync.register();

        LOGGER.info("Palworks Fabric initialized!");
    }
}
