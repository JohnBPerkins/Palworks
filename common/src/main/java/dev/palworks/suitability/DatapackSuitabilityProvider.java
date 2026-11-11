package dev.palworks.suitability;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suitabilities from the datapack files ({@link SuitabilityData}), by the Pokémon's current types
 * and species, so they follow it through evolution and form changes.
 */
public class DatapackSuitabilityProvider implements SuitabilityProvider {
    // Each species and types' levels, worked out once for the data loaded: workers ask for their
    // suitabilities several times a tick each. A /reload loads new data, and with it a new cache.
    private volatile Cache cache = new Cache(SuitabilityData.EMPTY);

    @Override
    public Set<Suitability> getSuitabilities(Pokemon pokemon) {
        return levels(pokemon).suitabilities();
    }

    @Override
    public int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        return levels(pokemon).levels().getOrDefault(suitability, 0);
    }

    private Levels levels(Pokemon pokemon) {
        List<ElementalType> types = new ArrayList<>(2);
        for (ElementalType type : pokemon.getTypes()) {
            types.add(type);
        }
        Key key = new Key(pokemon.getSpecies(), types);

        SuitabilityData data = SuitabilityData.current();
        Cache current = cache;
        if (current.data() != data) {
            current = new Cache(data);
            cache = current;
        }
        return current.levels().computeIfAbsent(key, k -> compute(data, k));
    }

    private static Levels compute(SuitabilityData data, Key key) {
        List<String> typeIds = new ArrayList<>(key.types().size());
        for (ElementalType type : key.types()) {
            typeIds.add(type.getShowdownId());
        }
        String speciesId = key.species().getResourceIdentifier().toString();
        Map<Suitability, Integer> levels = data.levels(typeIds, speciesId);
        Set<Suitability> suitabilities = EnumSet.noneOf(Suitability.class);
        suitabilities.addAll(levels.keySet());
        return new Levels(Collections.unmodifiableMap(levels), Collections.unmodifiableSet(suitabilities));
    }

    /** A species and types' job levels, and the jobs themselves (as an EnumSet, for quick checks). */
    private record Levels(Map<Suitability, Integer> levels, Set<Suitability> suitabilities) {}

    /** What a Pokémon's levels depend on: its species and its types (its form's, or changed ones). */
    private record Key(Species species, List<ElementalType> types) {}

    private record Cache(SuitabilityData data, Map<Key, Levels> levels) {
        Cache(SuitabilityData data) {
            this(data, new ConcurrentHashMap<>());
        }
    }
}
