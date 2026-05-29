package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.net.packets.OpenInventoryPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Server-side handler for OpenInventoryPacket.
 * Opens the worker inventory GUI for the specified Pokemon.
 */
public class OpenInventoryHandler implements ServerNetworkPacketHandler<OpenInventoryPacket> {

    @Override
    public void handle(@NotNull OpenInventoryPacket packet, @NotNull MinecraftServer server, @NotNull ServerPlayer player) {
        UUID entityUUID = packet.getPokemonUUID();

        // The UUID from the interact wheel is the entity UUID, not the Pokemon storage UUID
        // Find the entity in the world first
        Entity entity = player.serverLevel().getEntity(entityUUID);

        if (!(entity instanceof PokemonEntity pokemonEntity)) {
            Palworks.LOGGER.warn("Player {} tried to open inventory for entity {} but it wasn't a Pokemon entity",
                    player.getName().getString(), entityUUID);
            player.displayClientMessage(
                    Component.translatable("palworks.message.inventory.not_found"),
                    true
            );
            return;
        }

        // Get the Pokemon from the entity
        Pokemon pokemon = pokemonEntity.getPokemon();

        if (pokemon == null) {
            Palworks.LOGGER.warn("Player {} tried to open inventory for Pokemon entity {} but Pokemon was null",
                    player.getName().getString(), entityUUID);
            player.displayClientMessage(
                    Component.translatable("palworks.message.inventory.not_found"),
                    true
            );
            return;
        }

        // Open the inventory GUI
        ModMenuTypes.openWorkerInventory(player, pokemon);
        Palworks.LOGGER.debug("Opened worker inventory for Pokemon {} for player {}",
                pokemon.getSpecies().getName(), player.getName().getString());
    }
}
