package dev.palworks.neoforge;

import dev.palworks.inventory.MenuOpener;
import dev.palworks.neoforge.command.PalworksCommands;
import dev.palworks.neoforge.inventory.NeoForgeMenuOpener;
import dev.palworks.neoforge.inventory.NeoForgeModMenuTypes;
import dev.palworks.suitability.SuitabilityReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
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

        // Register menu types (NeoForge-specific)
        NeoForgeModMenuTypes.register(modEventBus);

        // Set up menu opener
        MenuOpener.Holder.setInstance(new NeoForgeMenuOpener());

        // Load work suitabilities from datapacks (data/<namespace>/palworks/suitability)
        NeoForge.EVENT_BUS.addListener(
                AddReloadListenerEvent.class, event -> event.addListener(new SuitabilityReloadListener()));

        // Register command handler (WorkerTickHandler is auto-registered via @EventBusSubscriber)
        NeoForge.EVENT_BUS.register(new PalworksCommands());

        System.out.println("[Palworks] WorkerTickHandler will be auto-registered via @EventBusSubscriber");
        LOGGER.info("==================================================");
        LOGGER.info("PALWORKS NEOFORGE INITIALIZED!");
        LOGGER.info("==================================================");
    }
}
