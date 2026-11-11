package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler;
import dev.palworks.net.packets.RequestPcWorkLevelsPacket;
import dev.palworks.worker.WorkLevelSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/** Server-side handler for RequestPcWorkLevelsPacket: sends the player their PC's work levels. */
public class RequestPcWorkLevelsHandler implements ServerNetworkPacketHandler<RequestPcWorkLevelsPacket> {

    @Override
    public void handle(
            @NotNull RequestPcWorkLevelsPacket packet, @NotNull MinecraftServer server, @NotNull ServerPlayer player) {
        WorkLevelSync.sendPc(player, server.getTickCount());
    }
}
