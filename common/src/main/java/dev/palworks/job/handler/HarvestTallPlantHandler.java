package dev.palworks.job.handler;

import dev.palworks.animation.WorkAnimation;
import dev.palworks.crop.TallPlants;
import dev.palworks.drops.TrackedDrops;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Harvests a tall plant (sugar cane, cactus, bamboo, kelp, nether vines: TallPlants) from its second
 * block, the job's: everything but its base, with HarvestCropsHandler's moves (the nether vines,
 * Nether work, with its Night Shade). One handler class for all their job types.
 */
final class HarvestTallPlantHandler implements JobHandler {

    private final List<Step> steps;

    HarvestTallPlantHandler(WorkAnimation move) {
        steps = List.of(Step.animated("harvest", 20, move, Step.effect(this::work)));
    }

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
        }
    }
}
