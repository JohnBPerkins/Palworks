package dev.palworks.suitability;

/**
 * Represents what a Pokémon CAN do.
 * Its levels come from the datapacks' suitability files (see SuitabilityData), named there, in
 * commands and in the overrides file by {@link #getId}. Some have no jobs yet.
 */
public enum Suitability {
    KINDLING("kindling"), // Light furnaces
    WATERING("watering"), // Water crops
    PLANTING("planting"), // Plant seeds in farms
    GENERATING_ELECTRICITY("generating_electricity"), // Power electric devices (no jobs yet)
    HANDIWORK("handiwork"), // Crafting (no jobs yet)
    GATHERING("gathering"), // Harvest crops
    LUMBERING("lumbering"), // Cut down trees
    MINING("mining"), // Mine crystals (ores: not yet)
    MEDICINE_PRODUCTION("medicine_production"), // Brewing (no jobs yet)
    COOLING("cooling"), // Cool down containers (no jobs yet)
    TRANSPORTING("transporting"), // Move items into storage
    NETHER("nether"), // Nether wart, and crimson and warped trees
    FARMING("farming"); // Drop items when in ranch (no jobs yet)

    private final String id;

    Suitability(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static Suitability fromId(String id) {
        for (Suitability suitability : values()) {
            if (suitability.id.equals(id)) {
                return suitability;
            }
        }
        return null;
    }
}
