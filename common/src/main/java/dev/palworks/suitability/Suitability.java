package dev.palworks.suitability;

/**
 * Represents what a Pokémon CAN do.
 * Stored as tags in data to allow additions without breaking saves.
 */
public enum Suitability {
    KINDLING("kindling"), // Power furnaces
    WATERING("watering"), // Water crops, power water wheels
    PLANTING("planting"), // Plant seeds in farms
    GENERATING_ELECTRICITY("generating_electricity"), // Power electric devices
    HANDIWORK("handiwork"), // Manage crafting benches
    GATHERING("gathering"), // Harvest crops
    LUMBERING("lumbering"), // Cut down trees
    MINING("mining"), // Mine rocks
    MEDICINE_PRODUCTION("medicine_production"), // Create medicine at workbenches
    COOLING("cooling"), // Cool down containers
    TRANSPORTING("transporting"), // Move items into storage
    NETHER("nether"), // Work with nether-related tasks (ghost types)
    FARMING("farming"); // Drop items when in ranch

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
