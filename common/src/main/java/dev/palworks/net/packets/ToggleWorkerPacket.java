package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.net.PalworksResource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Sent from client to server when the player presses the work toggle on a Pokemon's interact
 * wheel. Carries the entity UUID; the server checks ownership and flips the worker's enabled flag.
 */
public class ToggleWorkerPacket implements NetworkPacket<ToggleWorkerPacket> {
    public static final ResourceLocation ID = PalworksResource.of("toggle_worker");

    private final UUID entityUUID;

    public ToggleWorkerPacket(UUID entityUUID) {
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

    public static ToggleWorkerPacket decode(RegistryFriendlyByteBuf buffer) {
        return new ToggleWorkerPacket(buffer.readUUID());
    }
}
