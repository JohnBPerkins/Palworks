package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import dev.palworks.suitability.Suitability;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from server to a player: which work skills each species can have (see SpeciesWorkSkills), by
 * species id, for the Pokédex's work skill filter. The client can't work these out itself: they
 * come from the server's datapacks and admins' overrides. A species that isn't in it has none.
 * Only for display: the server never reads it back.
 */
public class DexWorkSkillsPacket implements NetworkPacket<DexWorkSkillsPacket> {
    public static final ResourceLocation ID = Palworks.id("dex_work_skills");

    private final Map<ResourceLocation, Set<Suitability>> skills;

    public DexWorkSkillsPacket(Map<ResourceLocation, Set<Suitability>> skills) {
        this.skills = skills;
    }

    public Map<ResourceLocation, Set<Suitability>> getSkills() {
        return skills;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    @SuppressWarnings("EnumOrdinal") // a bit for each, read back by Suitability.values(); both ends run the same jar
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(skills.size());
        skills.forEach((species, has) -> {
            buffer.writeResourceLocation(species);
            int bits = 0;
            for (Suitability skill : has) {
                bits |= 1 << skill.ordinal();
            }
            buffer.writeVarInt(bits);
        });
    }

    public static DexWorkSkillsPacket decode(RegistryFriendlyByteBuf buffer) {
        Suitability[] all = Suitability.values();
        int count = buffer.readVarInt();
        Map<ResourceLocation, Set<Suitability>> skills = new HashMap<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation species = buffer.readResourceLocation();
            int bits = buffer.readVarInt();
            Set<Suitability> has = EnumSet.noneOf(Suitability.class);
            for (int bit = 0; bit < all.length; bit++) {
                if ((bits & 1 << bit) != 0) {
                    has.add(all[bit]);
                }
            }
            skills.put(species, has);
        }
        return new DexWorkSkillsPacket(skills);
    }
}
