package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.drops.TrackedDrops;
import java.util.List;
import net.minecraft.core.BlockPos;

final class HarvestCropsHandler implements JobHandler {

    private final List<Step> steps =
            List.of(Step.animated("harvest", 20, WorkAnimations.HARVEST, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos harvestPos = ctx.pos();
        // What it drops is the worker's, for transporters to pick up (see TrackedDrops)
        boolean harvested = TrackedDrops.breaking(
                ctx.pokemon().getUuid(), () -> dev.palworks.crop.CropAdapter.harvestCrop(ctx.level(), harvestPos));
        if (harvested) {
            ctx.didWork(1);
            Palworks.LOGGER.debug("{} harvested crop at {}", ctx.name(), harvestPos);
        } else {
            Palworks.LOGGER.debug("{} failed to harvest crop at {}", ctx.name(), harvestPos);
        }
    }
}
