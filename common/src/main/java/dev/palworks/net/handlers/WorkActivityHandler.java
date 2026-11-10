package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ClientNetworkPacketHandler;
import dev.palworks.client.hud.WorkActivityCache;
import dev.palworks.net.packets.WorkActivityPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/** Client-side handler for WorkActivityPacket: remembers it for the party HUD. */
public class WorkActivityHandler implements ClientNetworkPacketHandler<WorkActivityPacket> {

    @Override
    public void handle(@NotNull WorkActivityPacket packet, @NotNull Minecraft client) {
        WorkActivityCache.put(packet.getPokemonUUID(), packet.getActivity());
    }
}
