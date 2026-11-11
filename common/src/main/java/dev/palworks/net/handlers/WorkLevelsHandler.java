package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ClientNetworkPacketHandler;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.net.packets.WorkLevelsPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/** Client-side handler for WorkLevelsPacket: remembers the levels for the PC and summary screens. */
public class WorkLevelsHandler implements ClientNetworkPacketHandler<WorkLevelsPacket> {

    @Override
    public void handle(@NotNull WorkLevelsPacket packet, @NotNull Minecraft client) {
        ClientWorkerData.putWorkLevels(packet.getLevels());
    }
}
