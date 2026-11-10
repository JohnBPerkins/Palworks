package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import dev.palworks.inventory.CarryMode;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from server to the owner just before Cobblemon's own packet that opens a Pokemon's
 * interact wheel, so the wheel can show whether that Pokemon is working and whether it has an
 * inventory. The client can't work these out itself: suitabilities come from server datapacks.
 * Only a display hint: the worker state stays on the server, in the Pokemon's persistent data.
 */
public class WorkerStatusPacket implements NetworkPacket<WorkerStatusPacket> {
    public static final ResourceLocation ID = Palworks.id("worker_status");

    private final UUID entityUUID;
    private final boolean enabled;
    private final CarryMode carryMode;

    public WorkerStatusPacket(UUID entityUUID, boolean enabled, CarryMode carryMode) {
        this.entityUUID = entityUUID;
        this.enabled = enabled;
        this.carryMode = carryMode;
    }

    public UUID getEntityUUID() {
        return entityUUID;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CarryMode getCarryMode() {
        return carryMode;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    @SuppressWarnings("EnumOrdinal") // decoded by fromOrdinal; both ends run the same jar
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(entityUUID);
        buffer.writeBoolean(enabled);
        buffer.writeVarInt(carryMode.ordinal());
    }

    public static WorkerStatusPacket decode(RegistryFriendlyByteBuf buffer) {
        return new WorkerStatusPacket(
                buffer.readUUID(), buffer.readBoolean(), CarryMode.fromOrdinal(buffer.readVarInt()));
    }
}
