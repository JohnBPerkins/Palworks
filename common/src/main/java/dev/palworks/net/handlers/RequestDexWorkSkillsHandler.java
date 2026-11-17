package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler;
import dev.palworks.net.packets.RequestDexWorkSkillsPacket;
import dev.palworks.worker.WorkLevelSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/** Server-side handler for RequestDexWorkSkillsPacket: sends the player every species' work skills. */
public class RequestDexWorkSkillsHandler implements ServerNetworkPacketHandler<RequestDexWorkSkillsPacket> {

    @Override
    public void handle(
            @NotNull RequestDexWorkSkillsPacket packet, @NotNull MinecraftServer server, @NotNull ServerPlayer player) {
        WorkLevelSync.sendDex(player);
    }
}
