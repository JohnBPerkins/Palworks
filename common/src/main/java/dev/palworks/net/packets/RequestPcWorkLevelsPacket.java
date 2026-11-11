package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from client to server when the player opens a PC: asks for the work levels of every
 * Pokémon in their PC ({@link WorkLevelsPacket}), to show as they hover over them.
 */
public class RequestPcWorkLevelsPacket implements NetworkPacket<RequestPcWorkLevelsPacket> {
    public static final ResourceLocation ID = Palworks.id("request_pc_work_levels");

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        // Nothing to send: the server knows whose PC it is
    }

    public static RequestPcWorkLevelsPacket decode(RegistryFriendlyByteBuf buffer) {
        return new RequestPcWorkLevelsPacket();
    }
}
