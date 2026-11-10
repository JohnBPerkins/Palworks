package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.net.PalworksResource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Sent from server to the owner just before Cobblemon's own packet that opens a Pokemon's
 * interact wheel, so the wheel can show whether that Pokemon is working. Only a display hint: the
 * worker state itself stays on the server, in the Pokemon's persistent data.
 */
public class WorkerStatusPacket implements NetworkPacket<WorkerStatusPacket> {
    public static final ResourceLocation ID = PalworksResource.of("worker_status");

    private final UUID entityUUID;
    private final boolean enabled;

    public WorkerStatusPacket(UUID entityUUID, boolean enabled) {
        this.entityUUID = entityUUID;
        this.enabled = enabled;
    }

    public UUID getEntityUUID() {
        return entityUUID;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(entityUUID);
        buffer.writeBoolean(enabled);
    }

    public static WorkerStatusPacket decode(RegistryFriendlyByteBuf buffer) {
        return new WorkerStatusPacket(buffer.readUUID(), buffer.readBoolean());
    }
}
