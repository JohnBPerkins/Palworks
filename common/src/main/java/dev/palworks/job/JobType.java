package dev.palworks.job;

import dev.palworks.suitability.Suitability;
import java.util.Locale;

/**
 * Represents what needs doing NOW.
 * Separate from Suitability to allow flexible job definitions. Only jobs a worker can actually do
 * are listed; add a value together with its handler (WorkerAI) and its scan (JobManager).
 */
public enum JobType {
    // Priorities: 0-10, higher is taken first (see JobManager's job scoring)

    // Kindling
    FUEL_FURNACE(Suitability.KINDLING, 7),

    // Planting
    PLANT_SEEDS(Suitability.PLANTING, 5),
    GET_SEEDS(Suitability.PLANTING, 6), // Get seeds from a chest for planting

    // Gathering
    HARVEST_CROPS(Suitability.GATHERING, 8),

    // Lumbering
    CHOP_TREE(Suitability.LUMBERING, 4),

    // Mining (not scanned for yet: see JobManager's MINING_JOBS_ENABLED)
    MINE_RESOURCE(Suitability.MINING, 7),

    // Transporting
    COLLECT_DROPS(Suitability.TRANSPORTING, 9), // Pick up dropped items from the ground
    DELIVER_ITEMS(Suitability.TRANSPORTING, 10); // Deliver carried items to a chest: before anything else

    private final Suitability requiredSuitability;
    private final int priority;

    JobType(Suitability requiredSuitability, int priority) {
        this.requiredSuitability = requiredSuitability;
        this.priority = priority;
    }

    /** How soon a worker takes this job over others: 0-10, higher first. */
    public int priority() {
        return priority;
    }

    public Suitability getRequiredSuitability() {
        return requiredSuitability;
    }

    public String getId() {
        return name().toLowerCase(Locale.ROOT);
    }
}
