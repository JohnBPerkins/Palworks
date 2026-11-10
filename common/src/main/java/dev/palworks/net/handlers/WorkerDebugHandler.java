package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ClientNetworkPacketHandler;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.net.packets.WorkerDebugPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/** Client-side handler for WorkerDebugPacket: replaces what the debug overlay draws. */
public class WorkerDebugHandler implements ClientNetworkPacketHandler<WorkerDebugPacket> {

    @Override
    public void handle(@NotNull WorkerDebugPacket packet, @NotNull Minecraft client) {
        ClientWorkerData.setDebugEntries(packet.getEntries());
    }
}
