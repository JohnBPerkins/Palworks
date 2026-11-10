package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.inventory.CarryMode;
import dev.palworks.inventory.ModMenuTypes;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.net.packets.OpenInventoryPacket;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Server-side handler for OpenInventoryPacket.
 * Opens the worker inventory GUI for the specified Pokemon.
 */
public class OpenInventoryHandler implements ServerNetworkPacketHandler<OpenInventoryPacket> {
    private static final boolean DEBUG = Palworks.DEBUG;

    @Override
    public void handle(
            @NotNull OpenInventoryPacket packet, @NotNull MinecraftServer server, @NotNull ServerPlayer player) {
        UUID entityUUID = packet.getPokemonUUID();

        // The UUID from the interact wheel is the entity UUID, not the Pokemon storage UUID
        // Find the entity in the world first
        Entity entity = player.serverLevel().getEntity(entityUUID);

        if (!(entity instanceof PokemonEntity pokemonEntity)) {
            Palworks.LOGGER.warn(
                    "Player {} tried to open inventory for entity {} but it wasn't a Pokemon entity",
                    player.getName().getString(),
                    entityUUID);
            player.displayClientMessage(Component.translatable("palworks.message.inventory.not_found"), true);
            return;
        }

        // Get the Pokemon from the entity
        Pokemon pokemon = pokemonEntity.getPokemon();

        if (pokemon == null) {
            Palworks.LOGGER.warn(
                    "Player {} tried to open inventory for Pokemon entity {} but Pokemon was null",
                    player.getName().getString(),
                    entityUUID);
            player.displayClientMessage(Component.translatable("palworks.message.inventory.not_found"), true);
            return;
        }

        // The packet comes from the client, so the server must check ownership itself: otherwise a
        // modified client could send any nearby Pokemon's UUID and take items from another player's
        // worker. Wild Pokemon (no owner) are rejected too.
        UUID ownerUUID = pokemon.getOwnerUUID();
        if (ownerUUID == null || !ownerUUID.equals(player.getUUID())) {
            Palworks.LOGGER.warn(
                    "Player {} tried to open the inventory of {} ({}), which they don't own",
                    player.getName().getString(),
                    pokemon.getSpecies().getName(),
                    entityUUID);
            player.displayClientMessage(Component.translatable("palworks.message.inventory.not_owner"), true);
            return;
        }

        // Only Transporting (full inventory) and planters (seed pouch) carry anything. The client
        // hides the button for everyone else; this guards against a modified client.
        if (WorkerInventoryManager.carryModeFor(pokemon) == CarryMode.NONE) {
            player.displayClientMessage(
                    Component.translatable(
                            "palworks.message.inventory.carries_nothing",
                            pokemon.getSpecies().getTranslatedName()),
                    true);
            return;
        }

        // Open the inventory GUI
        ModMenuTypes.openWorkerInventory(player, pokemon);
        if (DEBUG) {
            Palworks.LOGGER.debug(
                    "Opened worker inventory for Pokemon {} for player {}",
                    pokemon.getSpecies().getName(),
                    player.getName().getString());
        }
    }
}
