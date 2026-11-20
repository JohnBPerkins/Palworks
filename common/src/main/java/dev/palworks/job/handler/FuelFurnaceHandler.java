package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.station.KindlingHeat;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;

/**
 * Lighting a furnace with Ember, so it burns as long as the kindler's level gives (see
 * {@link KindlingHeat}). A level 1 kindler stays and lights it again each smelt while it has more
 * to smelt; one of a higher level lights it and goes, and the furnace going out makes it work again.
 */
final class FuelFurnaceHandler implements JobHandler {

    // One cycle matches one smelting operation: Ember, lighting the furnace as its flames reach
    // it, then wait out the rest of the smelt.
    private static final int CYCLE_TICKS = 200;
    private final Step ember =
            Step.animated("ember", 0, WorkAnimations.IGNITE_FURNACE, Step.effect(FuelFurnaceHandler::ignite));
    private final List<Step> steps = List.of(
            ember,
            // A level 1 kindler waits by its furnace: re-igniting once per cycle keeps it lit across
            // repeats, the 201-tick burn covering the 200-tick gap to the next ignite, however long
            // the species' ember takes
            Step.pause("smelt", species -> Math.max(0, CYCLE_TICKS - ember.ticksFor(species)))
                    .onlyIf(FuelFurnaceHandler::staysAtItsFurnace));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private static int kindlingLevel(JobContext ctx) {
        return SuitabilityDetector.getSuitabilityLevel(ctx.pokemon(), Suitability.KINDLING);
    }

    private static boolean staysAtItsFurnace(JobContext ctx) {
        return KindlingHeat.staysAtItsFurnace(kindlingLevel(ctx));
    }

    private static void ignite(JobContext ctx) {
        int burnDuration = KindlingHeat.burnTicks(kindlingLevel(ctx));
        FurnaceHelper.igniteFurnace(ctx.level(), ctx.pos(), burnDuration);
        ctx.didWork(1);
        Palworks.LOGGER.debug("{} ignited furnace at {} for {} ticks", ctx.name(), ctx.pos(), burnDuration);
    }

    @Override
    public boolean repeatAfterComplete(JobContext ctx) {
        // Keep going only if it stays to relight, and the furnace still has more than the item
        // being smelted
        if (staysAtItsFurnace(ctx) && FurnaceHelper.hasQueuedItems(ctx.level(), ctx.pos())) {
            Palworks.LOGGER.debug("{} continuing - furnace still has items to smelt", ctx.name());
            return true;
        }
        return false;
    }
}
