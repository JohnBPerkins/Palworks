package dev.palworks.job.handler;

import dev.palworks.ai.WorkStance;
import dev.palworks.sleep.Sleep;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A pastured worker sleeps in a bed: it lies down on it and sleeps, a long step at a time,
 * for as long as it's its bedtime (see {@link Sleep}), or, napping Tired in the day, until it's slept
 * it off. WorkerAI holds it asleep while it's in the bed and counts the sleep as in one; when its
 * bedtime or nap is over the job ends and it wakes.
 */
final class SleepHandler implements JobHandler {
    /** One stretch of sleep; it sleeps stretch after stretch until its bedtime's over. */
    static final int STRETCH_TICKS = 100;

    private final List<Step> steps = List.of(Step.of("sleep", STRETCH_TICKS, Step.effect(ctx -> {})));

    @Override
    public List<Step> steps() {
        return steps;
    }

    /** On the bed itself, not beside it (see WorkPositions). */
    @Override
    public WorkStance stance(@Nullable String speciesId) {
        return WorkStance.AGAINST;
    }

    /** Another stretch while it's still its bedtime, or its nap isn't over. */
    @Override
    public boolean repeatAfterComplete(JobContext ctx) {
        return ctx.entity() instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity
                && (Sleep.isBedtimeFor(entity, ctx.state()) || Sleep.isNapping(entity, ctx.state()));
    }
}
