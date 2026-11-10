package dev.palworks.job;

import dev.palworks.suitability.Suitability;
import java.util.Locale;

/**
 * Represents what needs doing NOW.
 * Separate from Suitability to allow flexible job definitions. Only jobs a worker can actually do
 * are listed; add a value together with its handler (WorkerAI) and its scan (JobManager).
 */
public enum JobType {
    // Kindling
    FUEL_FURNACE(Suitability.KINDLING),

    // Planting
    PLANT_SEEDS(Suitability.PLANTING),
    GET_SEEDS(Suitability.PLANTING), // Get seeds from a chest for planting

    // Gathering
    HARVEST_CROPS(Suitability.GATHERING),

    // Lumbering
    CHOP_TREE(Suitability.LUMBERING),

    // Mining (not scanned for yet: see JobManager's MINING_JOBS_ENABLED)
    MINE_RESOURCE(Suitability.MINING),

    // Transporting
    COLLECT_DROPS(Suitability.TRANSPORTING), // Pick up dropped items from the ground
    DELIVER_ITEMS(Suitability.TRANSPORTING); // Deliver carried items to a chest

    private final Suitability requiredSuitability;

    JobType(Suitability requiredSuitability) {
        this.requiredSuitability = requiredSuitability;
    }

    public Suitability getRequiredSuitability() {
        return requiredSuitability;
    }

    public String getId() {
        return name().toLowerCase(Locale.ROOT);
    }
}
