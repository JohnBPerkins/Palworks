package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.net.PalworksResource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Packet sent from client to server to request opening a Pokemon's inventory.
 */
public class OpenInventoryPacket implements NetworkPacket<OpenInventoryPacket> {
    public static final ResourceLocation ID = PalworksResource.of("open_inventory");

    private final UUID pokemonUUID;

    public OpenInventoryPacket(UUID pokemonUUID) {
        this.pokemonUUID = pokemonUUID;
    }

    public UUID getPokemonUUID() {
        return pokemonUUID;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(pokemonUUID);
    }

    public static OpenInventoryPacket decode(RegistryFriendlyByteBuf buffer) {
        return new OpenInventoryPacket(buffer.readUUID());
    }
}
