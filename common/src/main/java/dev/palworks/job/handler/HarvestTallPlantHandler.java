package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.crop.TallPlants;
import dev.palworks.drops.TrackedDrops;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Harvests a tall plant (sugar cane, cactus, bamboo, kelp, nether vines: TallPlants) from its second
 * block, the job's: everything but its base, with HarvestCropsHandler's move. One handler for all
 * their job types.
 */
final class HarvestTallPlantHandler implements JobHandler {

    private final List<Step> steps =
            List.of(Step.animated("harvest", 20, WorkAnimations.HARVEST, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos pos = ctx.pos();
        // What it drops is the worker's, for transporters to pick up (see TrackedDrops)
        boolean cut = TrackedDrops.breaking(
                ctx.pokemon().getUuid(), pos, () -> TallPlants.harvest(ctx.level(), pos, ctx.entity()) > 0);
        if (cut) {
            ctx.didWork(1);
            Palworks.LOGGER.debug("{} harvested a plant at {}", ctx.name(), pos);
        }
    }
}
