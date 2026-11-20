package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import dev.palworks.suitability.Suitability;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from server to a player: the work levels of some of their Pokémon (party or PC), by Pokémon
 * UUID, for the PC and summary screens. The client can't work these out itself: they come from
 * the server's datapacks, and from forms it evolved from, which Cobblemon 1.8.x doesn't sync. A
 * Pokémon with an empty map can't do any work. With them, the server's fertilize chance per
 * Watering level (its config), so the tooltip can say what each Watering level gives. Only for
 * display: the server never reads it back.
 */
public class WorkLevelsPacket implements NetworkPacket<WorkLevelsPacket> {
    public static final ResourceLocation ID = Palworks.id("work_levels");

    private final Map<UUID, Map<Suitability, Integer>> levels;
    private final double fertilizeChancePerLevel;

    public WorkLevelsPacket(Map<UUID, Map<Suitability, Integer>> levels, double fertilizeChancePerLevel) {
        this.levels = levels;
        this.fertilizeChancePerLevel = fertilizeChancePerLevel;
    }

    public Map<UUID, Map<Suitability, Integer>> getLevels() {
        return levels;
    }

    public double getFertilizeChancePerLevel() {
        return fertilizeChancePerLevel;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    @SuppressWarnings("EnumOrdinal") // decoded by Suitability.values(); both ends run the same jar
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(levels.size());
        levels.forEach((pokemon, jobs) -> {
            buffer.writeUUID(pokemon);
            buffer.writeVarInt(jobs.size());
            jobs.forEach((job, level) -> {
                buffer.writeVarInt(job.ordinal());
                buffer.writeVarInt(level);
            });
        });
        buffer.writeDouble(fertilizeChancePerLevel);
    }

    public static WorkLevelsPacket decode(RegistryFriendlyByteBuf buffer) {
        Suitability[] all = Suitability.values();
        int count = buffer.readVarInt();
        Map<UUID, Map<Suitability, Integer>> levels = new HashMap<>();
        for (int i = 0; i < count; i++) {
            UUID pokemon = buffer.readUUID();
            int jobs = buffer.readVarInt();
            Map<Suitability, Integer> byJob = new EnumMap<>(Suitability.class);
            for (int j = 0; j < jobs; j++) {
                byJob.put(all[buffer.readVarInt()], buffer.readVarInt());
            }
            levels.put(pokemon, byJob);
        }
        return new WorkLevelsPacket(levels, buffer.readDouble());
    }
}
