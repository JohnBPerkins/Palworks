package dev.palworks.suitability;

import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.*;

/**
 * Detects which suitabilities a Pokémon has based on its species, types, and other properties.
 * Uses a pluggable provider system for extensibility.
 */
public class SuitabilityDetector {
    private static final List<SuitabilityProvider> providers = new ArrayList<>();

    static {
        // Register default type-based provider
        providers.add(new TypeBasedSuitabilityProvider());
        // Register species-specific provider
        providers.add(new SpeciesBasedSuitabilityProvider());
    }

    /**
     * Register a custom suitability provider.
     * Providers are checked in registration order.
     */
    public static void registerProvider(SuitabilityProvider provider) {
        providers.add(provider);
    }

    /**
     * Get all suitabilities for a Pokémon.
     */
    public static Set<Suitability> getSuitabilities(Pokemon pokemon) {
        Set<Suitability> suitabilities = new HashSet<>();

        for (SuitabilityProvider provider : providers) {
            suitabilities.addAll(provider.getSuitabilities(pokemon));
        }

        return suitabilities;
    }

    /**
     * Check if a Pokémon has a specific suitability.
     */
    public static boolean hasSuitability(Pokemon pokemon, Suitability suitability) {
        for (SuitabilityProvider provider : providers) {
            if (provider.hasSuitability(pokemon, suitability)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get suitability level/efficiency for a Pokémon (1-5, higher is better).
     * Default is 1. Dual-type matches or special species can return higher values.
     */
    public static int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        int maxLevel = 0;

        for (SuitabilityProvider provider : providers) {
            int level = provider.getSuitabilityLevel(pokemon, suitability);
            maxLevel = Math.max(maxLevel, level);
        }

        return maxLevel;
    }

    /**
     * Clear all providers (useful for testing or reloading).
     */
    public static void clearProviders() {
        providers.clear();
    }

    /**
     * Reset to default providers.
     */
    public static void resetToDefaults() {
        providers.clear();
        providers.add(new TypeBasedSuitabilityProvider());
        providers.add(new SpeciesBasedSuitabilityProvider());
    }
}
