package dev.palworks.suitability;

import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Detects which suitabilities a Pokémon has. By default they come from datapack files
 * ({@link DatapackSuitabilityProvider}, see {@link SuitabilityData} for the format); other mods can
 * add providers of their own.
 */
public class SuitabilityDetector {
    private static final List<SuitabilityProvider> providers = new ArrayList<>();

    static {
        providers.add(new DatapackSuitabilityProvider());
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
        Set<Suitability> suitabilities = EnumSet.noneOf(Suitability.class);

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
     * 0 if it doesn't have it.
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
        providers.add(new DatapackSuitabilityProvider());
    }
}
