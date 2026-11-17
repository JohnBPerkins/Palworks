package dev.palworks.net.handlers;

import com.cobblemon.mod.common.api.net.ClientNetworkPacketHandler;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.client.gui.DexWorkSkillFilter;
import dev.palworks.net.packets.DexWorkSkillsPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/**
 * Client-side handler for DexWorkSkillsPacket: remembers every species' work skills for the
 * Pokédex's work skill filter, and has an open Pokédex filter again by them.
 */
public class DexWorkSkillsHandler implements ClientNetworkPacketHandler<DexWorkSkillsPacket> {

    @Override
    public void handle(@NotNull DexWorkSkillsPacket packet, @NotNull Minecraft client) {
        ClientWorkerData.setSpeciesSkills(packet.getSkills());
        DexWorkSkillFilter.skillsArrived(client);
    }
}
