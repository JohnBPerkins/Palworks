package dev.palworks.suitability;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suitabilities from the datapack files ({@link SuitabilityData}), by the Pokémon's current types
 * and species, so they follow it through evolution and form changes.
 */
public class DatapackSuitabilityProvider implements SuitabilityProvider {

    @Override
    public Set<Suitability> getSuitabilities(Pokemon pokemon) {
        return levels(pokemon).keySet();
    }

    @Override
    public int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        return levels(pokemon).getOrDefault(suitability, 0);
    }

    private static Map<Suitability, Integer> levels(Pokemon pokemon) {
        List<String> typeIds = new ArrayList<>(2);
        for (ElementalType type : pokemon.getTypes()) {
            typeIds.add(type.getShowdownId());
        }
        String speciesId = pokemon.getSpecies().getResourceIdentifier().toString();
        return SuitabilityData.current().levels(typeIds, speciesId);
    }
}
