package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from client to server when the player opens the Pokédex: asks for every species' work
 * skills ({@link DexWorkSkillsPacket}), for the Pokédex's work skill filter.
 */
public class RequestDexWorkSkillsPacket implements NetworkPacket<RequestDexWorkSkillsPacket> {
    public static final ResourceLocation ID = Palworks.id("request_dex_work_skills");

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        // Nothing to send: it's the same for every player
    }

    public static RequestDexWorkSkillsPacket decode(RegistryFriendlyByteBuf buffer) {
        return new RequestDexWorkSkillsPacket();
    }
}
