package dev.palworks.job;

import dev.palworks.suitability.Suitability;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Represents what needs doing NOW.
 * Separate from Suitability to allow flexible job definitions. Only jobs a worker can actually do
 * are listed; add a value together with its handler (JobHandlerRegistry) and how it's found
 * (JobScanner, or JobAssigner for jobs made per worker, like deliveries).
 */
public enum JobType {
    // Priorities: 0-10, higher is taken first (see JobManager's job scoring)

    // Kindling
    FUEL_FURNACE(Suitability.KINDLING, 7),

    // Planting
    PLANT_SEEDS(Suitability.PLANTING, 5),
    // Get seeds from a chest for planting: farmland seeds for Planting, nether wart for Nether work.
    // No one skill, as what a worker takes depends on its (see JobAssigner.seedToFetch)
    GET_SEEDS(null, 6),

    // Gathering
    HARVEST_CROPS(Suitability.GATHERING, 8),

    // Watering: upkeep, never urgent, so a worker that does other work too does that first
    WATER_CROPS(Suitability.WATERING, 3),

    // Lumbering
    CHOP_TREE(Suitability.LUMBERING, 4),

    // Mining (not scanned for yet: see JobManager's MINING_JOBS_ENABLED)
    MINE_RESOURCE(Suitability.MINING, 7),

    // Transporting
    COLLECT_DROPS(Suitability.TRANSPORTING, 9), // Pick up dropped items from the ground
    DELIVER_ITEMS(Suitability.TRANSPORTING, 10), // Deliver carried items to a chest: before anything else
    // Fetch food from a chest for a trough running low (then delivered there like any cargo)
    FETCH_FOOD(Suitability.TRANSPORTING, 9),

    // Not work: a hungry worker eats from a trough (see dev.palworks.hunger.Hunger). Any worker
    // does it, whatever its skills, and before any work (JobAssigner hands it out first).
    EAT_FOOD(null, 10),

    // Not work either: a pastured worker goes to a bed to sleep at its bedtime (see
    // dev.palworks.sleep.Sleep). JobAssigner hands it out, and nothing else, at bedtime.
    SLEEP(null, 10);

    @Nullable
    private final Suitability requiredSuitability;

    private final int priority;

    JobType(@Nullable Suitability requiredSuitability, int priority) {
        this.requiredSuitability = requiredSuitability;
        this.priority = priority;
    }

    /** How soon a worker takes this job over others: 0-10, higher first. */
    public int priority() {
        return priority;
    }

    /** The work skill this job needs, or null for one it doesn't decide alone (eating, sleeping, seeds). */
    @Nullable
    public Suitability getRequiredSuitability() {
        return requiredSuitability;
    }

    /** The work skills this job needs: its own, or none. */
    public java.util.Set<Suitability> requiredSuitabilities() {
        return requiredSuitability == null
                ? java.util.EnumSet.noneOf(Suitability.class)
                : java.util.EnumSet.of(requiredSuitability);
    }

    public String getId() {
        return name().toLowerCase(Locale.ROOT);
    }
}
