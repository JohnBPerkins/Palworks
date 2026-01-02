package dev.palworks.fabric;

import dev.palworks.fabric.event.WorkerTickHandler;
import dev.palworks.fabric.command.PalworksCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PalworksFabric implements ModInitializer {
    public static final String MOD_ID = "palworks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Palworks Fabric initializing...");

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PalworksCommands.register(dispatcher);
        });

        // Register worker tick handler
        WorkerTickHandler.register();

        LOGGER.info("Palworks Fabric initialized!");
    }
}
