package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ClientNetworkPacketHandler;
import dev.palworks.client.gui.WorkerStatusCache;
import dev.palworks.net.packets.WorkerStatusPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/**
 * Client-side handler for WorkerStatusPacket: remembers the status for the interact wheel that
 * Cobblemon is about to open.
 */
public class WorkerStatusHandler implements ClientNetworkPacketHandler<WorkerStatusPacket> {

    @Override
    public void handle(@NotNull WorkerStatusPacket packet, @NotNull Minecraft client) {
        WorkerStatusCache.put(packet.getEntityUUID(), packet.isEnabled());
    }
}
