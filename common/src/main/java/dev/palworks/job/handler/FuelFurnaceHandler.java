package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import java.util.List;

final class FuelFurnaceHandler implements JobHandler {
    private static final boolean DEBUG = Palworks.DEBUG;

    // One cycle matches one smelting operation: Ember, lighting the furnace as its flames reach
    // it, then wait out the rest of the smelt.
    private static final int CYCLE_TICKS = 200;
    private final Step ember =
            Step.animated("ember", 0, WorkAnimations.IGNITE_FURNACE, Step.effect(FuelFurnaceHandler::ignite));
    private final List<Step> steps = List.of(
            ember,
            // Re-igniting once per cycle keeps it lit across repeats: the 201-tick burn covers the
            // 200-tick gap to the next ignite, however long the species' ember takes.
            Step.pause("smelt", species -> Math.max(0, CYCLE_TICKS - ember.ticksFor(species))));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private static void ignite(JobContext ctx) {
        int burnDuration = dev.palworks.station.FurnaceHelper.getBurnDuration();
        dev.palworks.station.FurnaceHelper.igniteFurnace(ctx.level(), ctx.pos(), burnDuration);
        if (DEBUG) {
            Palworks.LOGGER.info("{} ignited furnace at {} for {} ticks", ctx.name(), ctx.pos(), burnDuration);
        }
    }

    @Override
    public boolean repeatAfterComplete(JobContext ctx) {
        // Keep going only if the furnace still has more than the item being smelted.
        if (dev.palworks.station.FurnaceHelper.hasQueuedItems(ctx.level(), ctx.pos())) {
            if (DEBUG) {
                Palworks.LOGGER.info("{} continuing - furnace still has items to smelt", ctx.name());
            }
            return true;
        }
        return false;
    }
}
