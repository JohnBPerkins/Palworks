package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Packet sent from client to server to request opening a Pokemon's inventory. It carries the
 * Pokémon's entity UUID (what the interact wheel has), not its storage UUID.
 */
public class OpenInventoryPacket implements NetworkPacket<OpenInventoryPacket> {
    public static final ResourceLocation ID = Palworks.id("open_inventory");

    private final UUID entityUUID;

    public OpenInventoryPacket(UUID entityUUID) {
        this.entityUUID = entityUUID;
    }

    public UUID getEntityUUID() {
        return entityUUID;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(entityUUID);
    }

    public static OpenInventoryPacket decode(RegistryFriendlyByteBuf buffer) {
        return new OpenInventoryPacket(buffer.readUUID());
    }
}
