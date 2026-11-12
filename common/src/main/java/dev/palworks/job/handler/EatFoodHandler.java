package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.ai.WorkStance;
import dev.palworks.block.TroughBlockEntity;
import dev.palworks.hunger.Hunger;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A hungry worker eats from a trough: a point of feed a bite, each taking {@link #EAT_TICKS}, for a
 * point of fullness, until it's as full as troughs fill it (see {@link Hunger#troughCap}) or the
 * trough (and those joined to it) run out. While it eats it holds the last food put in and drops
 * crumbs (see dev.palworks.hunger.EatingDisplay). Feed only gives fullness: the food's own effects
 * (EVs, friendship, healing) never happen.
 */
final class EatFoodHandler implements JobHandler {
    /** How long eating one item takes. */
    static final int EAT_TICKS = 50;

    // All or nothing: the feed leaves the trough as it's eaten, at the end, so a worker called
    // away mid-bite (a battle, its work switched off) hasn't used any up
    private final List<Step> steps = List.of(Step.of("eat", EAT_TICKS, EatFoodHandler::eat));

    @Override
    public List<Step> steps() {
        return steps;
    }

    /**
     * Right up against the trough, as a lumberjack stands at a trunk, so it eats with its head over
     * the food (it has no animation to take its stance from).
     */
    @Override
    public WorkStance stance(@Nullable String speciesId) {
        return WorkStance.AGAINST;
    }

    private static boolean eat(JobContext ctx) {
        if (!(ctx.level().getBlockEntity(ctx.pos()) instanceof TroughBlockEntity trough) || !trough.takeBite()) {
            return false;
        }
        Hunger.feed(ctx.pokemon(), 1);
        Palworks.LOGGER.debug(
                "{} ate from the trough at {} (fullness {}/{}, {} feed left there)",
                ctx.name(),
                ctx.pos(),
                ctx.pokemon().getCurrentFullness(),
                ctx.pokemon().getMaxFullness(),
                trough.feed());
        return true;
    }

    /** Another bite while it's still hungry and there's feed left. */
    @Override
    public boolean repeatAfterComplete(JobContext ctx) {
        return Hunger.wantsFood(ctx.pokemon())
                && ctx.level().getBlockEntity(ctx.pos()) instanceof TroughBlockEntity trough
                && trough.hasFeed();
    }
}
