package dev.palworks.suitability;

import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.*;

/**
 * Provides suitabilities based on specific Pokémon species.
 * Handles special cases like "has hands" for Handiwork.
 */
public class SpeciesBasedSuitabilityProvider implements SuitabilityProvider {

    // Species that have hands and can use crafting tables
    private static final Set<String> HANDIWORK_SPECIES = new HashSet<>(Arrays.asList(
        "machop", "machoke", "machamp",
        "timburr", "gurdurr", "conkeldurr",
        "meowth", "persian",
        "aipom", "ambipom",
        "mankey", "primeape", "annihilape",
        "pangoro", "pancham",
        "infernape", "monferno",
        "lucario", "riolu",
        "sawk", "throh",
        "hariyama", "makuhita",
        "hitmonlee", "hitmonchan", "hitmontop",
        "slaking", "vigoroth", "slakoth"
    ));

    // Species particularly good at lumbering (beyond Fighting/Steel types)
    private static final Set<String> LUMBERING_SPECIES = new HashSet<>(Arrays.asList(
        "treecko", "grovyle", "sceptile",
        "pinsir", "heracross", "scyther", "scizor",
        "durant", "escavalier",
        "kartana",
        "trevenant", "phantump"
    ));

    // Species that can produce special ranch items
    private static final Set<String> FARMING_SPECIES = new HashSet<>(Arrays.asList(
        "miltank",  // Moomoo Milk
        "wooloo", "dubwool",  // Wool
        "mareep", "flaaffy", "ampharos",  // Wool
        "combee", "vespiquen",  // Honey
        "tropius",  // Berries
        "shuckle",  // Berry Juice
        "chansey", "blissey"  // Eggs
    ));

    // Species good at gathering (berry pickers, etc.)
    private static final Set<String> GATHERING_SPECIES = new HashSet<>(Arrays.asList(
        "tropius", "exeggutor", "shiftry",
        "cherubi", "cherrim",
        "bounsweet", "steenee", "tsareena"
    ));

    @Override
    public Set<Suitability> getSuitabilities(Pokemon pokemon) {
        Set<Suitability> suitabilities = new HashSet<>();
        String speciesName = pokemon.getSpecies().getName().toLowerCase();

        // Handiwork - species with hands
        if (HANDIWORK_SPECIES.contains(speciesName)) {
            suitabilities.add(Suitability.HANDIWORK);
        }

        // Lumbering - specialized tree cutters
        if (LUMBERING_SPECIES.contains(speciesName)) {
            suitabilities.add(Suitability.LUMBERING);
        }

        // Farming - specialized ranch drops
        if (FARMING_SPECIES.contains(speciesName)) {
            suitabilities.add(Suitability.FARMING);
        }

        // Gathering - berry specialists
        if (GATHERING_SPECIES.contains(speciesName)) {
            suitabilities.add(Suitability.GATHERING);
        }

        return suitabilities;
    }

    @Override
    public int getSuitabilityLevel(Pokemon pokemon, Suitability suitability) {
        if (!hasSuitability(pokemon, suitability)) {
            return 0;
        }

        String speciesName = pokemon.getSpecies().getName().toLowerCase();

        // Specialized species get higher levels
        switch (suitability) {
            case HANDIWORK:
                // Machamp line are exceptional at handiwork
                if (speciesName.equals("machamp")) return 3;
                if (speciesName.equals("machoke")) return 2;
                return 1;

            case LUMBERING:
                // Scizor/Scyther are exceptional tree cutters
                if (speciesName.equals("scizor") || speciesName.equals("kartana")) return 3;
                if (speciesName.equals("scyther") || speciesName.equals("pinsir")) return 2;
                return 1;

            case FARMING:
                // Miltank and Chansey are exceptional producers
                if (speciesName.equals("miltank") || speciesName.equals("blissey")) return 3;
                if (speciesName.equals("chansey")) return 2;
                return 1;

            case GATHERING:
                // Tropius is exceptional at gathering
                if (speciesName.equals("tropius")) return 3;
                return 1;

            default:
                return 1;
        }
    }

    /**
     * Add a species to a suitability set.
     * Useful for runtime additions via data or config.
     */
    public static void addSpeciesSuitability(String speciesName, Suitability suitability) {
        speciesName = speciesName.toLowerCase();
        switch (suitability) {
            case HANDIWORK -> HANDIWORK_SPECIES.add(speciesName);
            case LUMBERING -> LUMBERING_SPECIES.add(speciesName);
            case FARMING -> FARMING_SPECIES.add(speciesName);
            case GATHERING -> GATHERING_SPECIES.add(speciesName);
        }
    }
}
