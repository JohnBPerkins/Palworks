package dev.palworks.job.handler;

import dev.palworks.animation.WorkAnimations;
import dev.palworks.station.BrewingStandHelper;
import dev.palworks.station.KindlingHeat;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;

/**
 * Lighting a brewing stand with Ember, in place of blaze powder: it gets as many brews as the
 * kindler's level gives (see {@link KindlingHeat#brewsPerEmber}). A level 1 kindler stays and lights
 * it again after each brew while it has more to brew; one of a higher level lights it and goes, and
 * the stand running out of charges with more to brew makes it work again.
 */
final class FuelBrewingStandHandler implements JobHandler {

    // A brew takes 400 ticks from the stand's next tick after it's lit; a couple more so it's done,
    // and the stand idle, by the time a level 1 kindler looks to light it again
    private static final int BREW_TICKS = 402;
    private final List<Step> steps = List.of(
            // The furnace's Ember, aimed low: at the stand's base, where blaze powder would burn
            Step.animated("ember", 0, WorkAnimations.IGNITE_FURNACE, Step.effect(FuelBrewingStandHandler::light)),
            Step.pause("brew", species -> BREW_TICKS).onlyIf(FuelBrewingStandHandler::staysToRelight));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private static int kindlingLevel(JobContext ctx) {
        return SuitabilityDetector.getSuitabilityLevel(ctx.pokemon(), Suitability.KINDLING);
    }

    private static boolean staysToRelight(JobContext ctx) {
        return KindlingHeat.staysToRelight(kindlingLevel(ctx));
    }

    private static void light(JobContext ctx) {
        int brews = KindlingHeat.brewsPerEmber(kindlingLevel(ctx));
        BrewingStandHelper.charge(ctx.level(), ctx.pos(), brews);
        ctx.didWork(1);
    }

    @Override
    public boolean repeatAfterComplete(JobContext ctx) {
        // Its brew done, a level 1 kindler goes on while what the stand holds now brews into more
        return staysToRelight(ctx) && BrewingStandHelper.needsCharge(ctx.level(), ctx.pos());
    }
}
