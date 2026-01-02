package dev.palworks.suitability;

import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.Set;

/**
 * Interface for providing suitability detection logic.
 * Allows extensible detection based on types, species, forms, abilities, etc.
 */
public interface SuitabilityProvider {
    /**
     * Get all suitabilities this provider grants to the Pokémon.
     */
    Set<Suitability> getSuitabilities(Pokemon pokemon);

    /**
     * Check if this provider grants a specific suitability to the Pokémon.
     */
    default boolean hasSuitability(Pokemon pokemon, Suitability suitability) {
        return getSuitabilities(pokemon).contains(suitability);
    }

    /**
     * Get the level/efficiency of this suitability (1-5, 0 if not present).
     * Higher levels mean better performance at the task.
     */
    default int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        return hasSuitability(pokemon, suitability) ? 1 : 0;
    }
}
