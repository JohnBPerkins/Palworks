package dev.palworks.suitability;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Which work skills each species can have, in any of its forms, from the datapacks and admins'
 * overrides: what the Pokédex's work skill filter shows (see WorkLevelSync.sendDex). Any form, as
 * Cobblemon's own Pokédex searches by ability and drops look at every form of a species. A species
 * with none is left out. Other mods' providers (SuitabilityDetector.registerProvider) aren't asked:
 * they answer for a Pokémon, not a species.
 *
 * <p>Worked out once for each {@link SuitabilityData}: a {@code /reload} or an admin's change makes
 * a new one, and with it a new map here, which is how WorkLevelSync knows to send it again.
 */
public final class SpeciesWorkSkills {
    private record Cached(SuitabilityData data, Map<ResourceLocation, Set<Suitability>> skills) {}

    private static volatile Cached cached;

    private SpeciesWorkSkills() {}

    /** Every species' work skills, by species id. The same map until the suitability data changes. */
    public static Map<ResourceLocation, Set<Suitability>> current() {
        SuitabilityData data = SuitabilityData.current();
        Cached now = cached;
        if (now == null || now.data() != data) {
            now = new Cached(data, compute());
            cached = now;
        }
        return now.skills();
    }

    private static Map<ResourceLocation, Set<Suitability>> compute() {
        Map<ResourceLocation, Set<Suitability>> all = new HashMap<>();
        for (Species species : PokemonSpecies.getSpecies()) {
            Set<Suitability> skills = EnumSet.noneOf(Suitability.class);
            add(skills, species.getStandardForm());
            for (FormData form : species.getForms()) {
                add(skills, form);
            }
            if (!skills.isEmpty()) {
                all.put(species.getResourceIdentifier(), Set.copyOf(skills));
            }
        }
        return Map.copyOf(all);
    }

    private static void add(Set<Suitability> skills, FormData form) {
        DatapackSuitabilityProvider.levels(form).forEach((skill, level) -> {
            if (level > 0) {
                skills.add(skill);
            }
        });
    }
}
