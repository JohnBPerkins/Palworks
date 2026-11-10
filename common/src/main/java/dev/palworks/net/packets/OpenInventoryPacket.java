package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Packet sent from client to server to request opening a Pokemon's inventory.
 */
public class OpenInventoryPacket implements NetworkPacket<OpenInventoryPacket> {
    public static final ResourceLocation ID = Palworks.id("open_inventory");

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
