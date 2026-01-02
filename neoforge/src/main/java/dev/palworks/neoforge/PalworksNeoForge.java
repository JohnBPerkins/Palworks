package dev.palworks.neoforge;

import dev.palworks.neoforge.command.PalworksCommands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("palworks")
public class PalworksNeoForge {
    public static final String MOD_ID = "palworks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public PalworksNeoForge(IEventBus modEventBus) {
        LOGGER.info("==================================================");
        LOGGER.info("PALWORKS NEOFORGE INITIALIZING...");
        LOGGER.info("==================================================");
        System.out.println("[Palworks] NeoForge mod is loading!");

        // Register command handler (WorkerTickHandler is auto-registered via @EventBusSubscriber)
        NeoForge.EVENT_BUS.register(new PalworksCommands());

        System.out.println("[Palworks] WorkerTickHandler will be auto-registered via @EventBusSubscriber");
        LOGGER.info("==================================================");
        LOGGER.info("PALWORKS NEOFORGE INITIALIZED!");
        LOGGER.info("==================================================");
    }
}
