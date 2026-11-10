package dev.palworks.neoforge;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimationReloadListener;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.command.PalworksCommands;
import dev.palworks.inventory.MenuOpener;
import dev.palworks.neoforge.inventory.NeoForgeMenuOpener;
import dev.palworks.neoforge.inventory.NeoForgeModMenuTypes;
import dev.palworks.suitability.SuitabilityReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(Palworks.MOD_ID)
public class PalworksNeoForge {
    private static final Logger LOGGER = Palworks.LOGGER;

    public PalworksNeoForge(IEventBus modEventBus) {
        LOGGER.info("Palworks NeoForge initializing...");

        // Register menu types (NeoForge-specific)
        NeoForgeModMenuTypes.register(modEventBus);

        // Set up menu opener
        MenuOpener.Holder.setInstance(new NeoForgeMenuOpener());

        // Load work suitabilities from datapacks (data/<namespace>/palworks/suitability)
        NeoForge.EVENT_BUS.addListener(
                AddReloadListenerEvent.class, event -> event.addListener(new SuitabilityReloadListener()));
        // And species' own work animations (data/<namespace>/palworks/work_animations)
        NeoForge.EVENT_BUS.addListener(
                AddReloadListenerEvent.class, event -> event.addListener(new WorkAnimationReloadListener()));

        // The /palworks commands
        NeoForge.EVENT_BUS.addListener(
                RegisterCommandsEvent.class, event -> event.getDispatcher().register(PalworksCommands.build()));
        // Workers run from event/WorkerTickHandler (registered by its @EventBusSubscriber)

        // Show what workers are doing as aspects, for posers to animate from
        WorkAspectSync.register();

        LOGGER.info("Palworks NeoForge initialized!");
    }
}
