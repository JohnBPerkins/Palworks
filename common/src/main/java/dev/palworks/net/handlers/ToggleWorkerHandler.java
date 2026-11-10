package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.net.packets.ToggleWorkerPacket;
import dev.palworks.worker.WorkerStateManager;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Server-side handler for ToggleWorkerPacket: turns the Pokemon's work on or off.
 */
public class ToggleWorkerHandler implements ServerNetworkPacketHandler<ToggleWorkerPacket> {

    @Override
    public void handle(
            @NotNull ToggleWorkerPacket packet, @NotNull MinecraftServer server, @NotNull ServerPlayer player) {
        UUID entityUUID = packet.getEntityUUID();
        Entity entity = player.serverLevel().getEntity(entityUUID);
        Pokemon pokemon = entity instanceof PokemonEntity pokemonEntity ? pokemonEntity.getPokemon() : null;
        if (pokemon == null) {
            player.displayClientMessage(Component.translatable("palworks.message.worker.not_found"), true);
            return;
        }

        // The packet comes from the client, so check ownership here: otherwise a modified client
        // could switch off (or on) another player's workers. Wild Pokemon are rejected too.
        UUID ownerUUID = pokemon.getOwnerUUID();
        if (ownerUUID == null || !ownerUUID.equals(player.getUUID())) {
            Palworks.LOGGER.warn(
                    "Player {} tried to toggle work for {} ({}), which they don't own",
                    player.getName().getString(),
                    pokemon.getSpecies().getName(),
                    entityUUID);
            player.displayClientMessage(Component.translatable("palworks.message.worker.not_owner"), true);
            return;
        }

        boolean enabled = !WorkerStateManager.getOrCreate(pokemon).isEnabled();
        WorkerStateManager.setEnabled(pokemon, enabled);
        if (!enabled) {
            // Stop it walking on to the job it just gave up.
            ((PokemonEntity) entity).getNavigation().stop();
        }

        player.displayClientMessage(
                Component.translatable(
                        enabled ? "palworks.message.worker.enabled" : "palworks.message.worker.disabled",
                        pokemon.getSpecies().getTranslatedName()),
                true);
    }
}
