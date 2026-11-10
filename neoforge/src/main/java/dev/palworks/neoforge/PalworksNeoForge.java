package dev.palworks.neoforge;

import dev.palworks.animation.WorkAspectSync;
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
    private static final String BANNER = "==================================================";

    public static final String MOD_ID = "palworks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public PalworksNeoForge(IEventBus modEventBus) {
        LOGGER.info(BANNER);
        LOGGER.info("PALWORKS NEOFORGE INITIALIZING...");
        LOGGER.info(BANNER);
        LOGGER.info("NeoForge mod is loading!");

        // Register menu types (NeoForge-specific)
        NeoForgeModMenuTypes.register(modEventBus);

        // Set up menu opener
        MenuOpener.Holder.setInstance(new NeoForgeMenuOpener());

        // Load work suitabilities from datapacks (data/<namespace>/palworks/suitability)
        NeoForge.EVENT_BUS.addListener(
                AddReloadListenerEvent.class, event -> event.addListener(new SuitabilityReloadListener()));

        // Register command handler (WorkerTickHandler is auto-registered via @EventBusSubscriber)
        NeoForge.EVENT_BUS.register(new PalworksCommands());

        // Show what workers are doing as aspects, for posers to animate from
        WorkAspectSync.register();

        LOGGER.info("WorkerTickHandler will be auto-registered via @EventBusSubscriber");
        LOGGER.info(BANNER);
        LOGGER.info("PALWORKS NEOFORGE INITIALIZED!");
        LOGGER.info(BANNER);
    }
}
