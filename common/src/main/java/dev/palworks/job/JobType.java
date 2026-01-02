package dev.palworks.job;

import dev.palworks.suitability.Suitability;

/**
 * Represents what needs doing NOW.
 * Separate from Suitability to allow flexible job definitions.
 */
public enum JobType {
    // Kindling jobs
    FUEL_FURNACE(Suitability.KINDLING),
    COOK_FOOD(Suitability.KINDLING),

    // Watering jobs
    WATER_CROPS(Suitability.WATERING),
    POWER_WATER_WHEEL(Suitability.WATERING),

    // Planting jobs
    PLANT_SEEDS(Suitability.PLANTING),

    // Electric jobs
    POWER_DEVICE(Suitability.GENERATING_ELECTRICITY),

    // Handiwork jobs
    CRAFT_ITEM(Suitability.HANDIWORK),
    MANAGE_CONVEYOR(Suitability.HANDIWORK),

    // Gathering jobs
    HARVEST_CROPS(Suitability.GATHERING),
    CARRY_BODY(Suitability.GATHERING),

    // Lumbering jobs
    CHOP_TREE(Suitability.LUMBERING),

    // Mining jobs
    MINE_RESOURCE(Suitability.MINING),

    // Medicine jobs
    BREW_POTION(Suitability.MEDICINE_PRODUCTION),

    // Cooling jobs
    COOL_CONTAINER(Suitability.COOLING),

    // Transporting jobs
    PICKUP_ITEMS(Suitability.TRANSPORTING),
    DELIVER_ITEMS(Suitability.TRANSPORTING),

    // Farming jobs (ranch)
    PRODUCE_RANCH_ITEMS(Suitability.FARMING);

    private final Suitability requiredSuitability;

    JobType(Suitability requiredSuitability) {
        this.requiredSuitability = requiredSuitability;
    }

    public Suitability getRequiredSuitability() {
        return requiredSuitability;
    }

    public String getId() {
        return name().toLowerCase();
    }
}
