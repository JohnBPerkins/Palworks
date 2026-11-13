package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ClientNetworkPacketHandler;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.net.packets.WorkerMoodPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/** Client-side handler for WorkerMoodPacket: remembers the moods for the PC and summary screens. */
public class WorkerMoodHandler implements ClientNetworkPacketHandler<WorkerMoodPacket> {

    @Override
    public void handle(@NotNull WorkerMoodPacket packet, @NotNull Minecraft client) {
        ClientWorkerData.putMoods(packet.getMoods(), packet.getStrikeUntil());
    }
}
