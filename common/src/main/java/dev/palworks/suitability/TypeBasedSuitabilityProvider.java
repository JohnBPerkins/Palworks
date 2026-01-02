package dev.palworks.suitability;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.HashSet;
import java.util.Set;

/**
 * Provides suitabilities based on Pokémon types.
 * Maps Cobblemon types to work suitabilities.
 */
public class TypeBasedSuitabilityProvider implements SuitabilityProvider {

    @Override
    public Set<Suitability> getSuitabilities(Pokemon pokemon) {
        Set<Suitability> suitabilities = new HashSet<>();

        for (ElementalType type : pokemon.getTypes()) {
            addSuitabilitiesForType(type, suitabilities);
        }

        return suitabilities;
    }

    @Override
    public int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        if (!hasSuitability(pokemon, suitability)) {
            return 0;
        }

        // Dual-type Pokémon matching both types get a bonus
        int level = 1;
        int typeMatches = 0;

        for (ElementalType type : pokemon.getTypes()) {
            Set<Suitability> typeSuitabilities = new HashSet<>();
            addSuitabilitiesForType(type, typeSuitabilities);
            if (typeSuitabilities.contains(suitability)) {
                typeMatches++;
            }
        }

        // Dual-type bonus
        if (typeMatches >= 2) {
            level = 2;
        }

        return level;
    }

    private void addSuitabilitiesForType(ElementalType type, Set<Suitability> suitabilities) {
        // Fire type → Kindling
        if (type == ElementalTypes.FIRE) {
            suitabilities.add(Suitability.KINDLING);
        }

        // Water type → Watering
        if (type == ElementalTypes.WATER) {
            suitabilities.add(Suitability.WATERING);
        }

        // Grass type → Planting and Gathering
        if (type == ElementalTypes.GRASS) {
            suitabilities.add(Suitability.PLANTING);
            suitabilities.add(Suitability.GATHERING);
        }

        // Electric type → Generating Electricity
        if (type == ElementalTypes.ELECTRIC) {
            suitabilities.add(Suitability.GENERATING_ELECTRICITY);
        }

        // Rock/Ground types → Mining
        if (type == ElementalTypes.ROCK || type == ElementalTypes.GROUND) {
            suitabilities.add(Suitability.MINING);
        }

        // Psychic type → Medicine Production
        if (type == ElementalTypes.PSYCHIC) {
            suitabilities.add(Suitability.MEDICINE_PRODUCTION);
        }

        // Ice type → Cooling
        if (type == ElementalTypes.ICE) {
            suitabilities.add(Suitability.COOLING);
        }

        // Fighting/Steel types → Lumbering (strong physical types)
        if (type == ElementalTypes.FIGHTING || type == ElementalTypes.STEEL) {
            suitabilities.add(Suitability.LUMBERING);
        }

        // Flying/Psychic types → Transporting (can carry/teleport items)
        if (type == ElementalTypes.FLYING || type == ElementalTypes.PSYCHIC) {
            suitabilities.add(Suitability.TRANSPORTING);
        }

        // Ghost type → Nether (work with nether-related tasks)
        if (type == ElementalTypes.GHOST) {
            suitabilities.add(Suitability.NETHER);
        }

        // Normal type → Farming (general-purpose ranch drops)
        if (type == ElementalTypes.NORMAL) {
            suitabilities.add(Suitability.FARMING);
        }
    }
}
