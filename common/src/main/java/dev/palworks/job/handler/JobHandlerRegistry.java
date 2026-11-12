package dev.palworks.job.handler;

import dev.palworks.ai.WorkPositions;
import dev.palworks.ai.WorkStance;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.job.JobType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * Every job's {@link JobHandler}, by {@link JobType}: how a worker does it once it's there.
 */
public final class JobHandlerRegistry {
    private static final Map<JobType, JobHandler> HANDLERS = new EnumMap<>(JobType.class);

    static {
        for (JobType type : JobType.values()) {
            HANDLERS.put(type, create(type));
        }
    }

    private JobHandlerRegistry() {}

    // Exhaustive (no default), so a new JobType doesn't compile until it has a handler
    private static JobHandler create(JobType type) {
        return switch (type) {
            case FUEL_FURNACE -> new FuelFurnaceHandler();
            case GET_SEEDS -> new GetSeedsHandler();
            case COLLECT_DROPS -> new CollectDropsHandler();
            case DELIVER_ITEMS -> new DeliverItemsHandler();
            case PLANT_SEEDS -> new PlantSeedsHandler();
            case HARVEST_CROPS -> new HarvestCropsHandler();
            case WATER_CROPS -> new WaterCropsHandler();
            case CHOP_TREE -> new ChopTreeHandler();
            case EAT_FOOD -> new EatFoodHandler();
            case FETCH_FOOD -> new FetchFoodHandler();
            // Stands 4s but doesn't break the ore yet (mining isn't scanned for until it does)
            case MINE_RESOURCE -> () -> List.of(Step.pause("wait", species -> 80));
        };
    }

    /** How long this job's steps take a worker of this species, all told (once round, for one that repeats). */
    public static int jobTicks(JobType job, Mob worker) {
        String species = WorkAnimations.speciesOf(worker);
        int ticks = 0;
        for (Step step : forJob(job).steps()) {
            ticks += step.ticksFor(species);
        }
        return ticks;
    }

    /** How a worker does this job. */
    public static JobHandler forJob(JobType type) {
        return HANDLERS.get(type);
    }

    /** A job's step, for the debug overlay: its name, which of how many it is, and its length. */
    public record StepView(String name, int index, int count, int ticks) {}

    /** The step at this index of a job's steps, as a worker of this species does it, or null if there's none there. */
    @Nullable
    public static StepView stepView(JobType job, int index, @Nullable String speciesId) {
        List<Step> steps = forJob(job).steps();
        if (index < 0 || index >= steps.size()) {
            return null;
        }
        Step step = steps.get(index);
        return new StepView(step.name(), index, steps.size(), step.ticksFor(speciesId));
    }

    /**
     * Where this worker stands to do this job: the stance its species plays the job's animation
     * from (see {@link JobHandler#stance}). {@link WorkPositions} finds the tile from it.
     */
    public static WorkStance stanceFor(JobType job, Mob worker) {
        return forJob(job).stance(WorkAnimations.speciesOf(worker));
    }
}
