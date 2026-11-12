package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.crop.Watering;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * Waters a growing crop (see {@link Watering}): hydrates its farmland and, the higher the worker's
 * Watering level, the likelier it also fertilizes it, as bone meal would.
 */
final class WaterCropsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.animated("water", 20, WorkAnimations.WATER, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        if (!(ctx.level() instanceof ServerLevel level)) {
            return;
        }
        int wateringLevel = SuitabilityDetector.getSuitabilityLevel(ctx.pokemon(), Suitability.WATERING);
        boolean fertilized = Watering.water(level, ctx.pos(), Watering.fertilizeChance(wateringLevel));
        ctx.didWork(1);
        Palworks.LOGGER.debug(
                "{} (Watering {}) watered the crop at {}{}",
                ctx.name(),
                wateringLevel,
                ctx.pos(),
                fertilized ? " and fertilized it" : "");
    }
}
