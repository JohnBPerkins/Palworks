package dev.palworks.neoforge.event;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.Palworks;
import dev.palworks.worker.WorkerTicking;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Runs workers on NeoForge: wires its events to {@link WorkerTicking}. */
@EventBusSubscriber(modid = Palworks.MOD_ID)
public final class WorkerTickHandler {
    private WorkerTickHandler() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        WorkerTicking.onServerStarting(FMLPaths.CONFIGDIR.get());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        WorkerTicking.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof PokemonEntity pokemonEntity) {
            WorkerTicking.onPokemonTick(pokemonEntity);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WorkerTicking.onServerStopped();
    }
}
