package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.drops.TrackedDrops;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

final class HarvestCropsHandler implements JobHandler {

    // Nether wart is Nether work, so it's harvested with Nether work's move: one of these steps is
    // played, by what's there
    private final List<Step> steps = List.of(
            Step.animated("harvest", 20, WorkAnimations.HARVEST, Step.effect(this::work))
                    .onlyIf(ctx -> !isWart(ctx)),
            Step.animated("harvest wart", 20, WorkAnimations.NETHER_HARVEST, Step.effect(this::work))
                    .onlyIf(HarvestCropsHandler::isWart));

    private static boolean isWart(JobContext ctx) {
        return ctx.level().getBlockState(ctx.pos()).is(Blocks.NETHER_WART);
    }

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos harvestPos = ctx.pos();
        // What it drops is the worker's, for transporters to pick up (see TrackedDrops)
        boolean harvested = TrackedDrops.breaking(
                ctx.pokemon().getUuid(),
                harvestPos,
                () -> dev.palworks.crop.CropAdapter.harvestCrop(ctx.level(), harvestPos));
        if (harvested) {
            ctx.didWork(1);
        } else {
            Palworks.LOGGER.debug("{} failed to harvest crop at {}", ctx.name(), harvestPos);
        }
    }
}
